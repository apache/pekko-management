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
  val scala212Version = "2.12.19"
  val scala213Version = "2.13.13"
  val scala3Version = "3.3.3"
  val crossScalaVersions = Seq(scala212Version, scala213Version, scala3Version)

  val pekkoVersion = PekkoCoreDependency.version
  val pekkoBinaryVersion = pekkoVersion.take(3)
  val pekkoHttpVersion = PekkoHttpDependency.version
  val pekkoHttpBinaryVersion = pekkoHttpVersion.take(3)

  val scalaTestVersion = "3.2.14"
  val scalaTestPlusJUnitVersion = scalaTestVersion + ".0"

  val awsSdkVersion = "1.12.688"
  val jacksonVersion = "2.14.3"

  val log4j2Version = "2.17.2"
  val log4j2Slf4j2Version = "2.23.1"
  val logbackVersion = "1.2.13"
  val logbackSlf4j2Version = "1.3.14"
  val slf4j2Version = "2.0.12"

  // often called-in transitively with insecure versions of databind / core
  private val jacksonDatabind = Seq(
    "com.fasterxml.jackson.core" % "jackson-databind" % jacksonVersion)

  private val jacksonDatatype = Seq(
    "com.fasterxml.jackson.datatype" % "jackson-datatype-guava" % jacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % jacksonVersion,
    // Specifying guava dependency because older transitive dependency has security vulnerability
    "com.google.guava" % "guava" % "31.1-jre")

  val discoveryConsul = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "com.orbitz.consul" % "consul-client" % "1.5.3",
    "com.pszymczyk.consul" % "embedded-consul" % "2.2.1" % Test,
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
    ("software.amazon.awssdk" % "ecs" % "2.17.184").exclude("software.amazon.awssdk", "apache-client"),
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
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test)

  val managementLoglevelsLogbackSlf4j2Overrides = if (Common.testWithSlf4J2) {
    Seq(
      "org.slf4j" % "slf4j-api" % slf4j2Version,
      "ch.qos.logback" % "logback-classic" % logbackSlf4j2Version % Test)
  } else {
    Seq.empty
  }

  val managementLoglevelsLog4j2 = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.apache.logging.log4j" % "log4j-core" % log4j2Version,
    "org.apache.logging.log4j" % "log4j-api" % log4j2Version,
    "org.apache.logging.log4j" % "log4j-slf4j-impl" % log4j2Version,
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test)

  val managementLoglevelsLog4j2Slf4j2 = Seq(
    "org.apache.pekko" %% "pekko-actor" % pekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % pekkoVersion,
    "org.slf4j" % "slf4j-api" % slf4j2Version,
    "org.apache.logging.log4j" % "log4j-core" % log4j2Slf4j2Version,
    "org.apache.logging.log4j" % "log4j-api" % log4j2Slf4j2Version,
    "org.apache.logging.log4j" % "log4j-slf4j2-impl" % log4j2Slf4j2Version,
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
    "org.mockito" % "mockito-all" % "1.10.19" % Test,
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
    "com.github.tomakehurst" % "wiremock-jre8" % "2.33.2" % Test,
    "org.scalatest" %% "scalatest" % scalaTestVersion % "it,test",
    "org.scalatestplus" %% "junit-4-13" % scalaTestPlusJUnitVersion % "it,test",
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % "it,test")

  val leaseKubernetesTest = Seq(
    "org.scalatest" %% "scalatest" % scalaTestVersion)

  val bootstrapDemos = Seq(
    "org.apache.pekko" %% "pekko-discovery" % pekkoVersion,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-slf4j" % pekkoVersion,
    "ch.qos.logback" % "logback-classic" % logbackVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test)

}
