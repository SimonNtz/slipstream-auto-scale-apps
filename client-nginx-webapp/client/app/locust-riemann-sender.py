#!/usr/bin/env python
import struct
import socket
import sys
import time
import urllib, json
import riemann_client.client
from riemann_client.transport import TCPTransport

riemann_port = '5555'
locust_stats_url = 'http://localhost:8089/stats/requests'
sleep_t = 5

th_min = 5000.0
th_max = 7000.0

resource = '/load'
m_name = 'avg_response_time'
host_name = 'httpclient'
tags = ['webapp']


def m_to_state(m):
    if m <= th_min:
        return 'ok'
    elif th_min < m <= th_max:
        return 'warning'
    else:
        return 'critical'


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


def event_from_stats(s):
    m = s.get(m_name)
    return {'host': host_name,
            'service': m_name,
            'metric_f': m,
            'tags': tags,
            'state': m_to_state(m),
            'time': int(time.time()),
            'ttl': 2 * sleep_t}


def publish(stats, client):
    for s in stats['stats']:
        if resource == s.get('name', ''):
            event = event_from_stats(s)
            try:
                client.event(**event)
                print "SENT:", event
            except Exception as ex:
                print "FAILED:", ex, event
                raise ex


def publish_to_riemann(ip, port=riemann_port, locust=locust_stats_url):
    t = TCPTransport(ip, port)
    connect(t)
    try:
        with riemann_client.client.Client(t) as client:
            while True:
                stats = get_stats(locust)
                try:
                    publish(stats, client)
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
    locust = nargs > 2 and sys.argv[2] or locust_stats_url
    publish_to_riemann(ip, port, locust)


if __name__ == '__main__':
   main()
