# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build

COPY pom.xml .
COPY src ./src

RUN apk add --no-cache maven && mvn -B -q package -DskipTests

# ── Stage 2: Runtime (Apache Tomcat 10.1) ────────────────────────────────────
FROM tomcat:10.1-jdk21-temurin-jammy AS runtime

LABEL maintainer="platform-ops@telecom.com"
LABEL app="flowops"
LABEL version="1.0.0"

# Remove default Tomcat webapps (security hardening)
RUN rm -rf /usr/local/tomcat/webapps/*

# Copy the WAR into Tomcat's webapps
COPY --from=builder /build/target/flowops.war /usr/local/tomcat/webapps/ROOT.war

# Tomcat tuning — connector thread pool, connection timeout
COPY docker/server.xml /usr/local/tomcat/conf/server.xml

# Non-root user for security
RUN groupadd -r tomcat && useradd -r -g tomcat tomcat \
    && chown -R tomcat:tomcat /usr/local/tomcat

USER tomcat

EXPOSE 8080

# Kubernetes health probes will call /actuator/health
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
    CMD wget -q -O- http://localhost:8080/actuator/health | grep -q '"status":"UP"' || exit 1

CMD ["catalina.sh", "run"]
