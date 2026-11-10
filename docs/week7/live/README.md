# 7주차 눈으로 보기 — 실제 서비스처럼 띄우고 kafka-ui로 본다

> 테스트가 아니라 **실제 프로세스**로 돈다: Kafka broker 3대 · kafka-ui · pg-sim(가짜 PG) · commerce-api(relay 포함) · commerce-streamer(consumer).
> `application.yml`은 고치지 않고 `config-live.yml`을 덧붙인다. 로컬 MySQL(`docker-mysql-1`)에 DB `loopers_w7`을 새로 만든다.
> `docker/infra-compose.yml`의 kafka-1~3·kafka-ui와 그 볼륨은 건드리지 않는다 (11-06 기준 kafka-3의 로그 디렉터리가 깨져 있어 따로 띄운다).

## 띄우기 · 정리

```bash
bash docs/week7/live/up.sh      # 약 1~2분. 끝나면 주소를 출력한다
bash docs/week7/live/down.sh    # up.sh가 만든 컨테이너·프로세스·DB만 지운다
```

| 무엇 | 주소 |
|---|---|
| **kafka-ui** | http://localhost:9098 |
| commerce-api | 8080 (관리 8081 — 재개 actuator) |
| commerce-streamer | 8085 (관리 8086) |
| pg-sim | 8082 (`/_stats` 호출 수) |
| 앱 로그 | `$TMPDIR/w7-live/api.log` · `streamer.log` |

## 도구

| 명령 | 하는 일 |
|---|---|
| `bash docs/week7/live/order.sh [사용자] [상품:수량 ...]` | 주문 → 확정 요청(카드). 예) `order.sh 7 3:2 1:1`. 사용자 1~1000, 상품 1~10 |
| `bash docs/week7/live/status.sh` | Outbox 상태별 수 · 최근 10행(주문 → Outbox → broker 위치 → 소비 완료 → 구매 활동) · relay 잠금 · consumer lag · PG 호출 수 |
| `bash docs/week7/live/restart.sh {api\|streamer} {stop\|kill\|start}` | 앱 정상 종료 · kill -9 · 다시 띄우기 |
| `docker stop / start w7-kafka-{1,2,3}` | broker 멈추기 · 되살리기 |

## kafka-ui에서 볼 곳

| 메뉴 | 볼 것 |
|---|---|
| **Topics → order-events → Messages** | record 하나 = 발행 한 번. **Key = 주문 ID**, Value = Outbox에 저장한 payload 원문, Partition·Offset. `status.sh`의 `p`·`off`와 같은지 본다 |
| Topics → order-events → Overview | partition 3개의 leader·replica·ISR (broker를 멈추면 ISR이 줄어든다) |
| **Consumers → commerce-streamer.product-purchase-activity** | partition별 committed offset · lag. consumer가 멈추면 lag가 쌓이고, 다시 뜨면 0이 된다 |
| Brokers | broker 3대 상태 |

## 시나리오 (11-06에 Claude가 한 번씩 돌려 확인함)

### S1 정상 — 주문 하나가 끝까지 간다

```bash
bash docs/week7/live/order.sh 7 3:2 1:1
bash docs/week7/live/status.sh
```

- 확인한 결과: Outbox 1행 PUBLISHED(p0 · offset 0) · consumed 1 · 활동 `1x1,3x2` · PG 생성 1 · 조회 1 · callback 1.
- kafka-ui: order-events에 record 1개(Key `1`). consumer lag 0.
- 확정 요청(202) 뒤 1~2초 안에 끝난다: PG callback 1초 + relay tick 1초.

### S2 broker 1대 장애 — 그래도 발행된다 (min.insync 2)

```bash
docker stop w7-kafka-2
bash docs/week7/live/order.sh 10 4:1
bash docs/week7/live/status.sh          # PUBLISHED
docker start w7-kafka-2
```

