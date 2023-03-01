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

import java.net.InetAddress
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.discovery.awsapi.ecs.AsyncEcsDiscovery
import com.typesafe.config.ConfigFactory
import pekko.management.cluster.bootstrap.ClusterBootstrap
import pekko.management.scaladsl.PekkoManagement

object EcsApiDemoApp {

  def main(args: Array[String]): Unit = {
    val privateAddress = getPrivateAddressOrExit
    val config = ConfigFactory
      .systemProperties()
      .withFallback(
        ConfigFactory.parseString(s"""
             |pekko {
             |  actor.provider = "cluster"
             |  management {
             |    cluster.bootstrap.contact-point.fallback-port = 8558
             |    http.hostname = "${privateAddress.getHostAddress}"
             |  }
             |  discovery.method = aws-api-ecs-async
             |  remote.netty.tcp.hostname = "${privateAddress.getHostAddress}"
             |}
           """.stripMargin))
    val system = ActorSystem("ecsBootstrapDemoApp", config)
    PekkoManagement(system).start()
    ClusterBootstrap(system).start()
  }

  private[this] def getPrivateAddressOrExit: InetAddress =
    AsyncEcsDiscovery.getContainerAddress match {
      case Left(error) =>
        System.err.println(s"$error Halting.")
        sys.exit(1)

      case Right(value) =>
        value
    }

}
