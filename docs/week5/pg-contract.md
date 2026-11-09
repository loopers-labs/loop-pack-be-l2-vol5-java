# 테스트 PG 계약과 결제 adapter 명세 — Week 5

> 상태: **구현 완료 (2026-10-23)**. 초안은 2026-10-20에 쓰고 같은 날 검증했으며(13절), 구현하며 달라진 점은 13-6절과 design.md 9-12절에 있다. 이 문서는 PG와 맞닿는 경계만 다룬다. 결제 시도·재고 예약 테이블, 주문 상태 전이, TX A/B, confirm·reconcile·callback API는 [`design.md`](../week2/design.md) 9절에 이어서 쓴다.

## 0. 범위

| 정하는 것 | 정하지 않는 것 (design.md 9절) |
|---|---|
| 로컬 테스트 PG의 HTTP 계약 (요청·응답·오류·알림) | 결제 시도·예약의 저장 구조와 유일 제약 |
| commerce가 PG를 부르는 경계 (domain port ↔ infrastructure adapter) | 주문 `DRAFT → PAYMENT_PENDING → CONFIRMED / PAYMENT_FAILED` 전이 |
| PG 응답을 **관찰 결과**로 바꾸는 규칙 | 관찰 결과를 주문·재고에 한 번만 적용하는 규칙 (`applyObservation`) |
| timeout·재조회·회로 차단기 설정 | confirm·reconcile·callback의 HTTP 계약 |
| 테스트 PG fixture와 계약 테스트 | 업무 시나리오 통합 테스트 |

## 1. 결정 요약

| 결정 | 이유 |
|---|---|
| PG 계약은 **과제가 정의한 로컬 계약 그대로** 구현한다 | 과제 체크리스트와 검증 장면(`X-USER-ID: 135135`, `transactionKey`, 202 PENDING, 422 `TEST_PG_REJECTED_BEFORE_CREATE`)이 이 계약을 전제로 한다 |
| PG는 **테스트 코드 안의 작은 HTTP 서버**로 만든다 (별도 앱·관리 화면 없음) | 과제가 "별도 PG 제품을 만들지 않는다"고 정했다. 테스트가 거래 수·create 횟수·상태를 직접 관찰·통제해야 한다 |
| PG 호출은 domain의 **port 인터페이스** 뒤에 숨기고, HTTP·JSON·timeout은 infrastructure adapter만 안다 | 주문 유스케이스가 네트워크 오류와 JSON 형식을 모르게 한다. 기존 ArchUnit 3규칙(`domain`↛나머지, `application`↛`infrastructure`)을 그대로 지킨다 |
| adapter는 예외 대신 **관찰 결과 타입**을 돌려준다 | "실패"와 "모름"을 호출자가 예외 종류로 추측하지 않게 한다. 모르는 경우를 타입으로 강제해 빠뜨리지 않게 한다 |
| create는 **어떤 층에서도 재시도하지 않는다** | 테스트 PG는 호출자 멱등 키를 받지 않고, 같은 orderId의 새 POST를 중복 제거하지 않는다. 재시도는 곧 두 번째 거래다 |
| 회로 차단기·재조회는 **조회 경로에만** 둔다 | 조회는 다시 해도 외부 효과가 없다. 과제가 회로 차단기를 "PG 조회 한 경로"에 적용하라고 정했다 |

**버린 대안: 토스페이먼츠 결제 흐름을 본뜨기**

| 토스의 특징 | 이 과제와 충돌하는 점 |
|---|---|
| 결제창(SDK)에서 카드사 인증 → `successUrl`로 `paymentKey·orderId·amount` 리다이렉트 → 서버가 승인 API 호출 | 프론트엔드 결제창과 리다이렉트가 필요하다. 과제는 서버가 합성 카드 값으로 PG에 직접 요청하는 계약이다 |
| POST API에 `Idempotency-Key` 헤더 지원 (15일 유효, 같은 키는 같은 응답) | 응답을 잃어도 같은 키로 재시도하면 되므로, 이번 주의 핵심 문제(멱등 보장이 없는 생성 요청의 결과 미확인)가 사라진다 |
| 시크릿 키 기반 인증, 외부 샌드박스 | 과제는 외부 PG 계정·실제 비밀정보 사용을 금지한다. 외부 샌드박스로는 "거래 생성 뒤 응답만 유실" 같은 장면을 결정적으로 재현할 수 없다 |

토스 같은 실제 PG로 바꾸는 일은 2절의 port 뒤에 adapter를 하나 더 두는 일로 남긴다. 실제 PG의 멱등 키를 이 계약에 섞지 않는다.

## 2. 구성 요소와 의존 방향

```
application  OrderFacade / PaymentFacade (design.md 9절)
     │ 사용
     ▼
domain       payment.PaymentGateway (port) + 관찰 결과 타입 (PgCreateResult, PgLookupResult, PgTransaction)
     ▲ 구현
     │
infrastructure  payment.TestPgPaymentGateway — RestClient + JdkClientHttpRequestFactory + Resilience4j
     │ HTTP (127.0.0.1)
     ▼
test         support.pg.TestPgServer — JDK HttpServer, 임의 포트, 메모리 거래 저장소
```

| 위치 | 클래스 (예정) | 책임 | 모르는 것 |
|---|---|---|---|
| `domain.payment` | `PaymentGateway`, `PgPaymentRequest`, `PgCreateResult`, `PgLookupResult`, `PgTransaction`, `PgTransactionStatus`, `ProviderOrderId` | PG에 무엇을 묻고 어떤 관찰을 받는지에 대한 약속 | HTTP, JSON, timeout, Resilience4j |
| `infrastructure.payment` | `TestPgPaymentGateway`, `PgProperties`, `PgClientConfig` | HTTP 요청 구성, 응답 형식 검증과 결과 매핑, timeout·재조회·회로 | 주문·재고·결제 시도의 상태 |
| `test ... support.pg` | `TestPgServer` | 로컬 계약을 따르는 거래 저장·조회, 실패 장면 통제, 호출 관찰 | commerce 내부 구조 |

## 3. HTTP 계약

이 응답 규칙은 테스트 adapter와 fixture가 공유하는 **실습용 약속**이다. 실제 PG의 규격이라고 주장하지 않는다.

### 3-1. 공통

| 항목 | 규칙 |
|---|---|
| base URL | 설정으로 고정 (`payment.pg.base-url`). 테스트는 fixture가 고른 `http://127.0.0.1:{임의 포트}`를 주입한다. 고객 입력으로 바꿀 수 없다 |
| 공통 헤더 | 세 요청 모두 `X-USER-ID: 135135` (합성 제공자 계정, 설정값). 요청 본문이 있으면 `Content-Type: application/json`, 모두 `Accept: application/json` |
| `orderId` | PG가 보는 주문 번호 = 우리 `providerOrderId`. 6자 이상 문자열. 내부 주문 42 → `LP-ORD-0000000042` (`LP-ORD-` + 10자리 0 채움). 생성·조회·알림에 같은 문자열을 쓴다 |
| `amount` | 원화 정수 (JSON number, 소수점 없음, 0보다 큼) |
| `transactionKey` | PG가 만드는 거래 식별자 (fixture: `TEST-TX-` + 4자리 이상 일련번호) |
| `status` | `PENDING` · `SUCCESS` · `FAILED` 중 하나. 그 밖의 값은 계약 위반 |
| 계정 범위 | fixture는 거래마다 생성 요청의 `X-USER-ID`를 저장하고, 조회는 같은 계정의 거래만 돌려준다. 다른 계정의 거래는 없는 거래와 같다 |
| 멱등 | PG는 호출자 멱등 키를 받지 않는다. 같은 `orderId`의 POST가 다시 오면 **새 거래를 만든다** |

### 3-2. 거래 생성 — `POST /api/v1/payments`

```http
POST /api/v1/payments
X-USER-ID: 135135
Content-Type: application/json

{
  "orderId": "LP-ORD-0000000042",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000",
  "amount": 7000,
  "callbackUrl": "http://127.0.0.1:8080/api/v1/payments/callback"
}
```

