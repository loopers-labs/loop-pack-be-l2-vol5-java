# API 계약

고객·관리자가 호출하는 Commerce API의 HTTP 경로, 입력, 성공 응답과 대표 오류를 정리한다.<br>
PG와 주고받는 HTTP 계약은 callback 수신을 포함해 [PG 연동 설계](external-systems/payment-gateway.md)를 기준으로 한다.<br>
애그리게이트 규칙은 [도메인 모델](domain-model.md), 복잡한 상태 변경 흐름은 각 유스케이스 설계를, 공통 오류 변환 방식은 [오류 변환 설계](error-mapping.md)를 기준으로 한다.

## 관리자 API

공통 Prefix: `/api-admin/v1`<br>
접근: `ADMIN` 역할 필요(인증 방식은 미정), 없으면 `403 Forbidden`

### 브랜드

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| 브랜드 목록 조회 | `GET` | `/brands` | Query: `status` (`ACTIVE`, `DELETED`, `ALL`; 기본 `ALL`) | `200 OK`<br/>삭제 상태를 포함한 브랜드 목록 | `400 Bad Request`<br/>잘못된 `status` 입력 |
| 브랜드 등록 | `POST` | `/brands` | Body: `name`(공백만 불가, 1~100자) | `201 Created`<br/>생성된 브랜드 정보 | `400 Bad Request`<br/>이름 검증 실패<br/>`409 Conflict`<br/>이미 등록된 이름 |
| 브랜드 상세 조회 | `GET` | `/brands/{brandId}` | Path: `brandId` | `200 OK`<br/>삭제 상태를 포함한 브랜드 상세 정보 | `404 Not Found`<br/>없는 Brand |
| 브랜드 수정 | `PUT` | `/brands/{brandId}` | Path: `brandId`<br/>Body: `name`(공백만 불가, 1~100자) | `200 OK`<br/>수정된 브랜드 정보 | `400 Bad Request`<br/>이름 검증 실패<br/>`404 Not Found`<br/>없거나 삭제된 Brand<br/>`409 Conflict`<br/>이미 등록된 이름 |
| 브랜드·연결 상품 일괄 삭제 | `DELETE` | `/brands/{brandId}` | Path: `brandId` | `200 OK`<br/>Brand와 연결된 활성 Product 논리 삭제 | `404 Not Found`<br/>없거나 삭제된 Brand |

### 상품·재고

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| 상품 목록 조회 | `GET` | `/products` | Query: `status` (`ACTIVE`, `DELETED`, `ALL`; 기본 `ALL`) | `200 OK`<br/>삭제 상태를 포함한 상품 목록 | `400 Bad Request`<br/>잘못된 `status` 입력 |
| 상품 등록 | `POST` | `/products` | Body: `brandId`(필수, 64비트 정수), `name`(공백만 불가, 1~100자), `price`(필수, 1~100,000,000원의 정수) | `201 Created`<br/>재고 0으로 생성된 상품 정보 | `400 Bad Request`<br/>브랜드 ID·상품 이름·가격 입력 오류<br/>`404 Not Found`<br/>없거나 삭제된 Brand |
| 상품 상세 조회 | `GET` | `/products/{productId}` | Path: `productId` | `200 OK`<br/>상품·브랜드·재고·삭제 상태 정보 | `404 Not Found`<br/>없는 Product |
| 상품 수정 | `PUT` | `/products/{productId}` | Path: `productId`<br/>Body: `name`(공백만 불가, 1~100자), `price`(필수, 1~100,000,000원의 정수) | `200 OK`<br/>수정된 상품 정보 | `400 Bad Request`<br/>상품 이름·가격 검증 실패<br/>`404 Not Found`<br/>없거나 삭제된 Product |
| 상품 삭제 | `DELETE` | `/products/{productId}` | Path: `productId` | `200 OK`<br/>Product 논리 삭제<br/>재고·기존 Like·주문 정보는 유지 | `404 Not Found`<br/>없거나 이미 삭제된 Product |
| 상품 재고 변경 | `PUT` | `/products/{productId}/stock` | Path: `productId`<br/>Body: `quantity`(필수, 0 이상의 64비트 정수인 최종 재고 수량) | `200 OK`<br/>변경된 재고 수량 | `400 Bad Request`<br/>재고 수량 입력 오류<br/>`404 Not Found`<br/>없거나 삭제된 Product |

