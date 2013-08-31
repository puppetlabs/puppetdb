---
title: "PuppetDB 1.4 » API » v3 » Query Structure"
layout: default
canonical: "/puppetdb/latest/api/query/v3/query.html"
---

[prefix]: http://en.wikipedia.org/wiki/Polish_notation
[jetty]: ../../../configure.html#jetty-http-settings
[index]: ../../index.html
[urlencode]: http://en.wikipedia.org/wiki/Percent-encoding
[operators]: ./operators.html
[tutorial]: ../tutorial.html
[curl]: ../curl.html

## Summary

PuppetDB's query API can retrieve data objects from PuppetDB for use in other applications. For example, the terminus plugins for puppet masters use this API to collect exported resources, and to translate node facts into the inventory service. 

The query API is implemented as HTTP URLs on the PuppetDB server. By default, it can only be accessed over the network via host-verified HTTPS; [see the jetty settings][jetty] if you need to access the API over unencrypted HTTP. 

## API URLs

The first component of an API URL is the API version, written as `v1`, `v3`, etc. This page describes version 2 of the API, so every URL will begin with `/v3`. After the version, URLs are organized into a number of **endpoints.**

### Endpoints

Conceptually, an endpoint represents a reservoir of some type of PuppetDB object. Each version of the PuppetDB API defines a set number of endpoints.

See the [API index][index] for a list of the available endpoints. Each endpoint may have additional sub-endpoints under it; these are generally just shortcuts for the most common types of query, so that you can write terser and simpler query strings. 

## Query Structure

A query consists of:

* An HTTP GET request to an endpoint URL...
* ...which may or may not contain a **query string** as a `query` URL parameter...
* ...and which must contain an `Accept` header matching `application/json`.

That is, nearly every query will look like a GET request to a URL that resembles the following:

    https://puppetdb:8081/v3/<ENDPOINT>?query=<QUERY STRING>

Query strings are optional for some endpoints, required for others, and prohibited for others; see each endpoint's documentation.

### Query Strings

A query string must be:

* A [URL-encoded][urlencode]...
* ...JSON array, which may contain scalar data types (usually strings) and additional arrays...
* ...which describes a complex _comparison operation..._
* ...in [_prefix notation._][prefix]

JSON arrays are delimited by square brackets (`[` and `]`), and items in the array are separated by commas. JSON strings are delimited by straight double-quotes (`"`) and must be UTF-8 text; literal double quotes and literal backslashes in the string must be escaped with a backslash (`"` is `\"` and `\` is `\\`).

"Prefix notation" means every array in a query string must begin with an [operator][operators], and the remaining elements in the array will be interpreted as that operator's arguments, in order. (The similarity to Lisp is intentional.)

A complete query string describes a comparison operation. When submitting a query, PuppetDB will check every _possible_ result from the endpoint to see if it matches the comparison from the query string, and will only return those objects that match. 

For a more complete description of how to construct query strings, see [the Operators page][operators]. 

## Query Responses

All queries return data with a content type of `application/json`. 

## Tutorial and Tips

For a walkthrough on constructing queries, see [the Query Tutorial page][tutorial]. For quick tips on using curl to make ad-hoc queries, see [the Curl Tips page][curl].

