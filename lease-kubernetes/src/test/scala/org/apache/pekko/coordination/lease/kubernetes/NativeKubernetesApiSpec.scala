/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.pekko.coordination.lease.kubernetes

import java.time.temporal.TemporalAccessor
import java.time.{ Instant, LocalDateTime, ZoneId }

import org.apache.pekko
import pekko.Done
import pekko.actor.ActorSystem
import pekko.coordination.lease.kubernetes.internal.NativeKubernetesApiImpl
import pekko.http.scaladsl.model.StatusCodes
import pekko.testkit.TestKit
import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.github.tomakehurst.wiremock.client.WireMock._
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.typesafe.config.ConfigFactory
import org.scalatest.{ BeforeAndAfterAll, BeforeAndAfterEach }
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._

class NativeKubernetesApiSpec
    extends TestKit(
      ActorSystem(
        "NativeKubernetesApiSpec",
        ConfigFactory.parseString("""pekko.coordination.lease.kubernetes {
        |    lease-operation-timeout = 10s
        |}
        |""".stripMargin)))
    with ScalaFutures
    with AnyWordSpecLike
    with BeforeAndAfterAll
    with Matchers
    with BeforeAndAfterEach {

  val wireMockServer = new WireMockServer(wireMockConfig().port(0))
  wireMockServer.start()

  val settings = new KubernetesSettings(
    "",
    "",
    "localhost",
    wireMockServer.port(),
    namespace = Some("lease"),
    "",
    apiServerRequestTimeout = 1.second,
    secure = false)

  WireMock.configureFor(settings.apiServerPort)

  implicit val patience: PatienceConfig = PatienceConfig(testKitSettings.DefaultTimeout.duration)

  val underTest = new NativeKubernetesApiImpl(system, settings) {
    // avoid touching slow CI filesystem
    override protected def readConfigVarFromFilesystem(path: String, name: String): Option[String] = None
  }
  val leaseName = "lease-1"
  val client1 = "client-1"
  val client2 = "client-2"

  override protected def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  override protected def beforeEach(): Unit = {
    wireMockServer.resetAll()
  }

  private def toRFC3339MicroString(t: TemporalAccessor): String =
    NativeKubernetesApiImpl.RFC3339MICRO_FORMATTER.withZone(ZoneId.of("UTC")).format(t)
  private def fromRFC3339MicroString(s: String): Long =
    LocalDateTime.parse(s, NativeKubernetesApiImpl.RFC3339MICRO_FORMATTER).atZone(
      ZoneId.of("UTC")).toInstant.toEpochMilli

  "Kubernetes native lease resource" should {
    "be able to be created" in {
      val version = "1234"
      stubFor(
        post(urlEqualTo("/apis/coordination.k8s.io/v1/namespaces/lease/leases/"))
          .willReturn(aResponse().withStatus(201).withHeader("Content-Type", "application/json").withBody(s"""
               |{
               |    "apiVersion": "coordination.k8s.io/v1",
               |    "kind": "Lease",
               |    "metadata": {
               |        "name": "lease-1",
               |        "namespace": "pekko-lease-tests",
               |        "resourceVersion": "$version",
               |        "uid": "c369949e-296c-11e9-9c62-16f8dd5735ba"
               |    },
               |    "spec": {
               |        "holderIdentity": "",
               |        "acquireTime": "2024-05-03T13:55:17.655342Z"
               |    }
               |}
            """.stripMargin)))

      underTest.removeLease(leaseName).futureValue shouldEqual Done
      val leaseRecord = underTest.readOrCreateLeaseResource(leaseName).futureValue
      leaseRecord.owner shouldEqual None
      leaseRecord.version shouldNot equal("")
      leaseRecord.version shouldEqual version
    }

    "update a lease successfully" in {
      val holderIdentity = "client1"
      val lease = "lease-1"
      val version = "2"
      val updatedVersion = "3"
      val timestamp = toRFC3339MicroString(Instant.now())
      stubFor(
        put(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/$lease"))
          .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(s"""
               |{
               |    "apiVersion": "coordination.k8s.io/v1",
               |    "kind": "Lease",
               |    "metadata": {
               |        "name": "lease-1",
               |        "namespace": "pekko-lease-tests",
               |        "resourceVersion": "$updatedVersion",
               |        "uid": "c369949e-296c-11e9-9c62-16f8dd5735ba"
               |    },
               |    "spec": {
               |        "holderIdentity": "$holderIdentity",
               |        "acquireTime": "$timestamp"
               |    }
               |}
            """.stripMargin)))

      val response =
        underTest.updateLeaseResource(lease, holderIdentity, version, fromRFC3339MicroString(timestamp)).futureValue
      response shouldEqual Right(LeaseResource(Some(holderIdentity), updatedVersion, fromRFC3339MicroString(timestamp)))
    }

    "update a lease conflict" in {
      val owner = "client1"
      val conflictedHolderIdentity = "client2"
      val lease = "lease-1"
      val version = "2"
      val updatedVersion = "3"
      val timestamp = toRFC3339MicroString(Instant.now())
      // Conflict
      stubFor(
        put(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/$lease"))
          .willReturn(aResponse().withStatus(StatusCodes.Conflict.intValue)))

      // Read to get version
      stubFor(
        get(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/$lease")).willReturn(
          aResponse().withStatus(StatusCodes.OK.intValue).withHeader("Content-Type", "application/json").withBody(s"""
               |{
               |    "apiVersion": "coordination.k8s.io/v1",
               |    "kind": "Lease",
               |    "metadata": {
               |        "name": "lease-1",
               |        "namespace": "pekko-lease-tests",
               |        "resourceVersion": "$updatedVersion",
               |        "uid": "c369949e-296c-11e9-9c62-16f8dd5735ba"
               |    },
               |    "spec": {
               |        "holderIdentity": "$conflictedHolderIdentity",
               |        "acquireTime": "$timestamp"
               |    }
               |}
            """.stripMargin)))

      val response = underTest.updateLeaseResource(lease, owner, version, fromRFC3339MicroString(timestamp)).futureValue
      response shouldEqual Left(LeaseResource(Some(conflictedHolderIdentity), updatedVersion,
        fromRFC3339MicroString(timestamp)))
    }

    "remove lease via DELETE" in {
      val lease = "lease-1"
      stubFor(
        delete(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/$lease"))
          .willReturn(aResponse().withStatus(StatusCodes.OK.intValue)))

      val response = underTest.removeLease(lease).futureValue
      response shouldEqual Done
    }

    "timeout on readLease" in {
      val owner = "client1"
      val lease = "lease-1"
      val version = "2"
      val timestamp = toRFC3339MicroString(Instant.now())

      stubFor(
        get(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/$lease")).willReturn(
          aResponse()
            .withFixedDelay((settings.apiServerRequestTimeout * 2).toMillis.toInt) // Oh noes
            .withStatus(StatusCodes.OK.intValue)
            .withHeader("Content-Type", "application/json")
            .withBody(s"""
               |{
               |    "apiVersion": "coordination.k8s.io/v1",
               |    "kind": "Lease",
               |    "metadata": {
               |        "name": "lease-1",
               |        "namespace": "pekko-lease-tests",
               |        "resourceVersion": "$version",
               |        "uid": "c369949e-296c-11e9-9c62-16f8dd5735ba"
               |    },
               |    "spec": {
               |        "holderIdentity": "$owner",
               |        "acquireTime": $timestamp
               |    }
               |}
            """.stripMargin)))

      underTest
        .readOrCreateLeaseResource(lease)
        .failed
        .futureValue
        .getMessage shouldEqual s"Timed out reading lease $lease. Is the API server up?"
    }

    "timeout on create lease" in {
      val lease = "lease-1"

      stubFor(
        get(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/$lease"))
          .willReturn(aResponse().withStatus(StatusCodes.NotFound.intValue)))

      stubFor(
        post(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/")).willReturn(
          aResponse()
            .withFixedDelay((settings.apiServerRequestTimeout * 2).toMillis.toInt) // Oh noes
            .withStatus(StatusCodes.OK.intValue)
            .withHeader("Content-Type", "application/json")))

      underTest
        .readOrCreateLeaseResource(lease)
        .failed
        .futureValue
        .getMessage shouldEqual s"Timed out creating lease $lease. Is the API server up?"
    }

    "timeout on updating lease" in {
      val lease = "lease-1"
      val owner = "client"
      stubFor(
        put(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/$lease")).willReturn(
          aResponse()
            .withFixedDelay((settings.apiServerRequestTimeout * 2).toMillis.toInt) // Oh noes
            .withStatus(StatusCodes.OK.intValue)
            .withHeader("Content-Type", "application/json")))

      underTest.updateLeaseResource(lease, owner, "1").failed.futureValue.getMessage shouldEqual
      s"Timed out updating lease [$lease] to owner [$owner]. It is not known if the update happened. Is the API server up?"
    }

    "timeout on remove lease " in {
      val lease = "lease-1"
      stubFor(
        delete(urlEqualTo(s"/apis/coordination.k8s.io/v1/namespaces/lease/leases/$lease")).willReturn(
          aResponse()
            .withFixedDelay((settings.apiServerRequestTimeout * 2).toMillis.toInt) // Oh noes
            .withStatus(StatusCodes.OK.intValue)))

      underTest.removeLease(lease).failed.futureValue.getMessage shouldEqual
      s"Timed out removing lease [$lease]. It is not known if the remove happened. Is the API server up?"
    }
  }

}
