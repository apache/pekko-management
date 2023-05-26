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
import pekko.actor.ActorSystem
import pekko.management.cluster.bootstrap.ClusterBootstrap
import pekko.management.scaladsl.PekkoManagement

object Ec2DemoApp extends App {

  implicit val system: ActorSystem = ActorSystem("demo")

  PekkoManagement(system).start()

  ClusterBootstrap(system).start()

}
