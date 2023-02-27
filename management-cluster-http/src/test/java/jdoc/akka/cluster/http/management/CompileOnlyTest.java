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

package jdoc.akka.cluster.http.management;

import akka.actor.ActorSystem;
import akka.management.scaladsl.AkkaManagement;
import akka.cluster.Cluster;
//#imports
import akka.http.javadsl.server.Route;
import akka.management.cluster.javadsl.ClusterHttpManagementRoutes;
//#imports

public class CompileOnlyTest {
    public static void example() {
        //#loading
        ActorSystem system = ActorSystem.create();
        AkkaManagement.get(system).start();
        //#loading


        //#all
        Cluster cluster = Cluster.get(system);
        Route allRoutes = ClusterHttpManagementRoutes.all(cluster);
        //#all

        //#read-only
        Route readOnlyRoutes = ClusterHttpManagementRoutes.readOnly(cluster);
        //#read-only
    }
}
