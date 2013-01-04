require 'puppet/util'
require 'puppet/util/logging'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/command'
require 'puppet/util/puppetdb/config'
require 'digest/sha1'
require 'time'
require 'fileutils'

module Puppet::Util::Puppetdb

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
  # the correct host/port, because this module gets mixed in to our indirector
  # termini.
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
    command.submit
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
