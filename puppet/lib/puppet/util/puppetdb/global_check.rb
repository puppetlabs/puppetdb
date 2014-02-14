require 'semver'
require 'puppet/version'
require 'puppet/error'

module Puppet::Util::Puppetdb
  # Global checks for version support and other validations before the terminus
  # is used.
  #
  class GlobalCheck
    # Validate that the support for the version of Puppet we are running on is
    # still maintained.
    #
    # @param minimum [String] minimum version for operation
    # @throws [Puppet::Error] raised if current version is unsupported
    # @api private
    def self.puppet_version_check(minimum)
      minimum_version = ::SemVer.new(minimum)
      puppet_version = ::SemVer.new(Puppet.version)
      if (puppet_version <=> minimum_version) == -1 then
        raise Puppet::Error, "You are attempting to use puppetdb-terminus on an unsupported version of Puppet (#{puppet_version}) the minimum supported version is #{minimum_version}"
      end
    end

    # Run all checks
    #
    # @throws [Puppet::Error] raised for any validation errors
    def self.run
      self.puppet_version_check("3.4.2")
    end
  end
end
