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

package org.apache.pekko.discovery.consul

import java.nio.file.{ Files, Paths }

import javax.net.ssl.X509TrustManager

import org.apache.pekko.discovery.consul.ConsulServiceDiscovery.{ buildTrustManagers, loadCertificates }
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ConsulCaCertificateSpec extends AnyWordSpec with Matchers {

  private def resourcePath(name: String): String =
    Paths.get(getClass.getClassLoader.getResource(name).toURI).toString

  private def acceptedSubjects(caPath: String): Seq[String] =
    buildTrustManagers(loadCertificates(caPath)).toSeq.collect {
      case tm: X509TrustManager => tm.getAcceptedIssuers.toSeq.map(_.getSubjectX500Principal.getName)
    }.flatten

  "Consul CA certificate loading" should {

    "trust the single certificate in a CA file that holds one" in {
      acceptedSubjects(resourcePath("ca-single.crt")) should contain theSameElementsAs Seq(
        "CN=pekko-consul-test-root-1")
    }

    "trust every certificate in a CA file that bundles more than one" in {
      acceptedSubjects(resourcePath("ca-bundle.crt")) should contain theSameElementsAs Seq(
        "CN=pekko-consul-test-root-1",
        "CN=pekko-consul-test-root-2")
    }

    "fail with a clear error when the CA file holds no certificates" in {
      val empty = Files.createTempFile("pekko-consul-empty-ca", ".crt")
      try {
        val ex = intercept[IllegalArgumentException](loadCertificates(empty.toString))
        ex.getMessage should include(empty.toString)
      } finally {
        Files.deleteIfExists(empty)
        ()
      }
    }
  }
}
