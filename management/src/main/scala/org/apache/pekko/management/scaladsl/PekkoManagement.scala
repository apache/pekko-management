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

package org.apache.pekko.management.scaladsl

import org.apache.pekko
import pekko.Done
import pekko.actor.{
  ActorSystem,
  ClassicActorSystemProvider,
  ExtendedActorSystem,
  Extension,
  ExtensionId,
  ExtensionIdProvider
}
import pekko.event.Logging
import pekko.http.javadsl.server.directives.SecurityDirectives.ProvidedCredentials
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.Uri
import pekko.http.scaladsl.server.Directives.{ authenticateBasicAsync, pathPrefix, rawPathPrefix }
import pekko.http.scaladsl.server.{ Directive, Directives, PathMatchers, Route }
import pekko.http.scaladsl.server.directives.Credentials
import pekko.http.scaladsl.settings.ServerSettings
import pekko.management.{ javadsl, ManagementLogMarker, NamedRouteProvider, PekkoManagementSettings }
import pekko.util.ManifestInfo
import pekko.util.FutureConverters._
import pekko.util.OptionConverters._

import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.immutable
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal

object PekkoManagement extends ExtensionId[PekkoManagement] with ExtensionIdProvider {
  override def lookup: PekkoManagement.type = PekkoManagement

  override def get(system: ActorSystem): PekkoManagement = super.get(system)

  override def get(system: ClassicActorSystemProvider): PekkoManagement = super.get(system)

  override def createExtension(system: ExtendedActorSystem): PekkoManagement =
    new PekkoManagement()(system)
}

final class PekkoManagement(implicit private[pekko] val system: ExtendedActorSystem) extends Extension {

  ManifestInfo(system).checkSameVersion(
    productName = "Pekko Management",
    dependencies = List(
      "pekko-discovery-consul",
      "pekko-discovery-aws-api",
      "pekko-discovery-marathon-api",
      "pekko-discovery-aws-api-async",
      "pekko-discovery-kubernetes-api",
      "pekko-management",
      "pekko-management-cluster-bootstrap",
      "pekko-management-cluster-http"),
    logWarning = true)

  private val log = Logging.withMarker(system, getClass)
  val settings: PekkoManagementSettings = new PekkoManagementSettings(system.settings.config)

  import system.dispatcher

  private val routeProviders: immutable.Seq[ManagementRouteProvider] = loadRouteProviders()

  private val bindingFuture = new AtomicReference[Future[Http.ServerBinding]]()
  private val selfUriPromise = Promise[Uri]() // TODO has to keep config as well as the Uri, so we can reject 2nd calls with diff uri

  private def providerSettings: ManagementRouteProviderSettings = {
    // port is on purpose never inferred from protocol, because this HTTP endpoint is not the "main" one for the app
    val protocol = "http" // changed to "https" if ManagementRouteProviderSettings.withHttpsConnectionContext is used
    val host = settings.Http.Hostname
    val port = settings.Http.Port
    val path = settings.Http.BasePath.fold("")("/" + _)
    val selfBaseUri =
      Uri.from(scheme = protocol, host = host, port = port, path = path)
    ManagementRouteProviderSettings(selfBaseUri, settings.Http.RouteProvidersReadOnly)
  }

  /**
   * Get the routes for the HTTP management endpoint.
   *
   * This method can be used to embed the Pekko management routes in an existing Pekko HTTP server.
   *
   * @throws java.lang.IllegalArgumentException if routes not configured for pekko management
   */
  def routes: Route = prepareCombinedRoutes(providerSettings)

  /**
   * Amend the [[ManagementRouteProviderSettings]] and get the routes for the HTTP management endpoint.
   *
   * Use this when adding authentication and HTTPS.
   *
   * This method can be used to embed the Pekko management routes in an existing Pekko HTTP server.
   *
   * @throws java.lang.IllegalArgumentException if routes not configured for pekko management
   */
  def routes(transformSettings: ManagementRouteProviderSettings => ManagementRouteProviderSettings): Route =
    prepareCombinedRoutes(transformSettings(providerSettings))

