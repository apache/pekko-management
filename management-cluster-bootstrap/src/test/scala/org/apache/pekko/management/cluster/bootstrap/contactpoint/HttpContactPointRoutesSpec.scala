/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, derived from Akka.
 */

/*
 * Copyright (C) 2017-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.management.cluster.bootstrap.contactpoint

import org.apache.pekko.cluster.{ Cluster, ClusterEvent }
import org.apache.pekko.event.NoLogging
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.management.cluster.bootstrap.ClusterBootstrapSettings
import org.apache.pekko.testkit.TestProbe
import org.scalatest.concurrent.Eventually
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class HttpContactPointRoutesSpec
    extends AnyWordSpecLike
    with Matchers
    with ScalatestRouteTest
    with HttpBootstrapJsonProtocol
    with Eventually {

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(3, Seconds)), interval = scaled(Span(50, Millis)))

  override def testConfigSource =
    s"""
    pekko {
      remote {
        netty.tcp {
          hostname = "127.0.0.1"
          port = 0
        }
        artery.canonical {
          hostname = "127.0.0.1"
          port = 0
        }
      }
    }
    """.stripMargin

  "Http Bootstrap routes" should {

    val settings = ClusterBootstrapSettings(system.settings.config, NoLogging)
    val httpBootstrap = new HttpClusterBootstrapRoutes(settings)

    "empty list if node is not part of a cluster" in {
      ClusterBootstrapRequests.bootstrapSeedNodes("") ~> httpBootstrap.routes ~> check {
        responseAs[String] should include(""""seedNodes":[]""")
      }
    }

    "include seed nodes when part of a cluster" in {
      val cluster = Cluster(system)
      cluster.join(cluster.selfAddress)

      val p = TestProbe()
      cluster.subscribe(p.ref, ClusterEvent.InitialStateAsEvents, classOf[ClusterEvent.MemberUp])
      val up = p.expectMsgType[ClusterEvent.MemberUp]
      up.member should ===(cluster.selfMember)

      eventually {
        ClusterBootstrapRequests.bootstrapSeedNodes("") ~> httpBootstrap.routes ~> check {
          val response = responseAs[HttpBootstrapJsonProtocol.SeedNodes]
          response.seedNodes should !==(Set.empty)
          response.seedNodes.map(_.node) should contain(cluster.selfAddress)
        }
      }
    }
  }

}
