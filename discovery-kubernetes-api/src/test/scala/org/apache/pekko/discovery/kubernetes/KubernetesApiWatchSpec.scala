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
import pekko.Done
import pekko.actor.ActorSystem
import pekko.discovery.Lookup
import pekko.discovery.kubernetes.KubernetesApiServiceDiscovery.{ KubernetesSetup, WatchState }
import pekko.discovery.kubernetes.PodList._
import pekko.http.scaladsl.{ ConnectionContext, Http }
import pekko.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes }
import pekko.http.scaladsl.settings.ConnectionPoolSettings
import pekko.stream.KillSwitches
import pekko.stream.scaladsl.{ Framing, Keep, Sink, Source }
import pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import javax.net.ssl.SSLContext

import scala.concurrent.{ Await, Future, TimeoutException }
import scala.concurrent.duration._

class KubernetesApiWatchSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {

  private implicit val system: ActorSystem = ActorSystem(
    "KubernetesApiWatchSpec",
    ConfigFactory.parseString("""
      pekko.discovery.kubernetes-api {
        api-poll-mode = "watch"
        watch-idle-timeout = 1 minute
        # never set, so that no request to an API server can be formed
        api-service-host-env-name = "PEKKO_KUBERNETES_API_WATCH_SPEC_NO_SUCH_HOST"
      }
      """)
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

    "advance the resource version on a BOOKMARK event without touching the cache" in {
      val state = new WatchState
      discovery.processWatchEvent(WatchEvent(Added, pod("pod-a", "10.0.0.1", "100")), state)

      discovery.processWatchEvent(
        WatchEvent(Bookmark, Pod(None, None, Some(Metadata(deletionTimestamp = None, resourceVersion = Some("250"))))),
        state)

      state.resourceVersion.get() should ===(Some("250"))
      cachedNames(state) should ===(Set("pod-a"))
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

  "resolveFromWatch" should {

    "wait for the initial list instead of answering from an empty cache" in {
      val state = new WatchState

      val ex = intercept[TimeoutException] {
        Await.result(discovery.resolveFromWatch(state, Lookup("svc"), "app=svc", "default", 200.millis), 10.seconds)
      }
      ex.getMessage should include("waiting for the initial pod list")
    }

    "answer from the cache once the initial list has completed" in {
      val state = new WatchState
      val pending = discovery.resolveFromWatch(state, Lookup("svc"), "app=svc", "default", 10.seconds)
      discovery.processWatchEvent(WatchEvent(Added, pod("pod-a", "10.0.0.1", "100")), state)
      pending.isCompleted shouldBe false

      state.synced.trySuccess(Done)

      Await.result(pending, 10.seconds).addresses.map(_.host) should ===(Seq("10-0-0-1.default.pod.cluster.local"))
    }

  }

  "registerWatchStream" should {

    "shut down a stream that is only registered after the watches have been stopped" in {
      val stopped = new KubernetesApiServiceDiscovery(settings)
      stopped.stopWatches()

      val (killSwitch, done) =
        Source.maybe[ByteString].viaMat(KillSwitches.single)(Keep.right).toMat(Sink.ignore)(Keep.both).run()
      stopped.registerWatchStream(new WatchState, killSwitch)

      Await.result(done, 10.seconds) should ===(Done)
    }

  }

  "startWatch" should {

    // the caller schedules the retry, so it has to be told that the watch did not start
    "fail the returned future when the watch cannot be started" in {
      val setup = KubernetesSetup("default", "token", ConnectionContext.httpsClient(SSLContext.getDefault))

      val failure = Await.result(discovery.startWatch(setup, "app=svc", new WatchState).failed, 10.seconds)

      failure shouldBe a[NoSuchElementException]
      failure.getMessage should include("Unable to form request")
    }

  }

  "the idle watch release" should {

    def runningStream() =
      Source.maybe[ByteString].viaMat(KillSwitches.single)(Keep.right).toMat(Sink.ignore)(Keep.both).run()

    val idleTimeout = settings.watchIdleTimeout.asInstanceOf[FiniteDuration]

    "stop and forget a watch that is no longer looked up, and start a fresh one on a later lookup" in {
      val lifecycle = new KubernetesApiServiceDiscovery(settings)
      val state = lifecycle.watchStateFor("default", "app=idle")
      state.startOnce() shouldBe true
      discovery.processWatchEvent(WatchEvent(Added, pod("pod-a", "10.0.0.1", "100")), state)
      val (killSwitch, done) = runningStream()
      lifecycle.registerWatchStream(state, killSwitch)

      lifecycle.releaseIdleWatches(System.nanoTime() + idleTimeout.toNanos)

      Await.result(done, 10.seconds) should ===(Done)
      state.isStopped shouldBe true

      val fresh = lifecycle.watchStateFor("default", "app=idle")
      fresh should not be theSameInstanceAs(state)
      fresh.isStopped shouldBe false
      fresh.podCache.get() shouldBe empty
      // the fresh state has not been started, so the lookup that created it starts a new watch
      fresh.startOnce() shouldBe true
      lifecycle.stopWatches()
    }

    "keep a watch that is still being looked up" in {
      val lifecycle = new KubernetesApiServiceDiscovery(settings)
      val idle = lifecycle.watchStateFor("default", "app=idle")
      val active = lifecycle.watchStateFor("default", "app=active")
      val (killSwitch, done) = runningStream()
      lifecycle.registerWatchStream(active, killSwitch)

      // half the timeout later `app=active` is looked up again, `app=idle` is not
      val later = System.nanoTime() + idleTimeout.toNanos / 2
      lifecycle.releaseIdleWatches(later)
      (lifecycle.watchStateFor("default", "app=active") should be).theSameInstanceAs(active)

      lifecycle.releaseIdleWatches(System.nanoTime() + idleTimeout.toNanos - 1.second.toNanos)
      (lifecycle.watchStateFor("default", "app=active") should be).theSameInstanceAs(active)
      active.isStopped shouldBe false
      done.isCompleted shouldBe false
      idle.isStopped shouldBe false

      lifecycle.releaseIdleWatches(System.nanoTime() + idleTimeout.toNanos)
      idle.isStopped shouldBe true
      lifecycle.stopWatches()
    }

    "shut down a stream that is only registered after its watch was released" in {
      val lifecycle = new KubernetesApiServiceDiscovery(settings)
      val state = lifecycle.watchStateFor("default", "app=idle")
      lifecycle.releaseIdleWatches(System.nanoTime() + idleTimeout.toNanos)

      val (killSwitch, done) = runningStream()
      lifecycle.registerWatchStream(state, killSwitch)

      Await.result(done, 10.seconds) should ===(Done)
      lifecycle.stopWatches()
    }

  }

  "watchSingleRequest" should {

    // A watch holds its connection for as long as it runs. Through the host connection pool, which has
    // max-connections per host, the watches beyond that number would never get a response.
    "run more concurrent watches than the host connection pool has connections" in {
      import system.dispatcher
      // a response that, like a watch, sends an event and then stays open
      val binding = Await.result(
        Http().newServerAt("127.0.0.1", 0).bindSync { _ =>
          HttpResponse(
            StatusCodes.OK,
            entity = HttpEntity(
              ContentTypes.`application/json`,
              Source.single(ByteString("{}\n")).concat(Source.maybe[ByteString])))
        },
        10.seconds)
      try {
        val watchCount = ConnectionPoolSettings(system).maxConnections + 2
        val request = HttpRequest(uri = s"http://127.0.0.1:${binding.localAddress.getPort}/api/v1/pods?watch=true")

        val streams = (1 to watchCount).map { _ =>
          discovery.watchSingleRequest(request, ConnectionContext.noEncryption()).map { response =>
            response.status should ===(StatusCodes.OK)
            response.entity.dataBytes
              .viaMat(KillSwitches.single)(Keep.right)
              .toMat(Sink.queue[ByteString]())(Keep.both)
              .run()
          }
        }
        val running = Await.result(Future.sequence(streams), 10.seconds)
        // every watch is being served at the same time
        val firstEvents = Await.result(Future.sequence(running.map(_._2.pull())), 10.seconds)
        firstEvents should ===(Vector.fill(watchCount)(Some(ByteString("{}\n"))))

        running.foreach(_._1.shutdown())
      } finally Await.result(binding.terminate(5.seconds), 10.seconds)
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
