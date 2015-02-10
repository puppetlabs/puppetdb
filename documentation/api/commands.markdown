---
title: "PuppetDB 2.2 » API » Commands"
layout: default
canonical: "/puppetdb/latest/api/commands.html"
---

[factsv4]: ./wire_format/facts_format_v4.html
[catalogv6]: ./wire_format/catalog_format_v6.html
[reportv5]: ./wire_format/report_format_v5.html

Commands are used to change PuppetDB's
model of a population. Commands are represented by `command objects`,
which have the following JSON wire format:

    {"command": "...",
     "version": 123,
     "payload": <json object>}

`command` is a string identifying the command.

`version` is a JSON integer describing what version of the given
command you're attempting to invoke. The version of the command
also indicates the version of the wire format to use for the command.

`payload` must be a valid JSON object of any sort. It's up to an
individual handler function to determine how to interpret that object.

The entire command MUST be encoded as UTF-8.

## Command submission

Commands are submitted via HTTP to the `/commands` URL and must
conform to the following rules:

* A `POST` is used
* The `POST` body must contain the JSON payload.
* There is an `Accept` header that matches `application/json`.
* The content-type is `application/json`.

Optionally, there may be a query parameter, `checksum`, that contains a SHA-1 hash of
the payload which will be used for verification.

When a command is successfully submitted, the submitter will
receive the following:

* A response code of 200
* A content-type of `application/json`
* A response body in the form of a JSON object, containing a single key 'uuid', whose
  value is a UUID corresponding to the submitted command. This can be used, for example, by
  clients to correlate submitted commands with server-side logs.

The PuppetDB termini for puppet masters use this command API to update facts, catalogs, and reports for nodes.

## Command Semantics

Commands are processed _asynchronously_. If PuppetDB returns a 200
when you submit a command, that only indicates that the command has
been _accepted_ for processing. There are no guarantees as to when
that command will be processed, nor that when it is processed it will
be successful.

Commands that fail processing will be stored in files in the "dead
letter office", located under the MQ data directory, in
`discarded/<command>`. These files contain the command and diagnostic
information that may be used to determine why the command failed to be
processed.

## List of Commands

### "replace catalog", version 6

Version 6 differs from version 5 only in that all field names that were
previously separated by dashes are separated by underscores.

The payload is expected to be a Puppet catalog, as a JSON object, conforming
exactly to the [catalog wire format v6][catalogv6]. Extra or missing fields
are an error.

### "replace facts", version 4

Similar to version 6 of replace catalog, this version of replace facts differs
from version 3 only in that the previously dashed fields are now
underscore-separated.

See [fact wire format v3][factsv3] for more information on the
payload of this command.

### "deactivate node", version 2

The payload is expected to be the name of a node, as a raw JSON string, which will be deactivated
effective as of the time the command is *processed*. Serialization of the payload is no
longer required.

### "store report", version 5

As with version 4 or replace facts and version 6 of replace catalog, the
version 5 store report command differs from version 4 only in that previously
dash-separated fields are underscore-separated.

The payload is expected to be a report, containing events that occurred on Puppet
resources.  It is structured as a JSON object, conforming to the
[report wire format v5][reportv5].

## Examples using curl

To post a `replace facts` command you can use the following curl command:

    curl -X POST \
      -H "Accept: application/json" \
      -H "Content-Type: application/json" \
      -d '{"command":"replace facts","version":3,"payload":{"name":"test1","environment":"DEV","values":{"myfact":"myvalue"}}}' \
      http://localhost:8080/v4/commands

An example of `deactivate node`:

    curl -X POST \
      -H "Accept: application/json" \
      -H "Content-Type: application/json" \
      -d '{"command":"deactivate node","version":2,"payload":"test1"}' \
      http://localhost:8080/v4/commands
