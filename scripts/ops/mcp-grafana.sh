#!/usr/bin/env bash
# Grafana MCP 서버 실행 래퍼(10주차 4b, warning = 사람이 당겨 쓰는 진단).
# 공식 grafana/mcp-grafana의 GitHub 릴리스 단일 바이너리를 쓴다. 공식 배포 형태 중 가장 작다(압축 약 18MB, Docker 이미지 불필요).
# 처음 실행할 때만 build/tools/ 아래로 받고, 아래 고정 SHA-256과 다르면 실행하지 않는다.
# 버전은 공개 2주가 지난 v2.3.0으로 고정한다(최신 v2.4.0은 2026-11-24 공개).
#
# 사용(MCP 클라이언트가 stdio로 띄운다):
#   GRAFANA_SERVICE_ACCOUNT_TOKEN=<Viewer 서비스 계정 토큰> scripts/ops/mcp-grafana.sh
#   Claude Code: claude mcp add grafana --env GRAFANA_SERVICE_ACCOUNT_TOKEN=... -- "$PWD/scripts/ops/mcp-grafana.sh"
#   받기만: scripts/ops/mcp-grafana.sh --install-only
# 토큰은 Grafana 서비스 계정(Viewer) 토큰이다. 환경 변수로만 받고 저장소·출력에 남기지 않는다.
# 쓰기 도구는 끈다(--disable-write). 읽는 도구 범주만 켠다.
# loki 범주는 켜지 않는다. 로그 원문에는 주문 번호 등 내부 식별자가 있어 LLM으로 보내지 않는다(w10s4b-4 ①).
#   --enabled-tools는 범주 단위라 query_loki_patterns만 따로 켤 수 없고, 패턴에 식별자가 남지 않는다는 공식 보장도 없다.
#   주문 번호로 로그 찾기는 사람이 Grafana Explore(Loki)에서 한다. AI는 지표·대시보드·알림으로 큰 틀만 본다.
# 익명 사용 통계는 기본으로 켜져 있어(Grafana Labs로 전송) 끈다(--usage-stats=disabled).
set -euo pipefail

VERSION="2.3.0"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
INSTALL_DIR="$ROOT/build/tools/mcp-grafana-$VERSION"
BIN="$INSTALL_DIR/mcp-grafana"

case "$(uname -s)_$(uname -m)" in
    Darwin_arm64) ASSET="mcp-grafana_Darwin_arm64.tar.gz"; SHA256="f62f4ab218c1778b5e34aa6bd135c8e28be95571c3404c959844bc310100e584" ;;
    Darwin_x86_64) ASSET="mcp-grafana_Darwin_x86_64.tar.gz"; SHA256="544d082c37e3c43154951131119a75a73a700faf2a4244fbc7a129d4a689aeb1" ;;
    Linux_aarch64 | Linux_arm64) ASSET="mcp-grafana_Linux_arm64.tar.gz"; SHA256="33ec8d857df1215bf833e57351990b8654e193a5066350af59735d7d8c040ee9" ;;
    Linux_x86_64) ASSET="mcp-grafana_Linux_x86_64.tar.gz"; SHA256="f808ea13d236e549a9fffa14b28398d30b5393623141307c916e26f5e7746671" ;;
    *) echo "지원하지 않는 OS·CPU: $(uname -s) $(uname -m)" >&2; exit 2 ;;
esac

sha256_of() {
    if command -v shasum >/dev/null 2>&1; then
        shasum -a 256 "$1" | cut -d' ' -f1
    else
        sha256sum "$1" | cut -d' ' -f1
    fi
}

install_binary() {
    local tmp
    tmp="$(mktemp -d)"
    trap 'rm -rf "$tmp"' RETURN
    curl -fsSL -o "$tmp/$ASSET" "https://github.com/grafana/mcp-grafana/releases/download/v$VERSION/$ASSET"
    if [[ "$(sha256_of "$tmp/$ASSET")" != "$SHA256" ]]; then
        echo "SHA-256 불일치: $ASSET (실행하지 않음)" >&2
        exit 1
    fi
    mkdir -p "$tmp/x" "$INSTALL_DIR"
    tar -xzf "$tmp/$ASSET" -C "$tmp/x" mcp-grafana
    mv "$tmp/x/mcp-grafana" "$BIN"
    chmod 0755 "$BIN"
    echo "설치: $BIN ($(du -h "$BIN" | cut -f1))" >&2
}

if [[ ! -x "$BIN" ]]; then
    install_binary
fi

if [[ "${1:-}" == "--install-only" ]]; then
    exit 0
fi

if [[ -z "${GRAFANA_SERVICE_ACCOUNT_TOKEN:-}" ]]; then
    echo "GRAFANA_SERVICE_ACCOUNT_TOKEN(Viewer 서비스 계정 토큰)이 없습니다." >&2
    exit 2
fi

export GRAFANA_URL="${GRAFANA_URL:-http://localhost:3000}"
exec "$BIN" -t stdio --disable-write --usage-stats=disabled \
    --enabled-tools search,datasource,prometheus,dashboard,alerting,navigation "$@"
