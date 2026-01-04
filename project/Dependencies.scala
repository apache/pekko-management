/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

import sbt._

object Dependencies {
  // keep in sync with .github/workflows/unit-tests.yml
  val scala213Version = "2.13.18"
  val scala3Version = "3.3.7"
  val crossScalaVersions = Seq(scala213Version, scala3Version)

  val pekkoVersion = PekkoCoreDependency.version
  val pekkoBinaryVersion = PekkoCoreDependency.default.link
  val pekkoHttpVersion = PekkoHttpDependency.version
  val pekkoHttpBinaryVersion = PekkoHttpDependency.default.link

  val scalaTestVersion = "3.2.19"
  val scalaTestPlusJUnitVersion = scalaTestVersion + ".0"

  val awsSdkVersion = "1.12.797"
  val guavaVersion = "33.5.0-jre"
  val jacksonVersion = "2.20.1"

  val log4j2Version = "2.25.3"
  val logbackVersion = "1.5.23"
  val slf4jVersion = "2.0.17"

  // often called-in transitively with insecure versions of databind / core
  private val jacksonDatabind = Seq(
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion)

  private val jacksonDatatype = Seq(
    "com.fasterxml.jackson.datatype" % "jackson-datatype-guava" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
    // Specifying guava dependency because older transitive dependency has security vulnerability
    "com.google.guava" % "guava" % guavaVersion)

  private val wireMockDependencies = Seq(
    "org.wiremock" % "wiremock" % "3.13.2" % Test)

  val discoveryConsul = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.kiwiproject" % "consul-client" % "1.9.0",
    "org.testcontainers" % "testcontainers-consul" % "2.0.3" % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion % Test,
    "ch.qos.logback" % "logback-classic" % logbackVersion % Test) ++ jacksonDatabind ++ jacksonDatatype // consul depends on insecure version of jackson

  val discoveryKubernetesApi = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test)

  val discoveryMarathonApi = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test)

  val discoveryAwsApi = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "com.amazonaws" % "aws-java-sdk-ec2" % awsSdkVersion,
    "com.amazonaws" % "aws-java-sdk-ecs" % awsSdkVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test) ++ jacksonDatabind // aws-java-sdk depends on insecure version of jackson

  val discoveryAwsApiAsync = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    ("software.amazon.awssdk" % "ecs" % "2.40.17").exclude("software.amazon.awssdk", "apache-client"),
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test) ++ jacksonDatabind // aws-java-sdk depends on insecure version of jackson

  val managementHttp = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-cluster" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % scalaTestPlusJUnitVersion % Test)

  val managementPki = Seq(
    "org.apache.pekko" %% "pekko-pki" % pekkoVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % scalaTestPlusJUnitVersion % Test)

  val managementLoglevelsLogback = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.slf4j" % "slf4j-api" % slf4jVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test)

  val managementLoglevelsLog4j2 = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.slf4j" % "slf4j-api" % slf4jVersion,
    "org.apache.logging.log4j" % "log4j-core" % log4j2Version,
    "org.apache.logging.log4j" % "log4j-api" % log4j2Version,
    "org.apache.logging.log4j" % "log4j-slf4j2-impl" % log4j2Version,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test)

  val managementClusterHttp = Seq(
    "org.apache.pekko" %% "pekko-cluster" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-sharding" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http-core" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.mockito" % "mockito-core" % "5.21.0" % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
    "org.apache.pekko" %% "pekko-distributed-data" % pekkoVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % scalaTestPlusJUnitVersion % Test)

  val managementClusterBootstrap = Seq(
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.apache.pekko" %% "pekko-cluster" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http-core" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test,
    "org.apache.pekko" %% "pekko-distributed-data" % pekkoVersion % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % scalaTestPlusJUnitVersion % Test)

  val leaseKubernetes = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-coordination" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % scalaTestPlusJUnitVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test) ++
    wireMockDependencies

  val leaseKubernetesTest = Seq(
    "org.scalatest" %% "scalatest" % scalaTestVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test)

  val bootstrapDemos = Seq(
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test)

}
