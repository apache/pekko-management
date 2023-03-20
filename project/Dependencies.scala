import sbt._
import Keys._

object Dependencies {
  // keep in sync with .github/workflows/unit-tests.yml
  val scala212Version = "2.12.17"
  val scala213Version = "2.13.10"
  val scala3Version = "3.1.2" // not yet enabled - missing pekko-http Scala 3 artifacts
  val crossScalaVersions = Seq(scala212Version, scala213Version)

  // Align the versions in integration-test/kubernetes-api-java/pom.xml
  val pekkoVersion = "0.0.0+26621-44d03df6-SNAPSHOT"
  val pekkoHttpVersion = "0.0.0+4298-26846a02-SNAPSHOT"

  val scalaTestVersion = "3.2.14"
  val scalaTestPlusJUnitVersion = scalaTestVersion + ".0"

  val awsSdkVersion = "1.12.210"
  val jacksonVersion = "2.11.4"

  val log4j2Version = "2.17.2"

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
    "ch.qos.logback" % "logback-classic" % "1.2.11" % Test) ++ jacksonDatabind ++ jacksonDatatype // consul depends on insecure version of jackson

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
    "ch.qos.logback" % "logback-classic" % "1.2.11",
    "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % pekkoHttpVersion,
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % pekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % pekkoHttpVersion % Test)

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
    "ch.qos.logback" % "logback-classic" % "1.2.11",
    "org.scalatest" %% "scalatest" % scalaTestVersion % Test)

}
