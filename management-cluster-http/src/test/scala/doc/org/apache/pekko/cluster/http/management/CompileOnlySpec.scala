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

package doc.org.apache.pekko.cluster.http.management

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.cluster.Cluster
import pekko.http.scaladsl.server.Route
import pekko.management.cluster.scaladsl.ClusterHttpManagementRoutes
import pekko.management.scaladsl.PekkoManagement

object CompileOnlySpec {

  // #loading
  val system = ActorSystem()
  // Automatically loads Cluster Http Routes
  PekkoManagement(system).start()
  // #loading

  // #all
  val cluster = Cluster(system)
  val allRoutes: Route = ClusterHttpManagementRoutes(cluster)
  // #all

  // #read-only
  val readOnlyRoutes: Route = ClusterHttpManagementRoutes.readOnly(cluster)
  // #read-only
}
