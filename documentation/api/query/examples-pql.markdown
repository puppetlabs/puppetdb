---
title: "Puppet Query Language (PQL) examples"
layout: default
---

# Examples

[tutorial]: ./tutorial-pql.markdown
[reference]: ./v4/pql.markdown

## Example Queries

Below is a list of example PQL queries that you may find useful.

Other resources you may also find useful include:

* [PQL tutorial][tutorial]
* [PQL reference guide][reference]

***

### Filtering on node names

Query nodes with `green` in their name.

``` ruby
nodes { certname ~ 'green' }
```

*Output:*

``` json
[
    {
        "cached_catalog_status": "not_used",
        "catalog_environment": "production",
        "catalog_timestamp": "2016-08-15T11:06:26.275Z",
        "certname": "greenserver.vm",
        "deactivated": null,
        "expired": null,
        "facts_environment": "production",
        "facts_timestamp": "2016-08-15T11:06:26.140Z",
        "latest_report_corrective_change": null,
        "latest_report_hash": "4a956674b016d95a7b77c99513ba26e4a744f8d1",
        "latest_report_noop": false,
        "latest_report_noop_pending": null,
        "latest_report_status": "changed",
        "report_environment": "production",
        "report_timestamp": "2016-08-15T11:06:18.393Z"
    }
]
```

***

### Querying for inactive nodes

``` ruby
nodes { node_state = "inactive" }
```

*Output:*

``` json
[
    {
        "cached_catalog_status": "not_used",
        "catalog_environment": "production",
        "catalog_timestamp": "2016-08-15T11:06:26.275Z",
        "certname": "foo.com",
        "deactivated": "2016-08-17T13:04:41.421Z",
        "expired": null,
        "facts_environment": "production",
        "facts_timestamp": "2016-08-15T11:06:26.140Z",
        "latest_report_corrective_change": null,
        "latest_report_hash": "az956674b016d95a7b77c99513ba26e4a744f8d1",
        "latest_report_noop": false,
        "latest_report_noop_pending": null,
        "latest_report_status": "changed",
        "report_environment": "production",
        "report_timestamp": "2016-08-15T11:06:18.393Z"
    }
]
```

***

### Basic fact filtering

Nodes with operating system name `CentOS`.

``` ruby
inventory { facts.os.name = "CentOS" }
```

*Output (abbreviated):*

``` json
[
    {
        "certname": "centos70.vm",
        "environment": "production",
        "facts": {
            ...
            "operatingsystem": "CentOS",
            "operatingsystemmajrelease": "7",
            "operatingsystemrelease": "7.0.1406",
            "os": {
                "architecture": "x86_64",
                "family": "RedHat",
                "hardware": "x86_64",
                "name": "CentOS",
                "release": {
                    "full": "7.0.1406",
                    "major": "7",
                    "minor": "0"
                },
                "selinux": {
                    "enabled": false
                }
            },
            "osfamily": "RedHat",
            ...
        },
        "timestamp": "2016-08-15T11:06:26.140Z",
        "trusted": {
            "authenticated": "remote",
            "certname": "centos70.vm",
            "domain": "vm",
            "extensions": {},
            "hostname": "centos70"
        }
    }
]
```

***

### Fact and resource filtering

Nodes with `CentOS` operating system, and with a declared `httpd` service resource.

``` ruby
inventory[certname] { facts.operatingsystem = "CentOS" and
                      resources { type = "Service" and title = "httpd" } }
```

*Output:*

``` json
[
    {
        "certname": "greenserver.vm"
    }
]
```

***

### Fact, resource and resource parameter filtering

`RedHat` boxes in the `PDX` datacenter, that have their `java` package forced to `1.7.0`.

``` ruby
inventory[certname] { facts.osfamily = "RedHat" and
                      facts.datacentre = "PDX" and
                      resources { type = "Package" and
                                  title = "java" and
                                  parameters.ensure = "1.7.0" } }
```

*Output:*

``` json
[
    {
        "certname": "greenserver.vm"
    }
]
```

***

### Fact and resource filtering for classes

CentOS boxes with the `apache` Puppet class.

``` ruby
inventory[certname] { facts.operatingsystem = "CentOS" and
                      resources { type = "Class" and
                                  title = "Apache" } }
```

*Output:*

``` json
[
    {
        "certname": "greenserver.vm"
    }
]
```

***

### Fact, resource and environment filtering

All windows servers, with service `sqlserver` in a particular feature branch environment.

``` ruby
nodes { certname in inventory[certname] { facts.osfamily = "Windows" } and
        resources { type = "Service" and
                    title = "sqlserver" } and
        report_environment = "feature_SYS-4926" }
```

*Output:*

``` json
[ {
  "deactivated" : null,
  "latest_report_hash" : "4a956674b016d95a7b77c99513ba26e4a744f8d1",
  "facts_environment" : "laboratory",
  "cached_catalog_status" : "not_used",
  "report_environment" : "laboratory",
  "latest_report_corrective_change" : false,
  "catalog_environment" : "laboratory",
  "facts_timestamp" : "2016-07-18T04:12:12.912Z",
  "latest_report_noop" : false,
  "expired" : null,
  "latest_report_noop_pending" : null,
  "report_timestamp" : "2016-07-18T04:12:15.907Z",
  "certname" : "windowserver.vm",
  "catalog_timestamp" : "2016-07-18T04:12:12.917Z",
  "latest_report_status" : "success"
} ]
```

### Fact, report status filtering with dot notation

Get only the `certname`, `os.family` and `puppetversion` for all nodes whose most recent
report indicated a failure.

