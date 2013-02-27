---
layout: default
title: "PuppetDB 1.1 » API » Query » Curl Tips"
canonical: "/puppetdb/1.1/api/query/curl.html"
---

[Facts]: ./v2/facts.html
[Nodes]: ./v2/nodes.html
[fact-names]: ./v2/fact-names.html
[Resources]: ./v2/resources.html
[Metrics]: ./v2/metrics.html
[curl]: http://curl.haxx.se/docs/manpage.html
[dashboard]: ../../maintain_and_tune.html#monitor-the-performance-dashboard
[whitelist]: ../../configure.html#certificate-whitelist


You can use [`curl`][curl] to directly interact with PuppetDB's REST API. This is useful for testing, prototyping, and quickly fetching arbitrary data.

The instructions below are simplified. For full usage details, see [the curl manpage][curl] . For additional examples, please see the docs for the individual REST endpoints:

* [facts][]
* [fact-names][]
* [nodes][]
* [resources][]
* [metrics][]

## Dealing with complex query strings

Many query strings will contain characters like `[` and `]`, which must be URL-encoded. To handle this, you can use `curl`'s `--data-urlencode` option. 

If you do this with an endpoint that accepts `GET` requests, **you must also use the `-G` or `--get` option.** This is because `curl` defaults to `POST` requests when the `--data-urlencode` option is present.

    curl -G -H "Accept: application/json" 'http://localhost:8080/v2/nodes' --data-urlencode 'query=["=", ["node", "active"], true]'


