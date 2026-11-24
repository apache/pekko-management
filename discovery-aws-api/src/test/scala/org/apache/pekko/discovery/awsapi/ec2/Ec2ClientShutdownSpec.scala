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

import java.net.ServerSocket
import java.util.concurrent.{ CountDownLatch, TimeUnit }
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }

import com.amazonaws.ClientConfiguration
import com.amazonaws.retry.PredefinedRetryPolicies
import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.{ ActorSystem, CoordinatedShutdown, ExtendedActorSystem }
import pekko.discovery.Lookup
import org.scalatest.{ BeforeAndAfterEach, Inside }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Failure

object Ec2ClientShutdownSpec {

  val configurationAttempts = new AtomicInteger(0)

  // Fails to construct, so that `ec2Client` always fails to initialise. A Scala `lazy val` whose
  // initialiser throws stays uninitialised, so any later access re-runs the initialiser.
  class FailingClientConfiguration extends ClientConfiguration {
    private val attempt = configurationAttempts.incrementAndGet()
    require(attempt < 0, "cannot build a client configuration")
  }

  @volatile var constructionStarted = new CountDownLatch(1)
  @volatile var finishConstruction = new CountDownLatch(1)

  // Holds up the construction of the client until the test lets it continue, so that coordinated
  // shutdown can run while `ec2Client` is still being initialised.
  class BlockingClientConfiguration extends ClientConfiguration {
    setRetryPolicy(PredefinedRetryPolicies.NO_RETRY_POLICY)
    constructionStarted.countDown()
    require(finishConstruction.await(30, TimeUnit.SECONDS), "the test did not let the construction finish")
  }

  def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

  // the SDK resolves credentials before it touches the connection pool, so supply some
  def withDummyCredentials[T](body: => T): T = {
    val keys = Seq("aws.accessKeyId" -> "dummy-access-key", "aws.secretKey" -> "dummy-secret-key")
    val previous = keys.map { case (k, _) => k -> Option(System.getProperty(k)) }
    keys.foreach { case (k, v) => System.setProperty(k, v) }
    try body
    finally previous.foreach {
        case (k, Some(v)) => System.setProperty(k, v)
        case (k, None)    => System.clearProperty(k)
      }
  }

}

class Ec2ClientShutdownSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach with Inside {

  import Ec2ClientShutdownSpec._

  private def newSystem(): ExtendedActorSystem =
    ActorSystem(
      "Ec2ClientShutdownSpec",
      ConfigFactory
        .parseString(
          s"""pekko.discovery.aws-api-ec2-tag-based.client-config = "${classOf[
              FailingClientConfiguration].getName}"""")
        .withFallback(ConfigFactory.load())).asInstanceOf[ExtendedActorSystem]

  override def beforeEach(): Unit = {
    configurationAttempts.set(0)
    constructionStarted = new CountDownLatch(1)
    finishConstruction = new CountDownLatch(1)
  }

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

    "fail a lookup that starts after coordinated shutdown, without building a client" in {
      // the actor system is kept running, so that the lookup can still schedule its timeout
      val system = ActorSystem(
        "Ec2ClientShutdownSpecAfterShutdown",
        ConfigFactory
          .parseString(s"""
            |pekko.coordinated-shutdown.terminate-actor-system = off
            |pekko.coordinated-shutdown.run-by-actor-system-terminate = off
            |pekko.discovery.aws-api-ec2-tag-based.client-config = "${classOf[FailingClientConfiguration].getName}"
            |""".stripMargin)
          .withFallback(ConfigFactory.load())).asInstanceOf[ExtendedActorSystem]
      try {
        val discovery = new Ec2TagBasedServiceDiscovery(system)
        Await.result(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 30.seconds)

        val result = Await.ready(discovery.lookup(Lookup("my-service"), 10.seconds), 30.seconds).value
        inside(result) {
          case Some(Failure(ex)) =>
            ex shouldBe an[IllegalStateException]
            ex.getMessage should include("shutting down")
        }
        configurationAttempts.get() should ===(0)
      } finally Await.ready(system.terminate(), 30.seconds)
    }

    "fail a lookup whose client is only built after coordinated shutdown has closed the client, " +
    "before any AWS request" in
    withDummyCredentials {
      // nothing listens on the endpoint: a request on a live client fails to connect, and one on a
      // shut down client fails with "Connection pool shut down". Neither is expected, because the
      // lookup must fail before it gets as far as the SDK.
      val system = ActorSystem(
        "Ec2ClientShutdownSpecLateClient",
        ConfigFactory
          .parseString(s"""
            |pekko.coordinated-shutdown.terminate-actor-system = off
            |pekko.coordinated-shutdown.run-by-actor-system-terminate = off
            |pekko.discovery.aws-api-ec2-tag-based {
            |  client-config = "${classOf[BlockingClientConfiguration].getName}"
            |  endpoint = "http://127.0.0.1:${freePort()}"
            |  region = "us-east-1"
            |}
            |""".stripMargin)
          .withFallback(ConfigFactory.load())).asInstanceOf[ExtendedActorSystem]
      try {
        val discovery = new Ec2TagBasedServiceDiscovery(system)

        // the lookup is inside the lazy client initialiser when coordinated shutdown runs
        val lookup = discovery.lookup(Lookup("my-service"), 20.seconds)
        constructionStarted.await(10, TimeUnit.SECONDS) should ===(true)
        Await.result(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 30.seconds)
        finishConstruction.countDown()

        val result = Await.ready(lookup, 30.seconds).value
        inside(result) {
          case Some(Failure(ex)) =>
            ex shouldBe an[IllegalStateException]
            ex.getMessage should include("The EC2 client has been closed because the actor system is shutting down")
        }

        // the client that was built late has been taken out of the reference, which only happens
        // on the way to shutting it down, so it is closed rather than leaked
        val builtClient = classOf[Ec2TagBasedServiceDiscovery].getDeclaredField("builtEc2Client")
        builtClient.setAccessible(true)
        builtClient.get(discovery).asInstanceOf[AtomicReference[AnyRef]].get() should ===(null)
      } finally {
        finishConstruction.countDown()
        Await.ready(system.terminate(), 30.seconds)
      }
    }

  }

}
