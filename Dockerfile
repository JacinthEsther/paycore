# Builds and runs the PayCore API. Sized for small hosts such as Render's
# free plan (512 MB RAM). Configuration comes from environment variables;
# see README.md.

# ---- Build ----
FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

# Download dependencies first so they are cached until pom.xml changes.
COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
# Tests need Docker (Testcontainers), which is not available here; run them
# locally or in CI before deploying.
RUN ./mvnw -q -B package -DskipTests \
    && cp target/paycore-*.jar app.jar

# ---- Run ----
FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 1001 paycore \
    && mkdir -p /app/data/kyc-documents \
    && chown -R paycore /app
USER paycore

COPY --from=build /workspace/app.jar app.jar

# Keep the JVM within a 512 MB container.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"

EXPOSE 8080
# Hosts like Render pass the port to listen on in $PORT.
CMD ["sh", "-c", "exec java -Dserver.port=${PORT:-8080} -jar app.jar"]
