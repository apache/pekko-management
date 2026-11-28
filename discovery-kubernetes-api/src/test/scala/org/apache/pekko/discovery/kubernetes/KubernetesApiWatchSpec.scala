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
import pekko.discovery.kubernetes.KubernetesApiServiceDiscovery.{
  KubernetesApiException,
  KubernetesSetup,
  WatchEventNotAppliedException,
  WatchState
}
import pekko.discovery.kubernetes.PodList._
import pekko.http.scaladsl.{ ConnectionContext, Http }
import pekko.http.scaladsl.model.{ ContentTypes, HttpEntity, HttpRequest, HttpResponse, StatusCodes }
import pekko.http.scaladsl.settings.ConnectionPoolSettings
import pekko.stream.KillSwitches
import pekko.stream.scaladsl.{ Framing, Keep, Sink, Source }
import pekko.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{ Millis, Seconds, Span }
import org.scalatest.wordspec.AnyWordSpecLike

import javax.net.ssl.SSLContext

import scala.concurrent.{ Await, Future, Promise, TimeoutException }
import scala.concurrent.duration._

class KubernetesApiWatchSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll with Eventually {

  private implicit val eventuallyPatience: PatienceConfig = PatienceConfig(Span(10, Seconds), Span(20, Millis))

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

  /**
   * A response entity, along with a future that completes once its stream has ended, be that because it
   * was read to the end, drained or cancelled. Until then the response holds on to its connection.
   */
  private def trackedEntity(data: Source[ByteString, Any]): (HttpEntity.Chunked, Future[Done]) = {
    val released = Promise[Done]()
    val tracked = data.watchTermination { (_, done) =>
      done.onComplete(_ => released.trySuccess(Done))(system.dispatcher)
    }
    (HttpEntity.Chunked.fromData(ContentTypes.`application/json`, tracked), released.future)
  }

  private def watchEventLine(eventType: String, name: String, resourceVersion: String): String =
    s"""{"type":"$eventType","object":{"metadata":{"name":"$name","resourceVersion":"$resourceVersion"}}}""" + "\n"

  private val setup = KubernetesSetup("default", "token", ConnectionContext.httpsClient(SSLContext.getDefault))

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

