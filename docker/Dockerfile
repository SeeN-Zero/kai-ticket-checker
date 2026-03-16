ARG BASE_IMAGE=kai-ticket-checker-base:latest
FROM ${BASE_IMAGE}
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

WORKDIR /app

# Menyalin file JAR hasil build dari host ke container (tanpa build ulang)
COPY target/*-runner.jar app.jar

EXPOSE 8080

CMD ["sh", "-lc", "xvfb-run -a --server-args='-screen 0 1920x1080x24' java -jar app.jar"]
