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

package org.apache.pekko.discovery.awsapi.ec2

import java.util.concurrent.atomic.AtomicInteger

import com.amazonaws.ClientConfiguration
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.{ ActorSystem, CoordinatedShutdown, ExtendedActorSystem }
import pekko.discovery.Lookup
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration._

object Ec2ClientShutdownSpec {

  val configurationAttempts = new AtomicInteger(0)

  // Fails to construct, so that `ec2Client` always fails to initialise. A Scala `lazy val` whose
  // initialiser throws stays uninitialised, so any later access re-runs the initialiser.
  class FailingClientConfiguration extends ClientConfiguration {
    private val attempt = configurationAttempts.incrementAndGet()
    require(attempt < 0, "cannot build a client configuration")
  }

}

class Ec2ClientShutdownSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  import Ec2ClientShutdownSpec._

  private def newSystem(): ExtendedActorSystem =
    ActorSystem(
      "Ec2ClientShutdownSpec",
      ConfigFactory
        .parseString(
          s"""pekko.discovery.aws-api-ec2-tag-based.client-config = "${classOf[
              FailingClientConfiguration].getName}"""")
        .withFallback(ConfigFactory.load())).asInstanceOf[ExtendedActorSystem]

  override def beforeEach(): Unit = configurationAttempts.set(0)

  "Ec2TagBasedServiceDiscovery" should {

    "not create the EC2 client during shutdown when discovery was never used" in {
      val system = newSystem()
      new Ec2TagBasedServiceDiscovery(system)

      Await.result(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 30.seconds)

      configurationAttempts.get() should ===(0)
    }

    "not re-attempt EC2 client creation during shutdown when creation previously failed" in {
      val system = newSystem()
      val discovery = new Ec2TagBasedServiceDiscovery(system)

      Await.ready(discovery.lookup(Lookup("my-service"), 10.seconds), 30.seconds)
      configurationAttempts.get() should ===(1)

      Await.result(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 30.seconds)

      configurationAttempts.get() should ===(1)
    }

  }

}
