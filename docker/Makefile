PUPPERWARE_ANALYTICS_STREAM ?= dev
NAMESPACE ?= puppet
git_describe = $(shell git describe)
vcs_ref := $(shell git rev-parse HEAD)
build_date := $(shell date -u +%FT%T)
hadolint_available := $(shell hadolint --help > /dev/null 2>&1; echo $$?)
hadolint_command := hadolint --ignore DL3008 --ignore DL3018 --ignore DL4000 --ignore DL4001
hadolint_container := hadolint/hadolint:latest
ifeq ($(IS_NIGHTLY),true)
	dockerfile := Dockerfile.nightly
	version := puppet6-nightly
else
	version = $(shell echo $(git_describe) | sed 's/-.*//')
	dockerfile := Dockerfile
endif


prep:
ifneq ($(IS_NIGHTLY),true)
	@git fetch --unshallow ||:
	@git fetch origin 'refs/tags/*:refs/tags/*'
endif

lint:
ifeq ($(hadolint_available),0)
	@$(hadolint_command) puppetdb/$(dockerfile)
else
	@docker pull $(hadolint_container)
	@docker run --rm -v $(PWD)/puppetdb/$(dockerfile):/Dockerfile \
		-i $(hadolint_container) $(hadolint_command) Dockerfile
endif

build: prep
	docker build \
		--pull \
		--build-arg vcs_ref=$(vcs_ref) \
		--build-arg build_date=$(build_date) \
		--build-arg version=$(version) \
		--build-arg pupperware_analytics_stream=$(PUPPERWARE_ANALYTICS_STREAM) \
		--file puppetdb/$(dockerfile) \
		--tag $(NAMESPACE)/puppetdb:$(version) \
		..
ifeq ($(IS_LATEST),true)
	@docker tag $(NAMESPACE)/puppetdb:$(version) $(NAMESPACE)/puppetdb:latest
endif

test: prep
	@bundle install --path .bundle/gems
	@PUPPET_TEST_DOCKER_IMAGE=$(NAMESPACE)/puppetdb:$(version) \
		bundle exec rspec puppetdb/spec

push-image: prep
	@docker push puppet/puppetdb:$(version)
ifeq ($(IS_LATEST),true)
	@docker push puppet/puppetdb:latest
endif

push-readme:
	@docker pull sheogorath/readme-to-dockerhub
	@docker run --rm \
		-v $(PWD)/README.md:/data/README.md \
		-e DOCKERHUB_USERNAME="$(DISTELLI_DOCKER_USERNAME)" \
		-e DOCKERHUB_PASSWORD="$(DISTELLI_DOCKER_PW)" \
		-e DOCKERHUB_REPO_PREFIX=puppet \
		-e DOCKERHUB_REPO_NAME=puppetdb \
		sheogorath/readme-to-dockerhub

publish: push-image push-readme

.PHONY: prep lint build test publish push-image push-readme
