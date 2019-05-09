---
title: "PuppetDB 5.2 » Puppet query language (PQL) » Tutorial"
layout: default
---

[lists]: ./v4/pql.html#lists
[curl]: ./curl.html
[config_jetty]: ../../configure.html#jetty-http-settings
[resources]: ./v4/resources.html
[entities]: ./v4/entities.html
[pql]: ./v4/pql.html
[projection]: ./v4/pql.html#projection
[regexp]: ./v4/pql.html#regexp-
[in]: ./v4/pql.html#array-match-in
[implicit]: ./v4/pql.html#implicit-subqueries
[cli_install]: ../../pdb_client_tools.html
[examples]: ./examples-pql.html

This page walks through the construction of several types of PuppetDB PQL
queries. We use the **version 4 API** in all examples.

Other resources you may also find useful include:

* [PQL examples][examples]
* [PQL reference guide][pql]

## How to query

Queries are performed by issuing an HTTP GET or POST request to an endpoint URL
and specifying a `query` URL parameter (in the GET case) or a JSON-valued
payload in the POST case, which contains the query to execute. Results are
always returned in `application/json` form.

Queries are usually issued from code, but you can easily issue them from the
command line by using the [PuppetDB CLI][cli_install] or using [curl][curl].

### Querying with the PuppetDB CLI

[See the PuppetDB CLI installation page for more information about using the PuppetDB CLI.][cli_install]

**Without SSL:**

    puppet query '<PQL query>' \
      --urls http://puppetdb.example.com:8080

This requires that PuppetDB be
[configured to accept non-SSL connections][config_jetty]. By default, it will
only accept unencrypted traffic from `localhost`.

**With SSL:**

    puppet query '<PQL query>' \
      --urls https://puppetdb.example.com:8081 \
      --cacert /etc/puppetlabs/puppet/ssl/certs/ca.pem \
      --cert /etc/puppetlabs/puppet/ssl/certs/thisnode.pem \
      --key /etc/puppetlabs/puppet/ssl/private_keys/thisnode.pem

This requires that you specify a certificate (issued by the same CA PuppetDB
trusts), a private key, and a CA certificate.

> **Note**: The PuppetDB CLI can be configured using a config file at
`$HOME/.puppetlabs/client-tools/puppetdb.conf` with default values for the
server urls and SSL credentials.

### Querying with curl

[See the curl tips page for more information about constructing curl commands.][curl]

**Without SSL:**

    curl -X GET http://puppetdb.example.com:8080/pdb/query/v4 \
      --data-urlencode 'query=<PQL query>'

    curl -X POST http://puppetdb.example.com:8080/pdb/query/v4 \
      -H 'Content-Type:application/json'
      -d '{"query":"<PQL query>"}'

This requires that PuppetDB be
[configured to accept non-SSL connections][config_jetty]. By default, it will
only accept unencrypted traffic from `localhost`.

**With SSL:**

    curl -X GET https://puppetdb.example.com:8081/pdb/query/v4 \
      --tlsv1 \
      --cacert /etc/puppetlabs/puppet/ssl/certs/ca.pem \
      --cert /etc/puppetlabs/puppet/ssl/certs/thisnode.pem \
      --key /etc/puppetlabs/puppet/ssl/private_keys/thisnode.pem \
      --data-urlencode 'query=<PQL query>'

This requires that you specify a certificate (issued by the same CA PuppetDB
trusts), a private key, and a CA certificate.

### Querying with Puppet code

The PuppetDB terminus includes the `puppetdb_query` function, which can be used
to query PuppetDB from within a Puppet manifest. For example,

    $debian_nodes_query = 'nodes[certname]{facts{name = "operatingsystem" and value = "Debian"}}'
    $debian_nodes = puppetdb_query($debian_nodes_query).map |$value| { $value["certname"] }
    notify {"Debian nodes":
        message => "Your debian nodes are ${join($debian_nodes, ', ')}",
    }

## Resources Walkthrough

### Our first query

Let's start by taking a look at a simple resource query.

    resources {}

Executing that query will return all resources for all nodes, however it is rare
that you will want all this information. In this case, we use filters to reduce
the results.

Now suppose we want to find the user `nick` on every node. We can use this
query:

    resources { type = "User" and title = "nick" }

This query has two `=` clauses, both of which must be true.

The `=` operator follows a specific structure:

    <attribute to compare> = <value>

In this case, the attributes are `type` and `title`, and the values are `User`
and `nick`.

The `and` operator also has a well-defined structure:

    <query clause> and <query clause>

The query clauses can be any legal query (including another `and`). At least one
clause must be specified, and all the clauses must be true for the `and` clause
to be true. An `or` operator is also available, which looks just like the `and`
operator, except that, as you'd expect, it's true if *any* specified clause is
true.

