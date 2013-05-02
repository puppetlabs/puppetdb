---
title: "PuppetDB 1.3 » API » Commands"
layout: default
canonical: "/puppetdb/latest/api/commands.html"
---

[facts]: ./wire_format/facts_format.html
[catalog]: ./wire_format/catalog_format.html
[report]: ./wire_format/report_format.html

Commands are used to change PuppetDB's
model of a population. Commands are represented by `command objects`,
which have the following JSON wire format:

    {"command": "...",
     "version": 123,
     "payload": <json object>}

`command` is a string identifying the command.

`version` is a JSON integer describing what version of the given
command you're attempting to invoke.

`payload` must be a valid JSON object of any sort. It's up to an
individual handler function to determine how to interpret that object.

The entire command MUST be encoded as UTF-8.

## Command submission

Commands are submitted via HTTP to the `/commands/` URL and must
conform to the following rules:

* A `POST` is used
* There is a parameter, `payload`, that contains the entire command object as
  outlined above. (Not to be confused with the `payload` field inside the command object.)
* There is an `Accept` header that contains `application/json`.
* The POST body is url-encoded
* The content-type is `x-www-form-urlencoded`.

Optionally, there may be a parameter, `checksum`, that contains a SHA-1 hash of
the payload which will be used for verification.

When a command is successfully submitted, the submitter will
receive the following:

* A response code of 200
* A content-type of `application/json`
* A response body in the form of a JSON object, containing a single key 'uuid', whose
  value is a UUID corresponding to the submitted command. This can be used, for example, by
  clients to correlate submitted commands with server-side logs.

The terminus plugins for puppet masters use this command API to update facts, catalogs, and reports for nodes. 

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

### "replace catalog", version 1

The payload is expected to be a Puppet catalog, as a JSON string, including the
fields of the [catalog wire format][catalog]. Extra fields are
ignored.

### "replace catalog", version 2

The payload is expected to be a Puppet catalog, as either a JSON string or an
object, conforming exactly to the [catalog wire
format][catalog]. Extra or missing fields are an error.

### "replace facts", version 1

The payload is expected to be a set of facts, as a JSON string, conforming to
the [fact wire format][facts]

### "deactivate node", version 1

The payload is expected to be the name of a node, as a JSON string, which will be deactivated
effective as of the time the command is *processed*.

## Experimental commands

### "store report", version 1

The payload is expected to be a report, containing events that occurred on Puppet
resources.  It is structured as a JSON object, confirming to the
[report wire format][report].
