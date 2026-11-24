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

package org.apache.pekko.discovery.consul

import com.google.common.net.HostAndPort
import org.apache.pekko
import pekko.Done
import pekko.actor.{ ActorSystem, CoordinatedShutdown }
import pekko.annotation.{ ApiMayChange, InternalApi }
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.consul.ConsulServiceDiscovery._
import pekko.discovery.{ Lookup, ServiceDiscovery }
import pekko.dispatch.Dispatchers.DefaultBlockingDispatcherId
import org.kiwiproject.consul.Consul
import org.kiwiproject.consul.async.ConsulResponseCallback
import org.kiwiproject.consul.model.ConsulResponse
import org.kiwiproject.consul.model.catalog.CatalogService
import org.kiwiproject.consul.option.Options

import java.net.InetAddress
import java.nio.file.{ Files, Paths }
import java.security.KeyStore
import java.security.cert.{ Certificate, CertificateFactory }
import java.util
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{ SSLContext, TrustManager, TrustManagerFactory, X509TrustManager }
import scala.collection.immutable.Seq
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ ExecutionContext, Future, Promise }
import scala.jdk.CollectionConverters._
import scala.util.Try

@ApiMayChange
class ConsulServiceDiscovery(system: ActorSystem) extends ServiceDiscovery {

  private val settings = ConsulSettings.get(system)

  private[consul] def createConsulClient(): Consul = {
    val builder = Consul
      .builder()
      .withHostAndPort(HostAndPort.fromParts(settings.consulHost, settings.consulPort))
      .withConnectTimeoutMillis(settings.connectTimeout.toMillis)
      .withReadTimeoutMillis(settings.readTimeout.toMillis)
      .withWriteTimeoutMillis(settings.writeTimeout.toMillis)
    settings.consulToken.foreach(builder.withTokenAuth)
    if (settings.tlsEnabled) {
      builder.withHttps(true)
      settings.caPath.foreach { caPath =>
        val trustManagers = buildTrustManagers(loadCertificates(caPath))
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(null, trustManagers, null)
        builder.withSslContext(sslContext)
        // without this the client falls back to the default JVM trust manager for chain cleaning,
        // even though the handshake uses the CA configured above
        trustManagers.collectFirst { case tm: X509TrustManager => tm }.foreach(builder.withTrustManager)
      }
    }
    builder.build()
  }

  // holds the client once it has been built, so that shutdown never forces the lazy initialisation
  private val builtConsul = new AtomicReference[Consul]()

  // set when coordinated shutdown destroys the client, so that a client whose construction was still
  // in progress at that point is destroyed as soon as it has been built rather than leaked
  @volatile private var shuttingDown = false

  private val blockingEc: ExecutionContext = system.dispatchers.lookup(DefaultBlockingDispatcherId)

  private lazy val consul: Consul = {
    val client = createConsulClient()
    builtConsul.set(client)
    // shutdown started while the client was being built, and so found nothing to destroy
    if (shuttingDown) takeBuiltConsul().foreach(destroy)
    client
  }

  private def takeBuiltConsul(): Option[Consul] = Option(builtConsul.getAndSet(null))

  // destroy() shuts down the OkHttp connection pool and executor service, which blocks, so it is kept
  // off the calling thread - a coordinated shutdown task, or a lookup overtaken by shutdown
  private def destroy(client: Consul): Future[Done] =
    Future {
      client.destroy()
      Done
    }(blockingEc)

  /**
   * The client to send a request with. Fails once shutdown has started: no client is built from then
   * on, and one that shutdown has destroyed - or is destroying - is never handed out for a request.
   */
  private def activeConsul(): Consul = {
    def ensureNotShutDown(): Unit =
      if (shuttingDown) throw new IllegalStateException("Consul service discovery has been shut down")
    ensureNotShutDown()
    val client = consul
    // shutdown may have started while the client was being built
    ensureNotShutDown()
    client
  }

  CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "consul-close") { () =>
    shuttingDown = true
    takeBuiltConsul() match {
      case None         => Future.successful(Done)
      case Some(client) => destroy(client)
    }
  }

  override def lookup(lookup: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    implicit val ec: ExecutionContext = system.dispatcher
    // A Promise, rather than Future.firstCompletedOf, so that the scheduled timeout can be
    // cancelled once the lookup completes instead of being left on the scheduler until it fires.
    // Note that a timeout does not cancel the Consul requests already in flight; those are bounded
    // by the connect/read/write timeouts the client is built with.
    val promise = Promise[Resolved]()
    val timeoutCancellable = system.scheduler.scheduleOnce(resolveTimeout) {
      promise.tryFailure(new TimeoutException(s"Lookup for [$lookup] timed-out, within [$resolveTimeout]!"))
    }
    lookupInConsul(lookup.serviceName).onComplete { result =>
      timeoutCancellable.cancel()
      promise.tryComplete(result)
    }
    promise.future
  }

  private def lookupInConsul(name: String)(implicit executionContext: ExecutionContext): Future[Resolved] = {
    val consulResult = for {
      servicesWithTags <- getServicesWithTags
      nameTag = settings.applicationNameTagPrefix + name
      serviceIds = servicesWithTags.getResponse
        .entrySet()
        .asScala
        .filter(e => e.getValue.contains(nameTag))
        .map(_.getKey)
      catalogServices <- boundedTraverse(serviceIds.toSeq, settings.parallelism)(id =>
        getService(id).map(_.getResponse.asScala.toList))
      resolvedTargets <- Future.traverse(catalogServices.flatten.toSeq) { catalogService =>
        Future(extractResolvedTargetFromCatalogService(catalogService))(blockingEc)
      }
    } yield resolvedTargets
    consulResult.map(targets => Resolved(name, targets))
  }

  private def extractResolvedTargetFromCatalogService(catalogService: CatalogService) = {
    val port = catalogService.getServiceTags.asScala
      .find(_.startsWith(settings.applicationPekkoManagementPortTagPrefix))
      .map(_.replace(settings.applicationPekkoManagementPortTagPrefix, ""))
      .flatMap { maybePort =>
        Try(maybePort.toInt).toOption
      }
    val address = catalogService.getServiceAddress
    ResolvedTarget(
      host = address,
      port = Some(port.getOrElse(catalogService.getServicePort)),
      address = Try(InetAddress.getByName(address)).toOption)
  }

  private[consul] def boundedTraverse[A, B](items: Seq[A], parallelism: Int)(f: A => Future[B])(
      implicit ec: ExecutionContext): Future[Seq[B]] = {
    require(parallelism > 0, s"parallelism must be greater than 0, was [$parallelism]")
    def loop(remaining: Seq[A], acc: Seq[B]): Future[Seq[B]] = {
      if (remaining.isEmpty) Future.successful(acc.reverse)
      else {
        val (batch, rest) = remaining.splitAt(parallelism)
        Future.traverse(batch)(f).flatMap(results => loop(rest, results.reverse ++ acc))
      }
    }
    loop(items, Seq.empty)
  }

  private def getServicesWithTags: Future[ConsulResponse[util.Map[String, util.List[String]]]] = {
    ((callback: ConsulResponseCallback[util.Map[String, util.List[String]]]) =>
          activeConsul().catalogClient().getServices(callback)).asFuture
  }

  private def getService(name: String) =
    ((callback: ConsulResponseCallback[util.List[CatalogService]]) =>
          activeConsul().catalogClient().getService(name, Options.BLANK_QUERY_OPTIONS, callback)).asFuture

}

@ApiMayChange
object ConsulServiceDiscovery {

  /**
   * INTERNAL API
   *
   * Loads every certificate held in the given PEM file. The file may bundle more than one
   * certificate (a root plus intermediates, or several roots while a CA is being rotated).
   */
  @InternalApi
  private[consul] def loadCertificates(caPath: String): Seq[Certificate] = {
    val in = Files.newInputStream(Paths.get(caPath))
    val certificates =
      try CertificateFactory.getInstance("X.509").generateCertificates(in).asScala.toSeq
      finally in.close()
    if (certificates.isEmpty)
      throw new IllegalArgumentException(s"No certificates found in Consul CA file [$caPath]")
    certificates
  }

  /**
   * INTERNAL API
   *
   * Builds trust managers that trust every one of the given certificates, each held under its
   * own alias so that no certificate hides another.
   */
  @InternalApi
  private[consul] def buildTrustManagers(certificates: Seq[Certificate]): Array[TrustManager] = {
    val ks = KeyStore.getInstance(KeyStore.getDefaultType)
    ks.load(null)
    certificates.zipWithIndex.foreach { case (cert, idx) => ks.setCertificateEntry(s"ca-$idx", cert) }
    val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(ks)
    tmf.getTrustManagers
  }

  implicit class ConsulResponseFutureDecorator[T](f: ConsulResponseCallback[T] => Unit) {
    def asFuture: Future[ConsulResponse[T]] = {
      val callback = new ConsulResponseFutureCallback[T]
      Try(f(callback)).recover[Unit] {
        case ex: Throwable => callback.fail(ex)
      }
      callback.future
    }
  }

  final case class ConsulResponseFutureCallback[T]() extends ConsulResponseCallback[T] {

    private val promise = Promise[ConsulResponse[T]]()

    def fail(exception: Throwable) = promise.failure(exception)

    def future: Future[ConsulResponse[T]] = promise.future

    override def onComplete(consulResponse: ConsulResponse[T]): Unit = {
      promise.success(consulResponse)
    }

    override def onFailure(throwable: Throwable): Unit = {
      promise.failure(throwable)
    }
  }

}
