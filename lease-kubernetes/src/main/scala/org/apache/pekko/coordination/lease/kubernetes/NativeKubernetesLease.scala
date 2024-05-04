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

package org.apache.pekko.coordination.lease.kubernetes

import org.apache.pekko
import pekko.actor.ExtendedActorSystem
import pekko.coordination.lease.LeaseSettings
import pekko.coordination.lease.kubernetes.internal.NativeKubernetesApiImpl

import java.util.concurrent.atomic.AtomicBoolean

class NativeKubernetesLease private[pekko] (system: ExtendedActorSystem, leaseTaken: AtomicBoolean,
    settings: LeaseSettings)
    extends AbstractKubernetesLease(system, leaseTaken, settings) {

  override def k8sApi = new NativeKubernetesApiImpl(system, k8sSettings)

  def this(leaseSettings: LeaseSettings, system: ExtendedActorSystem) =
    this(system, new AtomicBoolean(false), leaseSettings)
}
