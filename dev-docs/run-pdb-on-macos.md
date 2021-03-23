## Installing dependencies:
**Jdk 11:**

    brew install openjdk@11

After installing openjdk run:

    sudo ln -sfn /usr/local/opt/openjdk@11/libexec/openjdk.jdk /Library/Java/JavaVirtualMachines/openjdk-11.jdk
**Leiningen:**

    brew install leiningen

**pgbox script**

Download the [script](https://gitlab.com/pgbox-org/pgbox/-/blob/master/pgbox) locally and add it to your PATH

In the project directory install project dependencies using Leiningen:

    lein install

## Running pdb
Initialize a pdbbox environment:

    $ ext/bin/pdbbox-init \
      --sandbox ./test-sandbox \
      --pgbin /usr/local/bin \
      --pgport 5432 \
      --bind-addr 127.0.0.1 \
Run the project

    lein run services -c test-sandbox/conf.d
