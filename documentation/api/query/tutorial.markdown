---
title: "PuppetDB 1.3 » API » Query Tutorial"
layout: default
canonical: "/puppetdb/latest/api/query/tutorial.html"
---

This page is a walkthrough for constructing several types of PuppetDB queries. It uses the **version 2 API** in all of its examples; however, most of the general principles are also applicable to the version 1 API. 

If you need to use the v1 API, note that it lacks many of v2's capabilities, and be sure to consult the v1 endpoint references before attempting to use these examples with it.

## How to Query

Queries are performed by performing an HTTP GET request to an endpoint URL and supplying a URL parameter called `query`,
which contains the query to execute. Results are always returned in
`application/json` form. 

Oueries are usually issued from code, but you can easily issue them from the command line using curl.

### Querying with Curl

[See "Curl Tips" for more detailed information about constructing curl commands.](./curl.html) 

**Without SSL:**

`curl -H 'Accept: application/json' -X GET http://puppetdb.example.com:8080/v2/resources --data-urlencode query@<filename>`

This requires that PuppetDB be [configured to accept non-SSL connections][config_jetty]. By default, it will only accept unencrypted traffic from `localhost`.

[config_jetty]: ../../configure.html#jetty-http-settings

**With SSL:**

`curl -H 'Accept: application/json' -X GET https://puppetdb.example.com:8081/v2/resources --cacert /etc/puppet/ssl/certs/ca.pem --cert /etc/puppet/ssl/certs/thisnode.pem --key /etc/puppet/ssl/private_keys/thisnode.pem --data-urlencode query@<filename>`

This requires that you specify a certificate (issued by the same CA PuppetDB trusts), a private key, and a CA certificate.

In both examples, `<filename>` should be a file that contains the query to execute.


## Resources Walkthrough

### Our First Query

Let's start by taking a look at a simple resource query. Suppose we want to
find the user "nick" on every node. We can use this query:

    ["and",
      ["=", "type", "User"],
      ["=", "title", "nick"]]

This query has two `"="` clauses, which both must be true.

In general, the `"="` operator follows a specific structure:

`["=", <attribute to compare>, <value>]`

In this case, the attributes are "type" and "title", and the values are "User"
and "nick".

The `"and"` operator also has a well-defined structure:

`["and", <query clause>, <query clause>, <query clause>, ...]`

The query clauses can be any legal query (including another `"and"`). At least
one clause has to be specified, and all the clauses have to be true for the
`"and"` clause to be true. An `"or"` operator is also available, which looks
just like the `"and"` operator, except that, as you'd expect, it's true if
*any* specified clause is true.

The query format is declarative; it describes conditions the results must
satisfy, not how to find them. So the order of the clauses is irrelevant.
Either the type clause or the title clause could come first, without affecting
the performance or the results of the query.

If we execute this query against the `/resources` route, we get results that
look something like this:

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
      "sourceline" : 111,
      "sourcefile" : "/etc/puppet/manifests/user.pp",
      "exported" : false,
      "tags" : [ "firewall", "default", "node", "nick", "role::base", "users", "virtual", "user", "account", "base", "role::firewall::office", "role", "role::firewall", "class", "account::user", "office", "virtual::users", "allstaff" ],
      "title" : "nick",
      "type" : "User",
      "resource" : "0ae7e1230e4d540caa451d0ade2424f316bfbf39",
      "certname" : "foo.example.com"
    }]

Our results are an array of "resources", where each resource is an object with
a particular set of keys.

parameters: this field is itself an object, containing all the parameters and values of the resource
sourceline: the line the resource was declared on
sourcefile: the file the resource was specified in
exported: true if the resource was exported by this node, or false otherwise
tags: all the tags on the resource
title: the resource title
type: the resource type
resources: this is an internal identifier for the resource used by PuppetDB
certname: the node that the resource came from

There will be an entry in the list for every resource. A resource is specific
to a single node, so if the resource is on 100 nodes, there will be 100 copies
of the resource (each with at least a different certname field).

### Excluding results

We know this instance of the user "nick" is defined on line 111 of
/etc/puppet/manifests/user.pp. What if
we want to check whether or not we define the same resource somewhere else?
After all, if we're repeating ourselves, something may be wrong! Fortunately,
there's an operator to help us:

    ["and",
      ["=", "type", "User"],
      ["=", "title", "nick"],
      ["not",
        ["and",
          ["=", "sourceline", "/etc/puppet/manifests/user.pp"],
          ["=", "sourcefile", 111]]]]

The `"not"` operator wraps another clause, and returns results for which the
clause is *not* true. In this case, we want resources which aren't defined on
line 111 of /etc/puppet/manifests/user.pp.

### Resource Attributes

So far we've seen that we can query for resources based on their `certname`,
`type`, `title`, `sourcefile`, and `sourceline`. There are a few more available:

    ["and",
      ["=", "tag", "foo"],
      ["=", "exported", true],
      ["=", ["parameter", "ensure"], "present"]]

This query returns resources whose set of tags *contains* the tag
"foo", and which are exported, and whose "ensure" parameter is
"present". Because the parameter name can take any value (including
that of another attribute), it must be namespaced using
`["parameter", <parameter name>]`.

