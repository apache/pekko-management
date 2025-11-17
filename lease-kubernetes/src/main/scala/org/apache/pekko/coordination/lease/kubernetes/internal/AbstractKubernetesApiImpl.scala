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

package org.apache.pekko.coordination.lease.kubernetes.internal

import org.apache.pekko
import pekko.Done
import pekko.actor.{ ActorSystem, Scheduler }
import pekko.annotation.InternalApi
import pekko.coordination.lease.kubernetes.{ KubernetesApi, KubernetesSettings, LeaseResource }
import pekko.coordination.lease.{ LeaseException, LeaseTimeoutException }
import pekko.dispatch.ExecutionContexts
import pekko.event.{ LogSource, Logging, LoggingAdapter }
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.model.headers.{ Authorization, OAuth2BearerToken }
import pekko.http.scaladsl.unmarshalling.Unmarshal
import pekko.http.scaladsl.{ ConnectionContext, Http, HttpExt, HttpsConnectionContext }
import pekko.pattern.{ after, RetrySupport }
import pekko.pki.kubernetes.PemManagersProvider
import pekko.stream.scaladsl.{ FileIO, Keep, Sink }
import pekko.util.ByteString

import java.nio.file.{ Files, Paths }
import java.security.{ KeyStore, SecureRandom }
import javax.net.ssl.{ KeyManager, KeyManagerFactory, SSLContext, TrustManager }
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.control.{ NoStackTrace, NonFatal }

/**
 * Could be shared between leases: https://github.com/akka/akka-management/issues/680
 * INTERNAL API
 */
