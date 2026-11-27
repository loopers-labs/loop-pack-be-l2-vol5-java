# Runbook: "결제 확인 중"이 끝나지 않는 PG 결제 (health 는 UP)

10주차 설계(`docs/week10/design.md`)의 사고를 운영자가 다시 판단하기 위한 절차다. 명령 모음이 아니라 **무엇을 먼저 보존하고, 어떤 증거로 원인을 가르고, 언제 멈추는지**를 적는다.

> 이 runbook 에는 DB 에 상태를 직접 쓰는 명령과 PG create 를 다시 보내는 명령이 없다. 둘 다 하지 않는다.
> UNKNOWN 은 "거래가 없다"가 아니라 "우리가 결과를 모른다"이다. create 를 다시 보내면 중복 결제가 될 수 있다.

## 0. 이 runbook 을 쓰는 때

| 증상 | 확인 |
|---|---|
| 고객 주문이 `PAYMENT_PENDING` · 결제 상태 `UNKNOWN`/`PENDING`/`REQUESTED` 로 오래 남음 | 주문 상세 API `paymentStatus` |
| `commerce_payment_unresolved{status="UNKNOWN"}` 가 0 보다 큰 채로 줄지 않음 | Prometheus |
| 그런데 `/actuator/health` 는 `UP` | management 포트(127.0.0.1) |

health 는 "프로세스와 설정한 의존성이 응답한다"까지만 말한다. 특정 주문이 끝났는지는 말하지 않는다. health 가 UP 이라고 이 절차를 건너뛰지 않는다.

## 1. 시작: 영향 범위를 고정한다

1. **새 실험 · 테스트 주문 입력을 멈춘다.** 진단 중에 대상이 늘면 같은 집합을 비교할 수 없다. (운영이라면 영향 기능의 신규 결제를 막을지 별도로 판단한다. 이 runbook 범위 밖)
2. **대상 주문 목록을 고정한다.** 한 번 뽑은 목록을 끝까지 쓴다.

```sql
select p.order_id, p.attempt_key, p.provider_order_id, p.status, p.reservation_status,
       p.transaction_key, p.conflict_reason, p.updated_at
from payment p
where p.status in ('REQUESTED', 'UNKNOWN', 'PENDING')
order by p.updated_at;
```

3. 지표로 규모를 본다. 인스턴스마다 같은 DB 값을 내므로 `sum` 이 아니라 `max` 로 모은다.

```promql
max by (status) (commerce_payment_unresolved)
time() - max(commerce_payment_unresolved_refreshed)   # 지표가 몇 초 전 값인지. 갱신 주기(기본 15초)보다 크게 벌어지면 지표 자체를 먼저 의심
```

## 2. 진단: 보존 → 가설 → 가르는 질의

### 2.1 먼저 보존한다 (조치 전에)

| 보존할 것 | 이유 |
|---|---|
| 1장의 대상 주문 행(결제 · 주문 · 재고 · Outbox) | 복구가 상태를 바꾸므로, 바뀌기 전 사실은 지금만 남길 수 있다 |
| 대상 `providerOrderId` 를 담은 결제 경계 로그 | 원인을 가르는 근거. 아래 2.3 |
| Prometheus `commerce_payment_unresolved` 값과 시각 | 복구 전후 비교 |
| health 응답 | "UP 이었다"는 사실 자체가 기록 대상 |

보존한 파일은 hash(SHA-256)를 함께 남기고, 이후 단계에서 덮어쓰지 않는다. hash 는 파일이 바뀌지 않았음만 보여 준다. 내용이 참인지는 보장하지 않는다.

### 2.2 가설

| 가설 | 예상 관찰 |
|---|---|
| H1. PG 처리 자체가 지연 | 진단 조회의 거래 상태가 `PENDING` |
| H2. PG 성공 응답 유실 | 거래 1건 `SUCCESS`, 내부 `UNKNOWN`, `PG 생성 결과 … result=UNKNOWN cause=…` 로그 |
| H3. 성공 결과의 내부 반영 실패 | 거래 `SUCCESS`, 조회는 있었고 `결제 결과 적용 실패 …` 로그(TX rollback) |

