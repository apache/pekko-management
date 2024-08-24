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

import scala.annotation.nowarn
import scala.concurrent.duration.DurationInt

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

@nowarn("msg=deprecated")
class HealthCheckSettingsSpec extends AnyWordSpec with Matchers {

  "Health Check Settings" should {
    "filter out blank fqcn" in {
      HealthCheckSettings(ConfigFactory.parseString("""
         startup-checks {
          cluster-membership = ""
         }
         readiness-checks {}
         liveness-checks {}
         startup-path = ""
         readiness-path = ""
         liveness-path = ""
         check-timeout = 1s
        """)).startupChecks shouldEqual Nil
      HealthCheckSettings(ConfigFactory.parseString("""
         startup-checks {}
         readiness-checks {
          cluster-membership = ""
         }
         liveness-checks {}
         startup-path = ""
         readiness-path = ""
         liveness-path = ""
         check-timeout = 1s
        """)).readinessChecks shouldEqual Nil
      HealthCheckSettings(ConfigFactory.parseString("""
         startup-checks {}
         liveness-checks {
          cluster-membership = ""
         }
         readiness-checks {}
         startup-path = ""
         readiness-path = ""
         liveness-path = ""
         check-timeout = 1s
        """)).livenessChecks shouldEqual Nil
    }

    "be creatable with primary constructor" in {
      HealthCheckSettings.create(
        startupChecks = java.util.Collections.emptyList(),
        readinessChecks = java.util.Collections.emptyList(),
        livenessChecks = java.util.Collections.emptyList(),
        startupPath = "",
        readinessPath = "",
        livenessPath = "",
        checkDuration = java.time.Duration.ofSeconds(1L))
        .startupChecks shouldEqual Nil
    }

    "be creatable with legacy create method" in {
      HealthCheckSettings.create(
        readinessChecks = java.util.Collections.emptyList(),
        livenessChecks = java.util.Collections.emptyList(),
        readinessPath = "",
        livenessPath = "",
        checkDuration = java.time.Duration.ofSeconds(1L))
        .startupChecks shouldEqual Nil
    }

    "be creatable with legacy constructor" in {
      val healthCheckSettings = new HealthCheckSettings(
        readinessChecks = Nil,
        livenessChecks = Nil,
        readinessPath = "",
        livenessPath = "",
        checkTimeout = 1.seconds)
      healthCheckSettings.livenessChecks shouldEqual Nil
    }
  }

}
