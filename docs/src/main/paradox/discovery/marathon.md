## Marathon API

@@@ warning

This module is community maintained and the Lightbend subscription doesn't cover support for this module.
  It is also marked as @extref:[may change](pekko:common/may-change.html).
  That means that the API, configuration or semantics can change without warning or deprecation period.

@@@

Marathon currently ignores all fields apart from service name. This is expected to change.

If you're a Mesos or DC/OS user, you can use the provided Marathon API implementation. You'll need to add a label
to your Marathon JSON (named `ACTOR_SYSTEM_NAME`  by default) and set the value equal to the name of the configured
effective name, which defaults to your applications actor system name.

You'll also have to add a named port, by default `pekkomgmthttp`, and ensure that Pekko Management's HTTP interface
is bound to this port.

## Project Info

@@project-info{ projectId="discovery-marathon-api" }

### Dependencies and usage

This is a separate JAR file:

@@dependency[sbt,Gradle,Maven] {
  symbol1=PekkoManagementVersion
  value1=$project.version$
  group="org.apache.pekko.pekko.discovery"
  artifact="pekko-discovery-marathon-api_$scala.binary.version$"
  version=PekkoManagementVersion
}

`pekko-discovery-marathon-api` can be used with Pekko $pekko.version$ or later.
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

And in your `application.conf`:

```
pekko.discovery {
  method = marathon-api
}
```

And in your `marathon.json`:
```
{
   ...
   "cmd": "path-to-your-app -Dpekko.remote.netty.tcp.hostname=$HOST -Dpekko.remote.netty.tcp.port=$PORT_PEKKOREMOTE -Dpekko.management.http.hostname=$HOST -Dpekko.management.http.port=$PORT_AKKAMGMTHTTP",

   "labels": {
     "ACTOR_SYSTEM_NAME": "my-system"
   },

   "portDefinitions": [
     { "port": 0, "name": "pekkoremote" },
     { "port": 0, "name": "pekkomgmthttp" }
   ]
   ...
}
```

