/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

import com.typesafe.sbt.packager.docker._

name := "bootstrap-demo-marathon-api-docker"

scalaVersion := "2.13.15"

enablePlugins(JavaServerAppPackaging)

dockerUsername := sys.env.get("DOCKER_USER")
def pekkoManagementVersion(version: String) = version.split('+')(0)

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion(
    version.value),
  "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion(
    version.value),
  "org.apache.pekko" %% "pekko-discovery-marathon-api" % pekkoManagementVersion(
    version.value))
