#!/usr/bin/env bash

set -ueo pipefail
set -x

ulimit -u 4096

top="$(pwd)"

usage()
{
    set +x
    cat <<-EOF
	Usage: test.sh [--lein LEIN] [--pdb-ref REF] [--[no-]pg-sandbox]
	               [-- LEIN_TEST_ARGS]
	EOF
    set -x
}

strip-ends() {
    local stripped="$1"
    stripped="${stripped#?}"
    stripped="${stripped%?}"
    echo "$stripped"
}

lein-pprint() {
  "$lein" with-profile dev,ci pprint "$@"
}

# get_dep_version
# :arg1 is the dependency project whose version we want to grab from project.clj
get_dep_version() {
    local ver
    ver="$(lein-pprint ":$1")"
    ver="$(strip-ends "$ver")"
    test "$ver"
    echo "$ver"
}

# Track the version and git-describe for each dependency and pdbext itself
declare -A build_ver
declare -A build_desc

# checkout_repo
# :arg1 is the name of the repo to check out
# :arg2 is the artifact id of the project
checkout_repo() {
    local repo="$1"
    local depname="$2"

    git clone "git@github.com:puppetlabs/${repo}.git"

    # We need to make sure we are using the correct "release" tag.  It seems
    # unlikely in travisci context, but in the future we may need to check
    # whether this is a SNAPSHOT version and behave differently if so.
    local depversion
    depversion="$(get_dep_version "${depname}")"

    if [ -z "$depversion" ]; then
        echo "Could not get a dependency version for: ${depname}, exiting..."
        exit 1
    fi

    pushd "${repo}"
    git checkout "${depversion}"
    if [ "${repo}" == pe-rbac-service ]; then
        sed -i -e 's/\[puppetlabs\/pe-activity-service/#_\[puppetlabs\/pe-activity-service/g' project.clj
        sed -i -e 's/\[puppetlabs\/rbac-client/#_\[puppetlabs\/rbac-client/g' project.clj
    fi
    if [ "${repo}" == pe-activity-service ]; then
        sed -i -e 's/\[puppetlabs\/pe-rbac-service/#_\[puppetlabs\/pe-rbac-service/g' project.clj
    fi
    build_ver[$depname]="$depversion"
    build_desc[$depname]="$(git describe --always --tags)"
    lein install
    popd
}

lein=''
pdb_ref=''
pg_sandbox=''

while [ "$#" -ne 0 ]; do
    case "$1" in
        --lein)
            shift
            if [ "$#" -eq 0 ]; then
                usage 1>&2
                exit 1
            fi
            lein="$1"
            shift
            ;;
        --pdb-ref)
            shift
            if [ "$#" -eq 0 ]; then
                usage 1>&2
                exit 1
            fi
            pdb_ref="$1"
            shift
            ;;
        --pg-sandbox)
            pg_sandbox=true
            shift
            ;;
        --no-pg-sandbox)
            pg_sandbox=''
            shift
            ;;
        --)
            shift
            break
            ;;
        *)
            usage 1>&2
            exit 1
            ;;
    esac
done

lein_args=("$@")

# If --lein was not specified, then check any lein or lein2 in the path.
if test -z "$lein"; then
    for candidate in lein lein2; do
        type -p "$candidate" || continue
        lein_ver="$("$candidate" --version)"
        if ! [["$lein_ver" =~ ^Leiningen\ 2 ]]; then
            lein="$candidate"
            break
        fi
    done
fi

if test -z "$lein"; then
    echo "Unable to find suitable Leiningen command; halting" >&2
    exit 1
fi

build_ver[pe-puppetdb-extensions]="$(strip-ends "$(lein-pprint :version)")"
build_desc[pe-puppetdb-extensions]="$(git describe --always --tags)"
test "${build_ver[pe-puppetdb-extensions]}"

rm -rf checkouts
mkdir checkouts

# Clone each dependency locally into the ./checkouts directory so we can install
# from source into the local ~/.m2/repository
cd checkouts

checkout_repo clj-schema-tools schema-tools
checkout_repo liberator-util liberator-util
checkout_repo clj-rbac-client rbac-client
checkout_repo pe-rbac-service pe-rbac-service
checkout_repo pe-activity-service pe-activity-service

git clone https://github.com/puppetlabs/puppetdb

# For the puppetdb checkout, if pdb_ref is set, use that, otherwise
# the detected build_ver if it's a tag, or finally, any TRAVIS_BRANCH
# (i.e. the current extensions branch in $top).
build_ver[puppetdb]="$(get_dep_version puppetdb)"

cd puppetdb

if [ -z "$pdb_ref" ]; then
    pdb_ref="$(git tag -l "${build_ver[puppetdb]}")"
fi

pdb_ref="${pdb_ref:-${TRAVIS_BRANCH}}"
build_ver[puppetdb]="$pdb_ref"

git checkout "$pdb_ref"
build_desc[puppetdb]="$(git describe --always --tags)"
"$lein" install

cd "$top"

if [ "$pg_sandbox" ]; then
    export PGHOST=127.0.0.1
    export PGPORT=5432

    pgdir="$(pwd)/test-resources/var/pg"
    checkouts/puppetdb/ext/bin/setup-pdb-pg "$pgdir"
    pdb-env() { "$top/checkouts/puppetdb/ext/bin/pdb-test-env" "$pgdir" "$@"; }
else
    pdb-env() { env "$@"; }
fi

set +x
echo >&2
echo build versions: >&2
for item in ${!build_ver[*]}; do
    echo "  $item: ${build_ver[$item]} (${build_desc[$item]})" >&2
done
echo >&2
set -x

java -version
# This odd syntax avoids a "set -u" error if there are no lein args.
pdb-env "$lein" test ${arr[@]+"${arr[@]}"}
