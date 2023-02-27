import com.typesafe.sbt.packager.docker.{ Cmd, ExecCmd }
import sbt.Keys.parallelExecution

ThisBuild / resolvers += Resolver.jcenterRepo

// root
lazy val root = project
  .in(file("."))
  .enablePlugins(ScalaUnidocPlugin)
  .disablePlugins(MimaPlugin)
  .aggregate(
    // When this aggregate is updated the list of modules in ManifestInfo.checkSameVersion
    // in management should also be updated
    discoveryAwsApi,
    discoveryAwsApiAsync,
    discoveryConsul,
    discoveryKubernetesApi,
    discoveryMarathonApi,
    management,
    managementPki,
    managementClusterHttp,
    managementClusterBootstrap,
    managementLoglevelsLogback,
    managementLoglevelsLog4j2,
    integrationTestAwsApiEc2TagBased,
    integrationTestLocal,
    integrationTestAwsApiEcs,
    integrationTestKubernetesApi,
    integrationTestKubernetesApiJava,
    integrationTestKubernetesDns,
    integrationTestMarathonApiDocker,
    leaseKubernetes,
    leaseKubernetesIntTest,
    docs)
  .settings(
    GlobalScope / parallelExecution := false,
    publish / skip := true)

lazy val mimaPreviousArtifactsSet = mimaPreviousArtifacts := Set.empty // temporarily disable mima checks

lazy val discoveryKubernetesApi = pekkoModule("discovery-kubernetes-api")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-discovery-kubernetes-api",
    libraryDependencies := Dependencies.DiscoveryKubernetesApi,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(managementPki)

lazy val discoveryMarathonApi = pekkoModule("discovery-marathon-api")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-discovery-marathon-api",
    libraryDependencies := Dependencies.DiscoveryMarathonApi,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)

lazy val discoveryAwsApi = pekkoModule("discovery-aws-api")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-discovery-aws-api",
    libraryDependencies := Dependencies.DiscoveryAwsApi,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)

lazy val discoveryAwsApiAsync = pekkoModule("discovery-aws-api-async")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-discovery-aws-api-async",
    libraryDependencies := Dependencies.DiscoveryAwsApiAsync,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)

lazy val discoveryConsul = pekkoModule("discovery-consul")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-discovery-consul",
    libraryDependencies := Dependencies.DiscoveryConsul,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)

// gathers all enabled routes and serves them (HTTP or otherwise)
lazy val management = pekkoModule("management")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-management",
    libraryDependencies := Dependencies.ManagementHttp,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)

lazy val managementPki = pekkoModule("management-pki")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-management-pki",
    libraryDependencies := Dependencies.ManagementPki,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)

lazy val managementLoglevelsLogback = pekkoModule("management-loglevels-logback")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-management-loglevels-logback",
    libraryDependencies := Dependencies.LoglevelsLogback,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(management)

lazy val managementLoglevelsLog4j2 = pekkoModule("management-loglevels-log4j2")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .disablePlugins(MimaPlugin)
  .settings(
    name := "pekko-management-loglevels-log4j2",
    libraryDependencies := Dependencies.LoglevelsLog4j2)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(management)

lazy val managementClusterHttp = pekkoModule("management-cluster-http")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-management-cluster-http",
    libraryDependencies := Dependencies.ClusterHttp,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(management)

lazy val managementClusterBootstrap = pekkoModule("management-cluster-bootstrap")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-management-cluster-bootstrap",
    libraryDependencies := Dependencies.ClusterBootstrap,
    mimaPreviousArtifactsSet)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(management)

lazy val leaseKubernetes = pekkoModule("lease-kubernetes")
  .enablePlugins(AutomateHeaderPlugin)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .settings(
    name := "pekko-lease-kubernetes",
    libraryDependencies := Dependencies.LeaseKubernetes,
    mimaPreviousArtifactsSet)
  .settings(Defaults.itSettings)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .configs(IntegrationTest)
  .dependsOn(managementPki)

lazy val leaseKubernetesIntTest = pekkoModule("lease-kubernetes-int-test")
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(leaseKubernetes)
  .enablePlugins(AutomateHeaderPlugin)
  .disablePlugins(MimaPlugin)
  .settings(
    name := "pekko-lease-kubernetes-int-test",
    publish / skip := true,
    libraryDependencies := Dependencies.LeaseKubernetesTest,
    version ~= (_.replace('+', '-')),
    dockerBaseImage := "openjdk:8-jre-alpine",
    dockerUpdateLatest := true,
    dockerCommands := dockerCommands.value.flatMap {
      case ExecCmd("ENTRYPOINT", args @ _*) => Seq(Cmd("ENTRYPOINT", args.mkString(" ")))
      case v                                => Seq(v)
    },
    dockerCommands ++= Seq(
      Cmd("USER", "root"),
      Cmd("RUN", "chgrp -R 0 . && chmod -R g=u ."),
      Cmd("RUN", "/sbin/apk", "add", "--no-cache", "bash", "bind-tools", "busybox-extras", "curl", "strace"),
      Cmd("RUN", "chmod +x /opt/docker/bin/pekko-lease-kubernetes-int-test")))
  .settings(MetaInfLicenseNoticeCopy.settings)

