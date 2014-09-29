def run_beaker(test_files)
  config = ENV["BEAKER_CONFIG"] || "vbox-el6-64mda"
  options = ENV["BEAKER_OPTIONS"] || "postgres"
  preserve_hosts = ENV["BEAKER_PRESERVE_HOSTS"] || "never"
  color = ENV["BEAKER_COLOR"] == "false" ? false : true
  xml = ENV["BEAKER_XML"] == "true" ? true : false
  type = ENV["BEAKER_TYPE"] || "git"

  beaker = "beaker " +
     "-c '#{RAKE_ROOT}/acceptance/config/#{config}.cfg' " +
     "--type #{type} " +
     "--debug " +
     "--tests " + test_files + " " +
     "--options-file 'acceptance/options/#{options}.rb' " +
     "--root-keys " +
     "--preserve-hosts #{preserve_hosts}"

  beaker += " --no-color" unless color
  beaker += " --xml" if xml

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
end