```
inventory[certname, facts.os.family, facts.puppetversion] {
  certname in nodes[certname] { latest_report_status = "failed" }
}
```

Output:

```json
[ {
  "certname" : "server.vm",
  "facts.os.family": "Debian",
  "facts.puppetversion": "6.8.1"
} ]
```

***

### Timestamp filtering

Nodes that haven't checked in for 7 days.

``` ruby
nodes { report_timestamp <= "2016-08-03 00:00:00" }
```

*Output:*

``` json
[
    {
        "cached_catalog_status": "not_used",
        "catalog_environment": "production",
        "catalog_timestamp": "2016-08-15T11:06:26.275Z",
        "certname": "greenserver.vm",
        "deactivated": null,
        "expired": null,
        "facts_environment": "production",
        "facts_timestamp": "2016-08-15T11:06:26.140Z",
        "latest_report_corrective_change": null,
        "latest_report_hash": "4a956674b016d95a7b77c99513ba26e4a744f8d1",
        "latest_report_noop": false,
        "latest_report_noop_pending": null,
        "latest_report_status": "changed",
        "report_environment": "production",
        "report_timestamp": "2016-08-15T11:06:18.393Z"
    }
]
```

***

### Profile querying

Show active nodes that have the profile class `Profile::Remote_mgmt` applied to it.

``` ruby
nodes { resources { type = "Class" and title = "Profile::Remote_mgmt" } }
```

*Output:*

``` json
[
    {
        "cached_catalog_status": "not_used",
        "catalog_environment": "production",
        "catalog_timestamp": "2016-08-15T11:06:26.275Z",
        "certname": "greenserver.vm",
        "deactivated": null,
        "expired": null,
        "facts_environment": "production",
        "facts_timestamp": "2016-08-15T11:06:26.140Z",
        "latest_report_corrective_change": null,
        "latest_report_hash": "4a956674b016d95a7b77c99513ba26e4a744f8d1",
        "latest_report_noop": false,
        "latest_report_noop_pending": null,
        "latest_report_status": "changed",
        "report_environment": "production",
        "report_timestamp": "2016-08-15T11:06:18.393Z"
    }
]
```

***

### Querying catalog submission time

Check for nodes that haven't had a catalog applied since a certain time.

``` ruby
nodes { catalog_timestamp < "2016-08-15T11:37:00.000Z" }
```

*Output:*

``` json
[
    {
        "cached_catalog_status": "not_used",
        "catalog_environment": "production",
        "catalog_timestamp": "2016-08-15T11:06:26.275Z",
        "certname": "greenserver.vm",
        "deactivated": null,
        "expired": null,
        "facts_environment": "production",
        "facts_timestamp": "2016-08-15T11:06:26.140Z",
        "latest_report_corrective_change": null,
        "latest_report_hash": "4a956674b016d95a7b77c99513ba26e4a744f8d1",
        "latest_report_noop": false,
        "latest_report_noop_pending": null,
        "latest_report_status": "changed",
        "report_environment": "production",
        "report_timestamp": "2016-08-15T11:06:18.393Z"
    }
]
```

***

### List of nodes with report status failed

List all nodes that have had a failure on their last run.

``` ruby
nodes { latest_report_status = 'failed' }
```

*Output:*

``` json
[
    {
        "cached_catalog_status": "not_used",
        "catalog_environment": "production",
        "catalog_timestamp": "2016-08-15T11:03:26.275Z",
        "certname": "redserver.vm",
        "deactivated": null,
        "expired": null,
        "facts_environment": "production",
        "facts_timestamp": "2016-08-15T11:03:26.140Z",
        "latest_report_corrective_change": null,
        "latest_report_hash": "68f56674b016d95a7b77c99513ba26e4a744f001",
        "latest_report_noop": false,
        "latest_report_noop_pending": null,
        "latest_report_status": "failed",
        "report_environment": "production",
        "report_timestamp": "2016-08-15T11:03:18.393Z"
    }
]
```

***

### Query code_id from latest reports

Query all latest reports and show the certname and code_id.

``` ruby
reports[certname, code_id] { latest_report? = true }
```

*Output:*

``` json
[
    {
        "certname": "greenserver.vm",
        "code_id": "urn:puppet:code-id:1:519e404a1b6217b010cc543494c2dc50df8a53e3;production"
    },
    {
        "certname": "yellowserver.vm",
        "code_id": "urn:puppet:code-id:1:519e404a1b6217b010cc543494c2dc50df8a53e3;production"
    }
]
```

***

### Reports that have not applied a code_id

Show reports that have not had a particular code_id applied.

``` ruby
reports[certname, receive_time] {
  latest_report? = true and
  ! code_id = 'urn:puppet:code-id:1:519e404a1b6217b010cc543494c2dc50df8a53e3;production'
}
```

*Output:*

``` json
[
    {
        "certname": "whiteserver.vm",
        "receive_time": "2016-08-15T10:33:07.130Z"
    },
    {
        "certname": "brownserver.vm",
        "receive_time": "2016-08-15T11:06:26.553Z"
    }
]
```

***

### Show all exported resources

Show all exported resources

``` ruby
resources[certname, type, title] { exported = true }
```

*Output:*

``` json
[
    {
        "certname": "purpleserver.vm",
        "title": "purpleserver.vm-mysql",
        "type": "Monitor"
    },
    {
        "certname": "purpleserver.vm",
        "title": "purpleserver.vm-httpd",
        "type": "Monitor"
    },
    {
        "certname": "purpleserver.vm",
        "title": "purpleserver.vm-/etc",
        "type": "Backup"
    }
]
```
