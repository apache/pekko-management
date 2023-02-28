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

package doc.org.apache.pekko.management.cluster.bootstrap

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap
import org.apache.pekko.management.scaladsl.PekkoManagement

object ClusterBootstrapCompileOnly {

  val system = ActorSystem()

  // #start
  // Pekko Management hosts the HTTP routes used by bootstrap
  PekkoManagement(system).start()

  // Starting the bootstrap process needs to be done explicitly
  ClusterBootstrap(system).start()
  // #start

}
