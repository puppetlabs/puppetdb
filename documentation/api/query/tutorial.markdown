---
title: "PuppetDB: API query tutorial"
layout: default
canonical: "/puppetdb/latest/api/query/tutorial.html"
---

[array]: ./v4/ast.html#array
[curl]: ./curl.html
[select]: ./v4/ast.html#selectentity-subquery-statements
[config_jetty]: ../../configure.html#jetty-http-settings

This page walks through the construction of several types of PuppetDB
queries. We use the **version 4 API** in all examples.

## How to query

Queries are performed by issuing an HTTP GET or POST request to an endpoint URL
and specifying a `query` URL parameter (in the GET case) or a JSON-valued
payload in the POST case, which contains the query to execute. Results are
always returned in `application/json` form.

Queries are usually issued from code, but you can easily issue them from the command line by using curl.

### Querying with curl

[See the curl tips page for more information about constructing curl commands.][curl]

**Without SSL:**

    curl -X GET http://puppetdb.example.com:8080/pdb/query/v4/resources \
      --data-urlencode query@<FILENAME>

    curl -X POST http://puppetdb.example.com:8080/pdb/query/v4/resources \
      -H 'Content-Type:application/json'
      -d '{"query":["=","certname","foo.com"]}'

This requires that PuppetDB be [configured to accept non-SSL connections][config_jetty]. By default, it will only accept unencrypted traffic from `localhost`.


**With SSL:**

    curl -X GET https://puppetdb.example.com:8081/pdb/query/v4/resources \
      --tlsv1 \
      --cacert /etc/puppetlabs/puppet/ssl/certs/ca.pem \
      --cert /etc/puppetlabs/puppet/ssl/certs/thisnode.pem \
      --key /etc/puppetlabs/puppet/ssl/private_keys/thisnode.pem \
      --data-urlencode query@<FILENAME>

This requires that you specify a certificate (issued by the same CA PuppetDB trusts), a private key, and a CA certificate.

In both examples, `<filename>` should be a file that contains the query to execute.

### Querying with Puppet code

The PuppetDB terminus includes the `puppetdb_query` function, which can be used
to query PuppetDB from within a Puppet manifest. For example,

    $debian_nodes_query = ["from", "nodes", ["=", ["fact", "operatingsystem"], "Debian"]]
    $debian_nodes = puppetdb_query($debian_nodes_query).each |$value| { $value["certname"] }
    Notify {"Debian nodes":
        message => "Your debian nodes are ${join($debian_nodes, ', ')}",
    }

## Resources Walkthrough

### Our first query

Let's start by taking a look at a simple resource query. Suppose we want to
find the user "nick" on every node. We can use this query:

    ["and",
      ["=", "type", "User"],
      ["=", "title", "nick"]]

This query has two `"="` clauses, both of which must be true.

In general, the `"="` operator follows a specific structure:

`["=", <attribute to compare>, <value>]`

In this case, the attributes are "type" and "title", and the values are "User"
and "nick".

The `"and"` operator also has a well-defined structure:

`["and", <query clause>, <query clause>, <query clause>, ...]`

The query clauses can be any legal query (including another `"and"`). At least
one clause must be specified, and all the clauses must be true for the
`"and"` clause to be true. An `"or"` operator is also available, which looks
just like the `"and"` operator, except that, as you'd expect, it's true if
*any* specified clause is true.

The query format is declarative: it describes conditions the results must
satisfy, not how to find them. This means that the order of the clauses is irrelevant.

You can list either the type clause or the title clause first without impacting
the performance or the results of the query.

If we execute this query against the `/resources` route, and assuming that we're using the
production environment, we get results that look something like this:

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

Our results are an array of "resources", where each resource is an object with
a particular set of keys.

* `parameters`: this field is itself an object, containing all the parameters and values of the resource
* `line`: the line the resource was declared on
* `file`: the file the resource was specified in
* `exported`: true if the resource was exported by this node, or false otherwise
* `tags`: all the tags on the resource
* `title`: the resource title
* `type`: the resource type
* `resources`: this is an internal identifier for the resource used by PuppetDB
* `certname`: the node that the resource came from

There will be an entry in the list for every resource. A resource is specific
to a single node, so if the resource is on 100 nodes, there will be 100 copies
of the resource (each with at least a different certname field).

### Excluding results

We know this instance of the user "nick" is defined on line 111 of
`/etc/puppetlabs/code/environments/production/manifests/user.pp`. What if
we want to check whether or not we define the same resource somewhere else?
After all, if we're repeating ourselves, something may be wrong! Fortunately,
there's an operator to help us:

    ["and",
      ["=", "type", "User"],
      ["=", "title", "nick"],
      ["not",
        ["and",
          ["=", "file", "/etc/puppetlabs/code/environments/production/manifests/user.pp"],
          ["=", "line", 111]]]]

The `"not"` operator wraps another clause, and returns results for which the
clause is *not* true. In this case, we want resources which aren't defined on
line 111 of `/etc/puppetlabs/code/environments/production/manifests/user.pp`.

### Resource attributes

So far we've seen that we can query for resources based on their `certname`,
`type`, `title`, `file`, and `line`. There are a few more available:

    ["and",
      ["=", "tag", "foo"],
      ["=", "exported", true],
      ["=", ["parameter", "ensure"], "present"]]

