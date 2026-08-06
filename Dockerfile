# Multi-stage image: compile the Vue client, then package and run the Java API.
FROM node:25-alpine AS web
WORKDIR /web
COPY frontend/package.json ./
RUN npm install
COPY frontend ./
RUN npm run build

FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /app
COPY pom.xml ./
RUN mvn dependency:go-offline -q
COPY src ./src
COPY --from=web /web/dist ./frontend/dist
RUN mvn package -DskipTests -q

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /app/target/atoms-demo-1.0.0.jar app.jar
VOLUME ["/app/data"]
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
