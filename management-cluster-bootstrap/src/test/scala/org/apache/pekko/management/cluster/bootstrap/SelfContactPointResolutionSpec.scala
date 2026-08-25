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

import java.util.concurrent.TimeoutException

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.event.NoLogging
import pekko.testkit.TestKit
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

object SelfContactPointResolutionSpec {

  /** Waits a fraction of the real timeout, so that the blocking path can be exercised in a test. */
  class ShortTimeoutJoinDecider(system: ActorSystem, settings: ClusterBootstrapSettings)
      extends LowestAddressJoinDecider(system, settings) {
    override protected def selfContactPointTimeout: FiniteDuration = 500.millis
  }

  /** Reads back the production timeout, which is otherwise protected. */
  class TimeoutReadingJoinDecider(system: ActorSystem, settings: ClusterBootstrapSettings)
      extends LowestAddressJoinDecider(system, settings) {
    def configuredTimeout: FiniteDuration = selfContactPointTimeout
  }

}

class SelfContactPointResolutionSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll {

  import SelfContactPointResolutionSpec._

  private val config = ConfigFactory.parseString("""
      pekko {
        loglevel = INFO
        remote.artery.canonical.port = 0
        management.http {
          hostname = "10.0.0.2"
          base-path = "test"
        }
      }
      """).withFallback(ConfigFactory.load())

  // a system per test, because setSelfContactPoint completes a promise that cannot be reset
  private var systems: List[ActorSystem] = Nil

  private def newDecider(name: String): (ActorSystem, ShortTimeoutJoinDecider) = {
    val system = ActorSystem(name, config)
    systems = system :: systems
    val settings = ClusterBootstrapSettings(system.settings.config, NoLogging)
    (system, new ShortTimeoutJoinDecider(system, settings))
  }

  private def elapsed(body: => Unit): FiniteDuration = {
    val started = System.nanoTime()
    body
    (System.nanoTime() - started).nanos
  }

  "SelfAwareJoinDecider.selfContactPoint" should {

    "resolve the contact point once it has been set" in {
      val (system, decider) = newDecider("self-contact-point-resolves")
      ClusterBootstrap(system).setSelfContactPoint("http://10.0.0.2:8558/test")

      decider.selfContactPoint should ===(("10.0.0.2", 8558))
    }

    "cache the resolved contact point rather than resolving it again" in {
      val (system, decider) = newDecider("self-contact-point-caches")
      ClusterBootstrap(system).setSelfContactPoint("http://10.0.0.2:8558/test")

      val first = decider.selfContactPoint
      val second = decider.selfContactPoint

      second should ===(first)
      // the cache holds the same instance, not just an equal one
      second.asInstanceOf[AnyRef] should be theSameInstanceAs first.asInstanceOf[AnyRef]
    }

    "time out instead of blocking forever when the contact point is never set" in {
      val (_, decider) = newDecider("self-contact-point-times-out")

      a[TimeoutException] should be thrownBy decider.selfContactPoint
    }

    "block for the timeout only once, then fail fast" in {
      val (_, decider) = newDecider("self-contact-point-fails-fast")

      val firstCall = elapsed(a[TimeoutException] should be thrownBy decider.selfContactPoint)
      val secondCall = elapsed(a[TimeoutException] should be thrownBy decider.selfContactPoint)

      firstCall should be >= 500.millis
      // without this the bootstrap coordinator would park a dispatcher thread for the full
      // timeout on every probe, for as long as the contact point stays unset
      secondCall should be < 250.millis
    }

    "still pick up a contact point that is set after a timeout" in {
      val (system, decider) = newDecider("self-contact-point-set-late")

      a[TimeoutException] should be thrownBy decider.selfContactPoint

      ClusterBootstrap(system).setSelfContactPoint("http://10.0.0.2:8558/test")

      decider.selfContactPoint should ===(("10.0.0.2", 8558))
    }

  }

  "ClusterBootstrap.SelfContactPointTimeout" should {

    "be outlasted by the decider's own wait, so that the promise fails first" in {
      val (system, _) = newDecider("self-contact-point-timeout-ordering")
      val settings = ClusterBootstrapSettings(system.settings.config, NoLogging)
      val decider = new TimeoutReadingJoinDecider(system, settings)

      decider.configuredTimeout should be > ClusterBootstrap.SelfContactPointTimeout
    }

  }

  override def afterAll(): Unit =
    systems.foreach(TestKit.shutdownActorSystem(_, 5.seconds))

}
