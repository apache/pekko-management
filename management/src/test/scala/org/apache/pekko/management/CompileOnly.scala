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

package org.apache.pekko.management

import org.apache.pekko
import pekko.actor.ActorSystem
import pekko.http.scaladsl.server.directives.Credentials
import pekko.management.scaladsl.PekkoManagement

import scala.concurrent.{ ExecutionContext, Future }

object CompileOnly {
  val system: ActorSystem = null
  implicit val ec: ExecutionContext = system.dispatcher

  // #basic-auth
  def myUserPassAuthenticator(credentials: Credentials): Future[Option[String]] =
    credentials match {
      case p @ Credentials.Provided(id) =>
        Future {
          // potentially
          if (p.verify("p4ssw0rd")) Some(id)
          else None
        }
      case _ => Future.successful(None)
    }
  // ...
  val management = PekkoManagement(system)
  management.start(_.withAuth(myUserPassAuthenticator))
  // #basic-auth

  object stopping {
    // #stopping
    val management = PekkoManagement(system)
    management.start()
    // ...
    val bindingFuture = management.stop()
    bindingFuture.onComplete { _ =>
      println("It's stopped")
    }
    // #stopping
  }
}
