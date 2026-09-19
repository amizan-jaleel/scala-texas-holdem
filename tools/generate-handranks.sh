#!/usr/bin/env bash
#
# Generates src/main/resources/HandRanks.dat, the Two Plus Two hand-rank lookup table.
#
# Evaluator reads this 130 MB table as a classpath resource. It used to be stored in Git LFS,
# but LFS is disabled on the repository, so it is generated locally instead and kept out of
# git. The generator needs only a JDK 11 or newer, and checks its own output against the
# sha256 of the canonical table before reporting success.
#
# The sbt build runs this generator automatically before compiling; use this script when you
# want the table without going through sbt.
#
# Usage: tools/generate-handranks.sh [output-file] [--force]

set -euo pipefail

expected_bytes=129951336
script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(dirname "$script_dir")"
generator="$script_dir/HandRankTableGenerator.java"

output="$repo_root/src/main/resources/HandRanks.dat"
force=0
for arg in "$@"; do
  case "$arg" in
    --force) force=1 ;;
    *) output="$arg" ;;
  esac
done

file_size() {
  wc -c < "$1" | tr -d '[:space:]'
}

if [[ $force -eq 0 && -f "$output" && "$(file_size "$output")" == "$expected_bytes" ]]; then
  echo "HandRanks.dat is already in place at $output"
  echo "Pass --force to regenerate it."
  exit 0
fi

# Prefer JAVA_HOME, then java on PATH, then the JDKs IntelliJ keeps in ~/.jdks.
java_bin=""
if [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/java" ]]; then
  java_bin="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1; then
  java_bin="$(command -v java)"
else
  # Running a .java file straight from source needs the compiler, so skip any runtime that
  # ships without javac.
  for candidate in "$HOME"/.jdks/*/bin/java "$HOME"/.jdks/*/bin/java.exe; do
    candidate_bin="$(dirname "$candidate")"
    if [[ -x "$candidate" ]] && { [[ -x "$candidate_bin/javac" ]] || [[ -x "$candidate_bin/javac.exe" ]]; }; then
      java_bin="$candidate"
      break
    fi
  done
fi

if [[ -z "$java_bin" ]]; then
  echo "No JDK found. Set JAVA_HOME, or put java on PATH (JDK 11 or newer is required)." >&2
  exit 1
fi

echo "Using $java_bin"
echo "Generating $output ..."
"$java_bin" -Xmx1500m "$generator" "$output"
