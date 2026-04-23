# Stage 1: Build the Angular frontend
FROM docker.io/library/node:18 AS frontend-build
WORKDIR /app/ui
COPY ui/package.json ui/package-lock.json ./
RUN npm install
COPY ui/ ./
RUN npm run build -- --configuration production

# Stage 2: Build the Spring Boot backend
FROM docker.io/library/maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
# Copy the built frontend to spring boot static resources
COPY --from=frontend-build /app/ui/dist/bulkaibcd/browser src/main/resources/static
RUN mvn package -DskipTests

# Stage 3: Run the application
FROM docker.io/library/eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=backend-build /app/target/bulkaibcd-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
