FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:17-jre-jammy
WORKDIR /app
RUN groupadd --gid 10001 timealert && useradd --uid 10001 --gid timealert --no-create-home timealert
COPY --from=build --chown=timealert:timealert /build/target/timealert-0.0.1-SNAPSHOT.jar /app/app.jar
USER timealert
EXPOSE 10000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=60.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
