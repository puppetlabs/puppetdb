require 'puppet/error'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/char_encoding'

class Puppet::Util::Puppetdb::Command
  include Puppet::Util::Puppetdb::CommandNames

  Url = "/v2/commands"
  SpoolSubDir        = File.join("puppetdb", "commands")

  # Public class methods

  def self.each_enqueued_command
    # we expose an iterator as API rather than just returning a list of
    # all of the commands so that we are only reading one from disk at a time,
    # rather than trying to load every single command into memory
    all_command_files.sort_by { |f| File.basename(f) }.each do |command_file_path|
      command = load_command(command_file_path)
      yield command
    end
  end

  def self.queue_size
    all_command_files.length
  end

  # Public instance methods

  # Constructor;
  #
  # @param command String the name of the command; should be one of the
  #   constants defined in `Puppet::Util::Puppetdb::CommandNames`
  # @param version Integer the command version number
  # @param payload Object the payload of the command.  This object should be a
  #   primitive (numeric type, string, array, or hash) that is natively supported
  #   by JSON serialization / deserialization libraries.
  def initialize(command, version, certname, payload)
    @command = command
    @version = version
    @certname = certname
    @payload = self.class.format_payload(command, version, payload)
  end

  attr_reader :command, :version, :certname, :payload

  # This is not part of the public API
  # @private
  def initialize_from_file(command_file_path)
    File.open(command_file_path, "r") do |f|
      @command = f.readline.strip
      @version = f.readline.strip.to_i
      @certname = f.readline.strip
      @payload = f.read
      @spool_file_name = File.basename(command_file_path)
    end
  end

  def ==(other)
    (@command == other.command) &&
        (@version == other.version) &&
        (@certname == other.certname) &&
        (@payload == other.payload)
  end


  def supports_queueing?
    # Right now, only report commands are candidates for queueing
    command == CommandStoreReport
  end

  def queued?
    File.exists?(spool_file_path)
  end

  def enqueue
    # This is gross that we are referencing the Puppetdb config instance directly.
    # Would be better to make a separate 'Queue' class and construct an instance
    # of it at startup, and pass in the necessary config to the constructor.
    if (self.class.queue_size >= Puppet::Util::Puppetdb.config.max_queued_commands)
      raise Puppet::Error, "Unable to queue command, max queue size of " +
          "'#{Puppet::Util::Puppetdb.config.max_queued_commands}' has been reached. " +
          "Please clean out the queue directory ('#{self.class.spool_dir}')."
    end

    File.open(spool_file_path + ".tmp", "w") do |f|
      f.puts(command)
      f.puts(version)
      f.puts(certname)
      f.write(payload)
    end
    File.rename(spool_file_path + ".tmp", spool_file_path)
    Puppet.info("Spooled PuppetDB command for node '#{certname}' to file: '#{spool_file_path}'")
  end

  def dequeue
    File.delete(spool_file_path)
  end


  private

  ## Private class methods

  def self.format_payload(command, version, payload)
    message = {
        :command => command,
        :version => version,
        :payload => payload,
    }.to_pson

    Puppet::Util::Puppetdb::CharEncoding.utf8_string(message)
  end

  def self.load_command(command_file_path)
    cmd = self.allocate
    cmd.initialize_from_file(command_file_path)
    cmd
  end

  def self.spool_dir
    unless (@spool_dir)
      @spool_dir = File.join(Puppet[:vardir], SpoolSubDir)
      FileUtils.mkdir_p(@spool_dir)
    end
    @spool_dir
  end

  def self.all_command_files
    # this method is mostly useful for testing purposes
    Dir.glob(File.join(spool_dir, "*.command"))
  end

  def self.clear_queue
    # this method is mostly useful for cleaning up after tests
    all_command_files.each do |f|
      File.delete(f)
    end
  end

  ## Private instance methods

  def spool_file_name
    unless (@spool_file_name)
      # TODO: the logic for this method might need to be improved.  We're using
      # a sha1 of the payload to try to prevent filename collisions, but it's
      # entirely possible for subsequent catalog submissions to have the same
      # exact payload.  If we included a local timestamp in the catalog command,
      # this concern would probably be alleviated.
      clean_command_name = command.gsub(/[^\w_]/, "_")
      timestamp = Time.now.to_f.to_s.gsub("\.", "")
      @spool_file_name = "#{timestamp}_#{certname}_#{clean_command_name}_#{Digest::SHA1.hexdigest(payload.to_pson)}.command"
    end
    @spool_file_name
  end

  def spool_file_path
    File.join(self.class.spool_dir, spool_file_name)
  end

  # This method is *only* for use by the Command.load_command factory method.
  # In all other cases, the spool_file_name should be generated dynamically.
  def override_spool_file_name(file_name)
    @spool_file_name = file_name
  end

end