    "reject an event for a pod with no metadata.name, leaving the cache and the resource version alone" in {
      val state = new WatchState
      discovery.processWatchEvent(WatchEvent(Added, pod("pod-a", "10.0.0.1", "100")), state)
      val anonymous =
        Pod(None, None, Some(Metadata(deletionTimestamp = None, name = None, resourceVersion = Some("101"))))

      intercept[WatchEventNotAppliedException] {
        discovery.processWatchEvent(WatchEvent(Added, anonymous), state)
      }

      // not cached under a shared key, and the resume point has not moved past the event
      cachedNames(state) should ===(Set("pod-a"))
      state.resourceVersion.get() should ===(Some("100"))
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

  "stopWatches" should {

    "fail a later lookup promptly, without creating a new watch" in {
      val stopped = new KubernetesApiServiceDiscovery(settings)
      stopped.stopWatches()

      // far longer than the test waits: the lookup has to fail by itself, not by timing out
      val failure = Await.result(stopped.lookup(Lookup("svc"), 1.hour).failed, 10.seconds)

      failure shouldBe a[KubernetesApiServiceDiscovery.KubernetesApiException]
      failure.getMessage should include("the watches have been stopped")
      stopped.watchStateFor("default", "app=svc") should ===(None)
      stopped.watchCount should ===(0)
    }

    "fail a later lookup for a selector that was being watched" in {
      val stopped = new KubernetesApiServiceDiscovery(settings)
      val state = stopped.watchStateFor("default", "app=svc").get
      state.synced.trySuccess(Done)
      stopped.stopWatches()

      stopped.watchStateFor("default", "app=svc") should ===(None)
      state.isStopped shouldBe true
    }

    "fail the lookups that are still waiting for the initial list" in {
      val stopped = new KubernetesApiServiceDiscovery(settings)
      val state = stopped.watchStateFor("default", "app=svc").get
      val pending = stopped.resolveFromWatch(state, Lookup("svc"), "app=svc", "default", 1.hour)
      pending.isCompleted shouldBe false

      stopped.stopWatches()

      val failure = Await.result(pending.failed, 10.seconds)
      failure shouldBe a[KubernetesApiServiceDiscovery.KubernetesApiException]
      failure.getMessage should include("stopped before its initial pod list completed")
    }

  }

  "startWatch" should {

    // the caller schedules the retry, so it has to be told that the watch did not start
    "fail the returned future when the watch cannot be started" in {
      val failure = Await.result(discovery.startWatch(setup, "app=svc", new WatchState).failed, 10.seconds)

      failure shouldBe a[NoSuchElementException]
      failure.getMessage should include("Unable to form request")
    }

  }

  "processWatchStream" should {

    def watchResponse(first: String, rest: Future[ByteString]): HttpResponse =
      HttpResponse(
        StatusCodes.OK,
        entity = HttpEntity(
          ContentTypes.`application/json`,
          Source.single(ByteString(first)).concat(Source.future(rest))))

    // The cache has missed a change, so neither carrying on nor resuming from a later version is safe.
    "resync from a fresh list after an event that cannot be applied, without applying the events after it" in {
      val state = new WatchState
      val rest = Promise[ByteString]()
      try {
        discovery.processWatchStream(watchResponse(watchEventLine("ADDED", "pod-a", "100"), rest.future), setup,
          "app=svc", state)
        eventually(state.resourceVersion.get() should ===(Some("100")))

        rest.success(ByteString("this is not a watch event\n" + watchEventLine("ADDED", "pod-b", "102")))

        // the resume point is dropped, which makes the restart go back through a full list
        eventually(state.resourceVersion.get() should ===(None))
        eventually(state.killSwitch.get() should ===(None))
        cachedNames(state) should ===(Set("pod-a"))
        state.resourceVersion.get() should ===(None)
      } finally state.stop()
    }

    "resync from a fresh list after an event for a pod without a name" in {
      val state = new WatchState
      val rest = Promise[ByteString]()
      try {
        discovery.processWatchStream(watchResponse(watchEventLine("ADDED", "pod-a", "100"), rest.future), setup,
          "app=svc", state)
        eventually(state.resourceVersion.get() should ===(Some("100")))

        rest.success(ByteString(
          """{"type":"DELETED","object":{"metadata":{"resourceVersion":"101"}}}""" + "\n" +
          watchEventLine("ADDED", "pod-b", "102")))

        eventually(state.resourceVersion.get() should ===(None))
        eventually(state.killSwitch.get() should ===(None))
        cachedNames(state) should ===(Set("pod-a"))
        state.resourceVersion.get() should ===(None)
      } finally state.stop()
    }

    // A dropped connection loses no event that was received, so the watch resumes where it was.
    "keep the resume point when the connection fails" in {
      val state = new WatchState
      val rest = Promise[ByteString]()
      try {
        discovery.processWatchStream(watchResponse(watchEventLine("ADDED", "pod-a", "100"), rest.future), setup,
          "app=svc", state)
        eventually {
          state.resourceVersion.get() should ===(Some("100"))
          state.killSwitch.get() should not be empty
        }

        rest.failure(new RuntimeException("connection reset"))

        eventually(state.killSwitch.get() should ===(None))
        state.resourceVersion.get() should ===(Some("100"))
        cachedNames(state) should ===(Set("pod-a"))
      } finally state.stop()
    }

  }

  "readPodList" should {

    val oneKilobyte = ByteString("x" * 1024)

    // 1 KiB, and no time at all to wait for a list that does not arrive
    def boundedDiscovery(listTimeout: String = "1 hour") =
      new KubernetesApiServiceDiscovery(
        new Settings(
          ConfigFactory
            .parseString(s"""
              watch-max-list-length = 1 KiB
              watch-list-timeout = $listTimeout
              """)
            .withFallback(system.settings.config.getConfig("pekko.discovery.kubernetes-api"))))

    "read a pod list that is within the limits" in {
      val (entity, released) = trackedEntity(Source.single(ByteString(
        """{"metadata":{"resourceVersion":"500"},"items":[{"metadata":{"name":"pod-a","resourceVersion":"499"}}]}""")))

      val podList =
        Await.result(boundedDiscovery().readPodList(HttpResponse(StatusCodes.OK, entity = entity), 1.hour.fromNow),
          10.seconds)

      podList.metadata.flatMap(_.resourceVersion) should ===(Some("500"))
      podList.items.flatMap(_.metadata.flatMap(_.name)) should ===(List("pod-a"))
      Await.result(released, 10.seconds) should ===(Done)
    }

    "release an unsuccessful response instead of buffering it" in {
      // the body is still being sent, so buffering it first would not have failed yet
      val restOfBody = Promise[ByteString]()
      val (entity, released) =
        trackedEntity(Source.single(oneKilobyte).concat(Source.future(restOfBody.future)))

      val failure = Await.result(
        discovery.readPodList(HttpResponse(StatusCodes.Forbidden, entity = entity), 1.hour.fromNow).failed,
        10.seconds)

      failure shouldBe a[KubernetesApiException]
      failure.getMessage should include("403")
      // the body is drained as it arrives, after which the response no longer holds its connection
      restOfBody.success(oneKilobyte)
      Await.result(released, 10.seconds) should ===(Done)
    }

    "fail and release the response when the list is larger than watch-max-list-length" in {
      val (entity, released) = trackedEntity(Source.repeat(oneKilobyte))

      val failure = Await.result(
        boundedDiscovery().readPodList(HttpResponse(StatusCodes.OK, entity = entity), 1.hour.fromNow).failed,
        10.seconds)

      failure shouldBe a[KubernetesApiException]
      failure.getMessage should include("watch-max-list-length")
      Await.result(released, 10.seconds) should ===(Done)
    }

    "fail and release the response when the list is not read by the deadline" in {
      // sends the start of a list and then nothing more
      val (entity, released) =
        trackedEntity(Source.single(ByteString("""{"items":[""")).concat(Source.maybe[ByteString]))

      val failure = Await.result(
        boundedDiscovery("200 ms")
          .readPodList(HttpResponse(StatusCodes.OK, entity = entity), 200.millis.fromNow)
          .failed,
        10.seconds)

      failure shouldBe a[TimeoutException]
      failure.getMessage should include("was not read within")
      Await.result(released, 10.seconds) should ===(Done)
    }

  }

  "listResponseWithin" should {

    "pass on a response that arrives before the deadline" in {
      val response = HttpResponse(StatusCodes.OK)
      val passedOn =
        Await.result(discovery.listResponseWithin(Future.successful(response), 1.hour.fromNow), 10.seconds)
      (passedOn eq response) shouldBe true
    }

    "fail at the deadline, and release a response that arrives after it" in {
      val late = Promise[HttpResponse]()

      val failure =
        Await.result(discovery.listResponseWithin(late.future, 200.millis.fromNow).failed, 10.seconds)
      failure shouldBe a[TimeoutException]
      failure.getMessage should include("pod list request timed out")

      val (entity, released) = trackedEntity(Source.repeat(ByteString("x")).take(10))
      late.success(HttpResponse(StatusCodes.OK, entity = entity))
      Await.result(released, 10.seconds) should ===(Done)
    }

  }

  "the idle watch release" should {

    def runningStream() =
      Source.maybe[ByteString].viaMat(KillSwitches.single)(Keep.right).toMat(Sink.ignore)(Keep.both).run()

    val idleTimeout = settings.watchIdleTimeout.asInstanceOf[FiniteDuration]

    "stop and forget a watch that is no longer looked up, and start a fresh one on a later lookup" in {
      val lifecycle = new KubernetesApiServiceDiscovery(settings)
      val state = lifecycle.watchStateFor("default", "app=idle").get
      state.startOnce() shouldBe true
      discovery.processWatchEvent(WatchEvent(Added, pod("pod-a", "10.0.0.1", "100")), state)
      val (killSwitch, done) = runningStream()
      lifecycle.registerWatchStream(state, killSwitch)

      lifecycle.releaseIdleWatches(System.nanoTime() + idleTimeout.toNanos)

      Await.result(done, 10.seconds) should ===(Done)
      state.isStopped shouldBe true

      val fresh = lifecycle.watchStateFor("default", "app=idle").get
      fresh should not be theSameInstanceAs(state)
      fresh.isStopped shouldBe false
      fresh.podCache.get() shouldBe empty
      // the fresh state has not been started, so the lookup that created it starts a new watch
      fresh.startOnce() shouldBe true
      lifecycle.stopWatches()
    }

    "keep a watch that is still being looked up" in {
      val lifecycle = new KubernetesApiServiceDiscovery(settings)
      val idle = lifecycle.watchStateFor("default", "app=idle").get
      val active = lifecycle.watchStateFor("default", "app=active").get
      val (killSwitch, done) = runningStream()
      lifecycle.registerWatchStream(active, killSwitch)

      // half the timeout later `app=active` is looked up again, `app=idle` is not
      val later = System.nanoTime() + idleTimeout.toNanos / 2
      lifecycle.releaseIdleWatches(later)
      (lifecycle.watchStateFor("default", "app=active").get should be).theSameInstanceAs(active)

      lifecycle.releaseIdleWatches(System.nanoTime() + idleTimeout.toNanos - 1.second.toNanos)
      (lifecycle.watchStateFor("default", "app=active").get should be).theSameInstanceAs(active)
      active.isStopped shouldBe false
      done.isCompleted shouldBe false
      idle.isStopped shouldBe false

      lifecycle.releaseIdleWatches(System.nanoTime() + idleTimeout.toNanos)
      idle.isStopped shouldBe true
      lifecycle.stopWatches()
    }

    "shut down a stream that is only registered after its watch was released" in {
      val lifecycle = new KubernetesApiServiceDiscovery(settings)
      val state = lifecycle.watchStateFor("default", "app=idle").get
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
