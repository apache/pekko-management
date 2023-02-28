# Migration guide

## 1.0 

Version requirements:

* Akka 2.5.19 or later
* Pekko HTTP 10.1.7 or later

### Source changes

* `PekkoManagement` moved to package `org.apache.pekko.management.scaladsl.PekkoManagement`, if using from Java use `org.apache.pekko.management.javadsl.PekkoManagement`
* If implementing custom ManagementRouteProvider the package changed to `org.apache.pekko.management.scaladsl.ManagementRouteProvider`/`org.apache.pekko.management.javadsl.ManagementRouteProvider`
* `PekkoManagement.start` and `PekkoManagement.routes` may throw IllegalArgumentException instead of returning Try
* Auth and HTTPS has changed by using overloaded methods of `PekkoManagement.start` and `PekkoManagement.routes`, see the @ref[docs for more details](akka-management.md#enabling-basic-authentication)

### Configuration changes

* `org.apache.pekko.management.cluster.http.healthcheck.ready-states` moved to `org.apache.pekko.management.cluster.health-check.ready-states`
* `org.apache.pekko.management.cluster.bootstrap.form-new-cluster` renamed to `org.apache.pekko.management.cluster.bootstrap.new-cluster-enabled`

#### route-providers

`org.apache.pekko.management.cluster.route-providers` changed from being a list of fully qualified class names to
a configuration object `org.apache.pekko.management.cluster.routes` with named route providers. The reason for the
change was to be able to exclude a route provider that was included by a library (from reference.conf) by
using `""` or `null` as the FQCN of the named entry, for example:

```
pekko.management.http.routes {
  health-checks = ""
}
```

By default the `org.apache.pekko.management.HealthCheckRoutes` is enabled.

### Apache Pekko Discovery

For Pekko Management version 1.0 Service Discovery as well as the config, DNS and aggregate discovery methods 
were made core Akka module. The following steps are required when upgrading to 1.0 of Pekko Management.

Remove dependencies for:

@@dependency[sbt,Gradle,Maven] {
  group="com.lightbend.pekko.discovery"
  artifact="pekko-discovery"
  version="old_akka_management_version"
  group2="com.lightbend.pekko.discovery"
  artifact2="pekko-discovery-dns"
  version2="old_akka_management_version"
  group3="com.lightbend.pekko.discovery"
  artifact3="pekko-discovery-config"
  version3="old_akka_management_version"
  group4="com.lightbend.pekko.discovery"
  artifact4="pekko-discovery-aggregate"
  version4="old_akka_management_version"
}

If using Cluster Bootstrap the new dependency will be brought in automatically.
If using Service Discovery directly add the following dependency:

@@dependency[sbt,Gradle,Maven] {
  group="org.apache.pekko"
  artifact="pekko-discovery"
  version="latest_akka_version"
}

Setting the service discovery method now has to be the unqualified name e.g `kubernetes-api` rather than `pekko.discovery.kubernets-api`.
If using a custom discovery method the configuration for the discovery method must live under `pekko.discovery`. 

For bootstrap it is recommended to set the service discovery method via `org.apache.pekko.management.cluster.bootstrap.contact-point-discovery.discovery-method`
rather then overriding the global service discovery mechanism with `pekko.discovery.method` 

### DNS 

If using DNS service discovery it is no longer required to override the global Akka DNS resolver. Remove `akka.io.dns.resolver = async-dns` from your configuration
to avoid setting the `async-dns` as the global DNS resolver as it still lacks some features. The DNS discovery mechanism now uses an isolated resolver internally
to support SRV records. 

### Kubernetes

Kubernetes service discovery now automatically picks up the namespace at runtime. If previously hard coded or an environment variable used this can be removed
from configuration and the deployment.

Unless used for something other than service discovery / bootstrap the following can be removed from your deployment 

```
- name: NAMESPACE	
   valueFrom:	
     fieldRef:	
       fieldPath: metadata.namespace
```

If `pod-namespace` is set remove from your configuration as it will be automatically picked up from the `/var/run/secrets/kubernetes.io/serviceaccount/namespace` file
that is mounted to each Kubernetes container. The namespace can be overridden with `pod-namespace` if this isn't the desired behavior.

### Cluster HTTP

The `cluster-http` module now only exposes read only routes by default. To enable destructive operations such as downing members
set `pekko.management.http.route-providers-read-only` to `false.



