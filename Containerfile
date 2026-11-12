# The SuperAwesomeMagicalHealth server, with its web app. Build from the repository root:
#   podman build -t superawesomemagicalhealth .        (or: docker build -f Containerfile .)
# See README.md for running it.

# The web app (built once, whatever platform the image is for).
FROM --platform=$BUILDPLATFORM docker.io/library/node:22-alpine AS web
WORKDIR /src/web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
RUN npm run build

# The server (Java bytecode, so also built once).
FROM --platform=$BUILDPLATFORM docker.io/library/eclipse-temurin:21-jdk AS server
WORKDIR /src
# Leaves the Android app out of the build: it needs the Android SDK.
ENV SAMH_SERVER_ONLY=1
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle/ gradle/
# Only for the version number, which the whole project shares.
COPY android/build.gradle.kts android/
COPY shared/ shared/
COPY server/ server/
COPY --from=web /src/web/dist web/dist
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -Dorg.gradle.jvmargs=-Xmx1g :server:installDist

FROM docker.io/library/eclipse-temurin:21-jre-alpine
RUN apk add --no-cache tini \
    && addgroup -S -g 1000 samh && adduser -S -D -H -u 1000 -G samh samh \
    && mkdir -p /data && chown samh:samh /data
COPY --from=server /src/server/build/install/samh-server /opt/samh
ENV SAMH_DATA_DIR=/data \
    SAMH_PORT=8080 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC"
USER samh
VOLUME /data
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD wget -qO /dev/null http://127.0.0.1:8080/api/health || exit 1
LABEL org.opencontainers.image.title="SuperAwesomeMagicalHealth" \
      org.opencontainers.image.description="Your own health server: wearables, food, sleep and Oura-style scores, with single sign-on" \
      org.opencontainers.image.licenses="GPL-3.0-only"
ENTRYPOINT ["/sbin/tini", "--", "/opt/samh/bin/samh-server"]
