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

package org.apache.pekko.discovery.awsapi.ecs

import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicInteger }

import org.apache.pekko
import pekko.actor.{ ActorSystem, CoordinatedShutdown }
import pekko.discovery.Lookup
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import software.amazon.awssdk.services.ecs.EcsAsyncClient
import software.amazon.awssdk.services.ecs.model.{
  DescribeTasksRequest,
  DescribeTasksResponse,
  ListTasksRequest,
  ListTasksResponse
}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.Try

object AsyncEcsClientShutdownSpec {

  class StubEcsAsyncClient extends EcsAsyncClient {
    val closed = new AtomicBoolean(false)

    override def serviceName(): String = EcsAsyncClient.SERVICE_NAME

    override def close(): Unit = closed.set(true)

    override def listTasks(request: ListTasksRequest): CompletableFuture[ListTasksResponse] =
      CompletableFuture.completedFuture(ListTasksResponse.builder().build())

    override def describeTasks(request: DescribeTasksRequest): CompletableFuture[DescribeTasksResponse] =
      CompletableFuture.completedFuture(DescribeTasksResponse.builder().build())
  }

}

class AsyncEcsClientShutdownSpec extends AnyWordSpec with Matchers {

  import AsyncEcsClientShutdownSpec._

  private def withSystem[T](name: String)(body: ActorSystem => T): T = {
    val system = ActorSystem(name)
    try body(system)
    finally Await.ready(system.terminate(), 30.seconds)
  }

  private def shutdown(system: ActorSystem): Unit =
    Await.result(CoordinatedShutdown(system).run(CoordinatedShutdown.UnknownReason), 30.seconds)

  "AsyncEcsServiceDiscovery" should {

    "close the ECS client on coordinated shutdown" in withSystem("AsyncEcsServiceDiscoverySpec") { system =>
      val stub = new StubEcsAsyncClient
      val discovery = new AsyncEcsServiceDiscovery(system) {
        override private[ecs] def createEcsClient(): EcsAsyncClient = stub
      }

      Await.result(discovery.lookup(Lookup("my-service"), 10.seconds), 30.seconds).addresses should be(empty)
      stub.closed.get() should ===(false)

      shutdown(system)

      stub.closed.get() should ===(true)
    }

    "not create an ECS client during shutdown when discovery was never used" in
      withSystem("AsyncEcsServiceDiscoveryUnusedSpec") { system =>
        val created = new AtomicInteger(0)
        new AsyncEcsServiceDiscovery(system) {
          override private[ecs] def createEcsClient(): EcsAsyncClient = {
            created.incrementAndGet()
            new StubEcsAsyncClient
          }
        }

        shutdown(system)

        created.get() should ===(0)
      }

  }

  "AsyncEcsTaskSetDiscovery" should {

    "close the ECS client on coordinated shutdown" in withSystem("AsyncEcsTaskSetDiscoverySpec") { system =>
      val stub = new StubEcsAsyncClient
      val discovery = new AsyncEcsTaskSetDiscovery(system) {
        override private[ecs] def createEcsClient(): EcsAsyncClient = stub
      }

      // requires the ECS_CONTAINER_METADATA_URI environment variable, so it is expected to fail here,
      // but not before the (lazily created) ECS client has been built
      Try(Await.ready(discovery.lookup(Lookup("my-service"), 10.seconds), 30.seconds))
      stub.closed.get() should ===(false)

      shutdown(system)

      stub.closed.get() should ===(true)
    }

    "not create an ECS client during shutdown when discovery was never used" in
      withSystem("AsyncEcsTaskSetDiscoveryUnusedSpec") { system =>
        val created = new AtomicInteger(0)
        new AsyncEcsTaskSetDiscovery(system) {
          override private[ecs] def createEcsClient(): EcsAsyncClient = {
            created.incrementAndGet()
            new StubEcsAsyncClient
          }
        }

        shutdown(system)

        created.get() should ===(0)
      }

  }

}
