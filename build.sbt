/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

import com.typesafe.sbt.packager.docker.{ Cmd, ExecCmd }
import sbt.Keys.parallelExecution

ThisBuild / apacheSonatypeProjectProfile := "pekko"
ThisBuild / versionScheme := Some(VersionScheme.SemVerSpec)
sourceDistName := "apache-pekko-management"
sourceDistIncubating := true

commands := commands.value.filterNot { command =>
  command.nameOption.exists { name =>
    name.contains("sonatypeRelease") || name.contains("sonatypeBundleRelease")
  }
}

ThisBuild / resolvers += Resolver.jcenterRepo
// TODO: Remove when Pekko has a proper release
ThisBuild / resolvers += Resolver.ApacheMavenSnapshotsRepo
ThisBuild / resolvers += "Apache Pekko Staging".at("https://repository.apache.org/content/groups/staging")
ThisBuild / updateOptions := updateOptions.value.withLatestSnapshots(false)

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
    name := "pekko-management-root",
    GlobalScope / parallelExecution := false)
  .enablePlugins(NoPublish)

lazy val mimaPreviousArtifactsSet = mimaPreviousArtifacts := Set.empty // temporarily disable mima checks

lazy val discoveryKubernetesApi = pekkoModule("discovery-kubernetes-api")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-discovery-kubernetes-api",
    libraryDependencies := Dependencies.discoveryKubernetesApi,
    mimaPreviousArtifactsSet)
  .dependsOn(managementPki)

lazy val discoveryMarathonApi = pekkoModule("discovery-marathon-api")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-discovery-marathon-api",
    libraryDependencies := Dependencies.discoveryMarathonApi,
    mimaPreviousArtifactsSet)

lazy val discoveryAwsApi = pekkoModule("discovery-aws-api")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-discovery-aws-api",
    libraryDependencies := Dependencies.discoveryAwsApi,
    mimaPreviousArtifactsSet)

lazy val discoveryAwsApiAsync = pekkoModule("discovery-aws-api-async")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-discovery-aws-api-async",
    libraryDependencies := Dependencies.discoveryAwsApiAsync,
    mimaPreviousArtifactsSet)

lazy val discoveryConsul = pekkoModule("discovery-consul")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-discovery-consul",
    libraryDependencies := Dependencies.discoveryConsul,
    mimaPreviousArtifactsSet)

// gathers all enabled routes and serves them (HTTP or otherwise)
lazy val management = pekkoModule("management")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-management",
    libraryDependencies := Dependencies.managementHttp,
    mimaPreviousArtifactsSet)

lazy val managementPki = pekkoModule("management-pki")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-management-pki",
    libraryDependencies := Dependencies.managementPki,
    mimaPreviousArtifactsSet)

lazy val managementLoglevelsLogback = pekkoModule("management-loglevels-logback")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-management-loglevels-logback",
    libraryDependencies := Dependencies.managementLoglevelsLogback,
    mimaPreviousArtifactsSet)
  .dependsOn(management)

lazy val managementLoglevelsLog4j2 = pekkoModule("management-loglevels-log4j2")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .disablePlugins(MimaPlugin)
  .settings(
    name := "pekko-management-loglevels-log4j2",
    libraryDependencies := Dependencies.managementLoglevelsLog4j2)
  .dependsOn(management)

lazy val managementClusterHttp = pekkoModule("management-cluster-http")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-management-cluster-http",
    libraryDependencies := Dependencies.managementClusterHttp,
    mimaPreviousArtifactsSet)
  .dependsOn(management)

lazy val managementClusterBootstrap = pekkoModule("management-cluster-bootstrap")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-management-cluster-bootstrap",
    libraryDependencies := Dependencies.managementClusterBootstrap,
    mimaPreviousArtifactsSet)
  .dependsOn(management)

lazy val leaseKubernetes = pekkoModule("lease-kubernetes")
  .enablePlugins(AutomateHeaderPlugin, ReproducibleBuildsPlugin)
  .settings(
    name := "pekko-lease-kubernetes",
    libraryDependencies := Dependencies.leaseKubernetes,
    mimaPreviousArtifactsSet)
  .settings(Defaults.itSettings)
  .configs(IntegrationTest)
  .dependsOn(managementPki)

