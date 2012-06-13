0.9.1
=====

Many thanks to the following people who contributed patches to this
release:

* Kushal Pisavadia
* Reid Vandewiele
* Deepak Giridharagopal
* Nick Lewis
* Matthaus Litteken
* Daniel Pittman
* Chris Price
* Michael Stahnke

Notable changes:

* (#14613) Don't fail when collecting exec resources

  We were adding aliases for exec resources based on their command,
  which is the namevar. This is so that we can properly lookup
  resources referred to by relationship metaparameters when producing
  relationship edges. However, exec resources aren't isomorphic,
  meaning they aren't unique based on their namevar. This means we
  don't need to add those aliases, because it's illegal to specify a
  relationship to an exec resource using its command. It also means we
  were adding non-unique aliases, which were evaluated on the agent
  after collecting the resources, and could cause conflicts.

* (#14624) Tolerate leading whitespace in puppetdb.conf

  The INI file parser included in Puppet treats lines beginning with
  whitespace to be continuation lines, adding them to the value of the
  previous setting. This causes indentation to break puppetdb.conf,
  which is undesired. For example:

      [main]
        server = puppetdb.example.com
        port = 8081

  This will cause the value of the server setting to be
  "puppetdb.example.com\n port = 8081", and the port setting will not
  be set by the file, and will just default to 8080. This is
  definitely not our desired behavior, and indentation is something we
  want. So the INI parser has been replaced with a simple custom
  parser which handles this the way we want it.

* (#14659) Compatibility with PG 8.1.x

  RHEL5 ships with 8.1.23, and ARRAY declaration syntax was different
  waaaaaay back then. Running PuppetDB with that vintage PG results in
  SQLExceptions during initial migration.

  In particular, while more recent PG versions support data type
  definitions like “foo ARRAY NOT NULL”, PG 8.1 requires “foo ARRAY[1]
  NOT NULL”. The “1” is ignored by PG (much like how it ignores the
  size of a VARCHAR column).

* (#14665) Removal of SSL provider fails when class not available

  For compatibility with OpenJDK and OpenSSL 1.0+, we remove a buggy
  OpenJDK security provider. We do this by enumerating the current set
  of security providers and removing the problematic ones by checking
  if they are the instance of a specific class.

  However, if that class doesn't exist on the system at all, we get a
  ClassNotFoundException. We had a try/catch around this code for just
  that reason, only it turns out the try/catch isn't getting invoked
  when the error is thrown. So, like, we fixed that.

* (#14586) Incorrect start-stop-daemon args in Debian init script

  When we try to stop the daemon, we were passing in `--name puppetdb`
  instead of `--exec $JAVA_BIN`. Since our process isn't actually
  named puppetdb, that initial call wouldn't actually terminate the
  process. It would then fall-through to a second code path where the
  init script would then try to kill *all* processes with $JAVA_BIN as
  the executable. That's, like, not so great and stuff.

  We've corrected the args to start-stop-daemon to use both the pidfile
  and the executable name to give us a precise match. With that in
  place, the secondary code path is no longer necessary.

  A pleasant side-effect of this fix is that now stopping PuppetDB is
  extremely fast, instead of taking 30 seconds or so.

* Update project to build on suse

  This commit does some cleanup on the rakefile and moves the osfamily
  fact into an instance variable accessible to erb templates. It
  updates the rpm spec template to handle sles requires and
  buildrequires and also sets the rubylib for suse.

  This isn't _complete_ support for suse; there's still no init script
  for suse and we still don't have any testing around suse. This is
  just an intermediary step.

* Fix init script for RedHat-based PE installations

  Previously, we were grepping on username in the find_my_pid function of
  the init script.  This was fine if the username was <= 8 characters, but
  since pe-puppetdb is not, 'ps -ef' would fall back to using UID in the
  processes table, thus causing the function to fail.  This in turn led to
  empty pid files and puppetdb potentially starting lots of times.

  We now grep the jarfile name rather than user to confine the process
  listing.

* Sort unordered metaparameter values during catalog serialization

  You have a resource with a metaparameter, `tag`, with a value of
  `["c", "b", "a"]`. We generate a catalog and that catalog is sent to
  PuppetDB. The next time Puppet runs, you get the same resource, with
  the same metaparameter `tag`, only the value is now
  `["a", "b", "c"]`. That catalog is also sent to PuppetDB.

  Everything works correctly, however PuppetDB will think that the
  second catalog is different from the first catalog. That's okay in
  terms of correctness, but it's slower...`tag` (and several other
  metaparameters) are inherently unordered. We should treat both
  catalogs the same because, semantically, they are.

  This patchset munges catalogs prior to submission to PuppetDB such
  that all inherently unordered metaparameters are sorted. That way
  the output is the same no matter the initial ordering.

  You may be wondering how it's possible that Puppet could generate 2
  different orders for the same metaparam. But imagine that you're
  using the spaceship operator to set dependency relationships on
  resources. Puppet doesn't order the results of a resource collection
  operation. Something like this, for example, can trigger this bug:

     Yumrepo <| |> -> Package <| |>

* Perform garbage collection at startup

  This simply re-orders the sleep vs the garbage collection in the
  garbage collection thread.

0.9.0
=====

* Initial release
