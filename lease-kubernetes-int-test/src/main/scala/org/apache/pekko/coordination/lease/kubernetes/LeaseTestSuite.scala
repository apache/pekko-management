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

import org.apache.pekko.actor.{ ActorSystem, CoordinatedShutdown }
import org.scalatest.{ Args, Filter, Reporter, Stopper }
import org.scalatest.events.{ Event, TestFailed }

import scala.util.{ Failure, Success, Try }

object LeaseTestSuite {

  def main(args: Array[String]): Unit = {
    val as = ActorSystem("LeaseTestSuite")
    val log = as.log
    log.info("Running test")

    val leaseSpec = new LeaseSpec {
      override def system: ActorSystem = as
    }
    @volatile var failed = false

    val reporter = new Reporter() {
      override def apply(event: Event): Unit =
        event match {
          case tf: TestFailed =>
            failed = true
            log.error("TestFailed({}): {}", tf.testName, tf.message)
          case _ =>
        }
    }

    val testSuite = Try(leaseSpec.run(None, Args(reporter, Stopper.default, Filter())))
    log.info("Test complete {}", testSuite)
    testSuite match {
      case Success(_) if !failed =>
        log.info("Test succeeded")
        CoordinatedShutdown(as).run(TestPassedReason)
      case Success(_) if failed =>
        log.info("Test failed, see the logs")
        CoordinatedShutdown(as).run(TestFailedReason)
      case Failure(exception) =>
        log.error(exception, "Test exception")
        CoordinatedShutdown(as).run(TestFailedReason)
    }
  }

}
