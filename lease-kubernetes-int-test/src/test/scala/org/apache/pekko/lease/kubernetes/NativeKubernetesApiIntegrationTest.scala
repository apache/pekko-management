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
import pekko.coordination.lease.kubernetes.internal.NativeKubernetesApiImpl

/**
 * This test requires an API server available on localhost:8080, the lease CRD created and a namespace called lease
 *
 * One way of doing this is to have a kubectl proxy open:
 *
 * `kubectl proxy --port=8080`
 */
class NativeKubernetesApiIntegrationTest extends AbstractKubernetesApiIntegrationTest {

  val underTest = new NativeKubernetesApiImpl(system, settings)
}
