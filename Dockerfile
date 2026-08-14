# Copyright 2026 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     https://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

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
RUN mvn package -Dmaven.test.skip=true

# Stage 3: Run the application
FROM docker.io/library/eclipse-temurin:17-jre-jammy
WORKDIR /app
COPY --from=backend-build /app/target/bulkaibcd-0.0.1-SNAPSHOT.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]