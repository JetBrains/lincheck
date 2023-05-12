FROM openjdk:19-jdk-alpine

WORKDIR /lincheck

COPY . .

RUN ./gradlew build -x test