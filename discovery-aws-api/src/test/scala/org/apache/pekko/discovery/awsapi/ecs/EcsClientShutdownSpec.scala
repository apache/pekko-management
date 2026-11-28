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

package org.apache.pekko.discovery.awsapi.ecs

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.{ ActorSystem, CoordinatedShutdown }
import pekko.discovery.Lookup
import pekko.discovery.awsapi.ec2.Ec2ClientShutdownSpec.{ freePort, withDummyCredentials }
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

class EcsClientShutdownSpec extends AnyWordSpec with Matchers with Inside {

  "EcsServiceDiscovery" should {

    "fail a lookup that starts after coordinated shutdown, before any AWS request" in
    withDummyCredentials {
      // nothing listens on the endpoint: a request on a live client fails to connect, and one on a
      // shut down client fails because its connection pool has been shut down. Neither is expected,
      // because the lookup must fail before it gets as far as the SDK.
      val system = ActorSystem(
        "EcsClientShutdownSpecLateClient",
        ConfigFactory
          .parseString(s"""
            |pekko.coordinated-shutdown.terminate-actor-system = off
            |pekko.coordinated-shutdown.run-by-actor-system-terminate = off
            |pekko.discovery.aws-api-ecs {
            |  endpoint = "http://127.0.0.1:${freePort()}"
            |  region = "us-east-1"
            |}
            |""".stripMargin)
          .withFallback(ConfigFactory.load()))
      try {
        val discovery = new EcsServiceDiscovery(system)
        Await.result(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 30.seconds)

        val result = Await.ready(discovery.lookup(Lookup("my-service"), 10.seconds), 30.seconds).value
        inside(result) {
          case Some(Failure(ex)) =>
            ex shouldBe an[IllegalStateException]
            ex.getMessage should include("The ECS client has been closed because the actor system is shutting down")
        }
      } finally Await.ready(system.terminate(), 30.seconds)
    }

  }

}
