# 5주차 외부 계약 — 테스트 PG와 commerce API

**외부가 정한 사실**만 모은다. 출처는 5주차 실습 문서다. 이 계약은 로컬 실습용 약속이며 실제 PG의 공통 규격이 아니다. 우리 결정과 불변식은 `design.md`에 있고, 여기서는 번호로만 가리킨다.

## 1. 테스트 PG API

테스트 PG는 별도 앱이 아니라 테스트 내부 HTTP 서버나 작은 fixture로 만든다.

| 요청 | 계약 | 응답 |
|---|---|---|
| `POST /api/v1/payments` | `orderId`, `cardType`, `cardNo`, `amount`, `callbackUrl`로 거래를 생성한다 | **202**, `transactionKey`, `orderId`, `amount`, `status` |
| `GET /api/v1/payments/{transactionKey}` | 거래 한 건 조회 | **200**, 없는 키는 **404** |
| `GET /api/v1/payments?orderId={providerOrderId}` | 같은 주문의 거래 목록 | **200**, 거래 객체 배열(빈 배열 가능) |

- 공통 헤더: 세 요청 모두 합성 제공자 계정 `X-USER-ID: 135135`. 이 값은 우리 고객 인증을 대신하지 않는다.
- 외부 상태: `PENDING`, `SUCCESS`, `FAILED`. 생성 수락(PENDING)과 최종 성공(SUCCESS)은 다르다.
- `orderId`는 6자 이상 문자열, `amount`는 원화 정수다.
- **호출자의 멱등 키를 받지 않는다.** 같은 `orderId`로 새 POST를 보내면 중복 제거된다고 가정하지 않는다 → INV-005.

```json
// 생성 요청
{ "orderId": "LP-ORD-0000000042", "cardType": "TEST", "cardNo": "0000-0000-0000-0000",
  "amount": 7000, "callbackUrl": "http://127.0.0.1:8080/api/v1/payments/callback" }

// 생성 수락 (202)
{ "transactionKey": "TEST-TX-0042", "orderId": "LP-ORD-0000000042", "amount": 7000, "status": "PENDING" }
```

## 2. 생성 응답별 매핑

| PG 응답 | 의미 | 결제 시도 | 근거 |
|---|---|---|---|
| 202 + PENDING | 거래가 생겼다 | 처리중 (`transactionKey` 저장) | — |
| 422 + `code: TEST_PG_REJECTED_BEFORE_CREATE` + `transactionCreated: false` + 요청과 같은 `orderId`·`amount` (**모두** 만족) | 거래를 만들지 않았고 앞으로도 만들지 않는다 (효과 0건 확정 거절) | FAILED, 예약 한 번 반환. `transactionKey`를 꾸며 저장하지 않는다 | INV-004 |
| timeout, 본문 없는 422, 다른 code의 4xx·5xx, 깨진 JSON, 식별·금액 불일치 | **효과 0건의 증거가 아니다** | UNKNOWN, 예약 유지, 조회 경로로 확인 | INV-004 |

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/json

{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000042","amount":7000,"transactionCreated":false}
```

조회의 404나 빈 목록은 이 생성 거절과 다르다. 거래가 없다는 확실한 증거가 아니다.

## 3. 조회 결과 해석

| 조회 | 결과 | 처리 |
|---|---|---|
| 단건 (`transactionKey`) | PENDING | 미종결 유지 |
| 단건 | SUCCESS / FAILED (주문·금액·키 일치) | TX B로 반영 |
| 단건 | 404, 조회 실패 | 미종결 유지 |
| 목록 (`providerOrderId`) | 0건 | UNKNOWN 유지 (거래 없음의 영구 증거가 아닐 수 있음) |
| 목록 | 1건 | 그 거래를 검증해 반영 |
| 목록 | 2건 이상 | 자동으로 고르지 않고 충돌로 보존 → D-010 |
| 어느 쪽이든 | 주문·금액·제공자 계정 범위 불일치 | 거절, 충돌 기록, 기존 상태 유지 → INV-003 |

## 4. 식별자

| 식별자 | 누가 만드나 | 예 | 역할 |
|---|---|---|---|
| `orderId` | commerce (DB) | `42` | 내부 주문 |
| `paymentAttemptKey` | 클라이언트가 confirm 때 보냄 | — | 같은 시도의 요청을 식별 → D-015, D-016 |
| `providerOrderId` | adapter | `LP-ORD-0000000042` | PG에 보내는 주문 식별자. 생성과 목록 조회에 같은 값 |
| `transactionKey` | PG | `TEST-TX-0042` | PG 거래 식별자. 생성 수락 응답에서만 알 수 있다 |

## 5. callback

- `POST /api/v1/payments/callback`, 본문은 `providerOrderId`, `transactionKey`, `status`.
- 유실·중복·지연·순서 변경이 모두 가능하다. 테스트 PG에는 재전송 계약이 없다.
- **본문의 상태만으로 주문을 확정하지 않는다.** adapter로 거래를 권위 조회해 제공자 계정 범위·주문·`transactionKey`·금액을 확인한 뒤 TX B로 반영한다.
- callback-first와 lookup-first가 같은 최종 상태로 수렴해야 한다 → INV-002, INV-003.
- 우리 쪽 응답: 수신함에 저장되면 처리 결과와 상관없이 200 → D-020, D-021.

## 6. commerce API

| API | 동작 | 응답 |
|---|---|---|
| `POST /api/v1/orders/{orderId}/confirm` | 본인 DRAFT의 PG 결제 시작. `paymentAttemptKey`와 합성 카드를 받고, 금액은 저장된 주문에서 정한다 | 대기·미확인 **202**, 종결 **200** |
| `GET /api/v1/orders/{orderId}` | 본인 주문·결제수단·시도 상태·최종 결과. **PG를 부르지 않는다** | 200 |
| `POST /api/v1/orders/{orderId}/payment/reconcile` | 본인 미완료 시도의 기존 거래를 조회해 반영. 새 결제를 만들지 않는다 | 대기·미확인 202, 종결 200 |
| `POST /api/v1/payments/callback` | 위 5번 | — |
| `POST /api-admin/v1/orders/{orderId}/payment/reconcile` | (우리가 추가, D-031) 소유권 확인 없이 종결된 시도도 PG에 조회해 반영. 다른 결과면 충돌 기록 | 202 / 200 |
| `POST /api-admin/v1/orders/{orderId}/payment/fail` | (우리가 추가, D-031) 관리자가 PG에 거래가 없음을 확인한 뒤 FAILED 처리. 처리자 기록, 감시 시작(D-013) | 200 |

- 같은 PG 시도의 재요청은 저장된 결과를 돌려주고 추가 효과가 없다.
- 이전 POINT 확정 주문을 다시 확정하면 기존 "이미 확정됨" 오류(409)를 유지한다.
- 소유권, 잘못된 입력, 상충하는 시도는 기존 오류 계약(400/404/409)으로 구분한다.

## 7. 보안·설정 규칙

- 카드 값은 합성 값만 쓰고, **DB·로그에 카드 번호를 남기지 않는다.** 결제 시도에는 금액과 필요한 식별 관계만 저장한다.
- PG base URL과 callback URL은 로컬 설정으로 고정한다. 고객이 외부 주소를 넣게 하지 않는다.
- query는 URI builder로 인코딩한다.
- 생성 HTTP client, 프록시, 회복 라이브러리의 **숨은 재시도를 끈다** → INV-005.
