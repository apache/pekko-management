import com.geirsson.CiReleasePlugin
import com.lightbend.paradox.projectinfo.ParadoxProjectInfoPluginKeys._
import de.heikoseeberger.sbtheader.HeaderPlugin
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._
import sbt.Keys._
import sbt._
import xerial.sbt.Sonatype.autoImport.sonatypeProfileName

object Common extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin && HeaderPlugin && CiReleasePlugin

  val currentYear = "2023"

  override lazy val projectSettings: Seq[sbt.Def.Setting[_]] =
    Seq(
      organization := "org.apache.pekko",
      organizationName := "Apache Software Foundation",
      organizationHomepage := Some(url("https://www.apache.org/")),
      startYear := Some(2022),
      homepage := Some(url("https://pekko.apache.org/")),
      scmInfo := Some(
        ScmInfo(url("https://github.com/apache/incubator-pekko-management"),
          "git@github.com:apache/incubator-pekko-management.git")),
      developers += Developer(
        "contributors",
        "Contributors",
        "dev@pekko.apache.org",
        url("https://github.com/apache/incubator-pekko-management/graphs/contributors")),
      licenses := Seq(("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0"))),
      description := "Apache Pekko Management is a suite of tools for operating Apache Pekko Clusters.",
      headerLicense := Some(HeaderLicense.Custom(apacheHeader)),
      crossScalaVersions := Dependencies.crossScalaVersions,
      projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
      crossVersion := CrossVersion.binary,
      scalacOptions ++= {
        val scalacOptionsBase = Seq(
          "-encoding",
          "UTF-8",
          "-feature",
          "-unchecked",
          "-deprecation",
          "-Xlint",
          "-Ywarn-dead-code",
          "-target:jvm-1.8")
        if (scalaVersion.value == Dependencies.scala212Version)
          scalacOptionsBase ++: Seq("-Xfuture", "-Xfatal-warnings")
        else
          scalacOptionsBase
      },
      javacOptions ++= Seq(
        "-Xlint:unchecked"),
      javacOptions ++= (
        if (isJdk8) Seq.empty
        else Seq("--release", "8")
      ),
      Compile / doc / scalacOptions := scalacOptions.value ++ Seq(
        "-doc-title",
        "Apache Pekko Management",
        "-doc-version",
        version.value,
        "-skip-packages",
        "pekko.pattern" // for some reason Scaladoc creates this
      ),
      Compile / doc / scalacOptions ++= Seq(
        "-doc-source-url", {
          val branch = if (isSnapshot.value) "master" else s"v${version.value}"
          s"https://github.com/apache/incubator-pekko-management/tree/${branch}€{FILE_PATH_EXT}#L€{FILE_LINE}"
        },
        "-doc-canonical-base-url",
        "https://pekko.apache.org/api/pekko-management/current/"),
      autoAPIMappings := true,
      // show full stack traces and test case durations
      Test / testOptions += Tests.Argument("-oDF"),
      // -v Log "test run started" / "test started" / "test run finished" events on log level "info" instead of "debug".
      // -a Show stack traces and exception class name for AssertionErrors.
      testOptions += Tests.Argument(TestFrameworks.JUnit, "-v", "-a"),
      scalaVersion := Dependencies.scala212Version,
      sonatypeProfileName := "com.lightbend")

  private def isJdk8 =
    VersionNumber(sys.props("java.specification.version")).matchesSemVer(SemanticSelector(s"=1.8"))

  def apacheHeader: String =
    """Licensed to the Apache Software Foundation (ASF) under one or more
      |license agreements; and to You under the Apache License, version 2.0:
      |
      |  https://www.apache.org/licenses/LICENSE-2.0
      |
      |This file is part of the Apache Pekko project, derived from Akka.
      |""".stripMargin
}
