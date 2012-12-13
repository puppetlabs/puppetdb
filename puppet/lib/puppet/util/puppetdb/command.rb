require 'puppet/error'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/char_encoding'

class Puppet::Util::Puppetdb::Command
  include Puppet::Util::Puppetdb::CommandNames

  Url                = "/v2/commands"
  SpoolSubDir        = File.join("puppetdb", "commands")

  # Public class methods

  def self.each_queued_command
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

  def self.retry_queued_commands
    if queue_size == 0
      Puppet.notice("No queued commands to retry")
      return
    end

    each_queued_command do |command|
      begin
        command.submit
        command.dequeue
          # TODO: I'd really prefer to be catching a more specific exception here
      rescue => e
        # TODO: Use new exception handling methods from Puppet 3.0 here as soon as
        #  we are able to do so
        puts e, e.backtrace if Puppet[:trace]
        Puppet.err("Failed to submit command to PuppetDB: '#{e}'; Leaving in queue for retry.")
        break
      end
    end
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
    if (self.class.queue_size >= config.max_queued_commands)
      raise Puppet::Error, "Unable to queue command, max queue size of " +
          "'#{config.max_queued_commands}' has been reached. " +
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

  def submit
    checksum = Digest::SHA1.hexdigest(payload)
    escaped_payload = CGI.escape(payload)
    for_whom = " for #{certname}" if certname

    begin
      http = Puppet::Network::HttpPool.http_instance(config.server, config.port)
      response = http.post(Url, "checksum=#{checksum}&payload=#{escaped_payload}", headers)

      Puppet::Util::Puppetdb.log_x_deprecation_header(response)

      if response.is_a? Net::HTTPSuccess
        result = PSON.parse(response.body)
        Puppet.info "'#{command}' command#{for_whom} submitted to PuppetDB with UUID #{result['uuid']}"
        result
      else
        # Newline characters cause an HTTP error, so strip them
        raise Puppet::Error, "[#{response.code} #{response.message}] #{response.body.gsub(/[\r\n]/, '')}"
      end
    rescue => e
      # TODO: Use new exception handling methods from Puppet 3.0 here as soon as
      #  we are able to do so (can't call them yet w/o breaking backwards
      #  compatibility.)  We should either be using a nested exception or calling
      #  Puppet::Util::Logging#log_exception or #log_and_raise here; w/o them
      #  we lose context as to where the original exception occurred.
      puts e, e.backtrace if Puppet[:trace]
      raise Puppet::Error, "Failed to submit '#{command}' command#{for_whom} to PuppetDB at #{config.server}:#{config.port}: #{e}"
    end
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
      # TODO: the logic for this method might be able to be improved.
      # My main concern is that the filenames can be pretty long, and on some
      # really old filesystems that might be an issue.  We are trying to name
      # the files with a timestamp prefix that ensures FIFO (at least on a
      # per-thread basis).  However, because we have no guarantees about how
      # many processes or threads may be calling this code simultaneously, we
      # have to add the pid, thread id, and a thread-local counter integer
      # to the prefix.  Removing these causes transient test failures.
      clean_command_name = command.gsub(/[^\w_]/, "_")
      timestamp = Time.now.to_i.to_s
      @spool_file_name = "#{timestamp}_#{pid}_#{thread_id}_#{next_command_num}_#{certname}_#{clean_command_name}.command"
    end
    @spool_file_name
  end

  def spool_file_path
    File.join(self.class.spool_dir, spool_file_name)
  end

  def headers
    {
        "Accept" => "application/json",
        "Content-Type" => "application/x-www-form-urlencoded; charset=UTF-8",
    }
  end

  def config
    # Would prefer to pass this to the constructor or acquire it some other
    # way besides this pseudo-global reference.
    Puppet::Util::Puppetdb.config
  end

  def pid
    Process.pid
  end

  def thread_id
    Thread.current.object_id
  end

  def next_command_num
    rv = Thread.current['next_command_num'] || 0
    rv += 1
    Thread.current['next_command_num'] = rv
    rv
  end

end
