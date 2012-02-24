# Class: grayskull
#
# This module manages grayskull
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
class grayskull(
  $port = 8081,
  $installdir = "/var/lib/grayskull"
) {
  include grayskull::jdk
  include grayskull::users
}
