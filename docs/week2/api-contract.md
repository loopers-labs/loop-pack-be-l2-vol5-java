# commerce-api API 계약



## 공통 규칙

### 요청자 식별


| 대상                              | 식별                | 실패하면                                                                                           |
| ------------------------------- | ----------------- | ---------------------------------------------------------------------------------------------- |
| [고객 API](#고객-api) | `X-USER-ID` 요청 헤더 | `401 USER_NOT_IDENTIFIED`. 헤더가 없거나, 숫자가 아니거나, 없는 사용자이면 같은 결과로 거절한다.  |
| `/api-admin/v1/**`              | `ADMIN` 역할        | `403`. 관리자 접근 필터가 거절하며 컨트롤러에 닿지 않는다. 응답 본문은 [공통 응답 형식](#응답-형식)을 따르지 않는다.          |




### 목록

목록을 돌려주는 요청은 모두 같은 페이지 규칙을 쓴다.


| 항목                 | 규칙                                                 |
| ------------------ | -------------------------------------------------- |
| `page`             | 0부터 시작한다. 기본값은 0이다. 음수이면 `400 INVALID_REQUEST`     |
| `size`             | 1~100이다. 기본값은 20이다. 범위를 벗어나면 `400 INVALID_REQUEST` |
| 마지막 페이지를 넘는 `page` | 빈 목록을 돌려준다.                                        |
| 응답                 | [목록 응답](#목록-응답)을 따른다.                              |
| 순서                 | 요청마다 적는다. 순서 기준이 같으면 식별자 오름차순으로 정한다.               |




## 고객 API



### C-01. 브랜드 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, 고객 브랜드 |
| 오류 | `400 INVALID_REQUEST` · `404 BRAND_NOT_FOUND` |


### C-02. 상품 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/products` |
| 입력 | query `brandId`(선택), `sort`(`latest`·`price_asc`·`likes_desc`, 기본 `latest`), `page`, `size` |
| 성공 | `200`, 고객 상품 목록. 삭제되지 않은 상품만 담는다. |
| 순서 | `latest`는 등록 최신순, `price_asc`는 가격 오름차순, `likes_desc`는 좋아요 수 내림차순. 같으면 상품 식별자 오름차순 |
| 오류 | `400 INVALID_REQUEST` |
| 규칙 | 없거나 삭제된 `brandId`로 거르면 빈 목록을 돌려준다. |


### C-03. 상품 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, 고객 상품 |
| 오류 | `400 INVALID_REQUEST` · `404 PRODUCT_NOT_FOUND` |


### C-04. 좋아요 등록


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/products/{productId}/likes` |
| 입력 | path `productId` |
| 성공 | `201`, `data` 없음. 이미 좋아요한 상품이면 새로 만들지 않고 `200`이며, 좋아요 수는 그대로다. |
| 오류 | `400 INVALID_REQUEST` · `404 PRODUCT_NOT_FOUND` |


### C-05. 좋아요 취소


| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api/v1/products/{productId}/likes` |
| 입력 | path `productId` |
| 성공 | `200`, `data` 없음.  |
| 오류 | `400 INVALID_REQUEST` |


### C-06. 내 좋아요 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/users/{userId}/likes` |
| 입력 | path `userId`, query `page`, `size` |
| 성공 | `200`, 고객 상품 목록. 삭제된 상품은 담지 않는다. |
| 순서 | 좋아요한 시점 최신순 |
| 오류 | `400 INVALID_REQUEST` · `404 USER_NOT_FOUND` |


### C-07. 포인트 충전


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/points/charge` |
| 입력 | body `{ "amount": 10000 }`. `amount`는 양의 정수 |
| 성공 | `200`, `data`는 `{ "balance": 충전 후 잔액 }` |
| 오류 | `400 INVALID_REQUEST` · `400 INVALID_CHARGE_AMOUNT` · `409 POINT_BALANCE_LIMIT_EXCEEDED` |


### C-08. 내 잔액 조회


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/points` |
| 입력 | 없음 |
| 성공 | `200`, `data`는 `{ "balance": 저장된 잔액 }`. 충전한 적이 없으면 0이다. |
| 오류 | 공통 오류 외에는 없다. |


### C-09. 주문 생성


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/orders` |
| 입력 | body `{ "items": [ { "productId": 1, "quantity": 2 } ] }` |
| 성공 | `201`, 주문 상세 `id`, `status=DRAFT`, `items`(상품 ID·이름, 수량, 단가, 품목 금액), `totalAmount`. 결제 전이라 `payment`는 없고 재고·잔액은 그대로다. |
| 오류 | `400 INVALID_REQUEST` · `400 EMPTY_ORDER_ITEMS` · `400 INVALID_ORDER_QUANTITY` · `404 PRODUCT_NOT_FOUND` |




### C-10. 주문 확정 — PG 결제 요청


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/orders/{orderId}/confirm` |
| 입력 | path `orderId`. body가 없으면 기존 포인트 결제로 확정한다. PG 결제는 body `paymentKey`, `paymentAttemptKey`를 받으며, `paymentKey`는 클라이언트가 PG에서 받은 결제 키이고 `paymentAttemptKey`는 같은 승인 재요청에 재사용한다. |
| 성공 | `200`, `data`는 주문과 재고 예약을 확정한 결과. 주문 상태는 `CONFIRMED`이며 결제액·결제 시점을 담는다. |
| 대기 | PG 처리가 `PENDING`이면 `202`, `meta.result=PENDING`. `data`는 `orderStatus=DRAFT`, `paymentStatus=PENDING`, `statusUrl`(`/api/v1/orders/{orderId}`)을 담는다. |
| 오류 | `400 INVALID_REQUEST` · `404 ORDER_NOT_FOUND` · `409 ORDER_ALREADY_CONFIRMED` · `409 PRODUCT_NOT_AVAILABLE` · `409 INSUFFICIENT_STOCK` · `409 INSUFFICIENT_POINT` · `409 PAYMENT_ALREADY_PROCESSED` · `409 POINT_CONFLICT` · `422 PAYMENT_FAILED` · `503 PAYMENT_CANCELLATION_PENDING` · `500 INTERNAL_ERROR` |





### C-11. 내 주문 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/orders` |
| 입력 | query `page`, `size` |
| 성공 | `200`, 요청자의 주문 요약 목록. 각 항목은 `id`, `status`, `itemCount`, `totalAmount`를 담고, `CONFIRMED`일 때만 `paymentAmount`를 담는다. |
| 순서 | 주문 생성 최신순 |
| 오류 | `400 INVALID_REQUEST` |


### C-12. 내 주문 상세

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/orders/{orderId}` |
| 입력 | path `orderId` |
| 성공 | `200`, `meta.result=SUCCESS`. `data`는 아래 주문 상세를 담는다.  |
| 오류 | `400 INVALID_REQUEST` · `404 ORDER_NOT_FOUND` |

`data` 필드:

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `id` | 정수 | 주문 ID |
| `status` | 문자열 | 주문 상태. `DRAFT`, `CONFIRMED` 또는 `CANCELED` |
| `items` | 배열 | 주문 당시 품목 정보 |
| `items[].productId` | 정수 | 상품 ID |
| `items[].productName` | 문자열 | 주문 당시 상품 이름 |
| `items[].quantity` | 정수 | 주문 수량 |
| `items[].unitPrice` | 정수 | 주문 당시 단가(원) |
| `items[].amount` | 정수 | 품목 금액(원) |
| `totalAmount` | 정수 | 주문 총액(원) |
| `payment` | 객체 또는 `null` | 결제 정보. 결제 시작 전에는 `null` |
| `payment.method` | 문자열 | 결제수단. 신규 결제는 `PG`, 이전 포인트 결제는 `POINT` |
| `payment.status` | 문자열 | 결제 상태. `PENDING`, `UNKNOWN`, `SUCCESS`, `FAILED`, `CANCEL_PROCESSING`, `CANCELED` |
| `payment.amount` | 정수 | 결제 요청 금액(원). 이전 포인트 결제는 당시 결제액 |
| `payment.paidAt` | 날짜·시간 문자열 또는 `null` | 결제 성공 시각. 성공 전에는 `null` |

주문·결제 상태 조합:

| 상황 | `data.status` | `data.payment.status` |
| --- | --- | --- |
| 결제 시작 전 | `DRAFT` | 없음 (`payment: null`) |
| 결제 처리 중 | `DRAFT` | `PENDING` |
| 결제 결과 미확인 후 취소 요청 | 주문 상태는 이 처리로 변경하지 않음 | `CANCEL_PROCESSING` 또는 `CANCELED` |
| 결제 성공 반영 완료 | `CONFIRMED` | `SUCCESS` |
| 결제 실패 확정 | `DRAFT` | `FAILED` |



### C-13. 결제 결과 대사

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/orders/{orderId}/payment/reconcile` |
| 입력 | path `orderId`. 요청 본문 없음 |
| 성공 | `200`, `meta.result=SUCCESS`. `data`에 `orderStatus`, `paymentStatus`를 반환한다. 결제 실패를 정상 반영한 경우에도 `200`이며 `paymentStatus=FAILED`이다. |
| 대기 | `202`, `meta.result=PENDING`. `data`에 `orderStatus`, `paymentStatus`, `statusUrl`을 반환한다. |
| 오류 | `400 INVALID_REQUEST` · `404 ORDER_NOT_FOUND` |

`data` 필드:

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `orderStatus` | 문자열 | 반영 후 주문 상태. `DRAFT`, `CONFIRMED` 또는 `CANCELED` |
| `paymentStatus` | 문자열 | 결제 상태. `200`이면 `SUCCESS` 또는 `FAILED`, `202`이면 `PENDING` 또는 `UNKNOWN` |
| `statusUrl` | 문자열 | `202`에서만 반환하는 주문 상태 조회 경로. `/api/v1/orders/{orderId}` |

`202` 응답의 `data` 예시:

```json
{
  "orderStatus": "DRAFT",
  "paymentStatus": "UNKNOWN",
  "statusUrl": "/api/v1/orders/42"
}
```

## PG 콜백 API

### 결제 결과 알림

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/payments/callback` |
| 입력 | body `providerOrderId`, `transactionKey`, `status`. 고객 식별용 `X-USER-ID`는 요구하지 않는다. |
| 성공 | `200`, `meta.result=SUCCESS`, `data=null`. PG 조회로 검증한 성공·실패 결과를 반영했거나 이미 반영한 동일 결과의 중복 알림인 경우 |
| 대기 | `202`, `meta.result=PENDING`, `data=null`. PG 처리 중이거나 조회 불가로 결과를 확정하지 못한 경우 |
| 오류 | `400 INVALID_REQUEST` · `404 PAYMENT_ATTEMPT_NOT_FOUND` · `409 PAYMENT_RESULT_CONFLICT` |

요청 본문:

| 필드 | 타입 | 의미 |
| --- | --- | --- |
| `providerOrderId` | 문자열 | PG에 전달한 우리 주문 식별자 |
| `transactionKey` | 문자열 | PG가 발급한 거래 식별자 |
| `status` | 문자열 | PG가 알린 상태. `PENDING`, `SUCCESS`, `FAILED` |


## 관리자 API



### A-01. 브랜드 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/brands` |
| 입력 | query `page`, `size` |
| 성공 | `200`, 관리자 브랜드 목록. 삭제된 브랜드도 담는다. |
| 순서 | 등록 최신순 |
| 오류 | `400 INVALID_REQUEST` |


### A-02. 브랜드 생성


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api-admin/v1/brands` |
| 입력 | body `{ "name": "브랜드" }` |
| 성공 | `201`, 관리자 브랜드 |
| 오류 | `400 INVALID_REQUEST` · `400 INVALID_BRAND_NAME` · `409 DUPLICATE_BRAND_NAME` |


### A-03. 브랜드 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, 관리자 브랜드. 삭제된 브랜드도 돌려준다. |
| 오류 | `400 INVALID_REQUEST` · `404 BRAND_NOT_FOUND` |


### A-04. 브랜드 수정


| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId`, body `{ "name": "새 이름" }` |
| 성공 | `200`, 관리자 브랜드 |
| 오류 | `400 INVALID_REQUEST` · `400 INVALID_BRAND_NAME` · `404 BRAND_NOT_FOUND` · `409 DUPLICATE_BRAND_NAME` |


### A-05. 브랜드 삭제


| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, `data` 없음. 브랜드와 연결된 미삭제 상품 전체를 함께 논리 삭제하고, 다른 브랜드·상품과 기존 주문은 유지한다. |
| 오류 | `400 INVALID_REQUEST` · `404 BRAND_NOT_FOUND` |


### A-06. 상품 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/products` |
| 입력 | query `brandId`(선택), `page`, `size` |
| 성공 | `200`, 관리자 상품 목록. 삭제된 상품도 담는다. |
| 순서 | 등록 최신순 |
| 오류 | `400 INVALID_REQUEST` |


### A-07. 상품 생성


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api-admin/v1/products` |
| 입력 | body `{ "brandId": 1, "name": "상품", "price": 3000 }` |
| 성공 | `201`, 관리자 상품. `stock`은 0이다. |
| 오류 | `400 INVALID_REQUEST` · `400 INVALID_PRODUCT_NAME` · `400 INVALID_PRODUCT_PRICE` · `404 BRAND_NOT_FOUND` · `409 DUPLICATE_PRODUCT_NAME` |


### A-08. 상품 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, 관리자 상품. 삭제된 상품도 돌려준다. |
| 오류 | `400 INVALID_REQUEST` · `404 PRODUCT_NOT_FOUND` |


### A-09. 상품 수정


| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/products/{productId}` |
| 입력 | path `productId`, body `{ "name": "새 이름", "price": 3500, "brandId": 1 }`. `brandId`는 선택이며, 보내면 현재 브랜드와 같아야 한다. |
| 성공 | `200`, 관리자 상품. 재고는 바뀌지 않는다. |
| 오류 | `400 INVALID_REQUEST` · `400 INVALID_PRODUCT_NAME` · `400 INVALID_PRODUCT_PRICE` · `404 PRODUCT_NOT_FOUND` · `409 DUPLICATE_PRODUCT_NAME` · `409 BRAND_CHANGE_NOT_ALLOWED` |


### A-10. 상품 삭제


| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api-admin/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, `data` 없음. 삭제 여부만 바뀌고, 이 상품을 가리키는 좋아요와 주문 품목은 남는다. |
| 오류 | `400 INVALID_REQUEST` · `404 PRODUCT_NOT_FOUND` |


### A-11. 상품 재고 변경


| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/products/{productId}/stock` |
| 입력 | path `productId`, body `{ "quantity": 10 }`.  |
| 성공 | `200`, 관리자 상품 |
| 오류 | `400 INVALID_REQUEST` · `400 INVALID_STOCK_QUANTITY` · `404 PRODUCT_NOT_FOUND` |


### A-12. 주문 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/orders` |
| 입력 | query `buyerId`(선택), `page`, `size` |
| 성공 | `200`, 관리자 주문 요약 목록. 각 항목은 `id`, `buyerId`, `status`, `itemCount`, `totalAmount`와 `CONFIRMED`일 때만 `paymentAmount`를 담는다. `buyerId`가 있으면 그 구매자의 주문만 담는다. |
| 순서 | 주문 생성 최신순 |
| 오류 | `400 INVALID_REQUEST` |


### A-13. 주문 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/orders/{orderId}` |
| 입력 | path `orderId` |
| 성공 | `200`, 관리자 주문 상세 `id`, `buyerId`, `status`, `items`(주문 당시 상품 ID·이름, 수량, 단가, 품목 금액), `totalAmount`. `CONFIRMED`일 때만 `payment`(결제액·시각)를 담는다. |
| 오류 | `400 INVALID_REQUEST` · `404 ORDER_NOT_FOUND` |


## 응답 계약

각 엔드포인트의 성공·실패 응답이 따르는 형식, 필드, 오류 코드다.

### 응답 형식

모든 응답(관리자 `403` 제외)은 `meta`와 `data`로 구성된 공통 형식을 쓴다.

| 필드 | 정의 |
| --- | --- |
| `meta.result` | API 요청 처리 결과. `SUCCESS`, `FAIL`, `PENDING` 중 하나 |
| `meta.errorCode` | `FAIL`일 때 오류 코드. 그 외에는 `null` |
| `meta.message` | `FAIL`일 때 오류 메시지. 그 외에는 `null` |
| `data` | `SUCCESS`일 때 API별 처리 결과, `PENDING`일 때 API별 대기 정보. PG 콜백은 둘 다 `null`이며, `FAIL`도 `null` |

| `meta.result` | HTTP | 의미 |
| --- | --- | --- |
| `SUCCESS` | `200`·`201` | 요청 처리가 완료되었다. 조회 요청에서는 조회가 성공했다는 의미다. |
| `FAIL` | `4xx`·`5xx` | 요청이 거절되었거나 실행한 작업의 실패가 확정되었다. 관리자 `403`은 공통 형식에서 제외한다. |
| `PENDING` | `202` | 요청을 수락했지만 최종 결과가 확정되지 않았다. |



```json
{
  "meta": {
    "result": "FAIL",
    "errorCode": "PAYMENT_FAILED",
    "message": "결제가 실패했습니다."
  },
  "data": null
}
```

### 목록 응답




| 필드              | 뜻              |
| --------------- | -------------- |
| `content`       | 항목 배열          |
| `page`          | 요청한 페이지(0부터)   |
| `size`          | 요청한 페이지 크기     |
| `totalElements` | 조건에 맞는 전체 항목 수 |







### 오류 코드

실패 응답은 아래 HTTP 상태, 오류 코드, 메시지를 쓴다.


| 상태    | `meta.errorCode`               | 뜻                                                                                                                    | `meta.message`                        |
| ----- | ------------------------------ | -------------------------------------------------------------------------------------------------------------------- | ------------------------------------- |
| `400` | `INVALID_REQUEST`              | 요청의 형식이 틀렸다. 필드 누락, 타입 오류, 깨진 JSON, 잘못된 페이지·정렬 조건                                                                    | 요청 형식이 올바르지 않습니다. 입력값을 확인해 주세요.       |
| `400` | `INVALID_STOCK_QUANTITY`       | 재고 수량이 0보다 작다.                                                                                                       | 재고 수량은 0 이상이어야 합니다.                   |
| `400` | `INVALID_POINT_BALANCE`        | 포인트 잔액이 0보다 작다.                                                                                                      | 포인트 잔액은 0 이상이어야 합니다.                  |
| `400` | `INVALID_PRODUCT_NAME`         | 상품 이름이 비었거나 공백만 있거나, 앞뒤 공백을 뺀 길이가 100자를 넘는다.                                                                         | 상품 이름은 공백이 아닌 1~100자여야 합니다.           |
| `400` | `INVALID_PRODUCT_PRICE`        | 상품 가격이 1원~1,000,000,000원 밖이다.                                                                                        | 상품 가격은 1원 이상 1,000,000,000원 이하여야 합니다. |
| `400` | `INVALID_BRAND_NAME`           | 브랜드 이름이 비었거나 공백만 있거나, 앞뒤 공백을 뺀 길이가 50자를 넘는다.                                                                         | 브랜드 이름은 공백이 아닌 1~50자여야 합니다.           |
| `400` | `INVALID_CHARGE_AMOUNT`        | 충전액이 0 이하이다.                                                                                                         | 충전액은 1 이상이어야 합니다.                     |
| `400` | `INVALID_ORDER_QUANTITY`       | 주문 수량이 0 이하이다.                                                                                                       | 주문 수량은 1 이상이어야 합니다.                   |
| `400` | `EMPTY_ORDER_ITEMS`            | 주문에 품목이 없다.                                                                                                          | 주문할 상품을 하나 이상 담아 주세요.                 |
| `401` | `USER_NOT_IDENTIFIED`          | `X-USER-ID`가 없거나 숫자가 아니거나 없는 사용자이다.                                                                                  | 사용자를 확인할 수 없습니다.                      |
| `404` | `BRAND_NOT_FOUND`              | 브랜드가 없다. 또는 고객 조회·상품 생성에서 삭제된 브랜드이거나, 삭제된 브랜드를 수정·삭제하려 했다.                                                           | 브랜드를 찾을 수 없습니다.                       |
| `404` | `PRODUCT_NOT_FOUND`            | 상품이 없다. 또는 고객 조회·새 주문·좋아요에서 삭제된 상품이거나, 삭제된 상품을 수정·재고 변경·삭제하려 했다.                                                     | 상품을 찾을 수 없습니다.                        |
| `404` | `ORDER_NOT_FOUND`              | 주문이 없거나 다른 고객의 주문이다.                                                                                                 | 주문을 찾을 수 없습니다.                        |
| `404` | `USER_NOT_FOUND`               | 경로의 사용자가 요청자가 아니다.                                                                                                   | 사용자를 찾을 수 없습니다.                       |
| `404` | `LIKE_NOT_FOUND`               | 좋아요가 없거나 다른 고객의 좋아요다.                                                                                                | 좋아요를 찾을 수 없습니다.                       |
| `404` | `NOT_FOUND`                    | 요청한 경로가 없다.                                                                                                          | 요청한 경로를 찾을 수 없습니다.                    |
| `404` | `PAYMENT_ATTEMPT_NOT_FOUND` | 콜백에 대응하는 내부 결제 시도가 없다. | 결제 시도를 찾을 수 없습니다. |
| `405` | `METHOD_NOT_ALLOWED`           | 경로는 있지만 허용하지 않는 method로 요청했다.                                                                                        | 허용하지 않는 요청 방식입니다.                     |
| `409` | `DUPLICATE_BRAND_NAME`         | 삭제되지 않은 브랜드 중에 같은 이름이 있다. 앞뒤 공백을 빼고, 대소문자를 구분해 비교한다.                                                                 | 이미 사용 중인 브랜드 이름입니다.                   |
| `409` | `DUPLICATE_PRODUCT_NAME`       | 같은 브랜드의 삭제되지 않은 상품 중에 같은 이름이 있다. 앞뒤 공백을 빼고, 대소문자를 구분해 비교한다.                                                          | 이 브랜드에 같은 이름의 상품이 있습니다.               |
| `409` | `ORDER_ALREADY_CONFIRMED`      | 이미 확정된 주문을 확정하거나 품목 수량을 바꾸려 했다.                                                                                      | 이미 확정된 주문입니다.                         |
| `409` | `BRAND_CHANGE_NOT_ALLOWED`     | 상품 수정에서 브랜드를 바꾸려 했다.                                                                                                 | 상품의 브랜드는 바꿀 수 없습니다.                   |
| `409` | `PRODUCT_NOT_AVAILABLE`        | 확정하려는 주문의 상품이 삭제되었다.                                                                                                 | 주문한 상품 중 판매하지 않는 상품이 있습니다.            |
| `409` | `INSUFFICIENT_STOCK`           | 확정하려는 주문의 수량보다 재고가 적다.                                                                                               | 재고가 부족합니다.                            |
| `409` | `INSUFFICIENT_POINT`           | 확정하려는 주문의 금액보다 잔액이 적다.                                                                                               | 포인트 잔액이 부족합니다.                        |
| `409` | `PAYMENT_ALREADY_PROCESSED`    | 같은 결제 시도의 결제가 이미 성공 또는 실패로 종결되어 다시 요청했다.                                                                     | 이미 처리된 결제입니다.                         |
| `409` | `POINT_CONFLICT` | 주문 확정의 포인트 버전 충돌이 최대 2회 재시도 후에도 해소되지 않았다. 이번 확정의 변경은 롤백된다. | 포인트 잔액이 변경되었습니다. 최신 잔액을 확인한 뒤 다시 시도해 주세요. |
| `409` | `POINT_BALANCE_LIMIT_EXCEEDED` | 충전 후 잔액이 표현할 수 있는 범위를 넘는다.                                                                                           | 충전할 수 있는 한도를 넘었습니다.                   |
| `409` | `PAYMENT_RESULT_CONFLICT` | PG 조회 결과의 주문·거래·금액이 내부 기록과 일치하지 않거나 기존 최종 결과와 충돌한다. | 결제 결과가 기존 기록과 일치하지 않습니다. |
| `422` | `PAYMENT_FAILED` | 주문 확정 요청에서 결제 실패가 확정되었다. 처리 중·결과 미확인에는 사용하지 않는다. | 결제가 실패했습니다. |
| `503` | `PAYMENT_CANCELLATION_PENDING` | PG 결제 결과를 확인하지 못해 같은 취소 멱등키로 최대 3회 취소를 요청했다. 최종 취소 결과가 확인되지 않으면 결제 상태는 `CANCEL_PROCESSING`이다. | 결제 결과 확인 중 문제가 발생했습니다. 결제 취소가 진행 중입니다. |
| `500` | `INTERNAL_ERROR`               | 요청과 관계없는 내부 오류이다. 예: 결제액이 없는 결제 결과, 0 이하의 수량으로 재고 차감([ADR-003](./decisions.md#adr-003-재고-차감-수량이-0-이하이면-내부-오류로-거절한다)) | 일시적인 오류가 발생했습니다.                      |





## 고객 브랜드 목록

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/brands?page=0&size=20`, `X-USER-ID` 필수 |
| 정렬 | createdAt DESC, id ASC (기존 브랜드 목록 순서) |
| 페이지 | page 기본 0·최소 0, size 기본 20·범위 1~100 |
| 성공 | 200, `data: {content: [{id, name}], page, size, totalElements}`.  |
| 빈 목록 | 200, content 빈 배열. 범위 밖 페이지도 빈 배열 |
| 오류 | `400 INVALID_REQUEST` · `401 USER_NOT_IDENTIFIED` |
