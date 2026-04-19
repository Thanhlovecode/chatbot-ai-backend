#!/bin/bash
# ──────────────────────────────────────────────────────────────
# generate-test-keys.sh
# Generate ephemeral RSA-2048 key pair for JWT test signing.
# Keys are NEVER committed — generated fresh each CI run.
# ──────────────────────────────────────────────────────────────

set -euo pipefail

KEY_DIR="src/test/resources/keys"
PRIVATE_KEY="$KEY_DIR/private.pem"
PUBLIC_KEY="$KEY_DIR/public.pem"

# Skip if keys already exist (idempotent for local dev)
if [ -f "$PRIVATE_KEY" ] && [ -f "$PUBLIC_KEY" ]; then
  echo "✅ Test keys already exist at $KEY_DIR/ — skipping generation"
  exit 0
fi

mkdir -p "$KEY_DIR"

# Generate PKCS#8 private key (Spring Security expects PKCS#8 format)
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 \
  -out "$PRIVATE_KEY" 2>/dev/null

# Extract public key
openssl pkey -in "$PRIVATE_KEY" -pubout -out "$PUBLIC_KEY" 2>/dev/null

echo "✅ Ephemeral test keys generated at $KEY_DIR/"
echo "   Private: $PRIVATE_KEY ($(wc -c < "$PRIVATE_KEY") bytes)"
echo "   Public:  $PUBLIC_KEY ($(wc -c < "$PUBLIC_KEY") bytes)"
