class grayskull::users {
  user { "grayskull":
    home => $grayskull::installdir,
    managehome => true,
    ensure => present;
  }
}