This query returns resources whose set of tags *contains* the tag
"foo", and which are exported, and whose "ensure" parameter is
"present". Because the parameter name can take any value (including
that of another attribute), it must be namespaced using
`["parameter", <parameter name>]`.

For easy reference, the full set of queryable attributes can be found in [the resource
endpoint documentation](./v4/resources.html).

### Regular expressions

What if we want to restrict our results to a certain subset of nodes? We could use something like this:

    ["or",
      ["=", "certname", "www1.example.com"],
      ["=", "certname", "www2.example.com"],
      ["=", "certname", "www3.example.com"]]

And this works great if we know exactly the set of nodes we want. But what if
we want all the 'www' servers, regardless of how many we have? In this case, we
can use the regular expression match operator `~`:

    ["~", "certname", "www\\d+\\.example\\.com"]

Because our regular expression is specified inside a string, the
backslash characters must be escaped. The rules for which constructs can be
used in the regular expression depend on which database is in use, so common features
should be used for interoperability. The regular expression operator can be used on every
field of resources except for `parameters` and `exported`.

## Facts walkthrough

In addition to resources, we can also query for facts. This looks similar,
though the available fields and operators are a bit different. Some things are
the same, though. For instance, suppose you want all the facts for a certain
node:

    ["=", "certname", "foo.example.com"]

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

    ["and",
      ["=", "name", "operatingsystem"],
      ["=", "value", "Debian"]]

This will find all the "operatingsystem = Debian" facts, and their
corresponding nodes. As you see, "and" is supported for facts, as are "or" and
"not".

### Fact operators

As with resources, facts also support the `~` regular expression match
operator for all their fields. In addition, numeric comparisons are
supported for fact values:

    ["and",
      ["=", "name", "uptime_seconds"],
      [">=", "value", 100000],
      ["<", "value", 1000000]]

This will find nodes for which the "uptime_seconds" fact is in the
range 100000 to 1000000. Numeric comparisons will *always be false* for fact
values which are not numeric. Importantly, version numbers such as 2.6.12 are
not numeric, and numeric comparison operators can't be used with them at
this time.

## Nodes walkthrough

We can also query for nodes. Once again, this is quite similar to resource and
fact queries:

    ["=", "certname", "foo.example.com"]

The result of this query is:

    {
        "deactivated" : null,
        "facts_environment" : "production",
        "report_environment" : "production",
        "catalog_environment" : "production",
        "facts_timestamp" : "2015-06-22T17:25:11.886Z",
        "expired" : null,
        "report_timestamp" : "2015-06-22T17:25:07.484Z",
        "certname" : "foo.example.com",
        "catalog_timestamp" : "2015-06-22T17:25:12.023Z"
    }

This will return an object containing the certname "foo.example.com", as well
as some metadata detailing deactivation status and the most recent fact,
report, and catalog updates from that node.

### Querying on facts

Nodes can also be queried based on their facts, using the same operators as for
fact queries:

    ["and",
      ["=", ["fact", "operatingsystem"], "Debian"],
      ["<", ["fact", "uptime_seconds"], 10000]]

This will return Debian nodes with "uptime_seconds" less than 10,000.

## Subquery walkthrough

The queries we've looked at so far are quite powerful and useful, but what if
your query needs to consider both resources *and* facts?

For instance, suppose you're configuring a load balancer, and need the IP addresses of your Apache servers. You could find those servers by using this resource query:

    ["and",
      ["=", "type", "Class"],
      ["=", "title", "Apache"]]

This will find all the Class[Apache] resources, which each knows the certname
of the node it came from. Then you could put all those certnames into a fact
query:

    ["and",
      ["=", "name", "ipaddress"],
      ["or",
        ["=", "certname", "a.example.com"],
        ["=", "certname", "b.example.com"],
        ["=", "certname", "c.example.com"],
        ["=", "certname", "d.example.com"],
        ["=", "certname", "e.example.com"]]]

But this query is lengthy, and it requires some logic to assemble and run the
second query. There has to be a better way! What if we could find the
Class[Apache] servers and use the results of that query to find the
certname? We can, with this fact query:

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname", ["select_resources",
                                  ["and",
                                    ["=", "type", "Class"],
                                    ["=", "title", "Apache"]]]]

This may appear a little daunting, so we'll look at it piece by piece.

Let's start with "select_resources". This operator takes one argument, which is
a resource query, and returns the results of that query in exactly the form
you would expect to see them if you did a plain resource query.

We then use an operator called "extract" to turn our list of resources into
just a list of certnames. So we now conceptually have something like:

    ["in", "certname", ["foo.example.com", "bar.example.com", "baz.example.com"]]

The "in" operator matches facts whose "certname" is in the supplied list. (In our case, this list is generated by a subquery. To use a literal list, you must use the the "array" syntax described in the [AST array documentation][array].)

At this point, our query seems a lot like the one above, except we didn't have to specify exactly which certnames to use, and instead we get them in the same query.

Similarly, there are "select_facts", "select_nodes", and "select_fact_contents" operators,
which will perform subqueries against the facts, nodes, and fact-contents endpoints.
Any subquery operator is usable from any queryable endpoint. Subqueries may be nested,
and multiple subqueries may be used in a single query. For more information see
the [`select_<ENTITY>` documentation][select].
