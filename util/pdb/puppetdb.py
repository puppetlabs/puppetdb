import requests
import time
from requests.compat import urljoin
from commands import Commands
from commands import CommandPipe
import asyncio
import sys
import json
import ijson
import grequests
from datetime import datetime


DEFAULT_MUTATION_OPTS = {
    "events_per_report": 4
}

COMMAND_VERSIONS = {
    "replace_facts": 4,
    "store_report": 7,
    "replace_catalog": 9
}


DEFAULT_METRICS = {
    "replace_facts_time":
    "puppetlabs.puppetdb.storage:name=replace-facts-time",
    "queue_time":
    "puppetlabs.puppetdb.mq:name=global.queue-time",
    "command_parse_time":
    "puppetlabs.puppetdb.mq:name=global.command-parse-time",
    "queue_depth":
    "puppetlabs.puppetdb.mq:name=global.depth"
}


class PuppetDB(object):
    """ An interface to a PuppetDB instance """
    def __init__(self, host="localhost", port=8080):
        self.commands = None
        self.metrics = DEFAULT_METRICS
        self.baseurl = "http://%s:%s/" % (host, port)

    def __request(self, method, endpoint, data=None, params={}):
        if method == 'get':
            r = requests.get(urljoin(self.baseurl, endpoint), data=data)
            return r
        elif method == 'post':
            return grequests.post(urljoin(self.baseurl, endpoint),
                                  params=params, data=data)

    def query(self, query):
        """write the result of a query to an output stream"""
        url = urljoin(self.baseurl, '/pdb/query/v4')
        out = sys.stdout
        data = {'query': query}
        with requests.get(url, data=data, stream=True) as r:
            objects = ijson.items(r, 'item')
            out.write('[\n')
            d = next(objects)
        while d:
            out.write(json.dumps(d, indent=4, sort_keys=True))
            try:
                d = next(objects)
                out.write(",\n")
            except:
                out.write("]")
                break

    def submit_command(self, certname, command, data):
        """ submit a command to PuppetDB. Parameters are:
        certname: the certname of the agent associated with the command
        command: the command name ('replace_facts', 'store_report', etc)
        data: the full command body, as described in PDB's wire format docs"""

        data["producer_timestamp"] = datetime.now().isoformat()
        json_data = json.dumps(data)
        version = COMMAND_VERSIONS[command]
        return self.__request('post', '/pdb/cmd/v1', data=json_data,
                              params={"certname": certname,
                                      "command": command,
                                      "version": str(version)})

    def _simulate(self, stream, numhosts, runinterval):
        while True:
            reqs = []
            bufsize = 10
            for i in range(bufsize):
                f, r, c = stream.next()
                reqs += [self.submit_command(
                    f['certname'], 'replace_facts', f)]
            grequests.map(reqs)

    def _record_metrics(self, metrics, outputfile):
        while True:
            result = requests.post(
                urljoin(self.baseurl, '/metrics/v1/mbeans'),
                data=json.dumps(metrics),
                headers={"Content-Type": "application/json"}).json()

            with open(outputfile, 'a') as f:
                result.update({"timestamp": str(datetime.now())})
                f.write(json.dumps(result) + '\n')
            time.sleep(60)

    def prepare_sample(self):
        """ interactively prepare a simulation seed set """

    def simulate(self, numhosts, runinterval, orphans=False,
                 events_per_report=4, opts=DEFAULT_MUTATION_OPTS):
        """Submit simulated commands to PDB, targeting equivalence to a given
        number of hosts, run interval, and mutation rate. Record selected
        metrics in a log file on an interval.
        """
        if not self.commands:
            self.commands = Commands()
        s = CommandPipe(self.commands, numhosts, runinterval, orphans)
        t = datetime.now().isoformat()
        outputfile = 'simulation-logs-%s-%s-%s.txt' % (numhosts,
                                                       runinterval, t)
        nworkers = 8
        loop = asyncio.get_event_loop()

        async def sim():
            futures = [
                loop.run_in_executor(
                    None,
                    self._simulate,
                    s,
                    numhosts,
                    runinterval
                )
                for i in range(nworkers)
            ] + [loop.run_in_executor(None, self._record_metrics,
                                      self.metrics, outputfile)]
            return await asyncio.gather(*futures)

        loop = asyncio.get_event_loop()
        loop.run_until_complete(sim())
        loop.close()
