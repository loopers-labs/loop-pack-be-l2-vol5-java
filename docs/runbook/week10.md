# Runbook: 결제 결과 미확인(health UP)

> **health가 UP이어도 결제는 미확인일 수 있다. 재결제·재시작·DB 수정 전에 증거를 보존하고, 같은 거래를 조회해 같은 주문만 마무리한다.**

대상: commerce-api 결제(W5 PG), 주문 확정 이후 Outbox → streamer 구매 활동 → 주간 랭킹. 로컬 실습 환경 기준이다.

## 1. 증상

| 신호 | 어디서 |
|---|---|
| 고객 주문이 `PAYMENT_PENDING`, 결제 상태 `UNKNOWN`·`PENDING`("결제 확인 중") | 주문 조회 API |
| 알람 "미확인 결제 급증" (`UNKNOWN > 5`, 5분) 또는 "결제 미확인 고객 장기 대기" (최장 대기 > 600초, 1분) | Grafana → Slack |
| `/actuator/health` = `UP` | management 포트 |

health는 설정한 상태 검사만 통과했다는 뜻이다. 이 프로젝트의 응답은 `status`·`groups`만 보이고 특정 주문의 종결은 말하지 않는다. 응답 유실은 WARN 로그라 에러 알람은 울리지 않는다.

## 2. 먼저 보존한다 (아무것도 바꾸기 전에)

1. Prometheus 원문을 파일로 저장한다.
   ```bash
   curl -s localhost:8081/actuator/prometheus | grep '^commerce_payment_unresolved' > incident-$(date -u +%Y%m%dT%H%M%SZ)-prometheus.txt
   ```
2. 미종결 시도 목록을 SELECT로 저장한다(읽기만).
   ```sql
   SELECT a.order_id, a.provider_order_id, a.transaction_key, a.status AS attempt_status, a.anomaly,
          a.created_at, TIMESTAMPDIFF(SECOND, a.created_at, NOW(6)) AS age_seconds, o.status AS order_status
   FROM payment_attempts a JOIN orders o ON o.id = a.order_id
   WHERE a.status IN ('REQUESTED', 'PENDING', 'UNKNOWN')
   ORDER BY a.created_at;
   ```
3. 대상 주문의 경계 로그를 복사한다(api·streamer 같은 필드 이름).
   ```bash
   jq -c 'select(.orderId=="42") | {t: ."@timestamp", app: input_filename, level, event, result, cause, outcome, from, to, traceId, message}' \
     logs/commerce-api.json logs/commerce-streamer.json | jq -s 'sort_by(.t)[]' > incident-order-42.json
   ```
4. 저장한 파일의 `shasum -a 256`을 남긴다. 이후 단계에서 이 파일을 덮어쓰지 않는다.

## 3. 진단: 경쟁 가설을 증거로 가른다

PG SUCCESS + 내부 UNKNOWN 조합만으로는 가설 2와 3을 가를 수 없다. 경계 로그와 TX 결과까지 본다.

| 가설 | 예상 관찰 | 구분 질의 |
|---|---|---|
| PG 처리 지연 | 같은 거래 조회가 `PENDING` | 아래 PG 조회(읽기) |
| PG 성공 응답 유실 | PG `SUCCESS`, `payment.create.result` `result=UNKNOWN` `cause=IO`·`TIMEOUT`, 적용 시도 없음 | `jq 'select(.event=="payment.create.result" and .result=="UNKNOWN")' logs/commerce-api.json` |
| 성공 결과의 내부 반영 실패 | PG `SUCCESS`, `payment.apply.rolled_back`(ERROR, MDC orderId), 시도 미종결·Outbox 없음 | `jq 'select(.event=="payment.apply.rolled_back" or .level=="ERROR")' logs/commerce-api.json` |

PG 거래 조회(읽기 전용, commerce 상태를 바꾸지 않는다):
```bash
curl -s -H 'X-USER-ID: 135135' 'http://127.0.0.1:8082/api/v1/payments?orderId=LP-ORD-0000000042'
```
- 경계 로그가 없거나 서로 모순되면 판정은 **"미확정"**이다. 추측으로 조치 범위를 넓히지 않는다.
- traceId는 한 요청의 흐름만 잇는다. 원 결제 요청과 나중의 reconcile은 다른 trace다. 같은 업무는 orderId·providerOrderId·eventId로 잇는다.

## 4. 조치 전제

- 새 입력을 멈춘다(실험이면 주문 생성 중단). 대상 주문 ID 목록을 고정하고 2장 SELECT 결과에 남긴다.
- **하지 않는다:** create 재전송(재결제), `payment_attempts`·`orders` 직접 UPDATE, 성공 수를 맞추기 위한 강제 CONFIRMED, 프로세스 재시작을 복구로 여기기(외부 거래는 사라지지 않는다).
- 쓰는 경로는 기존 **본인 주문 reconcile API** 하나다. 같은 계정·주문·금액의 단일 거래가 SUCCESS일 때만 기존 결과 적용 경계가 확정한다. 다른 사용자의 주문은 404로 막힌다.