| 응답 | 본문 | 의미 |
|---|---|---|
| **202** | `{"transactionKey":"TEST-TX-0001","orderId":"LP-ORD-0000000042","amount":7000,"status":"PENDING"}` | 거래를 만들고 접수했다. **결제 성공이 아니다** |
| **422** | `{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000042","amount":7000,"transactionCreated":false}` | 거래를 저장하거나 처리를 예약하기 **전에** 거절했고, 나중에도 이 요청의 거래를 만들지 않는다 (fixture가 통제할 때만 발생) |
| 400 | `{"code":"TEST_PG_INVALID_REQUEST"}` | 필수 필드 누락·형식 오류. fixture는 다음을 모두 만족해야 받는다: `X-USER-ID`가 있음, 본문이 JSON 객체, `orderId`가 6자 이상 문자열, `cardType`·`cardNo`·`callbackUrl`이 비어 있지 않은 문자열, `amount`가 0보다 큰 정수 number |
| 그 밖 | fixture 통제로 만든 임의 응답 (5xx, 본문 없음, 잘못된 JSON, 연결 끊김, 응답 지연) | 4-1절 매핑 대상 |

### 3-3. 거래 단건 조회 — `GET /api/v1/payments/{transactionKey}`

| 응답 | 본문 |
|---|---|
| 200 | `{"transactionKey":"TEST-TX-0001","orderId":"LP-ORD-0000000042","amount":7000,"status":"SUCCESS"}` |
| 404 | `{"code":"TEST_PG_NOT_FOUND"}` — 없는 거래, 또는 다른 계정의 거래 |
| 400 | `{"code":"TEST_PG_INVALID_REQUEST"}` — `X-USER-ID` 없음 (3-4절 주문별 조회도 같고, `orderId` 파라미터가 없을 때도 400) |

계약 밖의 경로·메서드에는 404 `TEST_PG_NOT_FOUND`로 응답한다.

### 3-4. 주문별 거래 조회 — `GET /api/v1/payments?orderId={providerOrderId}`

| 응답 | 본문 |
|---|---|
| 200 | 3-3절 거래 객체의 배열. 거래가 없으면 `[]` (404가 아니다) |

### 3-5. 결과 알림 (callback) — PG → `POST {callbackUrl}`

```json
{ "transactionKey": "TEST-TX-0001", "orderId": "LP-ORD-0000000042", "status": "SUCCESS" }
```

- `orderId`는 다른 PG 메시지와 같은 이름을 쓰며 값은 `providerOrderId`다. 과제 문장이 알림 본문을 "providerOrderId, transactionKey, status를 전달"한다고 설명하므로, commerce의 callback 엔드포인트는 `providerOrderId` 이름으로 와도 같은 값으로 받는다 (`@JsonAlias`, `PaymentV1DtoTest`). fixture의 `callbackPayload`는 `orderId`로 만든다.
- 알림은 **확인 요청의 계기**일 뿐이다. 본문의 `status`로 주문을 바꾸지 않고, commerce는 `transactionKey`로 3-3절을 다시 조회해 확인한다 (design.md 9절).
- 알림은 유실·중복·지연·순서 변경이 가능하다고 가정한다. fixture는 자동으로 보내지 않고, 테스트가 원하는 시점에 원하는 횟수만큼 만든다 (8-2절).

## 4. adapter 결과 모델 (domain port)

```java
package com.loopers.domain.payment;

public interface PaymentGateway {
    PgCreateResult requestPayment(PgPaymentRequest request);   // create — 재시도 없음
    PgLookupResult findTransaction(String transactionKey);      // 단건 조회 — 0건 또는 1건
    PgLookupResult findTransactionsByOrder(String providerOrderId); // 주문별 조회 — 0건 이상
}

// toString은 cardNo를 가린다 (record 기본 toString이 카드번호를 찍지 않게 재정의)
public record PgPaymentRequest(String providerOrderId, String cardType, String cardNo, long amount) { }

public sealed interface PgCreateResult {
    record Accepted(String transactionKey) implements PgCreateResult { }   // 거래 존재 확인. 결과는 아직 모름
    record RejectedNoEffect() implements PgCreateResult { }               // 효과 0건이 계약으로 보장됨
    record Unknown(UnknownReason reason) implements PgCreateResult { }    // 거래가 생겼는지 모름
}

public sealed interface PgLookupResult {
    record Transactions(List<PgTransaction> transactions) implements PgLookupResult { } // 형식이 맞는 응답 (0건 포함)
    record Unavailable(UnavailableReason reason) implements PgLookupResult { }        // 확인하지 못함
}

public record PgTransaction(String transactionKey, String providerOrderId, long amount, PgTransactionStatus status) { }
public enum PgTransactionStatus { PENDING, SUCCESS, FAILED }
```

| 이유코드 | 값 |
|---|---|
| `UnknownReason` | `TIMEOUT`, `CONNECTION_LOST`, `UNEXPECTED_STATUS`, `INVALID_BODY`, `ECHO_MISMATCH` |
| `UnavailableReason` | `TIMEOUT`, `CONNECTION_LOST`, `SERVER_ERROR`, `UNEXPECTED_STATUS`, `INVALID_BODY`, `CIRCUIT_OPEN` |

이유코드는 운영 기록용이다. design.md 9절의 상태 판단은 이유코드에 따라 갈리지 않는다. 모든 `Unknown`과 `Unavailable`은 같은 방식으로 다룬다 (예약 유지, 재발송 없음).

### 4-1. 생성 응답 매핑

| PG 응답 | 결과 | 비고 |
|---|---|---|
| 202 + 4-4절 형식을 만족하는 거래 객체 + 본문 `orderId`·`amount`가 요청과 같음 | `Accepted(transactionKey)` | 본문의 `status`는 형식만 검사하고 판단에는 쓰지 않는다. 최종 결과는 조회로만 확인한다 |
| 422 + JSON + `code == "TEST_PG_REJECTED_BEFORE_CREATE"` + `transactionCreated`가 boolean `false` + `orderId`·`amount`가 요청과 같음 | `RejectedNoEffect` | **네 조건을 모두** 만족할 때만. `transactionKey`를 꾸며 만들지 않는다 |
| read timeout | `Unknown(TIMEOUT)` | 요청이 도착해 거래가 생겼을 수 있다 |
| 응답 전 연결 끊김, 연결 실패 | `Unknown(CONNECTION_LOST)` | 연결 실패도 보수적으로 UNKNOWN에 둔다 (11절 열린 결정 1) |
| 본문 없는 422, `code`가 다른 422, `transactionCreated`가 `true`·문자열 `"false"`·누락 | `Unknown(UNEXPECTED_STATUS)` | 효과 0건의 증거가 아니다 |
| 400 `TEST_PG_INVALID_REQUEST`, 그 밖의 4xx·5xx, 3xx | `Unknown(UNEXPECTED_STATUS)` | 우리 요청 버그라도 "거래 없음"이라는 보장은 없다 |
| 202·422인데 JSON이 깨졌거나 필드 타입이 다름 (예: `"amount":"7000"`) | `Unknown(INVALID_BODY)` | 4-4절 |
| 202·422인데 `orderId`·`amount`가 요청과 다름 | `Unknown(ECHO_MISMATCH)` | 다른 거래의 응답일 수 있다 |

### 4-2. 조회 응답 매핑

| PG 응답 | 결과 | 재조회 | 회로 실패로 기록 |
|---|---|---|---|
| 단건 200 + 형식이 맞는 거래 | `Transactions([tx])` | 안 함 | 성공 |
| 단건 404 | `Transactions([])` | 안 함 | 성공 (업무 결과) |
| 주문별 200 + 배열 (0건·1건·여러 건) | `Transactions([...])` | 안 함 | 성공 |
| read timeout | `Unavailable(TIMEOUT)` | 함 | 실패 |
| 연결 실패·끊김 | `Unavailable(CONNECTION_LOST)` | 함 | 실패 |
| 5xx | `Unavailable(SERVER_ERROR)` | 함 | 실패 |
| 200인데 JSON이 깨짐, 필수 필드 누락·타입 다름, `status`가 세 값 밖, 주문별 조회가 배열이 아님 | `Unavailable(INVALID_BODY)` | 함 | 실패 |
| 404 외의 4xx | `Unavailable(UNEXPECTED_STATUS)` | 안 함 | **기록하지 않음** (우리 요청 문제를 PG 장애로 세지 않는다) |
| 회로 OPEN으로 호출 안 함 | `Unavailable(CIRCUIT_OPEN)` | 안 함 | — |

### 4-3. adapter가 하지 않는 검증

adapter는 **형식**(상태 코드, JSON 구조와 타입, 생성 응답이 요청을 그대로 돌려주는지)만 검증한다. 조회한 거래가 **이 결제 시도의 거래인지**는 도메인이 저장된 시도와 대조한다 (design.md 9절).

