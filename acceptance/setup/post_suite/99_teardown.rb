#!/usr/bin/env ruby

# TODO: this is copied and pasted from the setup script.  It needs to be
#  DRY'd up in the refactor.
step "DETERMINE OSFAMILY AGAIN BECAUSE WE HAVEN'T REFACTORED SETUP SCRIPT YET" do
  # Determine whether we're Debian or RedHat. Note that "git" installs are
  # currently assumed to be *Debian only*.
  on(database, "which yum", :silent => true)
  if result.exit_code == 0
    # TODO: this is basically a global variable right now, need to clean it up.
    @osfamily = :redhat
  else
    @osfamily = :debian
  end
end



def uninstall_package(pkg_name)
  if (@osfamily == :debian)
    on(database, "apt-get -f -y purge #{pkg_name}")
  elsif (@osfamily == :redhat)
    on(database, "yum -y remove #{pkg_name}")
  else
    raise ArgumentError, "Unsupported OS family: '#{@osfamily}'"
  end
end

step "Stop puppetdb" do
  stop_puppetdb(database)
end

if (PuppetDBExtensions.test_mode == :package)
  step "Uninstall packages" do
    uninstall_package("puppetdb")
    uninstall_package("puppetdb-terminus")
  end
end
