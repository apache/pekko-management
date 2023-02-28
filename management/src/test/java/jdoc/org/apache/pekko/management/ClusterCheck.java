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

package jdoc.org.apache.pekko.management;

import org.apache.pekko.actor.ActorSystem;
import org.apache.pekko.cluster.Cluster;
import org.apache.pekko.cluster.MemberStatus;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

//#cluster
class ClusterCheck implements Supplier<CompletionStage<Boolean>> {

    private final Cluster cluster;

    public ClusterCheck(ActorSystem system) {
        cluster = Cluster.get(system);
    }

    @Override
    public CompletionStage<Boolean> get() {
        return CompletableFuture.completedFuture(cluster.selfMember().status() == MemberStatus.up());
    }
}
//#cluster
