# ---- build stage ----
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY app/pom.xml .
RUN mvn -q -e -DskipTests dependency:go-offline
COPY app/src ./src
RUN mvn -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-alpine
ENV JAVA_OPTS=""
WORKDIR /app
COPY --from=build /src/target/*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["sh","-c","java $JAVA_OPTS -jar /app/app.jar"]
