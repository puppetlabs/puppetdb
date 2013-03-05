---
title: "PuppetDB 1.2 » Migrating Data"
layout: default
canonical: "/puppetdb/1.2/migrate.html"
---

Migrating from ActiveRecord storeconfigs
-----

If you're using exported resources with ActiveRecord storeconfigs, you may want to migrate your existing data to PuppetDB before connecting the master to it. This will ensure that whatever resources were being collected by the agents will still be collected, and no incorrect configuration will be applied.

The existing ActiveRecord data can be exported using the `puppet storeconfigs export` command, which will produce a tarball that can be consumed by PuppetDB. Because this command is intended only to stop nodes from failing until they have check into PuppetDB, it will only include exported resources, excluding edges and facts.