  /**
   * Start a Pekko HTTP server to serve the HTTP management endpoint.
   */
  def start(): Future[Uri] =
    start(identity)

  /**
   * Amend the [[ManagementRouteProviderSettings]] and start a Pekko HTTP server to serve
   * the HTTP management endpoint.
   *
   * Use this when adding authentication and HTTPS.
   */
  def start(transformSettings: ManagementRouteProviderSettings => ManagementRouteProviderSettings): Future[Uri] = {
    val serverBindingPromise = Promise[Http.ServerBinding]()
    if (bindingFuture.compareAndSet(null, serverBindingPromise.future)) {
      try {
        val effectiveBindHostname = settings.Http.EffectiveBindHostname
        val effectiveBindPort = settings.Http.EffectiveBindPort
        val effectiveProviderSettings = transformSettings(providerSettings)

        // TODO instead of binding to hardcoded things here, discovery could also be used for this binding!
        // Basically: "give me the SRV host/port for the port called `pekko-bootstrap`"
        // discovery.lookup("_pekko-bootstrap" + ".effective-name.default").find(myaddress)
        // ----
        // FIXME -- think about the style of how we want to make these available

        log.info("Binding Pekko Management (HTTP) endpoint to: {}:{}", effectiveBindHostname, effectiveBindPort)

        val combinedRoutes = prepareCombinedRoutes(effectiveProviderSettings)

        val baseBuilder = Http()
          .newServerAt(effectiveBindHostname, effectiveBindPort)
          .withSettings(ServerSettings(system).withRemoteAddressHeader(true))

        val securedBuilder = effectiveProviderSettings.httpsConnectionContext match {
          case Some(httpsContext) => baseBuilder.enableHttps(httpsContext)
          case None               => baseBuilder
        }
        val serverFutureBinding = securedBuilder.bind(combinedRoutes)

        serverBindingPromise.completeWith(serverFutureBinding).future.flatMap { binding =>
          val boundPort = binding.localAddress.getPort
          log.info(
            ManagementLogMarker.boundHttp(s"$effectiveBindHostname:$boundPort"),
            "Bound Pekko Management (HTTP) endpoint to: {}:{}",
            effectiveBindHostname,
            boundPort)
          selfUriPromise.success(effectiveProviderSettings.selfBaseUri.withPort(boundPort)).future
        }

      } catch {
        case NonFatal(ex) =>
          log.warning(ex.getMessage)
          Future.failed(new IllegalArgumentException("Failed to start Pekko Management HTTP endpoint.", ex))
      }
    } else selfUriPromise.future
  }

  private def prepareCombinedRoutes(providerSettings: ManagementRouteProviderSettings): Route = {
    val basePath: Directive[Unit] = {
      val pathPrefixName = settings.Http.BasePath.getOrElse("")
      if (pathPrefixName.isEmpty) {
        rawPathPrefix(pathPrefixName)
      } else {
        pathPrefix(PathMatchers.separateOnSlashes(pathPrefixName))
      }
    }

    def wrapWithAuthenticatorIfPresent(inner: Route): Route = {
      val providerSettingsImpl = providerSettings.asInstanceOf[ManagementRouteProviderSettingsImpl]
      (providerSettingsImpl.scaladslAuth, providerSettingsImpl.javadslAuth) match {
        case (None, None) =>
          inner

        case (Some(asyncAuthenticator), None) =>
          authenticateBasicAsync[String](realm = "secured", asyncAuthenticator)(_ => inner)

        case (None, Some(auth)) =>
          def credsToJava(cred: Credentials): Optional[ProvidedCredentials] = cred match {
            case provided: Credentials.Provided => Optional.of(ProvidedCredentials(provided))
            case _                              => Optional.empty()
          }
          authenticateBasicAsync(realm = "secured", c => auth.apply(credsToJava(c)).asScala.map(_.toScala)).optional
            .apply(_ => inner)

        case (Some(_), Some(_)) =>
          throw new IllegalStateException("Unexpected that both scaladsl and javadsl auth were defined")
      }
    }

    val combinedRoutes = routeProviders.map { provider =>
      log.info("Including HTTP management routes for {}", Logging.simpleName(provider))
      provider.routes(providerSettings)
    }

    if (combinedRoutes.nonEmpty) {
      basePath {
        wrapWithAuthenticatorIfPresent(Directives.concat(combinedRoutes: _*))
      }
    } else
      throw new IllegalArgumentException(
        "No routes configured for pekko management! " +
        "Double check your `pekko.management.http.routes` config.")
  }

