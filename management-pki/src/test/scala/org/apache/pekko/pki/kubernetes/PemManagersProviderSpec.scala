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

package org.apache.pekko.pki.kubernetes

import javax.net.ssl.SSLEngine

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PemManagersProviderSpec extends AnyWordSpec with Matchers {

  private def clientEngine(minTlsVersion: String): SSLEngine = {
    // no CA path: trust the default JVM store, which is enough to inspect protocol negotiation
    val sslContext = PemManagersProvider.createSslContext(None)
    PemManagersProvider.configureClientEngine(sslContext.createSSLEngine("example.com", 443), minTlsVersion)
  }

  "protocolsAtOrAbove" should {
    "keep only versions at or above the minimum" in {
      val supported = Array("TLSv1", "TLSv1.1", "TLSv1.2", "TLSv1.3")
      PemManagersProvider.protocolsAtOrAbove("TLSv1.2", supported).toSeq shouldEqual Seq("TLSv1.2", "TLSv1.3")
      PemManagersProvider.protocolsAtOrAbove("TLSv1.3", supported).toSeq shouldEqual Seq("TLSv1.3")
      PemManagersProvider.protocolsAtOrAbove("TLSv1", supported).toSeq shouldEqual supported.toSeq
    }

    "never select non-TLS pseudo-protocols" in {
      val supported = Array("SSLv2Hello", "SSLv3", "TLSv1.2", "TLSv1.3")
      PemManagersProvider.protocolsAtOrAbove("TLSv1", supported).toSeq shouldEqual Seq("TLSv1.2", "TLSv1.3")
    }

    "reject an unknown minimum version" in {
      val ex = intercept[IllegalArgumentException] {
        PemManagersProvider.protocolsAtOrAbove("BAD_VERSION", Array("TLSv1.2"))
      }
      ex.getMessage should include("BAD_VERSION")
    }

    "reject a minimum no supported protocol satisfies" in {
      val ex = intercept[IllegalArgumentException] {
        PemManagersProvider.protocolsAtOrAbove("TLSv1.3", Array("TLSv1.1", "TLSv1.2"))
      }
      ex.getMessage should include("TLSv1.3")
    }
  }

  "configureClientEngine" should {
    // regression: the minimum used to be applied to a throwaway SSLParameters copy returned by
    // SSLContext.getDefaultSSLParameters, so it never reached the connection at all
    "actually restrict the protocols enabled on the engine" in {
      val engine = clientEngine("TLSv1.3")
      engine.getEnabledProtocols.toSeq shouldEqual Seq("TLSv1.3")
      engine.getEnabledProtocols should not contain "TLSv1.2"
    }

    "enable every version at or above the minimum" in {
      val engine = clientEngine("TLSv1.2")
      val enabled = engine.getEnabledProtocols.toSeq
      enabled should contain("TLSv1.2")
      enabled.foreach(p => Seq("TLSv1.2", "TLSv1.3") should contain(p))
    }

    "put the engine in client mode and keep https endpoint identification" in {
      val engine = clientEngine("TLSv1.2")
      engine.getUseClientMode shouldBe true
      // dropping this would silently disable hostname verification
      engine.getSSLParameters.getEndpointIdentificationAlgorithm shouldEqual "https"
    }

    "reject an unknown minimum version" in {
      intercept[IllegalArgumentException] {
        clientEngine("BAD_VERSION")
      }.getMessage should include("BAD_VERSION")
    }
  }

  "createSslContext" should {
    "support more than just the default protocol" in {
      // the context itself must stay unrestricted; the engine is what pins the minimum
      val engine = PemManagersProvider.createSslContext(None).createSSLEngine()
      engine.getSupportedProtocols should contain("TLSv1.2")
    }
  }
}
