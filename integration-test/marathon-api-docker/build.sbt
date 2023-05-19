import com.typesafe.sbt.packager.docker._

name := "bootstrap-demo-marathon-api-docker"

version := "1.1.4"

scalaVersion := "2.13.10"

enablePlugins(JavaServerAppPackaging)

dockerUsername := sys.env.get("DOCKER_USER")

val pekkoManagementVersion = "1.1.4"

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion,
  "org.apache.pekko" %% "pekko-discovery-marathon-api" % pekkoManagementVersion)
