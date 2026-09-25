#!/bin/bash
# SPDX-License-Identifier: MIT
# Copyright (c) 2026 Divinakra
#
# Builds everything and runs the offline checks against the official PokeWilds 0.8.11 jar.
# Usage: ./test.sh /path/to/pokewilds.jar
set -euo pipefail
[ $# -eq 1 ] || { echo "usage: $0 /path/to/official/pokewilds.jar" >&2; exit 1; }
cd "$(dirname "$0")"
GAME="$(cd "$(dirname "$1")" && pwd)/$(basename "$1")"
./build.sh
rm -rf build/test build/patched.jar && mkdir -p build/test
javac -d build/test -cp "dist/bugfix.jar:$GAME" tests/*.java 2>&1 | grep -v '^Note:' || true

echo; echo "== 1. bytecode patches: every patched site accounted for, every class passes the verifier"
java -Xverify:all -cp "build/test:dist/bugfix.jar:$GAME" local.pokewilds.bugfix.TestFloors "$GAME" 2>&1 | grep -E "^(PASS|FAIL|reads|ALL OK|FAILURES)" | grep -v "^PASS untouched" | grep -v "tiles assignments hooked"
echo; echo "== 2. per-floor Pokemon maps: logic"
java -cp "build/test:dist/bugfix.jar" local.pokewilds.bugfix.TestFloorMaps 2>&1 | grep -E "^(FAIL|ALL OK|FAILURES)|randomized"
echo; echo "== 3. as a -javaagent: all game classes load with the strict verifier"
java -Xverify:all -javaagent:dist/bugfix.jar -cp "$GAME:build/test" local.pokewilds.bugfix.LoadAll "$GAME" 2>&1 | grep -E "^\[bugfix\] (floors: game jar|agent)|loaded|VERIFY|reflection"
java -Xverify:all -javaagent:dist/bugfix.jar -cp "$GAME:build/test" HoOhCheck 2>&1 | tail -1
echo; echo "== 4. as an offline patch: patch the jar, then load it with NO agent"
java -jar dist/bugfix.jar "$GAME" build/patched.jar 2>&1 | grep -E "^(Patched|Wrote|ERROR)"
java -Xverify:all -cp "build/patched.jar:build/test" local.pokewilds.bugfix.LoadAll build/patched.jar 2>&1 | grep -E "loaded|VERIFY|reflection"
java -Xverify:all -cp "build/patched.jar:build/test" HoOhCheck 2>&1 | tail -1
echo; echo "== 5. the patcher refuses anything but the official jar"
! java -jar dist/bugfix.jar build/patched.jar build/again.jar 2>&1 | grep -E "^ERROR"
echo; echo "Done."
