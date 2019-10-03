include Pupperware::SpecHelpers

RSpec.configure do |c|
  c.before(:suite) do
    ENV['PUPPETDB_IMAGE'] = require_test_image
    pull_images('puppetdb')
    teardown_cluster()
    docker_compose_up()
  end

  c.after(:suite) do
    emit_logs
    teardown_cluster()
  end
end

describe 'puppetdb container specs' do
  it 'should have installed postgres extensions' do
    installed_extensions = get_postgres_extensions
    expect(installed_extensions).to match(/^\s+pg_trgm\s+/)
    expect(installed_extensions).to match(/^\s+pgcrypto\s+/)
  end

  it 'should have started puppetdb' do
    expect(get_service_container('puppetdb')).to_not be_empty
  end

  it 'should have a "running" puppetdb container' do
    expect(wait_on_service_health('puppetdb')).to eq('healthy')
  end
end
