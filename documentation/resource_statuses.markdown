---
title: "PuppetDB 3.2 » Resource Statuses (PE Only)"
layout: default
canonical: "/puppetdb/latest/resource_statuses.html"
---

[reports]: ./api/query/v4/reports.html
[terminus]: ./puppetdb_connection.markdown#include_unchanged_resources

## Resource Statuses

PE PuppetDB stores resources status from both unchanged and changed resources
from a Puppet report and surfaces that information via the [`/reports`][reports]
endpoint via a new `resources` field.

This feature will require additional disk space on your PostgreSQL instance.

For information about configuration of unchanged resources in reports, see the
[`terminus configuration section`][terminus].
