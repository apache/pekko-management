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

import scala.concurrent.Future

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import org.scalatest.BeforeAndAfterAll
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
    with ScalaFutures
    with BeforeAndAfterAll {

  override protected def afterAll(): Unit = shutdown()

  "AppVersionRevision extension" should {
    "return failed future if pod-name is not configured" in {
      val revisionExtension = AppVersionRevision(system)
      revisionExtension.start()
      val failure = revisionExtension.getRevision().failed.futureValue
      failure.getMessage should include("No configuration found to extract the pod name from")
    }

    "fail the revision when the Kubernetes API cannot be initialized" in {
      val config = ConfigFactory.parseString("""
          pekko.rollingupdate.kubernetes.pod-name = test-pod
          pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
        """).withFallback(AppVersionRevisionSpec.config)
      val revisionSystem = ActorSystem("AppVersionRevisionSetupSpec", config)
      try {
        val extension = AppVersionRevision(revisionSystem)
        val setupFailure = new IllegalStateException("Kubernetes API setup failed")
        extension.startWith(Future.failed[KubernetesApi](setupFailure))
        extension.getRevision().failed.futureValue shouldBe setupFailure
      } finally {
        TestKit.shutdownActorSystem(revisionSystem)
      }
    }
  }
}
