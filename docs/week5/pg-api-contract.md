# 테스트 PG API 계약

## 공통

| 항목 | 계약 |
| --- | --- |
| Base URL | `http://127.0.0.1:18089` |
| 요청 헤더 | Toss 승인 API는 `Authorization: Basic ...`; 조회·테스트 제어 API는 `X-USER-ID: 135135` |
| 승인 요청 멱등 키 | `POST /v1/payments/confirm`은 Toss처럼 `Idempotency-Key`를 선택적으로 받음. 같은 키의 재요청은 최초 응답을 재사용하고 300자 초과 키는 `400 INVALID_IDEMPOTENCY_KEY`로 거절 |
| 본문 형식 | JSON. 본문이 있는 요청·응답은 `Content-Type: application/json` |
| 응답 형식 | 승인 API는 Toss Payment 응답 중 합의한 필드만 반환. 조회 API는 거래 객체 또는 배열을 직접 반환 |
| 외부 상태 | `PENDING`, `SUCCESS`, `FAILED`. `UNKNOWN`은 commerce 내부 판단이며 PG 상태가 아님 |
| 주문 식별자 | PG의 `orderId`는 commerce의 `providerOrderId`. 내부 주문 ID와 구분하며 생성·조회에 같은 값 사용 |
| 승인 중복 | 같은 `paymentKey`는 한 번만 승인. 같은 멱등키의 재요청은 최초 응답을 반환하고, 이미 승인된 `paymentKey`를 다른 멱등키로 승인하면 `400 ALREADY_PROCESSED_PAYMENT` |
| 시나리오 선택 | 테스트가 WireMock 설정으로 선택. 고객 요청에 동기·비동기 모드 필드를 추가하지 않음 |

## PG-01. 결제 승인

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /v1/payments/confirm` |
| 헤더 | Basic 인증, 선택 `Idempotency-Key` |
| 입력 | body `paymentKey`, `orderId`, `amount` |
| 성공 | `200`, Toss 응답 중 `paymentKey`, `orderId`, `totalAmount`, `status=DONE`, `approvedAt` 반환 |
| 이미 처리된 결제 | `400 ALREADY_PROCESSED_PAYMENT` |
| 멱등키 길이 초과 | `400 INVALID_IDEMPOTENCY_KEY` |

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `paymentKey` | string | 인증된 결제의 고유 키 |
| `orderId` | string | 6자 이상 PG 주문 식별자 |
| `amount` | integer | 저장된 주문에서 결정한 원화 결제 금액 |

### 거래 객체

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `transactionKey` | string | PG 거래 한 건의 식별자. 같은 거래 조회에서 유지 |
| `orderId` | string | 생성 요청의 PG 주문 식별자 |
| `amount` | integer | 생성 요청의 금액 |
| `status` | string | `PENDING`, `SUCCESS`, `FAILED` |

### 거래 생성 전 확정 거절

| 필드 | 타입 | 값 |
| --- | --- | --- |
| `code` | string | `TEST_PG_REJECTED_BEFORE_CREATE` |
| `orderId` | string | 요청의 PG 주문 식별자 |
| `amount` | integer | 요청 금액 |
| `transactionCreated` | boolean | `false`. 이후에도 해당 요청의 거래를 생성하지 않음 |

## PG-02. 결제 단건 조회

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /v1/payments/{paymentKey}` |
| 입력 | path `paymentKey` |
| 성공 | `200`, 해당 결제의 거래 객체 |
| 없음 | `404`. 오류 본문에 의존하지 않음 |

## PG-03. 주문별 거래 조회

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /v1/payments/orders/{orderId}` |
| 입력 | path `orderId` (`providerOrderId`). URI builder로 인코딩 |
| 성공 | `200`, 거래 객체 배열. 없는 경우 `[]` |

## PG-04. 결제 완료 웹훅

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST {callbackUrl}` |
| 입력 | body `providerOrderId`, `transactionKey`, `status` |
| 완료 알림 상태 | `SUCCESS` 또는 `FAILED` |
| 수신 응답 | [commerce callback 계약](../week2/api-contract.md)에 따름 |