**`PG SUCCESS` + 내부 `UNKNOWN` 만으로는 H2 와 H3 을 가르지 않는다.** 둘 다 같은 조합을 만든다. 아래 로그까지 봐야 한다. 확인할 수 없는 항목이 있으면 판정은 "미확정"이다.

### 2.3 가르는 질의 · 로그

주문 하나(`providerOrderId=LP-ORD-…`)로 로그를 이어 읽는다. 같은 업무는 traceId 가 아니라 `orderId` · `providerOrderId` 로 잇는다. reconcile 은 다른 요청이라 traceId 가 다르다.

| 로그 (`key=value`) | 무엇을 말하나 |
|---|---|
| `PG 생성 결과. providerOrderId=… result=UNKNOWN cause=ResourceAccessException(EOFException)` | create 응답을 받지 못했다. 원인 예외 종류가 남는다 → H2 근거 |
| `결제 결과 적용. … source=CREATE before=REQUESTED after=UNKNOWN effect=NONE` | 그 결과로 UNKNOWN 이 되었고 TX 가 commit 됐다 |
| `PG 조회 결과. providerOrderId=… result=FOUND count=1 transactions=[키:SUCCESS:금액]` | 조회가 있었다. 없으면 아무도 확인하지 않은 것 |
| `결제 결과 적용 실패. … source=LOOKUP error=…` | 조회 결과를 반영하다 TX 가 rollback 됐다 → H3 근거 |

판정 예 (10주차 실험 `20261127T010929Z-c154f28f`):

- H1 **반박**: 진단 조회 3건 모두 `SUCCESS`.
- H2 **지지**: 거래 1건 SUCCESS · 내부 UNKNOWN · `result=UNKNOWN` 로그가 정확히 그 3건.
- H3 **반박**: 장애 동안 그 주문의 조회 0회 · `source=LOOKUP` 적용 로그 0 · 적용 실패 로그 0. 셋을 함께 볼 때만 반박한다.

### 2.4 진단 조회는 읽기만 한다

- PG 쪽 사실은 **같은 계정 · 같은 `providerOrderId` 의 주문 단위 조회**로 본다(W5 D-66). 거래 키 단건 조회는 두 번째 거래를 보지 못한다.
- reconcile API 는 결과를 **적용**하므로 진단에 쓰지 않는다.
- 진단 조회 전후로 1장의 질의 결과가 같은지 확인한다. 다르면 누군가 동시에 바꾸고 있는 것이니 멈춘다.
- AI 에게 로그 원문과 가설을 주고 비교를 맡길 수 있다. 다만 상태를 바꾸는 조치는 사람이 범위 · 중단 조건을 확인한 뒤 한다.

## 3. 조치 전제

| 전제 | 확인 |
|---|---|
| 대상 주문 목록이 1장에서 고정됨 | 목록 밖 주문은 이번 조치 대상이 아님 |
| 주문마다 2.4 의 진단 조회 결과가 아래 "진행 조건"을 만족 | 하나라도 아니면 그 주문은 4장 중단 |
| 조치 수단은 본인 주문 reconcile(`POST /api/v1/orders/{orderId}/payment/reconcile`)뿐 | create 재발송 · DB 직접 수정 · 강제 CONFIRMED 없음 |

**진행 조건**: 거래가 정확히 1건, 상태 `SUCCESS`, `providerOrderId` 일치, 금액이 `payment.amount` 와 같음.

## 4. 중단 조건

아래면 그 주문은 **reconcile 하지 않고 상태를 그대로 둔 채** 기록하고 다음 조회 시각을 정한다. 시간이 지났다는 이유로 성공 · 실패 처리하거나 예약을 반환하지 않는다.

| 관찰 | 뜻 | 남길 것 |
|---|---|---|
| 조회 0건 | PG 반영 지연일 수도, 정말 발송이 안 됐을 수도 있다. 구분할 수 없다 | 조회 시각, 다음 조회 시각 |
| 거래 2건 이상 | 중복 결제 의심. 자동으로 고르지 않는다 | 거래 키 · 상태 · 금액 전부 |
| 금액 · 주문 불일치 | 다른 주문의 거래거나 계약 위반 | 관찰한 값 |
| 계속 `PENDING` | PG 가 처리 중. 우리 쪽 조치 대상이 아님 | 처음 본 시각, 경과 |
| 조회 불가(timeout · 5xx · 회로 OPEN) | 판단 근거 없음 | 실패 원인 |
| 진단 조회 전후 commerce 상태가 다름 | 동시 변경 중 | 두 결과 |

