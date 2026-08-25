## Kubernetes API

If you want to use Kubernetes for @ref[Cluster Bootstrap](../bootstrap/index.md), please follow the @ref[Cluster Bootstrap Kubernetes API](../bootstrap/kubernetes-api.md) documentation that is tailored for that use case.

The typical way to consume a service in Kubernetes is to discover it through DNS: this will take into account liveness/readiness checks, and depending on the configuration take care of load balancing (removing the need for client-side load balancing). For this reason, for general usage the @extref:[`pekko-dns`](pekko:discovery/index.html#discovery-method-dns) implementation is usually a better fit for discovering services in Kubernetes. However, in some cases, such as for @ref[Cluster Bootstrap](../bootstrap/index.md), it is desirable to connect to the pods directly, bypassing any liveness/readiness checks or load balancing. For such situations we provide a discovery implementation that uses the Kubernetes API.

## Project Info

@@project-info{ projectId="discovery-kubernetes-api" }

### Dependencies and usage

First, add the dependency on the component:

@@dependency[sbt,Gradle,Maven] {
  symbol1=PekkoManagementVersion
  value1=$project.version$
  group="org.apache.pekko"
  artifact="pekko-discovery-kubernetes-api_$scala.binary.version$"
  version=PekkoManagementVersion
}

`pekko-discovery-kubernetes-api` can be used with Pekko $pekko.version$ or later.
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

As described above, it is uncommon to use the Kubernetes API discovery
mechanism as your default discovery mechanism. When using it with Pekko Cluster
Bootstrap, it is sufficient to configure it as described @ref[here](../bootstrap/kubernetes-api.md).
Otherwise, to load it manually, use `loadServiceDiscovery` on the `Discovery` extension:

Scala
:  @@snip [KubernetesApiServiceDiscoverySpec.scala](/discovery-kubernetes-api/src/test/scala/org/apache/pekko/discovery/kubernetes/KubernetesApiServiceDiscoverySpec.scala) { #kubernetes-api-discovery }

Java
:  @@snip [KubernetesApiDiscoveryDocsTest.java](/discovery-kubernetes-api/src/test/java/docs/KubernetesApiDiscoveryDocsTest.java) { #kubernetes-api-discovery }


To find other pods, this method needs to know how they are labeled, what the name of the target port is, and
what namespace they reside in. Below, you'll find the default configuration. It can be customized by changing these
values in your `application.conf`.

```
pekko.discovery {
  kubernetes-api {
    # Namespace discovery path
    #
    # If this path doesn't exist, the namespace will default to "default".
    pod-namespace-path = "/var/run/secrets/kubernetes.io/serviceaccount/namespace"

    # Namespace to query for pods.
    #
    # Set this value to a specific string to override discovering the namespace using pod-namespace-path.
    pod-namespace = "<pod-namespace>"

    # Selector value to query pod API with.
    # `%s` will be replaced with the configured effective name, which defaults to the actor system name
    pod-label-selector = "app=%s"

    # API poll mode for pod discovery.
    #
    # "list" (default) - performs a full pod list GET request on every lookup call.
    # "watch" - opens a long-lived Kubernetes Watch API stream that receives incremental
    #           pod change events (ADDED, MODIFIED, DELETED). An initial list is performed
    #           on the first lookup, then the stream keeps the local cache up to date.
    #           This scales much better for large clusters with frequent lookups.
    api-poll-mode = "list"
  }
}
```

This configuration complements the following Deployment specification:

```
apiVersion: apps/v1
kind: Deployment
metadata:
  labels:
    app: example
  name: example
spec:
  replicas: 4
  selector:
    matchLabels:
      app: example
  template:
    metadata:
      labels:
        app: example
    spec:
      containers:
      - name: example
        image: example/image:1.0.0
        imagePullPolicy: IfNotPresent
        ports:
        # pekko remoting
        - name: remoting
          containerPort: 7355
          protocol: TCP
        # When
        # pekko.management.cluster.bootstrap.contact-point-discovery.port-name
        # is defined, it must correspond to this name:
        - name: management
          containerPort: 7626
          protocol: TCP
```

### Watch Mode

For large clusters or deployments with frequent lookups, the default `list` poll mode can put significant load on the Kubernetes API server because it fetches the full pod list on every lookup call. The `watch` poll mode uses the Kubernetes Watch API to maintain a long-lived streaming connection that receives incremental pod change events, eliminating repeated full list requests.

To enable watch mode, set `api-poll-mode = "watch"` in your configuration:

```
pekko.discovery {
  kubernetes-api {
    api-poll-mode = "watch"

    # Optional: delay before reconnecting after stream completion (default: 1s)
    watch-reconnect-delay = 1s

    # Optional: delay before reconnecting after stream error (default: 5s)
    watch-on-error-reconnect-delay = 5s

    # Optional: largest single watch event the stream will accept (default: 1 MiB)
    watch-max-frame-length = 1 MiB
  }
}
```

When watch mode is enabled, the first `lookup` call performs an initial pod list to populate a local cache, then starts a persistent watch stream. Subsequent lookups read directly from the cache without making API calls. The watch stream automatically reconnects on errors or stream completion, using the Kubernetes `resourceVersion` mechanism to resume from where it left off. If the resume point is no longer valid - the server answers `410 Gone`, or sends an `ERROR` event - the cache is rebuilt from a fresh list rather than resumed, so that pods deleted while disconnected do not linger.

Each service name is watched separately, with its own cache, so one discovery instance can be used to look up more than one service. The watch streams are shut down during the `service-unbind` phase of Coordinated Shutdown.


### Role-Based Access Control

If your Kubernetes cluster has [Role-Based Access Control (RBAC)](https://kubernetes.io/docs/reference/access-authn-authz/rbac/)
enabled, you'll also have to grant the Service Account that your pods run under access to list pods. The following
configuration can be used as a starting point. It creates a `Role`, `pod-reader`, which grants access to query pod
information. It then binds the default Service Account to the `Role` by creating a `RoleBinding`.
Adjust as necessary.

@@@ note
If using `api-poll-mode = "watch"`, the RBAC role must also grant the `watch` verb on pods in addition to `list`.
@@@

> Using Google Kubernetes Engine? Your user will need permission to grant roles. See [Google's Documentation](https://cloud.google.com/kubernetes-engine/docs/how-to/role-based-access-control#prerequisites_for_using_role-based_access_control) for more information.

@@snip [pekko-cluster.yml](/integration-test/kubernetes-api/kubernetes/pekko-cluster.yml) { #rbac }
