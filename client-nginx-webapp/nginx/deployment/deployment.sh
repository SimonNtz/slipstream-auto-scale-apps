#!/bin/bash
set -e
set -x

riemann_host=`ss-get autoscaler_hostname`
riemann_port=5555



deploy_and_run_riemann_client() {
    yum install -y python-pip python-devel gcc zeromq-devel
    pip install --upgrade pip
    pip install pyzmq
    pip install --upgrade six

    # Due to https://bugs.debian.org/cgi-bin/bugreport.cgi?bug=835688
    pip install protobuf==3.1.0
    pip install riemann-client==6.3.0
    source_location=${source_root}/nginx/deployment

    curl -sSf -o ~/nginx_riemann_sender.py $source_location/nginx_riemann_sender.py

    # Autoscaler ready synchronization flag!
    ss-display "Waiting for Riemann to be ready."
    ss-get --timeout 600 autoscaler_ready

    chmod +x ~/nginx_riemann_sender.py
    ~/nginx_riemann_sender.py $riemann_host:$riemann_port &
}


deploy_collectd() {
    #
    # For collectd 5.x with collectd-write_riemann
    #

    ss-display "Installing collectd."

    hw_name=`uname -m`
    cat >/etc/yum.repos.d/collectd.repo<<EOF
[collectd-5.x]
name=collectd-5.x
baseurl=http://mirror.symnds.com/distributions/gf/el/6/plus/${hw_name}/
enabled=1
gpgcheck=0
EOF

    yum install -y \
        collectd \
        collectd-write_riemann \
        collectd-nginx

    cat > /etc/collectd.conf <<EOF
Hostname    "nginx"
BaseDir     "/var/lib/collectd"
PIDFile     "/var/run/collectd.pid"
PluginDir   "/usr/lib64/collectd"

#LoadPlugin syslog
#<Plugin syslog>
#       LogLevel info
#</Plugin>

LoadPlugin logfile
<Plugin logfile>
       LogLevel info
       File "/var/log/collectd.log"
       Timestamp true
       PrintSeverity true
</Plugin>

Include "/etc/collectd.d"
EOF

    cat > /etc/collectd.d/nginx.conf<<EOF
LoadPlugin nginx
<Plugin nginx>
       URL "http://localhost/nginx_status"
</Plugin>
EOF

    cat >/etc/collectd.d/write_riemann.conf<<EOF
LoadPlugin write_riemann

<Plugin "write_riemann">
    <Node "local">
        Host "$riemann_host"
        Port "$riemann_port"
        Protocol TCP
        StoreRates true
        AlwaysAppendDS false
    </Node>
    Tag "collectd"
    Tag "nginx"
</Plugin>

<Target "write">
    Plugin "write_riemann/local"
</Target>
EOF

    # Autoscaler ready synchronization flag!
    ss-display "Waiting for Riemann to be ready."
    ss-get --timeout 600 autoscaler_ready

    systemctl enable collectd.service
    systemctl start collectd.service
}

deploy_nginx() {
    yum install -y nginx

    [ -d /usr/share/nginx/html ] && mv /usr/share/nginx/html{,.bak}

   cat > /etc/nginx/nginx.conf<<EOF
user nginx;
worker_processes auto;
error_log /var/log/nginx/error.log;
pid /run/nginx.pid;

events {
    use epoll;
    worker_connections 1024;
    multi_accept on;
}

http {
    log_format  main  '\$remote_addr - \$remote_user [\$time_local] "\$request" '
                      '\$status \$body_bytes_sent "\$http_referer" '
                      '"\$http_user_agent" "\$http_x_forwarded_for" '
                      '$upstream_response_time

    access_log  /var/log/nginx/access.log  main;

    sendfile            on;
    tcp_nopush          on;
    tcp_nodelay         on;
    keepalive_timeout   65;
    types_hash_max_size 2048;

    include             /etc/nginx/mime.types;
    default_type        application/octet-stream;

    include /etc/nginx/conf.d/*.conf;
}
EOF

    # Wait for webapp to be fully deployed.
    webapp_ids=$(echo `ss-get webapp:ids` | tr ',' ' ')

    ss-display "Waiting for upstream cluster to be ready."
    for w_id in $webapp_ids; do
        ss-get --timeout 600 webapp.${w_id}:ready
    done

    servers_block=
    n="
"
    for w_id in $webapp_ids; do
        h=`ss-get webapp.${w_id}:hostname`
        servers_block="${servers_block}${n}server ${h};"
    done
    cat >/etc/nginx/conf.d/upstream-http-cluster.block<<EOF
upstream http-cluster {
    #least_conn;
    #ip_hash;
$servers_block
}
EOF
    cat > /etc/nginx/conf.d/http-cluster.conf<<EOF
include /etc/nginx/conf.d/upstream-http-cluster.block;

server {
    listen 80;

    location / {
        proxy_pass http://http-cluster;
    }

    location /nginx_status {
       stub_status on;
       access_log  off;
       #allow 127.0.0.1;
       allow all;
       #deny all;
    }
}
EOF

    systemctl enable nginx.service
    systemctl start nginx.service
}


deploy_nginx
deploy_and_run_riemann_client
deploy_collectd


hostname=`ss-get hostname`
url="http://$hostname"
ss-set url.service $url
ss-display "Webapp via Nginx proxy: $url"
