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

import java.util.concurrent.atomic.AtomicInteger

import scala.concurrent.Future
import scala.concurrent.duration._

import org.apache.pekko
import pekko.Done
import pekko.actor.ActorSystem
import pekko.actor.Address
import pekko.cluster.Cluster
import pekko.cluster.ClusterEvent.MemberUp
import pekko.cluster.Member
import pekko.cluster.MemberStatus
import pekko.cluster.UniqueAddress
import pekko.testkit.EventFilter
import pekko.testkit.ImplicitSender
import pekko.testkit.TestKit
import pekko.testkit.TestProbe
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.BeforeAndAfterEach
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.Millis
import org.scalatest.time.Seconds
import org.scalatest.time.Span
import org.scalatest.wordspec.AnyWordSpecLike

object PodDeletionCostAnnotatorCrSpec {
  val config = ConfigFactory.parseString("""
      pekko.loggers = ["org.apache.pekko.testkit.TestEventListener"]
      pekko.actor.provider = cluster
      pekko.rollingupdate.kubernetes.pod-deletion-cost.retry-delay = 1s

      pekko.remote.artery.canonical.port = 0
      pekko.remote.artery.canonical.hostname = 127.0.0.1

      pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
      pekko.coordinated-shutdown.terminate-actor-system = off
      pekko.coordinated-shutdown.run-by-actor-system-terminate = off
      pekko.test.filter-leeway = 10s
    """)

  private[pekko] trait TestCallCount {
    val callCount = new AtomicInteger()

    def getCallCount(): Int = callCount.get()
  }

  private[pekko] class TestKubernetesApi extends KubernetesApi {
    private var version = 1
    private var podCosts = Vector.empty[PodCost]

    override def namespace: String = "namespace-test"

    override def updatePodDeletionCostAnnotation(podName: String, cost: Int): Future[Done] =
      Future.successful(Done)

    override def readOrCreatePodCostResource(crName: String): Future[PodCostResource] = this.synchronized {
      Future.successful(PodCostResource(version.toString, podCosts))
    }

    override def updatePodCostResource(
        crName: String,
        v: String,
        pods: Seq[PodCost]): Future[Either[PodCostResource, PodCostResource]] = this.synchronized {

      podCosts = pods.toVector
      version = v.toInt + 1

      Future.successful(Right(PodCostResource(version.toString, podCosts)))
    }

    def getPodCosts(): Vector[PodCost] = this.synchronized {
      podCosts
    }

    override def readRevision(): Future[String] = Future.successful("1")
  }
}

class PodDeletionCostAnnotatorCrSpec
    extends TestKit(
      ActorSystem(
        "PodDeletionCostAnnotatorCrSpec",
        PodDeletionCostAnnotatorCrSpec.config
      ))
    with ImplicitSender
    with AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with BeforeAndAfterEach
    with Eventually {

  import PodDeletionCostAnnotatorCrSpec._

  private val namespace = "namespace-test"
  private val podName1 = "pod-test-1"
  private val podName2 = "pod-test-2"
  private lazy val system2 = ActorSystem("PodDeletionCostAnnotatorCrSpec", PodDeletionCostAnnotatorCrSpec.config)

  private def settings(podName: String) = {
    new KubernetesSettings(
      apiCaPath = "",
      apiTokenPath = "",
      apiServiceHost = "localhost",
      apiServicePort = 0,
      namespace = Some(namespace),
      namespacePath = "",
      podName = podName,
      secure = false,
      apiServiceRequestTimeout = 2.seconds,
      customResourceSettings = new CustomResourceSettings(enabled = true, crName = Some("test-cr"), 60.seconds)
    )
  }

  private def annotatorProps(pod: String, api: KubernetesApi) = PodDeletionCostAnnotator.props(
    settings(pod),
    PodDeletionCostSettings(system.settings.config.getConfig("pekko.rollingupdate.kubernetes")),
    api,
    crName = Some("test-cr")
  )

  override implicit val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(100, Millis))

  override protected def afterAll(): Unit = {
    super.shutdown()
    TestKit.shutdownActorSystem(system2)
  }

  override protected def beforeEach(): Unit = {}

  "The pod-deletion-cost annotator with CRD" should {

    "have a single node cluster running first" in {
      val probe = TestProbe()
      Cluster(system).join(Cluster(system).selfMember.address)
      probe.awaitAssert({
          Cluster(system).selfMember.status == MemberStatus.Up
        }, 3.seconds)
    }

    "write pod cost to custom resource" in {
      val api = new TestKubernetesApi
      EventFilter
        .info(pattern = ".*Updating PodCost CR.*", occurrences = 1)
        .intercept {
          system.actorOf(annotatorProps(podName1, api))
        }
      eventually {
        api.getPodCosts() should have size 1
        api.getPodCosts().head.podName shouldEqual podName1
      }
    }

    "update pod cost for second node in the cluster" in {
      val api = new TestKubernetesApi

      val probe = TestProbe()
      Cluster(system2).join(Cluster(system).selfMember.address)
      probe.awaitAssert({
          Cluster(system2).selfMember.status == MemberStatus.Up
        }, 3.seconds)

      system2.actorOf(annotatorProps(podName2, api))

      eventually {
        val costs = api.getPodCosts()
        costs.map(_.podName) should contain(podName2)
      }
    }

  }

}