If we execute this query, we get results that look something like this:

    [{
      "parameters" : {
        "comment" : "Nick Lewis",
        "uid" : "1115",
        "shell" : "/bin/bash",
        "managehome" : false,
        "gid" : "allstaff",
        "home" : "/home/nick",
        "groups" : "developers",
        "ensure" : "present"
      },
      "line" : 111,
      "file" : "/etc/puppetlabs/code/environments/production/manifests/user.pp",
      "exported" : false,
      "tags" : [ "firewall", "default", "node", "nick", "role::base", "users", "virtual", "user", "account", "base", "role::firewall::office", "role", "role::firewall", "class", "account::user", "office", "virtual::users", "allstaff" ],
      "title" : "nick",
      "type" : "User",
      "resource" : "0ae7e1230e4d540caa451d0ade2424f316bfbf39",
      "certname" : "foo.example.com"
    }]

Our results are an array of `resources`, where each resource is an object with a
particular set of keys.

* `parameters`: this field is itself an object, containing all the parameters
  and values of the resource
* `line`: the line the resource was declared on
* `file`: the file the resource was specified in
* `exported`: true if the resource was exported by this node, or false otherwise
* `tags`: all the tags on the resource
* `title`: the resource title
* `type`: the resource type
* `resource`: this is an internal identifier for the resource used by PuppetDB
* `certname`: the node that the resource came from

There will be an entry in the list for every resource. A resource is specific to
a single node, so if the resource is on 100 nodes, there will be 100 copies of
the resource (each with at least a different certname field).

*Note:* More information about entities and their available fields can be
accessed from the [entities document][entities].

### Excluding results

We know this instance of the user `nick` is defined on line 111 of
`/etc/puppetlabs/code/environments/production/manifests/user.pp`. What if we
want to check whether or not we define the same resource somewhere else? After
all, if we're repeating ourselves, something may be wrong! Fortunately, there's
an operator to help us:

    resources {
      type = "User" and
      title = "nick" and
      !(file = "/etc/puppetlabs/code/environments/production/manifests/user.pp" and line = 111)
    }

The `!` operator wraps another clause, and returns results for which the clause
is *not* true. In this case, we want resources which aren't defined on line 111
of `/etc/puppetlabs/code/environments/production/manifests/user.pp`.

Another thing to note is the way we have grouped `file` and `line`. This
grouping enforces the `!` operator to act on both parameter filters.

### Resource attributes

So far we've seen that we can query for resources based on their `certname`,
`type`, `title`, `file`, and `line`. There are a few more available:

    resources {
      tag = "foo" and
      exported = true
    }

This query returns resources whose set of tags *contains* the tag `foo`, and
which are exported.

For easy reference, the full set of queryable attributes can be found in
[the resource endpoint documentation][resources].

### Regular expressions

What if we want to restrict our results to a certain subset of nodes? We could
use something like this:

    resources {
      certname = "www1.example.com" or
      certname = "www2.example.com" or
      certname = "www3.example.com"
    }

And this works great if we know exactly the set of nodes we want. But what if we
want all the 'www' servers, regardless of how many we have? In this case, we can
use the regular expression match operator `~`:

    resources {
      certname ~ 'www\d+.example.com'
    }

For more information regarding the regular expression operator,
[consult the reference guide][regexp].

### Choosing fields to return (projection)

When you execute a resources query, all available fields for that query are
returned by default. Using the projection syntax however, you can limit what
fields are returned.

For example, if you want to only respond with the `certname`, `type`, and
`title` of each resource where `Class[apache]` is defined, you can do the
folllowing:

    resources[certname, type, title] {
      type = "Class" and
      title = "apache"
    }

For more information regarding projection, consult the
[reference guide][projection].

## Facts walkthrough

In addition to resources, we can also query for facts. This looks similar,
though the available fields and operators are a bit different. Some things are
the same, though. For instance, suppose you want all the facts for a certain
node:

    facts { certname = "foo.example.com" }

This gives results that look something like this:

    [ {
      "certname" : "foo.example.com",
      "name" : "architecture",
      "value" : "amd64",
      "environment" : "production"
    }, {
      "certname" : "foo.example.com",
      "name" : "fqdn",
      "value" : "foo.example.com",
      "environment" : "production"
    }, {
      "certname" : "foo.example.com",
      "name" : "hostname",
      "value" : "foo",
      "environment" : "production"
    }, {
      "certname" : "foo.example.com",
      "name" : "ipaddress",
      "value" : "192.168.100.102",
      "environment" : "production"
    }, {
      "certname" : "foo.example.com",
      "name" : "kernel",
      "value" : "Linux",
      "environment" : "production"
    }, {
      "certname" : "foo.example.com",
      "name" : "kernelversion",
      "value" : "2.6.32",
      "environment" : "production"
    } ]

### Fact attributes

In the last query, we saw that a "fact" consists of a "certname", a "name", and
a "value". As you might expect, we can query using "name" or "value".

    facts {
      name = "operatingsystem" and
      value = "Debian"
    }

