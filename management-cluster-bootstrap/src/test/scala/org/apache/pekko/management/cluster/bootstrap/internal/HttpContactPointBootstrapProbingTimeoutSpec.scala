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

package org.apache.pekko.management.cluster.bootstrap.internal

import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.actor.Address
import pekko.discovery.ServiceDiscovery.ResolvedTarget
import pekko.event.Logging
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import pekko.http.scaladsl.model.Uri
import pekko.http.scaladsl.server.Directives._
import pekko.http.scaladsl.server.Route
import pekko.management.cluster.bootstrap.ClusterBootstrapSettings
import pekko.management.cluster.bootstrap.contactpoint.HttpBootstrapJsonProtocol
import pekko.management.cluster.bootstrap.contactpoint.HttpBootstrapJsonProtocol.SeedNodes
import pekko.testkit.TestKit
import pekko.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.wordspec.AnyWordSpecLike

class HttpContactPointBootstrapProbingTimeoutSpec
    extends TestKit(ActorSystem(
      "HttpContactPointBootstrapProbingTimeoutSpec",
      ConfigFactory.parseString("""
        pekko.actor.provider = cluster
        pekko.remote.artery.canonical.hostname = "127.0.0.1"
        pekko.remote.artery.canonical.port = 0
        pekko.management.cluster.bootstrap.contact-point {
          probe-interval = 100 ms
          probing-request-timeout = 200 ms
        }
        """).withFallback(ConfigFactory.load())))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with SprayJsonSupport
    with HttpBootstrapJsonProtocol {

  private implicit val patience: PatienceConfig = PatienceConfig(Span(10, Seconds))

  private val seedNodes = SeedNodes(Address("pekko", "contact-point", "10.0.0.1", 7355), Set.empty)

  /**
   * Stands in for the Pekko Management endpoint of another node. The first `unansweredRequests` probes
   * are left without a response until the test is over, the ones after that are answered right away.
   */
  private class ContactPoint(unansweredRequests: Int) {
    val requestCount = new AtomicInteger(0)
    private val testIsOver = Promise[SeedNodes]()

    private val route: Route =
      path("bootstrap" / "seed-nodes") {
        get {
          if (requestCount.incrementAndGet() <= unansweredRequests) complete(testIsOver.future)
          else complete(seedNodes)
        }
      }

    private val binding = Http().newServerAt("127.0.0.1", 0).bind(route).futureValue
    private val port = binding.localAddress.getPort
    val target: ResolvedTarget = ResolvedTarget("127.0.0.1", Some(port), Some(InetAddress.getByName("127.0.0.1")))
    val baseUri: Uri = Uri(s"http://127.0.0.1:$port")

    def close(): Future[Any] = {
      testIsOver.trySuccess(seedNodes)
      binding.unbind()
    }
  }

  private def settings(probingFailureTimeout: String, probingRequestTimeout: String = "200 ms")
      : ClusterBootstrapSettings =
    new ClusterBootstrapSettings(
      ConfigFactory
        .parseString(s"""
          pekko.management.cluster.bootstrap.contact-point {
            probing-failure-timeout = $probingFailureTimeout
            probing-request-timeout = $probingRequestTimeout
          }
          """)
        .withFallback(system.settings.config),
      Logging(system, classOf[HttpContactPointBootstrapProbingTimeoutSpec]))

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  "HttpContactPointBootstrap" should {

    "probe again when a request timed out within the probing failure timeout" in {
      val contactPoint = new ContactPoint(unansweredRequests = 1)
      val parent = TestProbe()
      val child =
        parent.childActorOf(HttpContactPointBootstrap.props(settings("20 seconds"), contactPoint.target,
          contactPoint.baseUri))
      try {
        // the first message is the observation from the second probe, and not a ProbingFailed for the first one
        val observation =
          parent.expectMsgType[BootstrapCoordinator.Protocol.ObtainedHttpSeedNodesObservation](10.seconds)
        observation.contactPoint should ===(contactPoint.target)
        contactPoint.requestCount.get should be >= 2
      } finally {
        system.stop(child)
        contactPoint.close().futureValue
      }
    }

    "signal that probing failed once requests kept timing out for the probing failure timeout" in {
      val contactPoint = new ContactPoint(unansweredRequests = Int.MaxValue)
      val parent = TestProbe()
      val child =
        parent.childActorOf(HttpContactPointBootstrap.props(settings("1 second"), contactPoint.target,
          contactPoint.baseUri))
      try {
        parent.watch(child)
        val failed = parent.expectMsgType[BootstrapCoordinator.Protocol.ProbingFailed](10.seconds)
        failed.contactPoint should ===(contactPoint.target)
        failed.cause.getMessage should include("Probing timeout")
        parent.expectTerminated(child, 10.seconds)
        // it did not give up after the first request that timed out
        contactPoint.requestCount.get should be >= 2
      } finally {
        contactPoint.close().futureValue
      }
    }

    "not give a request longer than what is left of the probing failure timeout" in {
      val contactPoint = new ContactPoint(unansweredRequests = Int.MaxValue)
      val parent = TestProbe()
      // without the cap the only request would time out after a minute, long after this test gave up
      val child =
        parent.childActorOf(HttpContactPointBootstrap.props(
          settings(probingFailureTimeout = "250 ms", probingRequestTimeout = "1 minute"), contactPoint.target,
          contactPoint.baseUri))
      try {
        parent.watch(child)
        val failed = parent.expectMsgType[BootstrapCoordinator.Protocol.ProbingFailed](10.seconds)
        failed.contactPoint should ===(contactPoint.target)
        failed.cause.getMessage should include("Probing timeout")
        parent.expectTerminated(child, 10.seconds)
        contactPoint.requestCount.get should be <= 1
      } finally {
        contactPoint.close().futureValue
      }
    }

    "not start a probe once the probing failure timeout has passed" in {
      val contactPoint = new ContactPoint(unansweredRequests = Int.MaxValue)
      val parent = TestProbe()
      // The first request times out after 200 ms and the next probe is due at least 100 ms after that, which
      // is not before the failure deadline of 300 ms that started when the actor was created.
      val child =
        parent.childActorOf(HttpContactPointBootstrap.props(
          settings(probingFailureTimeout = "300 ms", probingRequestTimeout = "200 ms"), contactPoint.target,
          contactPoint.baseUri))
      try {
        parent.watch(child)
        val failed = parent.expectMsgType[BootstrapCoordinator.Protocol.ProbingFailed](10.seconds)
        failed.contactPoint should ===(contactPoint.target)
        // the cause is the failure of the request that was sent
        failed.cause.getMessage should include("Probing timeout")
        parent.expectTerminated(child, 10.seconds)
        contactPoint.requestCount.get should be <= 1
      } finally {
        contactPoint.close().futureValue
      }
    }

    "cap the request timeout of a configuration that only sets a shorter probing failure timeout" in {
      // an existing configuration: it does not know about probing-request-timeout, which defaults to 1 second
      val existing = new ClusterBootstrapSettings(
        ConfigFactory
          .parseString("pekko.management.cluster.bootstrap.contact-point.probing-failure-timeout = 250 ms")
          .withFallback(ConfigFactory.load()),
        Logging(system, classOf[HttpContactPointBootstrapProbingTimeoutSpec]))
      existing.contactPoint.probingRequestTimeout should ===(1.second)

      // the first probe has the whole failure timeout left, and that is all it gets
      HttpContactPointBootstrap.probeRequestTimeout(
        existing.contactPoint.probingRequestTimeout,
        existing.contactPoint.probingFailureTimeout) should ===(Some(250.millis))
      HttpContactPointBootstrap.probeRequestTimeout(existing.contactPoint.probingRequestTimeout,
        100.millis) should ===(Some(100.millis))
      // with plenty of time left the request timeout applies
      HttpContactPointBootstrap.probeRequestTimeout(existing.contactPoint.probingRequestTimeout,
        10.seconds) should ===(Some(1.second))
      // no request is sent when the failure timeout has passed
      HttpContactPointBootstrap.probeRequestTimeout(existing.contactPoint.probingRequestTimeout,
        Duration.Zero) should ===(None)
      HttpContactPointBootstrap.probeRequestTimeout(existing.contactPoint.probingRequestTimeout,
        (-1).milli) should ===(None)
    }
  }
}
