PuppetDB manual smoke testing guide

1. Set up your local system. You need:
   1. Virtualbox
   2. Vagrant
   3. The vagrant vbox-snapshot plugin
      
      ```sh
      vagrant plugin install vagrant-vbox-snapshot
      ```
2. Make a VM using an image from <https://vagrantcloud.com/puppetlabs>, ssh in, and become root. 
   Make sure to get a '-nocm' image!
   * wheezy:
        
        ```sh
        vagrant init puppetlabs/debian-7.8-64-nocm && vagrant up 
        vagrant ssh
        sudo bash
        ```
   * el7:
        
        ```sh
        vagrant init puppetlabs/centos-7.0-64-nocm && vagrant up
        vagrant ssh
        sudo bash
        ```
3. Provision the vm with your favorite utilities
   * wheezy:
        
        ```sh
        vagrant ssh -c "sudo apt-get install tmux vim git tree -y"
        ```
   * el7:
        
        ```sh
        vagrant ssh -c "sudo yum install tmux vim git tree -y"
        ```
4. Install base puppet stuff
  * For Puppet 4, use the PC1 repos:
    * wheezy:
        
        ```sh
        curl -o http://apt.puppetlabs.com/puppetlabs-release-pc1-wheezy.deb && dpkg -i puppetlabs-release-pc1-wheezy.deb
        apt-get install puppet-agent puppetserver
        export PATH=$PATH:/opt/puppetlabs/puppet/bin
        ``` 
    * el7:
        
        ```sh
        yum localinstall http://yum.puppetlabs.com/puppetlabs-release-pc1-el-7.noarch.rpm
        yum install puppet-agent puppetserver
        export PATH=$PATH:/opt/puppetlabs/puppet/bin
        ```
    Other repo setup packages:
    - http://apt.puppetlabs.com
    - http://yum.puppetlabs.com
    - https://puppetlabs.com/blog/welcome-puppet-collections
    - https://docs.puppetlabs.com/guides/puppetlabs_package_repositories.html#open-source-repositories
  * For Puppet 3:
    * wheezy:
        
        ```sh
        curl -o http://apt.puppetlabs.com/puppetlabs-release-pc1-wheezy.deb && dpkg -i puppetlabs-release-pc1-wheezy.deb
        apt-get install puppet
        ```
    * el7:
        
        ```sh
        yum localinstall http://yum.puppetlabs.com/puppetlabs-release-el-7.noarch.rpm
        yum install puppet
        ```
  * For both, set up your path and update the puppet.conf to set server under
    [main] and certname under [master] to your hostname.
        
        ```sh
        puppet config set certname $(facter fqdn) --section master
        puppet config set server $(facter fqdn) --section main
        puppet cert generate $(facter fqdn) --dns_alt_names  $(hostname -s),$(facter fqdn) --ca_name "Puppet CA generated on $(facter fqdn) at $(date '+%Y-%m-%d %H:%M:%S %z')"
        ```
5. Generate certs and sanity test
  * For Puppet 4
        
        ```sh
        service puppetserver start
        puppet agent -t
        puppet cert sign --all
        service puppetserver stop
        ```
  * For Puppet 3
        
        ```sh
        puppet master --no-daemonize --debug

        # then in another terminal
        puppet agent -t
        puppet cert sign --all

        # then ctrl-c the master in the first terminal
        ```
6. Set up your environment and the repos for the build you're testing
    * wheezy: 
        
        ```sh
        echo "export VERSION=<version>" >> /etc/profile
        source /etc/profile
        curl http://builds.puppetlabs.lan/puppetdb/${VERSION}/repo_configs/deb/pl-puppetdb-${VERSION}-wheezy.list >> /etc/apt/sources.list
        ```
    * el7:
        
        ```sh
        echo "export VERSION=<version>" >> /etc/profile
        source /etc/profile
        pushd /etc/yum.repos.d
        curl -O http://builds.puppetlabs.lan/puppetdb/${VERSION}/repo_configs/rpm/pl-puppetdb-${VERSION}-el-7-x86_64.repo
        popd
        ```
7. On the host box, snapshot your VM
    
    ```sh 
    vagrant snapshot take initial
    ```
