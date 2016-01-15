---
title: "PuppetDB 3.2: Archive endpoint"
layout: default
canonical: "/puppetdb/latest/api/admin/v1/archive.html"
---

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp

The `/archive` endpoint can be used for importing and exporting PuppetDB archives.

## `POST /pdb/admin/v1/archive`

This endpoint can be used for streaming a PuppetDB archive into PuppetDB.

### Request format

The request should be a multipart POST and have `Content-Type: multipart/mixed`.

### URL parameters

* `archive`: required. The archive file to import to the PuppetDB.
* `command_versions`: required. A JSON object mapping PuppetDB command names to their version. The mapping for a given PuppetDB archive can be found in the archive:

~~~ shell
    tar -xOf <my-pdb-archive>.tar.gz puppetdb-bak/export-metadata.json
~~~

### Response format

The response will be in `application/json`, and will return a JSON map upon successful completion of the importation:

    {"ok": true}

### Example

[Using `curl` from localhost][curl]:

        curl -X POST http://localhost:8080/pdb/admin/v1/archive \
        -F 'command_versions={"replace_facts":4,"store_report":5,"replace_catalog":6}'\
        -F "archive=@example_backup_archive.tgz"

    {"ok": true}

## `GET /pdb/admin/v1/archive`

This endpoint can be used to stream a tarred, gzipped backup archive of PuppetDB to your local machine.

### URL parameters

* `anonymization_profile`: optional. The level of anonymization applied to the archive files.

### Response format

The response will be a `application/octet-stream`, and will return a `tar.gz` archive.

### Example

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/admin/v1/archive -o puppetdb-export.tgz
