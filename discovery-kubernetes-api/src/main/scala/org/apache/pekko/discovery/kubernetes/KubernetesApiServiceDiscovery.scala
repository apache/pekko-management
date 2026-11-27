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
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicLong, AtomicReference }

import scala.jdk.CollectionConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Try
import scala.util.control.{ NoStackTrace, NonFatal }

import org.apache.pekko
import pekko.Done
import pekko.actor.{ ActorSystem, Cancellable, CoordinatedShutdown }
import pekko.annotation.InternalApi
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.kubernetes.JsonFormat._
import pekko.discovery.kubernetes.KubernetesApiServiceDiscovery.{ targets, KubernetesApiException }
import pekko.discovery.kubernetes.PodList.{ Added, Bookmark, Deleted, Error, Modified, WatchEvent }
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
import pekko.stream.{ KillSwitches, StreamLimitReachedException, UniqueKillSwitch }
import pekko.stream.scaladsl.{ Framing, Keep, Sink, Source }
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
      containerName: Option[String]): Seq[ResolvedTarget] =
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

  /**
   * INTERNAL API
   *
   * A watch event could not be applied to the pod cache. The events after it build on a state the cache
   * does not have, so the watch has to resync from a full list rather than carry on or resume.
   */
  @InternalApi private[kubernetes] final class WatchEventNotAppliedException(msg: String)
      extends RuntimeException(msg)
      with NoStackTrace

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
    val podCache = new AtomicReference[Map[String, PodList.Pod]](Map.empty)
    val resourceVersion = new AtomicReference[Option[String]](None)
    val killSwitch = new AtomicReference[Option[UniqueKillSwitch]](None)
    // completed by the first successful list; lookups wait for it so that they never answer from an
    // empty cache that simply has not been populated yet
    val synced: Promise[Done] = Promise[Done]()
    private val started = new AtomicBoolean(false)
    private val stopped = new AtomicBoolean(false)
    private val lastLookupNanos = new AtomicLong(System.nanoTime())

    /** True for the caller that is responsible for starting the watch. */
    def startOnce(): Boolean = started.compareAndSet(false, true)

    /** Records a lookup, which keeps the watch from being released as idle. */
    def touch(): Unit = lastLookupNanos.set(System.nanoTime())

    /** How long ago, in nanoseconds as of `nowNanos`, the last lookup used this watch. */
    def idleNanos(nowNanos: Long): Long = nowNanos - lastLookupNanos.get()

    def isStopped: Boolean = stopped.get()

    /**
     * Shuts down the stream feeding this watch, if any, and keeps it from being restarted. A watch that
     * is stopped before its initial list has completed will never complete it, so the lookups waiting
     * for it are failed rather than left to time out.
     */
    def stop(): Unit = {
      stopped.set(true)
      killSwitch.getAndSet(None).foreach(_.shutdown())
      synced.tryFailure(new KubernetesApiException("The watch was stopped before its initial pod list completed"))
    }
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
      stopWatches()
      Future.successful(Done)
    }
  }

  // A watch is only useful while something keeps looking its service up. Cluster Bootstrap, for one, stops
  // once the node has joined, so a watch that is no longer looked up is released rather than being kept,
  // with its connection and cached pods, until the actor system shuts down.
  private val idleWatchReaper: Option[Cancellable] = settings.watchIdleTimeout match {
    case idleTimeout: FiniteDuration if settings.watchMode =>
      val interval = (idleTimeout / 2).max(1.second)
      Some(system.scheduler.scheduleWithFixedDelay(interval, interval)(() => releaseIdleWatches()))
    case _ => None
  }

  /**
   * Stops every running watch stream and prevents any further (re)starts. From here on lookups fail,
   * the ones still waiting for an initial list included, and no new watch is created.
   */
  private[kubernetes] def stopWatches(): Unit = {
    watchesShutDown.set(true)
    idleWatchReaper.foreach(_.cancel())
    watches.values().asScala.foreach(_.stop())
  }

  private def isStopped(state: WatchState): Boolean = state.isStopped || watchesShutDown.get()

  /** The number of label selectors that currently have a watch state. */
  private[kubernetes] def watchCount: Int = watches.size()

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

      val authHeaders = Seq(Authorization(OAuth2BearerToken(token)))
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

    kubernetesSetup.flatMap { setup =>
      watchStateFor(setup.podNamespace, labelSelector) match {
        case Some(state) =>
          if (state.startOnce()) {
            log.info(
              "Starting watch for pods with label selector: [{}]. Namespace: [{}]",
              labelSelector,
              setup.podNamespace)
            // retries on its own until the initial list succeeds, which completes `state.synced`
            runWatch(setup, labelSelector, state)
          }
          resolveFromWatch(state, query, labelSelector, setup.podNamespace, resolveTimeout)
        case None =>
          // the watches no longer run, so nothing would complete this lookup or keep its answer current
          Future.failed(
            new KubernetesApiException(
              s"Kubernetes API watch lookup for label selector [$labelSelector] failed, " +
              "the watches have been stopped because the actor system is shutting down"))
      }
    }
  }

  /**
   * The watch state for a label selector, created if there is none. Counts as a lookup, so the state
   * returned is not released as idle before `watch-idle-timeout` has passed again. `None` once the
   * watches have been stopped, in which case no state is created.
   */
  private[kubernetes] def watchStateFor(podNamespace: String, labelSelector: String): Option[WatchState] =
    if (watchesShutDown.get()) None
    else {
      // marked as used inside `compute`, which is atomic with the removal in `releaseIdleWatches`: a lookup
      // either gets a state that has just been marked as used, or a new one - never one being released
      val key = watchKey(podNamespace, labelSelector)
      val state = watches.compute(
        key,
        (_, existing) => {
          val state = if (existing eq null) new WatchState else existing
          state.touch()
          state
        })
      // `stopWatches` may have run since the check above, and may not have seen a state that was only
      // being created at the time, so that is stopped and forgotten here instead
      if (watchesShutDown.get()) {
        state.stop()
        watches.remove(key, state)
        None
      } else Some(state)
    }

  /**
   * Stops and forgets every watch that has not been looked up for `watch-idle-timeout`. The next lookup
   * for such a selector starts a fresh watch, from a new initial list.
   */
  private[kubernetes] def releaseIdleWatches(nowNanos: Long = System.nanoTime()): Unit =
    settings.watchIdleTimeout match {
      case idleTimeout: FiniteDuration =>
        watches.keySet().asScala.toList.foreach { key =>
          watches.computeIfPresent(
            key,
            (_, state) =>
              if (state.idleNanos(nowNanos) >= idleTimeout.toNanos) {
                log.info("Stopping watch [{}], which has not been looked up for {}", key, idleTimeout)
                state.stop()
                null
              } else state)
        }
      case _ => // watches are kept until shutdown
    }

  /**
   * Answers a lookup from the watch cache once the initial list has populated it. Until then the lookup
   * waits, and fails with a `TimeoutException` if the initial list does not succeed within `resolveTimeout`.
   */
  private[kubernetes] def resolveFromWatch(
      state: WatchState,
      query: Lookup,
      labelSelector: String,
      podNamespace: String,
      resolveTimeout: FiniteDuration): Future[Resolved] = {
    val resolved = state.synced.future.map { _ =>
      val cachedPods = state.podCache.get()
      val podList = PodList(cachedPods.values.toList)
      val addresses =
        targets(podList, query.portName, podNamespace, settings.podDomain, settings.rawIp, settings.containerName)
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

    withTimeout(
      resolved,
      resolveTimeout,
      s"Kubernetes API watch lookup for label selector [$labelSelector] timed out after $resolveTimeout" +
      (if (state.synced.isCompleted) "" else " waiting for the initial pod list"))
  }

  private def watchKey(namespace: String, labelSelector: String): String = s"$namespace:$labelSelector"

  /**
   * Starts the watch - from a full list if there is no resource version to resume from - and schedules a
   * retry if it could not be started. This is the only place a failed start is turned into a retry;
   * `startWatch` and `startWatchStream` report failure through the future they return.
   */
  private def runWatch(setup: KubernetesSetup, labelSelector: String, state: WatchState): Unit =
    if (isStopped(state)) {
      log.debug("Not starting watch for label selector: [{}], it has been stopped", labelSelector)
    } else {
      val started =
        if (state.resourceVersion.get().isEmpty) startWatch(setup, labelSelector, state)
        else startWatchStream(setup, labelSelector, state)
      started.failed.foreach { e =>
        log.error(e, "Failed to start watch for pods with label selector: [{}]", labelSelector)
        scheduleWatchRestart(setup, labelSelector, state, settings.watchOnErrorReconnectDelay)
      }
    }

  /**
   * Performs the initial list, which seeds the cache and fixes the point the watch resumes from, then
   * opens the watch stream. The returned future fails if either step does.
   */
  private[kubernetes] def startWatch(setup: KubernetesSetup, labelSelector: String, state: WatchState): Future[Unit] = {
    // one deadline for the whole attempt, from sending the request to having read the response
    val deadline = settings.watchListTimeout.fromNow
    val listed = for {
      listReq <- optionToFuture(
        listRequest(setup.apiToken, setup.podNamespace, labelSelector),
        s"Unable to form request; check Kubernetes environment (expecting env vars ${settings.apiServiceHostEnvName}, ${settings.apiServicePortEnvName})")
      listResp <- listResponseWithin(http.singleRequest(listReq, setup.clientHttpsConnectionContext), deadline)
      podList <- readPodList(decodeResponse(listResp), deadline)
    } yield {
      // A full list replaces the cache outright, so pods deleted while we were disconnected drop out.
      state.podCache.set(podsByName(podList))
      // The list's own resourceVersion, not an item's - resuming from an item's version can silently
      // skip the events between it and the end of the list.
      state.resourceVersion.set(podList.metadata.flatMap(_.resourceVersion))
      state.synced.trySuccess(Done)
      ()
    }

    listed.flatMap(_ => startWatchStream(setup, labelSelector, state))
  }

  /**
   * The response to a pod list request, if it arrives before `deadline`. A response that only arrives
   * after that is discarded, so that its connection is released.
   */
  private[kubernetes] def listResponseWithin(response: Future[HttpResponse], deadline: Deadline)
      : Future[HttpResponse] = {
    val promise = Promise[HttpResponse]()
    val timeoutCancellable = system.scheduler.scheduleOnce(deadline.timeLeft.max(Duration.Zero)) {
      promise.tryFailure(
        new TimeoutException(s"Kubernetes API pod list request timed out after ${settings.watchListTimeout}"))
    }
    response.onComplete {
      case scala.util.Success(resp) =>
        timeoutCancellable.cancel()
        if (!promise.trySuccess(resp)) resp.discardEntityBytes()
      case scala.util.Failure(ex) =>
        timeoutCancellable.cancel()
        promise.tryFailure(ex)
    }
    promise.future
  }

  /**
   * Reads the pod list out of the response to a list request. The status is checked first: the entity of
   * an unsuccessful response is discarded rather than buffered. The entity of a successful one is read
   * until `deadline` at the latest and up to `watch-max-list-length` bytes. Whichever way this fails, the
   * entity stream is cancelled or drained, so the connection is released and a later attempt can succeed.
   */
  private[kubernetes] def readPodList(response: HttpResponse, deadline: Deadline): Future[PodList] =
    response.status match {
      case StatusCodes.OK =>
        response.entity.dataBytes
          .limitWeighted(settings.watchMaxListLength)(_.length.toLong)
          .completionTimeout(deadline.timeLeft.max(1.milli))
          .runFold(ByteString.newBuilder)(_ ++= _)
          .recoverWith {
            case _: StreamLimitReachedException =>
              Future.failed(new KubernetesApiException(
                s"Pod list from the Kubernetes API is larger than watch-max-list-length " +
                s"[${settings.watchMaxListLength} bytes]"))
            case _: TimeoutException =>
              Future.failed(new TimeoutException(
                s"Kubernetes API pod list was not read within ${settings.watchListTimeout}"))
          }
          .flatMap(bytes => Unmarshal(HttpEntity(ContentTypes.`application/json`, bytes.result())).to[PodList])
      case other =>
        response.discardEntityBytes()
        if (other == StatusCodes.Forbidden)
          log.warning("Forbidden to list pods with the Kubernetes API; check RBAC settings")
        else
          log.warning("Pod list failed with status [{}]", other)
        Future.failed(new KubernetesApiException(s"Pod list failed with status $other"))
    }

  /**
   * Opens the watch stream from the current resource version. The returned future fails if the request
   * could not be made or was refused; it succeeds once the stream is running, or the restart that a
   * `410 Gone` calls for has been scheduled.
   */
  private def startWatchStream(setup: KubernetesSetup, labelSelector: String, state: WatchState): Future[Unit] = {
    val resourceVersion = state.resourceVersion.get()
    optionToFuture(
      watchRequest(setup.apiToken, setup.podNamespace, labelSelector, resourceVersion),
      s"Unable to form request; check Kubernetes environment (expecting env vars ${settings.apiServiceHostEnvName}, ${settings.apiServicePortEnvName})"
    ).flatMap { request =>
      log.debug("Starting watch stream with resourceVersion: [{}]", resourceVersion)
      watchSingleRequest(request, setup.clientHttpsConnectionContext).map { response =>
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
            // e.g. 403 when the service account may list pods but not watch them. Drop the version so the
            // retry goes back through a full list; resuming the watch alone would leave the cache frozen
            // at whatever the last list returned, for as long as the watch keeps failing.
            response.discardEntityBytes()
            state.resourceVersion.set(None)
            throw new KubernetesApiException(s"Watch request failed with status $other")
        }
      }
    }
  }

  /**
   * Sends a watch request over a connection of its own rather than through the host connection pool. A
   * watch holds its connection for as long as the stream runs, and the pool only has
   * `pekko.http.host-connection-pool.max-connections` (4 by default) per host, so a few watches would
   * starve every other request to the API server, the initial lists and further watches included.
   */
  private[kubernetes] def watchSingleRequest(
      request: HttpRequest,
      connectionContext: ConnectionContext): Future[HttpResponse] =
    Source
      .single(request)
      .via(
        http.outgoingConnectionUsingContext(
          request.uri.authority.host.address(),
          request.uri.effectivePort,
          connectionContext))
      .runWith(Sink.head)

  private[kubernetes] def processWatchStream(
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
        // An event that cannot be applied fails the stream, so that none of the events after it are
        // applied either: they would advance the resume point past a change the cache does not have.
        val event =
          try JsonFormat.watchEventFormat.read(line.parseJson)
          catch {
            case NonFatal(ex) =>
              throw new WatchEventNotAppliedException(s"Failed to parse watch event: ${ex.getMessage}")
          }
        processWatchEvent(event, state)
      })(Keep.both)
      .run()

    registerWatchStream(state, killSwitch)

    done.onComplete { result =>
      state.killSwitch.set(None)
      val delay = result match {
        case scala.util.Success(_) =>
          log.info("Watch stream completed, reconnecting")
          settings.watchReconnectDelay
        case scala.util.Failure(ex: WatchEventNotAppliedException) =>
          // Not a disconnect: the cache has missed a change, so the version it would resume from no longer
          // describes it. Drop the version, so that the restart replaces the cache from a full list.
          log.warning("Watch event could not be applied: [{}], will resync from a fresh list", ex.getMessage)
          state.resourceVersion.set(None)
          settings.watchOnErrorReconnectDelay
        case scala.util.Failure(ex) =>
          // a transport failure loses no event that was received, so the watch resumes from the last one
          log.warning("Watch stream failed: [{}], reconnecting", ex.getMessage)
          settings.watchOnErrorReconnectDelay
      }
      scheduleWatchRestart(setup, labelSelector, state, delay)
    }
  }

  /**
   * Records the kill switch of a newly started watch stream. If the watch was stopped - by shutdown, or
   * because it was idle - while the stream was being started, that will have missed this kill switch, so
   * the stream is shut down here instead of being left open until the API server closes it.
   */
  private[kubernetes] def registerWatchStream(state: WatchState, killSwitch: UniqueKillSwitch): Unit = {
    state.killSwitch.set(Some(killSwitch))
    if (isStopped(state)) state.killSwitch.getAndSet(None).foreach(_.shutdown())
  }

  /**
   * Applies a watch event to the cache and advances the version the watch resumes from. Throws a
   * `WatchEventNotAppliedException`, leaving both as they were, for an event that cannot be applied.
   */
  private[kubernetes] def processWatchEvent(event: WatchEvent, state: WatchState): Unit = {
    def withName(f: String => Unit): Unit = event.pod.metadata.flatMap(_.name) match {
      case Some(podName) =>
        f(podName)
        // each event carries the version to resume the watch from
        event.pod.metadata.flatMap(_.resourceVersion).foreach(rv => state.resourceVersion.set(Some(rv)))
      case None =>
        // it cannot be told which pod changed, so the change is not applied and the cache needs a resync
        throw new WatchEventNotAppliedException(
          s"[${event.eventType}] watch event for a pod without metadata.name")
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
      case Bookmark =>
        // carries nothing but the version to resume from, which keeps a quiet watch resumable
        event.pod.metadata.flatMap(_.resourceVersion).foreach(rv => state.resourceVersion.set(Some(rv)))
      case Error =>
        // The server signals an error - most often an expired resourceVersion - and closes the stream.
        // Drop the version so that the reconnect resyncs from a full list rather than resuming.
        log.warning(
          "Watch stream reported an ERROR event [{}], will resync from a fresh list",
          event.errorMessage.getOrElse("no message"))
        state.resourceVersion.set(None)
    }
  }

  private def scheduleWatchRestart(
      setup: KubernetesSetup,
      labelSelector: String,
      state: WatchState,
      delay: FiniteDuration): Unit =
    if (isStopped(state)) {
      log.debug("Not restarting watch for label selector: [{}], it has been stopped", labelSelector)
    } else {
      system.scheduler.scheduleOnce(delay) {
        if (!isStopped(state)) log.info("Restarting watch for label selector: [{}]", labelSelector)
        runWatch(setup, labelSelector, state)
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
      // bookmarks let the server advance our resource version while no pods change
      val params = Seq("labelSelector" -> labelSelector, "watch" -> "true", "allowWatchBookmarks" -> "true") ++
        resourceVersion.map(rv => "resourceVersion" -> rv)
      val query = Uri.Query(params: _*)
      val uri = Uri.from(scheme = "https", host = host, port = port).withPath(path).withQuery(query)

      val authHeaders = Seq(Authorization(OAuth2BearerToken(token)))
      val acceptEncodingHeader = HttpEncodings.getForKey(settings.httpRequestAcceptEncoding)
        .map(httpEncoding => AcceptEncoding.create(httpEncoding))
      HttpRequest(uri = uri, headers = authHeaders ++ acceptEncodingHeader)
    }
  }

  /**
   * Keyed by pod name. Pods without a name are dropped rather than collapsed onto a shared key, which
   * would let them overwrite each other.
   */
  private def podsByName(podList: PodList): Map[String, PodList.Pod] =
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
