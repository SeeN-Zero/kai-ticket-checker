# Runtime stage
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install Chromium and its dependencies for Puppeteer
RUN apt-get update && apt-get install -y --no-install-recommends \
    chromium-browser \
    fonts-ipafont-gothic \
    fonts-wqy-zenhei \
    fonts-thai-tlwg \
    fonts-kacst \
    fonts-freefont-ttf \
    libxss1 \
    xvfb \
    libnss3 \
    libatk1.0-0 \
    libatk-bridge2.0-0 \
    libcups2 \
    libdrm2 \
    libxcomposite1 \
    libxdamage1 \
    libxext6 \
    libxfixes3 \
    libxrandr2 \
    libgbm1 \
    libpango-1.0-0 \
    libcairo2 \
    libasound2 \
    && rm -rf /var/lib/apt/lists/*

# Copy the pre-built uber-jar from the target directory
# In Jenkins, target/*-runner.jar is copied to the server before docker build
COPY target/*-runner.jar app.jar

# Set Puppeteer executable path to the installed Chromium
ENV KAI_PUPPETEER_EXECUTABLE_PATH=/usr/bin/chromium-browser
ENV KAI_PUPPETEER_HEADLESS=true

EXPOSE 8080

# Use xvfb-run only if needed, but jvppeteer usually works fine in headless mode.
# We'll use it by default to support non-headless mode if required.
ENTRYPOINT ["xvfb-run", "-a", "--server-args='-screen 0 1280x800x24'", "java", "-jar", "app.jar"]
