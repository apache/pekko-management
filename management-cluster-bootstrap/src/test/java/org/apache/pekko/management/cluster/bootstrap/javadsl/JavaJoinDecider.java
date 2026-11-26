/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.management.cluster.bootstrap.javadsl;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrapSettings;
import org.apache.pekko.management.cluster.bootstrap.JoinDecision;
import org.apache.pekko.management.cluster.bootstrap.JoinOtherSeedNodes;
import org.apache.pekko.management.cluster.bootstrap.JoinSelf;
import org.apache.pekko.management.cluster.bootstrap.KeepProbing;
import org.apache.pekko.management.cluster.bootstrap.SeedNodesInformation;

/** A join decider written against the Java API only, to prove no Scala types are needed. */
public class JavaJoinDecider implements JoinDecider {

  public JavaJoinDecider(ActorSystem system, ClusterBootstrapSettings settings) {}

  @Override
  public CompletionStage<JoinDecision> decide(SeedNodesInformation info) {
    // a pretyped CompletableFuture of the exact result type, as a Java caller would write it
    CompletableFuture<JoinDecision> decision = new CompletableFuture<>();
    if (info.hasSeedNodes()) {
      decision.complete(new JoinOtherSeedNodes(info.getAllSeedNodes()));
    } else if (info.getContactPoints().isEmpty()) {
      decision.complete(KeepProbing.getInstance());
    } else {
      decision.complete(JoinSelf.getInstance());
    }
    return decision;
  }
}
