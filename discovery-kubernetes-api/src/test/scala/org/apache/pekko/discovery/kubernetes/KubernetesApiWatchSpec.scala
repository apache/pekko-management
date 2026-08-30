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

package org.apache.pekko.discovery.kubernetes

import com.typesafe.config.ConfigFactory
import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.discovery.kubernetes.KubernetesApiServiceDiscovery.WatchState
import pekko.discovery.kubernetes.PodList._
import pekko.stream.scaladsl.{ Framing, Sink, Source }
import pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.Await
import scala.concurrent.duration._

class KubernetesApiWatchSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem(
    "KubernetesApiWatchSpec",
    ConfigFactory.parseString("""pekko.discovery.kubernetes-api.api-poll-mode = "watch" """)
      .withFallback(ConfigFactory.load()))

  private val settings = Settings(system)
  private val discovery = new KubernetesApiServiceDiscovery(settings)

  private def pod(name: String, ip: String, resourceVersion: String): Pod =
    Pod(
      Some(PodSpec(List(Container("app", Some(List(ContainerPort(Some("management"), 8080))))))),
      Some(PodStatus(Some(ip), Some(List(ContainerStatus("app", Map("running" -> (()))))), Some("Running"))),
      Some(Metadata(deletionTimestamp = None, name = Some(name), resourceVersion = Some(resourceVersion))))

  private def cachedNames(state: WatchState): Set[String] = state.podCache.get().keySet

  "processWatchEvent" should {

    "add, update and remove pods in the cache" in {
      val state = new WatchState

      discovery.processWatchEvent(WatchEvent(Added, pod("pod-a", "10.0.0.1", "100")), state)
      cachedNames(state) should ===(Set("pod-a"))

      discovery.processWatchEvent(WatchEvent(Added, pod("pod-b", "10.0.0.2", "101")), state)
      cachedNames(state) should ===(Set("pod-a", "pod-b"))

      discovery.processWatchEvent(WatchEvent(Modified, pod("pod-a", "10.0.0.9", "102")), state)
      cachedNames(state) should ===(Set("pod-a", "pod-b"))
      state.podCache.get()("pod-a").status.flatMap(_.podIP) should ===(Some("10.0.0.9"))

      discovery.processWatchEvent(WatchEvent(Deleted, pod("pod-a", "10.0.0.9", "103")), state)
      cachedNames(state) should ===(Set("pod-b"))
    }

    "track the resource version of the last event" in {
      val state = new WatchState

      discovery.processWatchEvent(WatchEvent(Added, pod("pod-a", "10.0.0.1", "100")), state)
      state.resourceVersion.get() should ===(Some("100"))

      discovery.processWatchEvent(WatchEvent(Modified, pod("pod-a", "10.0.0.1", "104")), state)
      state.resourceVersion.get() should ===(Some("104"))
    }

    "drop the resource version on an ERROR event so that the reconnect re-lists" in {
      val state = new WatchState
      discovery.processWatchEvent(WatchEvent(Added, pod("pod-a", "10.0.0.1", "100")), state)
      state.resourceVersion.get() should ===(Some("100"))

      discovery.processWatchEvent(WatchEvent(Error, Pod(None, None, None)), state)

      state.resourceVersion.get() should ===(None)
    }

    "ignore a pod with no metadata.name rather than caching it under a shared key" in {
      val state = new WatchState
      val anonymous = Pod(None, None, Some(Metadata(deletionTimestamp = None, name = None)))

      discovery.processWatchEvent(WatchEvent(Added, anonymous), state)
      discovery.processWatchEvent(WatchEvent(Added, anonymous), state)

      state.podCache.get() shouldBe empty
    }

    "keep the caches of two label selectors separate" in {
      val serviceA = new WatchState
      val serviceB = new WatchState

      discovery.processWatchEvent(WatchEvent(Added, pod("a-1", "10.0.0.1", "100")), serviceA)
      discovery.processWatchEvent(WatchEvent(Added, pod("b-1", "10.0.1.1", "101")), serviceB)

      cachedNames(serviceA) should ===(Set("a-1"))
      cachedNames(serviceB) should ===(Set("b-1"))
    }

  }

  "the watch stream framing" should {

    // The stream splits events on newlines. Decoding each network chunk to a String separately would
    // corrupt any multi-byte UTF-8 character that straddles a chunk boundary.
    "not corrupt a multi-byte character split across two chunks" in {
      val line = """{"type":"ADDED","object":{"metadata":{"name":"pød-ü-✈"}}}"""
      val bytes = ByteString(line + "\n")
      // 'ø' encodes as two bytes; cut between them
      val firstMultiByte = bytes.indexWhere(b => (b & 0xE0) == 0xC0)
      firstMultiByte should be > 0
      val (head, tail) = bytes.splitAt(firstMultiByte + 1)

      val frames = Await.result(
        Source(List(head, tail))
          .via(Framing.delimiter(ByteString("\n"), settings.watchMaxFrameLength, allowTruncation = true))
          .map(_.utf8String)
          .runWith(Sink.seq),
        10.seconds)

      frames should ===(Seq(line))
      JsonFormat.watchEventFormat
        .read(spray.json.JsonParser(frames.head))
        .pod
        .metadata
        .flatMap(_.name) should ===(Some("pød-ü-✈"))
    }

    "split a chunk that carries several events into one frame each" in {
      val lines = List("""{"type":"ADDED","object":{"metadata":{"name":"a"}}}""",
        """{"type":"DELETED","object":{"metadata":{"name":"b"}}}""")
      val chunk = ByteString(lines.mkString("", "\n", "\n"))

      val frames = Await.result(
        Source.single(chunk)
          .via(Framing.delimiter(ByteString("\n"), settings.watchMaxFrameLength, allowTruncation = true))
          .map(_.utf8String)
          .runWith(Sink.seq),
        10.seconds)

      frames should ===(lines)
    }

  }

  override def afterAll(): Unit = Await.ready(system.terminate(), 30.seconds)

}
