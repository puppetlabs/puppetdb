---
title: "PuppetDB 3.0 » Command API"
layout: default
canonical: "/puppetdb/latest/api/command/v1/commands.html"
---

[factsv4]: ../../wire_format/facts_format_v4.html
[catalogv6]: ../../wire_format/catalog_format_v6.html
[reportv5]: ../../wire_format/report_format_v5.html
[reportv6]: ../../wire_format/report_format_v6.html
[deactivatev3]: ../../wire_format/deactivate_node_format_v3.html

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

Commands are submitted via HTTP to the `/pdb/cmd/v1` URL and must
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

* All field names that were previously separated by dashes are
  separated by underscores.

* The catalog 'name' field has been renamed to 'certname'.

The payload is expected to be a Puppet catalog, as a JSON object,
conforming exactly to the [catalog wire format v6][catalogv6]. Extra
or missing fields are an error.

### "replace facts", version 4

* Similar to version 6 of replace catalog, previously dashed fields are now
  underscore-separated.

* The 'name' field has been renamed to 'certname', for consistency. 

See [fact wire format v4][factsv4] for more information on the
payload of this command.

### "deactivate node", version 3

* Previous versions of deactivate node required only the certname, as a raw JSON
  string. It is now formatted as a JSON map, and the `producer_timestamp`
  property has been added.

See [deactivate node wire format v3][deactivatev3] for more information on the
payload of this command.

### "store report", version 6

The version 6 store report command differs from previous versions by changing
from a `resource_events` property to a `resources` property. The
`resource_events` property was a merged version of `resources` and their
associated events `events`. This new version moves the command to use a similar
format to a raw Puppet report, with a list of `resources` each with an `events`
property containing a list of the resource's events.

The payload is expected to be a report, containing events that occurred on
Puppet resources. It is structured as a JSON object, conforming to the
[report wire format v6][reportv6].

## Deprecated Commands

### "store report", version 5

The version 5 store report command differs from version 4 in the addition of a
"noop" flag, which is a boolean indicating whether the report was produced by a
puppet run with --noop, as well as in the conversion of dash-separated fields to
underscored.

The payload is expected to be a report, containing events that occurred on
Puppet resources. It is structured as a JSON object, conforming to the
[report wire format v5][reportv5].

## Examples using curl

To post a `replace facts` command you can use the following curl command:

    curl -X POST \
      -H "Accept: application/json" \
      -H "Content-Type: application/json" \
      -d '{"command":"replace facts","version":4,"payload":{"certname":"test1","environment":"DEV","values":{"myfact":"myvalue"},"producer_timestamp":"2015-01-01"}}' \
      http://localhost:8080/pdb/cmd/v1

An example of `deactivate node`:

    curl -X POST \
      -H "Accept: application/json" \
      -H "Content-Type: application/json" \
      -d '{"command":"deactivate node","version":3,"payload":{"certname":"test1","producer_timestamp":"2015-01-01"}}' \
      http://localhost:8080/pdb/cmd/v1
