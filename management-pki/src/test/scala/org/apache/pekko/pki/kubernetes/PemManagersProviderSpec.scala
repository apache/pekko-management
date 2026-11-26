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

import java.security.{ KeyManagementException, SecureRandom }
import javax.net.ssl.{
  KeyManager,
  SSLContext,
  SSLContextSpi,
  SSLEngine,
  SSLServerSocketFactory,
  SSLSessionContext,
  SSLSocketFactory,
  TrustManager
}

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PemManagersProviderSpec extends AnyWordSpec with Matchers {

  private def clientEngine(minTlsVersion: String): SSLEngine = {
    // no CA path: trust the default JVM store, which is enough to inspect protocol negotiation
    val sslContext = PemManagersProvider.createSslContext(None)
    PemManagersProvider.configureClientEngine(sslContext.createSSLEngine("example.com", 443), minTlsVersion)
  }

  /**
   * An SSLContext whose client engines only have `clientProtocols` enabled, which is what the
   * `jdk.tls.client.protocols` system property does. That property is read once per JVM, so it
   * cannot be set from a test.
   */
  private def restrictedClientContext(clientProtocols: String*): SSLContext = {
    val delegate = PemManagersProvider.createSslContext(None)
    def restrict(engine: SSLEngine): SSLEngine = {
      engine.setUseClientMode(true)
      engine.setEnabledProtocols(clientProtocols.toArray)
      engine
    }
    val spi = new SSLContextSpi {
      override def engineInit(km: Array[KeyManager], tm: Array[TrustManager], sr: SecureRandom): Unit =
        throw new KeyManagementException("already initialized")
      override def engineGetSocketFactory(): SSLSocketFactory = delegate.getSocketFactory
      override def engineGetServerSocketFactory(): SSLServerSocketFactory = delegate.getServerSocketFactory
      override def engineCreateSSLEngine(): SSLEngine = restrict(delegate.createSSLEngine())
      override def engineCreateSSLEngine(host: String, port: Int): SSLEngine =
        restrict(delegate.createSSLEngine(host, port))
      override def engineGetServerSessionContext(): SSLSessionContext = delegate.getServerSessionContext
      override def engineGetClientSessionContext(): SSLSessionContext = delegate.getClientSessionContext
    }
    new SSLContext(spi, delegate.getProvider, delegate.getProtocol) {}
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

    // the minimum narrows what is enabled for the client, it must not bring back a version
    // that is supported by the JVM but was excluded, e.g. with jdk.tls.client.protocols
    "not enable protocols that are supported but not enabled for the client" in {
      val engine = restrictedClientContext("TLSv1.2").createSSLEngine("example.com", 443)
      engine.getSupportedProtocols should contain("TLSv1.3")
      PemManagersProvider.configureClientEngine(engine, "TLSv1.2")
      engine.getEnabledProtocols.toSeq shouldEqual Seq("TLSv1.2")
    }

    "reject a minimum that only a protocol not enabled for the client satisfies" in {
      val engine = restrictedClientContext("TLSv1.2").createSSLEngine("example.com", 443)
      intercept[IllegalArgumentException] {
        PemManagersProvider.configureClientEngine(engine, "TLSv1.3")
      }.getMessage should include("TLSv1.3")
      engine.getEnabledProtocols.toSeq shouldEqual Seq("TLSv1.2")
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

  "clientEngineFactory" should {
    "reject an unknown minimum version before any engine is created" in {
      val sslContext = PemManagersProvider.createSslContext(None)
      intercept[IllegalArgumentException] {
        PemManagersProvider.clientEngineFactory(sslContext, "BAD_VERSION")
      }.getMessage should include("BAD_VERSION")
      // "TLS" was accepted when the setting was passed to SSLContext.getInstance; it is not a version
      intercept[IllegalArgumentException] {
        PemManagersProvider.clientEngineFactory(sslContext, "TLS")
      }
    }

    "keep a restricted client protocol list restricted" in {
      val sslContext = restrictedClientContext("TLSv1.1", "TLSv1.2")
      val engine = PemManagersProvider.clientEngineFactory(sslContext, "TLSv1.2")("example.com", 443)
      engine.getSupportedProtocols should contain("TLSv1.3")
      engine.getEnabledProtocols.toSeq shouldEqual Seq("TLSv1.2")
    }

    "check the minimum against the protocols enabled for the client before any connection" in {
      // TLSv1.3 is supported by the JVM, but not in the restricted client list
      val sslContext = restrictedClientContext("TLSv1.2")
      sslContext.getSupportedSSLParameters.getProtocols should contain("TLSv1.3")
      intercept[IllegalArgumentException] {
        PemManagersProvider.clientEngineFactory(sslContext, "TLSv1.3")
      }.getMessage should include("TLSv1.3")
    }

    "trim the minimum version and apply it to the engines it creates" in {
      val sslContext = PemManagersProvider.createSslContext(None)
      val engine = PemManagersProvider.clientEngineFactory(sslContext, " TLSv1.3 ")("example.com", 443)
      engine.getEnabledProtocols.toSeq shouldEqual Seq("TLSv1.3")
      engine.getUseClientMode shouldBe true
      engine.getSSLParameters.getEndpointIdentificationAlgorithm shouldEqual "https"
      engine.getPeerHost shouldEqual "example.com"
      engine.getPeerPort shouldEqual 443
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