The full set of queryable attributes can be found in [the resource
endpoint documentation](./v2/resources.html) for easy reference.

### Regular Expressions

What if we want to restrict our results to a certain subset of nodes? Certainly, we could do something like:

    ["or",
      ["=", "certname", "www1.example.com"],
      ["=", "certname", "www2.example.com"],
      ["=", "certname", "www3.example.com"]]

And this works great if we know exactly the set of nodes we want. But what if
we want all the 'www' servers, regardless of how many we have? In this case, we
can use the regular expression match operator `~`:

    ["~", "certname", "www\\d+\\.example\\.com"]

Notice that, because our regular expression is specified inside a string, the
backslash characters must be escaped. The rules for which constructs can be
used in the regexp depend on which database is in use, so common features
should be used for interoperability. The regexp operator can be used on every
field of resources except for parameters, and `exported`.

## Facts Walkthrough

In addition to resources, we can also query for facts. This looks similar,
though the available fields and operators are a bit different. Some things are
the same, though. For instance, support you want all the facts for a certain
node:

    ["=", "certname", "foo.example.com"]

This gives results that look something like this:

    [ {
      "certname" : "foo.example.com",
      "name" : "architecture",
      "value" : "amd64"
    }, {
      "certname" : "foo.example.com",
      "name" : "fqdn",
      "value" : "foo.example.com"
    }, {
      "certname" : "foo.example.com",
      "name" : "hostname",
      "value" : "foo"
    }, {
      "certname" : "foo.example.com",
      "name" : "ipaddress",
      "value" : "192.168.100.102"
    }, {
      "certname" : "foo.example.com",
      "name" : "kernel",
      "value" : "Linux"
    }, {
      "certname" : "foo.example.com",
      "name" : "kernelversion",
      "value" : "2.6.32"
    } ]

### Fact Attributes

In the last query, we saw that a "fact" consists of a "certname", a "name", and
a "value". As you might expect, we can query using "name" or "value".

    ["and",
      ["=", "name", "operatingsystem"],
      ["=", "value", "Debian"]]

This will find all the "operatingsystem = Debian" facts, and their
corresponding nodes. As you see, "and" is supported for facts, as are "or" and
"not".

### Fact Operators

As with resources, facts also support the `~` regular expression match
operator, for all their fields. In addition to that, numeric comparisons are
supported for fact values:

    ["and",
      ["=", "name", "uptime_seconds"],
      [">=", "value", 100000],
      ["<", "value", 1000000]]

This will find nodes for which the uptime_seconds fact is in the half-open
range [100000, 1000000). Numeric comparisons will *always be false* for fact
values which are not numeric. Importantly, version numbers such as 2.6.12 are
not numeric, and the numeric comparison operators can't be used with them at
this time.

## Nodes Walkthrough

We can also query for nodes. Once again, this is quite similar to resource and
fact queries:

    ["=", "name", "foo.example.com"]

The result of this query is:

    ["foo.example.com"]

This will find the node foo.example.com. Note that the results of a node query
contain only the node names, rather than an object with multiple fields as with
resources and facts.

### Querying on Facts

Nodes can also be queried based on their facts, using the same operators as for
fact queries:

    ["and",
      ["=", ["fact", "operatingsystem"], "Debian"],
      ["<", ["fact", "uptime_seconds"], 10000]]

This will return Debian nodes with uptime_seconds < 10000.

## Subquery Walkthrough

The queries we've looked at so far are quite powerful and useful, but what if
your query needs to consider both resources *and* facts? For instance, suppose
you need the IP address of your Apache servers, to configure a load balancer.
You could find those servers using this resource query:

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
second query. No, there has to be a better way. What if we could find the
Class[Apache] servers and use the results of that directly to find the
certname? It turns out we can, with this fact query:

    ["and",
      ["=", "name", "ipaddress"],
      ["in", "certname",
        ["extract", "certname", ["select-resources",
                                  ["and",
                                    ["=", "type", "Class"],
                                    ["=", "title", "Apache"]]]]

This may appear a little daunting, so we'll look at it piecewise.

Let's start with "select-resources". This operator takes one argument, which is
a resource query, and returns the results of that query, in exactly the form
you would expect to see them if you did a plain resource query.

We then use an operator called "extract" to turn our list of resources into
just a list of certnames. So we now conceptually have something like

    ["in", "certname", ["foo.example.com", "bar.example.com", "baz.example.com"]]

The "in" operator matches facts whose "certname" is in the supplied list. (For
now, that list has to be generated from a subquery, and can't be supplied
directly in the query, so if you want a literal list, you'll unfortunately
still have to use a combination of "or" and "="). At this point, our query
seems a lot like the one above, except we didn't have to specify exactly which
certnames to use, and instead we get them in the same query.

Similarly, there is a "select-facts" operator which will perform a fact
subquery. Either kind of subquery is usable from every kind of query (facts,
resources, and nodes), subqueries may be nested, and multiple subqueries may be
used in a single query. Finding use cases for some of those combinations is
left as an exercise to the reader.
