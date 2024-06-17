# Release Notes (1.0.x)

## 1.0.0
Apache Pekko Management 1.0.0 is based on Akka Management 1.1.4.
Pekko came about as a result of Lightbend's decision to make future Akka releases under a [Business Software License](https://www.lightbend.com/blog/why-we-are-changing-the-license-for-akka),
a license that is not compatible with Open Source usage.

Apache Pekko has changed the package names, among other changes. Config names have changed to use `pekko` instead
of `akka` in their names. The default ports for Pekko Management have changed to avoid clashing with the Akka Management
defaults. Users switching from Akka to Pekko should read our @ref:[Migration Guide](../migration.md).

Generally, we have tried to make it as easy as possible to switch existing Akka based projects over to using
Pekko 1.0.

We have gone through the code base and have tried to properly acknowledge all third party source code in the
Apache Pekko code base. If anyone believes that there are any instances of third party source code that is not
properly acknowledged, please get in touch.

### Bug Fixes
We haven't had to fix any significant bugs that were in Akka Management 1.1.4.

### Additions

* Scala 3 support ([PR80](https://github.com/apache/pekko-management/pull/80))
    * minimum of Scala 3.3.0 needed

### Dependency Upgrades
We have tried to limit the changes to third party dependencies that are used in Pekko Management 1.0.0. These are some exceptions:

* Jackson 2.14.3 ([#7](https://github.com/apache/pekko/issues/7))
