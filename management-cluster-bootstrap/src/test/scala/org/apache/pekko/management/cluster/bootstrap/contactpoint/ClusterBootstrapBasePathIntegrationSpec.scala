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

package org.apache.pekko.management.cluster.bootstrap.contactpoint

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.cluster.Cluster
import pekko.cluster.ClusterEvent.{ CurrentClusterState, MemberUp }
import pekko.discovery.ServiceDiscovery.{ Resolved, ResolvedTarget }
import pekko.discovery.{ Lookup, MockDiscovery }
import pekko.management.cluster.bootstrap.ClusterBootstrap
import pekko.management.scaladsl.PekkoManagement
import pekko.testkit.{ SocketUtil, TestKit, TestProbe }
import org.scalatest.Inside
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.InetAddress
import scala.concurrent.Future
import scala.concurrent.duration._

/**
 * This test ensures that the client and server both respect the base-path setting and thus that the bootstrapping
 * process works correctly when this setting is specified.
 */
class ClusterBootstrapBasePathIntegrationSpec extends AnyWordSpecLike with Matchers with Inside {

  "Cluster Bootstrap" should {
    val (managementPort, remotingPort) = inside(SocketUtil.temporaryServerAddresses(2, "127.0.0.1").map(_.getPort)) {
      case Vector(mPort: Int, rPort: Int) => (mPort, rPort)
      case o                              => fail("Expected 2 ports but got: " + o)
    }

    val config =
      ConfigFactory.parseString(s"""
        pekko {
          loglevel = INFO

          cluster.jmx.multi-mbeans-in-same-jvm = on

          cluster.http.management.port = $managementPort
          remote.netty.tcp.port = $remotingPort
          remote.artery.canonical.port = $remotingPort

          discovery.mock-dns.class = "org.apache.pekko.discovery.MockDiscovery"

          management {
            cluster.bootstrap {
              contact-point-discovery {
                discovery-method = mock-dns
                service-namespace = "svc.cluster.local"
                required-contact-point-nr = 1
              }
            }

            http {
              hostname = "127.0.0.1"
              base-path = "test"
              port = $managementPort
            }
          }
        }
        """.stripMargin).withFallback(ConfigFactory.load())

    val systemA = ActorSystem("basepathsystem", config)

    val clusterA = Cluster(systemA)

    val managementA = PekkoManagement(systemA)

    val bootstrapA = ClusterBootstrap(systemA)

    // prepare the "mock DNS"
    val name = "basepathsystem.svc.cluster.local"
    MockDiscovery.set(
      Lookup(name).withProtocol("tcp"),
      () =>
        Future.successful(
          Resolved(
            name,
            List(
              ResolvedTarget(
                host = "127.0.0.1",
                port = Some(managementPort),
                address = Option(InetAddress.getByName("127.0.0.1")))))))

    "start listening with the http contact-points on system" in {
      managementA.start()

      bootstrapA.start()
    }

    "join self, thus forming new cluster (happy path)" in {
      bootstrapA.discovery.getClass should ===(classOf[MockDiscovery])

      bootstrapA.start()

      val pA = TestProbe()(systemA)
      clusterA.subscribe(pA.ref, classOf[MemberUp])

      pA.expectMsgType[CurrentClusterState]
      val up1 = pA.expectMsgType[MemberUp](30.seconds)
      info("" + up1)
    }

    "terminate system" in {
      TestKit.shutdownActorSystem(systemA, 3.seconds)
    }

  }

}
