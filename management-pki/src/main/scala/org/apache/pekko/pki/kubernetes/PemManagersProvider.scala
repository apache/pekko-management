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

import java.io.ByteArrayInputStream
import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.KeyStore
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import scala.concurrent.blocking
import org.apache.pekko
import pekko.annotation.InternalApi
import pekko.pki.pem.DERPrivateKeyLoader
import pekko.pki.pem.PEMDecoder
import pekko.util.ccompat.JavaConverters._
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import scala.util.Random

/**
 * INTERNAL API
 * Convenience methods to ease building an SSLContext from k8s-provided PEM files.
 */
// Duplicate from https://github.com/apache/incubator-pekko/blob/964dcf53eb9a81a65944a1b1d51575091fe0a031/remote/src/main/scala/org/apache/pekko/remote/artery/tcp/ssl/PemManagersProvider.scala
// Eventually that will be a bit more open and we can reuse the class from Pekko in pekko-management.
// See also https://github.com/akka/akka-http/issues/3772
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
    val pemData = new String(bytes, Charset.forName("UTF-8"))
    DERPrivateKeyLoader.load(PEMDecoder.decode(pemData))
  }

  private val certFactory = CertificateFactory.getInstance("X.509")

  /**
   * INTERNAL API
   */
  @InternalApi def loadCertificates(filename: String): Iterable[Certificate] = blocking {
    val bytes = Files.readAllBytes(new File(filename).toPath)
    certFactory.generateCertificates(new ByteArrayInputStream(bytes)).asScala
  }

}
