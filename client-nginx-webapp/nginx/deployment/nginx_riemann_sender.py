#!/usr/bin/env python
import itertools
import struct
import socket
import sys
import time
import urllib, json
import riemann_client.client
from riemann_client.transport import TCPTransport

riemann_port = '5555'
sleep_t = 5

resource = '/load'
host_name = 'httpclient'
access_log = "/var/log/nginx/access.log"
tags = ['webapp']

resource_metric_names = {resource: ['avg_response_time', 'current_rps']}
global_metric_names = ['user_count', 'fail_ratio']

global_stats = {"stats_avg": 0, "request_count": 0}

def follow(the_file):
    """
    Follow a given file and yield new lines when they are available, like `tail -f`.
    """
    with open(the_file) as f:
        f.seek(0, 2)  # seek to eof
        while True:
            line = f.readline()
            if not line:
                time.sleep(0.1)  # sleep briefly before trying again
                continue
            yield line

def get_stats_format(line):
    return(int(float(str.split(line)[-1]) * 1000)) #extract upstream time in ms

def get_stats_average(line):
    temp = global_stats['stats_avg'] + get_stats_format(line)
    global_stats['stats_avg'] = temp/2
    return(global_stats['stats_avg'])

def merge_dicts(*dict_args):
    '''
    Given any number of dicts, shallow copy and merge into a new dict,
    precedence goes to key value pairs in latter dicts.
    '''
    result = {}
    for dictionary in dict_args:
        result.update(dictionary)
    return result


def get_stats(url):
    response = urllib.urlopen(url)
    return json.loads(response.read())


def connect(t):
    while True:
        try:
            t.connect()
            break
        except socket.error as ex:
            print "Failed to connect to %s:%s : %s" % (t.host, t.port, ex)
        time.sleep(sleep_t)
    return t


def reconnect(t):
    print "reconnecting..."
    try:
        t.disconnect()
    except:
        pass
    connect(t)
    print "reconnected."


def default_event_struct():
    return {'host': host_name,
            'tags': tags,
            'time': int(time.time()),
            'ttl': 2 * sleep_t}


def with_event_base(events_data):
    return map(lambda e: merge_dicts(default_event_struct(), e), events_data)


def resource_specific_events(rstats, resource):
    return map(lambda m_name: {'metric_f': rstats, 'service': m_name},
               resource_metric_names[resource])


def events_from_resource_stats(rstats, resource):
    return with_event_base(resource_specific_events(rstats, resource))


def build_resource_events(resource, stats):
    #for s in stats['stats']:
    #    if resource == s.get('name', ''):
    return events_from_resource_stats(stats, resource)
    #return []


def global_specific_events(stats):
    if not stats:
        return []
    return map(lambda m_name: {'metric_f': stats, 'service': m_name},
               global_metric_names)


def build_global_events(stats):
    return with_event_base(global_specific_events(stats))


def publish_events(events, client):
    print(events)
    for event in events:
        try:
            client.event(**event)
            print "SENT:", event
        except riemann_client.transport.RiemannError as ex:
            print "Riemann FAILED:", ex, event
        except Exception as ex:
            print "FAILED:", ex, event
            raise ex


def publish(resources, stats, client):
    for r in resources:
        publish_events(	build_resource_events(r, stats), client)
    publish_events(build_global_events(stats), client)


def publish_to_riemann(resources, ip, port=riemann_port, locust=access_log):
    t = TCPTransport(ip, port)
    connect(t)
    try:
        with riemann_client.client.Client(t) as client:
            while True:
                for line in follow(locust):
                    try:
                        publish(resources, get_stats_average(line), client)
                        time.sleep(sleep_t)
                    except (socket.error, struct.error) as ex:
                        reconnect(client.transport)
    finally:
        t.disconnect()


def main():
    nargs = len(sys.argv)
    if nargs < 2:
        print "usage: riemann_ip[:port] [locust stats url]"
        raise SystemExit(1)
    ip_port = sys.argv[1].split(':')
    ip = ip_port[0]
    port = len(ip_port) == 2 and ip_port[1] or riemann_port
    publish_to_riemann([resource], ip, port)


if __name__ == '__main__':
    main()
