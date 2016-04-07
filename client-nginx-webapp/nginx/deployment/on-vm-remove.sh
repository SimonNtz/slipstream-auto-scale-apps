#!/bin/bash
set -e
set -x

on_webapp() {
    webapp_ids=$(echo `ss-get webapp:ids` | tr ',' ' ')
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
    systemctl reload nginx.service
}

case $SLIPSTREAM_SCALING_NODE in
    "webapp" )
        on_webapp ;;
esac
