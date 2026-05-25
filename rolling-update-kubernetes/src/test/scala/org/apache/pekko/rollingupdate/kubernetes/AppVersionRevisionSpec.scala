/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.rollingupdate.kubernetes

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import pekko.actor.ActorSystem
import pekko.testkit.TestKit

object AppVersionRevisionSpec {
  val config = ConfigFactory.parseString("""
      pekko.actor.provider = cluster

      pekko.remote.artery.canonical.port = 0
      pekko.remote.artery.canonical.hostname = 127.0.0.1

      pekko.coordinated-shutdown.terminate-actor-system = off
      pekko.coordinated-shutdown.run-by-actor-system-terminate = off
      pekko.rollingupdate.kubernetes.pod-name = ""
    """)
}
class AppVersionRevisionSpec
    extends TestKit(
      ActorSystem(
        "AppVersionRevisionSpec",
        AppVersionRevisionSpec.config
      ))
    with AnyWordSpecLike
    with Matchers
    with ScalaFutures {

  "AppVersionRevision extension" should {
    "return failed future if pod-name is not configured" in {
      val revisionExtension = AppVersionRevision(system)
      revisionExtension.start()
      val failure = revisionExtension.getRevision().failed.futureValue
      failure.getMessage should include("No configuration found to extract the pod name from")
    }
  }
}

