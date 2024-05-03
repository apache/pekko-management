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

import java.text.Normalizer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.Future
import scala.util.{ Failure, Success }
import scala.annotation.nowarn
import org.apache.pekko
import org.apache.pekko.coordination.lease.kubernetes.AbstractKubernetesLease.makeDNS1039Compatible
import pekko.actor.ExtendedActorSystem
import pekko.coordination.lease.kubernetes.LeaseActor._
import pekko.coordination.lease.scaladsl.Lease
import pekko.coordination.lease.LeaseException
import pekko.coordination.lease.LeaseSettings
import pekko.coordination.lease.LeaseTimeoutException
import pekko.dispatch.ExecutionContexts
import pekko.pattern.AskTimeoutException
import pekko.util.ConstantFun
import pekko.util.Timeout
import org.slf4j.LoggerFactory

object AbstractKubernetesLease {
  val configPath = "pekko.coordination.lease.kubernetes"
  private val leaseCounter = new AtomicInteger(1)

  /**
   * Limit the length of a name to 63 characters.
   * Some subsystem of Kubernetes cannot manage longer names.
   */
  private def truncateTo63Characters(name: String): String = name.take(63)

  /**
   * Removes from the leading and trailing positions the specified characters.
   */
  private def trim(name: String, characters: List[Char]): String =
    name.dropWhile(characters.contains(_)).reverse.dropWhile(characters.contains(_)).reverse

  /**
   * Make a name compatible with DNS 1039 standard: like a single domain name segment.
   * Regex to follow: [a-z]([-a-z0-9]*[a-z0-9])
   * Limit the resulting name to 63 characters
   */
  private def makeDNS1039Compatible(name: String): String = {
    val normalized =
      Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase.replaceAll("[_.]", "-").replaceAll("[^-a-z0-9]", "")
    trim(truncateTo63Characters(normalized), List('-'))
  }
}

abstract class AbstractKubernetesLease(system: ExtendedActorSystem, leaseTaken: AtomicBoolean,
    settings: LeaseSettings) extends Lease(settings) {

  import pekko.pattern.ask

  private val logger = LoggerFactory.getLogger(classOf[KubernetesLease])

  protected val k8sSettings: KubernetesSettings = KubernetesSettings(settings.leaseConfig, settings.timeoutSettings)

  protected def k8sApi: KubernetesApi

  private implicit val timeout: Timeout = Timeout(settings.timeoutSettings.operationTimeout)

  private val leaseName = makeDNS1039Compatible(settings.leaseName)
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
        case Success(LeaseReleased)       => Success(true)
        case Success(InvalidRequest(msg)) => Failure(new LeaseException(msg))
        case Failure(_: AskTimeoutException) => Failure(
            new LeaseTimeoutException(
              s"Timed out trying to release lease [$leaseName, ${settings.ownerName}]. It may still be taken."))
        case Failure(exception) => Failure(exception)
      }(ExecutionContexts.parasitic)
  }

  override def acquire(): Future[Boolean] = {
    acquire(ConstantFun.scalaAnyToUnit)

  }
  @nowarn("msg=match may not be exhaustive")
  override def acquire(leaseLostCallback: Option[Throwable] => Unit): Future[Boolean] = {
    (leaseActor ? Acquire(leaseLostCallback))
      .transform {
        case Success(LeaseAcquired)       => Success(true)
        case Success(LeaseTaken)          => Success(false)
        case Success(InvalidRequest(msg)) => Failure(new LeaseException(msg))
        case Failure(_: AskTimeoutException) => Failure(new LeaseTimeoutException(
            s"Timed out trying to acquire lease [$leaseName, ${settings.ownerName}]. It may still be taken."))
        case Failure(exception) => Failure(exception)
      }(ExecutionContexts.parasitic)
  }
}
