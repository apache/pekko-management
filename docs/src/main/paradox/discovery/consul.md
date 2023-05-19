## Consul

@@@ warning

This module is community maintained and the Lightbend subscription doesn't cover support for this module.
  It is also marked as @extref:[may change](pekko:common/may-change.html).
  That means that the API, configuration or semantics can change without warning or deprecation period.

@@@

Consul currently ignores all fields apart from service name. This is expected to change.

If you are using Consul to do the service discovery this would allow you to base your Cluster on Consul services.

## Project Info

@@project-info{ projectId="discovery-consul" }

@@dependency[sbt,Gradle,Maven] {
  symbol1=PekkoManagementVersion
  value1=$project.version$
  group="org.apache.pekko"
  artifact="pekko-discovery-consul_$scala.binary.version$"
  version=PekkoManagementVersion
}

`pekko-discovery-consul` can be used with Pekko $pekko.version$ or later.
You have to override the following Pekko dependencies by defining them explicitly in your build and
define the Pekko version to the one that you are using. Latest patch version of Pekko is recommended and
a later version than $pekko.version$ can be used.

@@dependency[sbt,Gradle,Maven] {
  symbol=PekkoVersion
  value=$pekko.version$
  group=org.apache.pekko
  artifact=pekko-cluster_$scala.binary.version$
  version=PekkoVersion
  group2=org.apache.pekko
  artifact2=pekko-discovery_$scala.binary.version$
  version2=PekkoVersion
}

In your application conf add:
```
pekko.discovery {
  method = pekko-consul
  pekko-consul {

    #How to connect to Consul to fetch services data
    consul-host = "127.0.0.1"
    consul-port = 8500

    # Prefix for consul tag with the name of the actor system / application name,
    # services with this tag present will be found by the discovery mechanism
    # i.e. `system:test` will be found in cluster if the cluster system is named `test`
    application-name-tag-prefix = "system:"

    # Prefix for tag containing port number where pekko management is set up so that
    # the seed nodes can be found, an example value for the tag would be `pekko-management-port:19999`
    application-pekko-management-port-tag-prefix = "pekko-management-port:"
  }
}
```

Notes:

* Since tags in Consul services are simple strings, prefixes are necessary to ensure that proper values are read.

* If Pekko management port tag is not found on service in Consul the implementation defaults to catalog service port.


