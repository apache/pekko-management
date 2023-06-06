enablePlugins(JavaAppPackaging)
name := "bootstrap-demo-dns-api"

scalaVersion := "2.13.11"

def pekkoManagementVersion(version: String) = version.split('+')(0)

libraryDependencies += "org.apache.pekko" %% "pekko-management-cluster-bootstrap" % pekkoManagementVersion(
  version.value)

libraryDependencies += "org.apache.pekko" %% "pekko-management-cluster-http" % pekkoManagementVersion(
  version.value)

libraryDependencies += "org.apache.pekko" %% "pekko-discovery" % "2.5.20"
