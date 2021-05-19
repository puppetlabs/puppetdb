---
title: "Edges endpoint"
layout: default
canonical: "/puppetdb/latest/api/query/v4/edges.html"
---
# Edges endpoint

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html
[query]: ./query.html
[subqueries]: ./ast.html#subquery-operators
[ast]: ./ast.html
[catalogs]: ./catalogs.html
[nodes]: ./nodes.html
[resources]: ./resources.html

Catalog edges are relationships formed between two [resources][resources].
They represent the edges inside the catalog graph, whereas resources represent
the nodes in the graph. You can query edges by making an HTTP request to the
`/edges` endpoint.

## `/pdb/query/v4/edges`

Returns all edges known to PuppetDB.

### URL parameters

* `query`: optional. A JSON array containing the query in prefix notation.
If not provided, all results will be returned. See the sections below for the
supported operators and fields. For general info about queries, see
[our guide to query structure.][query]

### Query operators

See [the AST query language page][ast].

### Query fields

* `certname` (string): the certname associated with the edge.
* `relationship` (string): the edge relationship. Can be `contains`, `before`, `required-by`, `notifies`, or `subscription-of`.
* `source_title` (string): the source resource title.
* `source_type` (string, with first letter always capitalized): the source resource type.
* `target_title` (string): the target resource title.
* `target_type` (string, with first letter always capitalized): the target resource type.

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {
      "certname": <string>,
      "relationship": <string>,
      "source_title": <string>,
      "source_type": <string>,
      "target_title": <string>,
      "target_type": <string>
    }

### Example

[You can use `curl`][curl] to query information about nodes:

    curl 'http://localhost:8080/pdb/query/v4/edges'

A sample response:

    [ {
      "certname" : "host-5",
      "relationship" : "required-by",
      "source_title" : "httpd",
      "source_type" : "Package",
      "target_title" : "authn_file.load",
      "target_type" : "File"
    }, {
      "certname" : "host-5",
      "relationship" : "contains",
      "source_title" : "/etc/apache2/ports.conf",
      "source_type" : "Concat",
      "target_title" : "concat_/etc/apache2/ports.conf",
      "target_type" : "Exec"
    }, {
      "certname" : "host-5",
      "relationship" : "notifies",
      "source_title" : "deflate.load",
      "source_type" : "File",
      "target_title" : "httpd",
      "target_type" : "Service"
    }, {
      "certname" : "host-5",
      "relationship" : "required-by",
      "source_title" : "mkdir /etc/apache2/mods-available",
      "source_type" : "Exec",
      "target_title" : "deflate.load",
      "target_type" : "File"
    }, {
      "certname" : "host-5",
      "relationship" : "notifies",
      "source_title" : "concat_/etc/apache2/ports.conf",
      "source_type" : "Exec",
      "target_title" : "/etc/apache2/ports.conf",
      "target_type" : "File"
    }, {
      "certname" : "host-5",
      "relationship" : "notifies",
      "source_title" : "authz_groupfile.load symlink",
      "source_type" : "File",
      "target_title" : "httpd",
      "target_type" : "Service"
    } ]

## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
