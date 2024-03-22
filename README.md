# Apache Pekko (Cluster) Management

This repository contains interfaces to inspect, interact and manage various Parts of Apache Pekko, primarily Pekko Cluster.
Future additions may extend these concepts to other parts of Apache Pekko.

Apache Pekko Management is a fork of [Akka Management](https://github.com/akka/akka-management)

## Documentation

The documentation is available at
[pekko.apache.org](https://pekko.apache.org/docs/pekko-management/current/).

## Building from Source

### Prerequisites
- Make sure you have installed a Java Development Kit (JDK) version 8 or later.
- Make sure you have [sbt](https://www.scala-sbt.org/) installed.
- [Graphviz](https://graphviz.gitlab.io/download/) is needed for the scaladoc generation build task, which is part of the release.

### Running the Build
- Open a command window and change directory to your preferred base directory
- Use git to clone the [repo](https://github.com/apache/pekko-management) or download a source release from https://pekko.apache.org (and unzip or untar it, as appropriate)
- Change directory to the directory where you installed the source (you should have a file called `build.sbt` in this directory)
- `sbt compile` compiles the main source for project default version of Scala (2.13)
    - `sbt +compile` will compile for all supported versions of Scala
- `sbt test` will compile the code and run the unit tests
- `sbt testQuick` similar to `test` but when repeated in shell mode will only run failing tests
- `sbt package` will build the jars
    - the jars will built into target dirs of the various modules
    - for the the 'discovery-aws-api' module, the jar will be built to `discovery-aws-api/target/scala-2.13/`
- `sbt publishLocal` will push the jars to your local Apache Ivy repository
- `sbt publishM2` will push the jars to your local Apache Maven repository
- `sbt docs/paradox` will build the docs (the ones describing the module features)
    - `sbt docs/paradoxBrowse` does the same but will open the docs in your browser when complete
    - the `index.html` file will appear in `target/paradox/site/main/`
- `sbt unidoc` will build the Javadocs for all the modules and load them to one place (may require Graphviz, see Prerequisites above)
    - the `index.html` file will appear in `target/scala-2.13/unidoc/`
- `sbt sourceDistGenerate` will generate source release to `target/dist/`
- The version number that appears in filenames and docs is derived, by default. The derived version contains the most git commit id or the date/time (if the directory is not under git control).
    - You can set the version number explicitly when running sbt commands
        - eg `sbt "set ThisBuild / version := \"1.0.0\"; sourceDistGenerate"`
    - Or you can add a file called `version.sbt` to the same directory that has the `build.sbt` containing something like
        - `ThisBuild / version := "1.0.0"`

### Running integration tests

The integration tests requires an Kubernetes API server running on `localhost:8080`. You can run a local Kubernetes cluster using [Minikube](https://kubernetes.io/docs/tasks/tools/install-minikube/).
You can bind the API server on `localhost:8080` using `kubectl proxy --port=8080`.

The following scripts can be used to run the integration tests:

- `./integration-test/kubernetes-api/test.sh` run the integration tests for the Kubernetes API.
- `./integration-test/kubernetes-dns/test.sh` run the integration tests related to the Kubernetes DNS-based discovery using the service name.
- `./integration-test/kubernetes-dns/test.sh` run the integration tests for the Kubernetes DNS-based discovery using the service name.
- `./lease-kubernetes-int-test/test.sh` run the integration tests for the Kubernetes lease implementation.

## Community

There are several ways to interact with the Apache Pekko community:

- [GitHub discussions](https://github.com/apache/pekko-management/discussions): for questions and general discussion.
- [Pekko users mailing list](https://lists.apache.org/list.html?users@pekko.apache.org): for Pekko user discussions.
- [Pekko dev mailing list](https://lists.apache.org/list.html?dev@pekko.apache.org): for Pekko development discussions.
- [GitHub issues](https://github.com/apache/pekko-management/issues): for bug reports and feature requests. Please search the existing issues before creating new ones. If you are unsure whether you have found a bug, consider asking in GitHub discussions or the mailing list first.

## Contributions & Maintainers

Contributions are very welcome. If you have an idea on how to improve Apache Pekko Management, don't hesitate to create an issue or submit a pull request.

See [CONTRIBUTING.md](https://github.com/apache/pekko-management/blob/main/CONTRIBUTING.md) for details on the development workflow and how to create your pull request.

## Project Status

With the exception of the experimental modules listed below, version 1.0.0 or later of this library
will be ready to be used in production and APIs are stable.

The 1.0.0 release is expected soon. Snapshots are published to https://repository.apache.org/content/groups/snapshots/.

The following modules are considered experimental and require more work and testing to be considered production ready:

* pekko-discovery-marathon-api
* pekko-discovery-aws-api
* pekko-discovery-aws-api-async
* pekko-discovery-consul

## Code of Conduct

Apache Pekko Management is governed by the [Apache code of conduct](https://www.apache.org/foundation/policies/conduct.html). By participating in this project you agree to abide by its terms.

## License

Apache Pekko Management is available under the Apache License, version 2.0. See [LICENSE](https://github.com/apache/pekko-management/blob/main/LICENSE) file for details.
