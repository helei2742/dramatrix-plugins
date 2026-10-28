#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TYPE="all"
OUTPUT_DIR="$ROOT/release/build/plugin-packages"
UNSIGNED="false"
KEY_ID="${RELEASE_PLUGIN_SIGNING_KEY_ID:-}"

usage() {
  cat >&2 <<'EOF'
usage: release/build-plugin-packages.sh [--type all|management|media|profile]
       [--output-dir DIR] [--key-id KEY_ID] [--unsigned]

Official packages require DRAMATRIX_PLUGIN_SIGNING_KEY_PEM and --key-id (or
RELEASE_PLUGIN_SIGNING_KEY_ID). --unsigned is for local inspection only.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --type) TYPE="${2:?--type requires a value}"; shift 2 ;;
    --output-dir) OUTPUT_DIR="${2:?--output-dir requires a path}"; shift 2 ;;
    --key-id) KEY_ID="${2:?--key-id requires a value}"; shift 2 ;;
    --unsigned) UNSIGNED="true"; shift ;;
    -h|--help) usage; exit 0 ;;
    *) echo "unknown option: $1" >&2; usage; exit 2 ;;
  esac
done

case "$TYPE" in
  all|management|media|profile) ;;
  *) echo "type must be all, management, media, or profile" >&2; exit 2 ;;
esac

if [[ "$UNSIGNED" != "true" ]]; then
  [[ -n "$KEY_ID" ]] || { echo "plugin signing key ID is required (--key-id or RELEASE_PLUGIN_SIGNING_KEY_ID)" >&2; exit 2; }
  [[ -n "${DRAMATRIX_PLUGIN_SIGNING_KEY_PEM:-}" ]] || { echo "DRAMATRIX_PLUGIN_SIGNING_KEY_PEM is required for signed releases" >&2; exit 2; }
fi

case "$TYPE" in
  all|media|profile)
    mvn -B -f "$ROOT/dramatrix-plugin/pom.xml" clean package \
      -Dproject.build.outputTimestamp=1980-01-01T00:00:02Z \
      -DskipTests -Dmaven.test.skip=true -Dplugin.deploy.skip=true
    ;;
esac

case "$TYPE" in
  all|management)
    mvn -B -pl \
      dramatrix-management-plugin/dramatrix-management-plugin-comfyui,\
dramatrix-management-plugin/dramatrix-management-plugin-llamacpp,\
dramatrix-management-plugin/dramatrix-management-plugin-aitoolkit,\
dramatrix-management-plugin/dramatrix-management-plugin-deepseek-harness,\
dramatrix-management-plugin/dramatrix-management-plugin-strata,\
dramatrix-management-plugin/dramatrix-management-plugin-seaweedfs,\
dramatrix-management-plugin/dramatrix-management-plugin-codex,\
dramatrix-management-plugin/dramatrix-management-plugin-ytdlp,\
dramatrix-management-plugin/dramatrix-management-plugin-stirlingpdf \
      -am clean package -Dproject.build.outputTimestamp=1980-01-01T00:00:02Z \
      -DskipTests -Dmaven.test.skip=true
    ;;
esac

if [[ "$OUTPUT_DIR" != /* ]]; then OUTPUT_DIR="$ROOT/$OUTPUT_DIR"; fi
mkdir -p "$OUTPUT_DIR"
# This directory is build staging. Clear artifacts from older invocations so a
# removed plugin or stale version cannot leak into the next release catalog.
find "$OUTPUT_DIR" -maxdepth 1 -type f \
  \( -name '*.zip' -o -name '*.zip.sig.json' -o -name '*.zip.tmp' -o -name '*.zip.sig.json.tmp' \) \
  -delete
PACKAGE_ARGS=(package-all --project-root "$ROOT" --output-dir "$OUTPUT_DIR")
if [[ "$TYPE" != "all" ]]; then PACKAGE_ARGS+=(--type "$TYPE"); fi
if [[ "$UNSIGNED" == "true" ]]; then
  PACKAGE_ARGS+=(--unsigned)
else
  PACKAGE_ARGS+=(--key-id "$KEY_ID")
fi
python3 "$SCRIPT_DIR/plugin_package.py" "${PACKAGE_ARGS[@]}"
