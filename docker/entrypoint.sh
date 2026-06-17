#!/usr/bin/env bash
#
# Headless launcher for the StasisBot Fabric *client* mod.
#
# A Minecraft client always needs a window + OpenGL context (LWJGL/GLFW), even
# when nothing is shown. We give it a virtual X server (Xvfb) and Mesa software
# OpenGL (llvmpipe) so it runs on a server with no GPU and no display.
#
set -euo pipefail

export GRADLE_USER_HOME="${GRADLE_USER_HOME:-/root/.gradle}"
export DISPLAY="${DISPLAY:-:99}"
export LIBGL_ALWAYS_SOFTWARE=1
export GALLIUM_DRIVER="${GALLIUM_DRIVER:-llvmpipe}"

# This server has no sound card and no audio backend (PulseAudio/ALSA), so the
# OpenAL Soft library LWJGL bundles can't open a real device and Minecraft logs
# "Failed to open OpenAL device". Force OpenAL Soft's built-in "null" backend,
# which always opens (silent output) — the game then runs without that error.
export ALSOFT_DRIVERS="${ALSOFT_DRIVERS:-null}"

# Server the bot auto-joins on launch (quickPlay). Read by build.gradle.
export STASIS_SERVER="${STASIS_SERVER:-2b2t.org}"

# The committed gradle.properties pins a Windows-only NIO workaround
# (-Djdk.net.unixdomain.tmpdir=C:/Temp) that is invalid on Linux. Override the
# JVM args via the Gradle *user* home, which has higher precedence than the
# project file, so Gradle networks correctly inside the container.
mkdir -p "${GRADLE_USER_HOME}"
cat > "${GRADLE_USER_HOME}/gradle.properties" <<'EOF'
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true
EOF

# DevAuth auto-creates its config.toml (with an [accounts.main] Microsoft entry)
# the first time it runs. We select that account explicitly via the
# `devauth.account=main` JVM property (set in build.gradle), which overrides the
# commented-out `defaultAccount` in DevAuth's generated template. The OAuth token
# is then cached in run/devauth/microsoft_accounts.json after the one-time login.
mkdir -p "/app/run/devauth"

# --- Headless render tuning -------------------------------------------------
# There's no GPU here: OpenGL goes through Mesa llvmpipe (software), so every
# rendered frame is rasterized on the CPU. With the default client options
# (renderDistance 16, maxFps 120, "fancy" graphics) the game pegs several cores
# and the game loop — which runs ticking *and* rendering on the same thread —
# starves, so the bot lags and looks frozen. A bot only needs the tick loop
# (20 TPS), not smooth visuals, so we cap the frame rate and strip rendering to
# the minimum. Written into the persisted options.txt, idempotently: replace the
# key if present, append it otherwise. Tune via env if you ever need to.
OPTS="/app/run/options.txt"
touch "${OPTS}"
set_opt() {
	local key="$1" val="$2"
	if grep -q "^${key}:" "${OPTS}"; then
		sed -i "s|^${key}:.*|${key}:${val}|" "${OPTS}"
	else
		printf '%s:%s\n' "${key}" "${val}" >>"${OPTS}"
	fi
}
set_opt maxFps "${SB_MAX_FPS:-10}"
set_opt renderDistance "${SB_RENDER_DISTANCE:-6}"
set_opt simulationDistance "${SB_SIMULATION_DISTANCE:-6}"
set_opt enableVsync false
set_opt graphicsPreset '"fast"'
set_opt entityShadows false
set_opt renderClouds '"false"'
set_opt mipmapLevels 0
set_opt biomeBlendRadius 0
set_opt particles 2
echo "[stasisbot] render tuning: maxFps=${SB_MAX_FPS:-10} renderDistance=${SB_RENDER_DISTANCE:-6}"

echo "[stasisbot] starting Xvfb on ${DISPLAY} ..."
Xvfb "${DISPLAY}" -screen 0 1280x720x24 -ac +extension GLX +render -noreset \
	>/tmp/xvfb.log 2>&1 &
XVFB_PID=$!

cleanup() {
	echo "[stasisbot] shutting down ..."
	kill "${XVFB_PID}" 2>/dev/null || true
}
trap cleanup EXIT INT TERM

# Wait for the virtual display to be ready before launching the game.
for _ in $(seq 1 50); do
	if xdpyinfo -display "${DISPLAY}" >/dev/null 2>&1; then
		break
	fi
	sleep 0.2
done

echo "[stasisbot] launching headless client -> server: ${STASIS_SERVER}"
echo "[stasisbot] first run only: DevAuth logs an 'OAuth URL' below."
echo "[stasisbot] open that URL once in a browser and sign in; Microsoft redirects"
echo "[stasisbot] to 127.0.0.1:3000 and the token is then cached in run/devauth/."

exec ./gradlew --no-daemon --console=plain runClient -Pdevauth
