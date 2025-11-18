/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.management.cluster.bootstrap.internal

import java.util.concurrent.atomic.AtomicReference

import org.apache.pekko
import pekko.actor.{ ActorRef, ActorSystem, Props }
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.{ Lookup, MockDiscovery }
import pekko.http.scaladsl.model.Uri
import pekko.management.cluster.bootstrap.internal.BootstrapCoordinator.Protocol.InitiateBootstrapping
import pekko.management.cluster.bootstrap.{ ClusterBootstrapSettings, LowestAddressJoinDecider }
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.{ Await, Future }
import scala.concurrent.duration._

class BootstrapCoordinatorSpec extends AnyWordSpec with Matchers with BeforeAndAfterAll with Eventually {
  val serviceName = "bootstrap-coordinator-test-service"
  val selfUri = Uri("http://localhost:7626/")
  val system = ActorSystem(
    "test",
    ConfigFactory.parseString(s"""
      |pekko.management.cluster.bootstrap {
      | contact-point-discovery.service-name = $serviceName
      |}
    """.stripMargin).withFallback(ConfigFactory.load()))
  val settings = ClusterBootstrapSettings(system.settings.config, system.log)
  val joinDecider = new LowestAddressJoinDecider(system, settings)

  val discovery = new MockDiscovery(system)

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  "The bootstrap coordinator, when avoiding named port lookups" should {

    "probe only on the Pekko Management port" in {

      MockDiscovery.set(
        Lookup(serviceName, portName = None, protocol = Some("tcp")),
        () =>
          Future.successful(
            Resolved(
              serviceName,
              List(
                ResolvedTarget("host1", Some(7355), None),
                ResolvedTarget("host1", Some(7626), None),
                ResolvedTarget("host2", Some(7355), None),
                ResolvedTarget("host2", Some(7626), None)))))

      val targets = new AtomicReference[List[ResolvedTarget]](Nil)
      val coordinator = system.actorOf(
        Props(new BootstrapCoordinator(discovery, joinDecider, settings) {
          override def ensureProbing(
              selfContactPointScheme: String,
              contactPoint: ResolvedTarget): Option[ActorRef] = {
            println(s"Resolving $contactPoint")
            val targetsSoFar = targets.get
            targets.compareAndSet(targetsSoFar, contactPoint +: targetsSoFar)
            None
          }
        }))
      coordinator ! InitiateBootstrapping(selfUri)
      eventually {
        val targetsToCheck = targets.get
        targetsToCheck.length should be >= 2
        targetsToCheck.map(_.host) should contain("host1")
        targetsToCheck.map(_.host) should contain("host2")
        targetsToCheck.flatMap(_.port).toSet should be(Set(7626))
      }
    }

    "probe all hosts with fallback port" in {

      MockDiscovery.set(
        Lookup(serviceName, portName = None, protocol = Some("tcp")),
        () =>
          Future.successful(
            Resolved(
              serviceName,
              List(
                ResolvedTarget("host1", None, None),
                ResolvedTarget("host1", None, None),
                ResolvedTarget("host2", None, None),
                ResolvedTarget("host2", None, None)))))

      val targets = new AtomicReference[List[ResolvedTarget]](Nil)
      val coordinator = system.actorOf(Props(new BootstrapCoordinator(discovery, joinDecider, settings) {
        override def ensureProbing(selfContactPointScheme: String, contactPoint: ResolvedTarget): Option[ActorRef] = {
          println(s"Resolving $contactPoint")
          val targetsSoFar = targets.get
          targets.compareAndSet(targetsSoFar, contactPoint +: targetsSoFar)
          None
        }
      }))
      coordinator ! InitiateBootstrapping(selfUri)
      eventually {
        val targetsToCheck = targets.get
        targetsToCheck.length should be >= 2
        targetsToCheck.map(_.host) should contain("host1")
        targetsToCheck.map(_.host) should contain("host2")
        targetsToCheck.flatMap(_.port).toSet shouldBe empty
      }
    }
  }

  "BootstrapCoordinator target filtering" should {
    // For example when using DNS SRV-record-based discovery
    "not filter when port-name is set" in {
      val beforeFiltering = List(
        ResolvedTarget("host1", Some(1), None),
        ResolvedTarget("host2", Some(2), None),
        ResolvedTarget("host3", Some(3), None),
        ResolvedTarget("host4", Some(4), None))

      BootstrapCoordinator.selectHosts(
        Lookup.create("service").withPortName("cats"),
        7626,
        filterOnFallbackPort = true,
        beforeFiltering) shouldEqual beforeFiltering
    }

    // For example when using DNS A-record-based discovery in K8s
    "filter when port-name is not set" in {
      val beforeFiltering = List(
        ResolvedTarget("host1", Some(7626), None),
        ResolvedTarget("host1", Some(2), None),
        ResolvedTarget("host2", Some(7626), None),
        ResolvedTarget("host2", Some(4), None))

      BootstrapCoordinator.selectHosts(Lookup.create("service"), 7626, filterOnFallbackPort = true,
        beforeFiltering) shouldEqual List(
        ResolvedTarget("host1", Some(7626), None),
        ResolvedTarget("host2", Some(7626), None))
    }

    // For example when using ECS service discovery
    "not filter when port-name is not set but filtering disabled" in {
      val beforeFiltering = List(
        ResolvedTarget("host1", Some(1), None),
        ResolvedTarget("host1", Some(2), None),
        ResolvedTarget("host2", Some(3), None),
        ResolvedTarget("host2", Some(4), None))

      BootstrapCoordinator.selectHosts(Lookup.create("service"), 7626, filterOnFallbackPort = false,
        beforeFiltering) shouldEqual beforeFiltering
    }

    "not filter if there is a single target per host" in {
      val beforeFiltering = List(
        ResolvedTarget("host1", Some(7626), None),
        ResolvedTarget("host2", Some(2), None),
        ResolvedTarget("host3", Some(7626), None),
        ResolvedTarget("host4", Some(4), None))

      BootstrapCoordinator
        .selectHosts(Lookup.create("service"), 7626, filterOnFallbackPort = true, beforeFiltering)
        .toSet shouldEqual beforeFiltering.toSet
    }
  }

  override def afterAll(): Unit = {
    Await.result(system.terminate(), 10.seconds)
    super.afterAll()
  }
}
