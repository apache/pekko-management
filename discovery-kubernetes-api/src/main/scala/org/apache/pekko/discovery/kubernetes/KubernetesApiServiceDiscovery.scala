/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.discovery.kubernetes

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeoutException
import java.nio.file.{ Files, Paths }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.{ NoStackTrace, NonFatal }

import org.apache.pekko
import pekko.Done
import pekko.actor.{ ActorSystem, CoordinatedShutdown }
import pekko.annotation.InternalApi
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.kubernetes.JsonFormat._
import pekko.discovery.kubernetes.KubernetesApiServiceDiscovery.{ targets, KubernetesApiException }
import pekko.discovery.kubernetes.PodList.{ Added, Deleted, Error, Modified, WatchEvent }
import pekko.discovery.{ Lookup, ServiceDiscovery }
import pekko.dispatch.Dispatchers.DefaultBlockingDispatcherId
import pekko.event.Logging
import pekko.http.javadsl.model.headers.AcceptEncoding
import pekko.http.scaladsl.{ HttpsConnectionContext, _ }
import pekko.http.scaladsl.coding.Coders
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers.{ Authorization, HttpEncodings, OAuth2BearerToken }
import pekko.http.scaladsl.unmarshalling.Unmarshal
import pekko.pki.kubernetes.PemManagersProvider
import pekko.stream.{ KillSwitches, UniqueKillSwitch }
import pekko.stream.scaladsl.{ Framing, Keep, Sink }
import pekko.util.ByteString
import spray.json._

object KubernetesApiServiceDiscovery {

  /**
   * INTERNAL API
   *
   * Finds relevant targets given a pod list. Note that this doesn't filter by name as it is the job of the selector
   * to do that.
   */
  @InternalApi
  private[kubernetes] def targets(
      podList: PodList,
      portName: Option[String],
      podNamespace: String,
      podDomain: String,
      rawIp: Boolean,
      containerName: Option[String]): immutable.Seq[ResolvedTarget] =
    for {
      item <- podList.items
      if item.metadata.flatMap(_.deletionTimestamp).isEmpty
      itemSpec <- item.spec.toSeq
      itemStatus <- item.status.toSeq
      if itemStatus.phase.contains("Running")
      if containerName.forall(name =>
        itemStatus.containerStatuses match {
          case Some(statuses) => statuses.filter(_.name == name).exists(!_.state.contains("waiting"))
          case None           => false
        })
      ip <- itemStatus.podIP.toSeq
      // Maybe port is an Option of a port, and will be None if no portName was requested
      maybePort <- portName match {
        case None =>
          List(None)
        case Some(name) =>
          for {
            container <- itemSpec.containers
            ports <- container.ports.toSeq
            port <- ports
            if port.name.contains(name)
          } yield Some(port.containerPort)
      }
    } yield {
      val hostOrIp = if (rawIp) ip else s"${ip.replace('.', '-')}.$podNamespace.pod.$podDomain"
      ResolvedTarget(
        host = hostOrIp,
        port = maybePort,
        address = Some(InetAddress.getByName(ip)))
    }

  class KubernetesApiException(msg: String) extends RuntimeException(msg) with NoStackTrace

  private[kubernetes] final case class KubernetesSetup(
      podNamespace: String,
      apiToken: String,
      clientHttpsConnectionContext: HttpsConnectionContext)

  /**
   * INTERNAL API
   *
   * Watch state for a single label selector: the pods it has seen, the version its watch resumes from,
   * and the kill switch for the stream currently feeding it.
   */
  @InternalApi private[kubernetes] final class WatchState {
    val podCache = new AtomicReference[immutable.Map[String, PodList.Pod]](immutable.Map.empty)
    val resourceVersion = new AtomicReference[Option[String]](None)
    val killSwitch = new AtomicReference[Option[UniqueKillSwitch]](None)
    private val started = new AtomicBoolean(false)

    /** True for the caller that is responsible for starting the watch. */
    def startOnce(): Boolean = started.compareAndSet(false, true)
  }
}

/**
 * An alternative implementation that uses the Kubernetes API. The main advantage of this method is that it allows
 * you to define readiness/health checks that don't affect the bootstrap mechanism.
 */
