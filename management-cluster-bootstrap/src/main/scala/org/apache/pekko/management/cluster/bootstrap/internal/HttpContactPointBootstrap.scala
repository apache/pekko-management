/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.management.cluster.bootstrap.internal

import java.time.LocalDateTime
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import scala.concurrent.{ Future, Promise }
import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.Actor
import pekko.actor.ActorLogging
import pekko.actor.ActorSystem
import pekko.actor.DeadLetterSuppression
import pekko.actor.Props
import pekko.actor.Status
import pekko.actor.Timers
import pekko.annotation.InternalApi
import pekko.cluster.Cluster
import pekko.discovery.ServiceDiscovery.ResolvedTarget
import pekko.http.scaladsl.ConnectionContext
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.HttpsConnectionContext
import pekko.http.scaladsl.model.HttpResponse
import pekko.http.scaladsl.model.StatusCodes
import pekko.http.scaladsl.model.Uri
import pekko.http.scaladsl.model.Uri.Host
import pekko.http.scaladsl.model.headers.BasicHttpCredentials
import pekko.http.scaladsl.settings.ConnectionPoolSettings
import pekko.http.scaladsl.unmarshalling.Unmarshal
import pekko.management.cluster.bootstrap.ClusterBootstrapSettings
import pekko.management.cluster.bootstrap.contactpoint.HttpBootstrapJsonProtocol.SeedNodes
import pekko.management.cluster.bootstrap.contactpoint.{ ClusterBootstrapRequests, HttpBootstrapJsonProtocol }
import pekko.pattern.pipe
import pekko.pki.kubernetes.PemManagersProvider

@InternalApi
private[bootstrap] object HttpContactPointBootstrap {

  def name(host: Host, port: Int): String = {
    val ValidSymbols = """-_.*$+:@&=,!~';"""
    val cleanHost = host.address.filter(c =>
      (c >= 'a' && c <= 'z') ||
      (c >= 'A' && c <= 'Z') ||
      (c >= '0' && c <= '9') ||
      (ValidSymbols.indexOf(c) != -1))
    s"contactPointProbe-$cleanHost-$port"
  }
  def props(settings: ClusterBootstrapSettings, contactPoint: ResolvedTarget, baseUri: Uri): Props =
    Props(new HttpContactPointBootstrap(settings, contactPoint, baseUri))

  private case object ProbeTick extends DeadLetterSuppression
  private val ProbingTimerKey = "probing-key"
  private val DefaultTlsVersion = "TLSv1.2" // keep in sync with default in reference.conf

  def basicAuthCredentials(settings: ClusterBootstrapSettings): Option[BasicHttpCredentials] =
    settings.contactPoint.httpClient.basicAuthUsername.map { username =>
      BasicHttpCredentials(username, settings.contactPoint.httpClient.basicAuthPassword)
    }

  /**
   * `false` when basic auth credentials are configured and probing over `scheme` would send them in the clear,
   * without `basic-auth.allow-insecure-http` having been enabled.
   */
  def mayProbeWithScheme(settings: ClusterBootstrapSettings, scheme: String): Boolean =
    settings.contactPoint.httpClient.basicAuthUsername.isEmpty ||
    scheme == "https" ||
    settings.contactPoint.httpClient.basicAuthAllowInsecureHttp

  val InsecureBasicAuthMessage: String =
    "pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth is configured, but the contact " +
    "points are not probed over https, so the credentials would be sent in the clear. Serve Pekko Management " +
    "over HTTPS, or set basic-auth.allow-insecure-http = on if the connections are protected in another way."

  /**
   * How long a probe request that is about to be sent may take: `probingRequestTimeout`, but never longer than
   * `timeLeft`, which is what is left of the probing failure timeout. `None` when there is no time left, in
   * which case no request should be sent at all.
   */
  def probeRequestTimeout(probingRequestTimeout: FiniteDuration, timeLeft: FiniteDuration): Option[FiniteDuration] =
    if (timeLeft <= Duration.Zero) None
    else Some(probingRequestTimeout.min(timeLeft))

  def generateSSLContext(settings: ClusterBootstrapSettings): SSLContext = {
    val caPath = settings.contactPoint.httpClient.caPath.trim
    // an empty ca-path means use the default JVM trust store
    PemManagersProvider.createSslContext(if (caPath.nonEmpty) Some(caPath) else None)
  }

  def generateClientConnectionContext(settings: ClusterBootstrapSettings): HttpsConnectionContext = {
    val sslContext = generateSSLContext(settings)
    ConnectionContext.httpsClient(
      PemManagersProvider.clientEngineFactory(sslContext, settings.contactPoint.httpClient.minTlsVersion))
  }

  /**
   * The connection context that https probes use instead of the default client https context
   * of the HTTP extension, if there is one.
   *
   * With the shipped defaults (no `ca-path` and the default `tls-version`) this is `None` and
   * the probes use `Http().defaultClientHttpsContext`, which the application may have replaced
   * with `Http().setDefaultClientHttpsContext`. That context then takes precedence: it decides
   * what is trusted and which TLS versions are enabled, and the configured minimum is not
   * applied to it. Pekko HTTP offers no way to narrow the protocols of an existing
   * `HttpsConnectionContext` without replacing its trust settings.
   *
   * Setting a `ca-path`, or a `tls-version` other than the default, gives the probes a context
   * of their own, restricted to the configured minimum TLS version.
   */
  def customClientConnectionContext(settings: ClusterBootstrapSettings): Option[HttpsConnectionContext] =
    if (settings.contactPoint.httpClient.caPath.trim.nonEmpty ||
      settings.contactPoint.httpClient.minTlsVersion.trim != DefaultTlsVersion)
      Some(generateClientConnectionContext(settings))
    else None
}