lazy val leaseKubernetesIntTest = pekkoModule("lease-kubernetes-int-test")
  .enablePlugins(JavaAppPackaging, DockerPlugin)
  .dependsOn(leaseKubernetes)
  .enablePlugins(AutomateHeaderPlugin)
  .disablePlugins(MimaPlugin)
  .settings(
    name := "pekko-lease-kubernetes-int-test",
    libraryDependencies := Dependencies.leaseKubernetesTest,
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
  .enablePlugins(NoPublish)

lazy val integrationTestKubernetesApi = pekkoIntTestModule("kubernetes-api")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    libraryDependencies := Dependencies.bootstrapDemos)
  .dependsOn(management, managementClusterHttp, managementClusterBootstrap, discoveryKubernetesApi)
  .enablePlugins(NoPublish)

lazy val integrationTestKubernetesApiJava = pekkoIntTestModule("kubernetes-api-java")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin, NoPublish)
  .settings(
    libraryDependencies := Dependencies.bootstrapDemos)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap,
    discoveryKubernetesApi)

lazy val integrationTestKubernetesDns = pekkoIntTestModule("kubernetes-dns")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin, NoPublish)
  .settings(
    libraryDependencies := Dependencies.bootstrapDemos)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap)

lazy val integrationTestAwsApiEc2TagBased = pekkoIntTestModule("aws-api-ec2")
  .configs(IntegrationTest)
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin, NoPublish)
  .settings(
    Defaults.itSettings)
  .dependsOn(
    management,
    managementClusterHttp,
    discoveryAwsApi,
    managementClusterBootstrap)

lazy val integrationTestMarathonApiDocker = pekkoIntTestModule("marathon-api-docker")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin, NoPublish)
  .settings(
    name := "integration-test-marathon-api-docker")
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap,
    discoveryMarathonApi)

lazy val integrationTestAwsApiEcs = pekkoIntTestModule("aws-api-ecs")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap,
    discoveryAwsApiAsync)
  .enablePlugins(JavaAppPackaging, AshScriptPlugin, DockerPlugin, NoPublish)
  .settings(
    dockerBaseImage := "openjdk:10-jre-slim",
    Docker / com.typesafe.sbt.SbtNativePackager.autoImport.packageName := "ecs-integration-test-app",
    Docker / version := "1.0")

lazy val integrationTestLocal = pekkoIntTestModule("local")
  .disablePlugins(MimaPlugin)
  .enablePlugins(AutomateHeaderPlugin)
  .settings(
    name := "integration-test-local",
    libraryDependencies := Dependencies.bootstrapDemos)
  .dependsOn(
    management,
    managementClusterHttp,
    managementClusterBootstrap)
  .enablePlugins(JavaAppPackaging, AshScriptPlugin, NoPublish)

lazy val themeSettings = Seq(
  // allow access to snapshots for pekko-sbt-paradox
  resolvers += Resolver.ApacheMavenSnapshotsRepo,
  pekkoParadoxGithub := Some("https://github.com/apache/incubator-pekko-management"))

lazy val docs = project
  .in(file("docs"))
  .enablePlugins(ParadoxPlugin, PekkoParadoxPlugin, ParadoxSitePlugin, PreprocessPlugin)
  .disablePlugins(MimaPlugin)
  .settings(themeSettings)
  .settings(
    name := "pekko-management-docs",
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
      "scaladoc.base_url" -> s"https://pekko.apache.org/api/pekko-management/current/",
      "scala.binary.version" -> scalaBinaryVersion.value,
      "pekko.version" -> Dependencies.pekkoVersion,
      "extref.pekko.base_url" -> s"https://pekko.apache.org/docs/pekko/current/%s",
      "scaladoc.pekko.base_url" -> s"https://pekko.apache.org/api/pekko/current/",
      "extref.pekko-http.base_url" -> s"https://pekko.apache.org/docs/pekko-http/current/%s",
      "scaladoc.pekko.http.base_url" -> s"https://pekko.apache.org/api/pekko-http/current/",
      "extref.pekko-grpc.base_url" -> s"https://pekko.apache.org/docs/pekko-grpc/current/%s",
      "scaladoc.akka.management.base_url" -> s"/${(Preprocess / siteSubdirName).value}/"))

def pekkoModule(moduleName: String): Project =
  Project(id = moduleName, base = file(moduleName))

def pekkoIntTestModule(moduleName: String): Project =
  Project(id = s"integration-test-$moduleName", base = file(s"integration-test/$moduleName"))

TaskKey[Unit]("verifyCodeFmt") := {
  javafmtCheckAll.all(ScopeFilter(inAnyProject)).result.value.toEither.left.foreach { _ =>
    throw new MessageOnlyException(
      "Unformatted Java code found. Please run 'javafmtAll' and commit the reformatted code")
  }
}
