#!/bin/bash
set -e
set -x

# $source_root should be set in the wrapper SS script.
source_location=${source_root}/webapp/app

hostname=`ss-get hostname`

deploy_webapp() {

    curl -sSf -o ~/webapp.py $source_location/webapp.py
    chmod +x ~/webapp.py
    yum install -y python-pip
    pip install flask
    ~/webapp.py &
}

deploy_webapp

ss-set ready true
url=http://$hostname
ss-set url.service $url
ss-display "Webapp $url is ready!"
