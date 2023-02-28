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

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.cluster.Cluster
import org.apache.pekko.http.scaladsl.model.{ StatusCodes, Uri }
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.management.scaladsl.ManagementRouteProviderSettings
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

object ClusterHttpManagementRouteProviderSpec {}

class ClusterHttpManagementRouteProviderSpec extends AnyWordSpec with ScalatestRouteTest with Matchers {

  val cluster = Cluster(system)

  "Cluster HTTP Management Route" should {
    val routes = ClusterHttpManagementRouteProvider(
      system.asInstanceOf[ExtendedActorSystem])
    "not expose write operations when readOnly set" in {
      val readOnlyRoutes = routes.routes(
        ManagementRouteProviderSettings(
          Uri("http://localhost"),
          readOnly = true))
      Get("/cluster/members") ~> readOnlyRoutes ~> check {
        handled shouldEqual true
        status shouldEqual StatusCodes.OK
      }
      Post("/cluster/members") ~> readOnlyRoutes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
      Get("/cluster/members/member1") ~> readOnlyRoutes ~> check {
        handled shouldEqual true
        status shouldEqual StatusCodes.NotFound
      }
      Delete("/cluster/members/member1") ~> readOnlyRoutes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
      Put("/cluster/members/member1") ~> readOnlyRoutes ~> check {
        status shouldEqual StatusCodes.MethodNotAllowed
      }
    }

    "expose write when readOnly false" in {
      val allRoutes = routes.routes(
        ManagementRouteProviderSettings(
          Uri("http://localhost"),
          readOnly = false))
      Get("/cluster/members") ~> allRoutes ~> check {
        handled shouldEqual true
      }
      Get("/cluster/members/member1") ~> allRoutes ~> check {
        handled shouldEqual true
        status shouldEqual StatusCodes.NotFound
      }
      Delete("/cluster/members/member1") ~> allRoutes ~> check {
        handled shouldEqual true
        status shouldEqual StatusCodes.NotFound
      }
      Put("/cluster/members/member1") ~> allRoutes ~> check {
        handled shouldEqual true
        status shouldEqual StatusCodes.NotFound
      }
    }
  }

}