@InternalApi private[pekko] abstract class AbstractKubernetesApiImpl(system: ActorSystem, settings: KubernetesSettings)
    extends KubernetesApi
    with KubernetesJsonSupport {

  import system.dispatcher

  protected implicit val sys: ActorSystem = system
  protected val log: LoggingAdapter = Logging(system, getClass)(LogSource.fromClass)
  private val http: HttpExt = Http()(system)

  private lazy val sslContext: SSLContext = {
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

  private lazy val clientSslContext: HttpsConnectionContext = ConnectionContext.httpsClient(sslContext)

  protected val namespace: Future[String] = {
    settings.namespace match {
      case Some(nSpace) => Future.successful(nSpace)
      case _ =>
        readConfigVarFromFilesystem(settings.namespacePath, "namespace").map(_.getOrElse("default"))(
          ExecutionContexts.parasitic)
    }
  }

  protected val scheme: String = if (settings.secure) "https" else "http"
  private[pekko] def apiToken() = readConfigVarFromFilesystem(settings.apiTokenPath, "api-token").map(
    _.getOrElse(""))(ExecutionContexts.parasitic)
  private def headers() = if (settings.secure) {
    apiToken().map { token =>
      immutable.Seq(Authorization(OAuth2BearerToken(token)))
    }(ExecutionContexts.parasitic)
  } else
    Future.successful(Nil)

  log.debug("kubernetes access namespace: {}. Secure: {}", namespace, settings.secure)

  protected def createLeaseResource(name: String): Future[Option[LeaseResource]]

  protected def getLeaseResource(name: String): Future[Option[LeaseResource]]

  protected def pathForLease(name: String): Future[Uri.Path]

  override def readOrCreateLeaseResource(name: String): Future[LeaseResource] = {
    // TODO backoff retry
    val maxTries = 5

    def loop(tries: Int = 0): Future[LeaseResource] = {
      log.debug("Trying to create lease {}", tries)
      for {
        olr <- getLeaseResource(name)
        lr <- olr match {
          case Some(found) =>
            log.debug("{} already exists. Returning {}", name, found)
            Future.successful(found)
          case None =>
            log.info("lease {} does not exist, creating", name)
            createLeaseResource(name).flatMap {
              case Some(created) => Future.successful(created)
              case None =>
                if (tries < maxTries) loop(tries + 1)
                else Future.failed(new LeaseException(s"Unable to create or read lease after $maxTries tries"))
            }
        }
      } yield lr
    }

    loop()
  }

  private[pekko] def removeLease(name: String): Future[Done] = {
    for {
      leasePath <- pathForLease(name)
      request <- requestForPath(leasePath, HttpMethods.DELETE)
      response <- makeRequest(request, s"Timed out removing lease [$name]. It is not known if the remove happened")
      result <- response.status match {
        case StatusCodes.OK =>
          log.debug("Lease deleted {}", name)
          response.discardEntityBytes()
          Future.successful(Done)
        case StatusCodes.NotFound =>
          log.debug("Lease already deleted {}", name)
          response.discardEntityBytes()
          Future.successful(Done) // already deleted
        case StatusCodes.Unauthorized =>
          handleUnauthorized(response)
        case unexpected =>
          Unmarshal(response.entity)
            .to[String]
            .flatMap(body => {
              Future.failed(
                new LeaseException(s"Unexpected status code when deleting lease. Status: $unexpected. Body: $body"))
            })
      }
    } yield result
  }

  protected def handleUnauthorized(response: HttpResponse): Future[Nothing] = {
    Unmarshal(response.entity)
      .to[String]
      .flatMap(body => {
        Future.failed(new LeaseException(
          s"Unauthorized to communicate with Kubernetes API server. See https://pekko.apache.org/docs/pekko-management/current/kubernetes-lease.html#role-based-access-control for setting up access control. Body: $body"))
      })
  }

  protected def requestForPath(
      path: Uri.Path,
      method: HttpMethod = HttpMethods.GET,
      entity: RequestEntity = HttpEntity.Empty): Future[HttpRequest] = {
    val uri = Uri.from(scheme = scheme, host = settings.apiServerHost, port = settings.apiServerPort).withPath(path)
    headers().map { headers =>
      HttpRequest(uri = uri, headers = headers, method = method, entity = entity)
    }(ExecutionContexts.parasitic)
  }

  private[pekko] def makeRawRequest(request: HttpRequest): Future[HttpResponse] = {
    if (settings.secure)
      http.singleRequest(request, clientSslContext)
    else
      http.singleRequest(request)
  }

  // This exception is being thrown/caught because we are forced to use Pekko 1.0.x's
  // version of RetrySupport.retry which only works on the attempt functions throwing
  // exceptions
  private case class UnauthorizedException(httpResponse: HttpResponse) extends Throwable with NoStackTrace

  protected def makeRequest(request: HttpRequest, timeoutMsg: String): Future[HttpResponse] = {
    // It's possible to legitimately get a 401 response due to kubernetes doing a token rotation
    implicit val scheduler: Scheduler = system.scheduler
    val response = RetrySupport.retry(
      () =>
        makeRawRequest(request: HttpRequest).flatMap { response =>
          if (response.status == StatusCodes.Unauthorized) {
            log.warning("Received status code 401 as response, retrying due to possible token rotation")
            Future.failed(UnauthorizedException(response))
          } else Future.successful(response)
        },
      settings.tokenRetrySettings.maxAttempts,
      settings.tokenRetrySettings.minBackoff,
      settings.tokenRetrySettings.maxBackoff,
      settings.tokenRetrySettings.randomFactor
    ).recover {
      case unauthorized: UnauthorizedException => unauthorized.httpResponse
    }

    // make sure we always consume response body (in case of timeout)
    val strictResponse = response.flatMap(_.toStrict(settings.bodyReadTimeout))

    val timeout = after(settings.apiServerRequestTimeout, using = system.scheduler)(
      Future.failed(new LeaseTimeoutException(s"$timeoutMsg. Is the API server up?")))

    Future.firstCompletedOf(Seq(strictResponse, timeout))
  }

  protected def readConfigVarFromFilesystem(path: String, name: String): Future[Option[String]] = {
    val file = Paths.get(path)
    if (Files.exists(file)) {
      try {
        FileIO.fromPath(file)
          .toMat(Sink.fold(ByteString.empty)(_ ++ _))(Keep.right)
          .run()
          .map(bs => Some(bs.utf8String))(ExecutionContexts.parasitic)
      } catch {
        case NonFatal(e) =>
          log.error(e, "Error reading {} from {}", name, path)
          Future.successful(None)
      }
    } else {
      log.warning("Unable to read {} from {} because it doesn't exist.", name, path)
      Future.successful(None)
    }
  }

}
