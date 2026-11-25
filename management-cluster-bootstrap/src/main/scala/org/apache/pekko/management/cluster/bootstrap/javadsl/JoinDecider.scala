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

package org.apache.pekko.management.cluster.bootstrap.javadsl

import java.util.concurrent.CompletionStage

import org.apache.pekko
import pekko.management.cluster.bootstrap.JoinDecision
import pekko.management.cluster.bootstrap.SeedNodesInformation

/**
 * Java API: The decisions of joining existing seed-nodes or join self to form new
 * cluster is performed by the `JoinDecider` and the implementation is
 * defined in configuration to support different strategies.
 *
 * An implementation is selected with the `pekko.management.cluster.bootstrap.join-decider.class`
 * configuration property and must have a public constructor with `ActorSystem` and
 * `ClusterBootstrapSettings` parameters.
 */
trait JoinDecider {

  /**
   * Decide if and how to join based on the gathered [[pekko.management.cluster.bootstrap.SeedNodesInformation]].
   * The returned `JoinDecision` can be `KeepProbing.getInstance()`, `JoinSelf.getInstance()`
   * or a `JoinOtherSeedNodes`.
   */
  def decide(info: SeedNodesInformation): CompletionStage[JoinDecision]

}
