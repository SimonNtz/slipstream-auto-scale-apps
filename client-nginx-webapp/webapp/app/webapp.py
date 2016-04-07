#!/usr/bin/env python
import socket
from decimal import Decimal, getcontext

from flask import Flask

def get_ip_address():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.connect(("8.8.8.8", 80))
    ip = s.getsockname()[0]
    s.close()
    return ip

def pi_archimedes(n):
    polygon_edge_length_squared = Decimal(2)
    polygon_sides = 2
    for i in range(n):
        polygon_edge_length_squared = 2 - 2 * (1 - polygon_edge_length_squared / 4).sqrt()
        polygon_sides *= 2
    return polygon_sides * polygon_edge_length_squared.sqrt()

def load_calc(places=100):
    old_result = None
    getcontext().prec = 2*places
    for n in range(10*places):
        getcontext().prec = 2*places
        result = pi_archimedes(n)
        getcontext().prec = places
        result = +result
        if result == old_result:
            break
        old_result = result
    return result

app = Flask(__name__)

#@app.route('/')
#def hello_world():
#    return 'Hello from:' + get_ip_address()

@app.route('/load')
def load():
    res = load_calc(100)
    return 'Work result: %s' % res

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=80, threaded=True)
    #app.run(debug=True, host='0.0.0.0', port=80, threaded=True)
