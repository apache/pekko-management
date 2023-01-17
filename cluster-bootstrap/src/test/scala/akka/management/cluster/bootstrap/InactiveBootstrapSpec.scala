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

package akka.management.cluster.bootstrap

import akka.actor.ActorSystem
import akka.management.scaladsl.AkkaManagement

class InactiveBootstrapSpec extends AbstractBootstrapSpec {
  val system = ActorSystem("InactiveBootstrapSpec")

  "cluster-bootstrap on the classpath" should {
    "not fail management routes if bootstrap is not configured or used" in {
      // this will call ClusterBootstrap(system) which should not fail even if discovery is not configured
      AkkaManagement(system)
    }
  }

  override protected def afterAll(): Unit = system.terminate()
}
