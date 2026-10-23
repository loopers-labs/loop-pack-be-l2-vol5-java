# 학습용 PG 사용법

이 앱은 주문 서비스와 별도로 실행하는 테스트 결제 서버다. 실제 카드나 금전은 사용하지 않는다.

[DB 준비와 실행](../../docker/payment-lab/README.md) · [주문·재고·결제 상태](../../docs/week5/states/total.md)

## 실행과 요청

DB 준비 SQL을 순서대로 적용한 뒤 `./gradlew.bat :apps:test-pg:bootRun`으로 실행한다. 기본 주소는 `http://127.0.0.1:8081`이다.

세 API 모두 `X-USER-ID: 135135` 헤더가 필요하다. 헤더 누락은 400, 다른 값은 403이다. 이는 학습용 제공자 계정이며 고객 인증이 아니다.

```http
POST /api/v1/payments
X-USER-ID: 135135
Content-Type: application/json

{
  "orderId": "LP-ORD-0000000042",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000",
  "amount": 7000,
  "callbackUrl": "http://127.0.0.1:8080/api/v1/payments/callback",
  "testOptions": { "preset": "LOST_SUCCESS" }
}
```

`orderId`는 6~100자 문자열, `amount`는 0 이상의 원화 정수다. 카드는 예시의 합성 값만 허용하고 DB와 로그에 저장하지 않는다. callback 주소는 앱의 `pg.callback-url`과 정확히 같아야 하며 설정 주소도 `127.0.0.1`의 HTTP 주소로 제한한다.

거래 생성 수락은 **202 + PENDING**이다. PG가 요청을 받은 시점의 상태이며 결제 성공을 뜻하지 않는다. 응답 전달이 늦는 동안 거래가 먼저 완료될 수도 있다.

```json
{"transactionKey":"TEST-TX-...","orderId":"LP-ORD-0000000042","amount":7000,"status":"PENDING"}
```

| 조회 | 응답 |
|---|---|
| `GET /api/v1/payments/{transactionKey}` | 200 + 거래 객체. 없는 키는 404 |
| `GET /api/v1/payments?orderId=LP-ORD-0000000042` | 200 + 같은 주문의 거래 배열. 없으면 `[]` |

요청의 `orderId`가 같아도 POST마다 새 거래를 만든다. 응답이 없다는 이유로 POST를 다시 보내면 중복 거래가 생긴다. 생성 후 확인은 조회 API를 사용한다.

## 프리셋과 세부 조건

`testOptions`를 생략하면 `NORMAL_SUCCESS`다. 프리셋으로 시작하고 바꾸고 싶은 필드만 `overrides`에 지정한다. 거래에는 해석을 마친 최종 값이 저장된다.

| 프리셋 | 예정 결과 | 생성 응답 |
|---|---|---|
| `NORMAL_SUCCESS` | 지연 없이 성공 처리 대상 | 정상 |
| `NORMAL_FAILURE` | 지연 없이 실패 처리 대상 | 정상 |
| `PENDING` | 처리 중 유지 | 정상 |
| `DELAYED_SUCCESS` | 3초 뒤 성공 처리 대상 | 4초 지연 |
| `LOST_SUCCESS` | 1초 뒤 성공 처리 대상 | 유실 |
| `LOST_FAILURE` | 1초 뒤 실패 처리 대상 | 유실 |
| `REJECT_BEFORE_CREATE` | 거래를 만들지 않고 거절 | 정확한 422 거절 응답 |

자동 완료는 연결되어 있다. 100ms 주기로 최대 100개의 기한 지난 거래를 찾아 저장된 예정 결과로 종결한다. 처리 지연은 종결 대상이 되는 최소 대기 시간이며, 실제 완료는 polling과 DB 처리 시간만큼 늦어질 수 있다. PG 재시작 뒤에도 같은 DB에서 기한 지난 거래를 찾아 처리한다.

