# puppet-cmdb

The Puppet CMDB is a central store for database about the infrastructure that
we manage on your network, as well as a tie point for all the behaviour that
should flow from that infrastructure as it changes and grows.

For more details see [the Puppet CMDB Manifesto][manifesto] in Google Docs.

## Usage

Install [leiningen][leiningen] (google it)

To (re)initialize the database:

    dropdb cmdb
    createdb cmdb
    psql cmdb -f resources/cmdb.sql

To get all the requisite dev dependencies:

    lein deps

To execute the code:

    lein run

Testing requires that you install the `midje` code; `lein deps` will do that.
Then you can run:

    lein midje

Documentation can be generated using `marginalia`; `lein deps` will do that.
Then you can run the following, which spits out docs into the `docs/`
directory:

    lein marg

If you want to set some JVM options, like max memory:

    JVM_OPTS='-Xmx2048m' lein run


## License

Copyright (C) 2011 Puppet Labs

No license to distribute or reuse this product is currently available.
For details, contact Puppet Labs.



[manifesto]: https://docs.google.com/a/puppetlabs.com/document/d/1f3KvmdlBR5_wGwazBWEA9vdlU-LNBRyIeflNFUyOXOY/edit?hl=en_US
[leiningen]: https://github.com/technomancy/leiningen