- `transactionKey`가 시도에 저장된 값과 같은가 (저장된 값이 있을 때)
- `orderId`가 시도의 `providerOrderId`와 같은가
- `amount`가 시도에 저장된 금액과 같은가
- 주문별 조회가 2건 이상인가 → 첫 거래를 고르지 않고 충돌로 남긴다

**제공자 계정 범위**는 응답 본문에 계정이 없어 도메인이 대조할 수 없다. 대신 adapter가 고정 계정(`X-USER-ID: 135135`)으로만 조회하고, PG는 그 계정의 거래만 돌려준다는 계약(3-1절)으로 보장한다. 이 계약은 9절 표 #11이 확인한다.

### 4-4. 응답 본문 해석 규칙

응답 본문은 **문자열로 받아 `JsonNode`로 읽고, 필드마다 타입을 직접 검사**한다. 응답을 record·DTO로 바로 바인딩하지 않는다.

| 필드 | 통과 조건 |
|---|---|
| `transactionKey`, `orderId`, `code` | 문자열(`isTextual`)이고 비어 있지 않음 |
| `amount` | 정수 number(`isIntegralNumber`), `long` 범위, 0보다 큼 |
| `status` | 문자열이고 `PENDING`·`SUCCESS`·`FAILED` 중 하나 |
| `transactionCreated` | boolean(`isBoolean`) |
| 주문별 조회 본문 | 배열(`isArray`)이고 원소가 모두 위 거래 객체 형식 |

정해 둔 필드 밖의 필드는 무시한다.

**타입 바인딩을 쓰지 않는 이유** (13절 실험 E6): Jackson은 기본값에서 문자열을 숫자·boolean으로 바꿔 읽는다. 이 프로젝트의 공용 `ObjectMapper`(`supports/jackson`의 `JacksonConfig`)는 `ACCEPT_SINGLE_VALUE_AS_ARRAY`까지 켜 둔다. 게다가 결과가 **어느 `ObjectMapper`를 쓰느냐에 따라 달라진다.** Spring Boot가 주입하는 `RestClient.Builder`는 Boot의 메시지 컨버터를 거쳐 공용 설정을 따르고, `RestClient.builder()`로 직접 만들면 Spring의 기본 Jackson 설정(`Jackson2ObjectMapperBuilder` 기본값)을 따른다. 아래 표의 "기본 매퍼"가 이것이다.

| 응답 | 공용 매퍼로 record 바인딩 | 기본 매퍼로 record 바인딩 | 결과적으로 생기는 일 |
|---|---|---|---|
| `"transactionCreated":"false"` (문자열) | `false` | `false` | 4-1절이 UNKNOWN으로 두라고 한 응답이 `RejectedNoEffect`가 되어 예약을 반환한다 |
| `transactionCreated` 누락 | 오류 (`FAIL_ON_NULL_FOR_PRIMITIVES`) | 기본값 `false` | 기본 매퍼에서는 위와 같다 |
| `"amount":"7000"` (문자열) | `7000` | `7000` | 형식 위반을 정상으로 받는다 |
| 주문별 조회가 배열이 아닌 객체 하나 | 원소 1개짜리 목록 | 오류 | 공용 매퍼에서는 형식 위반을 정상 1건으로 받는다 |

"효과 0건"을 잘못 판정하면, PG에 거래가 남아 있을 수 있는데 재고를 돌려주게 된다. `JsonNode` 타입 검사는 매퍼 설정과 상관없이 같은 결과를 낸다.

## 5. 시간·재시도·회로

### 5-1. Timeout

| 항목 | 값 (기본 / 테스트) | 이유 |
|---|---|---|
| 연결 수립 | 500ms / 500ms | 로컬 고정 주소라 연결이 오래 걸릴 이유가 없다 |
| create 응답 대기 | 3s / 500ms | create timeout은 UNKNOWN을 만들고, 사람이 reconcile해야 하는 비싼 결과다. 그래서 조회보다 길게 기다린다 |
| 조회 응답 대기 | 1s / 300ms | 조회는 다시 해도 안전하므로 짧게 끊고 재조회한다 |

- timeout은 `JdkClientHttpRequestFactory`(실제로 쓰는 request factory)에 설정한다. 연결 수립은 하위 `java.net.http.HttpClient`의 `connectTimeout`, 응답 대기는 factory의 `readTimeout`이다.
- create와 조회는 응답 대기 시간이 달라 **RestClient를 두 개** 만든다 (같은 base URL·헤더, factory만 다름).
- timeout은 우리 쪽 대기를 끝낼 뿐 PG 거래를 취소하지 않는다.

### 5-2. 숨은 재시도 제거

아래 "실험" 열은 13절 실험 결과다. 서버가 요청을 끝까지 읽은 뒤 응답 없이 연결을 닫을 때, 서버가 실제로 받은 요청 수를 셌다.

