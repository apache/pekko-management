/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

enablePlugins(JavaAppPackaging)
name := "bootstrap-demo-dns-api"

scalaVersion := "2.13.16"

def pekkoManagementVersion(version: String) = version.split('+')(0)

libraryDependencies += "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion(
  version.value)

libraryDependencies += "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion(
  version.value)

libraryDependencies += "org.apache.pekko" %% "pekko-discovery" % "1.0.2"
