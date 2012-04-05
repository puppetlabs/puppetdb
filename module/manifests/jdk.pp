class puppetdb::jdk {
  package { "openjdk-6-jdk":
    ensure => installed;
  }
}
