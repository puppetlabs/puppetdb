0.9.2
=====

Many thanks to the following people who contributed patches to this
release:

* Jason Ashby
* Kushal Pisavadia
* Erik Dalén
* Deepak Giridharagopal
* Nick Lewis
* Matthaus Litteken
* Chris Price

Notable changes:

* Allow more advanced storeconfigs queries

  Now, when using PuppetDB, your puppet manifests can use "and" and
  "or" in collection queries:

    File <<| mode == 0755 or content == "bar" |>>

* (#15388) Add redirect from '/' to the dashboard

  Prior to this fix, if you started up PuppetDB and then attempted to
  browse to "/", you'd get an error message that might lead you to
  believe that the server wasn't actually running (depending on your
  browser).

  This commit simply adds a redirect from "/" to the dashboard index
  page.

* (#14688) Improve stdout/stderr handling for redhat init script

  Prior to this fix, the redhat init script was keeping stdout/stderr
  open when you called "service puppetdb stop". This resulted in some
  undesirable behavior; starting the service over an ssh connection
  would not release the ssh connection, errors would appear on the
  console rather than in the log file, etc. Now, daemon startup
  redirects stdout/stderr to a file (puppetdb-daemon.log) instead of
  spamming the console, and we more properly background the launched
  process to prevent "locking" of a parent SSH connection.

* (#15349) Work around non-string resource titles

  It's possible in some cases for Puppet to generate a resource whose
  title isn't a string. However, since the generated edges refer to
  the resource using a string title, we end up with a mismatch. Now we
  will stringify all resource titles on the way out. In future, Puppet
  should do this for us.

* (#15446) Improve handling of user/group removal on rpm removal

  Fixed the following bugs in our handling of user/group removal
  during rpm removal:

  1. We were not conditioning the calls to groupdel / userdel to avoid
     running them during an upgrade, which meant that we were trying
     to delete them even during upgrades... which would have been bad.
  2. We had an || where we needed an &&, so we weren't actually
     calling the groupdel / userdel commands.
  3. We were hard-coding the user's home dir to a bad path.
  4. We had some '-r' flags that were wrong and/or unnecessary.

* (#15136, #15340) Properly handle non-string node queries

  Previously, these would result in 500 errors as the database failed
  the comparisons because of mismatched types. Now, all equality
  comparisons will be done against strings, and all numeric
  comparisons will be done against numbers.

  For equality comparisons, non-string arguments will be
  converted. This allows natural queries against numbers or booleans
  to work despite all fact values technically being strings.

  For numeric comparisons, non-numeric arguments will be converted.
  However, if the argument doesn't represent a number, the query will
  be rejected.

* (#15075) Improve handling of service start/stop during rpm
  upgrade/uninstall

  On uninstall, we now check to see if this is part of an upgrade or
  not, and we only stop and disable the service if this is *not* part
  of an upgrade. Also, we stop the service before we install the new
  package, and restart it after we finish removing the old package.

* (#14947) Restrict accetable client certificates by CN

  PuppetDB now implements an optional whitelist for HTTPS clients. If
  enabled by the user, we validate that the CN of the supplied client
  certificate exactly matches an entry in the whitelist. This feature
  is off by default.

* (#15321) Add aliases for namevars that are munged via `title_pattern`

  When we are creating aliases for resources (in order to ensure
  dependency resolution in the catalog), we need to take into account
  the case where the resource type defines one or more title_patterns,
  which, when used to set the value of the namevar, may munge the
  value via regex awesomeness.  'File' is an example of such a
  resource, as it will strip trailing slashes from the title to set
  the :path parameter if :path is not specified.

  Because this `title_pattern` munging happens as a side effect of the
  Puppet::Resource#to_hash method, it is important that our namevar
  alias code search that hash for necessary aliases rather than
  searching the Puppet::Resource instance directly.

* (#15059) Stop loading non-SSL content in the dashboard

  You can now view the PuppetDB dashboard using HTTPS without
  triggering browser warnings about mixing HTTP and HTTPS content.

* Improved "logging of last resort"

  There are certain points in the lifecycle of PuppetDB where it's
  critical that we properly log an exception, even if that means we
  spam different log targets (logfiles, stdout, stderr, etc) and
  duplicate output. Daemon startup and unhandled exceptions within a
  thread are two such critical points. We now more thoroughly ensure
  that these types of errors get logged properly.

* `puppetdb-ssl-setup` should be able to be re-executed

  The script can now be executed multiple times. It will ensure that
  all generated files are readable by the PuppetDB daemon, and it
  reconfigures PuppetDB to use the newly-generated keystore and
  truststore passwords.

* `puppetdb-ssl-setup` shouldn't fail when FQDN can't be determined

  We now revert to using `facter hostname`, to allow installation to
  continue unimpeded.

* Change SSL setup to use master SSL keys intead of agent

  This fixes installation bugs on systems that use different Puppet
  `ssldir` settings for `[master]` and `[agent]`.

* Automatic testing against Puppet 3.x ("telly")

  Spec tests now properly execute against Telly, and they are plugged
  into our continuous integration system.

* Acceptance testing

  We not automatically run PuppetDB through a series of
  acceptance-level tests (included in the source tree). This verifies
  correct behavior in an actual multi-node Puppet environment. Tests
  are executed automatically as part of Puppet Labs' continuous
  integration system.

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
