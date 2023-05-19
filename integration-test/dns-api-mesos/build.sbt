enablePlugins(JavaAppPackaging)
name := "bootstrap-demo-dns-api"

scalaVersion := "2.13.10"

def akkaManagementVersion(version: String) = version.split('+')(0)

libraryDependencies += "com.lightbend.akka.management" %% "pekko-management-cluster-bootstrap" % akkaManagementVersion(
  version.value)

libraryDependencies += "com.lightbend.akka.management" %% "pekko-management-cluster-http" % akkaManagementVersion(
  version.value)

libraryDependencies += "org.apache.pekko" %% "pekko-discovery" % "2.5.20"
