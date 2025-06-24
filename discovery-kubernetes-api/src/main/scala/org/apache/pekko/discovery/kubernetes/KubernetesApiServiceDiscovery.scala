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

package org.apache.pekko.discovery.kubernetes

import java.net.InetAddress
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Paths }
import java.security.{ KeyStore, SecureRandom }
import scala.collection.immutable.Seq
import scala.concurrent.Future
import scala.concurrent.duration.FiniteDuration
import scala.util.Try
import scala.util.control.{ NoStackTrace, NonFatal }

import org.apache.pekko
import org.apache.pekko.http.javadsl.model.headers.AcceptEncoding
import org.apache.pekko.http.scaladsl.coding.Coders
import pekko.actor.ActorSystem
import pekko.annotation.InternalApi
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.kubernetes.JsonFormat._
import pekko.discovery.kubernetes.KubernetesApiServiceDiscovery.{ targets, KubernetesApiException }
import pekko.discovery.{ Lookup, ServiceDiscovery }
import pekko.event.{ LogSource, Logging }
import pekko.http.scaladsl.HttpsConnectionContext
import pekko.http.scaladsl._
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers.{ Authorization, HttpEncodings, OAuth2BearerToken }
import pekko.http.scaladsl.unmarshalling.Unmarshal
import pekko.pki.kubernetes.PemManagersProvider

import javax.net.ssl.{ KeyManager, KeyManagerFactory, SSLContext, TrustManager }

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
          Seq(None)
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

}

/**
 * An alternative implementation that uses the Kubernetes API. The main advantage of this method is that it allows
 * you to define readiness/health checks that don't affect the bootstrap mechanism.
 */
class KubernetesApiServiceDiscovery(settings: Settings)(
    implicit system: ActorSystem) extends ServiceDiscovery {

  import system.dispatcher

  private val http = Http()

  def this()(implicit system: ActorSystem) = this(Settings(system))

  private val log = Logging(system, getClass)(LogSource.fromClass)

  private val sslContext = {
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
    sslContext
  }

  private val clientSslContext: HttpsConnectionContext = ConnectionContext.httpsClient(sslContext)

  log.debug("Settings {}", settings)

  override def lookup(query: Lookup, resolveTimeout: FiniteDuration): Future[Resolved] = {
    val labelSelector = settings.podLabelSelector(query.serviceName)

    log.info(
      "Querying for pods with label selector: [{}]. Namespace: [{}]. Port: [{}]",
      labelSelector,
      podNamespace,
      query.portName)

    for {
      request <- optionToFuture(
        podRequest(apiToken, podNamespace, labelSelector),
        s"Unable to form request; check Kubernetes environment (expecting env vars ${settings.apiServiceHostEnvName}, ${settings.apiServicePortEnvName})")

      response <- {
        val f = http.singleRequest(request, clientSslContext)
        f.onComplete {
          case scala.util.Failure(exception) =>
            log.error(exception, "Lookup failed to communicate with Kubernetes API server.")
          case scala.util.Success(_) =>
            log.info("Lookup successfully communicated with Kubernetes API server.")
        }
        f.map(decodeResponse)
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
        targets(podList, query.portName, podNamespace, settings.podDomain, settings.rawIp, settings.containerName)
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

  private val apiToken = readConfigVarFromFilesystem(settings.apiTokenPath, "api-token").getOrElse("")

  private val podNamespace = settings.podNamespace
    .orElse(readConfigVarFromFilesystem(settings.podNamespacePath, "pod-namespace"))
    .getOrElse("default")

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
}
