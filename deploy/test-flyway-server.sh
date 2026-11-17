#!/bin/sh
# Creates a separate schema and candidate container in the dedicated test stack.
set -eu
cd /opt/neighbor-pharmacy-cloud
container=neighbor-pharmacy-test-mysql-1
database=npc_flyway_test_20261116
docker exec "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; exec mysql -uroot -e "CREATE DATABASE npc_flyway_test_20261116 CHARACTER SET utf8mb4; GRANT ALL ON npc_flyway_test_20261116.* TO '\''hmdp'\''@'\''%'\'';"'
docker exec "$container" sh -c 'export MYSQL_PWD="$MYSQL_ROOT_PASSWORD"; mysqldump -uroot --no-data --skip-add-drop-table --no-tablespaces hmdp | mysql -uroot npc_flyway_test_20261116'
docker run -d --name neighbor-pharmacy-flyway-check --network neighbor-pharmacy-test_default \
  --memory 320m --memory-swap 320m --cpus 0.5 --env-file .env \
  -e DB_URL="jdbc:mysql://mysql:3306/$database?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai" \
  -e DB_USERNAME=hmdp -e PHARMACY_FLYWAY_ENABLED=true -e REDIS_HOST=redis \
  -e SPRING_DATA_REDIS_DATABASE=15 -e KAFKA_BOOTSTRAP_SERVERS=kafka:19092 \
  -e KAFKA_PUSH_TOPIC=pharmacy-test-push -e KAFKA_STORE_TOPIC=pharmacy-test-store \
  -e KAFKA_PUSH_GROUP=pharmacy-test-push -e KAFKA_STORE_GROUP=pharmacy-test-store \
  -v /opt/neighbor-pharmacy-cloud/app-candidate.jar:/app/app.jar:ro \
  eclipse-temurin:17-jre java -Xms64m -Xmx192m -XX:MaxMetaspaceSize=96m -jar /app/app.jar >/dev/null
echo 'candidate_started'
