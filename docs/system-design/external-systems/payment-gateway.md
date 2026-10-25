# PG 연동 설계

> 주문 결제: [1. 결제 요청](../use-cases/order-payment-request.md) → **2. PG 연동** → [3. 결과 반영](../use-cases/order-payment-result.md)

로컬 테스트 PG와 Commerce 사이의 송수신 계약과 adapter 규칙을 정리한다.<br>
요청 조율과 트랜잭션은 [결제 요청](../use-cases/order-payment-request.md), 시도·주문·재고 변경은 [결과 반영](../use-cases/order-payment-result.md)이 맡는다.

PG 자체의 독립 실행과 거래 처리·시나리오는 [로컬 테스트 PG](test-payment-gateway.md)에 둔다.

로컬 서버는 정상 생성·조회·자동 처리·웹훅 발송과 확정 실패·생성 전 확정 거절·응답 유실 등의 [시나리오](test-payment-gateway.md#시나리오-제어)를 구현했다.<br>
Commerce는 adapter의 생성·조회와 관찰 결과 판정, 결과 반영(TX B), 결제 요청(confirm), callback 수신, 고객 reconcile과 주기 조회까지 연결했다. 조회 CircuitBreaker, Retry, RateLimiter, TimeLimiter는 단계별 실험 프로필에 구현했다. 기본 프로필에서는 비활성화하며 [조회 회복 정책과 실행 프로필](../use-cases/order-payment-recovery.md#조회-회복-정책), [실험 절차와 관측 자료](../../performance/payment-lookup/README.md)를 따른다.

## 테스트 PG 계약

실습에서 정의한 로컬 HTTP 계약이다.

| 요청 | 계약 |
| --- | --- |
| `POST /api/v1/payments` | 입력 `orderId`, `cardType`, `cardNo`, `amount`, `callbackUrl`. 거래를 만들고 `202`로 `transactionKey`, `orderId`, `amount`, `status` 반환 |
| `GET /api/v1/payments/{transactionKey}` | 거래 한 건을 `200`으로 반환. 없는 키는 `404` |
| `GET /api/v1/payments?orderId={providerOrderId}` | 같은 주문의 거래 배열을 `200`으로 반환. 빈 배열 가능 |
| 공통 헤더 | 세 요청 모두 합성 제공자 계정 `X-USER-ID: 135135` |
| 외부 상태 | `PENDING`, `SUCCESS`, `FAILED` |

- 테스트 PG는 하나만 사용한다. 결제 시도에 PG 구분을 저장하지 않는다.
- 생성 응답의 `PENDING`은 거래 접수이고 최종 성공이 아니다. 최종 결과는 거래 조회로 확인한다.
- 호출자의 멱등 키를 받지 않고, 같은 `orderId`로 다시 보낸 생성 요청의 중복도 막지 않는다. 생성 요청을 다시 보내면 거래가 하나 더 생길 수 있다.
- `orderId`는 영문 대소문자·숫자·`-`·`_`로 이루어진 6자 이상 64자 이하 문자열이다. `amount`는 원화 정수다.
- Commerce는 결제 시도에 저장한 `providerOrderId`를 생성·조회·callback 매칭에 같은 값으로 사용한다. Commerce의 생성 정책은 [결제 요청의 외부 주문 식별자 생성](../use-cases/order-payment-request.md#외부-주문-식별자-생성)을 따른다.

### 생성 요청과 처리 중 응답

생성 요청 본문:

```json
{
  "orderId": "LP-ORD-0000000042-7f3a9c1e",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000",
  "amount": 7000,
  "callbackUrl": "http://127.0.0.1:8080/api/v1/payments/callback"
}
```

`202 Accepted` 응답 본문:

```json
{
  "transactionKey": "TEST-TX-0042",
  "orderId": "LP-ORD-0000000042-7f3a9c1e",
  "amount": 7000,
  "status": "PENDING"
}
```

### 생성 전 확정 거절

로컬 fixture는 거래를 저장하거나 처리를 예약하기 전에 끝나는 거절을 정의한다. 이 요청으로는 나중에도 거래가 생기지 않는다.

```text
HTTP/1.1 422
{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000042-7f3a9c1e","amount":7000,"transactionCreated":false}
```

`422`, 정확한 `code`, `transactionCreated`가 JSON boolean `false`, 요청과 같은 `orderId`·`amount`를 모두 확인한 경우에만 `REJECTED_NO_EFFECT`로 매핑한다. 문자열 `"false"`나 필드 누락을 포함해 하나라도 다르면 `UNKNOWN`이다.

## adapter 관찰 결과

adapter는 HTTP 상태·JSON 형식·요청 값과의 일치를 검사해 관찰을 넘긴다.<br>
저장된 시도와의 비교와 상태 변경은 [결과 반영 서비스](../use-cases/order-payment-result.md#관찰-반영-규칙)가 맡는다.

JSON 본문은 끝까지 검사한다. JSON 값 뒤에 다른 JSON 값이나 공백 이외의 문자가 붙으면 생성은 `UNKNOWN`, 조회는 `UNAVAILABLE`로 처리한다.

### 생성 응답

| 관찰 | 판정 조건 |
| --- | --- |
| `ACCEPTED` | `202`, 요청과 같은 `orderId`·`amount`, `transactionKey`와 `PENDING` |
| `REJECTED_NO_EFFECT` | 생성 전 확정 거절 조건을 모두 만족 |
| `UNKNOWN` | 위 두 관찰에 해당하지 않는 모든 응답. 예: timeout, 연결 유실, 본문 없는 422, 다른 code의 4xx·5xx(테스트 PG의 `400`·`401` 포함), 잘못된 JSON, 요청과 다른 `orderId`·`amount`, `transactionKey`가 없거나 `status`가 `PENDING`이 아닌 202, 202가 아닌 2xx |

### 조회 응답

저장된 `transactionKey`가 있으면 단건 조회, 없으면 `providerOrderId`로 주문 단위 조회한다. 주문 조회 query는 URI builder로 인코딩한다.

| 관찰 | 판정 조건 |
| --- | --- |
| `FOUND` | 단건 `200` 또는 주문 단위 `200`이고 JSON이 계약과 맞음. 거래(`transactionKey`, `orderId`, `amount`, `status`) 목록을 그대로 넘긴다. 빈 배열은 0건 목록이다. |
| `NOT_FOUND` | 단건 조회 `404` |
| `UNAVAILABLE` | timeout, 연결 유실, 그 밖의 4xx·5xx, 잘못된 JSON |

조회의 `404`와 빈 배열은 확정 거절이 아니다. 거래 목록을 성공·실패·충돌로 판정하는 일은 결과 반영 서비스가 한다.

## callback 수신

PG가 결과 알림을 보내는 Commerce의 수신 API다.

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| PG 결과 알림 | `POST` | `/api/v1/payments/callback` | JSON: `providerOrderId`(필수), `transactionKey`, `status` | `200 OK`<br/>알림을 처리함 (아래 응답 규칙) | `400 Bad Request`<br/>본문 형식 오류<br/>`404 Not Found`<br/>해당 시도 없음<br/>`503 Service Unavailable`<br/>PG 조회 불가, 결과 반영 저장 오류<br/>`500 Internal Server Error`<br/>결과 반영 중 정합성 오류, 저장 상태 재조회 실패 |

- 경로는 로컬 설정의 callback URL과 같고, 생성 요청의 `callbackUrl`로 PG에 전달한다. 고객이 바꿀 수 없다.
- PG가 호출하므로 고객 인증(`X-USER-ID`)을 쓰지 않는다.
- 본문은 위조·중복·지연될 수 있으므로 결과로 쓰지 않는다. 시도를 찾을 식별자만 꺼내 결과 반영 유스케이스를 호출한다. 결제 결과는 PG 조회로 확인한다.
- callback의 `providerOrderId`는 생성·조회 계약의 `orderId`와 같은 값이다. `status`는 알림이며 최종 결과의 근거로 쓰지 않는다.

### 응답 규칙

PG는 **10초 안에 받은 `200`만 성공**으로 본다. 그 밖의 응답이나 timeout은 실패이며, 재전송하는 PG는 같은 알림을 다시 보낸다.<br>
응답은 처리 결과를 PG에 알리는 용도이며, 본문은 기존 `ApiResponse` 형식을 사용한다.

| 처리 결과 | 응답 | 이유 |
| --- | --- | --- |
| 결과 반영, 이미 종결된 시도(조회 없이 저장 결과 확인), 중복·늦은 관찰, 충돌 기록, 미확인 유지(`PENDING`, 거래 0건, 단건 `404`) | `200` | 알림을 정상 처리했다. 다시 받아도 판정이 같으며, 남은 미확인은 [주기 조회](../use-cases/order-payment-recovery.md#주기-조회)와 reconcile이 확인한다. |
| PG 조회 불가(`UNAVAILABLE`), 결과 반영 저장 오류로 TX B rollback | `503` | 일시 장애다. 재전송되면 같은 경로로 다시 조회·반영할 수 있다. |
| 결과 반영 중 [정합성 오류](../use-cases/order-payment-result.md#실패-처리), 저장 상태 재조회 실패 | `500` | 정합성 오류는 TX B를 rollback한다. 재조회 실패는 저장된 결과를 응답할 수 없다는 뜻이며, 이미 commit된 결과를 되돌리지 않는다. |
| JSON 형식 오류, `providerOrderId` 누락·[주문번호 규칙](#테스트-pg-계약) 위반 | `400` | 기존 입력 오류 계약을 따른다. 재전송해도 결과가 같다. |
| `providerOrderId`에 해당하는 시도 없음 | `404` | 기존 조회 실패 계약을 따른다. 시도는 PG 호출 전에 저장되므로 정상 PG 알림에서는 생기지 않는다. |

- `transactionKey`·`status`는 결과 판단에 쓰지 않으므로 누락돼도 거절하지 않는다.
- PG 조회와 TX B를 요청 안에서 동기로 처리한다. PG의 10초 수신 예산을 고려해 조회 대기를 제한한다. 기본 응답 timeout은 3초, 전체 정책 실험의 조회 예산은 900ms이며 TX B와 응답 전송 시간은 별도다. 10초 이내 완료를 이 설정만으로 보장하지 않는다. [조회 회복 정책](../use-cases/order-payment-recovery.md#조회-회복-정책)을 따른다.
- 서명 검증 같은 callback 인증은 이번 범위에서 두지 않는다. 위조 알림도 PG 조회와 검증을 통과한 실제 결과의 반영을 유발할 수 있지만, 본문의 `status`만으로 임의의 결제 결과를 반영하지는 못한다.
- 다른 경로와 비교한 응답 변환 방식은 [결제 경로의 응답 변환](../error-mapping.md#결제-경로의-응답-변환)을 따른다.
- 오류 응답이나 timeout만으로 DB 반영 여부를 판단하지 않는다. commit 뒤 재조회·응답 실패와 중복 수신의 처리는 [결과 반영의 실패 처리](../use-cases/order-payment-result.md#실패-처리)를 따른다.
- `200`이 아닌 응답과 timeout은 재전송 대상이다. 형식 오류·시도 없음·정합성 오류는 같은 원인이 남아 있으면 재전송만으로 해결되지 않는다. 현재 테스트 PG는 한 번만 보내므로 일시 장애의 복구는 주기 조회와 reconcile이 맡고, 재전송은 [후속 작업](test-payment-gateway.md#후속-작업)이다.

## 설정과 민감 정보

- base URL, callback URL과 제공자 `X-USER-ID`는 로컬 설정으로 고정한다. 고객이 callback URL이나 외부 주소를 주입할 수 없다.
- 제공자 `X-USER-ID: 135135`는 PG 계정 식별자이고, 고객 API의 `X-USER-ID`와 다르다. 고객 인증을 대신하지 않는다.
- 카드 정보는 확정 요청에서 합성 값(`cardType`, `cardNo`)으로 받아 PG 생성 요청에만 전달한다. 결제 시도·DB·로그에 카드 번호를 남기지 않는다. 로그 레벨과 무관하게 지키도록, 카드 값을 담는 요청·명령 객체는 문자열 표현에서 값을 가린다. HTTP client는 디버그 로그에 요청 본문을 문자열 표현으로 남기므로 PG 생성 요청도 직렬화 전 문자열이 아닌 이 객체로 넘긴다.
- Commerce는 PG의 입력 검사와 같은 조건(공백만이 아닌 필수 문자열)을 재고 확보 전에 검사한다. PG가 입력 오류로 거절한 생성 응답은 `UNKNOWN`이 되어 재고가 묶이므로, PG가 거절할 입력은 Commerce가 먼저 `400`으로 끝낸다. 형식·길이·카드번호 유효성은 PG도 검사하지 않으므로 Commerce도 검사하지 않는다.
- 생성 요청은 HTTP client·프록시·회복 라이브러리의 숨은 재시도를 끈다. 생성 POST는 adapter에서 정확히 한 번 실행된다.
- 생성·조회 모두 연결 1초, 응답 3초를 기본값으로 설정에 둔다. `test`는 응답 1초, 조회 정책 실험 프로필은 응답 500ms를 사용한다. 이 공통 HTTP 설정은 생성에도 적용되지만 Retry와 나머지 조회 정책은 생성에 적용하지 않는다. 정책별 값은 [조회 회복 정책](../use-cases/order-payment-recovery.md#조회-회복-정책)에서 관리한다.

## 검증 기준

[로컬 테스트 PG](test-payment-gateway.md)는 별도 프로세스로 실행한다. Commerce 테스트는 [Testcontainers로 같은 이미지](test-payment-gateway.md#commerce-테스트의-pg-실행)를 시작·종료하고, 시나리오·처리 시점·호출 수는 [시나리오 제어](test-payment-gateway.md#시나리오-제어)로 다룬다.<br>
정상 형식 확인에는 무작위 지연이나 성공 확률을 넣지 않는다.

| 영역 | 상황 | 기대값 |
| --- | --- | --- |
| HTTP 형식 | 실제 adapter → 요청을 캡처하는 작은 HTTP fixture | 메서드·경로·세 요청의 제공자 헤더·JSON 타입·금액·고정 callback URL·거래 키 경로와 주문 ID query 인코딩 및 관찰 결과 매핑이 계약과 일치 |
| 생성 응답 판정 | 확정 거절 / 본문 없는 422·다른 code·`transactionCreated` 누락·문자열 `"false"`·잘못된 JSON·주문 ID·금액 불일치 | `REJECTED_NO_EFFECT` / `UNKNOWN` |
| 숨은 재시도 | 생성 요청 실패 | PG의 호출 수 조회에서 생성 1회 |
| 카드 값 노출 | 결제 요청의 정상·응답 유실·생성 전 거절·반영 실패·입력 오류 경로를 현재 로그 설정과 전체 DEBUG·Spring HTTP TRACE에서 실행 | Commerce 로그·DB·응답 본문과 테스트 PG 로그·거래 조회에 카드 값 없음 |
| callback 응답 | 처리 결과별, 저장 상태 재조회 실패 | 응답 규칙에 맞는 `200`·`503`·`500`·`400`·`404`. HTTP 응답만으로 DB 반영 여부를 단정하지 않음 |

확정 거절·생성 뒤 응답 유실과 TX B rollback·commit 뒤 응답 실패의 업무 효과는 [결과 반영 검증](../use-cases/order-payment-result.md#검증-기준)을 따른다.

## 정하지 않은 것

- 운영 활성화와 임계값 조정, 장기 미확인 알림 등은 [결제 회복의 미정 사항](../use-cases/order-payment-recovery.md#미정-사항)에서 관리한다. 실험 프로필의 timeout, 조회 재시도, 회로, fallback은 이미 구현했다.
