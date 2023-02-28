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

package org.apache.pekko.cluster.bootstrap

import org.apache.pekko.actor.Actor
import org.apache.pekko.actor.ActorLogging

class NoisySingleton extends Actor with ActorLogging {

  override def preStart(): Unit =
    log.info("Noisy singleton started")

  override def postStop(): Unit =
    log.info("Noisy singleton stopped")

  override def receive: Receive = {
    case msg => log.info("Msg: {}", msg)
  }
}
