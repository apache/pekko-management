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

package org.apache.pekko.pki.kubernetes

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.{ KeyStore, PrivateKey, SecureRandom }
import java.security.cert.{ Certificate, CertificateFactory }

import scala.concurrent.blocking
import scala.jdk.CollectionConverters._
import scala.util.Random

import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.pki.pem.{ DERPrivateKeyLoader, PEMDecoder }

import javax.net.ssl.{ KeyManagerFactory, SSLContext, SSLEngine, TrustManager, TrustManagerFactory }

/**
 * INTERNAL API
 * Convenience methods to ease building an SSLContext from k8s-provided PEM files.
 */
@InternalApi
private[pekko] object PemManagersProvider {

  /**
   * INTERNAL API
   */
  @InternalApi def buildTrustManagers(cacerts: Iterable[Certificate]): Array[TrustManager] = {
    val trustStore = KeyStore.getInstance("JKS")
    trustStore.load(null)
    cacerts.foreach(cert => trustStore.setCertificateEntry("cacert-" + Random.alphanumeric.take(6).mkString(""), cert))

    val tmf =
      TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm)
    tmf.init(trustStore)
    tmf.getTrustManagers
  }

  /**
   * INTERNAL API
   */
  @InternalApi def loadPrivateKey(filename: String): PrivateKey = blocking {
    val bytes = Files.readAllBytes(new File(filename).toPath)
    val pemData = new String(bytes, StandardCharsets.UTF_8)
    DERPrivateKeyLoader.load(PEMDecoder.decode(pemData))
  }

  private val certFactory = CertificateFactory.getInstance("X.509")

  /**
   * INTERNAL API
   */
  @InternalApi def loadCertificates(filename: String): Iterable[Certificate] = blocking {
    certFactory.generateCertificates(Files.newInputStream(new File(filename).toPath)).asScala
  }

  /**
   * INTERNAL API
   *
   * TLS protocol versions in ascending order of preference. Anything not listed here
   * (for example SSLv3 or the "SSLv2Hello" pseudo-protocol) is never selected by
   * [[protocolsAtOrAbove]].
   */
  private val TlsVersionOrder = Map("TLSv1" -> 1, "TLSv1.1" -> 2, "TLSv1.2" -> 3, "TLSv1.3" -> 4)

  /**
   * INTERNAL API
   *
   * The subset of `supportedProtocols` that is at or above `minTlsVersion`.
   *
   * A minimum version can only be enforced on an `SSLSocket` or `SSLEngine`; an `SSLContext`
   * has no mutable protocol list, so callers must apply the result via
   * [[configureClientEngine]] or `SSLSocket.setEnabledProtocols`.
   *
   * @throws IllegalArgumentException if `minTlsVersion` is not a known TLS version, or if no
   *                                  supported protocol satisfies it.
   */
  @InternalApi def protocolsAtOrAbove(minTlsVersion: String, supportedProtocols: Array[String]): Array[String] = {
    val minOrder = TlsVersionOrder.getOrElse(
      minTlsVersion,
      throw new IllegalArgumentException(
        s"Unknown TLS version [$minTlsVersion]. Supported values: " +
        TlsVersionOrder.keys.toSeq.sorted.mkString(", ")))
    val filtered = supportedProtocols.filter(protocol => TlsVersionOrder.get(protocol).exists(_ >= minOrder))
    if (filtered.isEmpty)
      throw new IllegalArgumentException(
        s"No TLS protocol at or above [$minTlsVersion] is supported. Supported protocols: " +
        supportedProtocols.mkString(", "))
    filtered
  }

  /**
   * INTERNAL API
   *
   * Configures `engine` as a client engine that only negotiates TLS versions at or above
   * `minTlsVersion`, keeping the "https" endpoint identification algorithm that
   * `ConnectionContext.httpsClient(sslContext)` would otherwise apply, so hostname
   * verification is not lost.
   */
  @InternalApi def configureClientEngine(engine: SSLEngine, minTlsVersion: String): SSLEngine = {
    engine.setUseClientMode(true)
    engine.setEnabledProtocols(protocolsAtOrAbove(minTlsVersion, engine.getSupportedProtocols))
    val params = engine.getSSLParameters
    params.setEndpointIdentificationAlgorithm("https")
    engine.setSSLParameters(params)
    engine
  }

  /**
   * INTERNAL API
   *
   * Creates an SSLContext that trusts the given CA certificate file, with no client key material.
   *
   * The returned context supports every TLS version the JVM enables by default; a minimum
   * version cannot be pinned on an `SSLContext`, so restrict the connection itself with
   * [[configureClientEngine]].
   */
  @InternalApi def createSslContext(caCertPath: String): SSLContext =
    createSslContext(Some(caCertPath))

  /**
   * INTERNAL API
   *
   * Creates an SSLContext with no client key material. If `caCertPath` is `Some(path)` the CA
   * certificates in that file are trusted, otherwise the default JVM trust store is used.
   */
  @InternalApi def createSslContext(caCertPath: Option[String]): SSLContext = {
    val tm = caCertPath match {
      case Some(path) => buildTrustManagers(loadCertificates(path))
      case None       => null // null means use the default JVM trust store
    }
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null)
    factory.init(ks, Array.empty)
    val km = factory.getKeyManagers
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(km, tm, new SecureRandom)
    sslContext
  }

}
