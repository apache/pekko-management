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

import scala.collection.immutable
import org.apache.pekko.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[kubernetes] object PodList {
  final case class Metadata(
      deletionTimestamp: Option[String],
      name: Option[String] = None,
      uid: Option[String] = None,
      resourceVersion: Option[String] = None)

  final case class ContainerPort(name: Option[String], containerPort: Int)

  final case class Container(name: String, ports: Option[immutable.Seq[ContainerPort]])

  final case class PodSpec(containers: immutable.Seq[Container])

  final case class ContainerStatus(name: String, state: Map[String, Unit])

  final case class PodStatus(
      podIP: Option[String],
      containerStatuses: Option[immutable.Seq[ContainerStatus]],
      phase: Option[String])

  final case class Pod(spec: Option[PodSpec], status: Option[PodStatus], metadata: Option[Metadata])

  /**
   * INTERNAL API
   *
   * Collection-level metadata. Its `resourceVersion` is the point a watch must be started from for the
   * watch to be consistent with the list it follows; an individual pod's `resourceVersion` is not.
   */
  @InternalApi private[kubernetes] final case class ListMeta(resourceVersion: Option[String] = None)

  /**
   * INTERNAL API
   *
   * Type of event from the Kubernetes Watch API.
   */
  @InternalApi private[kubernetes] sealed trait WatchEventType
  @InternalApi private[kubernetes] case object Added extends WatchEventType
  @InternalApi private[kubernetes] case object Modified extends WatchEventType
  @InternalApi private[kubernetes] case object Deleted extends WatchEventType
  @InternalApi private[kubernetes] case object Error extends WatchEventType

  /**
   * INTERNAL API
   *
   * A single event from the Kubernetes Watch API.
   */
  @InternalApi private[kubernetes] final case class WatchEvent(eventType: WatchEventType, pod: Pod)
}

/**
 * INTERNAL API
 */
@InternalApi private[kubernetes] final case class PodList(
    items: immutable.Seq[PodList.Pod],
    metadata: Option[PodList.ListMeta] = None)
