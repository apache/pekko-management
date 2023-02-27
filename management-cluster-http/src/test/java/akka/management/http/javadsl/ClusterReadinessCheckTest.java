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

package akka.management.http.javadsl;

import akka.actor.ActorSystem;
import akka.management.cluster.javadsl.ClusterMembershipCheck;

import java.util.concurrent.CompletionStage;


public class ClusterReadinessCheckTest {

   private static ActorSystem system = null;

   // test type works
   public static CompletionStage<Boolean> worksFromJava() throws Exception {
      ClusterMembershipCheck check = new ClusterMembershipCheck(system);
      return check.get();
   }
}
