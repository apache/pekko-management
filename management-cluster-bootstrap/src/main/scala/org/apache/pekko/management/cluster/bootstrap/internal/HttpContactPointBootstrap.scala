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
import java.security.{ KeyStore, SecureRandom }
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeoutException
import javax.net.ssl.{ KeyManager, KeyManagerFactory, SSLContext, TrustManager }
import scala.concurrent.Future
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
import pekko.http.scaladsl.settings.ConnectionPoolSettings
import pekko.http.scaladsl.unmarshalling.Unmarshal
import pekko.management.cluster.bootstrap.ClusterBootstrapSettings
import pekko.management.cluster.bootstrap.contactpoint.HttpBootstrapJsonProtocol.SeedNodes
import pekko.management.cluster.bootstrap.contactpoint.{ ClusterBootstrapRequests, HttpBootstrapJsonProtocol }
import pekko.pattern.after
import pekko.pattern.pipe
import pekko.pki.kubernetes.PemManagersProvider

@InternalApi
private[bootstrap] object HttpContactPointBootstrap {

  def name(host: Host, port: Int): String = {
    val ValidSymbols = """-_.*$+:@&=,!~';"""
    val cleanHost = host.address.filter(c =>
      (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || (ValidSymbols.indexOf(c) != -1))
    s"contactPointProbe-$cleanHost-$port"
  }
  def props(settings: ClusterBootstrapSettings, contactPoint: ResolvedTarget, baseUri: Uri): Props =
    Props(new HttpContactPointBootstrap(settings, contactPoint, baseUri))

  private case object ProbeTick extends DeadLetterSuppression
  private val ProbingTimerKey = "probing-key"
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

  import HttpContactPointBootstrap.ProbeTick
  import HttpContactPointBootstrap.ProbingTimerKey

  private val cluster = Cluster(context.system)

  if (baseUri.authority.host.address() == cluster.selfAddress.host.getOrElse("---") &&
    baseUri.authority.port == cluster.selfAddress.port.getOrElse(-1)) {
    throw new IllegalArgumentException(
      "Requested base Uri to be probed matches local remoting address, bailing out! " +
      s"Uri: $baseUri, this node's remoting address: ${cluster.selfAddress}")
  }

  private implicit val sys: ActorSystem = context.system

  private lazy val sslContext = {
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null)
    factory.init(keyStore, Array.empty)
    val km: Array[KeyManager] = factory.getKeyManagers
    val caPath = settings.contactPoint.httpClient.caPath.trim
    val tm: Array[TrustManager] = if (caPath.isEmpty) {
      Array.empty
    } else {
      val certificates = PemManagersProvider.loadCertificates(caPath)
      PemManagersProvider.buildTrustManagers(certificates)
    }
    val random: SecureRandom = new SecureRandom
    val sslContext = SSLContext.getInstance("TLSv1.2")
    sslContext.init(km, tm, random)
    sslContext
  }

  private lazy val clientSslContext: HttpsConnectionContext =
    ConnectionContext.httpsClient(sslContext)

  private val http = Http()

  private val connectionPoolWithoutRetries = ConnectionPoolSettings(context.system).withMaxRetries(0)
  import context.dispatcher

  private val probeInterval = settings.contactPoint.probeInterval
  private val probeRequest = ClusterBootstrapRequests.bootstrapSeedNodes(baseUri)
  private val replyTimeout = Future.failed(new TimeoutException(s"Probing timeout of [$baseUri]"))

  /**
   * If probing keeps failing until the deadline triggers, we notify the parent,
   * such that it rediscover again.
   */
  private var probingKeepFailingDeadline: Deadline = settings.contactPoint.probingFailureTimeout.fromNow

  private def resetProbingKeepFailingWithinDeadline(): Unit =
    probingKeepFailingDeadline = settings.contactPoint.probingFailureTimeout.fromNow

  override def preStart(): Unit =
    self ! ProbeTick

  override def receive = {
    case ProbeTick =>
      log.debug("Probing [{}] for seed nodes...", probeRequest.uri)
      val reply = if (probeRequest.uri.scheme == "https") {
        http.singleRequest(probeRequest, settings = connectionPoolWithoutRetries,
          connectionContext = clientSslContext)
      } else {
        http.singleRequest(probeRequest, settings = connectionPoolWithoutRetries)
      }.flatMap(handleResponse)

      val afterTimeout = after(settings.contactPoint.probingFailureTimeout, context.system.scheduler)(replyTimeout)
      Future.firstCompletedOf(List(reply, afterTimeout)).pipeTo(self)

    case Status.Failure(cause) =>
      log.warning("Probing [{}] failed due to: {}", probeRequest.uri, cause.getMessage)
      if (probingKeepFailingDeadline.isOverdue()) {
        log.error("Overdue of probing-failure-timeout, stop probing, signaling that it's failed")
        context.parent ! BootstrapCoordinator.Protocol.ProbingFailed(contactPoint, cause)
        context.stop(self)
      } else {
        // keep probing, hoping the request will eventually succeed
        scheduleNextContactPointProbing()
      }

    case response: SeedNodes =>
      notifyParentAboutSeedNodes(response)
      resetProbingKeepFailingWithinDeadline()
      // we keep probing and looking if maybe a cluster does form after all
      // (technically could be long polling or web-sockets, but that would need reconnect logic, so this is simpler)
      scheduleNextContactPointProbing()
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
