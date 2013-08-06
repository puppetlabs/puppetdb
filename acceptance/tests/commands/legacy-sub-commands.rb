test_name "legacy sub-commands" do
  step "check to make sure the legacy commands still work" do
    ["puppetdb-ssl-setup","puppetdb-import","puppetdb-export","puppetdb-anonymize"].each do |k|
      result = on database, "/usr/sbin/#{k} -h"

      assert_match(/WARNING: #{k} style of executing puppetdb commands is deprecated/, result.stdout, "Legacy sub-command did not returning a deprecation WARNING line")
    end
  end
end
