# 6주차 실험 키트 — 전체 흐름 실행 · E-6 `max.block.ms`

> 상태: 키트는 2026-10-29 작은 규모(callback 21건, broker 정상)로 끝까지 도는 것을 확인했다. **E-6 본 측정은 아직 안 했다 — 학습자가 돌린다.**
> `application.yml`은 고치지 않는다. 실험 설정은 모두 이 폴더의 yml을 `--spring.config.additional-location`으로 덧붙인다.

## 파일

| 파일 | 내용 |
|---|---|
| `pg-sim.py` | 가짜 PG (127.0.0.1:8082, 표준 라이브러리만). `auto` = 1초 뒤 SUCCESS + callback, `hold` = PENDING으로 둠 |
| `config-base.yml` | 실험 DB `loopers_w6`, SQL 로그 끔, `ddl-auto: validate`, Tomcat 지표 켬. api·streamer 공통 |
| `config-maxblock-{60s,2s,1s}.yml` | producer `max.block.ms`만 바꾼다 |
| `schema-extra.sql` · `seed.sql` | 스타터 `example` 테이블 · W6 테이블 비우기 (W5 seed 뒤에 붙인다) |
| `prepare.js` | 결제 대기 주문 N건 만들기 (주문 → 확정 요청 202) |
| `callback.js` | callback을 일정 도착률로 보낸다 + 1초마다 Tomcat busy 스레드·Hikari 연결을 기록한다 |
| `result.sql` | 주문·결제·발행 기록·소비 결과 집계 |

## 0. 처음 한 번 — 실험 DB

```bash
docker compose -f docker/infra-compose.yml up -d mysql redis-master redis-readonly kafka-1 kafka-2 kafka-3 kafka-ui
docker exec -i docker-mysql-1 mysql -uroot -proot -e "CREATE DATABASE IF NOT EXISTS loopers_w6 DEFAULT CHARACTER SET utf8mb4; GRANT ALL PRIVILEGES ON loopers_w6.* TO 'application'@'%';"
cat docs/week5/k6/schema.sql docs/week6/schema.sql docs/week6/k6/schema-extra.sql | docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_w6
```

## 1. 매 실행 전 — 같은 출발점

```bash
cat docs/week5/k6/seed.sql docs/week6/k6/seed.sql | docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_w6
python3 -I docs/week6/k6/pg-sim.py          # 터미널 1. 다시 띄우면 거래 목록이 비워진다
```

앱 실행 (저장소 루트에서, 경로는 절대 경로):

```bash
K=$PWD/docs/week6/k6
./gradlew :apps:commerce-api:bootRun --args="--spring.profiles.active=local --spring.config.additional-location=file:$K/config-base.yml,file:$K/config-maxblock-1s.yml"   # 터미널 2 (8080 · 관리 8081)
./gradlew :apps:commerce-streamer:bootRun --args="--spring.profiles.active=local --spring.config.additional-location=file:$K/config-base.yml"                           # 터미널 3 (8085)
```

설정이 먹었는지: api 로그의 `ProducerConfig values:`에서 `max.block.ms = …`를 확인한다 (첫 send 때 찍힌다).

## 2. 전체 흐름 한 번 (C-1 · C-9) — 2026-10-29 실행함, 결과는 `../incident-w6.md`

pg-sim `auto` 모드에서 주문 하나를 만들고 확정한다. `http/` 요청이나 curl:

```bash
curl -s -XPOST localhost:8080/api/v1/orders -H 'Content-Type: application/json' -H 'X-USER-ID: 7' -d '{"items":[{"productId":3,"quantity":2},{"productId":1,"quantity":1}]}'
curl -s -XPOST localhost:8080/api/v1/orders/{id}/confirm -H 'Content-Type: application/json' -H 'X-USER-ID: 7' -H 'Idempotency-Key: any-1' -d '{"cardType":"SAMSUNG","cardNo":"1234-5678-9814-1451"}'
```

확인: `result.sql`, kafka-ui(`localhost:9099`), committed offset:

```bash
docker exec kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server kafka-1:9092 --describe --group commerce-streamer.product-purchase-activity
```

## 3. E-6 — broker가 모두 죽었을 때 callback 스레드는 얼마나 막히나

