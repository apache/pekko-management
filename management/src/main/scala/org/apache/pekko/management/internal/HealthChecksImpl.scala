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

package org.apache.pekko.management.internal

import java.util.concurrent.CompletionStage
import java.util.function.Supplier
import java.util.{ List => JList }
import java.lang.{ Boolean => JBoolean }
import org.apache.pekko
import pekko.actor.{ ActorSystem, ExtendedActorSystem }
import pekko.annotation.InternalApi
import pekko.event.Logging
import pekko.management.{ HealthCheckSettings, InvalidHealthCheckException, ManagementLogMarker, NamedHealthCheck }
import pekko.management.javadsl.{ LivenessCheckSetup => JLivenessCheckSetup }
import pekko.management.javadsl.{ ReadinessCheckSetup => JReadinessCheckSetup }
import pekko.management.javadsl.{ StartupCheckSetup => JStartupCheckSetup }
import pekko.management.scaladsl.{ HealthChecks, LivenessCheckSetup, ReadinessCheckSetup, StartupCheckSetup }
import pekko.util.FutureConverters._
import pekko.util.ccompat.JavaConverters._

import scala.collection.immutable
import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }

final case class CheckFailedException(msg: String, cause: Throwable) extends RuntimeException(msg, cause)

final case class CheckTimeoutException(msg: String) extends RuntimeException(msg)

/**
 * INTERNAL API
 */
