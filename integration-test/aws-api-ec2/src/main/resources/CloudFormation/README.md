Cloud Formation Scripts
=======================

Note that these files are in the resources folder, so that they can be read by an integration test. To use Cluster Bootstrap 
with EC2 Tag Based Discovery, you do not need to have these files in your resources folder.

The template targets a default VPC and resolves the Amazon Linux 2023 x86_64 AMI
for the region via a public Systems Manager parameter. The stack creator needs
permission to read the AMI parameter and manage the EC2 launch template; these
permissions are not needed by the instance role. The T3 demo instances use
standard CPU credits rather than incurring surplus-credit charges. Set `SSHLocation`
to your own CIDR and supply an HTTPS URL for the packaged `app.zip`. The test
uses us-east-1 and creates billable EC2 instances. Delete the stack after use.
