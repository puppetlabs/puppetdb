$ErrorActionPreference = 'Stop'

function Get-CurrentDirectory
{
  $thisName = $MyInvocation.MyCommand.Name
  [IO.Path]::GetDirectoryName((Get-Content function:$thisName).File)
}

# installs gems for build and test and grabs base images
function Invoke-ContainerBuildSetup
{
  Push-Location (Get-CurrentDirectory)
  bundle install --path '.bundle/gems'
  bundle exec puppet-docker update-base-images ubuntu:16.04 postgres:9.6.8
  Pop-Location
}

function Build-Container($Name, $Repository = '127.0.0.1')
{
  Push-Location (Join-Path (Get-CurrentDirectory) '..')
  bundle exec puppet-docker local-lint $Name
  bundle exec puppet-docker build $Name --no-cache --repository $Repository --build-arg namespace=$Repository
  Pop-Location
}
