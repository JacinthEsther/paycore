FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace

COPY mvnw pom.xml ./
COPY .mvn .mvn
RUN chmod +x mvnw && ./mvnw -q -B dependency:go-offline

COPY src src
RUN ./mvnw -q -B package -DskipTests \
    && cp target/paycore-*.jar app.jar

FROM eclipse-temurin:21-jre
WORKDIR /app

RUN useradd --system --uid 1001 paycore \
    && mkdir -p /app/data/kyc-documents \
    && chown -R paycore /app
USER paycore

COPY --from=build /workspace/app.jar app.jar

ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss512k -XX:TieredStopAtLevel=1"

EXPOSE 8080
CMD ["sh", "-c", "exec java -Dserver.port=${PORT:-8080} -jar app.jar"]
