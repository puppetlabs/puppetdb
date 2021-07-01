# Acceptance tests

PuppetDB uses the [Beaker](https://github.com/puppetlabs/beaker) acceptance
testing framework. We run acceptance tests on a matrix of machine and database
configurations before we merge new code into our stable or master branches, but
it can be useful for a variety of reasons to run them yourself.

The current recommended way of running acceptance tests is via EC2. Other
methods should be possible, as Beaker supports a wide variety of hypervisors.


EC2 setup
---------
* Create ~/.fog with these contents. Note that 'aws access key id' is the thing
  otherwise called an AWS access key. It's not your user id. 

      :default:
        :aws_access_key_id: <your AWS access key>
        :aws_secret_access_key: <your AWS secret key>

* The included configuration files in `acceptance/config` refer to resources
  (security groups, VPCs, etc) that exist in the Puppet AWS account. If
  you're using your own AWS account, you'll have to create the appropriate
  resources and modify the appropriate configuration file to refer to them.

Running the tests
-----------------
* If you previously munged your host file as described below, first remove the
  IP address entry.

* Do a normal acceptance test run the first time, so you get a fully provisioned VM to work with. 

      rake "beaker:first_run[acceptance/tests/some/test.rb]"
      
* For now, you have to modify the host file you're using to include the IP
  addresses for the VMs that were provisioned the first time. You should be able
  to find these near the top of the console output. You could also ask the
  hypervisor for help:

      rake beaker:list_vms

  Take the IP address of the VM that was created for you and put it in the
  appropriate beaker hosts file. If you didn't specify one, the default is
  `acceptance/config/ec2-west-dev.cfg`. It has a commented-out IP address field,
  you should be able to uncomment it and put in the IP of your running VM.

* For subsequent runs, you can reuse the already-provisioned VM for a quicker
  turnaround time.

      rake "beaker:rerun[acceptance/tests/some/test.rb]"


