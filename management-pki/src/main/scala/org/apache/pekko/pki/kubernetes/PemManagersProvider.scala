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

import javax.net.ssl.{ KeyManagerFactory, SSLContext, TrustManager, TrustManagerFactory }

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

  private val TlsVersionOrder = Map("TLSv1" -> 1, "TLSv1.1" -> 2, "TLSv1.2" -> 3, "TLSv1.3" -> 4)

  /**
   * INTERNAL API
   *
   * Creates an SSLContext that trusts the given CA certificate file, with no client key material.
   * Only TLS protocol versions at or above the given `minTlsVersion` are enabled.
   */
  @InternalApi def createSslContext(caCertPath: String, minTlsVersion: String): SSLContext = {
    createSslContext(Some(caCertPath), minTlsVersion)
  }

  /**
   * INTERNAL API
   *
   * Creates an SSLContext with no client key material.
   * If `caCertPath` is `Some(path)`, trusts the CA certificates in that file.
   * If `caCertPath` is `None`, uses the default JVM trust store.
   * Only TLS protocol versions at or above the given `minTlsVersion` are enabled.
   */
  @InternalApi def createSslContext(caCertPath: Option[String], minTlsVersion: String): SSLContext = {
    val tm = caCertPath match {
      case Some(path) => buildTrustManagers(loadCertificates(path))
      case None       => null // use default JVM trust store
    }
    val factory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm)
    val ks = KeyStore.getInstance("PKCS12")
    ks.load(null)
    factory.init(ks, Array.empty)
    val km = factory.getKeyManagers
    val sslContext = SSLContext.getInstance("TLS")
    sslContext.init(km, tm, new SecureRandom)
    val minOrder = TlsVersionOrder.getOrElse(minTlsVersion,
      throw new IllegalArgumentException(s"Unknown TLS version: $minTlsVersion. " +
        s"Supported values: ${TlsVersionOrder.keys.mkString(", ")}"))
    val defaultParams = sslContext.getDefaultSSLParameters
    val filteredProtocols = defaultParams.getProtocols.filter { protocol =>
      TlsVersionOrder.get(protocol).exists(_ >= minOrder)
    }
    require(filteredProtocols.nonEmpty,
      s"No supported TLS protocols at or above $minTlsVersion. " +
      s"Supported protocols: ${defaultParams.getProtocols.mkString(", ")}")
    defaultParams.setProtocols(filteredProtocols)
    sslContext
  }

}
