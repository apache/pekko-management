/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.management.cluster.bootstrap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.Extension
import org.apache.pekko.actor.ExtensionId
import org.apache.pekko.actor.ExtensionIdProvider
import org.apache.pekko.annotation.InternalApi
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.discovery.Discovery
import org.apache.pekko.discovery.ServiceDiscovery
import org.apache.pekko.event.Logging
import org.apache.pekko.management.cluster.bootstrap.contactpoint.HttpClusterBootstrapRoutes
import org.apache.pekko.management.cluster.bootstrap.internal.BootstrapCoordinator
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.management.scaladsl.ManagementRouteProvider
import org.apache.pekko.management.scaladsl.ManagementRouteProviderSettings
import org.apache.pekko.http.scaladsl.model.Uri
import org.apache.pekko.http.scaladsl.server.Route

import java.util.concurrent.atomic.AtomicReference
import scala.concurrent.duration._
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.TimeoutException
import scala.util.control.NonFatal

final class ClusterBootstrap(implicit system: ExtendedActorSystem) extends Extension with ManagementRouteProvider {

  import ClusterBootstrap.Internal._
  import system.dispatcher

  private val log = Logging(system, classOf[ClusterBootstrap])

  private final val bootstrapStep = new AtomicReference[BootstrapStep](NotRunning)

  val settings: ClusterBootstrapSettings = ClusterBootstrapSettings(system.settings.config, log)

  // used for initial discovery of contact points
  lazy val discovery: ServiceDiscovery =
    settings.contactPointDiscovery.discoveryMethod match {
      case "pekko.discovery" =>
        val discovery = Discovery(system).discovery
        log.info("Bootstrap using default `pekko.discovery` method: {}", Logging.simpleName(discovery))
        discovery

      case otherDiscoveryMechanism =>
        log.info("Bootstrap using `pekko.discovery` method: {}", otherDiscoveryMechanism)
        Discovery(system).loadServiceDiscovery(otherDiscoveryMechanism)
    }

  private val joinDecider: JoinDecider = {
    system.dynamicAccess
      .createInstanceFor[JoinDecider](
        settings.joinDecider.implClass,
        List((classOf[ActorSystem], system), (classOf[ClusterBootstrapSettings], settings)))
      .get
  }

  private[this] val _selfContactPointUri: Promise[Uri] = Promise()

  // autostart if the extension is loaded through the config extension list
  private val autostart =
    system.settings.config.getStringList("pekko.extensions").contains(classOf[ClusterBootstrap].getName)

  if (autostart) {
    log.info("ClusterBootstrap loaded through 'pekko.extensions' auto starting management and bootstrap.")
    // Pekko Management hosts the HTTP routes used by bootstrap
    // we can't let it block extension init, so run it in a different thread and let constructor complete
    Future {
      def autostartFailed(ex: Throwable): Unit = {
        log.error(ex, "Failed to autostart cluster bootstrap, terminating system")
        system.terminate()
      }
      try {
        PekkoManagement(system).start().failed.foreach(autostartFailed)
        ClusterBootstrap(system).start()
      } catch {
        case NonFatal(ex) => autostartFailed(ex)
      }
    }
  }

  override def routes(routeProviderSettings: ManagementRouteProviderSettings): Route = {
    log.info(s"Using self contact point address: ${routeProviderSettings.selfBaseUri}")
    this.setSelfContactPoint(routeProviderSettings.selfBaseUri)

    new HttpClusterBootstrapRoutes(settings).routes
  }

  def start(): Unit =
    if (Cluster(system).settings.SeedNodes.nonEmpty) {
      log.warning(
        "Application is configured with specific `pekko.cluster.seed-nodes`: {}, bailing out of the bootstrap process! " +
        "If you want to use the automatic bootstrap mechanism, make sure to NOT set explicit seed nodes in the configuration. " +
        "This node will attempt to join the configured seed nodes.",
        Cluster(system).settings.SeedNodes.mkString("[", ", ", "]"))
    } else if (bootstrapStep.compareAndSet(NotRunning, Initializing)) {
      log.info("Initiating bootstrap procedure using {} method...", settings.contactPointDiscovery.discoveryMethod)

      ensureSelfContactPoint()
      val bootstrapProps = BootstrapCoordinator.props(discovery, joinDecider, settings)
      val bootstrap = system.systemActorOf(bootstrapProps, "bootstrapCoordinator")
      // Bootstrap already logs in several other execution points when it can't form a cluster, and why.
      selfContactPoint.foreach { uri =>
        bootstrap ! BootstrapCoordinator.Protocol.InitiateBootstrapping(uri)
      }
    } else log.warning("Bootstrap already initiated, yet start() method was called again. Ignoring.")

  /**
   * INTERNAL API
   *
   * We give the required selfContactPoint some time to be set asynchronously, or else log an error.
   */
  @InternalApi private[bootstrap] def ensureSelfContactPoint(): Unit = system.scheduler.scheduleOnce(10.seconds) {
    if (!selfContactPoint.isCompleted) {
      _selfContactPointUri.failure(new TimeoutException("Awaiting Bootstrap.selfContactPoint timed out."))
      log.error(
        "'Bootstrap.selfContactPoint' was NOT set, but is required for the bootstrap to work " +
        "if binding bootstrap routes manually and not via akka-management.")
    }
  }

  /**
   * INTERNAL API
   *
   * Must be invoked by whoever starts the HTTP server with the `HttpClusterBootstrapRoutes`.
   * This allows us to "reverse lookup" from a lowest-address sorted contact point list,
   * that we discover via discovery, if a given contact point corresponds to our remoting address,
   * and if so, we may opt to join ourselves using the address.
   */
  @InternalApi
  private[pekko] def setSelfContactPoint(baseUri: Uri): Unit =
    _selfContactPointUri.success(baseUri)

  /** INTERNAL API */
  @InternalApi private[pekko] def selfContactPoint: Future[Uri] = _selfContactPointUri.future
}

object ClusterBootstrap extends ExtensionId[ClusterBootstrap] with ExtensionIdProvider {

  override def lookup: ClusterBootstrap.type = ClusterBootstrap

  override def get(system: ActorSystem): ClusterBootstrap = super.get(system)

  override def get(system: ClassicActorSystemProvider): ClusterBootstrap = super.get(system)

  override def createExtension(system: ExtendedActorSystem): ClusterBootstrap = new ClusterBootstrap()(system)

  /**
   * INTERNAL API
   */
  private[bootstrap] object Internal {
    sealed trait BootstrapStep
    case object NotRunning extends BootstrapStep
    case object Initializing extends BootstrapStep
  }

}
