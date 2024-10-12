/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.pekko.discovery.eureka

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.discovery.ServiceDiscovery.ResolvedTarget
import pekko.discovery.eureka.{ EurekaServiceDiscovery, JsonFormat }
import pekko.testkit.TestKitBase
import com.typesafe.config.ConfigFactory
import org.kiwiproject.eureka.EmbeddedEurekaServer
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._

import java.net.InetAddress
import scala.concurrent.{ ExecutionContext, Future }
import scala.concurrent.duration.DurationInt
import scala.io.Source
import scala.util.Try

class EurekaServiceDiscoverySpec
    extends AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with TestKitBase
    with ScalaFutures {

  private val embeddedEurekaServer = new EmbeddedEurekaServer()
  embeddedEurekaServer.start()
  private val testConfig = ConfigFactory.parseString(
    s"""
       |pekko.discovery.eureka {
       |  eureka-port = ${embeddedEurekaServer.getEurekaPort()}
       |}
       |""".stripMargin).withFallback(ConfigFactory.load())

  override implicit lazy val system: ActorSystem = ActorSystem("test", testConfig)
  implicit val ec: ExecutionContext = system.dispatcher

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = scaled(Span(30, Seconds)), interval = scaled(Span(50, Millis)))

  override def afterAll(): Unit = {
    super.afterAll()
    embeddedEurekaServer.stop()
  }

  "Eureka Discovery" should {
    "work for defaults" in {
      val application = "BANK-ACCOUNT"
      val host = "acme.com"
      embeddedEurekaServer.getRegistry().registerApplication(
        application, host, "https://acme-vip.com", "UP")

      val lookupService = new EurekaServiceDiscovery()
      val resolved = lookupService.lookup(application, 10.seconds).futureValue
      resolved.addresses should contain(
        ResolvedTarget(
          host = host,
          port = Some(7001),
          address = None))
    }

    "pick status and group then resolved targets" in {
      val data = resourceAsString("response.json")

      val response = JsonFormat.rootFormat.read(data.parseJson)
      val instances = response.application.instance

      val resolved = EurekaServiceDiscovery.targets(instances)

      resolved shouldBe List(
        ResolvedTarget(
          host = "localhost",
          port = Some(8558),
          address = Try(InetAddress.getByName("127.0.0.1")).toOption),
        ResolvedTarget(
          host = "10.0.0.1",
          port = Some(8558),
          address = Try(InetAddress.getByName("10.0.0.1")).toOption),
        ResolvedTarget(
          host = "192.168.1.1",
          port = Some(8558),
          address = Try(InetAddress.getByName("192.168.1.1")).toOption)
      )

      val result = for {
        picked <- EurekaServiceDiscovery.pick(instances)
        resolved <- Future.successful(EurekaServiceDiscovery.targets(picked))
      } yield resolved

      result.futureValue should contain(
        ResolvedTarget(
          host = "localhost",
          port = Some(8558),
          address = Try(InetAddress.getByName("127.0.0.1")).toOption))
    }

  }

  private def resourceAsString(name: String): String =
    Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(name)).mkString
}
