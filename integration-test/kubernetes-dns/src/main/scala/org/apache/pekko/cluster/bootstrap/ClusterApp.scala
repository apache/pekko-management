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

import org.apache.pekko
import pekko.actor.{ Actor, ActorLogging, ActorSystem, PoisonPill, Props }
import pekko.cluster.ClusterEvent.ClusterDomainEvent
import pekko.cluster.singleton.{ ClusterSingletonManager, ClusterSingletonManagerSettings }
import pekko.cluster.{ Cluster, ClusterEvent }
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model._
import pekko.http.scaladsl.server.Directives._
import pekko.management.cluster.bootstrap.ClusterBootstrap
import pekko.management.scaladsl.PekkoManagement

object ClusterApp {

  def main(args: Array[String]): Unit = {

    implicit val system = ActorSystem()
    val cluster = Cluster(system)

    system.log.info("Starting Pekko Management")
    PekkoManagement(system).start()
    ClusterBootstrap(system).start()

    system.actorOf(
      ClusterSingletonManager.props(
        Props[NoisySingleton],
        PoisonPill,
        ClusterSingletonManagerSettings(system)))
    Cluster(system).subscribe(
      system.actorOf(Props[ClusterWatcher]),
      ClusterEvent.InitialStateAsEvents,
      classOf[ClusterDomainEvent])

    // add real app routes here
    val routes =
      path("hello") {
        get {
          complete(
            HttpEntity(ContentTypes.`text/html(UTF-8)`, "<h1>Hello</h1>"))
        }
      }

    Http().newServerAt("0.0.0.0", 8080).bind(routes)

    system.log.info(
      s"Server online at http://localhost:8080/\nPress RETURN to stop...")

    cluster.registerOnMemberUp(() => {
      system.log.info("Cluster member is up!")
    })
  }

  class ClusterWatcher extends Actor with ActorLogging {
    val cluster = Cluster(context.system)

    override def receive = {
      case msg => log.info(s"Cluster ${cluster.selfAddress} >>> " + msg)
    }
  }
}
