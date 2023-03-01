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

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.cluster.{ Cluster, MemberStatus }
import pekko.http.scaladsl.Http
import pekko.http.scaladsl.model.{ HttpRequest, StatusCode, StatusCodes }
import pekko.management.cluster.bootstrap.ClusterBootstrap
import pekko.management.scaladsl.PekkoManagement
import pekko.testkit.{ SocketUtil, TestKit }
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{ Eventually, ScalaFutures }
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpec

import scala.concurrent.duration._

object LocalBootstrapTest {
  val managementPorts = SocketUtil.temporaryServerAddresses(3, "127.0.0.1").map(_.getPort)
  // See src/main/resources/application.conf for bootstrap settings which are used in docs so needs tested
  val config = ConfigFactory.parseString(s"""
      pekko.remote.artery {
        enabled = on
        transport = tcp
        canonical {
          hostname = localhost
          port = 0
        }
      }
      pekko.management {
        http.hostname = "127.0.0.1"
        cluster.bootstrap.contact-point-discovery.port-name = "management"
      }
      pekko.discovery {
        config.services = {
          local-cluster = {
            endpoints = [
              {
                host = "127.0.0.1"
                port = ${managementPorts(0)}
              },
              {
                host = "127.0.0.1"
                port = ${managementPorts(1)}
              },
              {
                host = "127.0.0.1"
                port = ${managementPorts(2)}
              }
            ]
          }
        }
      }
    """).withFallback(ConfigFactory.load())
}

class LocalBootstrapTest extends AnyWordSpec with ScalaFutures with Matchers with Eventually with BeforeAndAfterAll {
  import LocalBootstrapTest._

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(
      timeout = scaled(Span(20, Seconds)),
      interval = scaled(Span(500, Millis)))

  private var systems = Seq.empty[ActorSystem]

  def newSystem(managementPort: Int): ActorSystem =
    ActorSystem(
      "local-cluster",
      ConfigFactory.parseString(s"""
      pekko.management.http.port = $managementPort
      pekko.coordinated-shutdown.exit-jvm = off
       """.stripMargin).withFallback(config))

  override def afterAll(): Unit = {
    // TODO: shutdown Pekko HTTP connection pools. Requires Pekko HTTP 10.2
    systems.reverse.foreach { sys =>
      TestKit.shutdownActorSystem(sys, 3.seconds)
    }
    super.afterAll()
  }

  def readyStatusCode(port: Int)(implicit system: ActorSystem): StatusCode =
    healthCheckStatus(port, "health/ready")
  def aliveStatusCode(port: Int)(implicit system: ActorSystem): StatusCode =
    healthCheckStatus(port, "health/alive")

  def healthCheckStatus(port: Int, path: String)(
      implicit system: ActorSystem): StatusCode = {
    Http().singleRequest(HttpRequest(uri = s"http://localhost:$port/$path")).futureValue.status
  }

  "Cluster bootstrap with health checks" should {
    systems = managementPorts.map(newSystem)
    val clusters = systems.map(Cluster.apply)
    systems.foreach(PekkoManagement(_).start())
    // for http client
    implicit val system = systems(0)

    "not be ready initially" in {
      eventually {
        managementPorts.foreach { port =>
          readyStatusCode(port) shouldEqual StatusCodes.InternalServerError
        }
      }
    }

    "be alive initially" in {
      eventually {
        managementPorts.foreach { port =>
          aliveStatusCode(port) shouldEqual StatusCodes.OK
        }
      }
    }
    "form a cluster" in {
      systems.foreach(ClusterBootstrap(_).start())
      eventually {
        clusters.foreach(c =>
          c.state.members.toList.map(_.status) shouldEqual List(
            MemberStatus.Up,
            MemberStatus.Up,
            MemberStatus.Up))
      }
    }

    "be ready after formation" in {
      eventually {
        managementPorts.foreach { port =>
          readyStatusCode(port) shouldEqual StatusCodes.OK
        }
      }
    }
  }
}
