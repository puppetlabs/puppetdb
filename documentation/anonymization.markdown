---
title: "Export, import and anonymization"
layout: default
---
# Exporting and anonymizing data

This document covers using the export, import and anonymization tools for
PuppetDB.

The export tool will return an archive of all of your PuppetDB data which can be
uploaded to another PuppetDB via the import tool. The export tool also has the
ability to anonymize the archive before returning it. This is particularly
useful when sharing PuppetDB data that contains sensitive items.

## Using the `export` command

To create an anonymized PuppetDB archive directly, use the Puppet `db` subcommand
from any node with puppet-client-tools installed:

    $ puppet db export my-puppetdb-export.tar.gz --anonymization moderate

## Using the `import` command

To import an anonymized PuppetDB tarball, use the Puppet `db` subcommand from
any node with puppet-client-tools installed:

    $ puppet db import my-puppetdb-export.tar.gz

## How does it work?

The tool walks through your entire data set, applying different rules to each of
the leaf data based on the profile you have chosen. The data structure is left
intact, and only the data contents are modified. This maintains the "shape" of
the data without exposing the underlying data you may wish to scrub.

We do this by always ensuring we replace data consistently. For example, if a
string is replaced with something random, we ensure that all instances of that
original string are replaced with the same random string throughout the data.

By keeping its original shape, the data can be anonymized based on your needs
but still hold some value to the consumer of your anonymized data.

## Anonymization profiles

You may not need to anonymize all data in every case, so we have provided a
number of profiles offering varying levels of anonymization.

The profile can be specified on the command line when the command is run. For
example, to choose the `low` profile, enter:

    $ puppet db export ./my-puppetdb-anonymized-export.tar.gz --anonymization low

### Profile: full

The `full` profile will anonymize all data (including node names, resource
types, resource titles, parameter names, values, any log messages, file names,
and file lines) while retaining the data set's shape. The result is a completely
anonymized data set. Report metrics under the `resources` and `events`
categories are left intact, as these can be inferred from the rest of the data,
but names of metrics under the `time` category are anonymized as resource types.

This is useful if you are really concerned about limiting the data you expose,
but provides the least utility for the consumer depending on the activity they
are trying to test.

### Profile: moderate

The `moderate` profile attempts to be a bit smarter about what it anonymizes and
is **the recommended profile for most cases**. It sorts and anonymizes data by
data type:

* Node name: is anonymized by default.
* Resource type name: the core types that are built into Puppet are not
  anonymized, including some common types from the modules: `stdlib`,
  `postgresql`, `rabbitmq`, `puppetdb`, `apache`, `mrep`, `f5`, `apt`,
  `registry`, `concat`, and `mysql`. Any Puppet Enterprise core type names are
  also preserved. The goal here is to anonymize any custom or unknown resource
  type names, as these may contain confidential information.
* Resource titles: all titles are anonymized expect for those belonging to
  Filebucket, Package, Service, and Stage.
* Parameter names: are never anonymized.
* Parameter values: everything is anonymized except for the values for
  `provider`, `ensure`, `noop`, `loglevel`, `audit`, and `schedule`.
* Report log messages: are always anonymized.
* File names: are always anonymized.
* File numbers: are left as they are.
* Log messages: are always anonymized.
* Metrics: metric names in the `time` category are anonymized as resource types.

### Profile: low

This profile is aimed at hiding security information specifically, but leaving
most of the data in its original state. The following categories are anonymized:

* Node name: is always anonymized.
* Parameter values: only values and messages for parameter names containing the
  strings `password`, `pwd`, `secret`, `key`, or `private` are anonymized.
* Log messages: are always anonymized.

## Verifying your anonymized data

After anonymizing data with the `puppetdb export` tool, we **strongly
recommend** that you analyze the anonymized data before sharing it with another
party to ensure that all sensitive data has been scrubbed.

Simply untar the export file and analyze the contents:

    $ tar -xzf my-puppetdb-anonymized-export.tar.gz
    $ cd puppetdb-bak

Inside this directory there is a directory for each content type (reports,
catalogs, and facts), and each file inside represents a node (and a report
instance for reports). The data is represented as human-readable JSON. You can
open these files and use tools such as `grep` to check the status of specific
information you wish to anonymize.
