# Apache Pekko Discovery Methods

As of version `1.0.0` of Pekko Management @extref:[Akka Discovery](pekko:discovery/index.html)
has become a core Akka module. Older versions of Service Discovery from Pekko Management are not compatible with the 
Akka Discovery module in Akka.

Pekko Management contains methods for:

 * @ref[Kubernetes](kubernetes.md)
 * @ref[Consul](consul.md)
 * @ref[Marathon](marathon.md)
 * @ref[AWS](aws.md)
 
The @ref[Kubernetes](kubernetes.md) and @extref:[Akka Discovery DNS](pekko:discovery/index.html#discovery-method-dns)
methods are known to be well used and tested. The others are community contributions that are not tested as
part of the build and release process.
 
@@@ index

  - [Kubernetes](kubernetes.md)
  - [Consul](consul.md)
  - [Marathon](marathon.md)
  - [AWS](aws.md)
  
@@@
