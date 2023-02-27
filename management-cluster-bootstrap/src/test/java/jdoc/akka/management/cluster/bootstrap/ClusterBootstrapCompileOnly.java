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

package jdoc.akka.management.cluster.bootstrap;

import akka.actor.ActorSystem;
import akka.management.scaladsl.AkkaManagement;
import akka.management.cluster.bootstrap.ClusterBootstrap;

public class ClusterBootstrapCompileOnly {
    public static void bootstrap() {

        ActorSystem system = ActorSystem.create();

        //#start
        // Akka Management hosts the HTTP routes used by bootstrap
        AkkaManagement.get(system).start();

        // Starting the bootstrap process needs to be done explicitly
        ClusterBootstrap.get(system).start();
        //#start
    }
}
