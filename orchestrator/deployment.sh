#!/bin/bash

#
# NB! User writing this script doesn't know
#     what linux distribution it will run on!
#

#
# For Ubuntu distribution. Version 14.x or higher is assumed.
#

set -e
set -x

#
# NB! The target gets run always on Executing state.
# We want to avoid being run each time when scaling action is called.
ORCH_LOCK_FILE=~/orchestrator-deployment-target.lock

[ -f $ORCH_LOCK_FILE ] && { echo "Orchestrator deployment lock file exists. Exiting!.."; exit 0; }

# $riemann_conf_url should be set in the wrapper SS script.
[ -z "$riemann_conf_url" ] && { echo "Required \$riemann_conf_url environment variable is not set."; exit 1; } || true

hostname=`ss-get hostname`

cat /etc/*-release*

RIEMANN_VER_RHEL=0.2.10-1
RIEMANN_VER_DEB=0.2.10_all

riemann_dashboard_port=

_configure_selinux() {
    [ -f /selinux/enforce ] && echo 0 > /selinux/enforce || true
    [ -f /etc/sysconfig/selinux ] && sed -i -e 's/^SELINUX=.*/SELINUX=disabled/' /etc/sysconfig/selinux || true
    [ -f /etc/selinux/config ] && sed -i -e 's/^SELINUX=.*/SELINUX=disabled/' /etc/selinux/config || true
}

deploy_ntpd_rhel() {
    yum install -y ntp
    service ntpd start
    chkconfig ntpd on
}
deploy_ntpd_ubuntu() {
    apt-get update
    apt-get install -y ntp
    /etc/init.d/ntp start
    #chkconfig ntpd on
}

deploy_riemann_rhel() {
    yum localinstall -y \
        http://download.fedoraproject.org/pub/epel/6/x86_64/epel-release-6-8.noarch.rpm

    yum install -y ruby ruby-devel zlib-devel jre
    gem install --no-ri --no-rdoc riemann-client riemann-tools riemann-dash
    yum localinstall -y https://aphyr.com/riemann/riemann-${RIEMANN_VER_RHEL}.noarch.rpm
    chkconfig riemann on
    service riemann start
}
deploy_riemann_ubuntu() {
    # Ubuntu 14
    apt-get install -y ruby ruby-dev zlib1g-dev openjdk-7-jre build-essential

    gem install --no-ri --no-rdoc riemann-client riemann-tools riemann-dash

    curl -O https://aphyr.com/riemann/riemann_${RIEMANN_VER_DEB}.deb
    dpkg -i riemann_${RIEMANN_VER_DEB}.deb

    riemann_ss_conf=/etc/riemann/riemann-ss-streams.clj
    curl -sSf -o $riemann_ss_conf $riemann_conf_url/riemann-ss-streams.clj

    # Download SS Clojure client.
    ss_endpoint=$(awk -F= '/serviceurl/ {gsub(/^[ \t]+|[ \t]+$)/, "", $2); print $2}' \
                    /opt/slipstream/client/sbin/slipstream.context)
    mkdir -p /opt/slipstream/client/lib/
    clj_ss_client=/opt/slipstream/client/lib/clj-ss-client.jar
    curl -k -sSfL -o $clj_ss_client $ss_endpoint/downloads/clj-ss-client.jar

    cat > /etc/default/riemann <<EOF
EXTRA_CLASSPATH=$clj_ss_client
RIEMANN_CONFIG=$riemann_ss_conf
EOF

    # Hack.  Our `superstring` requires different version of java/lang/String.
    sed -i -e \
       's|JAR="/usr/share/riemann/riemann.jar:$EXTRA_CLASSPATH"|JAR="$EXTRA_CLASSPATH:/usr/share/riemann/riemann.jar"|' \
       /usr/bin/riemann

    /etc/init.d/riemann stop || true
    /etc/init.d/riemann start
    #chkconfig riemann on
}
start_riemann_dash() {
    cd /etc/riemann/
    wget $riemann_conf_url/dashboard.rb
    wget $riemann_conf_url/dashboard.json
    sed -i -e "s/<riemann-host>/$hostname/" /etc/riemann/dashboard.json
    sed -i -e 's/<comp-name>/webapp/g' /etc/riemann/dashboard.json
    riemann-dash dashboard.rb &
    riemann_dashboard_port=`awk '/port/ {print $3}' /etc/riemann/dashboard.rb`
}

deploy_graphite_ubuntu() {
    # Ubuntu 14
    DEBIAN_FRONTEND=noninteractive apt-get install -y graphite-web graphite-carbon apache2 libapache2-mod-wsgi
    #
    # Graphite
    rm -f /etc/apache2/sites-enabled/000-default.conf
    ln -s /usr/share/graphite-web/apache2-graphite.conf /etc/apache2/sites-enabled/
    python /usr/lib/python2.7/dist-packages/graphite/manage.py syncdb --noinput
    echo "from django.contrib.auth.models import User; User.objects.create_superuser('admin', 'email@example.com', 'password')" | \
        python /usr/lib/python2.7/dist-packages/graphite/manage.py shell
    chmod 755 /usr/share/graphite-web/graphite.wsgi
    chmod 666 /var/lib/graphite/graphite.db
    service apache2 restart
    #
    # Carbon cache
    sed -i -e 's/CARBON_CACHE_ENABLED=false/CARBON_CACHE_ENABLED=true/' /etc/default/graphite-carbon
    service carbon-cache start
}
deploy_graphite_rhel() {
    echo TODO
}

_configure_selinux
ss-display "Deploying ntpd"
deploy_ntpd_ubuntu
#deploy_ntpd_rhel
ss-display "Deploying Graphite"
deploy_graphite_ubuntu
#deploy_graphite_rhel
ss-display "Deploying Riemann"
deploy_riemann_ubuntu
#deploy_riemann_rhel
start_riemann_dash

service ufw stop
# Publish Riemann dashboard endpoint.
ss-set url.service "http://${hostname}:${riemann_dashboard_port}"

ss-display "Riemann ready!"

touch $ORCH_LOCK_FILE
exit 0

