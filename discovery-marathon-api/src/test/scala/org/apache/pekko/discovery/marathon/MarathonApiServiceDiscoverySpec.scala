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

package org.apache.pekko.discovery.marathon

import java.net.InetAddress
import org.apache.pekko.discovery.ServiceDiscovery.ResolvedTarget
import spray.json._

import scala.io.Source
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MarathonApiServiceDiscoverySpec extends AnyWordSpec with Matchers {
  "targets" should {
    "calculate the correct list of resolved targets" in {
      val data = resourceAsString("apps.json")

      val appList = JsonFormat.appListFormat.read(data.parseJson)

      MarathonApiServiceDiscovery.targets(appList, "management") shouldBe List(
        ResolvedTarget(
          host = "192.168.65.60",
          port = Some(23236),
          address = Option(InetAddress.getByName("192.168.65.60"))),
        ResolvedTarget(
          host = "192.168.65.111",
          port = Some(6850),
          address = Option(InetAddress.getByName("192.168.65.111"))))
    }
    "calculate the correct list of resolved targets for docker" in {
      val data = resourceAsString("docker-app.json")

      val appList = JsonFormat.appListFormat.read(data.parseJson)

      MarathonApiServiceDiscovery.targets(appList, "pekkomgmthttp") shouldBe List(
        ResolvedTarget(
          host = "10.121.48.204",
          port = Some(29480),
          address = Option(InetAddress.getByName("10.121.48.204"))),
        ResolvedTarget(
          host = "10.121.48.204",
          port = Some(10136),
          address = Option(InetAddress.getByName("10.121.48.204"))))
    }
  }

  private def resourceAsString(name: String): String =
    Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(name)).mkString
}
