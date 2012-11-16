require 'puppet/util/puppetdb'

class Puppet::Util::Puppetdb::ReportHelper
  include Puppet::Util::Puppetdb

  #######################################################################
  # NOTE: This class is a backwards compatibility hack.
  #######################################################################
  # We need to be able to issue an HTTPS request to the PuppetDB server,
  # and we need to do it using Puppet's SSL config/certificates for
  # authentication.  Unfortunately, the "right" method to use for
  # accomplishing this is the `#http_request` method in the class
  # `Puppet::Indirector::REST`.  This is the method that has the most
  # complete and useful error handling for SSL errors.  Unfortunately,
  # this class is pretty much impossible to use unless you're writing an
  # Indirector terminus.
  #
  # To compound the issue, our Puppet::Util::Puppetdb module is designed
  # to be used as a mix-in for our PuppetDB indirector termini.  It
  # provides them the very useful `#submit_command` method, but it relies
  # on the existence of an `#http_post` method (defined in
  # `Puppet::Indirector::Rest`, as mentioned above) in order to work
  # properly.
  #
  # So... this class exists to provide a workaround implementation of
  # `#http_post`.  The underlying problem should be fixed in Puppet
  # 3.x with the resolution of ticket #15975 / pull request #1040.
  # However, for backward compatibility, we're stuck with this crappy
  # hack until that version of Puppet is our oldest supported version. We
  # should make it a point to clean this up as soon as possible after
  # that point in time.
  #######################################################################

  ## HACK: the existing `http_*` methods and the
  # `Puppet::Util::PuppetDb#submit_command` expect their first argument to
  # be a "request" object (which is typically an instance of
  # `Puppet::Indirector::Request`), but really all they use it for is to check
  # it for attributes called `server`, `port`, and `key`.  Since we don't have,
  # want, or need an instance of `Indirector::Request` here, we will use this
  # hacky struct to comply with the existing "API".
  BunkRequest = Struct.new(:server, :port, :key)

  # A `#submit_command` method that doesn't require an `Indirector::Request`
  #  argument.
  def submit_command(key, payload, command, version)
    bunk_request = BunkRequest.new(Puppet::Util::Puppetdb.server, Puppet::Util::Puppetdb.port, key)
    # call into the "real" `#submit_command` method in `Puppet::Util::Puppetdb`
    super(bunk_request, payload, command, version)
  end

  # This method will be called back by `Puppet::Util::Puppetdb#submit_command`
  def http_post(bunk_request, path, body, headers)
    http = Puppet::Network::HttpPool.http_instance(bunk_request.server, bunk_request.port)
    http.post(path, body, headers)
  end
end