/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

/*
 * Copyright (C) 2017-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package org.apache.pekko.rollingupdate.kubernetes

import java.text.Normalizer

import scala.collection.immutable
import scala.concurrent.Future

import org.apache.pekko
import pekko.Done
import pekko.actor.AddressFromURIString
import pekko.annotation.InternalApi
import pekko.cluster.UniqueAddress

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] final case class PodCostResource(version: String, pods: immutable.Seq[PodCost])

/**
 * INTERNAL API
 */
@InternalApi
private[pekko] final case class PodCost(podName: String, cost: Int, address: String, uid: Long, time: Long) {
  @transient
  lazy val uniqueAddress: UniqueAddress = UniqueAddress(AddressFromURIString(address), uid)
}

/**
 * INTERNAL API
 */
@InternalApi private[pekko] sealed class PodCostException(message: String) extends RuntimeException(message)

/**
 * INTERNAL API
 */
@InternalApi private[pekko] final class PodCostTimeoutException(message: String) extends PodCostException(message)

/**
 * INTERNAL API
 */
@InternalApi private[pekko] final class PodCostClientException(message: String) extends PodCostException(message)

/**
 * INTERNAL API
 */
@InternalApi private[pekko] sealed class ReadRevisionException(message: String) extends RuntimeException(message)

/**
 * INTERNAL API
 */
@InternalApi private[pekko] sealed class MissingPodNameException(message: String) extends RuntimeException(message)

/**
 * INTERNAL API
 */
@InternalApi private[pekko] sealed class GetPodException(message: String) extends RuntimeException(message)

/**
 * INTERNAL API
 */
@InternalApi private[pekko] sealed class ReplicaSetException(message: String) extends RuntimeException(message)

/**
 * INTERNAL API
 */
@InternalApi private[pekko] object KubernetesApi {

  /**
   * Removes from the leading and trailing positions the specified characters.
   */
  private def trim(name: String, characters: List[Char]): String =
    name.dropWhile(characters.contains(_)).reverse.dropWhile(characters.contains(_)).reverse

  /**
   * Make a name compatible with DNS 1035 standard: like a single domain name segment.
   * Regex to follow: [a-z]([-a-z0-9]*[a-z0-9])
   * Validates the resulting name to be at most 63 characters, otherwise throws `IllegalArgumentException`.
   */
  def makeDNS1039Compatible(name: String): String = {
    val normalized =
      Normalizer.normalize(name, Normalizer.Form.NFKD).toLowerCase.replaceAll("[_.]", "-").replaceAll("[^-a-z0-9]", "")
    if (normalized.length > 63)
      throw new IllegalArgumentException(s"Too long resource name [$normalized]. At most 63 characters are accepted. " +
        "A custom resource name can be defined in configuration `pekko.rollingupdate.kubernetes.custom-resource.cr-name`.")
    trim(normalized, List('-'))
  }
}

/**
 * INTERNAL API
 */
private[pekko] trait KubernetesApi {

  def namespace: String

  def updatePodDeletionCostAnnotation(podName: String, cost: Int): Future[Done]

  def readRevision(): Future[String]

  /**
   * Reads a PodCost from the API server. If it doesn't exist it tries to create it.
   * The creation can fail due to another instance creating at the same time, in this case
   * the read is retried.
   */
  def readOrCreatePodCostResource(crName: String): Future[PodCostResource]

  /**
   * Update the named resource.
   *
   * Must call [[readOrCreatePodCostResource]] first to get a resource version.
   *
   * Can return one of three things:
   *  - Future failure e.g. timed out waiting for k8s api server to respond
   *  - Left - Update failed due to version not matching current in the k8s api server. In this case resource is returned so the version can be used for subsequent calls
   *  - Right - Success
   *
   *  Any subsequent updates should also use the latest version or re-read with [[readOrCreatePodCostResource]]
   */
  def updatePodCostResource(
      crName: String,
      version: String,
      pods: immutable.Seq[PodCost]): Future[Either[PodCostResource, PodCostResource]]

}
