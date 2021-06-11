#!/usr/bin/env python3

from locust import HttpUser, task, between
import yaml, json, os

class PuppetDbLoadTest(HttpUser):
    def response_printer(self, opts, response):
        if response.status_code == 0:
            print(response.error)
            exit(1)
        elif response.status_code != 200:
            print(
            "Method: " + opts['method'],
            "Query: " + str(opts['query']),
            "Response status: " + str(response.status_code),
            "Response body: " + response.text,
            end="\n-------------------------------------------\n", sep="\n" )

    def get_request(self, opts):
        limit = opts.get('limit')
        offset = opts.get('offset')
        url = opts['path'] + "?query=" + json.dumps(opts['query'])
        if limit:
            url = url + "&limit=" + str(limit)
        if offset:
            url = url + "&offset=" + str(offset)
        with self.client.request(opts['method'], url, str(opts['query'])) as response:
            self.response_printer(opts, response)

    def post_request(self, opts):
        query = {'query': opts['query']}
        limit = opts.get('limit')
        offset = opts.get('offset')
        if limit:
            query['limit'] = limit
        if offset:
            query['offset'] = offset
        headers = opts.get('headers')
        with self.client.request(opts['method'], opts['path'], str(opts['query']), data=json.dumps(query), json=True, headers=headers) as response:
            self.response_printer(opts, response)

    @task
    def swarm(self):
        dir_path = os.path.dirname(os.path.realpath(__file__))
        with open(dir_path + '/config.yaml') as stream:
            config = yaml.safe_load(stream)
            for opts in config:
                if opts['method'] == 'GET':
                    self.get_request(opts)
                elif opts['method'] == 'POST':
                    self.post_request(opts)



