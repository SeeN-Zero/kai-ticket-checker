FROM maven:3.9.11-eclipse-temurin-21

ARG PLAYWRIGHT_VERSION=1.42.0
ENV PLAYWRIGHT_BROWSERS_PATH=/ms-playwright

# Install Linux dependencies for Chromium on Ubuntu 24.04 (noble).
RUN apt-get update && apt-get install -y --no-install-recommends \
    ca-certificates \
    fonts-liberation \
    libasound2t64 \
    libatk-bridge2.0-0 \
    libatk1.0-0 \
    libatspi2.0-0 \
    libcups2 \
    libdbus-1-3 \
    libdrm2 \
    libgbm1 \
    libglib2.0-0 \
    libnspr4 \
    libnss3 \
    libx11-6 \
    libx11-xcb1 \
    libxcb1 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxkbcommon0 \
    libxrandr2 \
    xdg-utils \
    && rm -rf /var/lib/apt/lists/*

# Install Playwright and pre-download Chromium with full runtime classpath.
RUN printf '%s\n' \
    '<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">' \
    '  <modelVersion>4.0.0</modelVersion>' \
    '  <groupId>local.playwright</groupId>' \
    '  <artifactId>installer</artifactId>' \
    '  <version>1.0.0</version>' \
    '  <dependencies>' \
    '    <dependency>' \
    '      <groupId>com.microsoft.playwright</groupId>' \
    '      <artifactId>playwright</artifactId>' \
    '      <version>'"${PLAYWRIGHT_VERSION}"'</version>' \
    '    </dependency>' \
    '  </dependencies>' \
    '</project>' > /tmp/playwright-installer-pom.xml
RUN mvn -q -f /tmp/playwright-installer-pom.xml \
    org.codehaus.mojo:exec-maven-plugin:3.5.0:java \
    -Dexec.mainClass=com.microsoft.playwright.CLI \
    -Dexec.args="install chromium"
