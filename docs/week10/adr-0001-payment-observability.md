# ADR-0001 · 어떤 주문을 미확인으로 셀까?

채택 · 2026-11-26. **응답 기한을 넘기고도 우리 DB가 결제 결과를 모르는 주문**을 센다. 미확인 건수가 늘면 운영자는 로그에서 해당 주문을 찾고, PG(결제 대행사) 거래를 조회해 원인을 좁힌다. 수집할 때마다 DB를 읽던 초기안은 [ADR-0002](adr-0002-payment-metric-refresh.md)에서 주기 집계로 바뀌었다.

## 주문 한 건을 세는 기준

`payment_attempts`에서 세 조건을 **모두** 만족할 때 주문 한 건을 더한다. 주문당 결제 시도 행은 한 개다. [집계 쿼리](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/query/pay/QueryDslPaymentMetricQueryDao.java) · [주문별 유일 제약](../../docker/payment-lab/003-payment-attempts.sql)

| 조건 | 이유 |
|---|---|
| 발송 상태가 `STARTED` | Commerce가 PG 요청을 보내는 단계에 들어갔다. PG가 요청을 받았는지는 거래 조회로 확인한다. |
| 결제 시도 결과가 `UNKNOWN` | 우리 DB에 결제 결과가 없다. PG의 처리 중 응답을 받은 `PENDING`은 제외한다. |
| 저장된 `response_deadline`이 관찰 시각보다 앞선다 | 정상 대기 중인 주문을 제외한다. 기한과 정확히 같은 시각에는 0건, 그 시각이 지나면 1건이다. |

최초 PG 호출 직전에 응답 대기 설정(현재 2초)으로 기한을 한 번 기록한다. 주문 접수 시각과 발송 시각은 다를 수 있다. 발송한 `UNKNOWN`에 기한이 하나라도 빠져 있으면 전체 건수는 **확인 불가**다. 기존 DB의 결제 시도에는 기한 컬럼과 인덱스가 필요하다. [기한 기록](../../apps/commerce-api/src/main/java/com/loopers/application/ordering/facade/ConfirmOrderFacade.java) · [응답 대기 설정](../../apps/commerce-api/src/main/java/com/loopers/application/pay/gateway/PaymentProviderGateway.java) · [스키마 변경](../../docker/payment-lab/019-payment-response-deadline.sql) · [DB 경계값 테스트](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/query/pay/PaymentMetricQueryDaoIntegrationTest.java)

## 로그로 이어볼 경계

실행별 로그에서 `runId`와 `orderId`로 찾고, 같은 결제 시도인지 `attemptKey`로 확인한다. DB의 `providerOrderId`는 PG에 전달한 주문번호다. PG가 거래를 만들면 `transactionKey`로 그 거래를 특정한다. [결제 로그 구현](../../apps/commerce-api/src/main/java/com/loopers/application/pay/logging/PaymentAttemptLog.java)

| 같은 주문에서 읽을 기록 | 뜻 |
|---|---|
| `PG_CREATE`의 `result` | 최초 호출이 어떻게 끝났는지 보여준다. `TIMEOUT`은 우리 서버가 응답을 기다리다 기한을 넘겼다는 뜻이다. |
| `DB_OBSERVATION`의 `result`와 `outcome` | `COMMITTED / UNKNOWN`이면 관찰 처리는 커밋됐고 결제 결과는 여전히 미확인이다. `UNCONFIRMED`이면 새 DB 조회로 커밋 여부부터 확인한다. |

주문·거래 ID는 로그에 남긴다. 이를 지표 라벨에 넣으면 주문이 늘 때마다 별도 시계열이 생긴다. 실행 로그에서 카드 정보·인증 헤더·결제 요청 본문은 제외한다. 복구가 별도 요청으로 들어와도 주문 ID는 이어진다. 이번 실험은 이 ID들을 사용했고, Trace ID·Span ID를 수집하는 분산 추적은 후속 학습 범위다. [사고 사례](README.md) · [주문별 조회 절차](../runbook/week10.md)
