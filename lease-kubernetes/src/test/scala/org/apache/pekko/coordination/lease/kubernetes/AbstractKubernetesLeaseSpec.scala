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

import org.scalatest.PrivateMethodTester
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AbstractKubernetesLeaseSpec extends AnyWordSpec with Matchers with PrivateMethodTester {

  private val makeDNS1039CompatibleMethod = PrivateMethod[String](Symbol("makeDNS1039Compatible"))

  private def makeDNS1039Compatible(leaseName: String): String =
    AbstractKubernetesLease.invokePrivate(makeDNS1039CompatibleMethod(leaseName))

  "AbstractKubernetesLease" should {
    "normalize a lease name shorter than 63 characters" in {
      val leaseName = "test-system-singleton-pekko://test-system/path/to/actor"
      makeDNS1039Compatible(leaseName) shouldEqual "test-system-singleton-pekkotest-systempathtoactor"
    }
    "hash a lease name longer than 63 characters" in {
      val leaseName =
        "test-with-long-system-name-that-has-more-than-the-expected-characters-count-singleton-pekko://test-with-long-system-name-that-has-more-than-the-expected-characters-count/path/to/actor"
      makeDNS1039Compatible(leaseName) shouldEqual "test-with-long-system-name-that-has-more-than-the-expe-5c4332cf"
    }
  }

}
