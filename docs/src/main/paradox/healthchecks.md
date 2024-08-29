# Health checks

Pekko Management supports two kinds of health checks:

* Readiness checks: should the application receive external traffic, for example waiting for the following to complete
    * Joining a Cluster
    * Establishing a connection to a database or queuing system
* Liveness checks: should the application be left running 

Readiness checks can be used to decide if a load balancer should route traffic whereas liveness checks can be used in environments which can restart a hung process.

This matches [Kubernetes Health checks](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-probes/). 
See [Kubernetes Liveness and Readiness Probes: How to Avoid Shooting Yourself in the Foot](https://blog.colinbreck.com/kubernetes-liveness-and-readiness-probes-how-to-avoid-shooting-yourself-in-the-foot/) for a
good overview of how to use readiness and liveness probes.

Pekko Management provides endpoints for readiness and liveness checks out of the box at `/ready` and `/alive` (paths can be configured, see below).
Configuration settings are used to add checks to back those endpoints. When called, each endpoint will only report to be healthy when
*all* the configured health checks (for the particular kind, readiness or liveness) are succeeding.

## Defining a Health Check

A health check must extend @scala[`Function0[Future[Boolean]]`]@java[`Supplier[CompletionStage[Boolean]]`] and have either a no argument constructor or a constructor
with a single argument of type `ActorSystem.` A general type is used rather than a specific interface so that modules such as `pekko-cluster` can 
provide health checks without depending on Pekko management.

Having access to the `ActorSystem` allows loading of any external resource via a Pekko extension e.g. `Cluster` or a database connection. Health checks
return a @scala[`Future`]@java[`CompletionStage`] so that an asynchronous action can be taken.

Scala
: @@snip [ExampleHealthCheck.scala](/management/src/test/scala/doc/org/apache/pekko/management/ExampleHealthCheck.scala)  { #basic}

Java
: @@snip [ExampleHealthCheck.java](/management/src/test/java/jdoc/org/apache/pekko/management/BasicHealthCheck.java)  { #basic}


Typically the `ActorSystem` is used to get a hold of any state needed to execute the check e.g.

Scala
: @@snip [ExampleHealthCheck.scala](/management/src/test/scala/doc/org/apache/pekko/management/ExampleHealthCheck.scala)  { #cluster }

Java
: @@snip [ExampleHealthCheck.java](/management/src/test/java/jdoc/org/apache/pekko/management/ClusterCheck.java)  { #cluster }

Note that @ref:[Cluster Http Management](cluster-http-management.md) includes a health check for cluster membership that is configurable for which states are considered healthy.

Any of the above health checks can be configured as either readiness checks or liveness checks. 

## Configuring health checks

Health checks are picked up from configuration. Modules are expected to provide health checks e.g. @ref:[Cluster Http Management](cluster-http-management.md) provides a cluster readiness check.

Application specific health checks can be added a `name = <fully qualified class name>` to `pekko.management.health-checks.readiness-checks` or `pekko.management.health-checks.liveness-checks` e.g.

@@snip [reference.conf](/management-cluster-http/src/main/resources/reference.conf)  { #health }

## Hosting health checks as a Pekko Management Route

Health checks can be hosted via the Pekko management HTTP server. The `pekko.management.HealthCheckRoutes` is enabled
by default as a Pekko management route provider.

By default all startup checks are hosted on `/startup`, readiness checks are hosted on `/ready` and liveness checks are hosted on `/alive`. If all of the checks
for an endpoint succeed a `200` is returned, if any fail or return `false` a `500` is returned. The paths are
configurable via `pekko.management.health-checks.startup-path`, `pekko.management.health-checks.readiness-path` and `pekko.management.health-checks.liveness-path` e.g.

@@snip [application.conf](/integration-test/local/src/main/resources/application.conf)  { #health }

The `org.apache.pekko.management.HealthCheckRoutes` can be disabled with the following configuration but that also
means that the configured `startup-checks`, `readiness-checks` and `liveness-checks` will not be used.

```
pekko.management.http.routes {
  health-checks = ""
}
```




