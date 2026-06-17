# Headless StasisBot: runs the Fabric *client* mod with no display by giving
# LWJGL/GLFW a virtual X server (Xvfb) and Mesa software OpenGL (llvmpipe).
# Authentication is handled at runtime by DevAuth (real Microsoft OAuth), so no
# credentials are ever baked into the image.
FROM eclipse-temurin:21-jdk-jammy

ENV DEBIAN_FRONTEND=noninteractive
ENV GRADLE_USER_HOME=/root/.gradle

# Xvfb (virtual display) + Mesa software GL + the X libraries GLFW dlopens.
RUN apt-get update && apt-get install -y --no-install-recommends \
        xvfb x11-utils \
        libgl1 libglx-mesa0 libgl1-mesa-dri libglu1-mesa \
        libx11-6 libxext6 libxrender1 libxi6 libxcursor1 libxrandr2 \
        libxinerama1 libxxf86vm1 libxkbcommon0 libxtst6 \
        ca-certificates \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Gradle wrapper + build scripts first for better layer caching.
COPY gradlew gradlew.bat settings.gradle build.gradle gradle.properties ./
COPY gradle ./gradle
# Normalize CRLF (committed from Windows) so the wrapper runs under bash.
RUN sed -i 's/\r$//' gradlew && chmod +x gradlew

# Mod source + the headless entrypoint.
COPY src ./src
COPY docker/entrypoint.sh /usr/local/bin/entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/entrypoint.sh \
    && chmod +x /usr/local/bin/entrypoint.sh

# Server the bot auto-joins on launch. Override per run (compose / -e).
ENV STASIS_SERVER=2b2t.org

ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
