class grayskull::service($ensure=running) {
  case $operatingsystem {
    "Debian": {
      file { 'grayskull-init':
        ensure  => present,
        path    => "/etc/init.d/grayskull",
        content => template('grayskull/init.erb'),
        mode    => 0755,
        notify  => Service[grayskull],
      }
    }
  }

  file { "${grayskull::installdir}/grayskull.jar":
    ensure => present,
    source => "puppet:///modules/grayskull/grayskull.jar",
    owner  => grayskull,
    group  => grayskull,
    mode   => 0644,
    notify => Service[grayskull],
  }

  file { 'grayskull-config':
    ensure  => present,
    path => "${grayskull::installdir}/config.ini",
    content => template('grayskull/config.ini.erb'),
    owner   => grayskull,
    group   => grayskull,
    mode    => 0640,
    notify  => Service[grayskull],
  }

  file { 'grayskull-daemonize':
    ensure => present,
    path   => "${grayskull::installdir}/daemonize.rb",
    source => "puppet:///modules/grayskull/daemonize.rb",
    owner  => grayskull,
    group  => grayskull,
    mode   => 0755,
    notify => Service[grayskull],
  }

  package { "libdaemons-ruby":
    ensure => present,
  }
  ->
  service { grayskull:
    ensure => $ensure,
    notify => Service[nginx],
  }
}
