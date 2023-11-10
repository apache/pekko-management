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

package org.apache.pekko.management.loglevels.log4j2slf4j2

import org.apache.pekko
import pekko.actor.ExtendedActorSystem
import pekko.event.{ Logging => ClassicLogging }
import pekko.http.javadsl.server.MalformedQueryParamRejection
import pekko.http.scaladsl.model.{ StatusCodes, Uri }
import pekko.http.scaladsl.testkit.ScalatestRouteTest
import pekko.management.scaladsl.ManagementRouteProviderSettings
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.slf4j.LoggerFactory

class LogLevelRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  override def testConfigSource: String =
    """
      pekko.loglevel = INFO
      """

  val routes = LogLevelRoutes
    .createExtension(system.asInstanceOf[ExtendedActorSystem])
    .routes(ManagementRouteProviderSettings(Uri("https://example.com"), readOnly = false))

  "The logback log level routes" must {

    "show log level of a Logger" in {
      Get("/loglevel/log4j2?logger=LogLevelRoutesSpec") ~> routes ~> check {
        responseAs[String]
      }
    }

    "change log level of a Logger to ERROR" in {
      Put("/loglevel/log4j2?logger=LogLevelRoutesSpec&level=ERROR") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        println(response)
        LoggerFactory.getLogger("LogLevelRoutesSpec").isErrorEnabled should ===(true)
      }
    }

    "change log level of a Logger to DEBUG" in {
      Put("/loglevel/log4j2?logger=LogLevelRoutesSpec&level=DEBUG") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        println(response)
        LoggerFactory.getLogger("LogLevelRoutesSpec").isDebugEnabled() should ===(true)
      }
    }

    "change log level of a Logger to INFO" in {
      Put("/loglevel/log4j2?logger=LogLevelRoutesSpec&level=INFO") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        println(response)
        LoggerFactory.getLogger("LogLevelRoutesSpec").isInfoEnabled should ===(true)
      }
    }

    "change log level of a Logger to WARN" in {
      Put("/loglevel/log4j2?logger=LogLevelRoutesSpec&level=WARN") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        println(response)
        LoggerFactory.getLogger("LogLevelRoutesSpec").isWarnEnabled should ===(true)
      }
    }

    "fail for unknown log level" in {
      Put("/loglevel/log4j2?logger=LogLevelRoutesSpec&level=MONKEY") ~> routes ~> check {
        rejection shouldBe an[MalformedQueryParamRejection]
      }
    }

    "not change loglevel if read only" in {
      val readOnlyRoutes = LogLevelRoutes
        .createExtension(system.asInstanceOf[ExtendedActorSystem])
        .routes(ManagementRouteProviderSettings(Uri("https://example.com"), readOnly = true))
      Put("/loglevel/log4j2?logger=LogLevelRoutesSpec&level=DEBUG") ~> readOnlyRoutes ~> check {
        response.status should ===(StatusCodes.Forbidden)
      }
    }

    "allow inspecting classic Pekko loglevel" in {
      Get("/loglevel/pekko") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        responseAs[String] should ===("INFO")
      }
    }

    "allow changing classic Pekko loglevel" in {
      Put("/loglevel/pekko?level=DEBUG") ~> routes ~> check {
        response.status should ===(StatusCodes.OK)
        system.eventStream.logLevel should ===(ClassicLogging.DebugLevel)
      }
    }
  }

}
