/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * license agreements; and to You under the Apache License, version 2.0:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * This file is part of the Apache Pekko project, which was derived from Akka.
 */

import com.github.sbt.git.GitPlugin.autoImport.git
import com.lightbend.paradox.projectinfo.ParadoxProjectInfoPluginKeys._
import sbtheader.HeaderPlugin
import sbtheader.HeaderPlugin.autoImport._
import sbt.Keys._
import sbt._
import org.mdedetrich.apache.sonatype.ApacheSonatypePlugin
import sbtdynver.DynVerPlugin
import sbtdynver.DynVerPlugin.autoImport.dynverSonatypeSnapshots
import com.github.sbt.JavaFormatterPlugin.autoImport.javafmtSortImports

object Common extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin && HeaderPlugin && ApacheSonatypePlugin && DynVerPlugin

  val currentYear = "2023"

  val isScala3 = Def.setting(scalaBinaryVersion.value == "3")

  override def globalSettings =
    Seq(excludeLintKeys ++= Set(autoAPIMappings, projectInfoVersion))

  override lazy val projectSettings: Seq[sbt.Def.Setting[?]] =
    Seq(
      startYear := Some(2022),
      homepage := Some(url("https://pekko.apache.org/")),
      releaseNotesURL := {
        val artifactVersion = version.value
        // Sonatype's bundle target also treats versions ending in -SNAPSHOT as snapshots.
        if (isSnapshot.value || artifactVersion.endsWith("-SNAPSHOT")) None
        else Some(url(s"https://pekko.apache.org/docs/pekko-management/$artifactVersion/release-notes/index.html"))
      },
      scmInfo := Some(
        ScmInfo(url("https://github.com/apache/pekko-management"),
          "git@github.com:apache/pekko-management.git")),
      developers += Developer(
        "contributors",
        "Contributors",
        "dev@pekko.apache.org",
        url("https://github.com/apache/pekko-management/graphs/contributors")),
      description := "Apache Pekko Management is a suite of tools for operating Apache Pekko Clusters.",
      crossScalaVersions := Dependencies.crossScalaVersions,
      // all Scala 3 versions share the _3 suffix, so the scala3Next build is only tested, never published
      publish / skip := (publish / skip).value || scalaVersion.value == Dependencies.scala3NextVersion,
      projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
      crossVersion := CrossVersion.binary,
      scalacOptions ++= {
        val scalacOptionsBase = Seq(
          "-encoding", "UTF-8",
          "-feature",
          "-unchecked",
          "-deprecation",
          "-release:17")
        if (scalaBinaryVersion.value == "2.13")
          scalacOptionsBase ++: Seq(
            "-Werror",
            "-Wdead-code")
        else
          scalacOptionsBase ++:
          (Seq(
            "-Werror",
            "-Wconf:msg=Implicit parameters should be provided with a `using` clause:s",
            "-Wconf:msg=is deprecated for wildcard arguments of types:s",
            "-Wconf:msg=The trailing ` _` for eta-expansion is unnecessary:s",
            "-Wconf:msg=with as a type operator has been deprecated:s",
            "-Wconf:msg=Unreachable case except for null:s",
            "-Wconf:msg=is no longer supported for vararg splices:s") ++
          (if (scalaVersion.value.startsWith("3.3."))
             Seq("-Yfuture-lazy-vals", "-Wconf:msg=bad option.*-Yfuture-lazy-vals:s")
           else Seq.empty))
      },
      javacOptions ++= Seq(
        "-Xlint:unchecked"),
      // Necessary otherwise javadoc fails with Unexpected javac output: javadoc: error - invalid flag: -Xlint:unchecked.
      Compile / doc / javacOptions -= "-Xlint:unchecked",
      javacOptions ++= Seq("--release", "17"),
      Compile / doc / scalacOptions := scalacOptions.value ++ Seq(
        "-doc-title",
        "Apache Pekko Management",
        "-doc-version",
        version.value) ++
      // for some reason Scaladoc creates this
      (if (!isScala3.value)
         Seq(
           "-skip-packages",
           "org.apache.pekko.pattern")
       else
         Seq("-skip-packages:org.apache.pekko.pattern")),
      Compile / doc / scalacOptions ++= {
        val root = (LocalRootProject / baseDirectory).value.getAbsolutePath
        val revision = if (isSnapshot.value) git.gitHeadCommit.value.getOrElse("main") else s"v${version.value}"
        val sourceLinks =
          if (isScala3.value)
            Seq(s"-source-links:${root}=github://apache/pekko-management/${revision}")
          else Seq(
            "-sourcepath",
            root,
            "-doc-source-url",
            s"https://github.com/apache/pekko-management/blob/${revision}/€{FILE_PATH_EXT}#L€{FILE_LINE}")
        sourceLinks ++ Seq("-doc-canonical-base-url", "https://pekko.apache.org/api/pekko-management/current/")
      },
      autoAPIMappings := true,
      javafmtSortImports := false,
      // show full stack traces and test case durations
      Test / testOptions += Tests.Argument("-oDF"),
      // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
      // -a Show stack traces and exception class name for AssertionErrors.
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
      scalaVersion := Dependencies.scala213Version) ++ Seq(Compile, Test).map { config =>
      config / scalacOptions := {
        val options = (config / scalacOptions).value
        if (semanticdbEnabled.value) {
          // OrganizeImports needs unused-import diagnostics, but must be able to repair them before warnings fail the build.
          options.filterNot(option =>
            Set("-Xfatal-warnings", "-Werror").contains(option) ||
            (option.startsWith("-Wconf:") && option.toLowerCase.contains("unused import"))) :+
          (if (scalaVersion.value.startsWith("2.12.")) "-Ywarn-unused:imports" else "-Wunused:imports")
        } else options
      }
    }

  override lazy val buildSettings = Seq(
    dynverSonatypeSnapshots := true)
}
