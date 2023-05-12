FROM eclipse-temurin:19-jdk

WORKDIR /lincheck

COPY . .

RUN ./gradlew build -x test