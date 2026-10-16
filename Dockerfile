# Image de l'application ORPEX : interface Angular et API dans un seul JAR, lancé par un utilisateur sans droits.
# Construire : docker build -t orpex:0.1.0 .
# Les tests ne tournent pas ici (ceux d'intégration ont besoin de Docker) : lancer scripts/build.ps1 avant une livraison.

FROM node:24.22.0-alpine AS front
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

FROM maven:3.9.16-eclipse-temurin-21 AS back
WORKDIR /src/backend
COPY backend/pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY backend/src ./src
COPY --from=front /src/frontend/dist/frontend/browser/ ./src/main/resources/static/
RUN mvn -B -q package -DskipTests && cp target/orpex-backend-*.jar /orpex.jar

FROM eclipse-temurin:21.0.11_10-jre-alpine
RUN addgroup -S orpex && adduser -S -G orpex -H orpex
WORKDIR /app
COPY --from=back /orpex.jar /app/orpex.jar
USER orpex
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75" \
    ORPEX_LOG_FORMAT=ecs
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health | grep -q '"UP"' || exit 1
# exec : Java reçoit directement le signal d'arrêt et termine proprement un envoi en cours.
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/orpex.jar"]
