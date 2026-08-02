# Build and runtime are separate images so the JDK, the Gradle cache and the source tree do not
# ship to production - the result is a JRE plus this application's jars, nothing else.

FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /src

# Dependencies resolve in their own layer, so editing source does not re-download the world.
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle gradle
RUN ./gradlew --no-daemon --quiet dependencies

COPY src src
# Tests run in CI and on a laptop, not here: a failing test should not be discovered by a
# deploy, and building an image should not need a Postgres container to be reachable.
RUN ./gradlew --no-daemon --quiet installDist -x test


FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

# Never root. The bot makes outbound HTTPS calls and writes one SQLite file; nothing it does
# needs privilege, and a tip bot holds a bearer token worth stealing.
RUN addgroup -S tipbot && adduser -S -G tipbot tipbot \
 && mkdir -p /data /config \
 && chown -R tipbot:tipbot /data /config

COPY --from=build --chown=tipbot:tipbot /src/build/install/tipbot ./

USER tipbot

# The database lives on a volume. Without one, a redeploy wipes the in-flight invoices and the
# double-payout guard with them.
VOLUME ["/data"]
ENV TIPBOT_JDBC_URL="jdbc:sqlite:/data/tipbot.db" \
    TIPBOT_WALLETS="/config/tipbot.yaml" \
    # Percentage rather than a fixed -Xmx, so one image behaves on a 256 MB container and a 2 GB one.
    JAVA_OPTS="-XX:MaxRAMPercentage=70 -XX:+UseSerialGC"

# Long polling means no inbound port and no public URL - the same image runs behind any NAT.
ENTRYPOINT ["./bin/tipbot"]
