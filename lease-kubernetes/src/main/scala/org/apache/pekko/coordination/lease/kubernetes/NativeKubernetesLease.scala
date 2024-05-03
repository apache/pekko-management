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

package org.apache.pekko.coordination.lease.kubernetes

import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.coordination.lease.LeaseSettings
import org.apache.pekko.coordination.lease.kubernetes.internal.NativeKubernetesApiImpl

import java.util.concurrent.atomic.AtomicBoolean

class NativeKubernetesLease private[pekko] (system: ExtendedActorSystem, leaseTaken: AtomicBoolean,
    settings: LeaseSettings)
    extends AbstractKubernetesLease(system, leaseTaken, settings) {

  override def k8sApi = new NativeKubernetesApiImpl(system, k8sSettings)

  def this(leaseSettings: LeaseSettings, system: ExtendedActorSystem) =
    this(system, new AtomicBoolean(false), leaseSettings)
}
