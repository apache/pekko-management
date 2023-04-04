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

package org.apache.pekko.management.cluster.cluster;

import org.apache.pekko.annotation.InternalApi;
import org.apache.pekko.cluster.Cluster;
import org.apache.pekko.cluster.ClusterReadView;

/** INTERNAL API */
@InternalApi
public class ClusterReadViewAccess {

  /**
   * INTERNAL API
   *
   * <p>Exposes the internal {@code readView} of the Akka Cluster, not reachable from Scala code
   * because it is {@code private[cluster]}.
   */
  @InternalApi
  public static ClusterReadView internalReadView(Cluster cluster) {
    return cluster.readView();
  }
}
