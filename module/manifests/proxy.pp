class grayskull::proxy($servername = $fqdn, $port = 8080) {
  include nginx

  nginx::resource::upstream { $servername:
    members => [
      "localhost:${grayskull::port}"
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

  file { "${grayskull::installdir}/ssl":
    ensure  => directory,
    owner   => $nginx::params::nx_daemon_user,
    group   => $nginx::params::nx_daemon_user,
    mode    => 0600,
    recurse => true;
  }
}
