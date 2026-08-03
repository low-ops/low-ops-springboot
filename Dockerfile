FROM maven:3.9.16-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:21-jre-noble

WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

COPY --from=build /app/target/*.jar app.jar
COPY entrypoint.sh .
RUN chmod +x entrypoint.sh

EXPOSE 8000

# Small-container JVM: leave headroom for metaspace/native; prefer fast startup over peak throughput.
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=128m -Xss256k -XX:+UseSerialGC -XX:TieredStopAtLevel=1 -Djava.security.egd=file:/dev/./urandom -Dspring.jmx.enabled=false"

# Give Slow CPU/memory environments time to boot before health failures restart the container.
HEALTHCHECK --interval=10s --timeout=3s --start-period=120s --retries=3 \
  CMD curl -fsS http://127.0.0.1:8000/health || exit 1

ENTRYPOINT ["./entrypoint.sh"]
CMD ["java", "-jar", "app.jar"]