## 5. 복구 확인

reconcile 뒤 **같은 ID 목록**을 새로 조회해 아래를 모두 본다. 주문 상태만 보고 끝내지 않는다.

| 항목 | 기대 |
|---|---|
| 대상 주문 | 주문 `CONFIRMED`, 결제 `SUCCESS`, 예약 `CONSUMED`, 거래 키가 PG 거래와 같음 |
| PG create 수 | 복구 전과 같음 (추가 0) |
| PG 거래 | 주문마다 1건 그대로 |
| 재고 | 복구 전과 같음 (성공 적용 때 추가 차감 없음) |
| 포인트 · 쿠폰 | 변화 없음 (PG 결제 복구와 무관) |
| Outbox | 대상 주문마다 1행. reconcile 을 다시 불러도 늘지 않음 |
| 구매 활동 | relay → Kafka → streamer 뒤 주문마다 1행 |
| 지표 | `commerce_payment_unresolved{status="UNKNOWN"}` 가 갱신 주기 안에 줄어듦 |

- 메시지 발행 시도 수(Outbox `attempt_count`)는 재시도로 늘 수 있다. 업무 활동 수와 같은 수로 세지 않는다.
- 복구 주문의 `confirmedAt` 은 reconcile 시각이다. 처음 확정된 시각이므로 그 시각이 속한 주의 랭킹에 들어간다. PG 요청 시각으로 바꾸지 않는다.
- 이미 공개된 주간 랭킹은 저절로 바뀌지 않는다. 반영하려면 그 기간으로 batch 를 다시 실행해 새 revision 을 공개한다(W9 D-191). 먼저 `--weekly-ranking.command=check` 로 늦은 구매 수를 본다.

## 6. 재진입

복구 중에 멈추거나 실패해도 처음부터 다시 하면 된다. 단, 아래를 지킨다.

- 1장의 목록을 다시 뽑지 말고 **보존한 목록**을 쓴다. 새로 생긴 미확인은 별도 사건으로 다룬다.
- 이미 종결된 주문에 reconcile 을 다시 부르면 PG 를 조회하지 않고 저장 상태만 돌려준다. 다시 불러도 안전하다.
- 남은 주문만 2.4 진단부터 다시 한다. 이전 진단 결과를 재사용하지 않는다(그 사이 PG 상태가 바뀌었을 수 있다).
- 보존한 fault 기록을 덮어쓰지 않는다. 새 시도는 새 기록 위치에 남긴다. 실패한 시도의 기록도 지우지 않는다.

## 7. 후속 (사후 분석)

개인이 아니라 실패 조건과 빠진 방어를 적는다. "모니터링 강화"처럼 확인할 수 없는 문장은 쓰지 않는다.

| 빠진 방어 | 확인할 수 있는 개선 |
|---|---|
| 미확인이 쌓여도 사람이 주문 화면을 볼 때까지 모름 | `commerce_payment_unresolved{status="UNKNOWN"}` 가 N 분 넘게 0 보다 크면 이 runbook 으로 연결하는 알림. N 은 실제 PG 응답 · 사용자 대기 측정으로 정함 |
| 개수만 있고 나이가 없음 | 가장 오래된 미종결 시도의 나이 지표 |
| 진단 조회를 운영자가 직접 할 경로가 없음 (실험은 테스트에서 adapter 를 직접 부름) | 권한 · 감사 기록이 있는 읽기 전용 관리자 진단 API |
| 수동 reconcile 만 있음 | 위 진행 조건 · 중단 조건을 그대로 쓰는 자동 조회 (create 재발송 없음) |

## 부록: 10주차 실험에서 확인한 범위

- 로컬 통제 사고 1종(H2: 거래 저장 뒤 생성 응답 유실)과 같은 주문 복구, 후속 Outbox · 구매 활동 · 주간 랭킹. `CheckoutIncidentTest`.
- 확인하지 않은 것: 운영 배포, 실제 PG · 계정, 부하, H3 의 실제 발생 경로(W5 `OrderConfirmTransactionTest` 가 TX B 실패를 따로 확인함), DB · broker 장애.