class KubernetesApiServiceDiscovery(settings: Settings)(
    implicit system: ActorSystem) extends ServiceDiscovery {

  import KubernetesApiServiceDiscovery.{ KubernetesSetup, WatchState }
  import pekko.discovery.kubernetes.KubernetesApiServiceDiscovery._

  private val http = Http()

  def this()(implicit system: ActorSystem) = this(Settings(system))

  private val log = Logging(system, classOf[KubernetesApiServiceDiscovery])

  log.debug("Settings {}", settings)

  private val kubernetesSetup: Future[KubernetesSetup] = {
    implicit val blockingDispatcher: ExecutionContext = system.dispatchers.lookup(DefaultBlockingDispatcherId)
    for {
      apiToken: String <- Future {
        readConfigVarFromFilesystem(settings.apiTokenPath, "api-token").getOrElse("")
      }
      namespace: String <- Future {
        settings.podNamespace
          .orElse(readConfigVarFromFilesystem(settings.podNamespacePath, "pod-namespace"))
          .getOrElse("default")
      }
      httpsContext <- Future(clientHttpsConnectionContext())
    } yield {
      KubernetesSetup(namespace, apiToken, httpsContext)
    }
  }

  import system.dispatcher

  // Watch mode state. A single discovery instance can be asked about more than one service name, and
  // each service name maps to its own label selector, so the pod cache and the resource version have to
  // be held per selector - sharing them would let one service's lookup return another service's pods.
  private val watches = new ConcurrentHashMap[String, WatchState]()
  private val watchesShutDown = new AtomicBoolean(false)

  if (settings.watchMode) {
    CoordinatedShutdown(system).addTask(CoordinatedShutdown.PhaseServiceUnbind, "kubernetes-api-watch-stop") { () =>
      watchesShutDown.set(true)
      watches.values().asScala.foreach(_.killSwitch.getAndSet(None).foreach(_.shutdown()))
      Future.successful(Done)
    }
  }

  override def lookup(query: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    if (settings.watchMode) {
      lookupWatch(query, resolveTimeout)
    } else {
      lookupList(query, resolveTimeout)
    }
  }

  private def lookupList(query: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    val labelSelector = settings.podLabelSelector(query.serviceName)

    for {
      setup <- kubernetesSetup

      request <- {
        log.info(
          "Querying for pods with label selector: [{}]. Namespace: [{}]. Port: [{}]",
          labelSelector,
          setup.podNamespace,
          query.portName)

        optionToFuture(
          podRequest(setup.apiToken, setup.podNamespace, labelSelector),
          s"Unable to form request; check Kubernetes environment (expecting env vars ${settings.apiServiceHostEnvName}, ${settings.apiServicePortEnvName})"
        )
      }

      response <- {
        val rawResponse = http.singleRequest(request, setup.clientHttpsConnectionContext)
        val promise = Promise[HttpResponse]()
        val timeoutCancellable = system.scheduler.scheduleOnce(resolveTimeout) {
          promise.tryFailure(new TimeoutException(s"Kubernetes API request timed out after $resolveTimeout"))
        }
        rawResponse.onComplete {
          case scala.util.Success(resp) =>
            timeoutCancellable.cancel()
            if (!promise.trySuccess(resp)) {
              resp.discardEntityBytes()
            }
          case scala.util.Failure(ex) =>
            timeoutCancellable.cancel()
            promise.tryFailure(ex)
        }(system.dispatcher)
        promise.future.map(decodeResponse)
      }

      entity <- response.entity.toStrict(resolveTimeout)

      podList <- {
        response.status match {
          case StatusCodes.OK =>
            log.debug("Kubernetes API entity: [{}]", entity.data.utf8String)
            val unmarshalled = Unmarshal(entity).to[PodList]
            unmarshalled.failed.foreach { t =>
              log.warning(
                "Failed to unmarshal Kubernetes API response.  Status code: [{}]; Response body: [{}]. Ex: [{}]",
                response.status.value,
                entity,
                t.getMessage)
            }
            unmarshalled
          case StatusCodes.Forbidden =>
            Unmarshal(entity).to[String].foreach { body =>
              log.warning(
                "Forbidden to communicate with Kubernetes API server; check RBAC settings. Response: [{}]",
                body)
            }
            Future.failed(
              new KubernetesApiException("Forbidden when communicating with the Kubernetes API. Check RBAC settings."))
          case other =>
            Unmarshal(entity).to[String].foreach { body =>
              log.warning(
                "Non-200 when communicating with Kubernetes API server. Status code: [{}]. Response body: [{}]",
                other,
                body)
            }
            Future.failed(new KubernetesApiException(s"Non-200 from Kubernetes API server: $other"))
        }
      }

    } yield {
      val addresses =
        targets(podList, query.portName, setup.podNamespace, settings.podDomain, settings.rawIp, settings.containerName)
      if (addresses.isEmpty && podList.items.nonEmpty) {
        if (log.isInfoEnabled) {
          val containerPortNames = podList.items.flatMap(_.spec).flatMap(_.containers).flatMap(_.ports).flatten.toSet
          log.info(
            "No targets found from pod list. Is the correct port name configured? Current configuration: [{}]. Ports on pods: [{}]",
            query.portName,
            containerPortNames)
        }
      }
      Resolved(
        serviceName = query.serviceName,
        addresses = addresses)
    }
  }

  private def optionToFuture[T](option: Option[T], failMsg: String): Future[T] =
    option.fold(Future.failed[T](new NoSuchElementException(failMsg)))(Future.successful)

  private def podRequest(token: String, namespace: String, labelSelector: String) =
    for {
      host <- sys.env.get(settings.apiServiceHostEnvName)
      portStr <- sys.env.get(settings.apiServicePortEnvName)
      port <- Try(portStr.toInt).toOption
    } yield {
      val path = Uri.Path.Empty / "api" / "v1" / "namespaces" / namespace / "pods"
      val query = Uri.Query("labelSelector" -> labelSelector)
      val uri = Uri.from(scheme = "https", host = host, port = port).withPath(path).withQuery(query)

      val authHeaders = immutable.Seq(Authorization(OAuth2BearerToken(token)))
      val acceptEncodingHeader = HttpEncodings.getForKey(settings.httpRequestAcceptEncoding)
        .map(httpEncoding => AcceptEncoding.create(httpEncoding))
      HttpRequest(uri = uri, headers = authHeaders ++ acceptEncodingHeader)
    }

  /**
   * This uses blocking IO, and so should only be used at startup from blocking dispatcher.
   */
  private def clientHttpsConnectionContext(): HttpsConnectionContext = {
    val sslContext = PemManagersProvider.createSslContext(settings.apiCaPath, settings.tlsVersion)
    ConnectionContext.httpsClient(sslContext)
  }

  /**
   * This uses blocking IO, and so should only be used to read configuration at startup.
   */
  private def readConfigVarFromFilesystem(path: String, name: String): Option[String] = {
    val file = Paths.get(path)
    if (Files.exists(file)) {
      try {
        Some(new String(Files.readAllBytes(file), StandardCharsets.UTF_8))
      } catch {
        case NonFatal(e) =>
          log.error(e, "Error reading {} from {}", name, path)
          None
      }
    } else {
      log.warning("Unable to read {} from {} because it doesn't exist.", name, path)
      None
    }
  }

  private def decodeResponse(response: HttpResponse): HttpResponse = {
    val decoder = response.encoding match {
      case HttpEncodings.gzip =>
        Coders.Gzip
      case HttpEncodings.deflate =>
        Coders.Deflate
      case _ =>
        Coders.NoCoding
    }
    decoder.decodeMessage(response)
  }

  // ---- Watch mode methods ----

  private def lookupWatch(query: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    val labelSelector = settings.podLabelSelector(query.serviceName)

    val resolved = for {
      setup <- kubernetesSetup
      state = watches.computeIfAbsent(watchKey(setup.podNamespace, labelSelector), _ => new WatchState)
      _ <- if (state.startOnce()) {
        log.info(
          "Starting watch for pods with label selector: [{}]. Namespace: [{}]",
          labelSelector,
          setup.podNamespace)
        startWatch(setup, labelSelector, state)
      } else {
        Future.unit
      }
    } yield {
      val cachedPods = state.podCache.get()
      val podList = PodList(cachedPods.values.toList)
      val addresses =
        targets(podList, query.portName, setup.podNamespace, settings.podDomain, settings.rawIp, settings.containerName)
      if (addresses.isEmpty && cachedPods.nonEmpty) {
        if (log.isInfoEnabled) {
          val containerPortNames =
            cachedPods.values.flatMap(_.spec).flatMap(_.containers).flatMap(_.ports).flatten.toSet
          log.info(
            "No targets found from pod cache. Is the correct port name configured? Current configuration: [{}]. Ports on pods: [{}]",
            query.portName,
            containerPortNames)
        }
      }
      Resolved(
        serviceName = query.serviceName,
        addresses = addresses)
    }

    withTimeout(resolved, resolveTimeout, s"Kubernetes API watch lookup timed out after $resolveTimeout")
  }

  private def watchKey(namespace: String, labelSelector: String): String = s"$namespace:$labelSelector"

  /** Performs the initial list, which seeds the cache and fixes the point the watch resumes from. */
  private def startWatch(setup: KubernetesSetup, labelSelector: String, state: WatchState): Future[Unit] = {
    val listed = for {
      listReq <- optionToFuture(
        listRequest(setup.apiToken, setup.podNamespace, labelSelector),
        s"Unable to form request; check Kubernetes environment (expecting env vars ${settings.apiServiceHostEnvName}, ${settings.apiServicePortEnvName})")
      listResp <- http.singleRequest(listReq, setup.clientHttpsConnectionContext).map(decodeResponse)
      bytes <- listResp.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
      podList <- {
        listResp.status match {
          case StatusCodes.OK =>
            Unmarshal(HttpEntity(ContentTypes.`application/json`, bytes)).to[PodList]
          case other =>
            log.warning("Initial list failed with status [{}]", other)
            Future.failed(new KubernetesApiException(s"Initial pod list failed with status $other"))
        }
      }
    } yield {
      // A full list replaces the cache outright, so pods deleted while we were disconnected drop out.
      state.podCache.set(podsByName(podList))
      // The list's own resourceVersion, not an item's - resuming from an item's version can silently
      // skip the events between it and the end of the list.
      state.resourceVersion.set(podList.metadata.flatMap(_.resourceVersion))
      ()
    }

    listed.flatMap(_ => startWatchStream(setup, labelSelector, state)).recover {
      case NonFatal(e) =>
        log.error(e, "Failed to start watch for pods with label selector: [{}]", labelSelector)
        scheduleWatchRestart(setup, labelSelector, state, settings.watchOnErrorReconnectDelay)
    }
  }

  private def startWatchStream(setup: KubernetesSetup, labelSelector: String, state: WatchState): Future[Unit] = {
    val resourceVersion = state.resourceVersion.get()
    optionToFuture(
      watchRequest(setup.apiToken, setup.podNamespace, labelSelector, resourceVersion),
      s"Unable to form request; check Kubernetes environment (expecting env vars ${settings.apiServiceHostEnvName}, ${settings.apiServicePortEnvName})"
    ).flatMap { request =>
      log.debug("Starting watch stream with resourceVersion: [{}]", resourceVersion)
      http.singleRequest(request, setup.clientHttpsConnectionContext).map { response =>
        response.status match {
          case StatusCodes.OK =>
            log.info("Watch stream started for label selector: [{}]", labelSelector)
            processWatchStream(decodeResponse(response), setup, labelSelector, state)
          case StatusCodes.Gone =>
            // resourceVersion too old; drop it so the restart goes back through a full list
            log.warning("Watch resourceVersion expired (410 Gone), restarting with a fresh list")
            response.discardEntityBytes()
            state.resourceVersion.set(None)
            scheduleWatchRestart(setup, labelSelector, state, settings.watchReconnectDelay)
          case other =>
            response.discardEntityBytes()
            throw new KubernetesApiException(s"Watch request failed with status $other")
        }
      }
    }
  }

  private def processWatchStream(
      response: HttpResponse,
      setup: KubernetesSetup,
      labelSelector: String,
      state: WatchState): Unit = {
    // Frame on the raw bytes rather than decoding each chunk: a chunk boundary can fall in the middle
    // of a multi-byte UTF-8 character, and decoding per chunk would corrupt it.
    val (killSwitch, done) = response.entity.dataBytes
      .viaMat(KillSwitches.single)(Keep.right)
      .via(Framing.delimiter(ByteString("\n"), settings.watchMaxFrameLength, allowTruncation = true))
      .map(_.utf8String)
      .filter(_.nonEmpty)
      .toMat(Sink.foreach { line =>
        Try(JsonFormat.watchEventFormat.read(line.parseJson)) match {
          case scala.util.Success(event) => processWatchEvent(event, state)
          case scala.util.Failure(ex)    => log.warning("Failed to parse watch event: [{}]", ex.getMessage)
        }
      })(Keep.both)
      .run()

    state.killSwitch.set(Some(killSwitch))

    done.onComplete { result =>
      state.killSwitch.set(None)
      val delay = result match {
        case scala.util.Success(_) =>
          log.info("Watch stream completed, reconnecting")
          settings.watchReconnectDelay
        case scala.util.Failure(ex) =>
          log.warning("Watch stream failed: [{}], reconnecting", ex.getMessage)
          settings.watchOnErrorReconnectDelay
      }
      scheduleWatchRestart(setup, labelSelector, state, delay)
    }
  }

  private[kubernetes] def processWatchEvent(event: WatchEvent, state: WatchState): Unit = {
    def withName(f: String => Unit): Unit = event.pod.metadata.flatMap(_.name) match {
      case Some(podName) =>
        f(podName)
        // each event carries the version to resume the watch from
        event.pod.metadata.flatMap(_.resourceVersion).foreach(rv => state.resourceVersion.set(Some(rv)))
      case None =>
        log.warning("Ignoring [{}] watch event for a pod without metadata.name", event.eventType)
    }

    event.eventType match {
      case Added | Modified =>
        withName { podName =>
          log.debug("Watch event [{}] for pod [{}]", event.eventType, podName)
          state.podCache.updateAndGet(cache => cache + (podName -> event.pod))
        }
      case Deleted =>
        withName { podName =>
          log.debug("Watch event DELETED for pod [{}]", podName)
          state.podCache.updateAndGet(cache => cache - podName)
        }
      case Error =>
        // The server signals an error - most often an expired resourceVersion - and closes the stream.
        // Drop the version so that the reconnect resyncs from a full list rather than resuming.
        log.warning("Watch stream reported an ERROR event, will resync from a fresh list")
        state.resourceVersion.set(None)
    }
  }

  private def scheduleWatchRestart(
      setup: KubernetesSetup,
      labelSelector: String,
      state: WatchState,
      delay: FiniteDuration): Unit =
    if (watchesShutDown.get()) {
      log.debug("Not restarting watch for label selector: [{}], shutting down", labelSelector)
    } else {
      system.scheduler.scheduleOnce(delay) {
        if (watchesShutDown.get()) {
          log.debug("Not restarting watch for label selector: [{}], shutting down", labelSelector)
        } else {
          log.info("Restarting watch for label selector: [{}]", labelSelector)
          // without a resource version there is nothing to resume from, so go back through a full list
          val restarted =
            if (state.resourceVersion.get().isEmpty) startWatch(setup, labelSelector, state)
            else startWatchStream(setup, labelSelector, state)
          restarted.recover {
            case NonFatal(e) =>
              log.error(e, "Watch restart failed for label selector: [{}]", labelSelector)
              scheduleWatchRestart(setup, labelSelector, state, settings.watchOnErrorReconnectDelay)
          }
        }
      }
    }

  private def listRequest(token: String, namespace: String, labelSelector: String): Option[HttpRequest] =
    podRequest(token, namespace, labelSelector)

  private def watchRequest(
      token: String,
      namespace: String,
      labelSelector: String,
      resourceVersion: Option[String]): Option[HttpRequest] = {
    for {
      host <- sys.env.get(settings.apiServiceHostEnvName)
      portStr <- sys.env.get(settings.apiServicePortEnvName)
      port <- Try(portStr.toInt).toOption
    } yield {
      val path = Uri.Path.Empty / "api" / "v1" / "namespaces" / namespace / "pods"
      val params = Seq("labelSelector" -> labelSelector, "watch" -> "true") ++
        resourceVersion.map(rv => "resourceVersion" -> rv)
      val query = Uri.Query(params: _*)
      val uri = Uri.from(scheme = "https", host = host, port = port).withPath(path).withQuery(query)

      val authHeaders = immutable.Seq(Authorization(OAuth2BearerToken(token)))
      val acceptEncodingHeader = HttpEncodings.getForKey(settings.httpRequestAcceptEncoding)
        .map(httpEncoding => AcceptEncoding.create(httpEncoding))
      HttpRequest(uri = uri, headers = authHeaders ++ acceptEncodingHeader)
    }
  }

  /**
   * Keyed by pod name. Pods without a name are dropped rather than collapsed onto a shared key, which
   * would let them overwrite each other.
   */
  private def podsByName(podList: PodList): immutable.Map[String, PodList.Pod] =
    podList.items.flatMap(pod => pod.metadata.flatMap(_.name).map(_ -> pod)).toMap

  private def withTimeout[T](future: Future[T], timeout: FiniteDuration, message: => String): Future[T] = {
    val promise = Promise[T]()
    val timeoutCancellable = system.scheduler.scheduleOnce(timeout) {
      promise.tryFailure(new TimeoutException(message))
    }
    future.onComplete { result =>
      timeoutCancellable.cancel()
      promise.tryComplete(result)
    }
    promise.future
  }
}
