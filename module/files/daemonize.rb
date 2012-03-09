#!/usr/bin/env ruby
require 'daemons'

Daemons.daemonize

exec(*ARGV)