| 위치 | 실험·근거 | 조치 |
|---|---|---|
| `HttpURLConnection` 직접 사용 | 본문을 버퍼링한 POST가 **2번** 도착 (E1a). JDK 속성 `sun.net.http.retryPost`의 기본값이 `true`다 | 쓰지 않는다 |
| Spring `SimpleClientHttpRequestFactory` | POST는 1번 (E1b, Spring이 고정 길이 스트리밍으로 보내 재전송 조건을 피한다), GET은 **2번** (E2a) | 쓰지 않는다. POST 재전송을 막는 일이 Spring 내부 구현에 기대게 된다 |
| `JdkClientHttpRequestFactory` + `java.net.http.HttpClient` | POST 1번 (E1c·E1d), read timeout 뒤 재전송 없음 (E3a). **GET은 응답 없이 연결이 끊기면 2번** 도착한다 (E2b). JDK 21 `MultiExchange`는 GET·HEAD를 한 번 다시 보내고, 이것을 끄는 속성은 없다. `jdk.httpclient.enableAllMethodRetry`는 재전송 대상을 넓힐 뿐이다. 상대가 처리하지 않은 요청이나 연결 수립 실패는 메서드와 상관없이 다시 보낸다 (요청이 처리되지 않았으므로 안전하다) | **채택.** create(POST)는 재전송되지 않는다. 조회(GET)의 내부 재전송은 외부 효과가 없어 허용하되, 실제 호출 수 기대값에 반영한다 (9절 표 #12). create 횟수는 계약 테스트로 직접 센다 (9절 표 #7·#8) |
| HTTP/2 업그레이드 | `HttpClient` 기본 버전은 HTTP/2다. 평문 연결에 `Upgrade: h2c`, `HTTP2-Settings` 헤더를 붙여 보낸다 (E1c) | `HttpClient.Version.HTTP_1_1`로 고정한다 |
| 프록시 | `HttpClient.proxy()`가 비어 있어도 공개되지 않은 기본 프록시 선택기(`ProxySelector.getDefault()`)를 쓸 수 있다 (JDK 21 javadoc). 시스템 속성으로 프록시가 끼면, 그 프록시의 재전송·버퍼링이 우리가 센 호출 수 밖에서 일어난다 | `HttpClient.Builder.NO_PROXY`로 직접 연결을 고정한다 |
| Resilience4j `Retry` | create에 걸리면 곧 이중 결제다 | create 경로에는 어떤 decorator도 걸지 않는다 |
| Spring 재시도 (`@Retryable`), 상위 서비스 재시도 | 서비스 계층에서 create를 감싸면 호출 수가 곱해진다 | 쓰지 않는다. design.md 9절의 confirm도 create를 한 번만 부른다 |

### 5-3. 제한된 재조회 (조회 경로만)

| 항목 | 값 |
|---|---|
| 최대 시도 | 3회 (처음 1회 + 재시도 2회. Resilience4j `maxAttempts=3`은 첫 호출을 포함한다) |
| 시도 사이 대기 | 100ms → 200ms (지수 2배, jitter 없음 — 테스트에서 대기 시간을 정확히 예상하기 위해) |
| 재시도 대상 | timeout, 연결 실패·끊김, 5xx, 형식 오류 |
| 재시도 안 함 | 정상 응답(0건·PENDING 포함), 404, 그 밖의 4xx, 회로 OPEN |
| 최악 대기 예산 | 기본 3 × 1s + 0.3s ≈ **3.3초**. 테스트 3 × 300ms + 0.3s ≈ 1.2초 |

0건이나 PENDING은 "다시 물어보면 바뀔 수도 있는" 정상 응답이지만 재조회 대상이 아니다. 기다렸다 다시 확인하는 일은 사람의 reconcile 호출(design.md 9절)이나 callback이 맡는다. 재조회 한도를 다 써도 새 결제를 허용하지 않는다.

### 5-4. 회로 차단기 (조회 경로 한 개)

이름: `pgLookup`. 단건 조회와 주문별 조회가 같은 회로를 쓴다 (같은 PG 조회 기능의 상태를 본다).

| 설정 | 값 (기본 / 테스트) | 이유 |
|---|---|---|
| 창 종류·크기 | 호출 수 기준, 10 | 호출이 드문 실습 환경에서 시간 기준 창은 표본이 모이지 않는다 |
| 최소 표본 | 5 | 한두 번의 실패로 열리지 않게 한다 |
| 실패율 기준 | 50% | |
| 느린 호출 | 따로 세지 않음 | 응답 대기가 이미 timeout으로 잘려 실패로 기록된다 |
| OPEN 유지 | 10s / 500ms | 테스트는 짧게 두고, 상한이 있는 대기로 확인한다. **재시도 대기 합(100ms + 200ms)보다 길어야 한다.** 짧으면 같은 조회의 재시도가 OPEN 대기를 넘겨 HALF_OPEN 탐색으로 나가, 5-5절 예의 호출 수가 달라진다 (13-6절) |
| HALF_OPEN 탐색 호출 | 2 | 2건이 끝나면 실패율을 계산해, 기준 **이상**(50% 이상)이면 다시 OPEN, 아니면 CLOSED가 된다. 2건 중 1건만 실패해도 50%라서 OPEN이다. **2건 모두 성공해야 CLOSED** |
| OPEN → HALF_OPEN 전환 | 다음 호출 때 (자동 전환 끔 — Resilience4j 기본값) | 대기 시간이 지나도 호출이 들어와야 전환된다. 백그라운드 스레드 없이 결정적으로 확인한다 |
| 실패로 기록 | 재시도 가능 예외(`PgRetryableException`: timeout, 연결 실패·끊김, 5xx, 형식 오류) | `recordException`에 이 예외만 지정한다 |
| 기록 제외 | 재시도하지 않는 예외(`PgNonRetryableException`: 404 외 4xx) | `ignoreExceptions`에 지정한다. 무시한 예외는 성공으로도 실패로도 세지 않는다 |
| 성공으로 기록 | 정상 반환 (404·0건 포함) | 예외 없이 돌아온 호출 |

주의: Resilience4j는 `recordExceptions` 목록을 주면 **목록 밖의 예외를 성공으로 센다**. 404 외 4xx를 목록에서만 빼고 무시 목록에 넣지 않으면, 우리 요청 오류가 성공 표본이 되어 실패율을 낮춘다. 그래서 기록과 무시를 둘 다 명시한다.

**OPEN이어도 결제를 FAILED로 확정하지 않는다.** fallback은 `Unavailable(CIRCUIT_OPEN)`을 돌려줄 뿐이다. design.md 9절이 이것을 "저장된 상태 유지 + 확인 불가 응답"으로 바꾼다. 가짜 성공, 금액 0, 포인트 대체 결제는 하지 않는다.

### 5-5. 조합 순서

```
findTransaction(key)
 = catch( Retry( CircuitBreaker( HTTP 호출 1회 ) ) )
```

- 회로 차단기가 안쪽이다. **시도 하나가 표본 하나**다. 5-2절의 JDK 내부 GET 재전송은 시도 하나 안에서 일어나므로 표본을 늘리지 않는다.
- 재시도 조건은 `retryOnException(e -> e instanceof PgRetryableException)`이다. 조건에 맞지 않는 예외는 기다리지 않고 바로 던진다 (Resilience4j 2.2.0 `RetryImpl` 소스로 확인). 그래서 회로가 거절한 호출(`CallNotPermittedException`)과 404 외 4xx는 다시 시도하지 않는다.
- 맨 바깥에서 예외를 결과 타입으로 바꾼다. 바깥으로 예외가 새지 않는다.
- 예: 조회가 계속 500이면 이렇게 된다.
  - 1회차 조회: HTTP 3번, 표본 3 (최소 표본 5 미만이라 아직 CLOSED)
  - 2회차 조회: HTTP 2번 만에 표본 5, 실패율 100%로 OPEN. 세 번째 시도는 회로가 거절한다
  - 3회차 조회: HTTP 0번
  - 연결 끊김으로 실패시키면 HTTP 수만 각각 2배가 되고(JDK 내부 재전송), 표본 수와 상태 전이는 같다

decorator는 **코드로 직접 조립**한다 (annotation 대신).

| 이유 | 설명 |
|---|---|
| 프록시가 필요 없다 | AOP 의존성 추가, 자기 호출로 적용이 빠지는 문제, 실제 프록시가 걸렸는지 확인할 일이 없다 |
| 순서가 코드에 보인다 | Retry·CircuitBreaker의 바깥·안쪽 순서를 설정 속성의 우선순위로 추측하지 않는다 |
| 테스트가 쉽다 | 테스트가 `CircuitBreakerRegistry`에서 회로 상태를 직접 읽을 수 있다 |

### 5-6. 의존성

- `io.github.resilience4j:resilience4j-circuitbreaker`, `resilience4j-retry`만 추가한다 (Spring Boot 스타터·AOP 없음).
- 버전은 `gradle.properties`에 `resilience4jVersion=2.2.0`으로 명시한다. 이 프로젝트가 가져오는 Spring Cloud 2024.0.1 BOM이 `resilience4j-bom` 2.2.0을 관리하므로, 같은 값으로 고정해 두 버전이 섞이지 않게 한다 (Spring Cloud 2024.0.1 → `spring-cloud-circuitbreaker` 3.2.1 → `resilience4j.version` 2.2.0, Gradle 캐시의 BOM에서 확인). 2.0.0 릴리스 노트가 "Upgraded to Java 17"이라고 밝히므로 JDK 21에서 쓸 수 있다.
- HTTP client는 기존 `spring-boot-starter-web`의 `RestClient`(Spring Framework 6.2)를 쓴다. Feign·WebClient·Apache HttpClient는 추가하지 않는다.

## 6. 설정값

```yaml
payment:
  pg:
    base-url: http://127.0.0.1:8082      # local 기본값. 테스트는 fixture 주소로 덮어쓴다
    callback-url: http://127.0.0.1:8080/api/v1/payments/callback
    provider-user-id: "135135"
    connect-timeout: 500ms
    create-read-timeout: 3s
    lookup-read-timeout: 1s
    lookup-retry:
      max-attempts: 3
      initial-wait: 100ms
      multiplier: 2
    lookup-circuit-breaker:
      sliding-window-size: 10
      minimum-number-of-calls: 5
      failure-rate-threshold: 50
      wait-duration-in-open-state: 10s
      permitted-calls-in-half-open-state: 2
```

- `@ConfigurationProperties("payment.pg")` record(`PgProperties`)로 바인딩한다.
- 테스트는 `@DynamicPropertySource`로 `base-url`을 fixture 주소로 바꾸고, 시간 값은 5절의 테스트 값으로 줄인다.
- local 프로필에는 실제 PG가 없다. 앱을 그냥 띄워 confirm하면 연결 실패로 UNKNOWN이 된다 (의도된 동작).

## 7. 보안·개인정보

| 규칙 | 적용 |
|---|---|
| 카드 값은 합성 값만 | 테스트·예시는 `TEST` / `0000-0000-0000-0000`만 쓴다 |
| 카드번호를 남기지 않음 | DB 컬럼 없음. `PgPaymentRequest.toString()`에서 가림. adapter는 요청·응답 본문을 로그에 찍지 않는다. 예외 메시지에 요청 본문을 넣지 않는다 |
| 주소 주입 금지 | `base-url`·`callback-url`은 설정값. confirm 요청에 URL 필드를 두지 않는다 |
| 외부 계정 ≠ 고객 | PG로 보내는 `X-USER-ID: 135135`는 제공자 계정이다. commerce의 고객 식별 헤더(`X-USER-ID`, `@LoginUserId`)와 이름만 같고 관계가 없다. callback 엔드포인트는 이 헤더로 고객을 식별하지 않는다 |
| 실행 범위 | fixture는 `127.0.0.1`에만 바인딩한다 |

## 8. 테스트 PG fixture — `TestPgServer`

> 구현: `apps/commerce-api/src/test/java/com/loopers/support/pg/` (2026-10-21). 자체 테스트 `TestPgServerTest` 20건.
>
> | 파일 | 맡는 일 |
> |---|---|
> | `TestPgServer` | 테스트가 쓰는 유일한 입구. 시작·종료, 통제·관찰 API, 요청 라우팅과 HTTP 처리 |
> | `TestPgContract` | 3절 계약: 경로·헤더·code 값, 생성 요청 형식 검사, 응답 JSON 모양 |
> | `TestPgControls` | 8-2절 실패 통제 상태 (다음 생성 요청 통제, 조회 실패 횟수, 응답 붙잡기). 동기화를 여기에 모은다 |
> | `TestPgTransaction`, `TestPgStatus`, `TestPgLookupFailure`, `TestPgRequest` | 거래, 거래 상태, 조회 실패 방식, 받은 요청 기록 |

JDK 내장 `com.sun.net.httpserver.HttpServer`로 만든다 (새 의존성 없음). 3절 계약만 구현하고, 무작위 지연·성공 확률은 넣지 않는다.

**요청 처리 스레드 풀을 반드시 지정한다** (`setExecutor`, 예: 고정 8개). `HttpServer`는 executor를 주지 않으면 요청을 한 스레드에서 차례로 처리한다. 1초 걸리는 요청 2개를 동시에 보내면 2초가 걸렸다 (13절 실험 E5a). 이 상태에서는 응답을 붙잡는 동안 다른 요청이 모두 멈추고, 동시 confirm 테스트가 PG 쪽에서 저절로 직렬화되어 동시성을 검증하지 못한다.

### 8-1. 상태와 관찰값

| 항목 | 내용 |
|---|---|
| 거래 저장소 | `transactionKey → (계정, orderId, amount, status, cardType)`. 카드번호는 거래에 저장하지 않는다 |
| 호출 수 | `createCount`, `lookupByKeyCount`, `lookupByOrderCount` (요청을 받은 순간 증가) |
| 요청 기록 | `requests()`: 메서드, 인코딩된 그대로의 경로·query, 모든 헤더(이름 대소문자 무시), 본문 문자열과 `json()` — 계약 테스트가 형식을 대조하는 용도. 메모리에만 두고 로그로 찍지 않는다 |
| 거래 | `transactionCount()`, `transactionsOf(orderId)`(생성 순서), `transaction(key)` |
| 처리 오류 | `handlerErrors()`: fixture 코드에서 난 예외. 비어 있지 않으면 fixture 버그다 (이때 응답 없이 연결을 닫으므로 클라이언트에는 연결 끊김처럼 보인다). 자체 테스트는 매 테스트 뒤 비어 있는지 확인한다 |

### 8-2. 통제 기능

| 기능 | 동작 | 쓰는 장면 |
|---|---|---|
| (기본) | create → 거래 저장(PENDING) 후 즉시 202 | 정상 |
| `rejectNextCreate()` | 거래를 저장하지 않고 3-2절의 정확한 422 | 효과 전 확정 거절 |
| `respondNextCreate(status, body, createTransaction)` | 임의 상태·본문으로 응답. 거래를 저장한 뒤 응답할지 고를 수 있다 | 본문 없는 422, 다른 code, `"false"` 문자열, 금액 불일치, 500 |
| `holdNextCreateResponse()` | 거래를 저장한 뒤 응답을 붙잡아 클라이언트가 timeout되게 한다 (`releaseHeld()`나 상한 5초까지) | 거래 생성 뒤 응답 유실 (timeout) |
| `dropNextCreateResponse()` | 거래를 저장한 뒤 응답 헤더를 보내지 않고 `exchange.close()`한다. 클라이언트는 `EOFException`("header parser received no bytes")을 받는다 (E5b) | 거래 생성 뒤 응답 유실 (연결 끊김) |
| `complete(transactionKey, SUCCESS \| FAILED)` | 거래 상태를 바꾼다. 자동 비동기 처리는 없다 | 조회·callback 결과 만들기 |
| `failLookups(mode)` / `failNextLookups(n, mode)` / `healLookups()` | 조회를 `SERVER_ERROR`(500) · `HOLD`(붙잡기) · `DROP`(연결 끊기) · `INVALID_JSON`(200 + 깨진 JSON)으로 실패시킨다. 단건·주문별 조회에 똑같이 적용되고, 계정 확인보다 먼저 적용된다. `DROP`이면 JDK `HttpClient`가 GET을 한 번 다시 보내 서버는 조회를 2번 받는다 (5-2절) | 재조회 횟수, 회로 OPEN·회복 |
| `seedTransaction(account, orderId, amount, status)` | create 없이 거래를 직접 넣는다 | 2건 이상, 금액·주문 불일치, 다른 계정 |
| `callbackPayload(transactionKey)` | 3-5절 본문을 거래의 현재 상태로 만든다 (보내지는 않는다) | callback-first, 중복 알림 |
| `releaseHeld()` | 붙잡은 응답을 모두 푼다. 이후 응답은 다시 붙잡힌다 | 늦게 도착한 응답 |
| `reset()` / `close()` | 붙잡은 응답을 풀고 거래·호출 수·요청 기록·통제·처리 오류를 지운다 / 붙잡은 응답을 풀고 서버·처리 스레드를 정리한다 | 테스트 사이 정리 |

통제 규칙:
- 생성 통제 4개는 **형식 검사를 통과한 다음 생성 요청 한 번**에만 적용된다. 400으로 거절된 요청은 통제를 쓰지 않는다.
- 생성 통제가 이미 걸려 있으면 다른 통제를 겹쳐 걸 수 없다 (`IllegalStateException`). 한 테스트 안에서 어느 통제가 적용됐는지 헷갈리지 않게 한다.
- `reset()`은 거래 키 일련번호를 되돌리지 않는다. 공유 서버를 쓰는 테스트 사이에 같은 거래 키가 다시 나오지 않게 하기 위해서다.

### 8-3. 수명과 정리

| 테스트 종류 | fixture 수명 | 이유 |
|---|---|---|
| 계약 테스트 (Spring 없음) | `TestPgServer.start()`로 테스트 클래스마다 `127.0.0.1:0`(임의 포트)에 띄우고 `@AfterAll`에서 `close()` | adapter를 직접 만들므로 포트가 달라도 비용이 없다 |
| Spring 통합 테스트 | `TestPgServer.shared()`로 **JVM에 하나만** 띄워 모든 클래스가 같은 주소를 쓴다. 테스트가 `close()`하면 `UnsupportedOperationException`이고, JVM 종료 훅이 닫는다 | 클래스마다 포트가 다르면 `@DynamicPropertySource` 값이 달라 Spring 테스트 컨텍스트 캐시를 재사용하지 못하고, 클래스마다 컨텍스트를 새로 띄운다. 테스트는 `maxParallelForks = 1`로 한 JVM에서 차례로 돌므로 하나를 공유해도 된다 |

서버는 **daemon 스레드에서 시작한다.** `HttpServer`의 디스패처 스레드는 서버를 시작한 스레드의 daemon 여부를 물려받는다. 테스트 스레드에서 바로 시작하면 닫지 않은 서버가 JVM 종료를 막는다 (13절 실험 E7). 처리 스레드 이름은 `test-pg-{서버 번호}-`로 시작해서(`threadNamePrefix()`), 닫은 뒤 그 서버의 스레드가 남았는지 확인할 수 있다.

- 테스트마다 `@BeforeEach`(또는 `@AfterEach`)에서 fixture `reset()`과 **회로 차단기 상태 초기화**(`CircuitBreaker.reset()`)를 함께 한다. 회로는 adapter 빈과 함께 컨텍스트에 남으므로, 앞 테스트의 실패 표본이 뒤 테스트에서 회로를 열 수 있다.
- 붙잡은 응답은 `reset()`과 `close()`가 반드시 풀어서 스레드가 남지 않게 한다.
- 모든 대기에는 상한이 있다 (응답 붙잡기 최대 5초, 테스트 쪽 대기는 시간 제한이 있는 `await`).

### 8-4. 업무 테스트에서도 같은 fixture를 쓴다

| 선택 | 이유 |
|---|---|
| 채택: 계약 테스트와 업무 통합 테스트가 **같은 HTTP fixture**를 쓴다 | 업무 테스트도 실제 adapter의 매핑을 거친다. "가짜 gateway는 맞는데 실제 adapter는 다른" 틈이 없다. 재시작 장면에서 서비스·클라이언트를 새로 만들어도 PG 거래 상태가 그대로 남는다 |
| 버린 대안: 업무 테스트용 메모리 가짜 `PaymentGateway` | 빠르지만 응답 유실·형식 오류를 가짜가 흉내 내야 해서, 실제 adapter와 다른 동작을 검증할 위험이 있다 |
| 비용 | 응답 유실 장면마다 create timeout(테스트 500ms)만큼 걸린다 |

callback은 실제 controller를 거쳐야 하므로, 업무 테스트는 `callbackPayload()`로 만든 본문을 기존 방식대로 MockMvc로 `POST /api/v1/payments/callback`에 보낸다 (11절 열린 결정 3).

## 9. 계약 테스트 — `PaymentProviderContractTest`

실제 `TestPgPaymentGateway` → 실제 HTTP → `TestPgServer`. Spring 컨텍스트와 DB 없이 adapter를 직접 만들어 검증한다 (설정 값은 테스트에서 주입).

| # | 장면 | 기대 결과 |
|---|---|---|
| 1 | create 요청 형식 | `POST /api/v1/payments`. `X-USER-ID: 135135`, `Content-Type: application/json`. `orderId`·`cardType`·`cardNo`·`callbackUrl`은 문자열, `amount`는 정수 number. `callbackUrl`은 설정값 |
| 2 | create 202 | `Accepted("TEST-TX-…")`, fixture 거래 1, `createCount` 1 |
| 3 | 정확한 422 | `RejectedNoEffect`, fixture 거래 0 |
| 4 | 422 변형 (본문 없음 / 다른 code / `transactionCreated: true` / `"false"` 문자열 / `transactionCreated` 누락 / `amount`가 문자열 `"7000"` / `orderId` 불일치 / `amount` 불일치) | 모두 `Unknown`, `RejectedNoEffect`가 아님. 특히 `"false"` 문자열과 누락은 타입 바인딩이면 `false`가 되는 경우다 (4-4절) |
| 5 | 400 `TEST_PG_INVALID_REQUEST`, 500, 깨진 JSON의 202, `amount`가 다른 202, `amount`가 문자열인 202 | 모두 `Unknown` |
| 6 | 500인데 fixture는 거래를 저장함 | `Unknown`, fixture 거래 1 |
| 7 | 거래 생성 뒤 응답 붙잡기 | `Unknown(TIMEOUT)`, fixture 거래 1, **`createCount` 1** (숨은 재시도 없음), 대기 시간이 create timeout 근처 |
| 8 | 거래 생성 뒤 연결 끊기 | `Unknown(CONNECTION_LOST)`, fixture 거래 1, **`createCount` 1** |
| 9 | 단건 조회 형식·결과 | `GET /api/v1/payments/{key}` + 헤더. 200 → `Transactions([tx])` (필드 값 일치), 404 → `Transactions([])` |
| 10 | 주문별 조회와 query 인코딩 | `orderId=LP-ORD-0000000042` 0건 → `[]`, 1건, 2건 → 2건 그대로. 특수문자(`A B&C+D`)를 넘기면 fixture가 같은 문자열로 받는다(`orderId=A%20B%26C%2BD`). 본문이 배열이 아닌 객체 하나면 `Unavailable(INVALID_BODY)` (원소 1개 목록으로 받지 않는다) — 이 형식은 fixture로 만들 수 없어 `TestPgResponsesTest`가 맡는다 |
| 11 | 계정 범위 | 다른 계정으로 seed한 거래는 단건 404·주문별 `[]` |
| 12 | 조회 재시도와 실제 호출 수 | timeout·500·깨진 JSON이면 HTTP **3번** 후 `Unavailable`. 응답 없는 연결 끊김이면 HTTP **6번**(시도 3 × JDK 내부 GET 재전송 2, 5-2절). 404·400이면 HTTP 1번 |
| 13 | 조회 1~2회 실패 후 회복 | 재시도 안에서 성공 → `Transactions`, HTTP 횟수 = 실패 수 + 1 (실패를 500으로 만든 경우) |
| 14 | 회로 OPEN | 500 고정 → 조회 1회차 HTTP 3번, 2회차 HTTP 2번 후 OPEN, 3회차 HTTP 0번 + `Unavailable(CIRCUIT_OPEN)` |
| 15 | HALF_OPEN → CLOSED | OPEN 대기 뒤 fixture 회복 → 탐색 조회 2건 모두 성공 → CLOSED, 이후 조회 정상 |
| 16 | HALF_OPEN → OPEN | OPEN 대기 뒤 탐색 조회 2건 중 1건이라도 실패 → 다시 OPEN (실패율 50% ≥ 기준 50%) |
| 17 | 회로와 create의 분리 | 조회 회로가 OPEN이어도 create는 PG에 도달한다 (create는 회로를 쓰지 않음) |
| 18 | 카드번호 로그 | 1~8번을 실행하는 동안 캡처한 로그에 `0000-0000-0000-0000`이 없다. `PgPaymentRequest.toString()`에 카드번호가 없다 |

실행 기준: 테스트 0건·skip·연결 실패를 통과로 세지 않는다. 각 테스트는 자기가 띄운 서버와 붙잡은 응답을 정리하고, 회로 상태를 초기화한다 (8-3절).

**과제가 "작은 HTTP 계약 테스트"에 요구한 대조 중 재고 예약이 걸린 항목**은 adapter만으로 확인할 수 없다. 예약이 생긴 뒤 업무 통합 테스트(`PaymentIntegrationTest`, design.md 9절)에서 같은 fixture로 확인하고, 아래처럼 위 표와 짝지어 둔다.

| 과제 대조 항목 | adapter 쪽 (이 표) | 업무 쪽 (design.md 9절) |
|---|---|---|
| 정확한 422 → 거래 0건·예약 반환 1회 | #3 | 주문 `PAYMENT_FAILED`, 가용 재고 복귀, 반환 1회 |
| 같은 거절을 두 번 적용해도 반환이 늘지 않음 | — | 같은 관찰을 두 번 적용해도 가용 재고가 한 번만 복귀 |
| 거래 생성 뒤 응답 유실 → 거래 1건·UNKNOWN·예약 유지·create 추가 0회 | #7·#8 | 주문 `PAYMENT_PENDING`, 예약 유지, reconcile 뒤에도 `createCount` 1 |

```
./gradlew :apps:commerce-api:test --tests '*PaymentProviderContractTest'
```

## 10. 도메인이 관찰 결과를 쓰는 방식 (design.md 9절 미리보기)

| 관찰 | 결제 시도 | 주문 | 재고 예약 | 다음 확인 |
|---|---|---|---|---|
| `Accepted(key)` | 처리 중, `transactionKey` 저장 | `PAYMENT_PENDING` | 유지 | callback 또는 reconcile |
| `RejectedNoEffect` | 실패 (효과 0건 확정) | `PAYMENT_FAILED` | 한 번 반환 | 없음 (종결) |
| `Unknown` | 결과 미확인 | `PAYMENT_PENDING` | 유지 | reconcile (주문별 조회) |
| 조회 1건 + 시도와 일치 + `SUCCESS` | 성공 | `CONFIRMED` | 소비 (추가 차감 없음) | 종결 |
| 조회 1건 + 시도와 일치 + `FAILED` | 실패 | `PAYMENT_FAILED` | 한 번 반환 | 종결 |
| 조회 1건 + 시도와 일치 + `PENDING` | 그대로 | 그대로 | 유지 | 다시 확인 |
| 조회 0건 | 그대로 | 그대로 | 유지 | 다시 확인. **create 재발송 없음** |
| 조회 2건 이상, 또는 금액·주문·키 불일치 | 충돌 기록, 상태는 그대로 | 그대로 | 유지 | 사람 판단 |
| `Unavailable` (회로 OPEN 포함) | 그대로 | 그대로 | 유지 | 응답에 "확인 불가"와 조회 경로 |

이미 종결된 시도에는 어떤 관찰도 상태를 되돌리거나 덮어쓰지 않는다.

## 11. 결정 사항 (열린 결정의 결과)

구현을 시작하기 전에 정하지 못한 6개 항목이다. 항목별로 따로 확정받지는 않았고, 기본안대로 구현해 달라는 요청에 따라 기본안으로 진행했다. 다른 선택을 하면 아래 "바꾸면 생기는 영향"이 생긴다.

| # | 질문 | 결정 (구현) | 확인한 곳 | 바꾸면 생기는 영향 |
|---|---|---|---|---|
| 1 | 연결 수립 실패(요청이 PG에 닿지 않은 것이 확실한 경우)도 UNKNOWN인가? | **UNKNOWN** — 과제가 나열한 "효과 0건 증거"는 정확한 422뿐이다 | `PaymentProviderContractTest` — PG가 내려가 있어도 `CONNECTION_LOST` 결과 미확인 | FAILED로 두면 PG가 내려간 동안의 주문이 예약을 바로 반환받지만, "연결 실패"와 "보낸 뒤 끊김"을 클라이언트 예외만으로 구분하는 데 기대게 된다 |
| 2 | create 202 본문의 `status`를 판단에 쓰는가? | **쓰지 않는다** — 접수 사실과 `transactionKey`만 쓰고 최종은 조회로 확인 | `TestPgResponsesTest` — 202 본문이 `SUCCESS`여도 접수로만 본다 | 쓰면 create 응답의 `SUCCESS`로 바로 확정할 수 있지만, 생성 수락과 최종 성공을 구분하라는 요구와 멀어진다 |
| 3 | callback 경로 검증을 MockMvc로 하는가, 실제 포트로 하는가? | **MockMvc** — 기존 테스트 방식과 같고 실제 controller를 거친다 | `PaymentCallbackIntegrationTest` | 실제 포트(`RANDOM_PORT`)면 fixture가 HTTP로 직접 알릴 수 있지만, 기존 MockMvc 사용자·관리자 설정과 다른 테스트 구성이 하나 더 생긴다 |
| 4 | 회로 차단기·재조회를 Resilience4j로 하는가? | **Resilience4j 코드 조립** (5-5절) | `PaymentProviderContractTest`(재조회·회로 #12~#17), `PgClientConfigTest` | 직접 구현하면 의존성은 없지만 상태 전이·표본 계산을 직접 검증해야 한다 |
| 5 | create에도 별도 회로를 두는가? | **두지 않는다** — 과제 범위는 조회 한 경로 | `PaymentProviderContractTest` #17 — 조회 회로가 열려 있어도 create는 PG에 도달 | PG 장애 동안 confirm마다 create timeout(3s)만큼 기다리는 비용이 남는다 |
| 6 | callback 본문의 주문 필드 이름은? | **`orderId`를 기본으로, `providerOrderId`도 같은 값으로 받는다** (3-5절). 처음에는 `orderId`만 받았으나, 구현 뒤 과제 원문과 대조하다가 `providerOrderId`로 보낸 본문이 `orderId=null`이 되어 400이 되는 것을 확인해 별칭을 더했다 | `PaymentV1DtoTest` — 두 이름 모두 같은 값으로 읽힌다 | 한 이름만 받으면 PG 메시지 안에서 같은 값의 이름이 하나로 통일되지만, 과제 문장과 다른 이름으로 오는 알림을 거절하게 된다 |

## 12. 구현 순서 (PG 단계)

1. ✅ `domain.payment` — port, 요청·결과 타입, `ProviderOrderId` 변환 (+ 단위 테스트: `LP-ORD-0000000042`, `toString` 마스킹). 2026-10-23 완료
2. ✅ `TestPgServer` — 3절 계약과 8절 통제·관찰 기능, 스레드 풀 executor (+ fixture 자체를 확인하는 작은 테스트: 응답을 붙잡은 동안 다른 요청이 처리되는지, drop 모드에서 클라이언트가 EOF를 받는지). 2026-10-21 완료, `TestPgServerTest` 20건 통과 (13-5절)
3. ✅ `TestPgPaymentGateway` + `PgProperties` + 설정 — `HTTP_1_1`·`NO_PROXY`로 만든 `HttpClient`, `JsonNode` 해석(4-4절, `TestPgResponses`), create·조회 매핑 (9절 표 #1~#11). 2026-10-23 완료
4. ✅ 재조회·회로 조립 (9절 표 #12~#17). 2026-10-23 완료
5. ✅ 로그·마스킹 확인 (9절 표 #18). 2026-10-23 완료
6. ✅ `./gradlew :apps:commerce-api:check` — Checkstyle·ArchUnit·기존 회귀. 2026-10-23 351건 실행, 실패 0 (skip 3건은 다른 작업의 옵트인 실험 하네스). 주문 연결 뒤의 검증은 design.md 9-12절

이 단계가 끝나면 주문·재고 연결(design.md 9절)로 넘어간다.

## 13. 검증 기록 (2026-10-20)

초안의 주장을 과제 원문, 현재 코드, 라이브러리 문서·소스, 실험으로 다시 확인했다.

### 13-1. 실험

Gradle 캐시에 있는 이 프로젝트의 실제 의존성(Spring Web 6.2.5, Jackson 2.18.3)과 JDK 21.0.11로, 일회성 실험 프로그램을 돌렸다. 서버는 원시 소켓으로 요청을 끝까지 읽은 뒤 응답 없이 닫거나 3초 붙잡는다.

| # | 장면 | 결과 |
|---|---|---|
| E1a | `HttpURLConnection` 직접 사용, 버퍼링 POST, 응답 없이 닫힘 | 서버 수신 **2번** |
| E1b | `RestClient` + `SimpleClientHttpRequestFactory` POST | 1번 |
| E1c | `RestClient` + `JdkClientHttpRequestFactory`(기본 HTTP/2) POST | 1번. 요청에 `Upgrade: h2c`, `HTTP2-Settings` 헤더가 붙음 |
| E1d | 위와 같되 `HTTP_1_1` | 1번, 업그레이드 헤더 없음 |
| E2a | `SimpleClientHttpRequestFactory` GET, 응답 없이 닫힘 | **2번** |
| E2b | `JdkClientHttpRequestFactory`(HTTP_1_1) GET, 응답 없이 닫힘 | **2번** |
| E3a | Jdk factory `readTimeout=300ms`, POST, 서버가 붙잡음 | 1번, 309ms, `ResourceAccessException` ← `HttpTimeoutException` |
| E3b | Simple factory `readTimeout=300ms`, 같은 장면 | 1번, 305ms, `ResourceAccessException` ← `SocketTimeoutException` |
| E4 | `HttpClient.newHttpClient().proxy()` | `Optional.empty` (javadoc: 비어 있어도 기본 프록시 선택기를 쓸 수 있음) |
| E5a | `HttpServer` executor 미지정, 1초 요청 2개 동시 | 총 2,052ms (차례로 처리) |
| E5b | `HttpServer` 핸들러가 응답 없이 `exchange.close()` | 서버 수신 1번, 클라이언트 `EOFException`("HTTP/1.1 header parser received no bytes") |
| E6a | `"transactionCreated":"false"`, `"amount":"7000"`을 record(`boolean`, `long`)로 바인딩 | 기본 매퍼·공용 매퍼 모두 오류 없이 `false`, `7000` |
| E6b | 객체 하나를 `List`로 바인딩 | 공용 매퍼(`ACCEPT_SINGLE_VALUE_AS_ARRAY` 켬): 원소 1개 목록 |
| E6c | `transactionCreated`가 없는 본문을 record로 바인딩 | 기본 매퍼: 오류 없이 `false`. 공용 매퍼(`FAIL_ON_NULL_FOR_PRIMITIVES` 켬): `MismatchedInputException` |
| E6d | 같은 본문을 `JsonNode`로 읽음 | `isIntegralNumber=false`, `isBoolean=false`로 구분 가능 |
| E7 | `HttpServer`를 main 스레드에서 시작하고 main을 끝냄 / daemon 스레드에서 시작하고 끝냄 (2026-10-21) | `HTTP-Dispatcher daemon=false`, JVM이 끝나지 않음 / `daemon=true`, 바로 종료 |

"공용 매퍼"는 `JacksonConfig`가 켜고 끄는 `DeserializationFeature`를 그대로 적용한 `ObjectMapper`다.

### 13-2. 문서·소스로 확인한 것

| 주장 | 근거 |
|---|---|
| Resilience4j `maxAttempts`는 첫 호출을 포함한다 | Retry 문서: "including the initial call as the first attempt" |
| 재시도 조건에 맞지 않는 예외는 바로 던진다 | `RetryImpl`(v2.2.0) `onError`의 else 분기가 `throw exception` |
| 최소 표본 전에는 실패율을 계산하지 않는다. HALF_OPEN은 실패율이 기준 **이상**이면 OPEN이다 | CircuitBreaker 문서 |
| OPEN → HALF_OPEN 자동 전환은 기본으로 꺼져 있고, 호출이 들어와야 전환된다 | CircuitBreaker 문서 (`automaticTransitionFromOpenToHalfOpenEnabled` 기본 false) |
| 무시한 예외는 성공도 실패도 아니고, `recordExceptions`를 주면 목록 밖 예외는 성공으로 센다 | CircuitBreaker 문서 |
| 느린 호출 기준의 기본값은 100%·60초라서, 우리 timeout 아래에서는 따로 작동하지 않는다 | CircuitBreaker 문서 |
| JDK `HttpClient`는 GET·HEAD를 한 번 다시 보내고, 끄는 속성이 없다 | JDK 21 `MultiExchange` 소스 (`isIdempotentRequest`, `retriedOnce`) |
| `HttpClient` 기본 버전은 HTTP/2다 | JDK 21 javadoc `HttpClient.version()` |
| 이 프로젝트의 Resilience4j 관리 버전은 2.2.0이다 | Spring Cloud 2024.0.1 → `spring-cloud-circuitbreaker-dependencies` 3.2.1 POM (Gradle 캐시) |
| Spring Boot 3.4.4의 Spring Web은 6.2.5다 | Gradle 캐시 |

### 13-3. 초안에서 고친 것

| 초안 | 확인한 사실 | 고친 곳 |
|---|---|---|
| `SimpleClientHttpRequestFactory`가 `retryPost` 때문에 POST를 한 번 더 보낼 수 있다 | 다시 보내는 것은 `HttpURLConnection` 직접 사용(E1a)이고, Spring factory의 POST는 1번이었다(E1b). 대신 GET은 두 client 모두 2번이었다(E2) | 5-2절 |
| 조회가 연결 끊김으로 실패하면 HTTP 3번 | JDK 내부 GET 재전송 때문에 6번 | 5-2·5-5절, 9절 표 #12 |
| (없음) 응답을 어떻게 읽는지 | 타입 바인딩은 형식이 틀린 422를 "효과 0건"으로 판정할 수 있다(E6) | 4-4절 신설, 9절 표 #4·#5·#10 |
| (없음) fixture 스레드 | executor를 지정하지 않으면 요청을 차례로 처리한다(E5a) | 8절 |
| (없음) 프록시 | 과제가 "프록시의 숨은 재시도"를 끄라고 요구한다. 기본 프록시 선택기가 쓰일 수 있다 | 5-2절 `NO_PROXY` |
| HALF_OPEN은 "성공률이 기준 이상이면 CLOSED" | 실패율이 기준 **이상**이면 OPEN이라서, 2건 중 1건만 실패해도 OPEN이다 | 5-4절, 9절 표 #15·#16 |
| (없음) 회로 예외 분류 | `recordExceptions`만 주면 목록 밖 예외가 성공으로 세어진다 | 5-4절 기록·무시 둘 다 명시 |
| 클래스마다 fixture를 새로 띄움 | Spring 통합 테스트에서는 컨텍스트 캐시가 깨지고, 회로 상태가 테스트 사이에 남는다 | 8-3절 |
| 과제의 계약 테스트 대조 항목 | 예약 반환·유지 대조가 adapter 테스트에 없었다 | 9절 짝 표 |
| (없음) 계정 범위 확인 방법 | 응답에 계정이 없다 | 4-3절 |
| callback 필드 이름 | 과제 문장은 `providerOrderId`라고 부른다 | 11절 열린 결정 6 |

### 13-4. 아직 확인하지 않은 것

- Resilience4j 회로·재시도의 실제 동작(9절 표 #12~#16의 수치)은 문서와 소스로만 확인했다. 의존성을 추가한 뒤 계약 테스트로 확인한다.
- JDK 내부 GET 재전송이 timeout·500에서는 일어나지 않는다는 점은 POST timeout(E3)과 소스의 재전송 조건으로 추론했다. GET timeout은 9절 표 #12로 확인한다.
- 실험 프로그램은 저장소에 넣지 않았다. 같은 내용을 9절 계약 테스트와 8절 fixture 자체 테스트가 맡는다.

### 13-5. fixture 자체 테스트 (2026-10-21)

`./gradlew :apps:commerce-api:test --tests 'com.loopers.support.pg.TestPgServerTest'` — 20건 실행, 실패·skip 0건 (테스트 결과 XML로 실행 건수 확인). 같은 실행에서 `checkstyleTest` 통과.

| 묶음 | 확인한 것 |
|---|---|
| 생성 (9건) | 202 본문의 타입, PENDING 저장, 요청 기록, 같은 orderId의 새 거래, 형식 오류 400과 거래 0건, 정확한 422와 한 번만 적용되는 통제, 임의 응답과 거래 저장 여부, 응답 붙잡기 중 다른 요청 처리, 응답 끊기(POST 재전송 없음), 통제 겹치기 금지 |
| 조회 (4건) | 계정 범위(404), 주문별 생성 순서·빈 배열, query 인코딩 풀기(`A%20B%26C%2BD`), 최종 상태 반영과 callback 본문 |
| 조회 실패 (4건) | 정한 횟수만 500, 회복 전까지 깨진 JSON(단건·주문별), 붙잡기 timeout, 끊기 시 JDK GET 재전송으로 서버 수신 2번 |
| 수명 (3건) | reset 범위와 이어지는 키 일련번호, close가 붙잡은 응답을 풀고 처리 스레드를 정리한 뒤 연결 거절, 공유 서버는 하나이고 닫을 수 없음 |

**테스트가 실제로 잡는지 확인한 변경 실험**: `httpServer.setExecutor(executor)`를 지우고 다시 돌리면 "응답을 붙잡으면 … 붙잡은 동안에도 다른 요청은 처리된다" 1건만 실패했다 (20건 중 1건). 실험 뒤 원복하고 다시 확인했다.

### 13-6. adapter 구현 검증 (2026-10-23)

| 테스트 | 건수 | 확인한 것 |
|---|---|---|
| `PaymentProviderContractTest` | 21 | 9절 표 #1~#18, 연결 거절(열린 결정 1)을 실제 HTTP로 |
| `TestPgResponsesTest` | 9 | 4-1·4-4절 해석 규칙과 이유코드 순서, fixture로 만들 수 없는 형식(배열이 아닌 목록, 소수 금액, 소문자 상태) |
| `PgClientConfigTest` | 3 | `application.yml`의 `payment.pg`가 바인딩되고(시간 단위 포함), adapter가 `PaymentGateway` 빈이며, 회로가 설정값으로 `pgLookup`에 등록된다. DB 없이 PG 설정만 올린다 |
| `ProviderOrderIdTest`, `PgPaymentRequestTest` | 6 | 주문 번호 변환, 요청 검증, `toString` 카드번호 가림 |

모두 통과했고 skip은 0건이다. `ArchitectureTest`, `checkstyleMain`·`checkstyleTest`도 통과했다.

**구현하며 고친 것: OPEN 유지 테스트 값 200ms → 500ms**
- 확인하고자 한 것: 500이 계속될 때 5-5절 예대로 1회차 HTTP 3번, 2회차 2번 만에 OPEN, 3회차 0번인가.
- 결과: 2회차 뒤 상태가 OPEN이 아니라 HALF_OPEN이었고, 2회차 결과가 `CIRCUIT_OPEN`이 아니라 `SERVER_ERROR`였다 (#14~#17 실패).
- 확인한 것: 2회차의 두 번째 시도에서 회로가 열린 뒤, 재시도가 200ms를 기다리는 동안 OPEN 유지(200ms)가 끝났다. 자동 전환을 꺼 두었으므로 세 번째 시도가 들어온 순간 HALF_OPEN으로 넘어가 실제로 PG에 나갔다. 운영 값(OPEN 10초)에서는 생기지 않는다.
- 그래서 테스트 값을 재시도 대기 합(300ms)보다 긴 500ms로 바꿨고, 5-4절에 조건을 적었다.

**테스트가 실제로 잡는지 확인한 변경 실험**: 재시도 조건을 `error -> true`(모든 예외 재시도)로 바꾸면 #12의 "404 외 4xx는 HTTP 1번"과 #14의 "3회차는 PG에 묻지 않는다" 2건이 실패했다 (21건 중 2건). 원복하고 다시 확인했다.

**나중에 확인한 것**: 이 시점에는 Docker가 응답하지 않아 MySQL이 필요한 테스트를 돌리지 못했다. 이후 Docker를 켜고 `CommerceApiContextTest`와 기존 회귀를 포함한 전체 `check`를 돌려, adapter 빈이 전체 Spring 컨텍스트에서 함께 올라오는 것을 확인했다 (351건, 실패 0).

---
*이 문서는 구현하며 판단이 바뀌면 그때그때 갱신한다.*
