import com.typesafe.sbt.packager.docker._

name := "bootstrap-demo-marathon-api-docker"

version := "1.1.4"

scalaVersion := "2.12.16"

enablePlugins(JavaServerAppPackaging)

dockerUsername := sys.env.get("DOCKER_USER")

val akkaManagementVersion = "1.1.4"

libraryDependencies ++= Vector(
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaManagementVersion,
  "com.lightbend.akka.discovery" %% "akka-discovery-marathon-api" % akkaManagementVersion)
