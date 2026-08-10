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

package org.apache.pekko.management

import org.apache.pekko
import pekko.actor.ExtendedActorSystem
import pekko.http.scaladsl.model.{ StatusCodes, Uri }
import pekko.http.scaladsl.server._
import pekko.http.scaladsl.testkit.ScalatestRouteTest
import pekko.management.scaladsl.{ HealthChecks, ManagementRouteProviderSettings }

import scala.concurrent.Future
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HealthCheckRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private val eas = system.asInstanceOf[ExtendedActorSystem]

  private def testRouteWithProviderSettings(
      startupResultValue: Future[Either[String, Unit]] = Future.successful(Right(())),
      readyResultValue: Future[Either[String, Unit]] = Future.successful(Right(())),
      aliveResultValue: Future[Either[String, Unit]] = Future.successful(Right(()))): Route = {
    new HealthCheckRoutes(eas) {
      override protected val healthChecks: HealthChecks = new HealthChecks {
        override def startupResult(): Future[Either[String, Unit]] = startupResultValue
        override def startup(): Future[Boolean] = startupResultValue.map(_.isRight)
        override def readyResult(): Future[Either[String, Unit]] = readyResultValue
        override def ready(): Future[Boolean] = readyResultValue.map(_.isRight)
        override def aliveResult(): Future[Either[String, Unit]] = aliveResultValue
        override def alive(): Future[Boolean] = aliveResultValue.map(_.isRight)
      }
    }.routes(ManagementRouteProviderSettings(Uri("http://whocares"), readOnly = false))
  }

  private def testRoute(
      startupResultValue: Future[Either[String, Unit]] = Future.successful(Right(())),
      readyResultValue: Future[Either[String, Unit]] = Future.successful(Right(())),
      aliveResultValue: Future[Either[String, Unit]] = Future.successful(Right(()))): Route = {
    new HealthCheckRoutes(eas) {
      override protected val healthChecks: HealthChecks = new HealthChecks {
        override def startupResult(): Future[Either[String, Unit]] = startupResultValue
        override def startup(): Future[Boolean] = startupResultValue.map(_.isRight)
        override def readyResult(): Future[Either[String, Unit]] = readyResultValue
        override def ready(): Future[Boolean] = readyResultValue.map(_.isRight)
        override def aliveResult(): Future[Either[String, Unit]] = aliveResultValue
        override def alive(): Future[Boolean] = aliveResultValue.map(_.isRight)
      }
    }.routes()
  }

  tests("/startup", result => testRoute(startupResultValue = result))
  tests("/ready", result => testRoute(readyResultValue = result))
  tests("/alive", result => testRoute(aliveResultValue = result))

  // testRoutes with provider settings
  tests("/startup", result => testRouteWithProviderSettings(startupResultValue = result), withSettings = true)
  tests("/ready", result => testRouteWithProviderSettings(readyResultValue = result), withSettings = true)
  tests("/alive", result => testRouteWithProviderSettings(aliveResultValue = result), withSettings = true)

  def tests(endpoint: String, route: Future[Either[String, Unit]] => Route, withSettings: Boolean = false) = {
    s"Health check $endpoint endpoint - withSettingsProvided: $withSettings" should {
      "return 200 for Right" in {
        Get(endpoint) ~> route(Future.successful(Right(()))) ~> check {
          status shouldEqual StatusCodes.OK
        }
      }
      "return 500 for Left" in {
        Get(endpoint) ~> route(Future.successful(Left("com.someclass.MyCheck"))) ~> check {
          status shouldEqual StatusCodes.InternalServerError
          responseAs[String] shouldEqual "Not Healthy: com.someclass.MyCheck"
        }
      }
      "return 500 for fail" in {
        Get(endpoint) ~> route(Future.failed(new RuntimeException("darn it"))) ~> check {
          status shouldEqual StatusCodes.InternalServerError
          responseAs[String] shouldEqual "Health Check Failed: darn it"
        }
      }
    }
  }
}
