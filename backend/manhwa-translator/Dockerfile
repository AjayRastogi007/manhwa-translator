FROM eclipse-temurin:21-jdk

WORKDIR /app

RUN apt-get update && apt-get install -y libgl1

COPY . .

RUN ./gradlew build -x test

EXPOSE 8080

CMD ["java", "-jar", "build/libs/app.jar"]