lazy val integrationTestKubernetesApi = pekkoIntTestModule("kubernetes-api")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    publish / skip := true,
    doc / sources := Seq.empty,
    libraryDependencies := Dependencies.BootstrapDemos)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(management, managementClusterHttp, managementClusterBootstrap, discoveryKubernetesApi)

lazy val integrationTestKubernetesApiJava = pekkoIntTestModule("kubernetes-api-java")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    publish / skip := true,
    doc / sources := Seq.empty,
    libraryDependencies := Dependencies.BootstrapDemos)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap,
    discoveryKubernetesApi)

lazy val integrationTestKubernetesDns = pekkoIntTestModule("kubernetes-dns")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    publish / skip := true,
    doc / sources := Seq.empty,
    libraryDependencies := Dependencies.BootstrapDemos)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap)

lazy val integrationTestAwsApiEc2TagBased = pekkoIntTestModule("aws-api-ec2")
  .configs(IntegrationTest)
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    publish / skip := true,
    doc / sources := Seq.empty,
    Defaults.itSettings)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(
    management,
    managementClusterHttp,
    discoveryAwsApi,
    managementClusterBootstrap)

lazy val integrationTestMarathonApiDocker = pekkoIntTestModule("marathon-api-docker")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "integration-test-marathon-api-docker",
    publish / skip := true,
    doc / sources := Seq.empty)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap,
    discoveryMarathonApi)

lazy val integrationTestAwsApiEcs = pekkoIntTestModule("aws-api-ecs")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    publish / skip := true,
    doc / sources := Seq.empty)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap,
    discoveryAwsApiAsync)
  .enablePlugins(JavaAppPackaging, AshScriptPlugin, DockerPlugin)
  .settings(
    dockerBaseImage := "openjdk:10-jre-slim",
    Docker / com.typesafe.sbt.SbtNativePackager.autoImport.packageName := "ecs-integration-test-app",
    Docker / version := "1.0")

lazy val integrationTestLocal = pekkoIntTestModule("local")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "integration-test-local",
    publish / skip := true,
    doc / sources := Seq.empty,
    libraryDependencies := Dependencies.BootstrapDemos)
  .settings(MetaInfLicenseNoticeCopy.settings)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap)
  .enablePlugins(JavaAppPackaging, AshScriptPlugin)

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(AkkaParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin, PublishRsyncPlugin)
  .disablePlugins(MimaPlugin)
  .settings(
    name := "Pekko Management",
    publish / skip := true,
    makeSite := makeSite.dependsOn(LocalRootProject / ScalaUnidoc / doc).value,
    Preprocess / siteSubdirName := s"api/pekko-management/${if (isSnapshot.value) "snapshot" else version.value}",
    Preprocess / sourceDirectory := (LocalRootProject / ScalaUnidoc / unidoc / target).value,
    Preprocess / preprocessRules := Seq(
      ("\\.java\\.scala".r, _ => ".java")),
    previewPath := (Paradox / siteSubdirName).value,
    paradoxGroups := Map("Language" -> Seq("Java", "Scala")),
    Paradox / siteSubdirName := s"docs/pekko-management/${if (isSnapshot.value) "snapshot" else version.value}",
    Compile / paradoxProperties ++= Map(
      "date.year" -> Common.currentYear,
      "project.url" -> "https://pekko.apache.org/docs/pekko-management/current/",
      "canonical.base_url" -> "https://pekko.apache.org/docs/pekko-management/current",
      "scala.binary.version" -> scalaBinaryVersion.value,
      "akka.version" -> Dependencies.AkkaVersion,
      "extref.akka.base_url" -> s"https://pekko.apache.org/docs/pekko/current/%s",
      "scaladoc.akka.base_url" -> s"https://pekko.apache.org/api/pekko/current/",
      "extref.akka-http.base_url" -> s"https://pekko.apache.org/docs/pekko-http/${Dependencies.AkkaHttpBinaryVersion}/%s",
      "scaladoc.akka.http.base_url" -> s"https://pekko.apache.org/api/pekko-http/${Dependencies.AkkaHttpBinaryVersion}/",
      "extref.akka-grpc.base_url" -> s"https://pekko.apache.org/docs/pekko-grpc/current/%s",
      "extref.akka-enhancements.base_url" -> s"https://pekko.apache.org/docs/pekko-enhancements/current/%s",
      "scaladoc.akka.management.base_url" -> s"/${(Preprocess / siteSubdirName).value}/"),
    publishRsyncArtifacts += makeSite.value -> "www/",
    publishRsyncHost := "akkarepo@gustav.akka.io")

def pekkoModule(moduleName: String): Project =
  Project(id = moduleName, base = file(moduleName))

def pekkoIntTestModule(moduleName: String): Project =
  Project(id = s"integration-test-$moduleName", base = file(s"integration-test/$moduleName"))

TaskKey[Unit]("verifyCodeFmt") := {
  scalafmtCheckAll.all(ScopeFilter(inAnyProject)).result.value.toEither.left.foreach { _ =>
    throw new MessageOnlyException(
      "Unformatted Scala code found. Please run 'scalafmtAll' and commit the reformatted code")
  }
  (Compile / scalafmtSbtCheck).result.value.toEither.left.foreach { _ =>
    throw new MessageOnlyException(
      "Unformatted sbt code found. Please run 'scalafmtSbt' and commit the reformatted code")
  }
}

addCommandAlias("verifyCodeStyle", "headerCheckAll; verifyCodeFmt")
