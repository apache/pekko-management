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

package org.apache.pekko.management.cluster.bootstrap

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.{ AtomicBoolean, AtomicReference }

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.annotation.InternalApi
import pekko.discovery.ServiceDiscovery.ResolvedTarget
import pekko.event.{ LogSource, Logging }
import pekko.http.scaladsl.model.Uri

import scala.concurrent.Await
import scala.concurrent.duration._

/**
 * INTERNAL API
 *
 * Class for further behavior in a [[pekko.management.cluster.bootstrap.JoinDecider]]
 * leveraging self host logic.
 */
@InternalApi private[bootstrap] abstract class SelfAwareJoinDecider(
    system: ActorSystem,
    settings: ClusterBootstrapSettings) extends JoinDecider {

  protected implicit val classLogSource: LogSource[Class[?]] = LogSource.fromClass
  protected val log = Logging.withMarker(system, getClass: Class[?])

  /** Returns the current `selfContactPoints` as a String for logging, e.g. [127.0.0.1:64714]. */
  protected def contactPointString(contactPoint: (String, Int)): String =
    contactPoint.productIterator.mkString(":")

  protected def contactPointString(contactPoint: ResolvedTarget): String =
    s"${contactPoint.host}:${contactPoint.port.getOrElse("0")}"

  /**
   * How long to block waiting for the self contact point to be set. `ClusterBootstrap` fails the
   * promise itself after `ClusterBootstrap.SelfContactPointTimeout`, so this only has to outlast
   * that; it is derived from it rather than restated so that the two cannot drift apart. Overridable
   * for tests, which cannot afford to wait this long.
   */
  protected def selfContactPointTimeout: FiniteDuration = ClusterBootstrap.SelfContactPointTimeout * 3

  // `decide` is called from the BootstrapCoordinator actor and guarded by its `decisionInProgress`
  // flag, so in-tree callers are already serialised. `JoinDecider` is a user-pluggable interface
  // though, and a custom one is free to resolve the contact point inside the Future it returns, so
  // these stay atomic rather than plain vars.
  //
  // `cachedSelfContactPoint` holds the resolved value, `null` until there is one. Failures are not
  // cached, so a contact point set later is still picked up.
  private val cachedSelfContactPoint = new AtomicReference[(String, Int)]()
  // Set by whichever caller takes the one permitted blocking wait; everyone else fails fast.
  private val hasBlockedForSelfContactPoint = new AtomicBoolean(false)

  /**
   * The value `ClusterBootstrap(system).selfContactPoints` is set prior
   * to HTTP binding, during [[pekko.management.scaladsl.PekkoManagement.start()]], hence we
   * accept blocking on this initialization. If no value is received, the future will fail with
   * a `TimeoutException` and ClusterBootstrap will log an explanatory error to the user.
   *
   * A resolved value is cached. A failure is not, so that a contact point set later is still picked
   * up, but the blocking wait is only ever paid once: once `ClusterBootstrap.start()` has run, the
   * promise is always completed within `ClusterBootstrap.SelfContactPointTimeout`, so reaching
   * the timeout at all means it never ran and no amount of further waiting will help.
   */
  private[bootstrap] def selfContactPoint: (String, Int) = {
    val cached = cachedSelfContactPoint.get()
    if (cached ne null) cached
    else {
      val pending = ClusterBootstrap(system).selfContactPoint
      val uri = pending.value match {
        case Some(completed)                                                  => completed.get
        case None if hasBlockedForSelfContactPoint.compareAndSet(false, true) =>
          Await.result(pending, selfContactPointTimeout)
        case None =>
          throw new TimeoutException(
            "'Bootstrap.selfContactPoint' is still not set after waiting " +
            s"[$selfContactPointTimeout] for it once. It is required for the bootstrap to work " +
            "if binding bootstrap routes manually and not via pekko-management.")
      }
      val resolved = toContactPoint(uri)
      cachedSelfContactPoint.compareAndSet(null, resolved)
      resolved
    }
  }

  private def toContactPoint(uri: Uri): (String, Int) =
    (uri.authority.host.toString, uri.authority.port)

  /**
   * Determines whether it has the need and ability to join self and create a new cluster.
   */
  private[bootstrap] def canJoinSelf(target: ResolvedTarget, info: SeedNodesInformation): Boolean = {
    val self = selfContactPoint
    if (matchesSelf(target, self)) true
    else {
      if (!info.contactPoints.exists(matchesSelf(_, self))) {
        log.warning(
          BootstrapLogMarker.inProgress(info.contactPoints.map(contactPointString), info.allSeedNodes),
          "Self contact point [{}] not found in targets {}",
          contactPointString(self),
          info.contactPoints.mkString(", "))
      }
      false
    }
  }

  private[bootstrap] def matchesSelf(target: ResolvedTarget, contactPoint: (String, Int)): Boolean = {
    val (host, port) = contactPoint
    target.port match {
      case None             => hostMatches(host, target)
      case Some(lowestPort) => hostMatches(host, target) && port == lowestPort
    }
  }

  /**
   * Checks for both host name and IP address for discovery mechanisms that return both.
   */
  protected def hostMatches(host: String, target: ResolvedTarget): Boolean = {
    val hostWithoutBracket = host.replaceAll("[\\[\\]]", "")
    host == target.host || hostWithoutBracket == target.host ||
    target.address
      .map(_.getHostAddress)
      .contains(hostWithoutBracket)
  }

}