- 확인한 결과 (주문 4): broker 2를 멈춘 동안 ISR `3,1`(2대)로 PUBLISHED(p1 · offset 0) · consumed 1. 되살리고 15초 뒤 ISR `3,1,2`.
- kafka-ui Overview에서 ISR이 3 → 2로 줄었다가, 되살리면 3으로 돌아온다. 2대를 멈추면 ISR 1 < min.insync 2라 S3처럼 실패할 것이다 (돌려 보지 않음 — 직접 해 볼 것).

### S3 broker 전부 장애 → 재시도 → BLOCKED → 고친 뒤 재개

```bash
docker stop w7-kafka-1 w7-kafka-2 w7-kafka-3
bash docs/week7/live/order.sh 8 5:1
watch -n 5 bash docs/week7/live/status.sh   # 약 2분 동안 att 0 → 1 → 2 → BLOCKED 3
grep OUTBOX_BLOCKED $TMPDIR/w7-live/api.log
docker start w7-kafka-1 w7-kafka-2 w7-kafka-3     # 원인을 고친다. BLOCKED는 저절로 풀리지 않는다
                                                  # ⚠ status.sh에 BLOCKED가 보인 뒤에 되살린다 (약 2분). 그 전에 되살리면 다음 재시도에서 그냥 PUBLISHED
E=$(docker exec docker-mysql-1 mysql -uapplication -papplication -N -e "SELECT event_id FROM order_outbox WHERE status='BLOCKED'" loopers_w7 2>/dev/null)
[ -n "$E" ] && curl -i -X POST localhost:8081/actuator/outbox/$E || echo "BLOCKED 행 없음 — broker를 BLOCKED 전에 되살렸다면 relay가 스스로 보낸다"
```

확인한 결과 (주문 2):

| 시점 | 상태 |
|---|---|
| 주문 뒤 +10초 | SENDING, att 0 (lease 1분) |
| +32초 | 첫 실패 — `Expiring 1 record(s) … 30002 ms` (delivery.timeout.ms 30초). PENDING → 곧 다시 SENDING |
| +70초 | att 2 |
| **+101초** | **BLOCKED, att 3**, `[OUTBOX_BLOCKED]` ERROR 로그 |
| broker 되살린 뒤 | 여전히 BLOCKED (자동으로 풀지 않는다) |
| 재개 `200` | PENDING, att 0, `last_error` 남음 → 다음 tick에 PUBLISHED(p2 · offset 0) → consumed 1 · 활동 `5x1` |
| 한 번 더 재개 | `409` (이미 BLOCKED가 아님) · 없는 eventId `404` |

- 장애 동안 주문·결제는 정상으로 확정됐다. 늦어진 것은 "확정됐다"는 소식(구매 활동)뿐이다.

### S4 consumer 장애 — PUBLISHED지만 소비 완료가 아니다

```bash
bash docs/week7/live/restart.sh streamer kill
bash docs/week7/live/order.sh 9 2:3
bash docs/week7/live/status.sh          # PUBLISHED · consumed 0 · lag 1
bash docs/week7/live/restart.sh streamer start
bash docs/week7/live/status.sh          # 잠시 뒤 consumed 1
```

- 확인한 결과: PUBLISHED(p2 · offset 1)인데 consumed 0, kafka-ui·`status.sh`의 partition 2 lag 1.
- 다시 띄우고 약 25초 뒤 소비 완료. kill -9라 broker가 옛 consumer를 세션 만료 전까지 살아 있다고 보고, rebalance가 늦어진다 (W6 E-8과 같은 현상). `stop`(정상 종료)이면 바로 넘어간다.
- 이것이 `operations.md` O-6이 잡는 상황이다. relay를 재개할 일이 아니다.

### S5 (직접 해 볼 것) relay 강제 종료

```bash
bash docs/week7/live/restart.sh api kill     # 주문 중에 죽여도 확정된 주문의 Outbox 행은 DB에 남는다
bash docs/week7/live/restart.sh api start    # 남은 PENDING·만료된 SENDING을 이어서 보낸다
```

- "ack 뒤 저장 전"의 정확한 순간에 죽이기는 손으로는 어렵다. 그 경우는 `docs/week7/c7/run.sh` 3막(C-6)이 통제된 지점에서 확인했다.
