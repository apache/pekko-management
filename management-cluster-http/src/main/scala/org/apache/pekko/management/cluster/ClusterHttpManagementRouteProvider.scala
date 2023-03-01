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

package org.apache.pekko.management.cluster

import org.apache.pekko
import pekko.actor.{ ActorSystem, ClassicActorSystemProvider, ExtendedActorSystem, ExtensionId, ExtensionIdProvider }
import pekko.cluster.Cluster
import pekko.http.scaladsl.server.Route
import pekko.management.cluster.scaladsl.ClusterHttpManagementRoutes
import pekko.management.scaladsl.{ ManagementRouteProvider, ManagementRouteProviderSettings }

object ClusterHttpManagementRouteProvider
    extends ExtensionId[ClusterHttpManagementRouteProvider]
    with ExtensionIdProvider {
  override def lookup: ClusterHttpManagementRouteProvider.type = ClusterHttpManagementRouteProvider

  override def get(system: ActorSystem): ClusterHttpManagementRouteProvider = super.get(system)

  override def get(system: ClassicActorSystemProvider): ClusterHttpManagementRouteProvider = super.get(system)

  override def createExtension(system: ExtendedActorSystem): ClusterHttpManagementRouteProvider =
    new ClusterHttpManagementRouteProvider(system)

}

/**
 * Provides an HTTP management interface for [[pekko.cluster.Cluster]].
 */
final class ClusterHttpManagementRouteProvider(system: ExtendedActorSystem) extends ManagementRouteProvider {

  private val cluster = Cluster(system)

  val settings: ClusterHttpManagementSettings = new ClusterHttpManagementSettings(system.settings.config)

  /** Routes to be exposed by Akka cluster management */
  override def routes(routeProviderSettings: ManagementRouteProviderSettings): Route =
    if (routeProviderSettings.readOnly) {
      ClusterHttpManagementRoutes.readOnly(cluster)
    } else {
      ClusterHttpManagementRoutes(cluster)
    }

}
