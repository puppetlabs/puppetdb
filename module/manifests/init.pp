# Class: puppetdb
#
# This module manages PuppetDB
#
# Parameters:
#
# Actions:
#
# Requires:
#
# Sample Usage:
#
# [Remember: No empty lines between comments and class definition]
class puppetdb(
  $port = 8081,
  $installdir = "/var/lib/puppetdb"
) {
  include puppetdb::jdk
  include puppetdb::users
  include puppetdb::service
}
