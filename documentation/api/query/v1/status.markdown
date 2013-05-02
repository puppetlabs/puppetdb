---
title: "PuppetDB 1.3 » API » v1 » Querying Status"
layout: default
canonical: "/puppetdb/latest/api/query/v1/status.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp


## Routes

### `GET /v1/status/nodes/<NODE>`

This will return status information for the given node. There must be
an `Accept` header containing `application/json`.


## Response Format

Node status information will be returned in a JSON hash of the form:

    {"name": <node>,
     "deactivated": <timestamp>,
     "catalog_timestamp": <timestamp>,
     "facts_timestamp": <timestamp>}

If the node is active, "deactivated" will be null. If a catalog or facts are
not present, the corresponding timestamps will be null.

If no information is known about the node, the result will be a 404 with a JSON
hash containing an "error" key with a message indicating such.

## Example

[Using `curl` from localhost][curl]:

    curl -H "Accept: application/json" 'http://localhost:8080/status/nodes/<node>'

Where <node> is the name of the node whose status you wish to check.
