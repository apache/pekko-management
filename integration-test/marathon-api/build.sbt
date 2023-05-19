enablePlugins(JavaServerAppPackaging)

name := "bootstrap-demo-marathon-api"

version := "1.1.4"

scalaVersion := "2.13.10"

val akkaManagementVersion = "1.10.0"

libraryDependencies ++= Vector(
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % akkaManagementVersion,
  "org.apache.pekko" %% "pekko-management-cluster-http" % akkaManagementVersion,
  "org.apache.pekko" %% "pekko-discovery-marathon-api" % akkaManagementVersion)