8. Install and configure PuppetDB
   * For PuppetDB 3.x
     * wheezy:
        
        ```sh
        apt-get install puppetdb=${VERSION}
        apt-get install puppetdb-termini=${VERSION}
        echo "host = $(facter fqdn)" >> /etc/puppetlabs/puppetdb/conf.d/jetty.ini
        ```
     * el7:
        
        ```sh
        yum install puppetdb-${VERSION}
        yum install puppetdb-termini-${VERSION}
        ```
     * Then regardless of distro:
        
        ```sh
        echo "host = $(facter fqdn)" >> /etc/puppetlabs/puppetdb/conf.d/jetty.ini

        echo [main]                 > /etc/puppetlabs/puppet/puppetdb.conf
        echo server=$(facter fqdn) >> /etc/puppetlabs/puppet/puppetdb.conf
        echo port=8081             >> /etc/puppetlabs/puppet/puppetdb.conf

        echo "---"                         > /etc/puppetlabs/puppet/routes.yaml
        echo "master:"                    >> /etc/puppetlabs/puppet/routes.yaml
        echo "    facts:"                 >> /etc/puppetlabs/puppet/routes.yaml
        echo "       terminus: puppetdb"  >> /etc/puppetlabs/puppet/routes.yaml
        echo "       cache: yaml"         >> /etc/puppetlabs/puppet/routes.yaml

        puppet config set storeconfigs true --section master
        puppet config set storeconfigs_backend puppetdb --section master
        puppet config set reports puppetdb --section master
        ```
   * For PuppetDB 2.x
     * wheezy:
        
        ```sh
        apt-get install puppetdb=${VERSION}
        apt-get install puppetdb-terminus=${VERSION}
        ```
     * el7:
        
        ```sh
        yum install puppetdb-${VERSION}
        yum install puppetdb-terminus-${VERSION}
        ```
     * Then regardless of distro:
        
        ```sh
        echo "host = $(facter fqdn)" >> /etc/puppetdb/conf.d/jetty.ini

        echo [main]                 > /etc/puppet/puppetdb.conf
        echo server=$(facter fqdn) >> /etc/puppet/puppetdb.conf
        echo port=8081             >> /etc/puppet/puppetdb.conf

        echo "---"                         > /etc/puppet/routes.yaml
        echo "master:"                    >> /etc/puppet/routes.yaml
        echo "    facts:"                 >> /etc/puppet/routes.yaml
        echo "        terminus: puppetdb" >> /etc/puppet/routes.yaml
        echo "       cache: yaml"         >> /etc/puppet/routes.yaml

        puppet config set storeconfigs true --section master
        puppet config set storeconfigs_backend puppetdb --section master
        puppet config set reports puppetdb --section master
        ```
9. Restart puppetdb and puppetserver/puppet master
   * For Puppet 4:
        
        ```sh
        service puppetdb restart
        service puppetserver restart
        ```
   * For Puppet 3:
        
        ```sh
        service puppetdb restart
        puppet master --no-daemonize --debug
        ```
10. Check PuppetDB
  1. Do a puppet run
     If the JVM hasn't started yet you'll get a connection
     error so make sure to give it enough time. Once puppetdb is truly running
     the master log will reports storing things in PuppetDB.
     
     ```sh
     puppet agent -t
     ```
  2. Run these commands and make sure they produce the expected output:
    * For PuppetDB 3.x
        
        ```sh
        curl -X GET http://$(hostname):8080/pdb/meta/v1/version
        curl -X GET http://$(hostname):8080/pdb/query/v4/reports
        curl -X GET http://$(hostname):8080/pdb/query/v4/catalogs
        curl -X GET http://$(hostname):8080/pdb/query/v4/facts
        ``` 
    * For PuppetDB 2.x
        
        ```sh
        curl -X GET http://$(hostname):8080/v4/version
        curl -X GET http://$(hostname):8080/v4/reports
        curl -X GET http://$(hostname):8080/v4/catalogs/localhost.delivery.puppetlabs.net
        curl -X GET http://$(hostname):8080/v4/facts
        ```
11. Upgrade test
    1. Revert to your snapshot. On the host box
       
       ```vagrant snapshot go initial```
    2. Choose the base version for your upgrade
         
         ```sh
         echo "export VERSION=<version>" >> /etc/profile
         source /etc/profile
         ```
    3. Repeat steps 8-10 for the base version
    4. Upgrade to the latest version
       * PuppetDB 2.x->2.y, or 2->3
         * wheezy:
            
            ```apt-get upgrade puppetdb puppetdb-terminus```
         * el7:
             
             ```yum upgrade puppetdb puppetdb-terminus```
       * PuppetDB 3.x->3.y
         * wheezy:

             ```apt-get upgrade puppetdb puppetdb-termini```
         * el7:

             ```yum upgrade puppetdb puppetdb-termini```
    5. Run the commands in 10.2, checking that your reports, catalogs, and facts
       are still present.
    6. Run step 10 again, checking that you get new data.
