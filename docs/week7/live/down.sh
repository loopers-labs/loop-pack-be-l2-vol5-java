#!/usr/bin/env bash
# up.sh가 만든 것만 지운다: 앱·pg-sim 프로세스, w7-kafka-1~3 · w7-kafka-ui 컨테이너, w7-live-net, DB loopers_w7.
RUN="${TMPDIR:-/tmp}/w7-live"
for p in api streamer pg-sim; do [ -f "$RUN/$p.pid" ] && kill "$(cat "$RUN/$p.pid")" 2>/dev/null; done
docker rm -f w7-kafka-1 w7-kafka-2 w7-kafka-3 w7-kafka-ui >/dev/null 2>&1
docker network rm w7-live-net >/dev/null 2>&1
docker exec -i docker-mysql-1 mysql -uroot -proot -e "DROP DATABASE IF EXISTS loopers_w7" 2>/dev/null
rm -rf "$RUN"
echo "[live] 정리 끝"
