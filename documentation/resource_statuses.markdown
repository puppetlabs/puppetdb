---
title: "PuppetDB 4.1 » Resource statuses (PE Only)"
layout: default
canonical: "/puppetdb/latest/resource_statuses.html"
---

[reports]: ./api/query/v4/reports.html
[terminus]: ./puppetdb_connection.markdown#include_unchanged_resources

## Resource statuses

In Puppet Enterprise, PuppetDB stores resource statuses from both unchanged and changed resources
in a Puppet report. This information is surfaced via the [`/reports`][reports]
endpoint in a new `resources` field.

This feature will require additional disk space on your PostgreSQL instance.

For information about the configuration of unchanged resources in reports, see the
[`include_unchanged_resources` section][terminus] of the PuppetDB configuration guide.
