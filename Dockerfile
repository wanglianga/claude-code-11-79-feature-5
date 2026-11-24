# syntax=docker/dockerfile:1

# ---- Frontend build ----------------------------------------------------------
FROM node:22-alpine AS frontend
WORKDIR /app
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---- Backend build -----------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS backend
WORKDIR /build
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
# place the built SPA where the pom's copy-spa step expects it (../frontend/dist)
COPY --from=frontend /app/dist /frontend/dist
RUN mvn -q -B -DskipTests package

# ---- Runtime -----------------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app
RUN groupadd -r ctrial && useradd -r -g ctrial ctrial
COPY --from=backend /build/target/ctrial-sync-1.0.0.jar app.jar
USER ctrial
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
