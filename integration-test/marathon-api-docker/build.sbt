import com.typesafe.sbt.packager.docker._

name := "bootstrap-demo-marathon-api-docker"

scalaVersion := "2.13.10"

enablePlugins(JavaServerAppPackaging)

dockerUsername := sys.env.get("DOCKER_USER")
def pekkoManagementVersion(version: String) = version.split('+')(0)

libraryDependencies ++= Seq(
  "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion(
    version.value),
  "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion(
    version.value),
  "org.apache.pekko" %% "pekko-discovery-marathon-api" % pekkoManagementVersion(
    version.value))
