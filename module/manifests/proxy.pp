class grayskull::proxy(
    $servername = $fqdn,
    $port       = 8080,
    $ssl_dir     = "/etc/puppetlabs/puppet/ssl") {
  include nginx

  nginx::resource::upstream { $servername:
    members => [
      "${servername}:${grayskull::port}"
    ],
  }

  nginx::resource::vhost { $servername:
    listen_port       => $port,
    ssl               => "true",
    ssl_verify_client => "on",
    ssl_client_cert   => "${grayskull::installdir}/ssl/ca_crt.pem",
    ssl_cert          => "${grayskull::installdir}/ssl/crt.pem",
    ssl_key           => "${grayskull::installdir}/ssl/key.pem",
    proxy             => "http://$servername",
  }

  $grayskull_ssl_dir = "${grayskull::installdir}/ssl"

  file { grayskull_ssldir:
    path    => $grayskull_ssl_dir,
    ensure  => directory,
    owner   => $nginx::params::nx_daemon_user,
    group   => $nginx::params::nx_daemon_user,
    mode    => 0600,
    recurse => true;
  }

  file {
    grayskull_cert:
      path   => "${grayskull_ssl_dir}/crt.pem",
      ensure => link,
      source => "/etc/puppetlabs/puppet/ssl/certs/${servername}.pem",
      mode   => 0644,
    ;

    grayskull_ca_cert:
      path   => "${grayskull_ssl_dir}/ca_crt.pem",
      ensure => present,
      source => "/etc/puppetlabs/puppet/ssl/certs/ca.pem",
      mode   => 0644,
    ;

    grayskull_key:
      path   => "${grayskull_ssl_dir}/key.pem",
      ensure => present,
      source => "${ssl_dir}/private_keys/$servername.pem",
      mode   => 0600,
    ;
  }
}
