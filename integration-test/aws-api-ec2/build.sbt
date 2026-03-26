/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

enablePlugins(JavaAppPackaging)

Universal / packageName := "app" // should produce app.zip

libraryDependencies += ("software.amazon.awssdk" % "cloudformation" % Dependencies.awsSdkV2Version).exclude(
  "software.amazon.awssdk", "apache-client") % Test

libraryDependencies += ("software.amazon.awssdk" % "autoscaling" % Dependencies.awsSdkV2Version).exclude(
  "software.amazon.awssdk", "apache-client") % Test

libraryDependencies += "com.fasterxml.jackson.core" % "jackson-databind" % "2.14.3"

libraryDependencies += "org.scalatest" %% "scalatest" % Dependencies.scalaTestVersion % Test
