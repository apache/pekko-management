# Apache Pekko Discovery Methods

As of version `1.0.0` of Pekko Management @extref:[Pekko Discovery](pekko:discovery/index.html)
has become a core Pekko module. Older versions of Service Discovery from Pekko Management are not compatible with the 
Pekko Discovery module in Pekko.

Pekko Management contains methods for:

 * @ref[Kubernetes](kubernetes.md)
 * @ref[Consul](consul.md)
 * @ref[Marathon](marathon.md)
 * @ref[AWS](aws.md)
 
The @ref[Kubernetes](kubernetes.md) and @extref:[Pekko Discovery DNS](pekko:discovery/index.html#discovery-method-dns)
methods are known to be well used and tested. The others are community contributions that are not tested as
part of the build and release process.
 
@@@ index

  - [Kubernetes](kubernetes.md)
  - [Consul](consul.md)
  - [Marathon](marathon.md)
  - [AWS](aws.md)
  
@@@
