# Bootstrap environments

A set of integration tests projects can be found in [integration-test folder of the Pekko Management project](https://github.com/apache/incubator-pekko-management/tree/master/integration-test).
These test various Pekko management features together in various environments such as Kubernetes.

The following samples exist as standalone projects for Akka as a starting point and can be easily adapted to Pekko:

* [Akka Cluster bootstrap using the Kubernetes API with Java/Maven](https://github.com/akka/akka-sample-cluster-kubernetes-java)
* [Akka Cluster bootstrap using DNS in Kubernetes](https://github.com/akka/akka-sample-cluster-kubernetes-dns-java)

## Local

To run Bootstrap locally without any dependencies such as DNS or Kubernetes see the @ref[`local` example](local-config.md)

## Running Pekko Cluster in Kubernetes

The goal of bootstrap is to support running Pekko Cluster in Kubernetes as if it is a stateless application.
The part bootstrap solves is creating the initial cluster and handling scaling and re-deploys gracefully.

The recommended approach is to:

* Use a Deployment for creating the pods
* Use either the Kubernetes API or DNS for contact point discovery (details below)
* Optionally use a service or ingress for any for traffic coming from outside of the Pekko Cluster e.g. gRPC and HTTP

### Kubernetes Deployment

Use a regular deployment (not a StatefulSet) with the following settings.

#### Update strategy

For small clusters it may make sense to set `maxUnavailable` to 0 and `maxSurge` to 1.
This means that a new pod is created before removing any existing pods so if the new pod fails the cluster remains
at full strength until a rollback happens. For large clusters it may be too slow to do 1 pod at a time.

If using @extref:[Split Brain Resolver](pekko:split-brain-resolver.html) have a `maxUnavailable` that will not cause downing

### Cluster singletons

Deployments order pods by pod state and then time spent ready when deciding which to remove first. This works well
with cluster singletons as they are typically removed last and then the cluster singletons move to the the oldest new pod.

### External traffic

For production traffic e.g. HTTP use a regular service or an alternative ingress mechanism.
With an appropriate readiness check this results in traffic not being routed until bootstrap has finished.

@@snip [pekko-cluster.yml](/integration-test/kubernetes-dns/kubernetes/pekko-cluster.yml)  { #public }

This will result in a ClusterIP being created and only added to `Endpoints` when the pods are `ready`

Note that the `app` is the same for both services as they both refer to the same pods.

### Health checks

`pekko-management` includes a HTTP route for readiness and liveness checks.
`pekko-management-cluster-http` includes readiness check for the Pekko Cluster membership. To use it
add the following dependency:

@@dependency[sbt,Gradle,Maven] {
  symbol1=PekkoManagementVersion
  value1=$project.version$
  group=org.apache.pekko
  artifact=pekko-management-cluster-http_$scala.binary.version$
  version=PekkoManagementVersion
}

Pekko Cluster HTTP Management can be used with Pekko $pekko.version$ or later.
You have to override the following Pekko dependencies by defining them explicitly in your build and
define the Pekko version to the one that you are using. Latest patch version of Pekko is recommended and
a later version than $pekko.version$ can be used.

@@dependency[sbt,Gradle,Maven] {
  symbol=PekkoVersion
  value=$pekko.version$
  group=org.apache.pekko
  artifact=pekko-cluster-sharding_$scala.binary.version$
  version=PekkoVersion
  group2=org.apache.pekko
  artifact2=pekko-discovery_$scala.binary.version$
  version2=PekkoVersion
}

The readiness check is exposed on the Pekko Management port as a `GET` on `/ready` and the liveness check is a `GET` on `/alive`

Configure them to be used in your deployment:

@@snip [pekko-cluster.yml](/integration-test/kubernetes-dns/kubernetes/pekko-cluster.yml)  { #health }


This will mean that a pod won't get traffic until it is part of a cluster, which is important
if either `ClusterSharding` or `ClusterSingleton` are used.


### Contact point discovery


Contact point discovery can use either `kubernetes` or `pekko-dns` service discovery. Details
on additional resources required and how they work:

* @ref[Kubernetes using `kubernetes-api` discovery](kubernetes-api.md)
* @ref[Kubernetes using `pekko-dns` discovery](kubernetes.md)

Kubernetes-api is the more battle tested mechanism, DNS was added in Pekko 2.5.15 and Pekko Management 0.18.
DNS has the benefit that it is agnostic of Kubernetes so does not require pods be able to communicate with the API
server. However it requires a headless service that supports the `publishNotReadyAddresses` feature. If your Kubernetes setup
does not support `publishNotReadyAddresses` yet then use the `kubernetes-api` discovery mechanism.

### Running in Istio

For considerations and configuration necessary for bootstrapping a Pekko cluster in Istio, see @ref[Bootstrapping an Pekko cluster in Istio](istio.md).

### Running the Kubernetes demos

The following steps work for the `integration-test/kubernetes-api` or the `integration-test/kubernetes-dns` sub-project:

To run the demo in a real Kubernetes or OpenShift cluster the images must be pushed to a registry that cluster
has access to and then `kubernetes/pekko-cluster.yml` (in either sub-project) modified with the full image path.

The following shows how the sample can be run in a local cluster using either `minishift` or `minikube`. Unless
explicitly stated `minikube` can be replaced with `minishift` and `kubectl` with `oc` in any of the commands below.

Start [minikube](https://github.com/kubernetes/minikube) make sure you have installed and is running:

```
$ minikube start
```

Make sure your shell is configured to target the docker daemon running inside the VM:

```
$ eval $(minikube docker-env)
```

Publish the application docker image locally. If running this project in a real cluster you'll need to publish the image to a repository
that is accessible from your Kubernetes cluster and update the `kubernetes/pekko-cluster.yml` with the new image name.

```
$ sbt shell
> project integration-test-kubernetes-api (or integration-test-kubernetes-dns)
> docker:publishLocal
```

You can run multiple different Pekko Bootstrap-based applications in the same namespace,
alongside any other containers that belong to the same logical application.
The resources in `kubernetes/pekko-cluster.yml` are configured to run in the `pekko-bootstrap-demo-ns` namespace.
Change that to the namespace you want to deploy to. If you do not have a namespace to run your application in yet,
create it:

```
kubectl create namespace <insert-namespace-name-here>

# and set it as the default for subsequent commands
kubectl config set-context $(kubectl config current-context) --namespace=<insert-namespace-name-here>
```

Or if running with `minishift`:

```
oc new-project <insert-namespace-name-here>

# and switch to that project to make it the default for subsequent comments
oc project <insert-namespace-name-here>
```

Next deploy the application:

```
# minikube using Kubernetes API
kubectl apply -f integration-test/kubernetes-api/kubernetes/pekko-cluster.yml

or

# minikube using DNS
kubectl apply -f integration-test/kubernetes-dns/kubernetes/pekko-cluster.yml

or

# minishift using Kubernetes API
oc apply -f integration-test/kubernetes-api/kubernetes/pekko-cluster.yaml

or

# minishift using DNS
oc apply -f integration-test/kubernetes-dns/kubernetes/pekko-cluster.yaml
```

This will create and start running a number of Pods hosting the application. The application nodes will form a cluster.

In order to observe the logs during the cluster formation you can
pick one of the pods and issue the kubectl logs command on it:

```
$ POD=$(kubectl get pods | grep pekko-bootstrap | grep Running | head -n1 | awk '{ print $1 }'); echo $POD
akka-integration-test-bcc456d8c-6qx87

$ kubectl logs $POD --follow | less
```

```
[INFO] [12/13/2018 07:13:42.867] [main] [ClusterBootstrap(pekko://default)] Initiating bootstrap procedure using pekko.discovery.pekko-dns method...
[DEBUG] [12/13/2018 07:13:42.906] [default-akka.actor.default-dispatcher-2] [TimerScheduler(pekko://default)] Start timer [resolve-key] with generation [1]
[DEBUG] [12/13/2018 07:13:42.919] [default-akka.actor.default-dispatcher-2] [TimerScheduler(pekko://default)] Start timer [decide-key] with generation [2]
[INFO] [12/13/2018 07:13:42.924] [default-akka.actor.default-dispatcher-2] [pekko.tcp://default@172.17.0.7:7355/system/bootstrapCoordinator] Locating service members. Using discovery [pekko.discovery.dns.DnsSimpleServiceDiscovery], join decider [org.apache.pekko.management.cluster.bootstrap.LowestAddressJoinDecider]
[INFO] [12/13/2018 07:13:42.933] [default-akka.actor.default-dispatcher-2] [pekko.tcp://default@172.17.0.7:7355/system/bootstrapCoordinator] Looking up [Lookup(integration-test-kubernetes-dns-internal.pekko-bootstrap.svc.cluster.local,Some(management),Some(tcp))]
[DEBUG] [12/13/2018 07:13:42.936] [default-akka.actor.default-dispatcher-2] [DnsSimpleServiceDiscovery(pekko://default)] Lookup [Lookup(integration-test-kubernetes-dns-internal.pekko-bootstrap-demo-ns.svc.cluster.local,Some(management),Some(tcp))] translated to SRV query [_management._tcp.integration-test-kubernetes-dns-internal.pekko-bootstrap-demo-ns.svc.cluster.local] as contains portName and protocol
[DEBUG] [12/13/2018 07:13:42.995] [default-akka.actor.default-dispatcher-18] [pekko.tcp://default@172.17.0.7:7355/system/IO-DNS] Resolution request for _management._tcp.integration-test-kubernetes-dns-internal.pekko-bootstrap-demo-ns.svc.cluster.local Srv from Actor[pekko://default/temp/$a]
[DEBUG] [12/13/2018 07:13:43.011] [default-akka.actor.default-dispatcher-6] [pekko.tcp://default@172.17.0.7:7355/system/IO-DNS/async-dns/$a] Attempting to resolve _management._tcp.integration-test-kubernetes-dns-internal.pekko-bootstrap-demo-ns.svc.cluster.local with Actor[pekko://default/system/IO-DNS/async-dns/$a/$a#1272991285]
[DEBUG] [12/13/2018 07:13:43.049] [default-akka.actor.default-dispatcher-18] [pekko.tcp://default@172.17.0.7:7355/system/IO-TCP/selectors/$a/0] Successfully bound to /0.0.0.0:7626
[DEBUG] [12/13/2018 07:13:43.134] [default-akka.actor.default-dispatcher-18] [pekko.tcp://default@172.17.0.7:7355/system/IO-DNS/async-dns/$a/$a] Resolving [_management._tcp.integration-test-kubernetes-dns-internal.pekko-bootstrap-demo-ns.svc.cluster.local] (SRV)
[INFO] [12/13/2018 07:13:43.147] [default-akka.actor.default-dispatcher-6] [PekkoManagement(pekko://default)] Bound Pekko Management (HTTP) endpoint to: 0.0.0.0:7626
[DEBUG] [12/13/2018 07:13:43.156] [default-akka.actor.default-dispatcher-5] [pekko.tcp://default@172.17.0.7:7355/system/IO-TCP/selectors/$a/1] Successfully bound to /0.0.0.0:8080
[INFO] [12/13/2018 07:13:43.180] [main] [akka.actor.ActorSystemImpl(default)] Server online at http://localhost:8080/
....
[INFO] [12/13/2018 07:13:50.631] [default-pekko.actor.default-dispatcher-5] [org.apache.pekko.cluster.Cluster(pekko://default)] Cluster Node [pekko.tcp://default@172.17.0.7:7355] - Welcome from [pekko.tcp://default@172.17.0.6:7355]
[DEBUG] [12/13/2018 07:13:50.644] [default-pekko.remote.default-remote-dispatcher-22] [akka.serialization.Serialization(pekko://default)] Using serializer [org.apache.pekko.cluster.protobuf.ClusterMessageSerializer] for message [org.apache.pekko.cluster.GossipEnvelope]
[INFO] [12/13/2018 07:13:50.659] [default-pekko.actor.default-dispatcher-18] [pekko.tcp://default@172.17.0.7:7355/user/$b] Cluster pekko.tcp://default@172.17.0.7:7355 >>> MemberUp(Member(address = pekko.tcp://default@172.17.0.6:7355, status = Up))
[INFO] [12/13/2018 07:13:50.676] [default-pekko.actor.default-dispatcher-20] [pekko.tcp://default@172.17.0.7:7355/user/$b] Cluster pekko.tcp://default@172.17.0.7:7355 >>> MemberJoined(Member(address = pekko.tcp://default@172.17.0.7:7355, status = Joining))
[INFO] [12/13/2018 07:13:50.716] [default-pekko.actor.default-dispatcher-6] [pekko.tcp://default@172.17.0.7:7355/user/$b] Cluster pekko.tcp://default@172.17.0.7:7355 >>> LeaderChanged(Some(pekko.tcp://default@172.17.0.6:7355))
[INFO] [12/13/2018 07:13:50.720] [default-pekko.actor.default-dispatcher-3] [pekko.tcp://default@172.17.0.7:7355/user/$b] Cluster pekko.tcp://default@172.17.0.7:7355 >>> RoleLeaderChanged(dc-default,Some(pekko.tcp://default@172.17.0.6:7355))
[INFO] [12/13/2018 07:13:50.727] [default-pekko.actor.default-dispatcher-6] [pekko.tcp://default@172.17.0.7:7355/user/$b] Cluster pekko.tcp://default@172.17.0.7:7355 >>> SeenChanged(true,Set(pekko.tcp://default@172.17.0.6:7355, pekko.tcp://default@172.17.0.7:7355))
[INFO] [12/13/2018 07:13:50.733] [default-pekko.actor.default-dispatcher-5] [pekko.tcp://default@172.17.0.7:7355/user/$b] Cluster pekko.tcp://default@172.17.0.7:7355 >>> ReachabilityChanged()
```
