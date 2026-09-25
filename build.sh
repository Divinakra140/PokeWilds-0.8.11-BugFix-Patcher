#!/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Divinakra
#
# Builds dist/bugfix.jar (one jar: the -javaagent AND the offline patcher).
# Needs: a JDK 11+ (`javac`, `jar`), `curl`, and `shasum` or `sha1sum`.
# ASM 9.7 is downloaded from Maven Central and verified against pinned SHA-1s.
set -euo pipefail
cd "$(dirname "$0")"

ASM_VERSION=9.7
BASE=https://repo1.maven.org/maven2/org/ow2/asm
expected_sha1() {
  case "$1" in
    asm) echo 073d7b3086e14beb604ced229c302feff6449723 ;;
    asm-commons) echo e86dda4696d3c185fcc95d8d311904e7ce38a53f ;;
    asm-tree) echo e446a17b175bfb733b87c5c2560ccb4e57d69f1a ;;
  esac
}
sha1() { if command -v sha1sum >/dev/null; then sha1sum "$1" | cut -d' ' -f1; else shasum -a 1 "$1" | cut -d' ' -f1; fi; }

# Start from a clean slate (keep only the downloaded, checksum-verified ASM jars).
rm -rf build/tools build/agent build/asm-relocated.jar build/manifest.txt dist
mkdir -p build/lib build/tools build/agent dist
for a in asm asm-commons asm-tree; do
  jar="build/lib/$a-$ASM_VERSION.jar"
  [ -f "$jar" ] || curl -fsSL -o "$jar" "$BASE/$a/$ASM_VERSION/$a-$ASM_VERSION.jar"
  [ "$(sha1 "$jar")" = "$(expected_sha1 "$a")" ] || { echo "SHA-1 mismatch for $jar" >&2; rm -f "$jar"; exit 1; }
done
CP="build/lib/asm-$ASM_VERSION.jar:build/lib/asm-commons-$ASM_VERSION.jar:build/lib/asm-tree-$ASM_VERSION.jar"

# 1) Copy ASM into our own package so it cannot clash with the (older) ASM that is
#    already bundled inside pokewilds.jar.
javac -d build/tools -cp "$CP" tools/RelocateAsm.java
java -cp "build/tools:$CP" RelocateAsm "build/lib/asm-$ASM_VERSION.jar" build/asm-relocated.jar "local/pokewilds/bugfix/asm/"

# 2) Compile for Java 8 (the game itself targets Java 8).
javac --release 8 -d build/agent -cp build/asm-relocated.jar src/local/pokewilds/bugfix/*.java
(cd build/agent && jar xf ../asm-relocated.jar)

# 3) Package: runnable as `java -jar` (patcher) and as `-javaagent`.
printf 'Premain-Class: local.pokewilds.bugfix.BugFixAgent\nMain-Class: local.pokewilds.bugfix.PatchJar\n' > build/manifest.txt
jar --create --file dist/bugfix.jar --manifest build/manifest.txt -C build/agent .
echo "Built dist/bugfix.jar"
