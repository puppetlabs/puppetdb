require 'puppet/util'
require 'puppet/util/logging'
require 'puppet/util/profiler'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/command'
require 'puppet/util/puppetdb/config'
require 'digest/sha1'
require 'time'
require 'fileutils'

module Puppet::Util::Puppetdb

  class CommandSubmissionError < Puppet::Error
    def initialize(msg, context)
      super(msg)
      @context = context
    end
  end

  class InventorySearchError < Puppet::Error
  end
  class SoftWriteFailError < Puppet::Error
  end


  def self.config
    @config ||= Puppet::Util::Puppetdb::Config.load
    @config
  end

  # Given an instance of ruby's Time class, this method converts it to a String
  # that conforms to PuppetDB's wire format for representing a date/time.
  def self.to_wire_time(time)
    # The current implementation simply calls iso8601, but having this method
    # allows us to change that in the future if needed w/o being forced to
    # update all of the date objects elsewhere in the code.
    time.iso8601(9)
  end

  # Convert a value (usually a string) to a boolean
  def self.to_bool(value)
    case value
    when true, "true"; return true
    when false, "false"; return false
    else
      raise ArgumentError.new("invalid value for Boolean: \"#{val}\"")
    end
  end

  # @!group Public instance methods

  # Submit a command to PuppetDB.
  #
  # @param certname [String] hostname name of puppetdb instance
  # @param payload [String] payload
  # @param command_name [String] name of command
  # @param version [Number] version number of command
  # @return [Hash <String, String>]
  def submit_command(certname, payload, command_name, version)
    profile "Submitted command '#{command_name}' version '#{version}'" do
      command = Puppet::Util::Puppetdb::Command.new(command_name, version, certname, payload)
      command.submit
    end
  end

  # Profile a block of code and log the time it took to execute.
  #
  # This outputs logs entries to the Puppet masters logging destination
  # providing the time it took, a message describing the profiled code
  # and a leaf location marking where the profile method was called
  # in the profiled hierachy.
  #
  # @param message [String] A description of the profiled event
  # @param block [Block] The segment of code to profile
  # @api public
  def profile(message, &block)
    message = "PuppetDB: " + message
    Puppet::Util::Profiler.profile(message, &block)
  end

  # @!group Private instance methods

  # @api private
  def config
    Puppet::Util::Puppetdb.config
  end

  # @api private
  def log_x_deprecation_header(response)
    if warning = response['x-deprecation']
      Puppet.deprecation_warning "Deprecation from PuppetDB: #{warning}"
    end
  end
  module_function :log_x_deprecation_header

end
