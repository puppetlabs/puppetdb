---
title: "PuppetDB 4.3: Load testing"
layout: default
---

[export]: ./anonymization.html

A basic tool for simulating PuppetDB loads is included with the standard
PuppetDB distribution. **This tool is currently experimental and is likely to
change in future releases**.

The load testing tool can submit catalogs, facts, and/or reports at a defined
interval, for a specified number of nodes. It is also able to vary the submitted
catalogs over time to simulate catalog changes (which can cause a higher
PuppetDB load). The intent of the tool is to get a rough idea about how the
system (and software) will handle load under realistic conditions. The tool will
use catalogs/reports/facts specified by the user, including those exported from
a production/running system, to simulate real-world conditions. While originally
designed as a PuppetDB developer tool, we expect that many users may find it
useful.

Getting data for the tool
-----

The load testing tool does not yet have the ability to generate its own data. To
run simulations, you will need a collection of catalogs, facts and/or reports.
The easiest source of this information is the export tool included with PuppetDB
(more information [here][export]). The export will produce a tar.gz file
containing facts, catalogs, and reports.

Unzip the exported data. When unzipped/untared, find the
`puppetdb-bak/catalogs`, `puppetdb-bak/facts` and a `puppetdb-back/reports`
directories. One or all of these directories can be used as input to the load
testing tool.

Running the load testing tool (benchmark)
-----

Before running the load testing tool, make sure you have the full path to your
example data. You'll also need a config file (such as `config.ini`) with the
host and port information for the PuppetDB instance you wish to load test. The
config file format is the same as the one PuppetDB uses, but you only need two
entries:

      [jetty]
      host=<host name here>
      port=<port here>

There is currently no script for running the tool, so you'll need a command like
the one below:

    $ java -cp /opt/puppetlabs/server/apps/puppetdb/puppetdb.jar clojure.main \
        -m puppetlabs.puppetdb.cli.benchmark \
        --config myconfig.ini \
        --catalogs /tmp/puppetdb-bak/catalogs \
        --runinterval 30 --numhosts 1000 --rand-perc 10

Note that if you run it from the source tree via leiningen, you should
make sure to use trampoline, i.e. `lein trampoline run benchmark ...`
so that the tool can shut down and clean up normally.

### Arguments accepted by the benchmark command

- **`--config / -c`**: path to the INI file that has the host/port configuration
  for the PuppetDB instance to be tested.
- **`--catalogs / -C`**: directory containing catalogs to use for testing
  (probably from a previous PuppetDB export).
- **`--reports / -R`**: directory containing reports to use for testing
  (probably from a previous PuppetDB export).
- **`--facts / -F`**: directory containing facts to use for testing (probably
  from a previous PuppetDB export).
- **`--archive / -A`**: tarball archive obtained via a PuppetDB export. This
  option is incompatible with the preceding four.
- **`--runinterval / -i`**: integer indicating the amount of time in
  minutes between puppet runs for each simulated node. Typical values
  are 30 or 60.  Mutually exclusive with **`--nummsgs`**.  This option
  requires some temporary filesystem space, which will be allocated in
  TMPDIR (if set in the environment), java.io.tmpdir (if that JVM
  property is set), or the default JVM location.
- **`--numhosts / -n`**: number of separate hosts that the tool should simulate.
- **`--rand-perc / -r`**: what percentage of catalogs submissions should be
  changed (this simulates typical catalog changes, such as adding a resource,
  edge, or something similar). More changes to catalogs will cause a higher load
  on PuppetDB. A typical change percentage is 10.

>**Note:** If --facts, --catalogs, --reports, and --archive are unspecified, the
>PuppetDB sample data will be used. This data includes catalogs, facts, and
>reports.
