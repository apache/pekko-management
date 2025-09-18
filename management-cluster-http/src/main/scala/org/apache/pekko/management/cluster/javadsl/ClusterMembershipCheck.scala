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

package org.apache.pekko.management.cluster.javadsl

import java.util.concurrent.CompletionStage

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.management.cluster.scaladsl.{ ClusterMembershipCheck => ScalaClusterReadinessCheck }

import scala.concurrent.ExecutionContext
import scala.jdk.FutureConverters._

class ClusterMembershipCheck(system: ActorSystem)
    extends java.util.function.Supplier[CompletionStage[java.lang.Boolean]] {

  private val delegate = new ScalaClusterReadinessCheck(system)

  override def get(): CompletionStage[java.lang.Boolean] = {
    delegate.apply().map(Boolean.box)(ExecutionContext.parasitic).asJava
  }
}