## 5. 조치: 같은 주문만 조정

```bash
curl -s -X POST -H 'X-USER-ID: {주문 소유자}' http://localhost:8080/api/v1/orders/42/payment/reconcile
```
| 응답 | 뜻 | 다음 |
|---|---|---|
| 200, `payment.status=SUCCEEDED`·`orderStatus=CONFIRMED` | 같은 거래의 SUCCESS를 반영 | 6장 확인 |
| 200, `FAILED`·`PAYMENT_FAILED` | 검증한 실패, 재고 1회 반환 | 고객 안내 |
| 202, `PENDING`·`UNKNOWN` 유지 | 아직 결정할 사실이 없음 | 중단 조건 확인 |
| 202, `lookupUnavailable=true` | PG를 묻지 못함(timeout·5xx·회로 열림) | 중단, 재진입 |

### 중단 조건 (상태를 보존하고 멈춘다)
- 조회 실패·회로 열림(`lookupUnavailable`)
- 거래 0건(아직 안 보일 수 있다), 복수 거래(`anomaly=MULTIPLE_TRANSACTIONS`), 금액·주문 불일치(`MISMATCH`), 키 미존재(`TRANSACTION_NOT_FOUND`)
- 계속 `PENDING`
- `payment.apply.rolled_back`가 남음(내부 반영 실패 → 원인 해결 전 반복 호출 금지)

중단한 주문은 UNKNOWN·PENDING 그대로 두고 재고 예약도 유지된다. 임의 성공으로 바꾸지 않는다.

## 6. 복구 확인 (같은 ID 집합으로)

| 확인 | 기대 | 질의 |
|---|---|---|
| 시도·주문 | 대상 전부 `SUCCEEDED`·`CONFIRMED`, UNKNOWN 0 | 2장 SELECT를 `WHERE a.order_id IN (...)`로 |
| PG create | 조치 전후 거래 수·create 증가 0 | PG 조회 결과의 거래 수 |
| 재고 | 결제 시작 때 차감 그대로, 추가 차감 없음 | `SELECT id, stock FROM products WHERE id = ?` |
| 반복 조정 | 같은 주문 Outbox 1행 | `SELECT order_id, COUNT(*) FROM outbox WHERE order_id IN (...) GROUP BY order_id` |
| 후속 | Outbox `PUBLISHED`, 구매 활동 주문·상품당 1행 | `SELECT event_id, status, attempt_count, last_error FROM outbox WHERE order_id IN (...)` / `SELECT order_id, product_id, quantity, confirmed_at FROM product_purchase_activity WHERE order_id IN (...)` |
| 지표 | `commerce_payment_unresolved{status="UNKNOWN"}` 0 (갱신 주기 10s + scrape 5s 이내) | Prometheus |
| 랭킹 | 새 revision snapshot에 반영, 공개된 snapshot은 수정하지 않음 | `--job.name=weeklyRanking periodStart={월요일},java.time.LocalDate revision={다음},java.lang.Long` → `GET /api/v1/rankings/weekly?periodStart=` |

주간 소속은 내부에 처음 저장한 `confirmedAt`이다. 복구 시각이나 PG 요청 시각으로 덮어쓰지 않는다. 확정이 주 경계를 넘었다면 그 주에 들어간다. Outbox 발행 재시도 수는 업무 활동 수가 아니다.

## 7. 재진입

- 중단한 주문은 상태를 보존한 채, 회로가 반열림으로 넘어간 뒤(OPEN 대기 10s) 같은 reconcile을 다시 호출한다. create는 다시 보내지 않는다.
- 같은 주문을 짧은 간격으로 반복 호출하지 않는다. 결과가 바뀌지 않으면 조회 결과·anomaly를 기록하고 사람 판단으로 넘긴다.
- 실패한 조치 기록도 지우지 않는다. 다음 시도는 새 파일에 남긴다.

## 8. 후속(사후 분석에서 정할 개선 후보)

| 빠진 방어 | 검증 가능한 개선 |
|---|---|
| 응답 유실 UNKNOWN을 사람이 reconcile해야 끝남 | UNKNOWN이 N초를 넘으면 같은 거래 조회만 하는 자동 reconcile(한도·중단 조건 동일), 지표 `oldest_age`로 효과 측정 |
| 앱별 로그 파일을 jq로 합쳐 봄 | 로그 집계(Loki 등) 도입 검토 |
| 비동기 로그가 버린 건수를 모름 | 폐기 건수 지표 |
| 알람 임계치가 실험용 예시 | 실제 PG 처리 시간 분포로 한도 재설정 |
