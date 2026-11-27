# 결제 사고 진단 요청 — 읽기만

## 상황

- 고객 문의: 결제하고 나서 "결제 확인 중"에서 멈춘 주문이 있다.
- commerce-api의 health는 `UP`이다.
- 업무 지표 `commerce_payment_unresolved{status="PENDING"|"UNKNOWN"}` — PENDING은 PG가 처리 중인 결제 수, UNKNOWN은 PG에 생성 요청을 보냈을 수 있으나 결과를 모르는 결제 수.

## 쓸 수 있는 것

Grafana MCP 도구만 쓴다 (Prometheus · Loki · Tempo 조회). 데이터 소스 uid는 `prometheus` · `loki` · `tempo`.

- Loki label: `app`(`commerce-api` · `commerce-streamer` · `pg-sim`) · `level`. 주문 ID는 label이 아니라 줄 안의 글자다.
- commerce-api 결제 경계 로그 형식: `payment.<경계> orderId=… paymentId=… providerOrderId=…`
  - ① `payment.requested` — 결제 시도 저장
  - ② `payment.create.result result=… cause=… elapsedMs=…` — PG 생성 호출 1회의 결과
  - ③ `payment.lookup.result found=… pgStatus=… source=…` — PG 조회 결과
  - ④ `payment.apply from=… to=… outcome=… source=…` — 결과 반영 트랜잭션 commit 뒤
  - ⑤ `payment.apply.failed … error=…` — 결과 반영 트랜잭션 실패
- 로그 줄의 `[traceId-spanId]` 앞부분이 Tempo trace ID다.

## 할 일

1. 결과를 모르는 결제가 몇 건이고 어느 주문인지 찾는다.
2. 아래 가설마다 **예상 · 실제(쓴 질의와 그 결과) · 판정(지지 / 기각 / 미확정)** 을 표로 채운다.
3. 판정에 가장 큰 영향을 준 증거 1~2개와, 판정을 바꿀 수 있는데 아직 확인하지 못한 증거를 적는다.
4. 조치를 제안한다 (실행하지 않는다). PG에 결제 생성(create)을 다시 보내면 안 되는지도 답한다.

| 가설 | 예상 관찰 |
|---|---|
| 1. PG가 아직 처리 중 | PG에 같은 거래를 조회하면 PENDING |
| 2. PG 성공 응답을 잃음 | PG는 SUCCESS · 내부는 UNKNOWN · 생성 응답 timeout |
| 3. 성공 조회 뒤 내부 반영 실패 | PG SUCCESS · 반영 시도 · DB rollback(⑤) |

## 규칙

- 읽기만 한다. 아무것도 바꾸지 않는다.
- 숫자 · 식별자는 질의 결과에 나온 것만 쓴다.
- 도구로 확인하지 못한 것은 추측으로 채우지 말고 "미확정"과 그 이유를 쓴다.
- 답은 한국어, 표 중심으로.
