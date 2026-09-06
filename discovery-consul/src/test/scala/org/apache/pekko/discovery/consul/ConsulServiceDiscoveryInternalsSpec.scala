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

package org.apache.pekko.discovery.consul

import java.net.ServerSocket
import java.nio.file.Paths
import java.util.concurrent.atomic.{ AtomicInteger, AtomicReference }
import javax.net.ssl.X509TrustManager

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.{ ActorSystem, CoordinatedShutdown }
import pekko.discovery.Lookup
import org.kiwiproject.consul.Consul
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.concurrent.{ Await, ExecutionContext, Future }
import scala.util.Try

object ConsulServiceDiscoveryInternalsSpec {

  // Records how often the Consul client is built, and hangs on to the last one built.
  class RecordingConsulServiceDiscovery(system: ActorSystem) extends ConsulServiceDiscovery(system) {
    val created = new AtomicInteger(0)
    val client = new AtomicReference[Consul]()

    override private[consul] def createConsulClient(): Consul = {
      created.incrementAndGet()
      val consul = super.createConsulClient()
      client.set(consul)
      consul
    }
  }

  private def freePort(): Int = {
    val socket = new ServerSocket(0)
    try socket.getLocalPort
    finally socket.close()
  }

}

class ConsulServiceDiscoveryInternalsSpec extends AnyWordSpec with Matchers {

  import ConsulServiceDiscoveryInternalsSpec._

  private implicit val ec: ExecutionContext = ExecutionContext.global

  // nothing is listening here, so a lookup fails fast without leaving the machine
  private def config(overrides: String = ""): com.typesafe.config.Config =
    ConfigFactory
      .parseString(s"""
         |pekko.discovery.pekko-consul {
         |  consul-host = "127.0.0.1"
         |  consul-port = ${freePort()}
         |  $overrides
         |}
         |""".stripMargin)
      .withFallback(ConfigFactory.load())

  private def withSystem[T](name: String, overrides: String = "")(body: ActorSystem => T): T = {
    val system = ActorSystem(name, config(overrides))
    try body(system)
    finally Await.ready(system.terminate(), 30.seconds)
  }

  private def shutdown(system: ActorSystem): Unit =
    Await.result(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 30.seconds)

  "ConsulSettings" should {

    "reject a lookup-parallelism of 0" in withSystem("ConsulZeroParallelism", "lookup-parallelism = 0") { system =>
      // splitAt(0) returns (empty, remaining), so boundedTraverse would otherwise recurse forever
      // on an unchanged remaining/accumulator pair
      val ex = intercept[IllegalArgumentException](ConsulSettings.get(system).parallelism)
      ex.getMessage should include("lookup-parallelism must be greater than 0")
    }

    "reject a negative lookup-parallelism" in
    withSystem("ConsulNegativeParallelism", "lookup-parallelism = -1") { system =>
      intercept[IllegalArgumentException](ConsulSettings.get(system).parallelism)
    }

    "accept the default lookup-parallelism" in withSystem("ConsulDefaultParallelism") { system =>
      ConsulSettings.get(system).parallelism should ===(8)
    }

  }

  "boundedTraverse" should {

    "preserve the order of the input" in withSystem("ConsulTraverseOrder") { system =>
      val discovery = new ConsulServiceDiscovery(system)
      val items = Seq(1, 2, 3, 4, 5, 6, 7)

      val result = Await.result(discovery.boundedTraverse(items, 3)(i => Future(i * 2)), 30.seconds)

      result should ===(items.map(_ * 2))
    }

    "never run more tasks concurrently than the configured parallelism" in
    withSystem("ConsulTraverseBound") { system =>
      val discovery = new ConsulServiceDiscovery(system)
      val inFlight = new AtomicInteger(0)
      val maxInFlight = new AtomicInteger(0)

      val result = Await.result(
        discovery.boundedTraverse(Seq.range(0, 50), 4) { _ =>
          Future {
            val current = inFlight.incrementAndGet()
            maxInFlight.updateAndGet(previous => math.max(previous, current))
            Thread.sleep(5)
            inFlight.decrementAndGet()
            ()
          }
        },
        30.seconds)

      result should have size 50
      maxInFlight.get() should be <= 4
    }

    "reject a parallelism of 0 rather than looping forever" in withSystem("ConsulTraverseZero") { system =>
      val discovery = new ConsulServiceDiscovery(system)

      intercept[IllegalArgumentException](discovery.boundedTraverse(Seq(1, 2, 3), 0)(i => Future.successful(i)))
    }

  }

  "ConsulServiceDiscovery" should {

    "trust only the configured CA certificate when ca-path is set" in {
      val caPath = Paths.get(getClass.getResource("/consul-test-ca.crt").toURI).toAbsolutePath.toString
      withSystem("ConsulTls",
        s"""tls-enabled = true
                                 |    ca-path = "$caPath"""".stripMargin) { system =>
        val discovery = new ConsulServiceDiscovery(system)
        val consul = discovery.createConsulClient()
        try {
          // the Consul builder falls back to the default JVM trust manager when it is not given one,
          // which would accept every CA in the JDK trust store rather than only the one configured
          val field = consul.getClass.getDeclaredField("okHttpClient")
          field.setAccessible(true)
          val okHttpClient = field.get(consul)
          val trustManager = okHttpClient.getClass
            .getMethod("x509TrustManager")
            .invoke(okHttpClient)
            .asInstanceOf[X509TrustManager]

          val issuers = trustManager.getAcceptedIssuers
          (issuers should have).length(1)
          issuers.head.getSubjectX500Principal.getName should include("PekkoManagementConsulTestCA")
        } finally consul.destroy()
      }
    }

    "destroy the Consul client on coordinated shutdown" in withSystem("ConsulShutdownDestroy") { system =>
      val discovery = new RecordingConsulServiceDiscovery(system)

      // fails to connect, but not before the lazily created Consul client has been built
      Try(Await.ready(discovery.lookup(Lookup("my-service"), 10.seconds), 30.seconds))
      discovery.created.get() should ===(1)
      val client = discovery.client.get()
      client should not be null
      client.isDestroyed should ===(false)

      shutdown(system)

      client.isDestroyed should ===(true)
    }

    "not create a Consul client during shutdown when discovery was never used" in
    withSystem("ConsulShutdownUnused") { system =>
      val discovery = new RecordingConsulServiceDiscovery(system)

      shutdown(system)

      discovery.created.get() should ===(0)
    }

  }

}
