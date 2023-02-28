import sbt._
import Keys._

object Dependencies {

  val Scala212 = "2.12.16"
  val Scala213 = "2.13.8"
  val CrossScalaVersions = Seq(Dependencies.Scala212, Dependencies.Scala213)

  // Align the versions in integration-test/kubernetes-api-java/pom.xml
  val PekkoVersion = "0.0.0+26599-83545a33-SNAPSHOT"
  val AkkaBinary = "2.6"
  val PekkoHttpVersion = "0.0.0+4298-26846a02-SNAPSHOT"
  val AkkaHttpBinaryVersion = "10.2"

  val ScalaTestVersion = "3.1.4"
  val ScalaTestPlusJUnitVersion = ScalaTestVersion + ".0"

  val AwsSdkVersion = "1.12.210"
  val JacksonVersion = "2.11.4"

  val Log4j2Version = "2.17.2"

  // often called-in transitively with insecure versions of databind / core
  private val JacksonDatabind = Seq(
    "com.fasterxml.jackson.core" % "jackson-databind" % JacksonVersion)

  private val JacksonDatatype = Seq(
    "com.fasterxml.jackson.datatype" % "jackson-datatype-guava" % JacksonVersion,
    "com.fasterxml.jackson.datatype" % "jackson-datatype-jdk8" % JacksonVersion,
    // Specifying guava dependency because older transitive dependency has security vulnerability
    "com.google.guava" % "guava" % "31.1-jre")

  val DiscoveryConsul = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
    "com.orbitz.consul" % "consul-client" % "1.5.3",
    "com.pszymczyk.consul" % "embedded-consul" % "2.2.1" % Test,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test,
    "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion % Test,
    "ch.qos.logback" % "logback-classic" % "1.2.11" % Test) ++ JacksonDatabind ++ JacksonDatatype // consul depends on insecure version of jackson

  val DiscoveryKubernetesApi = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test)

  val DiscoveryMarathonApi = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test)

  val DiscoveryAwsApi = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
    "com.amazonaws" % "aws-java-sdk-ec2" % AwsSdkVersion,
    "com.amazonaws" % "aws-java-sdk-ecs" % AwsSdkVersion,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test) ++ JacksonDatabind // aws-java-sdk depends on insecure version of jackson

  val DiscoveryAwsApiAsync = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    ("software.amazon.awssdk" % "ecs" % "2.17.184").exclude("software.amazon.awssdk", "apache-client"),
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test) ++ JacksonDatabind // aws-java-sdk depends on insecure version of jackson

  val ManagementHttp = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test,
    "org.apache.pekko" %% "pekko-cluster" % PekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % ScalaTestPlusJUnitVersion % Test)

  val ManagementPki = Seq(
    "org.apache.pekko" %% "pekko-pki" % PekkoVersion,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % ScalaTestPlusJUnitVersion % Test)

  val LoglevelsLogback = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
    "ch.qos.logback" % "logback-classic" % "1.2.11",
    "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test)

  val LoglevelsLog4j2 = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
    "org.apache.logging.log4j" % "log4j-core" % Log4j2Version,
    "org.apache.logging.log4j" % "log4j-api" % Log4j2Version,
    "org.apache.logging.log4j" % "log4j-slf4j-impl" % Log4j2Version,
    "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
    "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test)

  val ClusterHttp = Seq(
    "org.apache.pekko" %% "pekko-cluster" % PekkoVersion,
    "org.apache.pekko" %% "pekko-cluster-sharding" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http-core" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test,
    "org.mockito" % "mockito-all" % "1.10.19" % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test,
    "org.apache.pekko" %% "pekko-distributed-data" % PekkoVersion % Test,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % ScalaTestPlusJUnitVersion % Test)

  val ClusterBootstrap = Seq(
    "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
    "org.apache.pekko" %% "pekko-cluster" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http-core" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test,
    "org.apache.pekko" %% "pekko-http-testkit" % PekkoHttpVersion % Test,
    "org.apache.pekko" %% "pekko-distributed-data" % PekkoVersion % Test,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test,
    "org.scalatestplus" %% "junit-4-13" % ScalaTestPlusJUnitVersion % Test)

  val LeaseKubernetes = Seq(
    "org.apache.pekko" %% "pekko-actor" % PekkoVersion,
    "org.apache.pekko" %% "pekko-coordination" % PekkoVersion,
    "org.apache.pekko" %% "pekko-stream" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http" % PekkoHttpVersion,
    "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion,
    "org.apache.pekko" %% "pekko-http-spray-json" % PekkoHttpVersion,
    "com.github.tomakehurst" % "wiremock-jre8" % "2.33.2" % Test,
    "org.scalatest" %% "scalatest" % ScalaTestVersion % "it,test",
    "org.scalatestplus" %% "junit-4-13" % ScalaTestPlusJUnitVersion % "it,test",
    "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % "it,test")

  val LeaseKubernetesTest = Seq(
    "org.scalatest" %% "scalatest" % ScalaTestVersion)

  val BootstrapDemos = Seq(
    "org.apache.pekko" %% "pekko-discovery" % PekkoVersion,
    "org.apache.pekko" %% "pekko-testkit" % PekkoVersion % Test,
    "org.apache.pekko" %% "pekko-slf4j" % PekkoVersion,
    "ch.qos.logback" % "logback-classic" % "1.2.11",
    "org.scalatest" %% "scalatest" % ScalaTestVersion % Test)

}
