# Runtime image only: CI (and local builds) run `./gradlew :server:installDist` first.
FROM eclipse-temurin:21-jre-noble
RUN useradd --system --uid 10001 --no-create-home loopdo
COPY server/build/install/server /app
USER 10001
# Small, predictable footprint for a 2-vCPU / 12 GB box shared with other services.
ENV JAVA_OPTS="-Xmx192m -XX:+UseSerialGC -XX:+ExitOnOutOfMemoryError -XX:TieredStopAtLevel=1"
EXPOSE 8080
ENTRYPOINT ["/app/bin/server"]
