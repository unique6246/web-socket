# ═══════════════════════════════════════════════════════════════
#  Multi-stage Dockerfile
#  Stage 1 — Build the JAR using Maven
#  Stage 2 — Run the JAR on a slim JRE image
# ═══════════════════════════════════════════════════════════════

# ── Stage 1: Build ─────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-17 AS builder

WORKDIR /build

# Copy pom first — lets Docker cache the dependency layer
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build (skip tests — run them separately in CI)
COPY src ./src
RUN mvn clean package -DskipTests -q

# ── Stage 2: Run ───────────────────────────────────────────────
FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

# Non-root user for security
RUN addgroup -S appgroup && adduser -S appuser -G appgroup

# Copy the fat JAR from the build stage
COPY --from=builder /build/target/*.jar app.jar

# Switch to non-root
USER appuser

# Expose the app port
EXPOSE 8080

# Start the application
ENTRYPOINT ["java", "-jar", "app.jar"]
