%global realname puppetdb
%global realversion 1.3.2
%global rpmversion 1.3.2

%global puppet_libdir     %(ruby -rrbconfig -e "puts RbConfig::CONFIG['sitelibdir']")

# These macros are not always defined on much older rpm-based systems
%global  _sharedstatedir /var/lib
%global  _realsysconfdir /etc
%global  _initddir   %{_realsysconfdir}/init.d
%global _rundir /var/run


Name:          puppetdb
Version:       1.3.2
Release:       1%{?dist}
BuildRoot:     %{_tmppath}/%{realname}-%{version}-%{release}-root-%(%{__id_u} -n)

Summary:       Puppet Centralized Storage Daemon
License:       Apache-2.0
URL:           http://github.com/puppetlabs/puppetdb
Source0:       http://downloads.puppetlabs.com/puppetdb/%{realname}-%{realversion}.tar.gz
Source1:       suse-initscript
Source2:       puppetdb.systemd.params
Source3:       puppetdb.service

Group:         System/Daemons

BuildRequires: puppet >= 2.7.12
BuildRequires: facter >= 1.6.8
BuildRequires: rubygem-rake
BuildRequires: ruby
BuildRequires: logrotate
Requires:      puppet >= 2.7.12
Requires:      facter >= 1.6.8
Requires:      logrotate
%if 0%{?suse_version}
BuildRequires: aaa_base
BuildRequires: unzip
Patch:         0001-patch-rakefile.patch
Requires:      aaa_base
Requires:      pwdutils
%else
BuildRequires: /usr/sbin/useradd
Requires:      chkconfig
%endif
BuildRequires: java-devel
Requires:      java
Requires(pre): %fillup_prereq
Requires(pre): %insserv_prereq
%if 0%{?suse_version} >= 1210
BuildRequires:  systemd
%{?systemd_requires}
%endif

%description
PuppetDB is a Puppet data warehouse; it manages storage and retrieval of all
platform-generated data. Currently, it stores catalogs and facts; in future
releases, it will expand to include more data, like reports.

%package terminus
Summary: Puppet terminus files to connect to PuppetDB
Group:   System/Libraries
Requires: puppet >= 2.7.12

%description terminus
Connect Puppet to PuppetDB by setting up a terminus for PuppetDB.

%prep
%setup -q -n %{realname}-%{realversion}

%build

%install
%if 0%{?suse_version}
export NO_BRP_CHECK_BYTECODE_VERSION=true
%endif

mkdir -p %{buildroot}/%{_initddir}

rake install PARAMS_FILE= DESTDIR=%{buildroot}
rake terminus PARAMS_FILE= DESTDIR=%{buildroot}

cp %{S:1} %{buildroot}/%{_initddir}/%{name}

mkdir -p %{buildroot}/%{_localstatedir}/log/%{name}
mkdir -p %{buildroot}/%{puppet_libdir}
mv %{buildroot}/puppet %{buildroot}/%{puppet_libdir}
rm -f %{buildroot}/etc/sysconfig/puppetdb
ln -s ../../etc/init.d/%{name} %{buildroot}/%{_sbindir}/rc%{name}

%if 0%{?_unitdir:1}
mkdir -p %{buildroot}etc/conf.d/
install -Dpm 0644  %{SOURCE2} %{buildroot}/etc/conf.d/%{name}
install -Dpm 0644  %{SOURCE3} %{buildroot}%_unitdir/%{name}.service
%endif

%pre
# Here we'll do a little bit of cleanup just in case something went horribly
# awry during a previous install/uninstall:
if [ -f "/usr/share/puppetdb/start_service_after_upgrade" ] ; then
   rm /usr/share/puppetdb/start_service_after_upgrade
fi
# If this is an upgrade (as opposed to an install) then we need to check
#  and see if the service is running.  If it is, we need to stop it.
#  we want to shut down and disable the service.
if [ "$1" = "2" ] ; then
    if /sbin/service %{name} status > /dev/null ; then
        # If we need to restart the service after the upgrade
        #  is finished, we will touch a temp file so that
        #  we can detect that state
        touch /usr/share/puppetdb/start_service_after_upgrade
        /sbin/service %{name} stop >/dev/null 2>&1
    fi
fi
# Add PuppetDB user
getent group %{name} > /dev/null || groupadd -r %{name}
getent passwd %{name} > /dev/null || \
useradd -r -g %{name} -d /usr/share/puppetdb -s /sbin/nologin \
     -c "PuppetDB daemon"  %{name}
%if 0%{?_unitdir:1}
%service_add_pre %{name}.service
%endif

