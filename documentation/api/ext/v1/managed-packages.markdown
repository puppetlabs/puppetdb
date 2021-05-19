---
title: "Managed Packages endpoints"
layout: default
canonical: "/puppetdb/latest/api/query/v4/managed-packages.html"
---

# Managed Packages endpoints

[curl]: ../curl.html#using-curl-from-localhost-non-sslhttp
[paging]: ./paging.html

> **PE-only**: The Managed Packages endpoints are only available for Puppet
> Enterprise.

## `managed-packages`

Returns all installed packages along with the certname of the nodes they are
installed on. For the puppet managed packages provides even resource hash and
manifest file.

### Query fields

* `certname` (string): The certname of the node the package data was collected
  from.

* `package_name` (string): The name of the package. (e.g. `emacs24`)

* `version` (string): The version of the package, in the format used by the
  package provider. (e.g. `24.5+1-6ubuntu1`)

* `provider` (string): The name of the provider which the package data came from;
  typically the name of the packaging system. (e.g. `apt`)

* `resource` (string): a SHA-1 hash of the managed resource's type, title, and parameters, for identification.

* `file` (string): the manifest file in which the managed resource was declared.

* `line` (number): the line of the manifest on which the managed resource was declared.

* `managed_version` (string): The version of the package that Puppet is trying to maintain

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {"certname":<string>,
     "package_name":<string>,
     "version":<string>,
     "provider":<string>,
     "resource":<string>,
     "file":<string>,
     "line":<integer>,
     "managed_version":<string>}

The array is unsorted by default.


### Example

[You can use `curl`][curl] to query information about managed packages:

    curl -G http://localhost:8080/pdb/query/v4 --data-urlencode 'query=["from", "managed-packages", ["~", "package_name", "ssl"]]'


## `extended-managed-packages`

Returns the same data as managed-packages but adds environment, os and os_release fields.

### Query fields

Same as managed-packages plus the following

* `environment` (string): The environment of the node

* `os` (string): The operating system of the node

* `os_release` (string): The operating system release of the node

### Response format

The response is a JSON array of hashes, where each hash has the form:

    {"certname":<string>,
     "package_name":<string>,
     "version":<string>,
     "provider":<string>,
     "resource":<string>,
     "file":<string>,
     "line":<integer>,
     "managed_version":<string>,
     "environment":<string>,
     "os":<string>,
     "os_release":<string>}

The array is unsorted by default.


### Example

[You can use `curl`][curl] to query information about managed packages:

    curl -G http://localhost:8080/pdb/query/v4 --data-urlencode 'query=["from", "extended-managed-packages", ["~", "package_name", "ssl"]]'


## Paging

This query endpoint supports paged results via the common PuppetDB paging
URL parameters. For more information, please see the documentation
on [paging][paging].
