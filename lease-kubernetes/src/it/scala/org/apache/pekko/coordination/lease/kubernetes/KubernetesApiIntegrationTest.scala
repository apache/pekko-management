/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

package org.apache.pekko.coordination.lease.kubernetes

import org.apache.pekko
import pekko.coordination.lease.kubernetes.internal.KubernetesApiImpl

/**
 * This test requires an API server available on localhost:8080, the lease CRD created and a namespace called lease
 *
 * One way of doing this is to have a kubectl proxy open:
 *
 * `kubectl proxy --port=8080`
 */
class KubernetesApiIntegrationTest extends AbstractKubernetesApiIntegrationTest {

  val underTest = new KubernetesApiImpl(system, settings)
}