### 먼저 알아야 할 것 — `send()`가 막히는 때는 정해져 있다

`KafkaProducer.send()`가 호출 스레드를 `max.block.ms`까지 붙잡는 것은 **그 topic의 metadata가 없을 때**(또는 버퍼 `buffer.memory`가 찼을 때)다.
metadata가 이미 있으면 record를 버퍼에 넣고 바로 돌아오고, 실패는 나중에(`delivery.timeout.ms`, 기본 120s) send 콜백으로 온다. 그래서 장면을 둘로 나눈다.

| 장면 | 순서 | 무엇을 보나 |
|---|---|---|
| **A** 운영 중 장애 | 앱이 broker 정상일 때 몇 건 발행(warm-up) → broker 3대 정지 → 본 부하 | callback이 막히는가, 실패 기록은 언제 어떤 모양으로 남는가 |
| **B** 장애 중 기동 | broker 3대 정지 → api 재시작 → 본 부하 | `max.block.ms` 60s · 2s · 1s별 busy 스레드 · p99 · 오류 |

AFTER_COMMIT listener는 callback 요청 스레드에서 돈다. 이 단계에서는 원래 TX의 DB 연결이 아직 반환되기 전이다. 그래서 Hikari(최대 40) 지표도 함께 본다.

### 절차 (장면·값마다 1 → 3 반복)

```bash
# ① 준비 — pg-sim을 hold로, 대기 주문 N = RATE × 초 (100 TPS × 60s = 6000)
curl -XPOST localhost:8082/_control -d '{"mode":"hold"}'
k6 run -e COUNT=6000 -e RATE=100 docs/week6/k6/prepare.js
curl -XPOST localhost:8082/_control/complete-all            # PG 쪽 거래를 SUCCESS로 (callback 없음)

# ② 장면 만들기
#   A: k6 run -e RATE=10 -e DURATION=3s -e LABEL=warmup docs/week6/k6/callback.js   (warm-up 30건 — producer가 metadata를 받는다)
#      docker compose -f docker/infra-compose.yml stop kafka-1 kafka-2 kafka-3
#   B: docker compose -f docker/infra-compose.yml stop kafka-1 kafka-2 kafka-3
#      api 재시작 (config-maxblock-60s / 2s / 1s 중 하나) — 기동 때 topic 생성(KafkaAdmin)이 broker를 못 찾아 오류 로그를 남기고 계속 뜬다

# ③ 본 부하
k6 run -e RATE=100 -e DURATION=60s -e LABEL=B-1s --summary-export=docs/week6/k6/results/B-1s.json docs/week6/k6/callback.js
docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_w6 < docs/week6/k6/result.sql

# 끝나면 broker를 다시 띄운다
docker compose -f docker/infra-compose.yml start kafka-1 kafka-2 kafka-3
```

- 장면 A는 본 부하 뒤에 **2분 이상 더 기다렸다가** `result.sql`을 다시 본다 — 버퍼에 들어간 record가 `delivery.timeout.ms` 뒤에 실패로 돌아오는지.
- k6 출력의 `[probe] t=…s busy=… hikariActive=… hikariPending=…` 줄이 초 단위 시계열이다. 표에는 최대값과 포화까지 걸린 시간을 적는다.
- `dropped_iterations`가 0이 아니면 k6 VU가 모자란 것이다 (도착률이 유지되지 않음).

### 예측 → 측정 → 차이 (학습자가 채운다)

| 장면 | `max.block.ms` | 예측: busy 스레드 | 예측: callback p99 | 측정: busy 최대 · Hikari active 최대 | 측정: p99 · 오류 | `published_at` null / `last_error` | 차이와 이유 |
|---|---|---|---|---|---|---|---|
| A | 1s | | | | | | |
| B | 60s | | | | | | |
| B | 2s | | | | | | |
| B | 1s | | | | | | |

## 한계

- PG는 가짜다. 앱·Tomcat·Hikari·MySQL·Kafka client는 진짜다. broker 정지는 `docker stop`(정상 종료)이다.
- 노트북 한 대에서 k6·앱·broker가 CPU를 나눠 쓴다. 절대값보다 **값 사이의 관계**(포화되나, 몇 배인가)를 본다.
