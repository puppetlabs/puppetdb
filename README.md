# Puppet CMDB

The Puppet CMDB is a central store for database about the infrastructure that
we manage on your network, as well as a tie point for all the behaviour that
should flow from that infrastructure as it changes and grows.

For more details about the motivation and intended use see [the Puppet CMDB Manifesto][manifesto] in Google Docs.

(A Puppet Labs account required, sorry.)

[manifesto]: https://docs.google.com/a/puppetlabs.com/document/d/1f3KvmdlBR5_wGwazBWEA9vdlU-LNBRyIeflNFUyOXOY/edit?hl=en_US

## CMDB Query API Specification

### Keywords in these Documents

The key words "*MUST*", "*MUST NOT*", "*REQUIRED*", "*SHALL*", "*SHALL NOT*", "*SHOULD*", "*SHOULD NOT*", "*RECOMMENDED*", "*MAY*", and "*OPTIONAL*" in this document are to be interpreted as described in [RFC 2119][RFC2119].

[RFC2119]: http://tools.ietf.org/html/rfc2119

 * [Common Behaviour and Background](spec/common.md)
 * [Data Models](spec/data-models.md)
 * [Resource Data Model](spec/resource.md)

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



[leiningen]: https://github.com/technomancy/leiningen
