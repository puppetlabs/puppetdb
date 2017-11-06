import os
import time
import json
import random
from collections import deque


def update_nums(values):
    """recursively replace all numeric values in a dict with random floats"""
    for k, v in values.items():
        if type(v) == int or type(v) == float:
            values[k] = v * random.random()
        elif type(v) == dict:
            values[k] = update_nums(v)
    return values


def mutate(command):
    """Mutate a command in a type-specific way. Mutation logic is:
    - facts: Numeric facts change every run. Non-numeric facts are static.
    - reports: identity (not implemented)
    - catalogs: identity (not implemented)"""

    if 'values' in command:
        # we assume every numeric fact is volatile
        values = command['values']
        command['values'] = update_nums(values)
    return command


class Commands(object):
    """Represents an on-disk collection of commands in format:
    location/
      facts/
      reports/
      catalogs/"""

    def __init__(self, location='./samples'):
        self.location = location
        self.commands = None
        self.init_data()

    def init_data(self):
        """slurp everything up to avoid disk contention with PDB"""
        commands = {'facts': [], 'reports': [], 'catalogs': []}
        for path, _, files in os.walk(self.location):
            for f in files:
                data = json.load(open(os.path.join(path, f)))
                if 'facts' in path:
                    commands['facts'] = commands['facts'] + [data]
                elif 'reports' in path:
                    commands['reports'] = commands['reports'] + [data]
                elif 'catalogs' in path:
                    commands['catalogs'] = commands['catalogs'] + [data]
        self.commands = commands

    def __getitem__(self, k):
        return self.commands[k]

    def get(self, command_type):
        """get a random instance of a command type"""
        return random.choice(self.commands[command_type])


class CommandPipe(object):
    """A threadsafe ring buffer accessed in order of update timestamp,
    representing the command submission schedule and containing references to
    data pertinent to each simulated host. Access is throttled according to
    numhosts/runinterval. Multiple consumers are required to avoid hiccups."""

    def __init__(self, commands, numhosts, runinterval, events_per_report=4):
        """initialize a generator representing a realistic command sequence for
        the requested parameters, with mutation"""
        self.interval_sec = 60 * runinterval
        self.commands = commands
        t = 1000*time.time() + random.random()
        splay = t - float(self.interval_sec * 1000)
        self.state = deque()
        for i in range(numhosts):
            f = random.randint(0, len(commands['facts']) - 1)
            r = random.randint(0, len(commands['reports']) - 1)
            c = random.randint(0, len(commands['catalogs']) - 1)
            record = {"id": i, "updated": splay, "ref": [f, r, c]}
            splay = splay + self.interval_sec/float(numhosts)
            self.state.append(record)

    def __iter__(self):
        return self

    def next(self):
        record = self.state.popleft()
        elapsed = 1000*time.time() - record['updated']
        print(elapsed)
        if elapsed < self.interval_sec*1000:
            time.sleep(self.interval_sec - elapsed/1000)
        fidx, ridx, cidx = record['ref']
        f = self.commands['facts'][fidx]
        r = self.commands['reports'][ridx]
        c = self.commands['catalogs'][cidx]

        certname = 'host-' + str(record['id'])
        f['certname'] = certname
        r['certname'] = certname
        c['certname'] = certname

        if random.random() < 1:
            f = mutate(f)
        if random.random() < 1:
            r = mutate(r)
        if random.random() < 1:
            c = mutate(c)
        record['updated'] = time.time()*1000
        self.state.append(record)
        return f, r, c
