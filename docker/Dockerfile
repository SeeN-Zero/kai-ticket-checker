ARG BASE_IMAGE=kai-ticket-checker-base:latest
FROM ${BASE_IMAGE}
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

WORKDIR /app

COPY . .

RUN mvn -DskipTests package

EXPOSE 8080

CMD ["sh", "-lc", "xvfb-run -a --server-args='-screen 0 1920x1080x24' java -jar target/kai-ticket-checker-1.0-SNAPSHOT-runner.jar"]
