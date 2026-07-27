# ------------------------------------------------------------------
# Single image used by all three Michi servers (save / multiplayer / patch).
# Each compose service simply changes its working_dir and command.
#
# NOTE: the build context is the REPOSITORY ROOT, not SERVERS/ (see the
# `context: ..` in docker-compose.yml). The engine stage below needs the
# game's own core module, which lives outside SERVERS/, so every COPY here
# is written relative to the repo root.
# ------------------------------------------------------------------

# ── Stage 1: build the authoritative gameplay engine jar. ──
# The multiplayer server shells out to this jar to run the REAL game
# simulation server-side (see multiplayer_server/game_engine.py). Without it
# the server still boots, but silently drops to no server-side gameplay
# authority — so we build it into the image rather than shipping it by hand.
# It is deliberately gitignored, so it cannot simply be COPYed from the repo.
#
# Built here (on Linux) rather than copied from a developer's machine: the jar
# is platform-neutral (gdx-platform's `natives-desktop` artifact bundles the
# Linux .so files alongside the Windows/macOS ones), but building in-image
# guarantees it always matches the committed core/ sources.
FROM eclipse-temurin:17-jdk AS engine

WORKDIR /build

# Gradle wrapper + build scripts first, so Docker can cache the (slow)
# distribution download and dependency resolution across source-only edits.
COPY gradlew                 /build/gradlew
COPY gradle                  /build/gradle
COPY build.gradle            /build/build.gradle
COPY settings.gradle         /build/settings.gradle

# The wrapper ships with CRLF line endings when checked out on Windows, which
# makes the Linux shell fail with "bad interpreter: No such file or directory".
RUN sed -i 's/\r$//' /build/gradlew && chmod +x /build/gradlew

# settings.gradle includes :desktop and :android. Neither is built here — the
# headless engine only needs :core — but Gradle still has to configure them to
# resolve the multi-project build. Stub them as plain java modules rather than
# dragging the desktop launcher and the whole Android SDK into this stage.
#
# Created BEFORE the cache-warming step below, which cannot configure the build
# without them. core/build.gradle's own dependencies are what we want cached.
RUN mkdir -p /build/desktop /build/android \
 && printf "apply plugin: 'java'\n" > /build/desktop/build.gradle \
 && printf "apply plugin: 'java'\n" > /build/android/build.gradle

# core/build.gradle alone (no sources yet) is enough to resolve and cache every
# dependency the engine jar needs, so editing game code later reuses this layer
# instead of re-downloading libGDX and LuaJ.
#
# build.gradle reads core/assets/res/build.properties at configuration time; it
# is guarded by an exists() check and falls back to defaults, so its absence
# here is harmless — the real value is baked in by the COPY below.
COPY core/build.gradle /build/core/build.gradle
RUN ./gradlew --no-daemon :core:dependencies --configuration runtimeClasspath -q > /dev/null

# Now the actual game sources.
COPY core /build/core

# → /build/SERVERS/multiplayer_server/engine.jar (destinationDirectory in
# core/build.gradle is rootProject-relative).
RUN ./gradlew --no-daemon :core:engineJar \
 && test -s /build/SERVERS/multiplayer_server/engine.jar


# ── Stage 2: the runtime image the three servers actually run in. ──
FROM python:3.12-slim AS runtime

# OS deps: cryptography needs libffi + build essentials only at compile
# time on slim, but the wheel is prebuilt for 3.12 → just install runtime.
#
# A JRE is required too: the multiplayer server launches engine.jar as a
# child process, and python:3.12-slim has no Java at all. headless-only, so
# the JRE needs no X/GL libraries.
RUN apt-get update \
 && apt-get install -y --no-install-recommends tini openjdk-17-jre-headless \
 && rm -rf /var/lib/apt/lists/*

# Python deps shared by all three servers.
COPY SERVERS/requirements.txt /tmp/requirements.txt
RUN pip install --no-cache-dir -r /tmp/requirements.txt \
 && rm /tmp/requirements.txt

# All three server trees are copied in at build time so the image is
# self-contained (no source bind-mount needed). Mutable state is mounted
# as named volumes / bind mounts in docker-compose.yml.
WORKDIR /srv
COPY SERVERS/save_server        /srv/save_server
COPY SERVERS/multiplayer_server /srv/multiplayer_server
COPY SERVERS/patch_server       /srv/patch_server

# The gameplay engine built in stage 1. game_engine.py looks for it at
# BASE_DIR/engine.jar, i.e. right next to the multiplayer server sources.
COPY --from=engine /build/SERVERS/multiplayer_server/engine.jar \
                   /srv/multiplayer_server/engine.jar

# Ensure runtime dirs exist with permissive perms (compose may bind-mount
# host folders over them; this just guarantees they're there for the
# image-only run case).
RUN mkdir -p /srv/save_server/saves \
             /srv/patch_server/patches

# tini = PID 1 → clean Ctrl-C handling, no zombie processes.
ENTRYPOINT ["/usr/bin/tini", "--"]
# Default command is overridden per-service in docker-compose.yml.
CMD ["python", "-V"]
