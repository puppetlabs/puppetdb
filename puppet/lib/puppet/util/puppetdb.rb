require 'puppet/util'
require 'puppet/util/logging'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/command'
require 'puppet/util/puppetdb/config'
require 'digest/sha1'
require 'time'
require 'fileutils'

# TODO: This module is intended to be mixed-in by subclasses of
# `Puppet::Indirector::REST`.  This is unfortunate because the code is useful
# in other cases as well, but it's hard to use the "right" methods in Puppet
# for making HTTPS requests using the Puppet SSL config / certs outside of the
# indirector.  This has been fixed as of Puppet 3.0, (commit fa32691db3a7c2326118d528144fd1df824c0bb3), and this module
# should probably be refactored once we don't need to support
# versions prior to that.  See additional comments
# below, and see also `Puppet::Util::Puppetdb::ReportHelper` for an example of
# how to work around this limitation for the time being.
module Puppet::Util::Puppetdb

  ## HACK: the existing `http_*` methods and the
  # `Puppet::Util::PuppetDb#submit_command` expect their first argument to
  # be a "request" object (which is typically an instance of
  # `Puppet::Indirector::Request`), but really all they use it for is to check
  # it for attributes called `server`, `port`, and `key`.  Since we don't have,
  # want, or need an instance of `Indirector::Request` in many cases, we will use
  # this hacky struct to comply with the existing "API".
  BunkRequest = Struct.new(:server, :port, :key)

  # Public class methods and magic voodoo

  def self.server
    config.server
  end

  def self.port
    config.port
  end

  def self.config
    @config ||= Puppet::Util::Puppetdb::Config.load
    @config
  end

  # This magical stuff is needed so that the indirector termini will make requests to
  # the correct host/port.
  module ClassMethods
    def server
      Puppet::Util::Puppetdb.server
    end

    def port
      Puppet::Util::Puppetdb.port
    end
  end

  def self.included(child)
    child.extend ClassMethods
  end

  ## Given an instance of ruby's Time class, this method converts it to a String
  ## that conforms to PuppetDB's wire format for representing a date/time.
  def self.to_wire_time(time)
    # The current implementation simply calls iso8601, but having this method
    # allows us to change that in the future if needed w/o being forced to
    # update all of the date objects elsewhere in the code.
    time.iso8601
  end


  # Public instance methods

  def submit_command(certname, payload, command_name, version)
    command = Puppet::Util::Puppetdb::Command.new(command_name, version, certname, payload)

    begin
      command.submit
    rescue Puppet::Error => e
      # TODO: Use new exception handling methods from Puppet 3.0 here as soon as
      #  we are able to do so
      puts e, e.backtrace if Puppet[:trace]
      Puppet.err("Failed to submit command '#{command_name}' for '#{certname}' to PuppetDB.")
      if (command.supports_queueing?)
        command.enqueue
      end
    end
  end


  private

  ## Private instance methods

  def config
    Puppet::Util::Puppetdb.config
  end


  def log_x_deprecation_header(response)
    if warning = response['x-deprecation']
      Puppet.deprecation_warning "Deprecation from PuppetDB: #{warning}"
    end
  end
  module_function :log_x_deprecation_header

end