This will find all the `operatingsystem = Debian` facts, and their corresponding
nodes. As you see, `and` is supported for facts, as are `or` and `!`.

### Fact operators

As with resources, facts also support the `~` regular expression match operator
for all their fields. In addition, numeric comparisons are supported for fact
values:

    facts {
      name = "uptime_seconds" and
      value >= 100000 and
      value < 1000000
    }

This will find nodes for which the `uptime_seconds` fact is in the range 100000
to 1000000. Numeric comparisons will *always be false* for fact values which are
not numeric. Importantly, version numbers such as 2.6.12 are not numeric, and
numeric comparison operators can't be used with them at this time.

## Nodes walkthrough

We can also query for nodes. Again, this is similar to resource and
fact queries:

    nodes {
      certname = "foo.example.com"
    }

The result of this query is:

    [ {
        "deactivated" : null,
        "facts_environment" : "production",
        "report_environment" : "production",
        "catalog_environment" : "production",
        "facts_timestamp" : "2015-06-22T17:25:11.886Z",
        "expired" : null,
        "report_timestamp" : "2015-06-22T17:25:07.484Z",
        "certname" : "foo.example.com",
        "catalog_timestamp" : "2015-06-22T17:25:12.023Z",
        "latest_report_hash" : "754b0b87af9ee647507b5aa3001f44f8e8843216",
        "latest_report_noop": true,
        "cached_catalog_status": "not_used",
        "latest_report_status" : "unchanged"
    } ]

This will return an object containing the certname `foo.example.com`, as well as
some metadata detailing deactivation status and the most recent fact, report,
and catalog updates from that node.

## Subquery walkthrough

### Explicit subqueries

The queries we've looked at so far are quite powerful and useful, but what if
your query needs to consider both resources *and* facts?

For instance, suppose you're configuring a load balancer, and need the IP
addresses of your Apache servers. You could find those servers by using this
resource query:

    resources {
      type = "Class" and
      title = "apache"
    }

This will find all the `Class[Apache]` resources, which each knows the certname
of the node it came from. Then you could put all those certnames into a fact
query:

    facts {
      name = "ipaddress" and
      (certname = "a.example.com" or
       certname = "b.example.com" or
       certname = "c.example.com" or
       certname = "d.example.com" or
       certname = "e.example.com")
    }

But this query is lengthy, and it requires some logic to assemble and run the
second query. There has to be a better way! What if we could find the
`Class[Apache]` servers and use the results of that query to find the certname?
We can, with this fact query:

    facts {
      name = "ipaddress" and
      certname in resources[certname] { type = "Class" and title = "apache" }
    }

This may appear a little daunting, so we'll look at it piece by piece.

Let's start with `resources[certname] { <filter> }`. This query will return the
certname for the results that match the filter as specified.

We then use an operator called `in` to turn our list of resources into just a
list of certnames. So we now conceptually have something like:

    certname in ["foo.example.com", "bar.example.com", "baz.example.com"]

The `in` operator matches facts whose `certname` is in the supplied list. (In
our case, this list is generated by a subquery. To use a literal list, you must
use the the syntax described in the [PQL lists section][lists].)

For more information regarding the `in` operator, consult the
[reference guide][in].

### Implicit subqueries

Explicit subqueries allow you to query across related entities, but they require
you to specify the columns that you wish to join on. Some relationships between
entities are well known to PuppetDB. We can use this information ourselves in a
query avoiding the need for specifying how entities relate.

If you take this example query:

    facts {
      name = "operatingsystem" and
      value = "Debian"
    }

This will return all `facts` that match the filter. But if we wanted to return
the `nodes` entity results for nodes that have facts that match this filter, we
can utilize implicit subqueries instead, by embedding the query inside a filter:

    nodes {
      facts {
        name = "operatingsystem" and
        value = "Debian"
      }
    }

In this case, node information is returned, even though the filter was done
across facts relating to the node:

    [ {
        "deactivated" : null,
        "facts_environment" : "production",
        "report_environment" : "production",
        "catalog_environment" : "production",
        "facts_timestamp" : "2015-06-22T17:25:11.886Z",
        "expired" : null,
        "report_timestamp" : "2015-06-22T17:25:07.484Z",
        "certname" : "foo.example.com",
        "catalog_timestamp" : "2015-06-22T17:25:12.023Z",
        "latest_report_hash" : "754b0b87af9ee647507b5aa3001f44f8e8843216",
        "latest_report_noop": true,
        "cached_catalog_status": "not_used",
        "latest_report_status" : "unchanged"
    } ]

Implicit subqueries are then just real queries embedded in the filtering part of
the query itself. You can mix and match subquery filters also like so:

    nodes {
      facts { name = "operatingsystem" and value = "Debian" } and
      resources { type = "Class" and title = "apache" }
    }

This query will query all `nodes` that are Debian and have the class
Class[apache] defined.

For more information regarding implicit subqueries, consult the
[reference guide][implicit].
