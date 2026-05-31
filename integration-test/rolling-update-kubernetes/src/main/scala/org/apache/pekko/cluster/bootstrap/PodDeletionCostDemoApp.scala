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

package org.apache.pekko.cluster.bootstrap

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.cluster.Cluster
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.server.Directives._
import pekko.management.cluster.bootstrap.ClusterBootstrap
import pekko.management.scaladsl.PekkoManagement
import pekko.rollingupdate.kubernetes.AppVersionRevision
import pekko.rollingupdate.kubernetes.PodDeletionCost

object PodDeletionCostDemoApp extends App {

  implicit val system: ActorSystem = ActorSystem("pekko-rolling-update-demo")

  import system.log
  val cluster = Cluster(system)

  log.info(s"Started [$system], cluster.selfAddress = ${cluster.selfAddress}")

  PekkoManagement(system).start()

  // preferred to be called before ClusterBootstrap
  AppVersionRevision(system).start()

  ClusterBootstrap(system).start()

  PodDeletionCost(system).start()

  Http().newServerAt("0.0.0.0", 8080).bind(complete("Hello world"))
}
