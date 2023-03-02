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

package org.apache.pekko.cluster.bootstrap.demo;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.actor.Props;
import org.apache.pekko.cluster.Cluster;
import org.apache.pekko.cluster.ClusterEvent;
import org.apache.pekko.http.javadsl.ConnectHttp;
import org.apache.pekko.http.javadsl.Http;
import org.apache.pekko.http.javadsl.server.AllDirectives;
//#start-pekko-management
import org.apache.pekko.management.javadsl.PekkoManagement;

//#start-pekko-management
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap;
import org.apache.pekko.stream.Materializer;

public class DemoApp extends AllDirectives {

  DemoApp() {
    ActorSystem system = ActorSystem.create("Appka");

    Materializer mat = Materializer.createMaterializer(system);
    Cluster cluster = Cluster.get(system);

    system.log().info("Started [" + system + "], cluster.selfAddress = " + cluster.selfAddress() + ")");

    //#start-pekko-management
    PekkoManagement.get(system).start();
    //#start-pekko-management
    ClusterBootstrap.get(system).start();

    cluster
      .subscribe(system.actorOf(Props.create(ClusterWatcher.class)), ClusterEvent.initialStateAsEvents(), ClusterEvent.ClusterDomainEvent.class);

    Http.get(system).bindAndHandle(complete("Hello world").flow(system, mat), ConnectHttp.toHost("0.0.0.0", 8080), mat);

    cluster.registerOnMemberUp(() -> {
      system.log().info("Cluster member is up!");
    });
  }

  public static void main(String[] args) {
    new DemoApp();
  }
}

