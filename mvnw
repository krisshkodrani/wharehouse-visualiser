#!/bin/sh
set -eu
PROJECT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
MVN_HOME="$PROJECT_DIR/.mvn/apache-maven-3.9.11"
if [ ! -x "$MVN_HOME/bin/mvn" ]; then
  echo "Downloading Maven 3.9.11..." >&2
  ARCHIVE="${TMPDIR:-/tmp}/apache-maven-3.9.11-bin.zip"
  curl -fsSL "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.11/apache-maven-3.9.11-bin.zip" -o "$ARCHIVE"
  mkdir -p "$PROJECT_DIR/.mvn"
  unzip -q -o "$ARCHIVE" -d "$PROJECT_DIR/.mvn"
fi
exec "$MVN_HOME/bin/mvn" "$@"
