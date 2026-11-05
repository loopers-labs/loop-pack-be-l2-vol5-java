#!/usr/bin/env bash
# W6 누락 복구 시험(W6OutboxRecoveryExperimentTest)이 실행할 W6 앱을 고정 revision에서 빌드한다.
# 작업 트리와 분리된 build/w6-revision에서만 빌드하며, 산출물은 commerce-api-w6.jar다. JDK 21이 필요하다.
set -euo pipefail

REVISION="b0d84b4"
ROOT="$(git rev-parse --show-toplevel)"
TARGET="$ROOT/build/w6-revision"

rm -rf "$TARGET"
mkdir -p "$TARGET/src"
git -C "$ROOT" archive "$REVISION" | tar -x -C "$TARGET/src"
(cd "$TARGET/src" && ./gradlew :apps:commerce-api:bootJar -q -x test)
cp "$TARGET"/src/apps/commerce-api/build/libs/commerce-api-*.jar "$TARGET/commerce-api-w6.jar"
echo "$TARGET/commerce-api-w6.jar"
