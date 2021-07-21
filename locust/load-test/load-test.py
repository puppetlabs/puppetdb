#!/usr/bin/env python3
from locust import HttpUser, task, tag
import yaml, json, os


def get_name(opts):
    if opts.get('alias'): return opts['alias']
    if opts.get('query'): return str(opts['query'])
    return opts['path']


def response_printer(opts, response):
    if response.status_code == 0:
        print(response.error)
        exit(1)
    elif response.status_code != 200:
        print(
            "Method: " + opts['method'],
            "Query: " + str(opts.get('query')),
            "Response status: " + str(response.status_code),
            "Response body: " + response.text,
            end="\n-------------------------------------------\n", sep="\n")


def create_get_url(params, opts):
    url = opts['path']
    added_first_param = False
    for param_name, param_val in params.items():
        if param_val == 'None' or param_val == 'null':
            continue
        if added_first_param:
            url += f'&{param_name}=' + param_val
        else:
            url += f'?{param_name}=' + param_val
            added_first_param = True
    return url


class PuppetDbLoadTest(HttpUser):
    def get_request(self, opts):
        params = {"limit": str(opts.get('limit')),
                  "offset": str(opts.get('offset')),
                  "order_by": json.dumps(opts.get('order_by')),
                  "query": json.dumps(opts['query'])}

        url = create_get_url(params, opts)

        with self.client.request(opts['method'], url, get_name(opts)) as response:
            response_printer(opts, response)

    def post_request(self, opts):
        query = {}
        if opts.get('query'):
            query['query'] = opts['query']
        limit = opts.get('limit')
        offset = opts.get('offset')
        if limit:
            query['limit'] = limit
        if offset:
            query['offset'] = offset
        headers = opts.get('headers')
        with self.client.request(opts['method'], opts['path'], get_name(opts), data=json.dumps(query), json=True,
                                 headers=headers) as response:
            response_printer(opts, response)

    def run_task(self, requests_file):
        dir_path = os.path.dirname(os.path.realpath(__file__))
        with open(dir_path + requests_file) as stream:
            config = yaml.safe_load(stream)
            for opts in config:
                if opts['method'] == 'GET':
                    self.get_request(opts)
                elif opts['method'] == 'POST':
                    self.post_request(opts)

    @tag('example')
    @task
    def run_example_queries(self):
        self.run_task('/example.yaml')

    @tag('console')
    @task
    def run_console_queries(self):
        self.run_task('/console.yaml')

    @tag('cd4pe')
    @task
    def run_cd4pe_queries(self):
        self.run_task('/cd4pe.yaml')

    @tag('estate')
    @task
    def run_cd4pe_queries(self):
        self.run_task('/estate-reporting.yaml')

    @tag('all')
    @task
    def run_all_queries(self):
        self.run_task('/console.yaml')
        self.run_task('/cd4pe.yaml')
        self.run_task('/estate-reporting.yaml')
