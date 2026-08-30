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

package org.apache.pekko.discovery.kubernetes

import spray.json._
import scala.io.Source

import PodList._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class JsonFormatSpec extends AnyWordSpec with Matchers {
  "JsonFormat" should {
    val data = resourceAsString("pods.json")

    "work" in {
      JsonFormat.podListFormat.read(data.parseJson) shouldBe PodList(
        List(
          Pod(
            Some(PodSpec(List(Container(
              "pekko-cluster-tooling-example",
              Some(List(
                ContainerPort(Some("pekko-remote"), 10000),
                ContainerPort(Some("management"), 10001),
                ContainerPort(Some("http"), 10002))))))),
            Some(
              PodStatus(
                Some("172.17.0.4"),
                Some(List(ContainerStatus("pekko-cluster-tooling-example", Map(("running", ()))))),
                Some("Running"))),
            Some(Metadata(
              deletionTimestamp = None,
              name = Some("pekko-cluster-tooling-example-v0-1-0-7f854bcc78-dvm9q"),
              uid = Some("5fe7a41b-da9a-11e7-b064-0800270d668b"),
              resourceVersion = Some("6523")))),
          Pod(
            Some(PodSpec(List(Container(
              "pekko-cluster-tooling-example",
              Some(List(
                ContainerPort(Some("pekko-remote"), 10000),
                ContainerPort(Some("management"), 10001),
                ContainerPort(Some("http"), 10002))))))),
            Some(
              PodStatus(
                Some("172.17.0.6"),
                Some(List(ContainerStatus("pekko-cluster-tooling-example", Map(("running", ()))))),
                Some("Running"))),
            Some(Metadata(
              deletionTimestamp = None,
              name = Some("pekko-cluster-tooling-example-v0-1-0-7f854bcc78-m8dqb"),
              uid = Some("5fe22476-da9a-11e7-b064-0800270d668b"),
              resourceVersion = Some("6520")))),
          Pod(
            Some(PodSpec(List(Container(
              "pekko-cluster-tooling-example",
              Some(List(
                ContainerPort(Some("pekko-remote"), 10000),
                ContainerPort(Some("management"), 10001),
                ContainerPort(Some("http"), 10002))))))),
            Some(
              PodStatus(
                Some("172.17.0.7"),
                Some(List(ContainerStatus("pekko-cluster-tooling-example", Map(("running", ()))))),
                Some("Running"))),
            Some(Metadata(
              deletionTimestamp = Some("2017-12-06T16:30:22Z"),
              name = Some("pekko-cluster-tooling-example-v0-1-0-7f854bcc78-xncvj"),
              uid = Some("5fe6b0c7-da9a-11e7-b064-0800270d668b"),
              resourceVersion = Some("6593")))),
          Pod(
            Some(PodSpec(
              List(Container("pekko-cluster-tooling-example", Some(List(ContainerPort(Some("management"), 10001))))))),
            Some(
              PodStatus(
                Some("172.17.0.47"),
                Some(List(ContainerStatus("pekko-cluster-tooling-example", Map(("terminated", ()))))),
                Some("Succeeded"))),
            Some(Metadata(
              deletionTimestamp = None,
              name = Some("pekko-cluster-tooling-example-job-mt4qt"),
              uid = Some("01b49788-4f17-11e9-b630-0262c2d3ba30"),
              resourceVersion = Some("7406832"))))),
        // the list's own resourceVersion, which is what a watch has to resume from. Note it differs
        // from every item's resourceVersion, including the last one.
        Some(ListMeta(Some("16042"))))
    }

    "parse watch events" in {
      val watchData = resourceAsString("watch-events.json")
      val lines = watchData.split("\n").filter(_.nonEmpty)
      val events = lines.map(line => JsonFormat.watchEventFormat.read(line.parseJson))

      events should have size 3
      events(0).eventType shouldBe Added
      events(0).pod.metadata.flatMap(_.name) shouldBe Some("test-pod-1")
      events(0).pod.status.flatMap(_.podIP) shouldBe Some("10.0.0.1")
      events(1).eventType shouldBe Modified
      events(1).pod.metadata.flatMap(_.name) shouldBe Some("test-pod-1")
      events(2).eventType shouldBe Deleted
      events(2).pod.metadata.flatMap(_.name) shouldBe Some("test-pod-1")
    }
  }

  private def resourceAsString(name: String): String =
    Source.fromInputStream(getClass.getClassLoader.getResourceAsStream(name)).mkString
}
