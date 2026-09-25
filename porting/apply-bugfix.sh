#!/bin/sh
# Applies the bug fixes to an official PokeWilds 0.8.11 jar and checks the result.
# Usage: apply-bugfix.sh <official pokewilds.jar> <output jar>
# Needs: Java 8+ (set JAVA to use a specific binary). The input file is never changed.
# Exit codes: 0 = patched and verified, 2 = refused (nothing written), 3 = patched but the result did not verify.
set -eu
[ $# -eq 2 ] || { echo "usage: $0 <official pokewilds.jar> <output jar>" >&2; exit 1; }
here=$(cd "$(dirname "$0")" && pwd)
JAVA=${JAVA:-java}
log=$(mktemp)
if ! "$JAVA" -Xmx64m -jar "$here/bugfix.jar" "$1" "$2" >"$log" 2>&1; then
  grep -E '^ERROR' "$log" >&2 || cat "$log" >&2
  rm -f "$log"; exit 2
fi
if grep -q "^Patched 22 of 405 game classes\." "$log" && grep -q "identical to the reference build" "$log"; then
  echo "patched: $2"; rm -f "$log"; exit 0
fi
cat "$log" >&2; rm -f "$log"; rm -f "$2"
echo "the patch ran but the result is not the expected one; the output was removed" >&2
exit 3
