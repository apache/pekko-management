import com.lightbend.paradox.projectinfo.ParadoxProjectInfoPluginKeys._
import de.heikoseeberger.sbtheader.HeaderPlugin
import de.heikoseeberger.sbtheader.HeaderPlugin.autoImport._
import sbt.Keys._
import sbt._
import org.mdedetrich.apache.sonatype.ApacheSonatypePlugin
import sbtdynver.DynVerPlugin
import sbtdynver.DynVerPlugin.autoImport.dynverSonatypeSnapshots

object Common extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin && HeaderPlugin && ApacheSonatypePlugin && DynVerPlugin

  val currentYear = "2023"

  override lazy val projectSettings: Seq[sbt.Def.Setting[_]] =
    Seq(
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
      description := "Apache Pekko Management is a suite of tools for operating Apache Pekko Clusters.",
      crossScalaVersions := Dependencies.crossScalaVersions,
      projectInfoVersion := (if (isSnapshot.value) "snapshot" else version.value),
      crossVersion := CrossVersion.binary,
      scalacOptions ++= {
        val scalacOptionsBase = Seq(
          "-encoding", "UTF-8",
          "-feature",
          "-unchecked",
          "-deprecation",
          "-release:8")
        if (scalaVersion.value == Dependencies.scala213Version)
          scalacOptionsBase ++: Seq(
            "-Werror",
            "-Wdead-code")
        else if (scalaVersion.value == Dependencies.scala3Version)
          scalacOptionsBase ++: Seq(
            "-Werror")
        else
          scalacOptionsBase
      },
      javacOptions ++= Seq(
        "-Xlint:unchecked"),
      // Necessary otherwise javadoc fails with Unexpected javac output: javadoc: error - invalid flag: -Xlint:unchecked.
      Compile / doc / javacOptions -= "-Xlint:unchecked",
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
        "org.apache.pekko.pattern" // for some reason Scaladoc creates this
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
      scalaVersion := Dependencies.scala213Version)

  override lazy val buildSettings = Seq(
    dynverSonatypeSnapshots := true)

  private def isJdk8 =
    VersionNumber(sys.props("java.specification.version")).matchesSemVer(SemanticSelector(s"=1.8"))
}
