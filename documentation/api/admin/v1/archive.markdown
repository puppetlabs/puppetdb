---
title: "Archive endpoint"
layout: default
canonical: "/puppetdb/latest/api/admin/v1/archive.html"
---
# Archive endpoint

[curl]: ../../query/curl.markdown#using-curl-from-localhost-non-sslhttp

The `/archive` endpoint can be used for importing and exporting PuppetDB
archives.

## `POST /pdb/admin/v1/archive`

This endpoint can be used for streaming a PuppetDB archive into PuppetDB.

### Request format

The request should be a multipart POST and have `Content-Type: multipart/mixed`.

### URL parameters

* `archive`: required. The archive file to import to the PuppetDB. This archive
  must have a file called `puppetdb-bak/metadata.json` as the first entry in the
  tarfile with a key `command_versions` which is a JSON object mapping PuppetDB
  command names to their version.

### Response format

The response will be in `application/json`, and will return a JSON map upon
successful completion of the importation:

    {"ok": true}

### Example

[Using `curl` from localhost][curl]:

        curl -X POST http://localhost:8080/pdb/admin/v1/archive \
        -F "archive=@example_backup_archive.tgz"

    {"ok": true}

## `GET /pdb/admin/v1/archive`

This endpoint can be used to stream a tarred, gzipped backup archive of PuppetDB
to your local machine.

### URL parameters

* `anonymization_profile`: optional. The level of anonymization applied to the
  archive files.

### Response format

The response will be a `application/octet-stream`, and will return a `tar.gz`
archive.

### Example

[Using `curl` from localhost][curl]:

    curl -X GET http://localhost:8080/pdb/admin/v1/archive -o puppetdb-export.tgz
