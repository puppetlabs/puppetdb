class puppetdb::service($ensure=running) {
  case $operatingsystem {
    "Debian": {
      file { 'puppetdb-init':
        ensure  => present,
        path    => "/etc/init.d/puppetdb",
        content => template('puppetdb/init.erb'),
        mode    => 0755,
        notify  => Service[puppetdb],
      }
    }
  }

  file { "${puppetdb::installdir}/puppetdb.jar":
    ensure => present,
    source => "puppet:///modules/puppetdb/puppetdb.jar",
    owner  => puppetdb,
    group  => puppetdb,
    mode   => 0644,
    notify => Service[puppetdb],
  }

  file { 'puppetdb-config':
    ensure  => present,
    path => "${puppetdb::installdir}/config.ini",
    content => template('puppetdb/config.ini.erb'),
    owner   => puppetdb,
    group   => puppetdb,
    mode    => 0640,
    notify  => Service[puppetdb],
  }

  file { 'puppetdb-daemonize':
    ensure => present,
    path   => "${puppetdb::installdir}/daemonize.rb",
    source => "puppet:///modules/puppetdb/daemonize.rb",
    owner  => puppetdb,
    group  => puppetdb,
    mode   => 0755,
    notify => Service[puppetdb],
  }

  package { "libdaemons-ruby":
    ensure => present,
  }
  ->
  service { puppetdb:
    ensure => $ensure,
    notify => Service[nginx],
  }
}
