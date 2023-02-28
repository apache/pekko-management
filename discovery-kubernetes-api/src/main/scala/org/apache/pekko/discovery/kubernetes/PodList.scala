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
  final case class Metadata(deletionTimestamp: Option[String])

  final case class ContainerPort(name: Option[String], containerPort: Int)

  final case class Container(name: String, ports: Option[immutable.Seq[ContainerPort]])

  final case class PodSpec(containers: immutable.Seq[Container])

  final case class ContainerStatus(name: String, state: Map[String, Unit])

  final case class PodStatus(
      podIP: Option[String],
      containerStatuses: Option[immutable.Seq[ContainerStatus]],
      phase: Option[String])

  final case class Pod(spec: Option[PodSpec], status: Option[PodStatus], metadata: Option[Metadata])
}

/**
 * INTERNAL API
 */
@InternalApi private[kubernetes] final case class PodList(items: immutable.Seq[PodList.Pod])
