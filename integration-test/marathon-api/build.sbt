enablePlugins(JavaServerAppPackaging)

name := "bootstrap-demo-marathon-api"

version := "1.1.4"

scalaVersion := "2.12.16"

val akkaManagementVersion = "1.10.0"

libraryDependencies ++= Vector(
  "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % akkaManagementVersion,
  "com.lightbend.akka.management" %% "akka-management-cluster-http" % akkaManagementVersion,
  "com.lightbend.akka.discovery" %% "akka-discovery-marathon-api" % akkaManagementVersion)
