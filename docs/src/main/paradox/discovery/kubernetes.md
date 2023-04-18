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
  group="com.lightbend.pekko.discovery"
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

### Role-Based Access Control

If your Kubernetes cluster has [Role-Based Access Control (RBAC)](https://kubernetes.io/docs/admin/authorization/rbac/)
enabled, you'll also have to grant the Service Account that your pods run under access to list pods. The following
configuration can be used as a starting point. It creates a `Role`, `pod-reader`, which grants access to query pod
information. It then binds the default Service Account to the `Role` by creating a `RoleBinding`.
Adjust as necessary.

> Using Google Kubernetes Engine? Your user will need permission to grant roles. See [Google's Documentation](https://cloud.google.com/kubernetes-engine/docs/how-to/role-based-access-control#prerequisites_for_using_role-based_access_control) for more information.

@@snip [pekko-cluster.yml](/integration-test/kubernetes-api/kubernetes/pekko-cluster.yml) { #rbac }
