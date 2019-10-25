require 'rake'

def run_beaker(test_files)
  config = ENV["BEAKER_CONFIG"] || "acceptance/config/vmpooler.cfg"
  options = ENV["BEAKER_OPTIONS"] || "acceptance/options/postgres.rb"
  preserve_hosts = ENV["BEAKER_PRESERVE_HOSTS"] || "never"
  no_provision = ENV["BEAKER_NO_PROVISION"] == "true" ? true : false
  color = ENV["BEAKER_COLOR"] == "false" ? false : true
  xml = ENV["BEAKER_XML"] == "true" ? true : false
  type = ENV["BEAKER_TYPE"] || "aio"
  keyfile = ENV["BEAKER_KEYFILE"] || nil
  collect_perf_data = ENV["BEAKER_COLLECT_PERF_DATA"] || nil

  beaker = "bundle exec beaker " +
     "-c '#{config}' " +
     "--type #{type} " +
     "--debug " +
     "--tests " + test_files + " " +
     "--options-file '#{options}' " +
     "--root-keys " +
     "--preserve-hosts #{preserve_hosts}"

  beaker += " --keyfile #{keyfile}" if keyfile
  beaker += " --no-color" unless color
  beaker += " --xml" if xml
  beaker += " --collect-perf-data #{collect_perf_data}" if collect_perf_data
  beaker += " --no-provision" if no_provision

  sh beaker
end

namespace :beaker do
  desc "Run beaker based acceptance tests"
  task :acceptance, [:test_files] do |t, args|
    args.with_defaults(:test_files => 'acceptance/tests/')
    run_beaker(args[:test_files])
  end

  desc "Run beaker based performance tests"
  task :performance, :test_files do |t, args|
    args.with_defaults(:test_files => 'acceptance/performance/')
    run_beaker(args[:test_files])
  end

  desc "Run beaker based acceptance tests, leaving VMs in place for later runs"
  task :first_run, [:test_files] do |t, args|
    args.with_defaults(:test_files => 'acceptance/tests/')
    ENV["BEAKER_PRESERVE_HOSTS"] = "always"
    run_beaker(args[:test_files])
  end

  desc "Re-run beaker based acceptance tests, using previously provisioned VMs"
  task :rerun, [:test_files] do |t, args|
    args.with_defaults(:test_files => 'acceptance/tests/')
    ENV["PUPPETDB_SKIP_PRESUITE_PROVISIONING"] = "true"
    ENV["BEAKER_NO_PROVISION"] = "true"
    ENV["BEAKER_PRESERVE_HOSTS"] = "always"
    run_beaker(args[:test_files])
  end

  desc "List your VMs in ec2"
  task :list_vms do
    sh 'aws ec2 describe-instances  --filters "Name=key-name,Values=Beaker-${USER}*" --query "Reservations[*].Instances[*].[InstanceId, State.Name, PublicIpAddress]" --output table'
  end
end

begin
  require 'packaging'
  Pkg::Util::RakeUtils.load_packaging_tasks
rescue LoadError => e
  puts "Error loading packaging rake tasks: #{e}"
end

namespace :package do
  task :bootstrap do
    puts 'Bootstrap is no longer needed, using packaging-as-a-gem'
  end
  task :implode do
    puts 'Implode is no longer needed, using packaging-as-a-gem'
  end
end

namespace :release do
  task :reconcile, [:release_version, :previous_version] do |t, versions|
    require 'httparty'

    project = 'PDB'.freeze
    version = versions[:release_version]
    prev_version = versions[:previous_version]
    fix_version = "#{project} #{version}".freeze

    puppetdb_repo = ENV['PDB_PATH'] || './'.freeze
    pe_puppetdb_extensions_repo = ENV['PDB_PE_PATH'] || '../pe-puppetdb-extensions'.freeze
    git_log = "git log --oneline --no-merges --no-decorate #{prev_version}..HEAD".freeze

    api_url = 'https://tickets.puppetlabs.com/rest/api/2'.freeze
    jql_search = "#{api_url}/search".freeze

    url = "#{jql_search}?jql=fixVersion='#{fix_version}'"
    response = HTTParty.get(url)
    json = response.parsed_response

    if ! json['errorMessages'].nil?
      for msg in json['errorMessages']
        puts "Jira query error: '#{msg}'"
      end
    elsif json['maxResults'] <= json['total']
      url = "#{jql_search}?maxResults=#{json['total']}&jql=fixVersion='#{fix_version}'"
      response = HTTParty.get(url)
      json = response.parsed_response
    end

    jira_issues = []
    json['issues']&.each do |issue|
      # puts issue['key']
      jira_issues.push({
        id: issue['id'],
        ticket: issue['key'],
        api_url: issue['self']
      });
    end

    def construct_git_data(output, repo)
      git_data = {}
      output.each_line do |line|
        commit, message = line.split(" ", 2)
        _, pdb_ref = /^\((PDB-\d+)\)/.match(message).to_a

        if pdb_ref
          if git_data.include?(pdb_ref)
            git_data[pdb_ref][:commits].push(commit)
          else
            git_data[pdb_ref] = {
              commits: [commit],
            }
          end
        else
          # Allow (doc) (docs) (maint) case-insensitive
          doc_regex = /^\(docs?\)/i
          maint_regex = /^\(maint\)/i
          i18n_regex = /^\(i18n\)/i
          puts "INVESTIGATE! #{repo} #{line}" unless doc_regex =~ message || maint_regex =~ message || i18n_regex =~ message
        end
      end

      git_data
    end

    out = `cd #{puppetdb_repo}; #{git_log}`
    pdb_git_data = construct_git_data(out, 'puppetdb')

    out = `cd #{pe_puppetdb_extensions_repo}; #{git_log}`
    pe_git_data = construct_git_data(out, 'pe-puppetdb-extensions')

    jira_issues.each do |issue|
      ticket = issue[:ticket]
      unless pdb_git_data.include?(ticket) || pe_git_data.include?(ticket)
        puts "#{ticket} exists in JIRA with fixVersion '#{fix_version}', but there is no corresponding git commit"
      end
    end

    def find_jira_match(jql_search, ticket, git_data, jira_issues, fix_version, repo)
      jira_match = jira_issues.any? do |issue|
        ticket == issue[:ticket]
      end

      url = "#{jql_search}?maxResults=1&jql=key=#{ticket} AND fixVersion='PDB n/a'"
      response = HTTParty.get(url)
      json = response.parsed_response

      if !jira_match and json['issues'].empty?
        puts "#{ticket} has a git commit(s) #{git_data[:commits]} in #{repo}, but its JIRA ticket does not have fixVersion '#{fix_version}'"
      end
    end

    pdb_git_data.each do |ticket, data|
      find_jira_match(jql_search, ticket, data, jira_issues, fix_version, 'puppetdb')
    end

    pe_git_data.each do |ticket, data|
      find_jira_match(jql_search, ticket, data, jira_issues, fix_version, 'pe-puppetdb-exntesions')
    end
  end
end
