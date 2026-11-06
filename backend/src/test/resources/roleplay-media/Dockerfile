FROM eclipse-temurin:21.0.12_8-jre-noble
RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*
ENTRYPOINT ["sleep", "infinity"]
