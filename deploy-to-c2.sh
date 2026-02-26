#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
# deploy-to-c2.sh — PearlPlus
# ============================================================================
#
# Builds the PearlPlus plugin JAR, uploads it to the VPS, and drops it into
# the Hydra C2 commander's plugin staging directory (/data/plugins inside the
# commander container).  The C2 PluginManager watches that directory and
# alerts the admin console when new/updated JARs appear.
#
# After staging, use:
#   /zenith update PearlPlus ALL   — push to all agents
#   /zenith restart ALL             — apply the update
#
# ── Prerequisites ──────────────────────────────────────────────────────────
#
# 1. SSH key auth to the VPS (no password prompts):
#      ssh-copy-id <user>@<host>
#
# 2. Your SSH user must be in the 'docker' group on the VPS:
#      sudo usermod -aG docker <user>
#
# 3. The commander container must be running.  Verify:
#      ssh <user>@<host> "docker ps --filter name=commander"
#
# 4. Java 21 + Gradle wrapper available locally.
#
# ── Usage ──────────────────────────────────────────────────────────────────
#
#   ./deploy-to-c2.sh              # build + stage
#   SKIP_BUILD=1 ./deploy-to-c2.sh # stage existing JAR (skip gradle)
#
# ── .env file ──────────────────────────────────────────────────────────────
#
# Create .env.deploy-c2 in this repo root (gitignored).
# Required keys:
#
#   SSH_USER=<your-ssh-user>
#   SSH_HOST=<your-vps-ip>
#   COMMANDER_PREFIX=<commander-container-name-prefix>
#   STAGING_DIR=/data/plugins
#
# ============================================================================

# Load env
ENV_FILE="${ENV_FILE:-.env.deploy-c2}"
if [[ -f "$ENV_FILE" ]]; then
  set -o allexport
  source "$ENV_FILE"
  set +o allexport
fi

# Required vars
: "${SSH_USER:?Set SSH_USER in $ENV_FILE}"
: "${SSH_HOST:?Set SSH_HOST in $ENV_FILE}"
: "${COMMANDER_PREFIX:?Set COMMANDER_PREFIX in $ENV_FILE}"

# Defaults
STAGING_DIR="${STAGING_DIR:-/data/plugins}"
SKIP_BUILD="${SKIP_BUILD:-0}"
PLUGIN_PREFIX="${PLUGIN_PREFIX:-PearlPlus}"
LOCAL_JAR_DIR="${LOCAL_JAR_DIR:-build/libs}"

err()  { echo "ERROR: $*" >&2; exit 1; }
info() { echo "[deploy-to-c2] $*"; }

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# ── Preflight: verify SSH + resolve commander container ────────────────────
info "Verifying SSH access to ${SSH_USER}@${SSH_HOST}..."
if ! ssh -o ConnectTimeout=5 -o BatchMode=yes "${SSH_USER}@${SSH_HOST}" "true" 2>/dev/null; then
  err "Cannot SSH to ${SSH_USER}@${SSH_HOST}. Check your SSH key and network."
fi

info "Resolving commander container (prefix: ${COMMANDER_PREFIX})..."
COMMANDER_CONTAINER="$(ssh "${SSH_USER}@${SSH_HOST}" \
  "docker ps --format '{{.Names}}' | grep -E '^${COMMANDER_PREFIX}' | head -n 1" 2>/dev/null || true)"
if [[ -z "$COMMANDER_CONTAINER" ]]; then
  err "No running container found matching prefix '${COMMANDER_PREFIX}'. Check with: docker ps --filter name=${COMMANDER_PREFIX}"
fi
info "Found commander container: ${COMMANDER_CONTAINER}"

# ── Build ──────────────────────────────────────────────────────────────────
if [[ "$SKIP_BUILD" != "1" ]]; then
  info "Building ${PLUGIN_PREFIX}..."
  (cd "$SCRIPT_DIR" && ./gradlew shadowJar --quiet)
  info "Build complete."
else
  info "SKIP_BUILD=1 — using existing JAR."
fi

# ── Find JAR ───────────────────────────────────────────────────────────────
JAR_PATH="$(ls -1t "${SCRIPT_DIR}/${LOCAL_JAR_DIR}"/${PLUGIN_PREFIX}*.jar 2>/dev/null | head -n 1 || true)"
if [[ -z "$JAR_PATH" ]]; then
  err "No JAR found matching: ${SCRIPT_DIR}/${LOCAL_JAR_DIR}/${PLUGIN_PREFIX}*.jar"
fi
JAR_NAME="$(basename "$JAR_PATH")"
JAR_SIZE="$(du -h "$JAR_PATH" | cut -f1)"
info "JAR: ${JAR_NAME} (${JAR_SIZE})"

# ── Upload + stage ─────────────────────────────────────────────────────────
REMOTE_TMP="/tmp/${JAR_NAME}"

info "Uploading to VPS..."
scp -q "$JAR_PATH" "${SSH_USER}@${SSH_HOST}:${REMOTE_TMP}"

info "Staging into commander container (${COMMANDER_CONTAINER}:${STAGING_DIR})..."
ssh "${SSH_USER}@${SSH_HOST}" bash -s <<REMOTE
set -euo pipefail
# Ensure staging dir exists inside container
docker exec "${COMMANDER_CONTAINER}" mkdir -p "${STAGING_DIR}"
# Remove old versions of this plugin from staging
docker exec "${COMMANDER_CONTAINER}" sh -c "rm -f '${STAGING_DIR}/${PLUGIN_PREFIX}'*.jar 2>/dev/null || true"
# Copy new JAR into staging
docker cp "${REMOTE_TMP}" "${COMMANDER_CONTAINER}:${STAGING_DIR}/${JAR_NAME}"
# Verify
docker exec "${COMMANDER_CONTAINER}" ls -lh "${STAGING_DIR}/${JAR_NAME}"
# Cleanup temp
rm -f "${REMOTE_TMP}"
REMOTE

info ""
info "Staged ${JAR_NAME} -> ${COMMANDER_CONTAINER}:${STAGING_DIR}/"
info ""
info "Next steps:"
info "  /zenith update ${PLUGIN_PREFIX} ALL   — deploy to all agents"
info "  /zenith restart ALL                   — apply the update"
