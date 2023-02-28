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

package org.apache.pekko.cluster.bootstrap.discovery

import com.google.common.net.HostAndPort
import com.orbitz.consul.Consul
import com.orbitz.consul.model.catalog.ImmutableCatalogRegistration
import com.orbitz.consul.model.health.ImmutableService
import com.pszymczyk.consul.{ ConsulProcess, ConsulStarterBuilder }
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.discovery.ServiceDiscovery.ResolvedTarget
import org.apache.pekko.discovery.consul.ConsulServiceDiscovery
import org.apache.pekko.testkit.TestKitBase
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpecLike

import java.net.InetAddress
import scala.concurrent.duration._

class ConsulDiscoverySpec
    extends AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with TestKitBase
    with ScalaFutures {

  private val consul: ConsulProcess = ConsulStarterBuilder.consulStarter().withHttpPort(8500).build().start()

  "Consul Discovery" should {
    "work for defaults" in {
      val consulAgent =
        Consul.builder().withHostAndPort(HostAndPort.fromParts(consul.getAddress, consul.getHttpPort)).build()
      consulAgent
        .catalogClient()
        .register(
          ImmutableCatalogRegistration
            .builder()
            .service(
              ImmutableService
                .builder()
                .addTags(s"system:${system.name}", "pekko-management-port:1234")
                .address("127.0.0.1")
                .id("test")
                .service("test")
                .port(1235)
                .build())
            .node("testNode")
            .address("localhost")
            .build())

      val lookupService = new ConsulServiceDiscovery(system)
      val resolved = lookupService.lookup("test", 10.seconds).futureValue
      resolved.addresses should contain(
        ResolvedTarget(
          host = "127.0.0.1",
          port = Some(1234),
          address = Some(InetAddress.getByName("127.0.0.1"))))
    }
  }

  override def afterAll(): Unit = {
    super.afterAll()
    consul.close()
  }

  override implicit lazy val system: ActorSystem = ActorSystem("test")

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(30, Seconds)), interval = scaled(Span(50, Millis)))

}
