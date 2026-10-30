# 5주차 k6 부하 실험 런북 — PG가 느리거나 아플 때

> 상태: **런북만 있다 (미실행).** 체크리스트(C-1~C-9) 밖의 검증이다. 실행하면 결과를 맨 아래 "결과 기록"에 채우고 `plan.md` §6을 고친다.
> 기준: `plan.md` 0-1 부하 가정(피크 100 TPS, Tomcat 200, Hikari 40, 발송 풀 50·대기열 0), §6 회복 설정.

## 0. 무엇을 확인하나

| # | 질문 | 판정 기준 (가설) |
|---|---|---|
| Q1 | PG가 정상일 때 확정 API는 PG를 기다리지 않는가 | 확정 p95가 PG 지연(0.1~0.5s)보다 훨씬 작다 (수십 ms) |
| Q2 | PG가 느려져도 결제와 무관한 API(상품 목록)는 버티는가 | E2 상품 목록 p95가 E1 대비 2배 이내, 오류 0 |
| Q3 | 회로가 느린 PG를 실제로 끊는가 | E2에서 PG 도착 생성 요청이 E2'(회로 사실상 끔)보다 확연히 적다. `UNKNOWN` 수도 적다 |
| Q4 | OPEN 10초가 적절한가 | E3에서 3s·10s·30s 중 "장애 중 PG 생성 호출 수"와 "PG 회복 뒤에도 차단된 결제 수(놓친 매출)"의 균형이 10s가 가장 낫다. 아니면 §6 값을 바꾼다 |

PG는 **로컬 가짜 PG**다 (진짜 PG를 부르지 않는다). 앱·HTTP·회로·Tomcat·Hikari·MySQL은 진짜이고, 가짜 PG는 "느림·실패"라는 자극만 만든다. 그래서 위 Q1~Q4는 믿을 수 있지만 **실제 PG의 처리량·지연 분포는 이 실험으로 알 수 없다** (결과 기록에 한계로 적는다).

## 1. 준비물

| 것 | 상태 | 비고 |
|---|---|---|
| k6 | 설치 필요 | `brew install k6` (터미널에서 직접) |
| 실험 DB `loopers_k6` | ✅ 만들어 둠 | 권한은 `application` 계정. 4주차 `loopers` DB는 건드리지 않는다 |
| `schema.sql` | ✅ | 현재 엔티티 기준 테이블 11개 |
| `seed.sql` | ✅ | 사용자 1,000 · 상품 10(재고 각 100만). **실험마다 먼저 실행** |
| `pg-sim.py` | ⏳ 작성 필요 (§2) | Python 표준 라이브러리만, 127.0.0.1:8082 |
| `config-*.yml` | ⏳ 작성 필요 (§3) | `application.yml`은 고치지 않는다 |
| `payment.js` | ⏳ 작성 필요 (§4) | k6 시나리오 |
| `result.sql` | ⏳ 작성 필요 (§6의 쿼리를 파일로) | |

## 2. 가짜 PG (`pg-sim.py`) 명세

과제 로컬 PG 계약을 그대로 따른다. `LocalTestPgServer`(테스트 fixture)와 같은 계약이다.

| 경로 | 동작 |
|---|---|
| `POST /api/v1/payments` | `X-USER-ID: 135135` 확인 → 거래 키 발급, `PENDING` 저장 → **현재 모드의 지연만큼 기다렸다가** 응답 `{transactionKey, orderId, amount, status: PENDING}` |
| `GET /api/v1/payments/{transactionKey}` | 저장된 거래 (없으면 404) |
| `GET /api/v1/payments?orderId=` | 그 주문의 거래 목록 |
| (자동) 완료 | 접수 후 `complete-after-ms`(기본 1,000ms) 뒤 `SUCCESS`(성공률만큼, 나머지 `FAILED`)로 바꾸고 `callbackUrl`로 `{transactionKey, orderId, status}` POST |
| `POST /_control` | 실험 중 모드 변경: `{"delayMs": 3500, "errorRate": 0, "successRate": 1.0}` |
| `POST /_control/outage` | 장애 구간 예약: `{"afterSec": 15, "durationSec": 30, "delayMs": 3500}` → 15초 뒤 30초 동안만 느려지고 저절로 회복 |
| `GET /_stats` | 생성 요청 도착 수, 조회 수, callback 전송 수 (초 단위 시계열 포함). **"PG가 실제로 받은 요청 수"의 근거** |

