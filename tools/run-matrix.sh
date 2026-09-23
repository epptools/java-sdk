#!/usr/bin/env bash
#
# Compile once with the newest JDK available, then run the offline suite on every runtime found.
#
# The jar is built with --release 8 so that one artefact serves every Java from 8 upwards. That is a
# claim about six runtimes, and a compiler flag is not evidence for it: --release only promises the
# bytecode version and the API subset, while what actually breaks on an old runtime is a method that
# moved, a default that changed, or a TLS or XML behaviour the JDK altered underneath us. So the
# suite runs on each one.
#
# Usage:
#   tools/run-matrix.sh              # JDKs under $JDK_ROOT (default /c/jdk)
#   JDK_ROOT=/opt/java tools/run-matrix.sh
#
# Runtimes are discovered, not listed, so adding a JDK to the directory extends the matrix. The run
# fails if fewer than MIN_RUNTIMES are found: a matrix that silently shrank to one runtime would
# report success for a range it never exercised.

set -euo pipefail

JDK_ROOT="${JDK_ROOT:-/c/jdk}"
MIN_RUNTIMES="${MIN_RUNTIMES:-4}"
TEST_CLASS="com.epptools.sdk.OfflineTest"

cd "$(dirname "$0")/.."
root=$(pwd)
out="$root/out/matrix"

if [ ! -d "$JDK_ROOT" ]; then
    echo "no JDKs found: $JDK_ROOT does not exist (set JDK_ROOT)" >&2
    exit 2
fi

# One binary per JDK directory. On Windows the shell resolves both `bin/java` and `bin/java.exe` to
# the same file, so globbing for both counts every runtime twice - and a matrix that reports twelve
# runs when it made six is the kind of number nobody checks.
tool_in() {
    for name in "$1/$2.exe" "$1/$2"; do
        if [ -x "$name" ]; then
            echo "$name"
            return 0
        fi
    done
    return 1
}

# The newest javac present compiles the release artefact, which is also what the published jar is
# built with: a library targeting an old release should still be compiled by a current compiler.
newest_javac=""
newest_version=0
for dir in "$JDK_ROOT"/*/bin; do
    candidate=$(tool_in "$dir" javac) || continue
    version=$("$candidate" -version 2>&1 | sed -n 's/^javac \([0-9]*\).*/\1/p')
    [ -n "$version" ] || continue
    if [ "$version" -gt "$newest_version" ]; then
        newest_version=$version
        newest_javac=$candidate
    fi
done

if [ -z "$newest_javac" ]; then
    echo "no javac found under $JDK_ROOT" >&2
    exit 2
fi

echo "== compiling with javac $newest_version, --release 8"
rm -rf "$out"
mkdir -p "$out"
sources=$(find src -name '*.java')
# -Xlint:-options silences only the note that target 8 is obsolete, which is the whole point here.
"$newest_javac" --release 8 -Xlint:-options -d "$out" $sources

echo "== running $TEST_CLASS on every runtime"
found=0
failed=0
for dir in "$JDK_ROOT"/*/bin; do
    candidate=$(tool_in "$dir" java) || continue
    raw=$("$candidate" -version 2>&1 | sed -n 's/.*version "\([^"]*\)".*/\1/p' | head -1)
    [ -n "$raw" ] || continue
    # Java 8 and older report 1.8.0_504, everything since reports 21.0.12 - so the feature number is
    # the second component on 8 and the first everywhere else.
    case "$raw" in
        1.*) version=${raw#1.}; version=${version%%.*} ;;
        *)   version=${raw%%.*} ;;
    esac
    found=$((found + 1))
    printf '   java %-3s ... ' "$version"
    if tail=$("$candidate" -cp "$out" "$TEST_CLASS" 2>&1); then
        echo "$(echo "$tail" | grep -E '[0-9]+ passed' | tail -1)"
    else
        echo "FAILED"
        echo "$tail" | grep -E 'FAIL|Exception|Error' | head -10 | sed 's/^/        /'
        failed=$((failed + 1))
    fi
done

if [ "$found" -lt "$MIN_RUNTIMES" ]; then
    echo "only $found runtimes were exercised, expected at least $MIN_RUNTIMES - the matrix is blind, not passing" >&2
    exit 1
fi

if [ "$failed" -gt 0 ]; then
    echo "$failed of $found runtimes failed" >&2
    exit 1
fi

echo "ok: the suite passes on all $found runtimes"
