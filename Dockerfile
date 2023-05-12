FROM ubuntu

WORKDIR /lincheck

COPY . .

RUN ./gradlew build -x test