## Next generation developer utilities for PDB

A python CLI utility for

* load simulation (currently in a hacky state, only supporting facts)
* querying (no streaming, possibly broken)

### Setup

* Install virtualenv and python 3
    apt-get install virtualenv

* Set up virtualenv
    virtualenv -p python3 venv
    source venv/bin/activate
    pip install -r requirements.txt

* Run the tool
    ./pdb simulate 100000 30
