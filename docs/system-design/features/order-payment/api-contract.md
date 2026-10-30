# 주문·PG 결제 API 계약

기존 절 번호 참조: [4.3](processing.md#43-주문-확정).

기존 문서의 계약·절 번호·승인/검증 상태를 보존해 이동했다. 재편 자체는 제품 계약 변경이나 새 실행 검증을 뜻하지 않는다.

PG 응답의 선택 이유는 [A.24](../../decisions/payment.md#a24-결제-상태확인-상태금액의-공개-표현), HTTP·오류는 [A.25](../../decisions/payment.md#a25-http오류멱등-응답의-의미), 식별자·입력은 [A.26](../../decisions/payment.md#a26-식별자합성-입력외부-정보의-노출-범위)에 대안·비용·재검토 조건을 기록한다.

## 5.1 주문 입력·DTO·금액 규칙

### 주문 수량 입력 정책 (5.1)

|항목|잠정 기준|잘못된 입력|
|---|---|---|
|주문 수량|품목별 1 이상의 정수|`400 INVALID_ORDER_QUANTITY`|

### 주문 금액 규칙

- 상품 가격·저장 단가·품목 금액·주문 총액·성공 결제액은 도메인 내부에서 `Money(long 원)`로 표현한다. Money는 0을 허용하고 음수는 거절한다. 덧셈·수량 곱셈 overflow는 기존 `400 NUMERIC_OVERFLOW`, 상품 가격 범위 오류는 `400 INVALID_PRODUCT_PRICE`를 유지한다.
- 주문 생성 요청은 상품 ID·수량만 받는다. 활성 Product의 생성 당시 단가를 OrderItem에 저장하고 `수량 × 저장 단가`의 합으로 총액을 정한다. 이후 상품 가격으로 재계산하지 않는다.
- DRAFT 생성은 포인트·재고를 변경하지 않는다. confirm에서 저장 총액으로 PG 시도를 준비하며 PG 요청 금액을 고객 입력으로 받지 않는다. PG 호출 전 가용 재고를 선차감·예약한다.
- 신규 PG 주문은 포인트를 사용하지 않는다. PG 시도가 있으면 `pointAmount=0`이며 Point 잔액과 ORDER_USE 이력에 변화가 없다. Point 미생성·잔액 부족은 PG 결제 거절 사유가 아니다.
- 이전 POINT 주문의 실제 pointAmount·paidAmount·결과는 보존한다. 기존 전액 POINT 결제 기록에서는 두 수치와 주문 총액이 같지만 이를 신규 PG 결제 규칙으로 사용하지 않는다.
- PG 성공은 내부 주문 CONFIRMED로 반영하고 실제 성공 금액을 paidAmount로 반환한다. PG 대기·실패의 paidAmount는 null로 두어 JSON에서 생략한다. PG 시도의 요청 금액은 payment.amount로 별도 반환한다.

### 주문 요청 DTO (5.1)

|요청 모델|필드|
|---|---|
|`OrderItemRequest`|`productId: Long`, `quantity: Long`|
|`OrderCreateRequest`|`items: List<OrderItemRequest>`|
|`OrderConfirmRequest`|`paymentAttemptKey: String`, `cardType: String`, `cardNo: String`|
|`PaymentCallbackRequest`|`providerOrderId: String`, `transactionKey: String`, `status: String`|

### 주문 응답 DTO (5.1)

|응답 모델|필드|
|---|---|
|`OrderItemResponse`|`productId: Long`, `quantity: Long`, `unitPrice: Long`, `amount: Long`|
|`OrderResponse`|`id: Long`, `status: String`, `totalAmount: Long`, `pointAmount: Long?`, `paidAmount: Long?`, `payment: OrderPaymentResponse?`, `items: List<OrderItemResponse>`|
|`AdminOrderItemResponse`|`productId: Long`, `quantity: Long`, `unitPrice: Long`, `amount: Long`|
|`AdminOrderResponse`|`id: Long`, `userId: Long`, `status: String`, `totalAmount: Long`, `pointAmount: Long?`, `paidAmount: Long?`, `payment: AdminOrderPaymentResponse?`, `items: List<AdminOrderItemResponse>`|

`OrderPaymentResponse`와 `AdminOrderPaymentResponse`는 동일한 의미의 결제 정보를 별도 DTO 경계로 선언한다. PG 결제의 pointAmount는 0이며 payment 객체는 다음 필드를 사용한다. payment와 payment_conflict의 저장 분리는 응답 필드 추가나 상시 조인을 뜻하지 않으며 paymentAttemptKey라는 공개 필드명은 유지한다. 외부 transactionKey·providerOrderId와 합성 카드 번호는 고객·관리자 응답에 노출하지 않는다.

|payment 필드|타입|의미|
|---|---|---|
|method|String|PG 또는 POINT|
|status|String|PG는 UNKNOWN·PENDING·SUCCESS·FAILED, POINT 완료는 SUCCESS. PG 응답 전 준비 구간도 UNKNOWN|
|paymentAttemptKey|String?|PG 시도의 UUID 키. POINT에는 생성하지 않음|
|amount|Long?|PG에 요청한 저장 금액. POINT에는 추가하지 않음|
|orderUrl|String|저장 주문 조회의 상대 경로|
|reconcileUrl|String?|고객의 기존 PG 거래 대사 상대 경로. POINT에는 없음. 관리자에도 고객용 대사 경로를 제공하며 실행 권한을 추가하지 않음|
|verificationStatus|String?|PG의 마지막 확인 결과. NOT_CHECKED·VERIFIED·UNAVAILABLE·CONFLICT. POINT에는 없음|

DRAFT는 payment=null로 생략한다. PG 시도가 있는 주문은 위 PG 필드와 결제 상태를 반환한다. 이전 POINT 주문은 method=POINT·status=SUCCESS·orderUrl만 반환하고 과거 시도 키·PG 거래를 꾸며 만들지 않는다. verificationStatus는 payment.status와 별개다. NOT_CHECKED는 확인 전, VERIFIED는 유효한 PG 관찰 확인, UNAVAILABLE은 응답 유실·조회 불가·0건, CONFLICT는 복수 거래·식별/금액·종결 충돌을 뜻한다. PENDING 상태에서 조회에 실패해도 PENDING을 실패로 바꾸지 않고 verificationStatus로 확인 불가를 표현한다. TX A commit 뒤 PG 응답 전은 status=UNKNOWN·verificationStatus=NOT_CHECKED다. UNKNOWN만으로 발송 여부·timeout 발생을 단정하지 않는다. 공개 REQUESTED 상태는 추가하지 않는다. 최근 확인은 [3.6](model.md#36-pg-저장-모델과-제약)에 따라 DB에 마지막 반영한 관찰이다. 먼저 시작한 조회가 늦게 도착해 확인 정보를 갱신할 수 있으며, 결제 종결 보호와 무시할 늦은 관찰 규칙은 별도로 유지한다.

고객 orderUrl은 `/api/v1/orders/{orderId}`, 관리자 orderUrl은 `/api-admin/v1/orders/{orderId}`다. PG 주문의 reconcileUrl은 두 응답 모두 `/api/v1/orders/{orderId}/payment/reconcile`이다. URL 제공은 권한 부여가 아니며 대사 요청에는 고객 식별·주문 소유권 규칙을 그대로 적용한다.

`pointAmount`와 `paidAmount`는 결제 전 `DRAFT`의 응답 객체에서 null이며 JSON에서 생략한다. PG 시도가 있는 주문의 pointAmount는 0이고, 이전 POINT 주문은 저장된 실제 값을 반환한다. PG의 paidAmount는 성공 시에만 저장한 금액을 반환하고 대기·실패에서는 생략한다. 상품 이름은 OrderItem의 주문 시점 스냅샷으로 저장하지 않으므로 주문 응답은 상품 식별자만 제공하며, 현재 Product 이름을 주문 당시 정보처럼 조합하지 않는다.

### 주문 API별 data 타입 (5.1)

|API|data 타입|
|---|---|
|주문 생성·결제 시작·대사·내 주문 상세|`OrderResponse`|
|PG 콜백 수신|`null` — JSON data 생략|
|내 주문 목록|`PageResponse<OrderResponse>`|
|관리자 전체 주문 목록|`PageResponse<AdminOrderResponse>`|
|관리자 주문 상세|`AdminOrderResponse`|

## 5.2 주문·PG 고객 API

### 주문

|기능|Method & Path|입력|결과|대표 오류|
|---|---|---|---|---|
|주문 생성|`POST /api/v1/orders`|`items[{productId, quantity}]`|`201`, DRAFT·저장 품목·총액|INVALID_ORDER_ITEMS, INVALID_ORDER_QUANTITY, PRODUCT_NOT_FOUND, NUMERIC_OVERFLOW|
|결제 시작|`POST /api/v1/orders/{orderId}/confirm`|주문 ID, paymentAttemptKey·합성 카드|검증된 최종 결과/확실한 로컬 미발송 실패는 `200 CONFIRMED/PAYMENT_FAILED`, 대기·미확인은 `202`, TX A 전 회로 차단은 `503 PG_CREATE_UNAVAILABLE`|ORDER_NOT_FOUND, ORDER_NOT_CONFIRMABLE, PRODUCT_NOT_FOUND, INSUFFICIENT_STOCK, INVALID_REQUEST, PAYMENT_ATTEMPT_CONFLICT, PAYMENT_RESULT_CONFLICT, PG_CREATE_UNAVAILABLE|
|내 주문 목록|`GET /api/v1/orders`|선택적 page·size·sort|`200`, 본인의 주문·품목·금액·결제 페이지|INVALID_PAGE_REQUEST, INVALID_SORT|
|내 주문 상세|`GET /api/v1/orders/{orderId}`|주문 ID|`200`, 저장된 주문·품목·금액·결제 정보|ORDER_NOT_FOUND|
|기존 결제 대사|`POST /api/v1/orders/{orderId}/payment/reconcile`|주문 ID, 추가 본문 없음|기존 거래 조회·검증·반영. 미완료 `202`, 동일 시도 완료 결과 `200`|ORDER_NOT_FOUND, PAYMENT_RESULT_CONFLICT|

- 고객은 기존 X-USER-ID와 User 존재·소유권 규칙을 따른다. 없는 주문·다른 사용자의 주문은 모두 404 ORDER_NOT_FOUND다. Point 존재·잔액 검사는 PG 결제에 적용하지 않는다.
- 생성은 각 품목의 양수·합산 overflow를 검증해 중복 상품 수량을 병합하고, 활성 Product 전체 존재와 저장 단가·총액을 확인해 DRAFT를 저장한다. Product·재고·Point는 변경하지 않는다.
- confirm은 저장된 품목·수량·단가·총액으로 재고·시도를 준비한다. 신규 PG 시도의 대기·미확인·성공·실패와 포인트 미사용을 조회에 반영한다. 최초 재고 확보 전 삭제·재고 부족이면 PG 0회이고 TX A 전체를 rollback한다.
- 같은 주문·같은 키의 동시 요청은 실제 PG create 1회만 허용한다. 같은 키를 다른 주문·금액·요청에 사용하거나 같은 주문을 새 키로 다시 결제하려는 요청은 거절한다. 키는 8-4-4-4-12자리 16진 UUID 문자열로 받고 UUID 동일값 기준으로 비교한다. 대소문자는 같은 값으로 정규화한다. cardType은 TEST, cardNo는 0000-0000-0000-0000만 허용한다. 누락·허용하지 않는 값은 400 INVALID_REQUEST다. 금액·callback URL은 서버가 결정한다.
- 동일 PG 시도의 종결 재요청은 저장 결과를 반환하고 create·재고 효과를 추가하지 않는다. 기존 POINT 확정 주문은 409 ORDER_NOT_CONFIRMABLE을 유지한다. 최초 요청에서 조회 FAILED·거래 생성 전 확정 거절 또는 TX A 후 실제 생성 회로 거절·생성 POST의 확실한 최초 풀 획득 timeout으로 확실한 미발송을 확인한 경우도 HTTP 200으로 PAYMENT_FAILED·payment.status=FAILED를 반환한다. 이것은 요청 입력·시도 충돌의 4xx와 구분한다. 로컬 미발송은 PG의 거절/확인 성공으로 표시하지 않으며 기존 확인 정보를 유지한다. 최초에는 verificationStatus=NOT_CHECKED·checkedAt 생략인 FAILED가 가능하다.
- 주문 GET은 마지막 확인 결과와 무관하게 저장 상태를 HTTP 200으로 반환하고 PG를 호출하지 않는다. 대사는 이미 SUCCESS·FAILED로 종결한 주문도 PG를 다시 조회·검증한다. transactionKey가 없으면 providerOrderId로 조회한다. 조회 0건·PENDING·조회 불가에 새 create·확정 실패·임의 예약 반환을 연결하지 않는다.
- 대사 재조회가 실패하면 저장된 종결 상태도 유지하고 verificationStatus=UNAVAILABLE·HTTP 202로 이번 확인 불가를 표현한다. 이미 CONFIRMED이면 응답의 주문 상태도 CONFIRMED이며, HTTP 202를 주문의 결제 실패·종결 취소로 해석하지 않는다. 같은 종결 결과를 재검증하면 200, 다른 종결 결과이면 기존 상태를 보존하고 409 PAYMENT_RESULT_CONFLICT다. 이미 종결한 뒤 시작한 권위 조회의 PENDING도 CONFLICT·409다. 미종결 때 시작한 조회의 PENDING이 다른 경로의 종결 후 도착하면 늦은 관찰로 무시하고 저장 종결을 200으로 반환한다.
- 이미 재고를 확보한 주문은 이후 상품 삭제와 구분해 결과를 마무리한다. 순서·저장 결과·중복 보호는 [주문 확정 흐름](processing.md#43-주문-확정)을 따른다.

### PG 콜백 수신

`POST /api/v1/payments/callback`은 `{providerOrderId, transactionKey, status}`를 받는다. 고객 헤더를 요구하는 주문 API와 구분하며, 고객이 임의 주문을 완료시키는 API로 사용하지 않는다.

고정한 PG adapter로 거래를 조회하고 제공자 범위·주문·transactionKey·저장 요청 금액을 검증한 뒤 공통 결과 반영을 호출한다. 본문의 status는 알림이며 성공의 근거가 아니다. 중복·지연·순서 변경에도 한 번만 확정·반환하고, 종결 결과와 다른 알림·조회 결과가 와도 기존 결과를 덮어쓰지 않는다.

내부 준비는 별도 readOnly TX의 후보 orderId 스칼라 조회를 끝낸 뒤, 새 업무 TX에서 Order를 먼저 잠그고 Payment를 일반 조회하는 순서다. 후보 0건/복수는 각각 404/500이며 임의 선택·PG 호출을 하지 않는다. 후보 조회의 상태/Entity를 업무 판단에 재사용하지 않고 잠금 뒤 외부 ID 매핑과 종결 여부를 재검증한다. 후보 식별 뒤 다른 TX가 종결했다면 새 확인 준비의 종결 상태로 terminalAtStart를 정한다. 상세 수명·경쟁 검증은 4.3·4.5를 따른다.

정상 수신 후 검증·종결 반영이 끝났거나 동일 종결 결과를 재확인하면 HTTP 200, 미종결 PENDING·미확인·조회 불가이면 202를 반환한다. 종결 전 시작한 늦은 PENDING의 200 무시와 종결 후 시작한 PENDING의 409 규칙도 대사와 동일하다. 두 응답 모두 기존 ApiResponse를 사용하고 data는 생략한다. 잘못된 필드 값·필수 값 누락은 400 INVALID_REQUEST, 없는 주문·시도는 404 ORDER_NOT_FOUND, 식별·금액·종결 결과 충돌은 409 PAYMENT_RESULT_CONFLICT, 내부 DB 실패는 기존 500 Internal Server Error다. 알림 status와 권위 조회 status가 다르면 알림을 최종 근거로 사용하지 않는다. PG의 콜백 자동 재전송을 가정하지 않으며, 미완료 시도는 고객의 reconcile로 확인 가능해야 한다.

### PG 결제 입력·오류 계약

|조건|HTTP·오류 코드|효과|
|---|---|---|
|본문 필드 누락·유효하지 않은 UUID·고정 합성 카드 외 값|400 INVALID_REQUEST|재고·시도 변경과 PG 호출 없음|
|없는 주문·다른 고객 소유 주문·없는 PG 시도|404 ORDER_NOT_FOUND|해당 자원 정보·변경 없음|
|이미 POINT로 확정한 주문 confirm|409 ORDER_NOT_CONFIRMABLE|과거 POINT 사실 보존|
|같은 키의 다른 요청·같은 주문의 새 키|409 PAYMENT_ATTEMPT_CONFLICT|두 번째 결제 행·재고 차감·PG 생성 없음|
|TX A 전 생성 회로 차단|503 PG_CREATE_UNAVAILABLE|DRAFT·결제 행 없음·재고/이력 변경 0·POST 0. 회복 후 고객 confirm은 첫 시도 생성 가능|
|TX A 후 실제 생성 회로의 확실한 미발송|200, 주문 PAYMENT_FAILED·결제 FAILED|CIRCUIT_REJECTED 원인·거래 키 없음·반환 한 번, PG 확인 정보 유지. 신규 상태/취소/환불 API 없음|
|TX A 후 생성 POST의 확실한 최초 풀 획득 timeout|200, 주문 PAYMENT_FAILED·결제 FAILED|POOL_ACQUIRE_TIMEOUT 원인·거래 키 없음·반환 한 번, PG 확인 정보 유지. 연결/읽기 timeout·후속 풀 오류는 해당 없음(기존 202 미확인)|
|파싱 가능한 거래·주문·금액 불일치, 복수 거래, 상충 종결 결과|409 PAYMENT_RESULT_CONFLICT|기존 종결 덮어쓰기·임의 예약 반환 없음|
|재고 부족·결제 시작 전 삭제 상품|409 INSUFFICIENT_STOCK 또는 404 PRODUCT_NOT_FOUND|TX A 전체 rollback·PG 0회|
|PG의 검증된 확정 실패|200, 주문 PAYMENT_FAILED·결제 FAILED|예약 한 번 반환·pointAmount=0|
|내부 DB·commit 기술 실패|500 Internal Server Error|commit 전 실패는 해당 TX rollback. commit 결과 미확인은 저장 상태를 다시 확인하며, 외부 효과·이미 commit한 TX A를 취소했다고 가정하지 않음|

INVALID_REQUEST는 유효한 JSON의 필수 필드·값 규칙 위반에 사용한다. JSON 해석·숫자 경로 변환 오류는 기존 Advice의 400 Bad Request를 유지한다. 기존 고객 식별·소유권과 대상 조회 우선 규칙을 보존하고, 이전 POINT 확정 재요청에 새로운 합성 카드 입력 검증을 먼저 적용해 기존 상태 오류를 바꾸지 않는다.

충돌은 payment_conflict, 마지막 확인 정보는 payment에 같은 TX로 저장하고 commit 뒤 409를 반환한다. 상태·저장 설계의 마지막 관찰 반영·원자성 계약을 따르며 요약 저장 실패는 rollback·500이다. 일반 주문 조회는 충돌 상세를 읽지 않는다.

### 결제 시작 요청·응답 예시

다음은 주문 42의 저장 총액이 7,000원인 경우다. 요청에 금액·callback URL을 받지 않는다.

```json
{
  "paymentAttemptKey": "6ce3c471-12ba-4ab4-bbc8-e701cf378e10",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000"
}
```

PG가 생성 수락 PENDING을 반환한 뒤의 커머스 응답은 HTTP 202다. paidAmount는 성공 금액이 아직 없으므로 생략한다.

```json
{
  "meta": {"result": "SUCCESS"},
  "data": {
    "id": 42,
    "status": "PAYMENT_PENDING",
    "totalAmount": 7000,
    "pointAmount": 0,
    "payment": {
      "method": "PG",
      "status": "PENDING",
      "paymentAttemptKey": "6ce3c471-12ba-4ab4-bbc8-e701cf378e10",
      "amount": 7000,
      "orderUrl": "/api/v1/orders/42",
      "reconcileUrl": "/api/v1/orders/42/payment/reconcile",
      "verificationStatus": "VERIFIED"
    },
    "items": [{"productId": 7, "quantity": 2, "unitPrice": 3500, "amount": 7000}]
  }
}
```

|상황|HTTP|주문 status|payment.status|verificationStatus|paidAmount|
|---|---|---|---|---|---|
|TX A 전 생성 회로 차단|confirm 503|DRAFT|생략|생략|생략|
|TX A 후 실제 회로 거절·최초 풀 획득 timeout의 확실한 미발송|confirm 200|PAYMENT_FAILED|FAILED|기존값 유지, 최초 NOT_CHECKED|생략|
|준비 commit 후 PG 응답 전인 저장 상태|GET은 200, 동일 시도 confirm은 202|PAYMENT_PENDING|UNKNOWN|NOT_CHECKED|생략|
|생성 응답 timeout·유실|202|PAYMENT_PENDING|UNKNOWN|UNAVAILABLE|생략|
|유효한 PENDING 확인|202|PAYMENT_PENDING|PENDING|VERIFIED|생략|
|검증된 SUCCESS 반영 commit|200|CONFIRMED|SUCCESS|VERIFIED|7000|
|검증된 FAILED 또는 효과 0건 거절 반영|200|PAYMENT_FAILED|FAILED|VERIFIED|생략|
|기존 PENDING의 대사 조회 실패|202|PAYMENT_PENDING|PENDING|UNAVAILABLE|생략|
|기존 CONFIRMED의 대사 조회 실패|202|CONFIRMED|SUCCESS|UNAVAILABLE|7000 유지|
|기존 PAYMENT_FAILED의 대사 조회 실패|202|PAYMENT_FAILED|FAILED|UNAVAILABLE|생략|

주문 GET은 같은 저장 상태를 200으로 반환한다. 이전 POINT 완료 주문은 기존 pointAmount·paidAmount를 유지하고 payment에는 method=POINT·status=SUCCESS·orderUrl만 포함한다. 카드·외부 거래 키·외부 주문 ID는 위 응답에 포함하지 않는다.

## 5.3 관리자 주문 API

### 주문 조회

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|전체 주문 목록|`GET /api-admin/v1/orders`|선택적 `page`, `size`, `sort`|`200`, 구매자·품목·금액·상태·결제액을 포함한 전체 주문 페이지|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|주문 상세|`GET /api-admin/v1/orders/{orderId}`|주문 ID|`200`, 구매자·품목·상태·주문 총액·포인트 사용액·결제액|`ORDER_NOT_FOUND`|

관리자는 주문을 조회만 하며 상태를 변경하지 않는다. 고객 응답과 같은 의미의 payment 객체로 결제수단·시도 상태·최종 결과를 확인하고, 이전 POINT 결제 사실과 구매자 구분을 유지한다. 관리자 응답에도 고객용 reconcileUrl을 제공하지만, 대사를 수행하는 고객의 식별·소유권 검사를 생략하거나 관리자에게 대사 권한을 추가하지 않는다.

## 5.4 주문·결제 기대값

### 주문·결제 기대값

|규칙|주어진 상태와 요청|기대 결과|
|---|---|---|
|주문 목록의 품목|품목이 여러 개인 주문을 포함한 내 주문 또는 관리자 주문 목록 조회|Order 단위로 페이지를 나누고 해당 주문의 모든 OrderItem을 포함|
|중복 주문 품목 합산|같은 상품을 수량 2와 3으로 요청|수량 5인 OrderItem 하나로 저장하고 총수량 기준으로 금액 계산|
|주문 생성과 차감 분리|유효한 여러 품목으로 주문 생성|`DRAFT` 저장, 재고와 포인트는 유지|
|DRAFT 주문 결제 정보|`DRAFT` 주문 상세 조회|주문 총액·DRAFT 상태 반환, 포인트 사용액과 결제액은 내부 null이며 JSON에서는 생략|
|PG 결제 성공|총액 7,000, 충분한 재고, 검증된 SUCCESS|CONFIRMED·PG 성공 사실, 재고 추가 차감 없음, 포인트 변화·ORDER_USE 0, pointAmount=0|
|PG와 포인트 분리|Point 미생성 또는 잔액이 주문 총액보다 적음|PG 결제를 해당 사유로 거절하지 않음. 재고·PG 조건으로 판단하며 포인트 변화 없음|
|재고 부족|한 품목의 가용 재고가 주문 수량보다 적음|409 INSUFFICIENT_STOCK, TX A 전체 rollback·PG 0회·예약/차감 없음|
|동일 PG 시도 종결 재요청|PG로 이미 종결한 동일 시도 confirm|저장 결과 200, create·확정·재고 효과 추가 없음|
|이전 POINT 확정 재요청|POINT로 이미 CONFIRMED인 주문 confirm|409 ORDER_NOT_CONFIRMABLE, 과거 사실 유지|
|재고 선차감과 PENDING|재고 5, 수량 2, PG 202 PENDING|가용 3·예약 2·주문 PAYMENT_PENDING, 포인트 변화 없음|
|생성 응답 유실|거래 생성 후 응답 유실|초기 관찰 UNKNOWN·예약 유지, create 추가 0·기존 거래 대사|
|확정 실패 반환|검증한 FAILED 또는 효과 0건 확정 거절|예약 한 번 반환. 재고 3→5이며 중복 반영 후에도 5|
|PG 성공 뒤 내부 실패|SUCCESS 뒤 TX B rollback|외부 성공은 유지, 같은 거래 대사로 재반영·create 추가 0|
|콜백과 대사 경쟁|같은 거래의 결과를 순서 변경·동시 적용|추가 차감·중복 반환·종결 상태 후퇴 없음|
|브랜드 삭제 후 DRAFT 확정|삭제 전 만든 DRAFT의 상품이 Brand 일괄 삭제되어 commit됨, PG 시도 시작 전|`404 PRODUCT_NOT_FOUND`, 해당 요청의 차감·확정·History 없음, 저장 품목·금액 보존|
