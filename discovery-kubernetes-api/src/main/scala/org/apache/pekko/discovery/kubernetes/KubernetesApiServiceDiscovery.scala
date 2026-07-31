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
import java.nio.file.{ Files, Paths }
import java.security.{ KeyStore, SecureRandom }
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.{ KeyManager, KeyManagerFactory, SSLContext, TrustManager }

import scala.collection.immutable
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.{ NoStackTrace, NonFatal }

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.annotation.InternalApi
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.kubernetes.JsonFormat._
import pekko.discovery.kubernetes.KubernetesApiServiceDiscovery.{ targets, KubernetesApiException }
import pekko.discovery.kubernetes.PodList.{ Added, Deleted, Modified, WatchEvent }
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
import pekko.stream.scaladsl.Sink
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
}

/**
 * An alternative implementation that uses the Kubernetes API. The main advantage of this method is that it allows
 * you to define readiness/health checks that don't affect the bootstrap mechanism.
 */
class KubernetesApiServiceDiscovery(settings: Settings)(
    implicit system: ActorSystem) extends ServiceDiscovery {

  import KubernetesApiServiceDiscovery.KubernetesSetup
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

  // Watch mode state: cache of pods keyed by pod name, and resource version for reconnection
  private val podCache = new AtomicReference[immutable.Map[String, PodList.Pod]](immutable.Map.empty)
  @volatile private var watchResourceVersion: Option[String] = None
  private val startedWatches = new ConcurrentHashMap[String, Boolean]()

  override def lookup(query: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    if (settings.apiPollMode == "watch") {
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

      response <- http.singleRequest(request, setup.clientHttpsConnectionContext).map(decodeResponse)

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
    val certificates = PemManagersProvider.loadCertificates(settings.apiCaPath)

    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    val keyStore = KeyStore.getInstance("PKCS12")
    keyStore.load(null)
    factory.init(keyStore, Array.empty)
    val km: Array[KeyManager] = factory.getKeyManagers
    val tm: Array[TrustManager] =
      PemManagersProvider.buildTrustManagers(certificates)
    val random: SecureRandom = new SecureRandom
    val sslContext = SSLContext.getInstance(settings.tlsVersion)
    sslContext.init(km, tm, random)
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

    for {
      setup <- kubernetesSetup

      _ <- {
        val watchKey = s"${setup.podNamespace}:$labelSelector"
        if (Option(startedWatches.putIfAbsent(watchKey, true)).isEmpty) {
          log.info(
            "Starting watch for pods with label selector: [{}]. Namespace: [{}]",
            labelSelector,
            setup.podNamespace)
          startWatch(setup, labelSelector)
        } else {
          Future.successful(())
        }
      }

    } yield {
      val cachedPods = podCache.get()
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
  }

  private def startWatch(setup: KubernetesApiServiceDiscovery.KubernetesSetup, labelSelector: String): Future[Unit] = {
    val token = setup.apiToken
    val namespace = setup.podNamespace
    val httpsContext = setup.clientHttpsConnectionContext

    // Perform initial list to populate cache and get resourceVersion
    val listFuture = for {
      listReq <- optionToFuture(
        listRequest(token, namespace, labelSelector),
        s"Unable to form request; check Kubernetes environment (expecting env vars ${settings.apiServiceHostEnvName}, ${settings.apiServicePortEnvName})")
      listResp <- http.singleRequest(listReq, httpsContext).map(decodeResponse)
      bytes <- listResp.entity.dataBytes.runFold(ByteString.empty)(_ ++ _)
      podList <- {
        listResp.status match {
          case StatusCodes.OK =>
            Unmarshal(HttpEntity(ContentTypes.`application/json`, bytes)).to[PodList]
          case other =>
            log.warning("Initial list failed with status [{}]", other)
            Future.successful(PodList(Nil))
        }
      }
    } yield {
      updatePodCache(podList)
      watchResourceVersion = podList.items
        .flatMap(_.metadata.flatMap(_.resourceVersion))
        .lastOption
        .orElse(Some("0"))
      podList
    }

    listFuture.flatMap { _ =>
      startWatchStream(token, namespace, labelSelector, httpsContext)
    }.recover {
      case NonFatal(e) =>
        log.error(e, "Failed to start watch for pods with label selector: [{}]", labelSelector)
        scheduleWatchRestart(setup, labelSelector, settings.watchOnErrorReconnectDelay)
    }
  }

  private def startWatchStream(
      token: String,
      namespace: String,
      labelSelector: String,
      httpsContext: HttpsConnectionContext): Future[Unit] = {
    optionToFuture(
      watchRequest(token, namespace, labelSelector, watchResourceVersion),
      s"Unable to form request; check Kubernetes environment (expecting env vars ${settings.apiServiceHostEnvName}, ${settings.apiServicePortEnvName})"
    ).flatMap { request =>
      log.debug("Starting watch stream with resourceVersion: [{}]", watchResourceVersion)
      http.singleRequest(request, httpsContext).flatMap { response =>
        response.status match {
          case StatusCodes.OK =>
            log.info("Watch stream started for label selector: [{}]", labelSelector)
            val decoded = decodeResponse(response)
            processWatchStream(decoded, token, namespace, labelSelector, httpsContext)
            Future.successful(())
          case StatusCodes.Gone =>
            // resourceVersion too old, reset and restart
            log.warning("Watch resourceVersion expired (410 Gone), restarting with fresh list")
            watchResourceVersion = None
            val setup = KubernetesApiServiceDiscovery.KubernetesSetup(namespace, token, httpsContext)
            scheduleWatchRestart(setup, labelSelector, settings.watchReconnectDelay)
            Future.successful(())
          case other =>
            log.warning("Watch request failed with status [{}]", other)
            Future.failed(new KubernetesApiException(s"Watch request failed with status $other"))
        }
      }
    }
  }

  private def processWatchStream(
      response: HttpResponse,
      token: String,
      namespace: String,
      labelSelector: String,
      httpsContext: HttpsConnectionContext): Unit = {
    response.entity.dataBytes
      .map(_.utf8String)
      .statefulMapConcat { () =>
        var buffer = ""
        chunk => {
          buffer += chunk
          val lines = buffer.split("\n", -1).toList
          buffer = lines.last
          lines.init.filter(_.nonEmpty)
        }
      }
      .map { line =>
        Try(JsonFormat.watchEventFormat.read(line.parseJson))
      }
      .mapConcat {
        case scala.util.Success(event) =>
          processWatchEvent(event)
          Nil
        case scala.util.Failure(ex) =>
          log.warning("Failed to parse watch event: [{}]", ex.getMessage)
          Nil
      }
      .runWith(Sink.ignore)
      .onComplete {
        case scala.util.Success(_) =>
          log.info("Watch stream completed, reconnecting")
          val setup = KubernetesApiServiceDiscovery.KubernetesSetup(namespace, token, httpsContext)
          scheduleWatchRestart(setup, labelSelector, settings.watchReconnectDelay)
        case scala.util.Failure(ex) =>
          log.warning("Watch stream failed: [{}], reconnecting", ex.getMessage)
          val setup = KubernetesApiServiceDiscovery.KubernetesSetup(namespace, token, httpsContext)
          scheduleWatchRestart(setup, labelSelector, settings.watchOnErrorReconnectDelay)
      }
  }

  private def processWatchEvent(event: WatchEvent): Unit = {
    val podName = event.pod.metadata.flatMap(_.name).getOrElse("unknown")
    event.eventType match {
      case Added | Modified =>
        log.debug("Watch event [{}] for pod [{}]", event.eventType, podName)
        podCache.updateAndGet(new java.util.function.UnaryOperator[immutable.Map[String, PodList.Pod]] {
          override def apply(cache: immutable.Map[String, PodList.Pod]): immutable.Map[String, PodList.Pod] =
            cache + (podName -> event.pod)
        })
        event.pod.metadata.flatMap(_.resourceVersion).foreach(rv => watchResourceVersion = Some(rv))
      case Deleted =>
        log.debug("Watch event DELETED for pod [{}]", podName)
        podCache.updateAndGet(new java.util.function.UnaryOperator[immutable.Map[String, PodList.Pod]] {
          override def apply(cache: immutable.Map[String, PodList.Pod]): immutable.Map[String, PodList.Pod] =
            cache - podName
        })
        event.pod.metadata.flatMap(_.resourceVersion).foreach(rv => watchResourceVersion = Some(rv))
      case other =>
        log.warning("Unexpected watch event type [{}] for pod [{}]", other, podName)
    }
  }

  private def scheduleWatchRestart(
      setup: KubernetesApiServiceDiscovery.KubernetesSetup,
      labelSelector: String,
      delay: FiniteDuration): Unit = {
    system.scheduler.scheduleOnce(delay) {
      log.info("Restarting watch for label selector: [{}]", labelSelector)
      startWatchStream(setup.apiToken, setup.podNamespace, labelSelector, setup.clientHttpsConnectionContext)
        .recover {
          case NonFatal(e) =>
            log.error(e, "Watch restart failed for label selector: [{}]", labelSelector)
            scheduleWatchRestart(setup, labelSelector, settings.watchOnErrorReconnectDelay)
        }
    }
  }

  private def listRequest(token: String, namespace: String, labelSelector: String): Option[HttpRequest] = {
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
  }

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

  private def updatePodCache(podList: PodList): Unit = {
    val runningPods = podList.items.collect {
      case pod
          if pod.metadata.flatMap(_.deletionTimestamp).isEmpty &&
          pod.status.flatMap(_.phase).contains("Running") =>
        pod.metadata.flatMap(_.name).getOrElse("unknown") -> pod
    }.toMap
    podCache.set(runningPods)
  }
}