- `ThreadingHTTPServer`로 동시 요청을 받는다. 지연은 `time.sleep`이라 요청마다 스레드 하나를 쓴다 (100 TPS × 3.5s ≈ 350 스레드, 로컬에서 문제없다).
- 실행: `python3 -I docs/week5/k6/pg-sim.py` (끝나면 Ctrl+C. **8082 포트가 비었는지 확인**).

## 3. 실험별 앱 설정

모두 `docs/week5/k6/` 아래 파일이고, 실행 때 덧붙인다.

```bash
./gradlew :apps:commerce-api:bootRun --args='--spring.profiles.active=local \
  --spring.config.additional-location=file:docs/week5/k6/config-base.yml,file:docs/week5/k6/config-<변형>.yml'
```

| 파일 | 내용 |
|---|---|
| `config-base.yml` | 공통: `datasource.mysql-jpa.main.jdbc-url: jdbc:mysql://localhost:3306/loopers_k6`, `spring.jpa.show-sql: false` (local 프로필의 SQL 로그가 처리량을 깎는다), `logging.level.org.hibernate.SQL: warn` |
| `config-open10.yml` | 아무것도 바꾸지 않음 (현재 값 OPEN 10s). E1·E2·E3-10s |
| `config-open3.yml` | `resilience4j.circuitbreaker.configs.pg.wait-duration-in-open-state: 3s` |
| `config-open30.yml` | 같은 키 `30s` |
| `config-no-cb.yml` | 회로 사실상 끔: `minimum-number-of-calls: 100000`, `sliding-window-size: 100000` (E2') |

설정이 실제로 먹었는지는 기동 후 `curl -s localhost:8081/actuator/prometheus | grep resilience4j_circuitbreaker_state` 와 로그로 확인한다.

## 4. k6 시나리오 (`payment.js`) 명세

두 시나리오를 동시에 돈다 (`constant-arrival-rate` — 응답이 느려져도 도착률을 유지해야 "몰림"이 재현된다).

| 시나리오 | 도착률 | 한 번의 반복 |
|---|---|---|
| `confirm` | 100/s (`RATE` 환경변수) | ① `POST /api/v1/orders` (`X-USER-ID`: 1~1000 순환, 상품 1~10 중 하나 1개) → ② `POST /api/v1/orders/{id}/confirm` (`Idempotency-Key`: 반복마다 UUID, 본문 `{"cardType":"SAMSUNG","cardNo":"1234-5678-9814-1451"}`) |
| `browse` | 50/s | `GET /api/v1/products?brandId=1&size=20` |

- 기간: E1·E2 60초, E3 90초 (`DURATION`). `preAllocatedVUs` 400, `maxVUs` 800 — 부족하면 k6가 `dropped_iterations`로 알려 준다 (0이어야 유효한 실험).
- 확인 응답은 202(접수·진행 중) 또는 200(이미 종결)이 정상이다. 상태별 카운터를 따로 둔다: `confirm_202`, `confirm_200_failed`, `confirm_5xx`.
- 태그로 p95를 나눠 본다: `http_req_duration{scenario:browse}`, `http_req_duration{name:confirm}`.
- 실행: `k6 run -e RATE=100 -e DURATION=60s --summary-export=docs/week5/k6/results/<실험>.json docs/week5/k6/payment.js`

## 5. 실험 절차

각 실험은 **같은 순서**로 한다. 앞 실험의 결제·회로 상태가 섞이지 않게 앱은 매번 새로 띄운다.

1. 앱이 떠 있으면 내린다.
2. `docker exec -i docker-mysql-1 mysql -uapplication -papplication loopers_k6 < docs/week5/k6/seed.sql`
3. 가짜 PG를 띄우고 모드를 맞춘다 (아래 표).
4. 앱을 해당 설정으로 띄우고 `curl -s localhost:8081/actuator/health` 가 UP인지 본다.
5. **연기 확인 1건**: 주문 1건을 만들어 확정 → 2초 안에 `GET /api/v1/orders/{id}/payment` 가 `CONFIRMED`인지. 아니면 실험하지 않는다 (가짜 PG·callback 경로가 틀린 것).
6. 그 1건을 지우기 위해 `seed.sql`을 다시 실행한다.
7. k6 실행. 동시에 다른 터미널에서 회로 상태를 1초마다 남긴다:
   `while true; do date +%T; curl -s localhost:8081/actuator/prometheus | grep 'resilience4j_circuitbreaker_state{.*pgCreate.*} 1'; sleep 1; done | tee docs/week5/k6/results/<실험>-circuit.log`
8. k6가 끝나면 **90초 기다린다** (reconcile 5초 주기·정리 1분 주기가 따라잡을 시간) → §6 쿼리 → `GET localhost:8082/_stats` 저장.

| 실험 | 앱 설정 | 가짜 PG 모드 | 기간 |
|---|---|---|---|
| E1 기준선 | open10 | `delayMs` 100~500 무작위, 성공률 1.0 | 60s |
| E2 PG 느림 | open10 | `delayMs` 3500 (생성 timeout 3s 초과) 내내 | 60s |
| E2' 회로 없음 | no-cb | E2와 같음 | 60s |
| E3-3 | open3 | 정상으로 시작 → `outage {afterSec:15, durationSec:30, delayMs:3500}` | 90s |
| E3-10 | open10 | 같음 | 90s |
| E3-30 | open30 | 같음 | 90s |

## 6. 결과 쿼리 (`result.sql`로 만들 것)

```sql
-- 결제 상태·실패 사유 분포
SELECT status, failure_reason, COUNT(*) FROM payments GROUP BY status, failure_reason ORDER BY 3 DESC;
-- 주문 상태 분포 (결제 상태와 짝이 맞아야 한다)
SELECT status, COUNT(*) FROM orders GROUP BY status;
-- 재고 정합성: 소비된 예약 합 = 판매량, 남은 예약(RESERVED)은 아직 미종결 결제 수와 같아야 한다
SELECT status, COUNT(*), SUM(quantity) FROM stock_reservations GROUP BY status;
SELECT 10000000 - SUM(stock) AS sold_or_reserved FROM products;   -- = CONSUMED + RESERVED 수량 합
-- 초 단위 결과 (E3: PG 회복 뒤에도 NOT_SENT가 이어진 구간 = 회로 OPEN 때문에 놓친 결제)
SELECT DATE_FORMAT(requested_at, '%H:%i:%s') AS sec, status, failure_reason, COUNT(*)
FROM payments GROUP BY sec, status, failure_reason ORDER BY sec;
```

## 7. 지표와 판정

| 지표 | 어디서 | 쓰는 질문 |
|---|---|---|
| 확정 p95 · 상품 목록 p95 · 오류율 | k6 요약 (`--summary-export`) | Q1·Q2 |
| PG가 받은 생성 요청 수 (초별) | `GET :8082/_stats` | Q3·Q4 (장애 중 PG를 두드린 양) |
| 결제 상태·사유 분포 | §6 쿼리 | Q3 (`UNKNOWN` 수), Q4 |
| 회로 상태 전이 시각 | 회로 로그 | Q4 (OPEN 시작·HALF_OPEN·CLOSED) |
| **놓친 매출** | PG 회복 시각(15s+30s) 이후 `FAILED(NOT_SENT)` 건수 | Q4 |
| 재고 정합성 | §6 쿼리 | 모든 실험: 어긋나면 실험보다 버그가 먼저다 |
| `dropped_iterations` | k6 요약 | 0이 아니면 그 실험은 무효 (부하 발생기가 모자랐다) |

E3 판정: 가로축 OPEN 시간(3/10/30), 세로축 두 개("장애 30초 동안 PG 생성 호출 수", "회복 뒤 놓친 결제 수")를 표로 놓고 고른다. 10s가 아니면 §6 값·변경 이력·이 문서를 함께 고친다.

## 8. 정리 (C-9 유지)

- 앱·가짜 PG(8082)·회로 로그 루프를 모두 종료한다: `lsof -i :8080 -i :8081 -i :8082` 가 비어야 한다.
- `loopers_k6` DB는 남겨도 되지만, 지울 때는 `DROP DATABASE loopers_k6;` (root).
- `results/`의 JSON·로그는 커밋할지 결정한다 (요약 표만 이 문서에 남기고 원본은 `.gitignore`도 가능).

## 결과 기록

| 실험 | 확정 p95 | 목록 p95 | PG 생성 도착 | SUCCESS | FAILED(NOT_SENT) | UNKNOWN→? | 회로 OPEN 구간 | 놓친 결제 | dropped |
|---|---|---|---|---|---|---|---|---|---|
| E1 | | | | | | | | | |
| E2 | | | | | | | | | |
| E2' | | | | | | | | | |
| E3-3 | | | | | | | | | |
| E3-10 | | | | | | | | | |
| E3-30 | | | | | | | | | |

한계: 가짜 PG라 실제 PG의 지연 분포·처리량·호출 한도는 반영하지 않는다. 단일 앱 인스턴스·로컬 MySQL 기준이다.
