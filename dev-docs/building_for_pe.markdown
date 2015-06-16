This document covers how to build, test and promote PuppetDB (PuppetDB
core + PE PuppetDB extensions) for PE. Specifically this document is
aimed at EZBake based releases of PuppetDB (Shallow Gravy and after,
PuppetDB 3.0.0+). This document will cover setting up a PE test
environment, creating PE PuppetDB packages, promoting a set of changes
and following that through to a PE build that includes the new
PuppetDB patches.

### Setting Up a PE Environment

Typically you'll be wanting to verify a fix for a PE related bug or a
new PE related feature. To be able to test out this new PE PuppetDB
package, it's easiest if you first get PE installed, then upgrade the
individual PuppetDB package(s) to the new (locally created)
versions. For this document, I'm going to assume CentOS 7 and VMWare
Fusion as the target of the PE Install.

#### 1 - Download a snapshot PE

To download a snapshotted version of PE, go to
[http://getpe.delivery.puppetlabs.net/](http://getpe.delivery.puppetlabs.net/). The 4.0.0
tab is for Shallow Gravy and _Enterprise 7 64-bit_ is the right
version for CentOS 7.

#### 2 - Launch a Vagrant VM

The below stanza can go in your ~/Vagrant file to grab a minimal
CentOS 7 image.

```
config.vm.define "centos7" do |v|
    v.vm.box = "puppetlabs/centos-7.0-64-nocm"
    v.vm.hostname = "centos7.vm"
    v.vm.box_url = "puppetlabs/centos-7.0-64-nocm"

    v.vm.provider "vmware_fusion" do |another_v|
  	    another_v.vmx["memsize"] = "4096"
    end

end
```

Note the memsize change above. My default memory size was 512 MB for
vagrant launched Fusion instances. This is not enough for PE and leads
to your PE install being broken in very subtle ways. It's best to
start out with more memory before you install. 4GB is recommended, may
or may not work with 2GB. Run `vagrant up centos7` to launch your test
instance.

#### 3 - Install PE

Once your instance has started, grab the PE tarball that you
downloaded above and untar it in `/home/vagrant`. You probably have
your home directory from the host machine mounted as `/vagrant` on
your test instance, so this is probably as easy as `cp
/vagrant/Download/<the-pe-tar> .`.  The easiest way to install PE is
to use an answers file. A basic answers file can be foun
[here](https://gist.github.com/senior/0056c6362c0e02185016). If you're
using centos7 as your hostname (like the above Vagrant config), you
should be able to use the file without modification. If you have a
different hostname, search and replace centos7 with the new
name. Next, from the directory you unpacked the puppet installer into,
run the following command to install PE


```
sudo ./puppet-enterprise-installer -D -a <path to your answers file> -l /vagrant/`hostname -s`-install.log
```

This will take a while.

#### 4 - Check to ensure PuppetDB is up

After install, PuppetDB should already be running a functional. You can test this out with:

```
[vagrant@centos7 ~]$ curl http://localhost:8080/pdb/meta/v1/version
{
  "version" : "3.0.0-SNAPSHOT"
}
```

If you get `Not Found` or something other than a proper version
returned as a JSON response, you need to dig in now to figure out
what's wrong. See below for common troubleshooting info.


### Building PE PuppetDB

#### 1 - Build/Install EZBake from HEAD of master

If you don't have a copy of
[EZBake](https://github.com/puppetlabs/ezbake) cloned locally, do that
now. Then run `lein do clean, install`. Note that this step becomes
unnecessary once EZBake 0.3.9 has been released.

#### 2 - Install PuppetDB

This is a multi-step process. First you need to create a branch based
off the commit you're wanting to create a build for. Assuming your local master branch is up to date and that's what you want to push, below are the steps you would follow:

```
git checkout -b pe-promote master
git rebase upstream/pr/1436
```

For now, we need Ken's patch from PR 1436. If you have a different way
of grabbing that patch and adding it to your branch, that's fine
too. Next, go into the PuppetDB project.clj file and change all
references of EZBake's version to `0.3.9-SNAPSHOT`. Also you'll need
to comment out the line that says `:pedantic? :abort`. Commit the
changes, then run:

```
lein do clean, install
```

#### 3 - Install pe-puppetdb-extensions

Whever you have the PuppetDB extensions project checkout out (pointing at the correct commit):

```
lein do clean, install
```

#### 4 - Build the PE PuppetDB Packages

Note you need to be on the VPN or on Puppet Lab's local network for
this part. `cd` back into the PuppetDB repository, then run the
following commands:

```
lein with-profile ezbake,pe ezbake stage
cd target/staging
rake package:bootstrap
rake pe:jenkins:uber_build PE_VER=4.0
```

The last command will give you some URLs that are based on the project
and the time of the build. A few lines from the bottom of the output
will say "Your packages will be available at:" and will give a
URL. The URL it provides looks like
`http://builds.puppetlabs.lan/pe-puppetdb/3.0.0...` but didn't work for
me. Switching from `puppetlabs.lan` to `builds.delivery.puppetlabs.net`
did work. So ultimately I ended up going to a URL like
`http://builds.delivery.puppetlabs.net/pe-puppetdb/3.0.0...`. From
there you can download the packages and install them in a VM locally.

#### 5 - Promoting a PE PuppetDB package

Step 4 above gives you a package you can use, but is not included in
the next snapshot release of PE, do do that you need to promote the
package you just built. To do that you need to use the
[Package-Promotion job](http://jenkins-compose.delivery.puppetlabs.net/view/Promotion/job/Package-Promotion/)
in Jenkins. You'll need to make sure you're logged in, then click
_Build with Parameters_. There are 2 fields you'll need to fill
in. The first is the package name to promote. For this example, we're
promoting `pe-puppetdb`. The REF is the version string you were given
in the previous step, which includes the version and the time, an
example of that REF is `3.0.0.SNAPSHOT.2015.06.09T1428`.

#### 6 - Compose will create a new PE tarball

Every half hour a job kicks off to grab the latest (promoted) PE
components and creates a new PE package. The compose jobs can be found
[here](http://jenkins-compose.delivery.puppetlabs.net/view/Compose/). To
track your commit through to a build, check the
[PE Compose Sign Repositories](http://jenkins-compose.delivery.puppetlabs.net/view/Compose/job/PE-Compose-Sign-Repositories/)
job. If you click on the current running job (or last job), there is a
list of changes and the PE PuppetDB version you promoted in the
previous step should be listed at the bottom. Note also at the top of
the page is a version with a sha snippet, like
`4.0.0-rc4-184-g4ff5492`. That sha will follow the build through to
the PE download page. Once the job has completed, it will run a
[monolithic smoke test](http://jenkins-enterprise.delivery.puppetlabs.net/job/enterprise_pe-acceptance-tests_integration-system_pe_smoke-monolithic_4.0.x/), then a
[split smoke test](http://jenkins-enterprise.delivery.puppetlabs.net/job/enterprise_pe-acceptance-tests_integration-system_pe_smoke-split_4.0.x/). Both
will eventually (on the left hand side of the page) include the
version + sha that was given in the "PE Compose Sign" job. Once the
split test is green, the new tarball is uploaded to
[http://getpe.delivery.puppetlabs.net/](http://getpe.delivery.puppetlabs.net/). The
version + sha will also be listed at the top of that page.

### Troubleshooting - Where is stuff?

#### PuppetDB install directory - /opt/puppetlabs/server/apps/puppetdb

To execute PuppetDB commands like starting PuppetDB in the foreground
or import/export, cd into `/opt/puppetlabs/server/apps/puppetdb` and run `bin/puppetdb command`
where _command_ is import/export/foreground etc. This
directory also has the JAR file if you wanted to look at the source
code directly.

#### PuppetDB logs - /var/log/puppetlabs/puppetdb/puppetdb.log

Logs will only get here if PuppetDB startup has reached the point that
is has consumed the user provided config and made the appropriate
logger changes. If you don't see anything in this file, it's likely
that the failure occurred before logging config has be consumed. To
troubleshoot that, it's easiest to launch puppetdb in the foreground
using the `puppetdb foreground` command (found in `bin` in the
PuppetDB install directory).

#### PuppetDB config - /etc/puppetlabs/puppetdb/

The TK bootstrap file is in the root of that directory, conf.d has
most of the other stuff that is of interest.
