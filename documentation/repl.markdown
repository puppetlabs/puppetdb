---
title: "PuppetDB 2.3 » Debugging with the Remote REPL"
layout: default
canonical: "/puppetdb/latest/repl.html"
---

PuppetDB includes a remote REPL interface, which is disabled by default.

This is mostly of use to developers who know Clojure and are familiar with PuppetDB's code base. It allows you to modify PuppetDB's code on the fly. Most users should never need to use the REPL, and it should usually be left disabled for security reasons.

Enabling the REPL
-----

To enable the REPL, you must edit PuppetDB's config file to [enable it, configure the listening IP address, and choose a port](./configure.html#repl-settings):

    # /etc/puppetdb/conf.d/repl.ini
    [nrepl]
    enabled = true
    port = 8082
    host = 127.0.0.1

After configuring it, you should restart the PuppetDB service.

Connecting to a Remote REPL
-----

Once PuppetDB is accepting remote REPL connections, you can connect to it and begin issuing low-level debugging commands and Clojure code.

For example, with a NREPL configured on port 8082, and using leiningen to connect:

    # lein repl :connect localhost:8082
    Connecting to nREPL at localhost:8082
    REPL-y 0.3.1
    Clojure 1.6.0
        Docs: (doc function-name-here)
              (find-doc "part-of-name-here")
      Source: (source function-name-here)
     Javadoc: (javadoc java-object-or-class-here)
        Exit: Control+D or (exit) or (quit)
     Results: Stored in vars *1, *2, *3, an exception in *e

    user=> (+ 1 2 3)
    6

Executing Functions
-----

Within the REPL, you can interactively execute PuppetDB's functions. For example, to manually compact the database:

    user=> (use 'puppetlabs.puppetdb.cli.services)
    nil
    user=> (use 'puppetlabs.puppetdb.scf.storage)
    nil
    user=> (use 'clojure.java.jdbc)
    nil
    user=> (garbage-collect! (:database configuration))
    (0)

Redefining Functions
-----

You can also manipulate the running PuppetDB instance by redefining functions on the fly. Let's say that for debugging purposes, you'd like to log every time a catalog is deleted. You can just redefine the existing `delete-catalog!` function dynamically:

    user=> (ns puppetlabs.puppetdb.scf.storage)
    nil
    puppetlabs.puppetdb.scf.storage=>
    (def original-delete-catalog! delete-catalog!)
    #'puppetlabs.puppetdb.scf.storage/original-delete-catalog!
    puppetlabs.puppetdb.scf.storage=>
    (defn delete-catalog!
      [catalog-hash]
      (log/info (str "Deleting catalog " catalog-hash))
      (original-delete-catalog! catalog-hash))
    #'puppetlabs.puppetdb.scf.storage/delete-catalog!

Now any time that function is called, you'll see a message logged.

Note that any changes you make to the running system are transient; they don't persist between restarts of the service. If you wish to make longer-lived changes to the code, consider [running PuppetDB directly from source](./install_from_source.html).
