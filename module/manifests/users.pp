class puppetdb::users {
  user { "puppetdb":
    home => $puppetdb::installdir,
    managehome => true,
    system     => true,
    ensure => present;
  }
}
