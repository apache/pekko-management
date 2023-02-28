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

package org.apache.pekko.cluster.bootstrap.demo;

import org.apache.pekko.actor.AbstractActor;
import org.apache.pekko.cluster.Cluster;
import org.apache.pekko.event.Logging;
import org.apache.pekko.event.LoggingAdapter;
import org.apache.pekko.japi.pf.ReceiveBuilder;

public class ClusterWatcher extends AbstractActor {
  LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  Cluster cluster = Cluster.get(context().system());

  @Override
  public Receive createReceive() {
    return ReceiveBuilder.create()
      .matchAny(msg -> {
        log.info("Cluster " + cluster.selfAddress() + " >>> " + msg);
      })
      .build();
  }
}
