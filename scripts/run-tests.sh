#!/usr/bin/env bash
# Runs the self-contained test suite (JDK only, no Paper server and no Maven needed).
# It tests everything except the thin Paper layer (see TESTING.md for the in-game checklist).
set -euo pipefail
cd "$(dirname "$0")/.."

OUT="${TMPDIR:-/tmp}/dkb-test-classes"
rm -rf "$OUT" && mkdir -p "$OUT"

# every source that does not import org.bukkit / net.kyori
SOURCES=$(find src/main/java -name '*.java' ! -path '*/bukkit/*' ! -path '*/commands/*' ! -path '*/listener/*' ! -name 'DiscordKitBridge.java')

javac -Xlint:all,-serial --release 21 -d "$OUT" $SOURCES src/test/java/br/asthen/kitbridge/*.java
java -cp "$OUT" br.asthen.kitbridge.TestMain
