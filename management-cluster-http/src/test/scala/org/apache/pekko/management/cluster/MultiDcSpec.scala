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

package org.apache.pekko.management.cluster

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model.{ HttpRequest, StatusCodes }
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.testkit.SocketUtil
import com.typesafe.config.ConfigFactory
import org.apache.pekko.management.scaladsl.ManagementRouteProviderSettings
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MultiDcSpec
    extends AnyWordSpec
    with Matchers
    with ScalaFutures
    with ClusterHttpManagementJsonProtocol
    with Eventually {

  implicit val patience: PatienceConfig = PatienceConfig(timeout = Span(10, Seconds), interval = Span(50, Millis))

  val config = ConfigFactory.parseString(
    """
      |pekko.actor.provider = "cluster"
      |pekko.remote.log-remote-lifecycle-events = off
      |pekko.remote.artery.canonical.hostname = "127.0.0.1"
      |pekko.remote.artery.enabled = true
      |#pekko.loglevel = DEBUG
    """.stripMargin)

  "Http cluster management" must {
    "allow multiple DCs" in {
      val Vector(httpPortA, portA, portB) = SocketUtil.temporaryServerAddresses(3, "127.0.0.1").map(_.getPort)
      val dcA = ConfigFactory.parseString(
        s"""
           |pekko.management.http.hostname = "127.0.0.1"
           |pekko.management.http.port = $httpPortA
           |pekko.cluster.seed-nodes = ["pekko://MultiDcSystem@127.0.0.1:$portA"]
           |pekko.cluster.multi-data-center.self-data-center = "DC-A"
           |pekko.remote.artery.canonical.port = $portA
           |pekko.remote.artery.canonical.port = $portA
           |          """.stripMargin)
      val dcB = ConfigFactory.parseString(
        s"""
           |pekko.cluster.seed-nodes = ["pekko://MultiDcSystem@127.0.0.1:$portA"]
           |pekko.cluster.multi-data-center.self-data-center = "DC-B"
           |pekko.remote.artery.canonical.port = $portB
           |          """.stripMargin)

      implicit val dcASystem = ActorSystem("MultiDcSystem", config.withFallback(dcA))
      val dcBSystem = ActorSystem("MultiDcSystem", config.withFallback(dcB))

      val routeSettings =
        ManagementRouteProviderSettings(selfBaseUri = s"http://127.0.0.1:$httpPortA", readOnly = false)

      try {
        Http()
          .newServerAt("127.0.0.1", httpPortA)
          .bind(ClusterHttpManagementRouteProvider(dcASystem).routes(routeSettings))
          .futureValue

        eventually {
          val response =
            Http().singleRequest(HttpRequest(uri = s"http://127.0.0.1:$httpPortA/cluster/members")).futureValue
          response.status should equal(StatusCodes.OK)
          val members = Unmarshal(response.entity).to[ClusterMembers].futureValue
          members.members.size should equal(2)
          members.members.map(_.status) should equal(Set("Up"))
        }
      } finally {
        dcASystem.terminate()
        dcBSystem.terminate()
      }
    }
  }
}
