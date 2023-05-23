FROM eclipse-temurin:19-jdk

WORKDIR /lincheck

COPY . .

RUN cd ./lincheck && ./gradlew publishToMavenLocal
RUN ./gradlew build testClasses -x test
