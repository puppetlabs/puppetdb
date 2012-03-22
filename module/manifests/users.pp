class grayskull::users {
  user { "grayskull":
    home => $grayskull::installdir,
    managehome => true,
    system     => true,
    ensure => present;
  }
}
