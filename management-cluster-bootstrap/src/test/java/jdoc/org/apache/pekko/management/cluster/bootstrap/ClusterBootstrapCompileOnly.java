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

package jdoc.org.apache.pekko.management.cluster.bootstrap;

import java.util.concurrent.CompletionStage;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.http.javadsl.model.Uri;
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrap;
import org.apache.pekko.management.javadsl.PekkoManagement;

public class ClusterBootstrapCompileOnly {
  public static void bootstrap() {

    ActorSystem system = ActorSystem.create();

    // #start
    // Pekko Management hosts the HTTP routes used by bootstrap
    CompletionStage<Uri> managementStarted = PekkoManagement.get(system).start();

    // Starting the bootstrap process needs to be done explicitly
    ClusterBootstrap.get(system).start();
    // #start
  }
}
