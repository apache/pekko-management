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

package org.apache.pekko.coordination.lease.kubernetes

import org.apache.pekko.coordination.lease.TimeoutSettings
import com.typesafe.config.ConfigFactory
import scala.concurrent.duration._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class KubernetesSettingsSpec extends AnyWordSpec with Matchers {

  private def conf(overrides: String): KubernetesSettings = {
    val c = ConfigFactory
      .parseString(overrides)
      .withFallback(ConfigFactory.load().getConfig("pekko.coordination.lease.kubernetes"))
    KubernetesSettings(c, TimeoutSettings(c))
  }

  "Kubernetes Settings" should {
    "default request-timeout is 2/5 of the lease-operation-timeout" in {
      conf("lease-operation-timeout=5s").apiServerRequestTimeout shouldEqual 2.seconds
    }
    "body-read timoeut is 1/2 api request timeout" in {
      conf("lease-operation-timeout=5s").bodyReadTimeout shouldEqual 1.seconds
    }
    "allow overriding of api server request timeout" in {
      conf("""
           lease-operation-timeout=5s
           api-server-request-timeout=4s
        """.stripMargin).apiServerRequestTimeout shouldEqual 4.seconds
    }
    "default tls-version to v1.2" in {
      conf("").tlsVersion shouldEqual "TLSv1.2"
    }
    "support tls-version override" in {
      conf("tls-version=TLSv1.3").tlsVersion shouldEqual "TLSv1.3"
    }
    "not allow server request timeout greater than operation timeout" in {
      intercept[IllegalArgumentException] {
        conf("""
           lease-operation-timeout=5s
           api-server-request-timeout=6s
        """.stripMargin).apiServerRequestTimeout
      }.getMessage shouldEqual "requirement failed: 'api-server-request-timeout can not be less than 'lease-operation-timeout'"
    }
  }

}