callback은 거래 완료 commit 뒤 별도 실행기에서 한 번 보낸다. 알림 지연·HTTP 오류가 거래 결과를 되돌리거나 다른 거래의 완료를 막지 않는다. 자동 retry와 redirect는 끄고 연결 1초·풀 대기 1초·응답/읽기 대기 2초를 적용한다. 전달에 실패하거나 발송 전에 PG가 종료되면 알림이 유실될 수 있으며, 재시작 후 재발행을 보장하지 않는다. 결과 확인은 거래 조회로도 가능하다.

```json
{
  "preset": "LOST_SUCCESS",
  "overrides": {
    "processingDelayMs": 5000,
    "callbackMode": "DROP"
  }
}
```

위 값은 `testOptions` 안에 넣는다. 5초 뒤 성공 처리 대상으로 만들되 생성 응답과 callback은 보내지 않는 실험이다.

| 세부 필드 | 값 |
|---|---|
| `rejectBeforeCreate` | 거래 생성 전 거절 여부 |
| `finalStatus` | 예정 결과 `PENDING` / `SUCCESS` / `FAILED` |
| `processingDelayMs` | 완료까지의 지연. 0~60000ms |
| `createResponseMode`, `lookupResponseMode` | `NORMAL` 즉시 응답 / `DELAY` 지연 / `DROP` 유실 |
| `createResponseDelayMs`, `lookupResponseDelayMs` | DELAY일 때만 1~30000ms. 다른 모드에서는 0 |
| `callbackMode` | `SEND` 발송 / `DROP` 유실 |
| `callbackDelayMs` | 발송 지연 0~30000ms. DROP이면 0 |

생성·조회 응답 지연은 거래 처리 시간과 별개다. 응답을 기다리는 동안 DB 트랜잭션을 유지하지 않는다. DROP은 최대 30초간 응답을 보류한 뒤 504로 자원을 정리하므로, 클라이언트의 2초 timeout 실험에 사용할 수 있다. 실제 TCP 연결 끊기는 별도 테스트 서버의 검증 범위다.

주문별 조회에서 거래가 여러 건이면, 그중 하나라도 조회 DROP일 때 전체 응답을 보류한다. 그 외에는 가장 긴 조회 지연을 적용한다.

### 함께 바꿔야 하는 조건

지정하지 않은 필드는 프리셋 값을 유지한다. 연관된 필드를 자동 보정하지 않으며, 모순된 조합은 400으로 거절한다.

금액과 지연은 JSON 정수로 전달한다. 소수나 문자열 숫자를 자동 변환하지 않는다. 알 수 없는 필드도 400으로 거절하므로 `presett` 같은 오타가 기본 성공 시나리오로 실행되지 않는다. 오류 응답에는 요청 본문이나 카드 값을 넣지 않는다.

- `DELAYED_SUCCESS`의 생성 응답을 즉시 받으려면 `createResponseMode: "NORMAL"`과 `createResponseDelayMs: 0`을 함께 지정한다.
- `NORMAL_SUCCESS`를 계속 처리 중으로 바꾸려면 `finalStatus: "PENDING"`과 `callbackMode: "DROP"`을 함께 지정한다. 처리 중 유지에는 처리 지연을 예약할 수 없다.
- 생성 전 거절에는 거래 완료·응답 지연·callback을 예약할 수 없다. 거절 프리셋을 그대로 사용하는 것이 가장 간단하다.

## 생성 전 확정 거절

`REJECT_BEFORE_CREATE`는 거래 저장이나 후속 처리 예약 없이 다음 응답을 반환한다.

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/json

{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000042","amount":7000,"transactionCreated":false}
```

다른 4xx·5xx, timeout, 빈 조회는 이 확정 거절과 다르다. 거래가 없다는 근거가 없으면 주문 서비스가 임의로 결제 실패나 재고 복구를 결정하면 안 된다.
