/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017 Lightbend Inc. <https://www.lightbend.com>
 */
package org.apache.pekko.cluster.bootstrap

import org.apache.pekko.actor.{ Actor, ActorLogging, ActorSystem, Props }
import org.apache.pekko.cluster.ClusterEvent.ClusterDomainEvent
import org.apache.pekko.cluster.{ Cluster, ClusterEvent }
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.management.scaladsl.PekkoManagement
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.stream.scaladsl.Sink
import org.apache.pekko.stream.scaladsl.Source
import com.typesafe.config.ConfigFactory

object DemoApp extends App {

  implicit val system = ActorSystem("simple")

  import system.log
  import system.dispatcher
  val cluster = Cluster(system)

  log.info("Started [{}], cluster.selfAddress = {}", system, cluster.selfAddress)

  PekkoManagement(system).start()
  ClusterBootstrap(system).start()

  cluster
    .subscribe(system.actorOf(Props[ClusterWatcher]), ClusterEvent.InitialStateAsEvents, classOf[ClusterDomainEvent])

  import org.apache.pekko.http.scaladsl.server.Directives._
  Http().bindAndHandle(complete("Hello world"), "0.0.0.0", 8080)

}

class ClusterWatcher extends Actor with ActorLogging {
  val cluster = Cluster(context.system)

  override def receive = {
    case msg => log.info("Cluster {} >>> {}", msg, cluster.selfAddress)
  }
}
