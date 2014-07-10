---
title: "PuppetDB 2.1 » Data Anonymization"
layout: default
canonical: "/puppetdb/latest/anonymization.html"
---

There are times when sharing your PuppetDB data is required, however due to the nature of the data it may contain sensitive items that need to be scrubbed or anonymized beforehand. For this purpose, we have written a tool that supports anonymizing a PuppetDB export file, resulting in a new 'anonymized' export file.

> **This tool is currently experimental:** While we have taken measures to ensure this tool is of the greatest quality it is still under development and may change without notice. If you like the tool, but find problems or have suggestions please let us know.

Using the tool
-----

First of all you should start by [exporting your existing database](./migrate.html#exporting-data-from-an-existing-puppetdb-database) using the `puppetdb export` tool:

    $ sudo puppetdb export --outfile ./my-puppetdb-export.tar.gz

This needs to be run on your PuppetDB instance preferably. See `puppetdb export -h` for more options for remote execution.

Once you have the export file, then you can utilise the `puppetdb anonymize` tool to transform that file:

    $ sudo puppetdb anonymize --infile ./my-puppetdb-export.tar.gz --outfile ./my-puppetdb-anonymized-export.tar.gz

The anonymization tool can run on any machine with PuppetDB installed so you can avoid putting load on your production systems by running it on your own desktop or another server if you like.

How does it work?
-----

The tool itself walks through your entire data set applying different rules to each of the leaf data based on the profile you have chosen. The data structure itself is left intact, while only the data contents are modified. The point here is to maintain the "shape" of the data without exposing the underlying data you may wish to scrub. We do this by always ensuring we replace data consistently, so while a string for example may have been replaced with something random - we make sure that all instances of that original string are replaced with the same random string throughout all your data.

By keeping the shape the data can be anonymized based on your needs but still hold some value to the consumer of your anonymized data.

Anonymization Profiles
-----

Anonymizing all data is often not that useful so we have provided you with a number of different profiles that you can choose from that will provide different levels of anonymization.

The profile itself can be chosen on the command line when the command is run. For example, you can choose the `low` profile as an option like so:

    $ sudo puppetdb anonymize --infile ./my-puppetdb-export.tar.gz --outfile ./my-puppetdb-anonymized-export.tar.gz --profile low

At the moment there are some different built-in profiles you can choose from, and each one is documented below. The default profile is none is specified is `moderate`.

> **Note:** While we do provide a way to create a custom profile with the `--config` switch this is an advanced interface that we have avoided documenting due to its experimental nature (and the fact that we may change this at any time in the future). If you have have specific requirements and the built-in profiles do not suffice, please let us know and we can help hand-craft a suitable configuration to suit your needs.

### Profile: full

The `full` profile will anonymize everything, while keeping the shape of data as previously mentioned. This includes: node names, resource types, resource titles, parameter names, values, any log messages, file names and file lines. The result should be a completely anonymized data set.

This is useful if you really paranoid about what data you expose, but provides the least amount of usefulness for the consumer of such data depending on the activity they are trying to test.

### Profile: moderate

> **Note:** If no profile is specified, this is the default.

The `moderate` profile attempts to be a bit smarter about what it anonymizes and is the recommended one for most cases. It operates different depending on the data type:

* node name: is anonymized by default always
* resource type name: the core types that are built-in to Puppet are not anonymized, including some common types from the modules: stdlib, postgresql, rabbitmq, puppetdb, apache, mrep, f5, apt, registry, concat and mysql. Any Puppet Enterprise core types names are also preserved. The goal here is to anonymize any custom or unknown resource type names as they may contain confidential information.
* resource titles: all titles are anonymized expect for those belonging to Filebucket, Package, Service and Stage.
* parameter names: are never anonymized
* parameter values: everything is anonymized except for the values for `provider`, `ensure`, `noop`, `loglevel`, `audit` and `schedule`.
* report log messages: are always anonymized
* file names: are always anonymized
* file numbers: are left as they are

### Profile: low

This profile is aimed at hiding security information specifically, but leaving most of the data in its original state. By default most things are not anonymized except for:

* node name: is always anonymized
* parameter values: we specifically anonymize any values and messages for any parameter name containing the strings: password, pwd, secret, key, private. Everything else is left alone.

Verifying your Anonymized Data
-----

While the tool itself tries to achieve the documented level of anonymization, this is your precious data and we recommend that you analyze it first before sharing it with another party to ensure all your requirements are met.

The best way to do this, is to untar the export file, and analyze the contents:

    $ tar -xzf my-puppetdb-anonymized-export.tar.gz
    $ cd puppetdb-bak

Inside this directory there is a directory for each content type: `reports`, `catalogs` and each file inside represents a node (and a report instance for reports). The data is represented as pretty-formatted JSON so you can open these files up yourself and use tools such as `grep` to find any specific information you might have wanted to anonymize.
