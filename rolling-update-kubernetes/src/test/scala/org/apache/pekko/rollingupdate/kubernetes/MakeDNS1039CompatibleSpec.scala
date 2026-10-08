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

package org.apache.pekko.rollingupdate.kubernetes

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class MakeDNS1039CompatibleSpec extends AnyWordSpec with Matchers {

  "KubernetesApi.makeDNS1039Compatible" should {

    "leave a valid pod name unchanged" in {
      KubernetesApi.makeDNS1039Compatible("my-pod-1") shouldEqual "my-pod-1"
    }

    "convert underscores and dots to hyphens" in {
      KubernetesApi.makeDNS1039Compatible("my.pod_name") shouldEqual "my-pod-name"
    }

    "lowercase and drop characters that are not allowed" in {
      KubernetesApi.makeDNS1039Compatible("My@Pod!Name") shouldEqual "mypodname"
    }

    "strip accents after unicode normalization" in {
      KubernetesApi.makeDNS1039Compatible("p\u00f6d-\u00e9") shouldEqual "pod-e"
    }

    "trim leading and trailing hyphens" in {
      KubernetesApi.makeDNS1039Compatible("_my-pod.") shouldEqual "my-pod"
    }

    "reject names longer than 63 characters" in {
      an[IllegalArgumentException] should be thrownBy KubernetesApi.makeDNS1039Compatible("a" * 64)
    }
  }
}
