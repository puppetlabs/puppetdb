---
title: "Query structure"
layout: default
canonical: "/puppetdb/latest/api/query/v4/query.html"
---

# Query structure

[prefix]: http://en.wikipedia.org/wiki/Polish_notation
[jetty]: ../../../configure.html#jetty-http-settings
[urlencode]: http://en.wikipedia.org/wiki/Percent-encoding
[ast]: ./ast.html
[tutorial]: ../tutorial.html
[curl]: ../curl.html
[paging]: ./paging.html
[entities]: ./entities.html
[root]: ./index.html
[pql]: ./pql.html

## Summary

PuppetDB's query API can retrieve data objects from PuppetDB for use in other
applications. For example, the PuppetDB-termini for Puppet Servers use this
API to collect exported resources.

The query API is implemented as HTTP URLs on the PuppetDB server. By default,
it can only be accessed over the network via host-verified HTTPS; [see the
jetty settings][jetty] if you need to access the API over unencrypted HTTP.

## Query structure

A query consists of an HTTP GET request to an endpoint URL which may or may not contain: 
* A `query` URL parameter, whose value is a **query string**. 
* Other URL parameters, to configure [paging][] or other behavior.

That is, most queries will look like a GET request to a URL that resembles the following:

    https://puppetdb:8081/pdb/query/v4/<ENDPOINT>?query=<QUERY STRING>

Alternatively, you can provide the entity context instead of using the `<ENDPOINT>` suffix with the following:

    https://puppetdb:8081/pdb/query/v4?query=<QUERY STRING>

Consult the [root] endpoint documentation for more details.

### API URLs

API URLs generally look like this:

    https://<SERVER>:<PORT>/pdb/query/<API VERSION>/<ENDPOINT>?<PARAMETER>=<VALUE>&<PARAMETER>=<VALUE>

For example: `https://puppetdb:8081/pdb/query/v4/resources?limit=50&offset=50`.

### API version

After the `/pdb/query/` prefix, the first part of an API URL is the
**API version,** written in the `v4` format. This section describes version
4 of the API, so every URL will begin with `/pdb/query/v4`.

### Entity endpoints

After the version, URLs are organized into a number of **endpoints** that express the entity you wish to query for.

Conceptually, an entity endpoint represents a PuppetDB entity. Each version of the PuppetDB API defines a set number of endpoints.

See the [entities documentation][entities] for a list of the available endpoints. Each endpoint may have additional sub-endpoints under it; these are generally just shortcuts for the most common types of query, so that you can write terser and simpler query strings.

### URL parameters

Finally, the URL may include some **URL parameters.** Some endpoints require certain parameters; for others they're optional or disallowed. Each endpoint's page lists the parameters it accepts, and most endpoints also support the [paging][] parameters.

A group of parameters begins with a question mark (`?`). Each parameter is formatted as `<PARAMETER>=<VALUE>`, and additional parameters are separated by ampersands (`&`). All parameter values must be [URL-encoded.][urlencode]

#### `query`

The most common URL parameter is `query`, which lets you define the set of results returned by most endpoints.

There are two query languages available in PuppetDB, consult the documentation for each for more details.

* [AST query language][ast]: a JSON based query language.
* [Puppet query language][pql]: a new query language designed for human users to simplify
  querying over the legacy AST language.

A complete query string describes a comparison operation. When submitting a query, PuppetDB will check every _possible_
result from the endpoint to see if it matches the comparison from the query string, and will only return those objects
that match.

> **Note:** Only the [root] endpoint supports PQL.

#### Paging

The next most common URL parameters are the **paging** parameters.

Most PuppetDB query endpoints support paged results via a set of shared URL parameters.  For more information, please see the documentation on [paging][paging].

## Query responses

All queries return data with a content type of `application/json`. Each endpoint's page describes the format of its return data.

### Rich data

Puppet 6 supports
[rich_data](https://github.com/puppetlabs/puppet-specifications/blob/master/language/types_values_variables.md#richdata)
types like Timestamp and SemVer, and enables rich data by default.
When rich data is enabled, readable string representations of rich
data values may appear in the report resource event `old_value` and
`new_value` fields, and in catalog parameter values.

For example, a Timestamp value would be recorded in PuppetDB as a
string like "2012-10-10T00:00:00.000000000 UTC", and a Deferred value
would be recorded as a string like "Deferred({'name' => 'join',
'arguments' => [[1, 2, 3], ':']})".

## Tutorial and tips

For a walkthrough on constructing queries, see [the query tutorial page][tutorial]. For quick tips on using curl to make ad hoc queries, see [the curl tips page][curl].

## Experimental query optimization

> *Note*: this feature is experimental and may be altered or removed
> in a future release, and while it is expected to be safe to enable
> it for queries that fall within the constraints described below,
> some caution is still advisable.  If something were to go wrong,
> the result set returned by the query might be incorrect.

PuppetDB has an experimental query optimizer that may be able to
substantially decrease the cost (and correspondingly decrease the
response time) of some queries.  It does this by attempting to avoid
retrieving unnecessary data when generating a response.

At the moment, this optimization can only be applied for queries that
ask for a subset of the available query response fields, for example,
a query against the nodes endpoint that only extracts the `certname`.
Further, for any given query it may or may not have any effect at all,
and the effect, if any, may vary across PuppetDB releases.  It is also
not currrently safe to apply the optimization to queries that include
aggregate/grouping operations or functions.

To enable the optimization for a query that does not include simply
add `optimize_drop_unused_joins=true` as a parameter.  For now, the
result data should be treated with some caution, and at a minimum, you
may want to inspect the results of the query to make sure that the
optimizer doesn't affect the result set.  If you'd like to determine
wheter or not PuppetDB attempted to optimize a query, any efforts
are logged at debug level.

(If you happen to have `diff` and `jq` installed, you should be able
 to compare the results of a given query with and without the
 optimizer by saving the data in each case to a file and then running
 a command like this:
 `diff -u <(jq -S . result-1.json) <(jq -S . result-2.json)`.)
