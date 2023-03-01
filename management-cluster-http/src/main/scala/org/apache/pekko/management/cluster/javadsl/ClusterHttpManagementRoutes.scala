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

package org.apache.pekko.management.cluster.javadsl

import org.apache.pekko
import pekko.cluster.Cluster
import pekko.http.javadsl.server.directives.RouteAdapter
import pekko.management.cluster.scaladsl

object ClusterHttpManagementRoutes {

  /**
   * Creates an instance of [[ClusterHttpManagementRoutes]] to manage the specified
   * [[pekko.cluster.Cluster]] instance. This version does not provide Basic Authentication. It uses
   * the specified path `pathPrefixName`.
   */
  def all(cluster: Cluster): pekko.http.javadsl.server.Route =
    RouteAdapter(scaladsl.ClusterHttpManagementRoutes(cluster))

  /**
   * Creates an instance of [[ClusterHttpManagementRoutes]] to manage the specified
   * [[pekko.cluster.Cluster]] instance. This version does not provide Basic Authentication. It uses
   * the specified path `pathPrefixName`.
   */
  def readOnly(cluster: Cluster): pekko.http.javadsl.server.Route =
    RouteAdapter(scaladsl.ClusterHttpManagementRoutes.readOnly(cluster))

}
