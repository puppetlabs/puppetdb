#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import sys
from puppetdb import PuppetDB

if __name__ == '__main__':
    pdb = PuppetDB()
    subcommand = sys.argv[1]
    if subcommand == 'simulate':
        nhosts, interval = sys.argv[2:]
        pdb.simulate(int(nhosts), int(interval))
    if subcommand == 'query':
        pdb.query(sys.argv[2])
