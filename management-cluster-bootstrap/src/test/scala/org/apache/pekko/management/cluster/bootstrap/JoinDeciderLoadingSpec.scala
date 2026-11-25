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

package org.apache.pekko.management.cluster.bootstrap

import java.time.LocalDateTime

import scala.concurrent.Await
import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.actor.Address
import pekko.actor.ExtendedActorSystem
import pekko.discovery.ServiceDiscovery.ResolvedTarget
import pekko.event.NoLogging
import pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object JoinDeciderLoadingSpec {

  /** Has the expected constructor, but implements neither JoinDecider type. */
  class NotAJoinDecider(system: ActorSystem, settings: ClusterBootstrapSettings)
}

class JoinDeciderLoadingSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  private val system = ActorSystem("JoinDeciderLoadingSpec").asInstanceOf[ExtendedActorSystem]

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  private def deciderFor(implClass: String): JoinDecider = {
    val config = ConfigFactory
      .parseString(s"""pekko.management.cluster.bootstrap.join-decider.class = "$implClass" """)
      .withFallback(system.settings.config)
    ClusterBootstrap.createJoinDecider(system, ClusterBootstrapSettings(config, NoLogging))
  }

  private val now = LocalDateTime.now()
  private val contactPoint = ResolvedTarget("10.0.0.2", Some(7626), None)
  private val seedNode = Address("pekko", "sys", "10.0.0.2", 7355)

  private def info(contactPoints: Set[ResolvedTarget], seedNodes: Set[Address]): SeedNodesInformation =
    new SeedNodesInformation(
      now,
      now,
      contactPoints,
      contactPoints.map(cp => new SeedNodesObservation(now, cp, seedNode, seedNodes)))

  private def decide(decider: JoinDecider, info: SeedNodesInformation): JoinDecision =
    Await.result(decider.decide(info), 3.seconds)

  "ClusterBootstrap.createJoinDecider" should {
    "load a decider written against the scaladsl JoinDecider" in {
      deciderFor(classOf[LowestAddressJoinDecider].getName) shouldBe a[LowestAddressJoinDecider]
    }

    "load and adapt a decider written against the javadsl JoinDecider" in {
      val decider = deciderFor("org.apache.pekko.management.cluster.bootstrap.javadsl.JavaJoinDecider")
      decide(decider, info(Set.empty, Set.empty)) shouldBe KeepProbing
      decide(decider, info(Set(contactPoint), Set.empty)) shouldBe JoinSelf
      decide(decider, info(Set(contactPoint), Set(seedNode))) shouldBe JoinOtherSeedNodes(Set(seedNode))
    }

    "reject a class that is not a JoinDecider" in {
      intercept[ClassCastException] {
        deciderFor(classOf[JoinDeciderLoadingSpec.NotAJoinDecider].getName)
      }
    }
  }
}