%post
%fillup_and_insserv
# If this is an install (as opposed to an upgrade)...
if [ "$1" = "1" ]; then
  # Register the puppetDB service
  /sbin/chkconfig --add %{name}
fi

chmod 755 /etc/puppetdb
chown -R puppetdb:puppetdb /etc/puppetdb/*
chmod -R 640 /etc/puppetdb/*
chmod -R ug+X /etc/puppetdb/*

chgrp puppetdb /var/log/puppetdb
chmod 775 /var/log/puppetdb

chown -R puppetdb:puppetdb /var/lib/puppetdb
%if 0%{?_unitdir:1}
%service_add_post %{name}.service
%endif

%preun
%stop_on_removal %{name}
# If this is an uninstall (as opposed to an upgrade) then
#  we want to shut down and disable the service.
if [ "$1" = "0" ] ; then
    /sbin/service %{name} stop >/dev/null 2>&1
    /sbin/chkconfig --del %{name}
fi
%if 0%{?_unitdir:1}
%service_del_preun %{name}.service
%endif

%postun
%restart_on_update %{name}
# Remove the rundir if this is an uninstall (as opposed to an upgrade)...
if [ "$1" = "0" ]; then
    rm -rf %{_rundir}/%{name} || :
fi

# If this is an upgrade (as opposed to an install) then we need to check
#  and see if we stopped the service during the install (we indicate
#  this via the existence of a temp file that was created during that
#  phase).  If we did, then we need to restart it.
if [ "$1" = "1" ] ; then
    if [ -f "/usr/share/puppetdb/start_service_after_upgrade" ] ; then
        rm /usr/share/puppetdb/start_service_after_upgrade
        /sbin/service %{name} start >/dev/null 2>&1
    fi
fi
%if 0%{?_unitdir:1}
%service_del_postun %{name}.service
%endif
%insserv_cleanup

%files
%defattr(-, root, root)
%doc *.md
%doc documentation
%dir %{_sysconfdir}/%{realname}
%dir %{_sysconfdir}/%{realname}/conf.d
%config(noreplace)%{_sysconfdir}/%{realname}/conf.d/config.ini
%config(noreplace)%{_sysconfdir}/%{realname}/log4j.properties
%config(noreplace)%{_sysconfdir}/%{realname}/conf.d/database.ini
%config(noreplace)%{_sysconfdir}/%{realname}/conf.d/jetty.ini
%config(noreplace)%{_sysconfdir}/%{realname}/conf.d/repl.ini
%config(noreplace)%{_realsysconfdir}/logrotate.d/%{name}
%{_sbindir}/puppetdb-ssl-setup
%{_sbindir}/puppetdb-foreground
%{_sbindir}/puppetdb-import
%{_sbindir}/puppetdb-export
%{_datadir}/%{realname}
%{_initddir}/%{name}
%{_sbindir}/rc%{name}
%{_sharedstatedir}/%{realname}
%{_datadir}/%{realname}/state
%dir %{_localstatedir}/log/%{name}
%if 0%{?_unitdir:1}
%_unitdir/%{name}.service
%dir /etc/conf.d/
%config /etc/conf.d/%{name}
%endif

%files terminus
%defattr(-, root, root)
%dir %{puppet_libdir}
%dir %{puppet_libdir}/puppet
%dir %{puppet_libdir}/puppet/*
%{puppet_libdir}/puppet/application/storeconfigs.rb
%dir %{puppet_libdir}/puppet/face/node
%{puppet_libdir}/puppet/face/node/deactivate.rb
%{puppet_libdir}/puppet/face/node/status.rb
%{puppet_libdir}/puppet/face/storeconfigs.rb
%dir %{puppet_libdir}/puppet/indirector/*
%{puppet_libdir}/puppet/indirector/catalog/puppetdb.rb
%{puppet_libdir}/puppet/indirector/facts/puppetdb.rb
%{puppet_libdir}/puppet/indirector/node/puppetdb.rb
%{puppet_libdir}/puppet/indirector/resource/puppetdb.rb
%{puppet_libdir}/puppet/reports/puppetdb.rb
%{puppet_libdir}/puppet/util/puppetdb.rb
%dir %{puppet_libdir}/puppet/util/puppetdb
%{puppet_libdir}/puppet/util/puppetdb/char_encoding.rb
%{puppet_libdir}/puppet/util/puppetdb/command.rb
%{puppet_libdir}/puppet/util/puppetdb/command_names.rb
%{puppet_libdir}/puppet/util/puppetdb/config.rb

%changelog