상품 등록·수정과 재고 변경의 Body 숫자 필드는 누락·null, 소수 부분이 있는 값이나 입력 범위 초과를 `400 Bad Request`로 거절하고 기존 데이터를 유지한다.<br>
각 입력 범위 내에서 `100.0`, `1e2`처럼 값이 정수인 표기는 허용한다.

관리자 재고 설정값은 이미 확보한 PG 예약 수량을 제외한 가용 수량의 최종 값이다. 미완료 PG 예약 수량과 설정값의 합이 `Long.MAX_VALUE`를 넘으면 `400 Bad Request`로 거절하고 기존 가용·예약 수량을 유지한다. 예약이 없으면 기존 64비트 비음수 범위를 모두 허용한다.

브랜드 이름, 상품 이름·가격이나 재고를 현재와 같은 값으로 설정해도 `200 OK`로 처리한다.<br>
같은 필드의 수정 요청이 겹치면 마지막으로 반영된 요청의 값이 남는다.<br>
결제 차감·반환과 겹칠 때의 결과는 [관리자 재고 설정과의 기대값](use-cases/order-payment-request.md#관리자-재고-설정과의-기대값)을 따른다.

### 주문

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| 주문 목록 조회 | `GET` | `/orders` | - | `200 OK`<br/>구매자·품목·상태·금액과 관리자용 [결제 상태 객체](#결제-상태-객체)를 포함한 주문 목록 | 기능별 대표 오류 없음 |
| 주문 상세 조회 | `GET` | `/orders/{orderId}` | Path: `orderId` | `200 OK`<br/>구매자·품목·상태·금액과 관리자용 [결제 상태 객체](#결제-상태-객체) | `404 Not Found`<br/>없는 Order |

## 고객 API

공통 Prefix: `/api/v1`<br>
사용자 식별: Header `X-USER-ID` (좋아요·포인트·주문 API)

### 브랜드·상품

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| 브랜드 상세 조회 | `GET` | `/brands/{brandId}` | Path: `brandId` | `200 OK`<br/>브랜드 상세 정보 | `404 Not Found`<br/>없거나 삭제된 Brand |
| 상품 목록 조회 | `GET` | `/products` | Query: `brandId`(선택), `page`(0부터 시작, 미지정 시 `0`), `size`(미지정 시 `20`, 1~100), `sort`(미지정 시 `latest`; `latest`·`price_asc`·`likes_desc`) | `200 OK`<br/>삭제되지 않은 상품의 브랜드 정보·좋아요 수를 포함한 목록 | `400 Bad Request`<br/>페이지·정렬 입력 오류 |
| 상품 상세 조회 | `GET` | `/products/{productId}` | Path: `productId` | `200 OK`<br/>브랜드 정보·좋아요 수를 포함한 상품 상세 정보 | `404 Not Found`<br/>없거나 삭제된 Product |

### 좋아요

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| 좋아요 등록 | `POST` | `/products/{productId}/likes` | Path: `productId` | `200 OK`<br/>좋아요 상태 보장<br/>이후 Brand·Product가 삭제돼도 저장된 Like 관계 보존 | `404 Not Found`<br/>상품 조회 시 없거나 삭제된 Product |
| 좋아요 취소 | `DELETE` | `/products/{productId}/likes` | Path: `productId` | `200 OK`<br/>좋아요 취소 상태 보장 | 기능별 대표 오류 없음 |
| 내 좋아요 목록 조회 | `GET` | `/users/{userId}/likes` | Path: `userId` (`X-USER-ID`와 일치) | `200 OK`<br/>삭제된 Product를 제외한 내 좋아요 상품 목록 | `400 Bad Request`<br/>`X-USER-ID` 누락 또는 Path `userId`와 불일치<br/>`404 Not Found`<br/>존재하지 않는 User |

같은 사용자·상품의 좋아요를 동시에 등록해도 각 요청은 `200 OK`와 좋아요 상태를 반환하고 관계는 1건만 저장한다.

### 포인트·주문

포인트는 `1포인트 = 1원`이다. 충전액의 숫자 표기별 허용·거절 예시는 [포인트 충전 기대값](#포인트-충전-기대값)을 따른다.

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| 포인트 충전 | `POST` | `/points/charge` | Body: `amount`(필수, 64비트 양의 정수) | `200 OK`<br/>충전 후 잔액 | `400 Bad Request`<br/>누락·null·잘못된 타입·입력 범위 초과·양의 정수가 아닌 `amount`, 충전 후 잔액 범위 초과 |
| 내 포인트 잔액 조회 | `GET` | `/points` | - | `200 OK`<br/>저장된 포인트 잔액 | 기능별 대표 오류 없음 |
| 주문 생성 | `POST` | `/orders` | Body: `items`(필수, 비어 있지 않은 품목 목록)<br/>품목별 `productId`(필수, 64비트 정수), `quantity`(필수, 32비트 양의 정수) | `201 Created`<br/>중복 품목을 합산해 품목, 수량, 단가와 합계를 저장한 `DRAFT` Order 반환<br/>재고 미차감<br/>이후 Brand·Product가 삭제돼도 저장된 DRAFT 보존 | `400 Bad Request`<br/>주문 품목 누락·빈 목록·null 품목, 상품 ID·수량 입력 오류<br/>`404 Not Found`<br/>상품 조회 시 없거나 삭제된 Product |
| 주문 결제 요청 | `POST` | `/orders/{orderId}/confirm` | Path: `orderId`<br/>Body: `paymentAttemptKey`(필수, UUID 문자열), `cardType`·`cardNo`(필수, 합성 카드 입력 문자열) | `202 Accepted`<br/>미종결 시도의 [결제 상태 객체](#결제-상태-객체)<br/>`200 OK`<br/>종결된 결제 상태 객체 | `400 Bad Request`<br/>입력 오류<br/>`404 Not Found`<br/>주문이 없거나 타인 소유<br/>`409 Conflict`<br/>시도 키 충돌, 이전 POINT 확정 주문, 상품 사용 불가, 재고 부족 |
| 주문 결제 결과 재확인·반영 | `POST` | `/orders/{orderId}/payment/reconcile` | Path: `orderId` | `202 Accepted`<br/>미종결 시도의 [결제 상태 객체](#결제-상태-객체). PG 조회 불가·결과 반영 저장 오류도 저장 상태를 읽을 수 있으면 그 상태로 응답<br/>`200 OK`<br/>종결된 결제 상태 객체 | `400 Bad Request`<br/>입력 오류<br/>`404 Not Found`<br/>주문이 없거나 타인 소유, 시도 없음<br/>`500 Internal Server Error`<br/>결과 반영 중 정합성 오류, 저장 상태 재조회 실패 |
| 내 주문 목록 조회 | `GET` | `/orders` | - | `200 OK`<br/>내 주문의 품목·수량·금액·상태와 주문별 [결제 상태 객체](#결제-상태-객체)(`payment`) 목록 | 기능별 대표 오류 없음 |
| 내 주문 상세 조회 | `GET` | `/orders/{orderId}` | Path: `orderId` | `200 OK`<br/>내 주문의 품목·수량·금액·상태와 [결제 상태 객체](#결제-상태-객체)(`payment`). 결제 요청을 만들지 않음 | `404 Not Found`<br/>조회할 수 없는 Order |

결제 요청 API는 이미 생성된 시도의 결과 반영에 실패해도 저장 상태를 다시 읽을 수 있으면 그 상태로 응답한다. 결제 요청·재확인 API에서 저장 상태도 읽을 수 없으면 기존 내부 오류 응답(`500`)으로 끝낸다.

결제 요청의 `paymentAttemptKey`는 표준 UUID 텍스트(`8-4-4-4-12` 형식의 16진수 36자)만 허용한다.<br>
대소문자는 구분하지 않고 소문자로 바꿔 저장·비교한다.<br>
누락·null·문자열이 아닌 값·형식 위반은 `400 Bad Request`로 거절하고 주문·재고·시도를 변경하지 않는다.<br>
키 재사용 규칙은 [멱등성과 재요청 정책](use-cases/order-payment-request.md#멱등성과-재요청-정책)을 따른다.

`cardType`·`cardNo`는 공백만으로 이루어지지 않은 필수 문자열이어야 한다.<br>
누락·null·문자열이 아닌 값·공백 문자열은 재고 확보 전에 `400 Bad Request`로 거절하고 주문·재고·시도를 변경하지 않는다.<br>
형식·길이·카드번호 유효성은 검사하지 않는다. 검사 범위의 기준은 [PG 연동의 설정과 민감 정보](external-systems/payment-gateway.md#설정과-민감-정보)를 따른다.

주문 생성의 `productId`·`quantity`는 누락·null, 소수 부분이 있는 값이나 표현 범위 초과를 `400 Bad Request`로 거절하고 주문을 생성하지 않는다.<br>
각 입력 범위 내에서 `100.0`, `1e2`처럼 값이 정수인 표기는 허용한다.

#### 결제 상태 객체

주문의 결제 진행 상황을 나타내는 하나의 객체다.<br>
confirm·reconcile은 이 객체를 응답 본문으로 반환하고, 고객 주문 목록·상세는 같은 객체를 `payment` 필드로 포함한다.<br>
저장된 결제 정보의 형태는 API마다 같다. reconcile에는 이번 조회의 불가 여부인 `lookupUnavailable`을 추가한다. confirm·reconcile의 HTTP 상태는 미종결이면 `202`, 종결이면 `200`이다.

| 필드 | 값 |
|---|---|
| `orderId` | 주문 ID |
| `orderStatus` | `DRAFT`, `PAYMENT_PENDING`, `CONFIRMED`, `PAYMENT_FAILED` |
| `paymentMethod` | `PG`, `POINT`. 결제를 시작하지 않은 `DRAFT`는 `null` |
| `paymentProgress` | [결제 진행 표시](#결제-진행-표시)의 값 |
| `attemptStatus` | `REQUESTED`, `PENDING`, `UNKNOWN`, `SUCCEEDED`, `FAILED`. 시도가 없는 `DRAFT`·이전 POINT 주문은 `null` |
| `paymentResult` | 종결된 주문의 `SUCCESS`·`FAILED`, 그 전에는 `null` |
| `paymentAmount` | `CONFIRMED` 주문의 결제액, 그 밖에는 `null` |
| `lookupUnavailable` | reconcile에만 포함하는 boolean. 이번 PG 조회가 timeout, 통신 오류, 회로 차단 등으로 불가능하면 `true`. 저장된 결제 상태와 구분한다. |
| `links.order` | 고객 주문 상세 경로. 항상 포함 |
| `links.reconcile` | 결제 결과 재확인 경로. 주문이 `PAYMENT_PENDING`일 때만 포함 |

- 품목·`userId`는 주문 응답에서 확인한다. 주문 상세 안의 `payment`도 `orderId`·`orderStatus`를 그대로 둬 객체의 형태를 바꾸지 않는다.
- 고객 응답에는 `transactionKey`, 충돌 사유, `paymentAttemptKey`를 넣지 않는다.
- `lookupUnavailable: false`는 이번 요청에서 조회 불가를 관찰하지 않았다는 뜻이다. 거래 없음, 결과 반영 rollback, 이미 종결돼 조회를 생략한 경우도 포함하며 결제 성공을 뜻하지 않는다. 조회 불가여도 마지막으로 확인한 `PENDING`과 예약은 유지한다.
- 관리자 주문 응답은 같은 객체에 `transactionKey`와 `conflictReason`을 더하고, 고객 경로인 `links`는 제공하지 않는다.
- 이전 포인트 결제 주문은 `paymentMethod: POINT`, `paymentProgress: COMPLETED`, `paymentResult: SUCCESS`, 당시 결제액으로 응답하고 주문 응답의 품목도 그대로 둔다.

```json
// POST /api/v1/orders/42/confirm → 202 Accepted
{
  "meta": { "result": "SUCCESS", "errorCode": null, "message": null },
  "data": {
    "orderId": 42,
    "orderStatus": "PAYMENT_PENDING",
    "paymentMethod": "PG",
    "paymentProgress": "WAITING",
    "attemptStatus": "PENDING",
    "paymentResult": null,
    "paymentAmount": null,
    "links": {
      "order": "/api/v1/orders/42",
      "reconcile": "/api/v1/orders/42/payment/reconcile"
    }
  }
}
```

종결된 `200` 응답도 같은 형태다. 예를 들어 성공 종결이면 `orderStatus: CONFIRMED`, `paymentProgress: COMPLETED`, `attemptStatus: SUCCEEDED`, `paymentResult: SUCCESS`, `paymentAmount`에 결제액이 채워지고, `links`에는 `order`만 남는다.

#### 결제 진행 표시

주문 상태를 먼저 보고, `PAYMENT_PENDING`이면 PG의 거래 접수 확인 여부로 나눈다.

| 값 | 표시 | 조건 |
|---|---|---|
| `NOT_STARTED` | 미결제 | 주문 `DRAFT` |
| `WAITING` | 결제 대기 | 주문 `PAYMENT_PENDING`, 시도 `PENDING`, 충돌 사유 없음. PG가 거래 접수를 확인한 상태다. |
| `UNCONFIRMED` | 결과 미확인 | 주문 `PAYMENT_PENDING`, 시도 `REQUESTED`·`UNKNOWN`이거나 충돌 사유 있음 |
| `COMPLETED` | 결제 완료 | 주문 `CONFIRMED` |
| `FAILED` | 결제 실패 | 주문 `PAYMENT_FAILED` |

종결된 주문은 충돌 사유가 있어도 저장된 최종 결과로 표시한다.

결제 요청의 처리 정책은 [주문 결제 요청](use-cases/order-payment-request.md#멱등성과-재요청-정책)을 따른다.

## 포인트 충전 기대값

| 규칙 | 주어진 상태와 입력 | 기대값 |
|---|---|---|
| 정상 충전 | 잔액 `100`에 `200` 충전 | `200 OK`, 잔액 `300` |
| 0 충전 거절 | 잔액 `100`에 `0` 충전 | `400 Bad Request`, 잔액 `100` 유지 |
| 음수 충전 거절 | 잔액 `100`에 `-1` 충전 | `400 Bad Request`, 잔액 `100` 유지 |
| 소수 부분이 있는 충전액 거절 | 잔액 `100`에 `100.9` 또는 `1.009e2` 충전 | `400 Bad Request`, 잔액 `100` 유지 |
| 정수 값의 숫자 표기 | 잔액 `100`에 `100.0` 또는 `1e2` 충전 | `200 OK`, 정확히 `100` 충전해 잔액 `200` |
| 충전 요청 입력 오류 | 잔액 `100`, `amount` 누락, 정수가 아닌 값 또는 처리 가능한 수치 범위 밖 값 | `400 Bad Request`, 잔액 `100` 유지 |
| 잔액 합산 한도 초과 | 시스템이 저장할 수 있는 최대 잔액에 `1`포인트 충전 | `400 Bad Request`, 기존 잔액 유지 |
| 동시 충전 | 잔액 `100`에 서로 다른 요청이 각각 `200`, `300` 충전 | 두 요청 모두 성공, 원자적 증가 결과 잔액 `600` |

## 상품 단독 삭제 기대값

| 규칙 | 주어진 상태·입력 | 기대값 |
|---|---|---|
| 활성 Product 삭제 | 활성 Brand에 연결된 Product 삭제 요청 | 성공, Product만 논리 삭제되고 Brand는 활성 상태 유지 |
| 재고 수량 보존 | 재고가 0 또는 양수인 Product 삭제 요청 | 삭제 성공, 재고 수량은 변경되지 않음 |
| 기존 관계·주문 보존 | Product에 Like 또는 기존 Order·OrderItem이 존재 | 삭제 후에도 Like 관계와 주문 스냅샷을 유지하고, 기존 Like 취소 허용 |
| 이미 삭제된 Product | 삭제된 Product에 삭제 요청 | `404 Not Found`, 기존 삭제 상태와 삭제 시각 유지 |
| 삭제 후 변경 시도 | 삭제된 Product의 수정·재고 변경 요청 | `404 Not Found`, Product 상태와 재고 유지 |
| 동일 Product 삭제 경쟁 | 같은 Product에 두 삭제 요청 실행, 한 요청이 commit | `200` 한 건, 다른 요청은 `404`, 기존 삭제 시각 유지 |
| 수정이나 재고 설정 후 삭제 | 수정 또는 재고 설정 완료 후 삭제 요청 | 삭제 성공, 변경된 이름과 가격 또는 재고 보존 |
| 삭제 후 수정이나 재고 설정 | 삭제 완료 후 수정 또는 재고 설정 요청 | `404`, 기존 상품 정보와 재고 및 삭제 상태 유지 |

