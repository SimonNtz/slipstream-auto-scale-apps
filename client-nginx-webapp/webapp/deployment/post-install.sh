#!/bin/bash
set -e
set -x

disable_ipv6() {
    sysctl -w net.ipv6.conf.all.disable_ipv6=1
    sysctl -w net.ipv6.conf.default.disable_ipv6=1
    cat >>/etc/sysctl.conf <<EOF
net.ipv6.conf.all.disable_ipv6 = 1
net.ipv6.conf.default.disable_ipv6 = 1
EOF
}

deploy_ntpd() {
    yum install -y ntp
    systemctl enable ntpd.service
    systemctl start ntpd.service
}

setenforce Permissive

disable_ipv6
yum install -y epel-release vim bash-completion
deploy_ntpd

# deploy and start collectd with publication of metrics to riemann
url=${source_root}/webapp/deployment/collectd.sh
curl -sSfL $url | bash