@InternalApi
final private[pekko] class HealthChecksImpl(system: ExtendedActorSystem, settings: HealthCheckSettings)
    extends HealthChecks {
  import HealthChecks._
  import system.dispatcher

  private val log = Logging.withMarker(system, classOf[HealthChecksImpl])

  log.info(
    "Loading startup checks [{}]",
    settings.startupChecks.map(a => a.name -> a.fullyQualifiedClassName).mkString(", "))
  log.info(
    "Loading readiness checks [{}]",
    settings.readinessChecks.map(a => a.name -> a.fullyQualifiedClassName).mkString(", "))
  log.info(
    "Loading liveness checks [{}]",
    settings.livenessChecks.map(a => a.name -> a.fullyQualifiedClassName).mkString(", "))

  private val startupChecks: immutable.Seq[HealthCheck] = {
    val fromScaladslSetup = system.settings.setup.get[StartupCheckSetup] match {
      case None        => Nil
      case Some(setup) => setup.createHealthChecks(system)
    }
    val fromJavadslSetup = system.settings.setup.get[JStartupCheckSetup] match {
      case None        => Nil
      case Some(setup) => convertSuppliersToScala(setup.createHealthChecks(system))
    }
    val fromConfig = load(settings.startupChecks)
    fromConfig ++ fromScaladslSetup ++ fromJavadslSetup
  }

  private val readiness: immutable.Seq[HealthCheck] = {
    val fromScaladslSetup = system.settings.setup.get[ReadinessCheckSetup] match {
      case None        => Nil
      case Some(setup) => setup.createHealthChecks(system)
    }
    val fromJavadslSetup = system.settings.setup.get[JReadinessCheckSetup] match {
      case None        => Nil
      case Some(setup) => convertSuppliersToScala(setup.createHealthChecks(system))
    }
    val fromConfig = load(settings.readinessChecks)
    fromConfig ++ fromScaladslSetup ++ fromJavadslSetup
  }

  private val liveness: immutable.Seq[HealthCheck] = {
    val fromScaladslSetup = system.settings.setup.get[LivenessCheckSetup] match {
      case None        => Nil
      case Some(setup) => setup.createHealthChecks(system)
    }
    val fromJavadslSetup = system.settings.setup.get[JLivenessCheckSetup] match {
      case None        => Nil
      case Some(setup) => convertSuppliersToScala(setup.createHealthChecks(system))
    }
    val fromConfig = load(settings.livenessChecks)
    fromConfig ++ fromScaladslSetup ++ fromJavadslSetup
  }

  private def convertSuppliersToScala(
      suppliers: JList[Supplier[CompletionStage[JBoolean]]]): immutable.Seq[HealthCheck] = {
    suppliers.asScala.toList.map(convertSupplierToScala)
  }

  private def convertSupplierToScala(supplier: Supplier[CompletionStage[JBoolean]]): HealthCheck = { () =>
    supplier.get().asScala.map(_.booleanValue)
  }

  private def tryLoadScalaHealthCheck(fqcn: String): Try[HealthCheck] = {
    system.dynamicAccess
      .createInstanceFor[HealthCheck](
        fqcn,
        immutable.Seq((classOf[ActorSystem], system)))
      .recoverWith {
        case _: NoSuchMethodException =>
          system.dynamicAccess.createInstanceFor[HealthCheck](fqcn, Nil)
      }
  }

  private def tryLoadJavaHealthCheck(fqcn: String): Try[HealthCheck] = {
    system.dynamicAccess
      .createInstanceFor[Supplier[CompletionStage[JBoolean]]](
        fqcn,
        immutable.Seq((classOf[ActorSystem], system)))
      .recoverWith {
        case _: NoSuchMethodException =>
          system.dynamicAccess.createInstanceFor[Supplier[CompletionStage[JBoolean]]](fqcn, Nil)
      }
      .map(convertSupplierToScala)
  }

  private def load(
      checks: immutable.Seq[NamedHealthCheck]): immutable.Seq[HealthCheck] = {
    checks
      .map(namedHealthCheck =>
        tryLoadScalaHealthCheck(namedHealthCheck.fullyQualifiedClassName).recoverWith {
          case _: ClassCastException =>
            tryLoadJavaHealthCheck(namedHealthCheck.fullyQualifiedClassName)
        })
      .map {
        case Success(c) => c
        case Failure(_: NoSuchMethodException) =>
          throw new InvalidHealthCheckException(
            s"Health checks: [${checks.mkString(",")}] must have a no args constructor or a single argument constructor that takes an ActorSystem")
        case Failure(_: ClassCastException) =>
          throw new InvalidHealthCheckException(
            s"Health checks: [${checks.mkString(",")}] must have type: () => Future[Boolean]")
        case Failure(c: ClassNotFoundException) =>
          throw new InvalidHealthCheckException(
            s"Health check: [${c.getMessage}] not found")
        case Failure(t) =>
          throw new InvalidHealthCheckException(
            "Uncaught exception from Health check construction",
            t)
      }
  }

  def startupResult(): Future[Either[String, Unit]] = {
    val result = check(startupChecks)
    result.onComplete {
      case Success(Right(())) =>
      case Success(Left(reason)) =>
        log.info(ManagementLogMarker.startupCheckFailed, reason)
      case Failure(e) =>
        log.warning(ManagementLogMarker.startupCheckFailed, e.getMessage)
    }
    result
  }

  def startup(): Future[Boolean] = startupResult().map(_.isRight)

  def readyResult(): Future[Either[String, Unit]] = {
    val result = check(readiness)
    result.onComplete {
      case Success(Right(())) =>
      case Success(Left(reason)) =>
        log.info(ManagementLogMarker.readinessCheckFailed, reason)
      case Failure(e) =>
        log.warning(ManagementLogMarker.readinessCheckFailed, e.getMessage)
    }
    result
  }

  def ready(): Future[Boolean] = readyResult().map(_.isRight)

  def aliveResult(): Future[Either[String, Unit]] = {
    val result = check(liveness)
    result.onComplete {
      case Success(Right(())) =>
      case Success(Left(reason)) =>
        log.info(ManagementLogMarker.livenessCheckFailed, reason)
      case Failure(e) =>
        log.warning(ManagementLogMarker.livenessCheckFailed, e.getMessage)
    }
    result
  }

  def alive(): Future[Boolean] = aliveResult().map(_.isRight)

  private def runCheck(check: HealthCheck): Future[Boolean] = {
    Future.fromTry(Try(check())).flatMap(identity)
  }

  private def check(checks: immutable.Seq[HealthCheck]): Future[Either[String, Unit]] = {
    val timeout = pekko.pattern.after(settings.checkTimeout, system.scheduler)(
      Future.failed(new RuntimeException) // will be enriched with which check timed out below
    )

    val spawnedChecks: Seq[Future[Either[String, Unit]]] = checks.map { check =>
      val checkName = check.getClass.getName
      Future.firstCompletedOf(
        Seq(
          timeout.recoverWith {
            case _: Throwable =>
              Future.failed(
                CheckTimeoutException(s"Check [$checkName] timed out after ${settings.checkTimeout}"))
          },
          runCheck(check)
            .map {
              case true  => Right(())
              case false => Left(s"Check [$checkName] not ok")
            }
            .recoverWith {
              case t: Throwable => Future.failed(CheckFailedException(s"Check [$checkName] failed: ${t.getMessage}", t))
            }))
    }

    Future.sequence(spawnedChecks).map { completedChecks =>
      completedChecks.collectFirst { case Left(failure) => failure } match {
        case Some(notOk) => Left(notOk)
        case None        => Right(())
      }
    }
  }
}
