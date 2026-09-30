#!/usr/bin/env bash
# Creates your release signing key (once) and keystore.properties in the project root.
# Run it yourself: it asks for the password in your terminal. Keep the key file and the password
# safe and backed up: every future update of the app must be signed with the same key.
# Usage: tools/create_release_key.sh
set -euo pipefail

KEYTOOL="${JAVA_HOME:-/home/anits/Downloads/android-studio-panda4-patch1-linux/android-studio/jbr}/bin/keytool"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIR="$HOME/ludo-release-key"
KEY="$DIR/ludo-release.jks"

if [ -e "$KEY" ]; then echo "$KEY already exists. Not overwriting it."; exit 1; fi

read -rsp "Choose a password for the release key (at least 6 characters): " PASS; echo
read -rsp "Type it again: " PASS2; echo
[ "$PASS" = "$PASS2" ] || { echo "The two passwords are different. Nothing was created."; exit 1; }
[ "${#PASS}" -ge 6 ] || { echo "The password is shorter than 6 characters. Nothing was created."; exit 1; }

mkdir -p "$DIR"
chmod 700 "$DIR"
export LUDO_KEY_PASS="$PASS"
"$KEYTOOL" -genkeypair -keystore "$KEY" -storetype PKCS12 -alias ludo -keyalg RSA -keysize 4096 \
    -validity 10000 -dname "CN=Ludo Duel" -storepass:env LUDO_KEY_PASS -keypass:env LUDO_KEY_PASS

umask 077
printf 'storeFile=%s\nstorePassword=%s\nkeyAlias=ludo\nkeyPassword=%s\n' "$KEY" "$PASS" "$PASS" > "$ROOT/keystore.properties"
echo "Created $KEY and $ROOT/keystore.properties."
echo "Back up both, and remember the password. Every future update must be signed with this key."