  @tailrec
  def stop(): Future[Done] = {
    val binding = bindingFuture.get()

    if (binding == null) {
      Future.successful(Done)
    } else if (bindingFuture.compareAndSet(binding, null)) {
      binding.flatMap(_.unbind()).map((_: Any) => Done)
    } else stop() // retry, CAS was not successful, someone else completed the stop()
  }

  private def loadRouteProviders(): immutable.Seq[ManagementRouteProvider] = {
    val dynamicAccess = system.dynamicAccess

    // since often the providers are Pekko extensions, we initialize them here as the ActorSystem would otherwise
    settings.Http.RouteProviders.map {
      case NamedRouteProvider(name, fqcn) =>
        dynamicAccess
          .getObjectFor[ExtensionIdProvider](fqcn)
          .recoverWith {
            case _ => dynamicAccess.createInstanceFor[ExtensionIdProvider](fqcn, Nil)
          }
          .recoverWith[ExtensionIdProvider] {
            case _: ClassCastException | _: NoSuchMethodException =>
              dynamicAccess.createInstanceFor[ExtensionIdProvider](fqcn, (classOf[ExtendedActorSystem], system) :: Nil)
          }
          .recoverWith[AnyRef] {
            case _: ClassCastException | _: NoSuchMethodException =>
              dynamicAccess.createInstanceFor[ManagementRouteProvider](fqcn, Nil)
          }
          .recoverWith[AnyRef] {
            case _: ClassCastException | _: NoSuchMethodException =>
              dynamicAccess.createInstanceFor[ManagementRouteProvider](
                fqcn,
                (classOf[ExtendedActorSystem], system) :: Nil)
          }
          .recoverWith[AnyRef] {
            case _: ClassCastException | _: NoSuchMethodException =>
              dynamicAccess.createInstanceFor[javadsl.ManagementRouteProvider](fqcn, Nil)
          }
          .recoverWith[AnyRef] {
            case _: ClassCastException | _: NoSuchMethodException =>
              dynamicAccess
                .createInstanceFor[javadsl.ManagementRouteProvider](fqcn, (classOf[ExtendedActorSystem], system) :: Nil)
          } match {
          case Success(p: ExtensionIdProvider) =>
            system.registerExtension(p.lookup) match {
              case provider: ManagementRouteProvider         => provider
              case provider: javadsl.ManagementRouteProvider => new ManagementRouteProviderAdapter(provider)
              case other =>
                throw new RuntimeException(
                  s"Extension [$fqcn] should create a 'ManagementRouteProvider' but was " +
                  s"[${other.getClass.getName}]")
            }

          case Success(provider: ManagementRouteProvider) =>
            provider

          case Success(provider: javadsl.ManagementRouteProvider) =>
            new ManagementRouteProviderAdapter(provider)

          case Success(_) =>
            throw new RuntimeException(
              s"[$fqcn] is not an 'ExtensionIdProvider', 'ExtensionId' or 'ManagementRouteProvider'")

          case Failure(problem) =>
            throw new RuntimeException(s"While trying to load route provider extension [$name = $fqcn]", problem)
        }
    }
  }

}
