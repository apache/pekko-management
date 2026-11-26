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

import java.util.concurrent.atomic.AtomicReference

import scala.concurrent.Future
import scala.concurrent.duration._

import org.apache.pekko
import pekko.actor.ActorRef
import pekko.actor.ActorSystem
import pekko.actor.Address
import pekko.actor.Props
import pekko.discovery.{ Lookup, MockDiscovery }
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import pekko.http.scaladsl.model.Uri
import pekko.http.scaladsl.model.headers.Authorization
import pekko.http.scaladsl.server.Directives._
import pekko.http.scaladsl.server.Route
import pekko.management.cluster.bootstrap.ClusterBootstrapSettings
import pekko.management.cluster.bootstrap.JoinDecider
import pekko.management.cluster.bootstrap.JoinDecision
import pekko.management.cluster.bootstrap.KeepProbing
import pekko.management.cluster.bootstrap.SeedNodesInformation
import pekko.management.cluster.bootstrap.contactpoint.HttpBootstrapJsonProtocol
import pekko.management.cluster.bootstrap.contactpoint.HttpBootstrapJsonProtocol.SeedNodes
import pekko.management.cluster.bootstrap.internal.BootstrapCoordinator.Protocol.InitiateBootstrapping
import pekko.testkit.TestKit
import pekko.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Seconds, Span }
import org.scalatest.wordspec.AnyWordSpecLike

