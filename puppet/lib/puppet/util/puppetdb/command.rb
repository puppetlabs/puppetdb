require 'puppet/error'
require 'puppet/network/http_pool'
require 'puppet/util/puppetdb'
require 'puppet/util/puppetdb/command_names'
require 'puppet/util/puppetdb/char_encoding'

###############################################################################
# NOTES ON COMMAND SUBMISSION / RETRY FOR PUPPETDB 1.1
###############################################################################
# This code is not in a finished state.  We started working on it in order to
# provide command retry capability for PuppetDB; so, if the master attempts
# to submit commands to PuppetDB and they fail for whatever reason, we could
# have some way of queueing them for retry at a later time.  This would be
# a first step towards a high-availability solution.
#
# However, we quickly realized that there are a lot of challenges involved.
# The biggest one is that we are not able to make any assumptions about the
# threading / process model that Puppet is running under; this means that we
# are extremely limited in our options for dealing with shared state and
# concurrent access to shared resources.  We were going to end up having to
# launch a daemon / worker process or run a cron job to ensure that the sensitive
# parts of the code were not being executed simultaneously by multiple processes
# or threads.  This seemed fairly risky, difficult to test, and not particularly
# user-friendly.
#
# There is also an ordering issue w/rt catalog and fact commands.  In their
# current incarnation, they do not contain any client-side timestamp information,
# and PuppetDB blindly overwrites existing data with the most recently submitted
# facts / catalog.  Thus, if we were to queue and retry fact and catalog
# commands, there is a potential for us to end up overwriting good data from
# recent successful command submissions with stale data from old, queued commands.
#
# The short-term compromise that we decided on is to keep things a bit simpler
# for now.  The only commands that we will queue are report commands, and they
# will never be automatically retried.  There is a new Face (puppetdb flush_queue)
# that will retry the queued commands, and users can run it manually for now if
# they need it.  As long as that is the only place where the sensitive code is
# called, we know we don't have to worry about concurrency.
#
# The current state of this code is basically a step towards an eventual goal
# of separating out the Command / Queue logic into a reasonable object model,
# and in its current state it has some benefits over the previous implementation
# (a bit more separation of concerns, decoupling from most of the internals of
# the indirector, etc.), but there is still room for improvement and further
# refactoring.  Our intent is to revisit this when we come back to do more work
# on high-availability.
###############################################################################

class Puppet::Util::Puppetdb::Command
  include Puppet::Util::Puppetdb::CommandNames

  # Notes on future refactoring:  We could at least break out a CommandQueue
  # class, and could potentially break out the transport mechanism (HTTP in this
  # case) into a separate class as well.

  Url                = "/v2/commands"
  SpoolSubDir        = File.join("puppetdb", "commands")

  # Public class methods

  # WARNING: this method is not thread-safe, nor safe to run in multiple
  # processes simultaneously.  For more info see the notes on the
  # `#all_command_files` method.
  def self.each_queued_command
    # we expose an iterator as API rather than just returning a list of
    # all of the commands so that we are only reading one from disk at a time,
    # rather than trying to load every single command into memory
    all_command_files.sort_by { |f| File.basename(f) }.each do |command_file_path|
      command = load_command(command_file_path)
      yield command
    end
  end

  # WARNING: this method is not thread-safe, nor safe to run in multiple
  # processes simultaneously.  For more info see the notes on the
  # `#all_command_files` method.
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
    # After discussion with @grimradical, we've decided that for the time
    # being, only report commands are candidates for queueing.  We will add
    # support for other commands at a later date.
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

  def self.queue_size
    # There is technically a race condition here in that files could be added
    # to or removed from the directory before, after, or during a call to this
    # method.  However, for all of our purposes, that shouldn't cause any problems.
    all_command_files.length
  end

  # WARNING: this method is NOT thread-safe, nor safe to execute in multiple
  # processes simultaneously.  If it is executed simultaneously, files may
  # be deleted out from under the nose of threads that are trying to read them,
  # commands may be submitted multiple times, etc.  Ideally this needs to be
  # restricted to a worker process that can use a thread-safe queue or some
  # other mechanism to manage concurrent access.
  def self.all_command_files
    # this method is mostly useful for testing purposes
    Dir.glob(File.join(spool_dir, "*.command"))
  end

  # WARNING: this is not thread safe
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
      # We need to name the files with a timestamp prefix that ensures FIFO
      # (at least on a per-thread basis).  However, because we have no guarantees
      # about how many processes or threads may be calling this code simultaneously,
      # we have to add the pid, thread id, and a thread-local counter integer
      # to the prefix.  (These last three make up for the fact that the timestamp
      # does not have enough precision to guarantee uniqueness.)
      clean_command_name = command.gsub(/[^\w]/, "_")
      timestamp = Time.now.to_i.to_s
      @spool_file_name = "#{timestamp}_#{Process.pid}_#{thread_id}_#{next_command_num}_#{certname}_#{clean_command_name}.command"
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
