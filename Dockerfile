FROM maven:3.9.11-eclipse-temurin-24 AS builder
WORKDIR /app

COPY pom.xml .

RUN --mount=type=cache,target=/root/.m2 \
    mvn dependency:go-offline -B

COPY src ./src

RUN --mount=type=cache,target=/root/.m2 \
    mvn clean package -DskipTests -B

RUN java -Djarmode=layertools -jar target/*.jar extract --destination target/extracted

FROM eclipse-temurin:24-jre-noble
WORKDIR /app

ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:+UseStringDeduplication"

RUN apt-get update && apt-get upgrade -y && \
    rm -rf /var/lib/apt/lists/* && \
    groupadd -r springgroup && useradd -r -g springgroup spring

EXPOSE 8080

RUN mkdir -p /var/log/spring-ai && chown spring:springgroup /var/log/spring-ai

COPY --from=builder --chown=spring:springgroup /app/target/extracted/dependencies/ ./
COPY --from=builder --chown=spring:springgroup /app/target/extracted/spring-boot-loader/ ./
COPY --from=builder --chown=spring:springgroup /app/target/extracted/snapshot-dependencies/ ./
COPY --from=builder --chown=spring:springgroup /app/target/extracted/application/ ./

USER spring

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dspring.config.location=classpath:/application.yaml,classpath:/application-${SPRING_PROFILES_ACTIVE:-dev}.yaml org.springframework.boot.loader.launch.JarLauncher"]