class BootstrapCoordinatorBasicAuthSpec
    extends TestKit(ActorSystem(
      "BootstrapCoordinatorBasicAuthSpec",
      ConfigFactory.parseString("""
        pekko.actor.provider = cluster
        pekko.remote.artery.canonical.hostname = "127.0.0.1"
        pekko.remote.artery.canonical.port = 0
        pekko.management.cluster.bootstrap {
          contact-point-discovery.interval = 100 ms
          contact-point.probe-interval = 100 ms
          # the contact points of this test share a host and only differ in their port
          contact-point.filter-on-fallback-port = off
        }
        """).withFallback(ConfigFactory.load())))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures
    with BeforeAndAfterAll
    with SprayJsonSupport
    with HttpBootstrapJsonProtocol {

  private implicit val patience: PatienceConfig = PatienceConfig(Span(10, Seconds))

  private val discovery = new MockDiscovery(system)

  // never joins, so that the coordinator (and with it the probes) only stops when a test expects it to
  private val keepProbing = new JoinDecider {
    override def decide(info: SeedNodesInformation): Future[JoinDecision] = Future.successful(KeepProbing)
  }

  // each probe request is reported as (port that was probed, whether it carried an Authorization header)
  private val requests = TestProbe()

  private def contactPointRoute(port: => Int): Route =
    path("bootstrap" / "seed-nodes") {
      get {
        optionalHeaderValueByType(Authorization) { authorization =>
          requests.ref ! (port -> authorization.isDefined)
          complete(SeedNodes(Address("pekko", "contact-point", "10.0.0.1", 7355), Set.empty))
        }
      }
    }

  // stand in for the Pekko Management endpoints of two other nodes
  private lazy val binding1: Http.ServerBinding =
    Http().newServerAt("127.0.0.1", 0).bind(contactPointRoute(binding1.localAddress.getPort)).futureValue
  private lazy val binding2: Http.ServerBinding =
    Http().newServerAt("127.0.0.1", 0).bind(contactPointRoute(binding2.localAddress.getPort)).futureValue
  private lazy val contactPoint1 = ResolvedTarget("127.0.0.1", Some(binding1.localAddress.getPort), None)
  private lazy val contactPoint2 = ResolvedTarget("127.0.0.1", Some(binding2.localAddress.getPort), None)

  private val basicAuth = """
    pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth {
      username = "bootstrap"
      password = "s3cr3t"
    }"""

  private val allowInsecureHttp =
    "pekko.management.cluster.bootstrap.contact-point.http-client.basic-auth.allow-insecure-http = on"

  private def settings(serviceName: String, extraConfig: String): ClusterBootstrapSettings =
    ClusterBootstrapSettings(
      ConfigFactory
        .parseString(extraConfig)
        .withFallback(ConfigFactory.parseString(
          s"pekko.management.cluster.bootstrap.contact-point-discovery.service-name = $serviceName"))
        .withFallback(system.settings.config),
      system.log)

  private class Fixture(serviceName: String, extraConfig: String) {
    val discovered = new AtomicReference[List[ResolvedTarget]](List(contactPoint1, contactPoint2))
    MockDiscovery.set(
      Lookup(serviceName, portName = None, protocol = Some("tcp")),
      () => Future.successful(Resolved(serviceName, discovered.get)))

    // the probing actors, reported as they are looked up or started
    val probes = TestProbe()
    val coordinator: ActorRef = system.actorOf(
      Props(new BootstrapCoordinator(discovery, keepProbing, settings(serviceName, extraConfig)) {
        override def ensureProbing(selfContactPointScheme: String, contactPoint: ResolvedTarget): Option[ActorRef] = {
          val probe = super.ensureProbing(selfContactPointScheme, contactPoint)
          probe.foreach(probes.ref ! contactPoint -> _)
          probe
        }
      }))

    def probeOf(contactPoint: ResolvedTarget): ActorRef =
      probes.fishForSpecificMessage(10.seconds) { case (`contactPoint`, ref: ActorRef) => ref }

    def expectRequest(contactPoint: ResolvedTarget, withCredentials: Boolean): Unit =
      requests.fishForSpecificMessage(10.seconds) {
        case (port, `withCredentials`) if contactPoint.port.contains(port) => ()
      }
  }

  override def afterAll(): Unit = {
    binding1.unbind().futureValue
    binding2.unbind().futureValue
    TestKit.shutdownActorSystem(system)
    super.afterAll()
  }

  "The bootstrap coordinator, with basic auth credentials configured" should {

    "not start bootstrapping over plain http" in new Fixture("basic-auth-plain-http", basicAuth) {
      val watcher = TestProbe()
      watcher.watch(coordinator)
      coordinator ! InitiateBootstrapping(Uri("http://127.0.0.1:7626"))
      watcher.expectTerminated(coordinator, 10.seconds)
      // nothing was probed, with or without the credentials
      probes.expectNoMessage(300.millis)
      requests.expectNoMessage(300.millis)
    }

    "stop probing a contact point as soon as discovery no longer returns it" in new Fixture(
      "basic-auth-removal",
      basicAuth + "\n" + allowInsecureHttp) {
      try {
        coordinator ! InitiateBootstrapping(Uri("http://127.0.0.1:7626"))
        val probe1 = probeOf(contactPoint1)
        val probe2 = probeOf(contactPoint2)
        expectRequest(contactPoint1, withCredentials = true)
        expectRequest(contactPoint2, withCredentials = true)

        val watcher = TestProbe()
        watcher.watch(probe1)
        watcher.watch(probe2)
        // contactPoint1 still answers its probes, so it is not the probing failure timeout that stops them
        discovered.set(List(contactPoint2))
        watcher.expectTerminated(probe1, 10.seconds)

        // a request that was already on its way may still arrive, after that contactPoint1 gets no more of them
        requests.receiveWhile(max = 500.millis) {
          case _ => ()
        }
        requests.receiveWhile(max = 500.millis) {
          case (port, _) => port
        } should contain only contactPoint2.port.get

        // the contact point that is still discovered keeps being probed
        watcher.expectNoMessage(100.millis)
        expectRequest(contactPoint2, withCredentials = true)

        // and a contact point that comes back is probed again
        discovered.set(List(contactPoint1, contactPoint2))
        expectRequest(contactPoint1, withCredentials = true)
      } finally system.stop(coordinator)
    }
  }

  "The bootstrap coordinator, without basic auth credentials" should {

    "keep probing a contact point that discovery no longer returns until probing fails" in new Fixture(
      "anonymous-removal",
      "") {
      try {
        coordinator ! InitiateBootstrapping(Uri("http://127.0.0.1:7626"))
        val probe1 = probeOf(contactPoint1)
        expectRequest(contactPoint1, withCredentials = false)

        val watcher = TestProbe()
        watcher.watch(probe1)
        discovered.set(List(contactPoint2))
        // several discovery rounds later
        watcher.expectNoMessage(500.millis)
        expectRequest(contactPoint1, withCredentials = false)
      } finally system.stop(coordinator)
    }
  }
}
