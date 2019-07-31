---
title: "Admin commands endpoint"
layout: default
canonical: "/puppetdb/latest/api/admin/v1/cmd.html"
---

[curl]: ../../query/curl.html#using-curl-from-localhost-non-sslhttp
[config-purge-limit]: ../../../configure.markdown#node-purge-gc-batch-limit


The `/cmd` endpoint can be used to trigger PuppetDB maintenance
operations.  Only one maintenance operation can be running at a time.
Any request received while an operation is already in progress will
return an HTTP conflict status (409).

## `POST /pdb/admin/v1/cmd`

The maintenance operations must be triggered by a POST.

### Request format

The POST request should specify `Content-Type: application/json` and
the request body should look like this:

``` json
{"version" : 1, "payload" : [REQUESTED_OPERATION, ...]}
```

where valid `REQUESTED_OPERATION`s are `"expire_nodes"`,
`"purge_nodes"`, `"purge_reports"`, `"gc_packages"`, and `"other"`.
In addition, a purge_nodes operation can be structured like this to
specify a batch_limit:

``` json
["purge_nodes" {"batch_limit" : 50}]
```

When specified, the `batch_limit` restricts the maximum number of
nodes purged to the value specified, and if not specified, the limit
will be the [`node-purge-gc-batch-limit`][config-purge-limit].

An empty payload vector requests all maintenance operations.

### URL parameters

* The POST endpoint accepts no URL parameters.

### Response format

The response type will be `application/json`, and upon success will
include this JSON map:

``` json
{"ok": true}
```

If any other maintenance operation is already in progress the HTTP
response status will be 409 (conflict), will include a map like this

``` json
{"kind": "conflict",
 "msg": "Another cleanup is already in progress",
 "details": null}
```

and no additional maintenance will be performed.  The `msg` and
`details` may or may not vary, but the `kind` will always be
"conflict".

### Example

[Using `curl` from localhost][curl]:

``` sh
$ curl -X POST http://localhost:8080/pdb/admin/v1/cmd \
       -H 'Accept: application/json' \
       -H 'Content-Type: application/json' \
       -d '{"command": "clean",
            "version": 1,
            "payload": ["expire_nodes", "purge_nodes"]}'
{"ok": true}
```
