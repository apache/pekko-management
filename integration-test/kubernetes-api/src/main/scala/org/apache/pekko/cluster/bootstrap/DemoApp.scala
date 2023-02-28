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

package org.apache.pekko.cluster.bootstrap

import org.apache.pekko.actor.{ Actor, ActorLogging, ActorSystem, Props }
import org.apache.pekko.cluster.ClusterEvent.ClusterDomainEvent
import org.apache.pekko.cluster.{ Cluster, ClusterEvent }
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement
//#start-akka-management

//#start-akka-management

object DemoApp extends App {

  implicit val system = ActorSystem("Appka")

  import system.log
  val cluster = Cluster(system)

  log.info(s"Started [$system], cluster.selfAddress = ${cluster.selfAddress}")

  // #start-akka-management
  PekkoManagement(system).start()
  // #start-akka-management
  ClusterBootstrap(system).start()

  cluster.subscribe(
    system.actorOf(Props[ClusterWatcher]),
    ClusterEvent.InitialStateAsEvents,
    classOf[ClusterDomainEvent])

  // add real app routes here
  val routes =
    path("hello") {
      get {
        complete(
          HttpEntity(
            ContentTypes.`text/html(UTF-8)`,
            "<h1>Hello</h1>"))
      }
    }
  Http().newServerAt("0.0.0.0", 8080).bind(routes)

  Cluster(system).registerOnMemberUp {
    log.info("Cluster member is up!")
  }

}

class ClusterWatcher extends Actor with ActorLogging {
  val cluster = Cluster(context.system)

  override def receive = {
    case msg => log.info(s"Cluster ${cluster.selfAddress} >>> " + msg)
  }
}
