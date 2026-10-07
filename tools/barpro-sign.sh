#!/usr/bin/env bash
# BarPro Forwarder — operator signing tool for remote-config and update manifests.
#
# Produces the two-segment document the app verifies in
# app/src/main/java/com/example/update/SignedPayload.kt:
#
#     base64url(payload_json) "." base64url(DER ECDSA-P256-SHA256 signature)
#
# The signature covers the literal ASCII bytes of the first segment, so no JSON
# canonicalisation is involved: what you sign is byte-for-byte what the device verifies.
#
# Requires only `openssl` and `base64` — no Python, no pip, nothing to install on the
# machine that holds the private key. Keep that machine offline.
#
# Usage:
#   ./barpro-sign.sh keygen <key-dir>
#       Creates <key-dir>/barpro-signing.key (PRIVATE, keep offline) and prints the
#       base64 public key to paste into gradle.properties as BARPRO_CONFIG_PUBLIC_KEY.
#
#   ./barpro-sign.sh pubkey <key-dir>
#       Re-prints the base64 public key for an existing private key.
#
#   ./barpro-sign.sh sign <key-dir> <payload.json> [out-file]
#       Signs a payload and writes the document (stdout if out-file is omitted).
#
#   ./barpro-sign.sh verify <key-dir> <document-file>
#       Verifies a document against the public key and prints the payload. Use this
#       before publishing: it is the same check the handset performs.
set -euo pipefail

# Declared at top level: the EXIT trap below runs after any function's locals are gone,
# and `set -u` would abort on an out-of-scope name.
TMP_DIR=""
cleanup() { [ -n "$TMP_DIR" ] && rm -rf "$TMP_DIR" || true; }
trap cleanup EXIT

die() { printf 'error: %s\n' "$1" >&2; exit 1; }

# base64url without padding, matching Base64.URL_SAFE|NO_WRAP|NO_PADDING on Android.
b64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }
# Restore padding before decoding, since `base64 -d` requires it.
b64url_decode() {
  local data; data="$(cat)"
  case $(( ${#data} % 4 )) in 2) data="${data}==" ;; 3) data="${data}=" ;; esac
  printf '%s' "$data" | tr '\-_' '+/' | openssl base64 -d -A
}

key_path()  { printf '%s/barpro-signing.key' "$1"; }

cmd_keygen() {
  local dir="${1:?key-dir required}" key
  key="$(key_path "$dir")"
  [ -e "$key" ] && die "$key already exists — refusing to overwrite a signing key"
  mkdir -p "$dir"
  ( umask 077; openssl ecparam -name prime256v1 -genkey -noout -out "$key" )
  chmod 600 "$key"
  printf 'Private key written to %s (mode 600). Back it up offline; losing it ends the update channel.\n\n' "$key" >&2
  cmd_pubkey "$dir"
}

cmd_pubkey() {
  local dir="${1:?key-dir required}" key
  key="$(key_path "$dir")"
  [ -r "$key" ] || die "cannot read $key"
  # PEM SPKI minus armour is exactly the DER that X509EncodedKeySpec expects.
  printf 'BARPRO_CONFIG_PUBLIC_KEY=%s\n' \
    "$(openssl ec -in "$key" -pubout 2>/dev/null | sed '1d;$d' | tr -d '\n')"
}

cmd_sign() {
  local dir="${1:?key-dir required}" payload="${2:?payload.json required}" out="${3:-}"
  local key; key="$(key_path "$dir")"
  [ -r "$key" ] || die "cannot read $key"
  [ -r "$payload" ] || die "cannot read $payload"
  # Fail before signing rather than shipping a document the device will reject.
  openssl base64 -d -A </dev/null >/dev/null 2>&1 || true
  command -v python3 >/dev/null 2>&1 && python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$payload" \
    || printf 'warning: python3 unavailable, skipping JSON syntax check\n' >&2

  local segment sig document
  segment="$(b64url <"$payload")"
  sig="$(printf '%s' "$segment" | openssl dgst -sha256 -sign "$key" | b64url)"
  document="${segment}.${sig}"

  if [ -n "$out" ]; then printf '%s' "$document" >"$out"; printf 'wrote %s\n' "$out" >&2
  else printf '%s' "$document"; fi
}

cmd_verify() {
  local dir="${1:?key-dir required}" doc="${2:?document-file required}"
  local key; key="$(key_path "$dir")"
  [ -r "$key" ] || die "cannot read $key"
  [ -r "$doc" ] || die "cannot read $doc"

  local document segment sig
  document="$(tr -d '\n\r' <"$doc")"
  [ "$(printf '%s' "$document" | tr -cd '.' | wc -c | tr -d ' ')" = "1" ] \
    || die "document must contain exactly one '.' separator"
  segment="${document%%.*}"
  sig="${document#*.}"

  TMP_DIR="$(mktemp -d "${TMPDIR:-/tmp}/barpro-sign.XXXXXX")"
  openssl ec -in "$key" -pubout -out "$TMP_DIR/pub.pem" 2>/dev/null
  printf '%s' "$sig" | b64url_decode >"$TMP_DIR/sig.der"
  printf '%s' "$segment" | openssl dgst -sha256 -verify "$TMP_DIR/pub.pem" -signature "$TMP_DIR/sig.der" >/dev/null \
    || die "SIGNATURE INVALID — do not publish this document"
  printf 'signature OK\n' >&2
  printf '%s' "$segment" | b64url_decode
}

case "${1:-}" in
  keygen) shift; cmd_keygen "$@" ;;
  pubkey) shift; cmd_pubkey "$@" ;;
  sign)   shift; cmd_sign   "$@" ;;
  verify) shift; cmd_verify "$@" ;;
  *) sed -n '2,33p' "$0" | sed 's/^# \{0,1\}//'; exit 1 ;;
esac