/**
 * Intended to be spawned as child actor by a higher-level Bootstrap coordinator that manages obtaining of the URIs.
 *
 * This additional step may at-first seem superficial -- after all, we already have some addresses of the nodes
 * that we'll want to join -- however it is not optional. By communicating with the actual nodes before joining their
 * cluster we're able to inquire about their status, double-check if perhaps they are part of an existing cluster already
 * that we should join, or even coordinate rolling upgrades or more advanced patterns.
 */
@InternalApi
private[bootstrap] class HttpContactPointBootstrap(
    settings: ClusterBootstrapSettings,
    contactPoint: ResolvedTarget,
    baseUri: Uri) extends Actor
    with ActorLogging
    with Timers
    with HttpBootstrapJsonProtocol {

  import HttpContactPointBootstrap._

  private val cluster = Cluster(context.system)

  if (baseUri.authority.host.address() == cluster.selfAddress.host.getOrElse("---") &&
    baseUri.authority.port == cluster.selfAddress.port.getOrElse(-1)) {
    throw new IllegalArgumentException(
      "Requested base Uri to be probed matches local remoting address, bailing out! " +
      s"Uri: $baseUri, this node's remoting address: ${cluster.selfAddress}")
  }

  // the coordinator does not get this far with such settings, this makes sure that no other caller does either
  if (!mayProbeWithScheme(settings, baseUri.scheme))
    throw new IllegalArgumentException(s"$InsecureBasicAuthMessage Uri: $baseUri")

  private implicit val sys: ActorSystem = context.system

  // None means that the default client https context of the HTTP extension is used
  private lazy val customClientSslContext: Option[HttpsConnectionContext] =
    customClientConnectionContext(settings)

  private val http = Http()

  private val connectionPoolWithoutRetries = ConnectionPoolSettings(context.system).withMaxRetries(0)
  import context.dispatcher

  private val probeInterval = settings.contactPoint.probeInterval
  private val probeRequest = {
    val request = ClusterBootstrapRequests.bootstrapSeedNodes(baseUri)
    basicAuthCredentials(settings) match {
      case Some(credentials) => request.addCredentials(credentials)
      case None              => request
    }
  }

  /**
   * If probing keeps failing until the deadline triggers, we notify the parent,
   * such that it rediscover again.
   */
  private var probingKeepFailingDeadline: Deadline = settings.contactPoint.probingFailureTimeout.fromNow

  private def resetProbingKeepFailingWithinDeadline(): Unit =
    probingKeepFailingDeadline = settings.contactPoint.probingFailureTimeout.fromNow

  /** Why the most recent probe failed, if it did. Reported to the parent when probing is given up. */
  private var lastProbeFailure: Option[Throwable] = None

  override def preStart(): Unit =
    self ! ProbeTick

  override def receive = {
    case ProbeTick =>
      probeRequestTimeout(settings.contactPoint.probingRequestTimeout, probingKeepFailingDeadline.timeLeft) match {
        case Some(requestTimeout) => probe(requestTimeout)
        case None                 =>
          // The failure deadline passed while waiting for this tick. A request sent now could only complete
          // after the deadline, so none is sent.
          probingFailed(lastProbeFailure.getOrElse(new TimeoutException(s"Probing timeout of [$baseUri]")))
      }

    case Status.Failure(cause) =>
      log.warning("Probing [{}] failed due to: {}", probeRequest.uri, cause.getMessage)
      lastProbeFailure = Some(cause)
      if (probingKeepFailingDeadline.isOverdue()) {
        probingFailed(cause)
      } else {
        // keep probing, hoping the request will eventually succeed
        scheduleNextContactPointProbing()
      }

    case response: SeedNodes =>
      notifyParentAboutSeedNodes(response)
      lastProbeFailure = None
      resetProbingKeepFailingWithinDeadline()
      // we keep probing and looking if maybe a cluster does form after all
      // (technically could be long polling or web-sockets, but that would need reconnect logic, so this is simpler)
      scheduleNextContactPointProbing()
  }

  private def probingFailed(cause: Throwable): Unit = {
    log.error("Overdue of probing-failure-timeout, stop probing, signaling that it's failed")
    context.parent ! BootstrapCoordinator.Protocol.ProbingFailed(contactPoint, cause)
    context.stop(self)
  }

  /**
   * Sends one probe request, which fails when it has not completed within `requestTimeout`.
   */
  private def probe(requestTimeout: FiniteDuration): Unit = {
    log.debug("Probing [{}] for seed nodes...", probeRequest.uri)
    val customSslContext = if (probeRequest.uri.scheme == "https") customClientSslContext else None
    val response = customSslContext match {
      case Some(connectionContext) =>
        http.singleRequest(probeRequest, settings = connectionPoolWithoutRetries,
          connectionContext = connectionContext)
      case None =>
        http.singleRequest(probeRequest, settings = connectionPoolWithoutRetries)
    }
    // Use a Promise-based pattern instead of Future.firstCompletedOf so that when the
    // timeout fires first, we properly discard the in-flight response entity to release
    // the HTTP connection back to the pool.
    val promise = Promise[SeedNodes]()
    // The request timeout is normally shorter than probingFailureTimeout: the failure deadline started before
    // this request was sent, so a request that only fails at probingFailureTimeout would never be retried.
    // It is also never longer than what is left of probingFailureTimeout, so that a request cannot outlast
    // the failure deadline.
    val timeoutCancellable = context.system.scheduler.scheduleOnce(requestTimeout) {
      promise.tryFailure(new TimeoutException(s"Probing timeout of [$baseUri]"))
    }
    response.flatMap(handleResponse).onComplete {
      case scala.util.Success(seedNodes) =>
        timeoutCancellable.cancel()
        promise.trySuccess(seedNodes)
      case scala.util.Failure(ex) =>
        timeoutCancellable.cancel()
        promise.tryFailure(ex)
    }(context.dispatcher)
    // If timeout fires first, discard the in-flight response to release the connection
    promise.future.failed.foreach { _ =>
      response.foreach { resp =>
        resp.discardEntityBytes()(pekko.stream.Materializer.matFromSystem(context.system))
      }(context.dispatcher)
    }(context.dispatcher)
    promise.future.pipeTo(self)
  }

  private def handleResponse(response: HttpResponse): Future[SeedNodes] = {
    val strictEntity = response.entity.toStrict(1.second)

    if (response.status == StatusCodes.OK)
      strictEntity.flatMap(res => Unmarshal(res).to[SeedNodes])
    else
      strictEntity.flatMap { entity =>
        val body = entity.data.utf8String
        Future.failed(
          new IllegalStateException(s"Expected response '200 OK' but found ${response.status}. Body: '$body'"))
      }
  }

  private def notifyParentAboutSeedNodes(members: SeedNodes): Unit = {
    val seedAddresses = members.seedNodes.map(_.node)
    context.parent ! BootstrapCoordinator.Protocol.ObtainedHttpSeedNodesObservation(
      timeNow(),
      contactPoint,
      members.selfNode,
      seedAddresses)
  }

  private def scheduleNextContactPointProbing(): Unit =
    timers.startSingleTimer(ProbingTimerKey, ProbeTick, effectiveProbeInterval())

  /** Duration with configured jitter applied */
  private def effectiveProbeInterval(): FiniteDuration =
    probeInterval + jitter(probeInterval)

  def jitter(d: FiniteDuration): FiniteDuration =
    (d.toMillis * settings.contactPoint.probeIntervalJitter * ThreadLocalRandom.current().nextDouble()).millis

  protected def timeNow(): LocalDateTime =
    LocalDateTime.now()

}
