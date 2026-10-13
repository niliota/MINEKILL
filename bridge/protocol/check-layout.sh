#!/usr/bin/env bash
# Cross-language layout check.
#
# The whole bridge rests on the C# side and the Java side agreeing, byte for byte,
# on where every field lives. If they drift, the two halves read each other's data
# as garbage and the failure looks like "random stutter" or "enemies take no
# damage" rather than an obvious error. So check it mechanically, every build.
#
# Usage: bridge/protocol/check-layout.sh
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
fail=0

echo "== regenerating protocol =="
python3 "$HERE/generate.py" >/dev/null || { echo "generate.py failed"; exit 1; }

echo "== C# layout =="
if ! dotnet run --project "$HERE/layout-check-cs" -c Release -v quiet --nologo \
     > "$TMP/cs.txt" 2>"$TMP/cs.err"; then
  echo "C# layout check failed to run:"; tail -20 "$TMP/cs.err"; exit 1
fi

echo "== Java layout =="
# Protocol.java has no dependencies beyond java.lang, so plain javac is enough.
if ! javac -d "$TMP/classes" \
     "$HERE/layout-check-java/LayoutCheck.java" \
     "$(dirname "$HERE")/../minecraft-mod/src/main/java/dev/minekill/bridge/Protocol.java" \
     2>"$TMP/javac.err"; then
  echo "javac failed:"; tail -20 "$TMP/javac.err"; exit 1
fi
java -cp "$TMP/classes" LayoutCheck > "$TMP/java.txt" 2>&1

echo "== comparing =="
# Compare the parts both languages can state exactly: struct sizes and field offsets.
grep -E '^(STRUCT|CONST)' "$TMP/cs.txt"    | grep -v '^STRUCT:Header' | sort > "$TMP/cs.core"
grep -E '^(STRUCT|CONST)' "$TMP/java.txt" | sort > "$TMP/java.core"

if diff -u "$TMP/cs.core" "$TMP/java.core" > "$TMP/diff.txt"; then
  echo "OK  struct sizes and header constants agree ($(grep -c '^STRUCT' "$TMP/cs.core") structs)"
else
  echo "MISMATCH between C# and Java layout:"
  cat "$TMP/diff.txt"
  fail=1
fi

# Field offsets: compare names only, and report any field the Java side has no
# constant for. Java emits ":?" for size because the wire size lives in the
# generator, not the struct.
grep -E '^FIELD' "$TMP/java.txt" | grep MISSING_CONSTANT && {
  echo "MISMATCH: Java fields without generated offsets"; fail=1; }

# Compare field offsets for the four slot structs. The C# dump also contains the
# header's fields, which have no Java offset constants (both sides use the fixed
# header layout), so only the slots are comparable field by field.
for s in SlotPlayer SlotCamera SlotInput SlotLevel SlotSelection; do
  # Keep name AND offset. C# prints "<name>:<offset>:<size>", Java prints
  # "<name>:<offset>:?"; keeping the first two colon-separated parts gives a
  # comparable "<Struct>.<name>:<offset>" from both. seq/seqEnd are real C#
  # fields but constants on the Java side, so they are compared separately below.
  grep -E "^FIELD:$s\." "$TMP/cs.txt"    | grep -vE '\.(seq|seqEnd):' \
    | awk -F: '{print $1":"$2":"$3}' | sort > "$TMP/cs.$s"
  grep -E "^FIELD:$s\." "$TMP/java.txt" \
    | awk -F: '{print $1":"$2":"$3}' | sort > "$TMP/java.$s"
  if ! diff -q "$TMP/cs.$s" "$TMP/java.$s" >/dev/null; then
    echo "MISMATCH: $s field offsets differ between the two sides"
    diff -u "$TMP/cs.$s" "$TMP/java.$s" | head -20
    fail=1
  fi
done

csfields=$(grep -cE '^FIELD' "$TMP/cs.txt")
javafields=$(grep -cE '^FIELD' "$TMP/java.txt")
echo "     C# field entries: $csfields   Java field entries: $javafields (Java has no header constants, so these differ by design)"

if [ "$fail" -ne 0 ]; then
  echo "LAYOUT CHECK FAILED"
  exit 1
fi
echo "LAYOUT CHECK PASSED"