import os
import time
import json
import random
from collections import deque
import random
import copy


def update_nums(values):
    """recursively replace all numeric values in a dict with random floats"""
    for k, v in values.items():
        if type(v) == int or type(v) == float:
            values[k] = v * random.random()
        elif type(v) == dict:
            values[k] = update_nums(v)
    return values

def kinda_random(certname, give_int=False):
    rand_float = random.Random(certname).random()
    if give_int:
        return int(10 * rand_float)
    else:
        return rand_float

def make_nested_fact(keyname, levels):
    """generate a nested fact with unique fact name key values"""
    key_suffix = kinda_random(keyname + str(levels))
    key_val = f"{keyname}-{key_suffix}"
    if levels <= 0:
        return {key_val: "val"}
    else:
        return {key_val: make_nested_fact(keyname, (levels - 1))}

def mutate(command, orphans=False):
    """Mutate a command in a type-specific way. Mutation logic is:
    - facts: Numeric facts change every run. Non-numeric facts are static.
    - reports: identity (not implemented)
    - catalogs: identity (not implemented)"""

    command_copy = copy.deepcopy(command)

    if 'values' in command_copy:
        # we assume every numeric fact is volatile
        values = command_copy['values']
        command_copy['values'] = update_nums(values)

        if orphans:
            certname = command_copy['certname']
            num_nested_facts = kinda_random(certname, True)

            for num in range(num_nested_facts):
                top_key = f"{certname}-{num}"
                levels = kinda_random(top_key, True)
                command_copy['values'][top_key] = make_nested_fact(top_key, levels)
    return command_copy


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

    def __init__(self, commands, numhosts, runinterval,
                  orphans=False, events_per_report=4):
        """initialize a generator representing a realistic command sequence for
        the requested parameters, with mutation"""
        self.interval_sec = 60 * runinterval
        self.commands = commands
        t = 1000*time.time() + random.random()
        splay = t - float(self.interval_sec * 1000)
        self.state = deque()
        self.orphans = orphans
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
            f = mutate(f, self.orphans)
        if random.random() < 1:
            r = mutate(r)
        if random.random() < 1:
            c = mutate(c)
        record['updated'] = time.time()*1000
        self.state.append(record)
        return f, r, c
