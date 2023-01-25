ARG TRINO_VERSION=403

FROM docker.io/library/maven:3.8.6-openjdk-18 AS builder
WORKDIR /root/redis-sql-trino
COPY . /root/redis-sql-trino
ENV MAVEN_FAST_INSTALL="-DskipTests -Dair.check.skip-all=true -Dmaven.javadoc.skip=true -B -q -T C1"
RUN mvn package $MAVEN_FAST_INSTALL

FROM trinodb/trino:${TRINO_VERSION}

COPY --from=builder --chown=trino:trino /root/redis-sql-trino/target/redis-sql-trino-*/* /usr/lib/trino/plugin/redisearch/

USER root:root
RUN apt-get update
RUN apt-get install -y -q gettext uuid-runtime
COPY --chown=trino:trino docker/etc /etc/trino
COPY docker/template docker/setup.sh /tmp/

RUN chmod 0777 /tmp/setup.sh

USER trino:trino

CMD ["/tmp/setup.sh"]