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
import pekko.http.scaladsl.server.directives.Credentials
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

class HttpContactPointBootstrapBasicAuthSpec
    extends TestKit(ActorSystem(
      "HttpContactPointBootstrapBasicAuthSpec",
      ConfigFactory.parseString("""
        pekko.actor.provider = cluster
        pekko.remote.artery.canonical.hostname = "127.0.0.1"
        pekko.remote.artery.canonical.port = 0
        pekko.management.cluster.bootstrap.contact-point {
          probe-interval = 100 ms
          probing-failure-timeout = 1 second
        }
        """).withFallback(ConfigFactory.load())))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with SprayJsonSupport
    with HttpBootstrapJsonProtocol {

  private implicit val patience: PatienceConfig = PatienceConfig(Span(10, Seconds))

  private val contactPointSelfNode = Address("pekko", "contact-point", "10.0.0.1", 7355)

  private def authenticator(credentials: Credentials): Option[String] =
    credentials match {
      case p @ Credentials.Provided("bootstrap") if p.verify("s3cr3t") => Some("bootstrap")
      case _                                                           => None
    }

  // stands in for the Pekko Management endpoint of another node, protected with basic authentication
  private val requestCount = new AtomicInteger(0)

  private val securedRoute: Route =
    extractRequest { _ =>
      requestCount.incrementAndGet()
      authenticateBasic(realm = "secured", authenticator) { _ =>
        path("bootstrap" / "seed-nodes") {
          get {
            complete(SeedNodes(contactPointSelfNode, Set.empty))
          }
        }
      }
    }

  private val binding = Http().newServerAt("127.0.0.1", 0).bind(securedRoute).futureValue
  private val port = binding.localAddress.getPort
  private val contactPoint = ResolvedTarget("127.0.0.1", Some(port), Some(InetAddress.getByName("127.0.0.1")))
  private val baseUri = Uri(s"http://127.0.0.1:$port")

  private def settings(extraConfig: String): ClusterBootstrapSettings =
    new ClusterBootstrapSettings(
      ConfigFactory.parseString(extraConfig).withFallback(system.settings.config),
      Logging(system, classOf[HttpContactPointBootstrapBasicAuthSpec]))

  override def afterAll(): Unit = {
    binding.unbind().futureValue
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  "HttpContactPointBootstrap" should {

    "send the configured basic auth credentials when probing a contact point" in {
      val parent = TestProbe()
      val probeSettings = settings("""
        pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth {
          username = "bootstrap"
          password = "s3cr3t"
          allow-insecure-http = on
        }""")
      val child = parent.childActorOf(HttpContactPointBootstrap.props(probeSettings, contactPoint, baseUri))
      try {
        val observation =
          parent.expectMsgType[BootstrapCoordinator.Protocol.ObtainedHttpSeedNodesObservation](10.seconds)
        observation.contactPoint should ===(contactPoint)
        observation.seedNodesSourceAddress should ===(contactPointSelfNode)
        observation.observedSeedNodes should ===(Set.empty[Address])
      } finally {
        system.stop(child)
      }
    }

    "refuse to send the credentials over plain http unless that is explicitly allowed" in {
      val parent = TestProbe()
      val probeSettings = settings("""
        pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth {
          username = "bootstrap"
          password = "s3cr3t"
        }""")
      val requestsBefore = requestCount.get
      val child = parent.childActorOf(HttpContactPointBootstrap.props(probeSettings, contactPoint, baseUri))
      // the actor fails to start, rather than probing with or without the credentials
      parent.watch(child)
      parent.expectTerminated(child, 10.seconds)
      parent.expectNoMessage(300.millis)
      requestCount.get should ===(requestsBefore)
    }

    "allow the credentials to be sent over https without further settings" in {
      val probeSettings = settings("""
        pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth {
          username = "bootstrap"
          password = "s3cr3t"
        }""")
      HttpContactPointBootstrap.mayProbeWithScheme(probeSettings, "https") should ===(true)
      HttpContactPointBootstrap.mayProbeWithScheme(probeSettings, "http") should ===(false)
      // anonymous probing is not restricted
      HttpContactPointBootstrap.mayProbeWithScheme(settings(""), "http") should ===(true)
    }

    "fail probing a contact point that requires basic auth when no credentials are configured" in {
      val parent = TestProbe()
      parent.childActorOf(HttpContactPointBootstrap.props(settings(""), contactPoint, baseUri))
      val failed = parent.expectMsgType[BootstrapCoordinator.Protocol.ProbingFailed](10.seconds)
      failed.contactPoint should ===(contactPoint)
      failed.cause.getMessage should include("401")
    }

    "fail probing a contact point when the configured credentials are rejected" in {
      val parent = TestProbe()
      val probeSettings = settings("""
        pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth {
          username = "bootstrap"
          password = "wrong"
          allow-insecure-http = on
        }""")
      parent.childActorOf(HttpContactPointBootstrap.props(probeSettings, contactPoint, baseUri))
      val failed = parent.expectMsgType[BootstrapCoordinator.Protocol.ProbingFailed](10.seconds)
      failed.cause.getMessage should include("401")
    }
  }
}
