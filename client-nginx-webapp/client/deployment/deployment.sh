#!/bin/bash
set -e
set -x
set -o pipefail

# $source_root should be set in the wrapper SS script.
source_location=${source_root}/client/app

riemann_host=`ss-get autoscaler_hostname`
riemann_port=5555

# Nginx.
webapp_ip=`ss-get webapp`

hostname=`ss-get hostname`

deploy_httpclient() {
    yum install -y python-pip python-devel gcc zeromq-devel
    pip install --upgrade pip
    pip install pyzmq
    pip install locustio
}

run_httpclient() {
    curl -sSf -o ~/locust-tasks.py $source_location/locust-tasks.py
    locust --host=http://$webapp_ip -f ~/locust-tasks.py WebsiteUser &
}

deploy_and_run_riemann_client() {
    pip install --upgrade six
    # Due to https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=835688
    pip install protobuf==3.1.0
    pip install riemann-client==6.3.0

    curl -sSf -o ~/locust_riemann_sender.py $source_location/locust_riemann_sender.py

    # Autoscaler ready synchronization flag!
    ss-display "Waiting for Riemann to be ready."
    ss-get --timeout 600 autoscaler_ready

    chmod +x ~/locust_riemann_sender.py
    ~/locust_riemann_sender.py $riemann_host:$riemann_port &
}

deploy_landing_web_page() {
    yum install -y httpd
    rm -rf /var/www/html/index.html
    curl -sSf -o /var/www/html/scalable-app-in-SS.png $source_location/scalable-app-in-SS.png
    curl -sSf -o /var/www/html/index.html $source_location/index.html
    sed -i -e "s|locust_url|http://${hostname}:8089|" \
           -e "s|riemann_dashboard_url|http://${riemann_host}:6006|" \
           -e "s|webapp_url|http://${webapp_ip}/load|" \
           /var/www/html/index.html
    systemctl start httpd
}

deploy_httpclient
run_httpclient
deploy_and_run_riemann_client
deploy_landing_web_page

url="http://${hostname}"
ss-set ss:url.service $url
ss-set url.service $url
ss-display "Load generator: $url:8089"

