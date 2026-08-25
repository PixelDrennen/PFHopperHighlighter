#!/usr/bin/env bash
set -euo pipefail

GRADLE_VERSION="9.5.0"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$ROOT/.gradle-local"
GRADLE_HOME="$TOOLS/gradle-$GRADLE_VERSION"

if ! command -v java >/dev/null 2>&1; then
  echo "Java 25 is required, but 'java' was not found." >&2
  exit 1
fi

JAVA_VERSION="$(java -version 2>&1 | head -n1)"
if [[ "$JAVA_VERSION" != *'"25'* ]]; then
  echo "Java 25 is required for Minecraft 26.1.2." >&2
  echo "Current runtime: $JAVA_VERSION" >&2
  echo "Point JAVA_HOME/PATH at a JDK 25 installation and run this script again." >&2
  exit 1
fi

if [[ ! -x "$GRADLE_HOME/bin/gradle" ]]; then
  mkdir -p "$TOOLS"
  ZIP="$TOOLS/gradle-$GRADLE_VERSION-bin.zip"
  echo "Downloading Gradle $GRADLE_VERSION..."
  curl -fL "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip" -o "$ZIP"
  unzip -q -o "$ZIP" -d "$TOOLS"
fi

cd "$ROOT"
exec "$GRADLE_HOME/bin/gradle" --no-daemon clean build
