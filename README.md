
## Riemann on autoscaler

Autoscaler users Riemann http://riemann.io/

## Riemann clients on components of your application

Components of your application should publish metrics to the Riemann running on 
the autoscaler component (by default on TCP:5666).

Riemann plugins and clients are available for almost all metrics collection 
applications.  Check http://riemann.io/clients.html

Example of a custom Riemann publisher in Python 
https://github.com/slipstream/slipstream-auto-scale-apps/blob/master/client-nginx-webapp/client/app/locust-riemann-sender.py

Example of installation and configuration of `write_riemann` plugin for `collectd`
https://github.com/slipstream/slipstream-auto-scale-apps/blob/master/client-nginx-webapp/webapp/deployment/collectd.sh

## Riemann streams for scaling

https://nuv.la/module/konstan/scale/autoscaler component should be part of your
application.

Set `riemann_config_url` input parameter to a public URL with the following files:
 -  riemann-ss-streams.clj - Riemann streams using SlipStream scale up/down actions,
 -  dashboard.json - Riemann dashboard layout and queries,
 -  dashboard.rb - Riemann dashboard configuration.

See https://github.com/slipstream/slipstream-auto-scale-apps/tree/master/client-nginx-webapp/scaler/riemann
for examples on how to write the above scripts and configuration files.

The main script to understand and write your own is `riemann-ss-streams.clj`.

## Riemann dashboard on autoscaler

After deployment of your application, the Riemann dashboard becomes available 
on http://\<autoscaler IP\>:6006.
