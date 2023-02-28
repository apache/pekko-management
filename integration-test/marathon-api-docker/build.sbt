import com.typesafe.sbt.packager.docker._

name := "bootstrap-demo-marathon-api-docker"

version := "1.1.4"

scalaVersion := "2.12.16"

enablePlugins(JavaServerAppPackaging)

dockerUsername := sys.env.get("DOCKER_USER")

val akkaManagementVersion = "1.1.4"

libraryDependencies ++= Vector(
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % akkaManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-http" % akkaManagementVersion,
  "org.apache.pekko" %% "pekko-discovery-marathon-api" % akkaManagementVersion)
