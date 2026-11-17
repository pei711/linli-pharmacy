#!/bin/sh
set -eu
docker exec -i neighbor-pharmacy-test-mysql-1 sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot' < /opt/neighbor-pharmacy-cloud/pharmacy-demo.sql
echo 'synthetic_demo_seed_applied'
