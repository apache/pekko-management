Your Pekko Cluster on Vanilla EC2 using Tag Based Discovery
==========================================================

Note: these steps can be easily automated via CloudFormation or 
Python / shell scripts etc. In fact, a simple CloudFormation script is provided [here](src/main/resources/CloudFormation).  

Examine the source code
-----------------------
In `application.conf`, you'll notice the lack of need for manual configuration of IPs
or host names for the cluster to form - the discovery mechanisms will do this for us.


Step 1: Launch Instances
------------------------

Launch at least two x86_64 Amazon Linux 2023 instances in the same VPC. Restrict SSH
to your own address. The sample requires Java 17; install its runtime on each instance:

```shell
sudo dnf install -y java-17-amazon-corretto-headless unzip
java -version
```

Step 2: Create IAM Role
-----------------------

Since the `pekko-discovery-aws-api` module uses the AWS HTTP API, we need to make sure that the proper security authorizations are 
in place. Hence, this step is about creating a IAM role. The IAM section is in the AWS Management Console, under "Services" and 
under "Security, Identity & Compliance". Click "Create role" and select "AWS service" (for type of trusted entity) and EC2 
(for the service that will use this role). Attach the "AmazonEC2ReadOnlyAccess" permission to this role. 
This permission should be sufficient for the `pekko-discovery-aws-api` module to do its job.


Step 3: Attach IAM Role
-----------------------

Go back to the EC2 view of the AWS Management Console and attach the newly created role to each and every EC2 instance you
launched in Step 1. See screenshot below to see how to do this.

![attaching a IAM role to an EC2 instance](screenshots/attach-iam-role.png)

Step 4: Create Security Group
-----------------------------

Since we want to run a Pekko cluster on these instances, they have to be able to talk to each other: the TCP ports we 
care about are 7355 and 7626. However, we don't want to open these ports to the whole wide world. In the Amazon Cloud,
instances can *privately* communicate with each other if they are part of the same security group *and* if the proper inbound 
rules are set. 

Create a security group called "pekko-cluster". Once the security group is created, select it and go to "edit inbound rules". 
First, add a custom TCP rule to allow yourself ssh access (port: 22, source: My IP). Then, add two custom TCP rules (for ports 7626, 7355)
using a custom source: the ID of the security group. 

See the screenshot below.

![creating a security group](screenshots/create-security-group.png)

Step 5: Attach Security Group
-----------------------------

Assign the 'pekko-cluster' security group to your EC2 instances.

Step 6: Package the App
-----------------------

Compile and package the demo app.

```
$ cd pekko-management 
$ sbt
> project integration-test-aws-api-ec2
> Universal / packageBin
```

Now, in the `integration-test/aws-api-ec2/target/universal` folder, you should have a
`app.zip` file. 

Step 7: Tag Instances
---------------------

Tag your instances with tag "service" -> "products-api". 
The demo app is configured (via the "pekko.discovery.aws-api-ec2-tag-based.filters" key, see the application.conf file) 
to require an additional tag "purpose" -> "demo". 

![tagging instances](screenshots/discovery-aws-ec2-tagged-instances.png)



Step 8: Run and validate the cluster !
--------------------------------------

Transfer (via scp, for example) the package zip file to your EC2 instances. 
Unzip the package and run the app like this on each instance:

```
$ unzip app.zip
$ cd app/bin
$ METADATA=http://169.254.169.254/latest
$ TOKEN=$(curl -fsS -X PUT -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' "$METADATA/api/token")
$ MY_IP=$(curl -fsS -H "X-aws-ec2-metadata-token: $TOKEN" "$METADATA/meta-data/local-ipv4")
$ ./integration-test-aws-api-ec2 -Dpekko.management.http.hostname="$MY_IP" -Dpekko.remote.artery.canonical.hostname="$MY_IP"
```

Follow the logs and watch for signs of successful cluster formation (e.g., a welcome message from one of the nodes). 

Open a second terminal session to one of the EC2 instances:

```
$ curl -fsS "http://<private-ip>:7626/cluster/members" | python3 -m json.tool
```

You should see two members with status equal to 'Up' and no unreachable members.

Congrats.
