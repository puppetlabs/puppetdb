#!/bin/bash -ex

echo "******************************************************************************"
echo "RUNNING LEIN TESTS AGAINST BRANCH: '${PUPPETDB_BRANCH}', database: ${PUPPETDB_DBTYPE}"
echo "******************************************************************************"

CWD=`pwd`

###########
# Install leiningen
export PATH="${CWD}/bin:${PATH}"
mkdir -p bin

pushd bin
wget --no-check-certificate -N https://raw.github.com/technomancy/leiningen/2.3.3/bin/lein
chmod +x lein
popd

###########
# Create postgres database instance if dbtype is postgres
if [ "$PUPPETDB_DBTYPE" == "postgres" ];
then
  echo "DB type is postgres, so starting temporary instance"

  DBPORT=$RANDOM
  let "DBPORT += 32767"
  DBBASE="${CWD}/pgdb"
  DBDATA="${DBBASE}/data"
  DBXLOG="${DBBASE}/xlog"
  DBNAME="puppetdb"
  export PUPPETDB_DBUSER=puppetdb
  export PUPPETDB_DBPASSWORD=puppetdb
  export PUPPETDB_DBSUBNAME=//localhost:${DBPORT}/${DBNAME}
  
  if [ -d $DBDATA ];
  then
    echo "Found old data dir ${DBDATA}, attempting shutdown and removing base dir ${DBBASE}"
    pg_ctl stop -D $DBDATA
    rm -rf $DBBASE
  fi
  
  echo "Creating database in ${DBDATA}"
  mkdir $DBBASE
  echo $PUPPETDB_DBPASSWORD > pwd.txt
  initdb --auth=trust --auth-host=trust --auth-local=trust --pgdata=$DBDATA --encoding=UTF8 --no-locale --pwfile=pwd.txt --username=$PUPPETDB_DBUSER --xlogdir=$DBXLOG

  echo "Starting postgres instance on port ${DBPORT}"
  pg_ctl start -D $DBDATA -l logfile -o "-p ${DBPORT} -i -N 128 -T"
  sleep 2

  echo "Creating database ${DBNAME}"
  createdb -e -E UTF8 -O $PUPPETDB_DBUSER -h localhost -U $PUPPETDB_DBUSER -w -p $DBPORT $DBNAME
  rm pwd.txt
fi

###########
# Clean old old content?
rm -f testreports.xml *.war *.jar

###########
# Run tests
export HTTP_CLIENT="wget --no-check-certificate -O"

lein --version

lein clean
lein deps
lein compile
lein test

###########
# Stop and remove database instance if dbtype is postgres
if [ "$PUPPETDB_DBTYPE" == "postgres" ];
then
  echo "Cleaning up postgres database instance"
  pg_ctl stop -D $DBDATA
  rm -rf $DBBASE
fi
