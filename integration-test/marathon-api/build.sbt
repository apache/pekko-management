/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

enablePlugins(JavaServerAppPackaging)

name := "bootstrap-demo-marathon-api"

version := "1.1.4"

scalaVersion := "2.13.11"

val pekkoManagementVersion = "1.10.0"

libraryDependencies ++= Vector(
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion,
  "org.apache.pekko" %% "pekko-discovery-marathon-api" % pekkoManagementVersion)
