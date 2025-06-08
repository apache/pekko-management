/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.coordination.lease.kubernetes

import java.util.concurrent.Executors

import scala.collection.immutable
import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.coordination.lease.TimeoutSettings
import pekko.coordination.lease.kubernetes.internal.KubernetesApiImpl
import pekko.coordination.lease.scaladsl.LeaseProvider
import pekko.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers

/**
 * This test requires an API server available on localhost:8080 with a namespace called lease
 *
 * One way of doing this is to have a kubectl proxy open:
 *
 * `kubectl proxy --port=8080`
 * Test in CI:
 * https://github.com/akka/akka-management/issues/679
 */
class LeaseContentionSpec extends TestKit(ActorSystem("LeaseContentionSpec",
      ConfigFactory.parseString(
        """
    pekko.loglevel = INFO
    pekko.coordination.lease.kubernetes {
      api-service-host = localhost
      api-service-port = 8080
      namespace = "lease"
      namespace-path = ""
      secure-api-server = false
    }

  """))) with AnyWordSpecLike with Matchers with ScalaFutures with BeforeAndAfterAll {

  implicit val patience: PatienceConfig = PatienceConfig(testKitSettings.DefaultTimeout.duration)

  // for cleanup
  val k8sApi = new KubernetesApiImpl(system,
    KubernetesSettings(system,
      TimeoutSettings(system.settings.config.getConfig("pekko.coordination.lease.kubernetes"))))

  val lease1 = "contended-lease"
  val lease2 = "contended-lease-2"

  override protected def beforeAll(): Unit = {
    k8sApi.removeLease(lease1).futureValue
    k8sApi.removeLease(lease2).futureValue
  }

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  "A lease under contention" should {

    "only allow one client to get acquire lease" in {
      val underTest = LeaseProvider(system)
      val nrClients = 30
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(nrClients)) // too many = HTTP request queue of pool fills up
      // could make this more contended with a countdown latch so they all start at the same time
      val leases: immutable.Seq[(String, Boolean)] = Future.sequence((0 until nrClients).map(i => {
        val clientName = s"client$i"
        val lease = underTest.getLease(lease1, KubernetesLease.configPath, clientName)
        Future {
          lease.acquire()
        }.flatMap(identity).map(granted => (clientName, granted))
      })).futureValue

      val numberGranted = leases.count { case (_, granted) => granted }
      withClue(s"More than one lease granted $leases") {
        numberGranted shouldEqual 1
      }
    }
  }

}
