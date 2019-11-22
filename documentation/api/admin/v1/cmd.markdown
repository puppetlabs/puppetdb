---
title: "Admin commands endpoint"
layout: default
canonical: "/puppetdb/latest/api/admin/v1/cmd.html"
---

[curl]: ../../query/curl.html#using-curl-from-localhost-non-sslhttp
[config-purge-limit]: ../../../configure.markdown#node-purge-gc-batch-limit


The `/cmd` endpoint can be used to trigger PuppetDB maintenance
operations or to directly delete a node.  Admin commands are processed
synchronously seperate from other PuppetDB commands.

## `POST /pdb/admin/v1/cmd`

Admin commands must be triggered by a POST.

### Request format

The POST request should specify `Content-Type: application/json` and
the request body should look like this:

``` json
{"command": "...",
 "version": 123,
 "payload": <json object>}
```

`command` is a string identifying the command.

`version` is a JSON integer describing what version of the given
command you're attempting to invoke. The version of the command
also indicates the version of the wire format to use for the command.

`payload` must be a valid JSON object of any sort. It's up to an
individual handler function to determine how to interpret that object.

### URL parameters

* The POST endpoint accepts no URL parameters.

## List of admin commands

## "clean", version 1

``` json
{"command" : "clean",
 "version" : 1,
 "payload" : [REQUESTED_OPERATION, ...]}
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

### Response format

The response type will be `application/json`, and upon success will
include this JSON map:

``` json
{"ok": true}
```

Only one maintenance operation can be running at a time.  If any other
maintenance operation is already in progress the HTTP response status will be
409 (conflict), will include a map like this

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

## "delete", version 1

``` json
{"command" : "delete",
 "version" : 1,
 "payload" : {"certname" : <string>}}
```

The `"delete"` command can be used to trigger the immediate deletion of all data
associated with a certname.  It is important to note that the delete operation
doesn't account for commands which may be in the command queue but not yet
processed by PuppetDB.  This could cause a node targeted for deletion to
reappear when the command in the queue gets processed after the deletion
operation has run.

### Response format

The response type will be `application/json`, and upon success will
include this JSON map:

``` json
{"deleted": "certname"}
```

### Example

[Using `curl` from localhost][curl]:

``` sh
$ curl -X POST http://localhost:8080/pdb/admin/v1/delete \
       -H 'Accept: application/json' \
       -H 'Content-Type: application/json' \
       -d '{"command": "delete",
            "version": 1,
            "payload": {"certname" : "node-1"}}'
{"deleted": "node-1"}
```
