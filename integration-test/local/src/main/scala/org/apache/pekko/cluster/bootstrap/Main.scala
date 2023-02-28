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

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.cluster.Cluster
import com.typesafe.config.{ Config, ConfigFactory }
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement

//#main
object Node1 extends App {
  new Main(1)
}

object Node2 extends App {
  new Main(2)
}

object Node3 extends App {
  new Main(3)
}

class Main(nr: Int) {

  val config: Config = ConfigFactory.parseString(s"""
      pekko.remote.artery.canonical.hostname = "127.0.0.$nr"
      pekko.management.http.hostname = "127.0.0.$nr"
    """).withFallback(ConfigFactory.load())
  val system = ActorSystem("local-cluster", config)

  PekkoManagement(system).start()

  ClusterBootstrap(system).start()

  Cluster(system).registerOnMemberUp {
    system.log.info("Cluster is up!")
  }
}
//#main
