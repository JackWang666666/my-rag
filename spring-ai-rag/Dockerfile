# Spring AI RAG backend.
#
# The build stage needs a full JDK 17 (the project sets java.version=17 and will
# not compile on 8 or 11); the runtime stage only needs the JRE.
FROM maven:3.9-eclipse-temurin-17 AS builder

WORKDIR /build

# Resolve dependencies against the POM alone first, so editing a source file does
# not re-download the Spring AI / Milvus / Tika tree on every build.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
# Tests are skipped here on purpose: they need a Milvus and a Neo4j to talk to,
# which do not exist during an image build. Run `mvn verify` in CI instead.
RUN mvn -B -DskipTests package


FROM eclipse-temurin:17-jre

# curl is what the compose health check calls.
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# The artifactId is spring-ai-rag and the version 1.0.0-SNAPSHOT, so the jar is
# spring-ai-rag-1.0.0-SNAPSHOT.jar. Matched by glob rather than spelled out so a
# version bump in the POM does not break the image.
COPY --from=builder /build/target/spring-ai-rag-*.jar ./app.jar

# See the Python image: same defaults, same reason for pinning DB_PATH.
ENV UPLOAD_DIR=/app/uploads \
    DB_PATH=/app/data/rag.db \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75"

# uid 10001 on purpose — the same as the Python image. Both backends mount the
# same volumes for /app/data and /app/uploads, and a volume carries numeric
# ownership, so mismatched uids would leave one backend unable to write.
RUN useradd --create-home --uid 10001 rag \
    && mkdir -p /app/uploads /app/data \
    && chown -R rag:rag /app/uploads /app/data
USER rag

EXPOSE 8080

# start-period is generous: a cold Spring context plus the Milvus and Neo4j
# connections take noticeably longer than the Python backend's import.
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=5 \
    CMD curl -fsS http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
