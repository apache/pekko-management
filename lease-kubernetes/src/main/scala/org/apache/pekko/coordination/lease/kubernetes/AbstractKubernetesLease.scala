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

package org.apache.pekko.coordination.lease.kubernetes

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success }
import scala.annotation.nowarn

import org.apache.pekko
import pekko.actor.ExtendedActorSystem
import pekko.coordination.lease.kubernetes.LeaseActor._
import pekko.coordination.lease.scaladsl.Lease
import pekko.coordination.lease.LeaseException
import pekko.coordination.lease.LeaseSettings
import pekko.coordination.lease.LeaseTimeoutException
import pekko.pattern.AskTimeoutException
import pekko.util.ConstantFun
import pekko.util.Timeout
import org.slf4j.LoggerFactory

object AbstractKubernetesLease {
  val configPath = "pekko.coordination.lease.kubernetes"
  private val leaseCounter = new AtomicInteger(1)

  // Base32 alphabet (RFC 4648 §6), **lowercased** so every character is a valid DNS 1039 label
  // character.  '=' padding is intentionally omitted: we stream full 5-bit groups and emit one
  // final partial group when bits remain, so the output contains only [a-z2-7] characters.
  private val base32Alphabet = "abcdefghijklmnopqrstuvwxyz234567"

  /**
   * Base32-encode a byte array using a lowercase alphabet and no '=' padding.
   * Every output character is in [a-z2-7], making the result safe for use in DNS 1039 labels.
   */
  private[kubernetes] def base32Encode(bytes: Array[Byte]): String = {
    val sb = new StringBuilder
    var buffer = 0
    var bitsLeft = 0
    for (b <- bytes) {
      buffer = (buffer << 8) | (b & 0xFF)
      bitsLeft += 8
      while (bitsLeft >= 5) {
        bitsLeft -= 5
        sb.append(base32Alphabet((buffer >> bitsLeft) & 0x1F))
      }
    }
    if (bitsLeft > 0) {
      sb.append(base32Alphabet((buffer << (5 - bitsLeft)) & 0x1F))
    }
    sb.toString()
  }

  /**
   * Compute a short hash suffix for the given name: SHA-256 → base32 → first `length` chars.
   */
  private def computeHashSuffix(name: String, length: Int): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hashBytes = digest.digest(name.getBytes(StandardCharsets.UTF_8))
    base32Encode(hashBytes).take(length)
  }

  /**
   * Limit the length of a name to the given number of characters.
   * Some subsystem of Kubernetes cannot manage longer names.
   */
  private def truncateToLength(name: String, maxLength: Int): String = name.take(maxLength)

  /**
   * Removes from the leading and trailing positions the specified characters.
   */
  private def trim(name: String, characters: List[Char]): String =
    name.dropWhile(characters.contains(_)).reverse.dropWhile(characters.contains(_)).reverse

  /**
   * Make a name compatible with DNS 1039 standard: like a single domain name segment.
   * Regex to follow: [a-z]([-a-z0-9]*[a-z0-9])
   * Limit the resulting name to maxLength characters (default 63).
   * When truncation is necessary and hashLength > 0, the last (hashLength + 1) characters of the
   * truncated name are replaced by a hyphen followed by a hashLength-character hash suffix derived
   * from a SHA-256 digest of the original name (base32-encoded, first hashLength chars taken).
   * If hashLength >= maxLength the result consists entirely of the first maxLength hash characters.
   */
  private[kubernetes] def makeDNS1039Compatible(name: String, maxLength: Int = 63, hashLength: Int = 0): String = {
    val normalized =
      Normalizer.normalize(name, Normalizer.Form.NFKD)
      .toLowerCase(Locale.ROOT)
      .replaceAll("[_.]", "-")
      .replaceAll("[^-a-z0-9]", "")
    if (normalized.length <= maxLength || hashLength <= 0) {
      trim(truncateToLength(normalized, maxLength), List('-'))
    } else {
      val maxSuffixLength = math.min(hashLength, maxLength)
      val hashSuffix = computeHashSuffix(name, maxSuffixLength)
      if (hashSuffix.length >= maxLength - 1) {
        // Hash suffix alone fills or exceeds the max length, so return only hash chars (capped at maxLength)
        // also account for the '-' that would be added if we had room for a prefix
        hashSuffix.take(maxLength)
      } else {
        // Truncate prefix to fit the hash suffix and hyphen within maxLength
        val prefixLength = maxLength - hashSuffix.length - 1
        val prefix = trim(truncateToLength(normalized, prefixLength), List('-'))
        s"$prefix-$hashSuffix"
      }
    }
  }
}

abstract class AbstractKubernetesLease(system: ExtendedActorSystem, leaseTaken: AtomicBoolean,
    settings: LeaseSettings) extends Lease(settings) {

  import pekko.pattern.ask

  private val logger = LoggerFactory.getLogger(classOf[KubernetesLease])

  protected val k8sSettings: KubernetesSettings = KubernetesSettings(settings.leaseConfig, settings.timeoutSettings)

  protected def k8sApi: KubernetesApi

  private implicit val timeout: Timeout = Timeout(settings.timeoutSettings.operationTimeout)

  private val leaseName =
    AbstractKubernetesLease.makeDNS1039Compatible(
      settings.leaseName,
      k8sSettings.leaseLabelMaxLength,
      k8sSettings.onTruncateAddHashLength)
  private val leaseActor = system.systemActorOf(
    LeaseActor.props(k8sApi, settings, leaseName, leaseTaken),
    s"kubernetesLease${AbstractKubernetesLease.leaseCounter.incrementAndGet}")
  if (leaseName != settings.leaseName) {
    logger.info(
      "Original lease name [{}] sanitized for kubernetes: [{}]",
      Array[Object](settings.leaseName, leaseName): _*)
  }
  logger.debug(
    "Starting kubernetes lease actor [{}] for lease [{}], owner [{}]",
    leaseActor,
    leaseName,
    settings.ownerName)

  override def checkLease(): Boolean = leaseTaken.get()

  @nowarn("msg=match may not be exhaustive")
  override def release(): Future[Boolean] = {
    (leaseActor ? Release())
      .transform {
        case Success(LeaseReleased)          => Success(true)
        case Success(InvalidRequest(msg))    => Failure(new LeaseException(msg))
        case Failure(_: AskTimeoutException) => Failure(
            new LeaseTimeoutException(
              s"Timed out trying to release lease [$leaseName, ${settings.ownerName}]. It may still be taken."))
        case Failure(exception) => Failure(exception)
      }(ExecutionContext.parasitic)
  }

  override def acquire(): Future[Boolean] = {
    acquire(ConstantFun.scalaAnyToUnit)

  }
  @nowarn("msg=match may not be exhaustive")
  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] = {
    (leaseActor ? Acquire(leaseLostCallback))
      .transform {
        case Success(LeaseAcquired)          => Success(true)
        case Success(LeaseTaken)             => Success(false)
        case Success(InvalidRequest(msg))    => Failure(new LeaseException(msg))
        case Failure(_: AskTimeoutException) => Failure(new LeaseTimeoutException(
            s"Timed out trying to acquire lease [$leaseName, ${settings.ownerName}]. It may still be taken."))
        case Failure(exception) => Failure(exception)
      }(ExecutionContext.parasitic)
  }
}
