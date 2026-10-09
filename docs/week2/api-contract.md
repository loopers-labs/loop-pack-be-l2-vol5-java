# commerce-api API 계약

과제가 요구한 기본 API의 method, path, 입력, 성공, 오류와 응답 형식을 한곳에 정리한다. 필드 타입·필수 여부·값 제약을 포함한 기계 판독 계약은 [OpenAPI 명세](../../apps/commerce-api/src/main/resources/static/openapi.yaml)에 있다.

이 문서는 사람이 검토할 정책 계약을 갖고, OpenAPI 명세는 HTTP 형식 계약을 갖는다. 각 OpenAPI `operationId`의 앞부분은 이 문서의 엔드포인트 ID와 대응한다. 각 API가 지키는 도메인 규칙은 [`domain-rules.yaml`](./domain-rules.yaml)에 있고, 여기에는 규칙 문장을 적지 않는다.

공통 인증·라우팅 오류는 공통 계약에서 정하고, 각 엔드포인트의 `오류` 칸은 그 API의 입력 형식·업무 오류를 전부 담는다.

## 공통 규칙

### 요청자 식별


| 대상                              | 식별                | 실패하면                                                                                           |
| ------------------------------- | ----------------- | ---------------------------------------------------------------------------------------------- |
| [고객 API](#고객-api) (C-01 ~ C-12) | `X-USER-ID` 요청 헤더 | `401 USER_NOT_IDENTIFIED`. 헤더가 없거나, 숫자가 아니거나, 없는 사용자이면 같은 결과로 거절한다. (R-ACCESS-05, P-ACCESS-01) |
| `/api-admin/v1/**`              | `ADMIN` 역할        | `403`. 관리자 접근 필터가 거절하며 컨트롤러에 닿지 않는다. 응답 본문은 [공통 응답 형식](#응답-형식)을 따르지 않는다. (R-ACCESS-04)         |


- 고객은 자신의 좋아요·포인트·주문만 다룬다. 다른 고객의 것을 요청하면 없는 대상으로 알린다. (R-ACCESS-03, P-ACCESS-02)
- 관리자의 변경은 이후 고객 조회에 반영된다. (R-ADMIN-09)

### 목록

목록을 돌려주는 요청은 모두 같은 페이지 규칙을 쓴다. 상품 목록의 정책(P-CATALOG-05, P-CATALOG-06, P-CATALOG-07)을 다른 목록에도 똑같이 적용한다.


| 항목                 | 규칙                                                 |
| ------------------ | -------------------------------------------------- |
| `page`             | 0부터 시작한다. 기본값은 0이다. 음수이면 `400 INVALID_REQUEST`     |
| `size`             | 1~100이다. 기본값은 20이다. 범위를 벗어나면 `400 INVALID_REQUEST` |
| 마지막 페이지를 넘는 `page` | 빈 목록을 돌려준다.                                        |
| 응답                 | [목록 응답](#목록-응답)을 따른다.                              |
| 순서                 | 요청마다 적는다. 순서 기준이 같으면 식별자 오름차순으로 정한다.               |


## 고객 API

고객이 브랜드·상품을 조회하고 좋아요·포인트·주문 기능을 쓰는 API다. (R-ACCESS-01)

### C-01. 브랜드 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, 고객 브랜드 |
| 오류 | `400 INVALID_REQUEST`: `brandId`가 정수가 아님 · `404 BRAND_NOT_FOUND`: 없거나 삭제된 브랜드 |


### C-02. 상품 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/products` |
| 입력 | query `brandId`(선택), `sort`(`latest`·`price_asc`·`likes_desc`, 기본 `latest`), `page`, `size` |
| 성공 | `200`, 고객 상품 목록. 삭제되지 않은 상품만 담는다. |
| 순서 | `latest`는 등록 최신순, `price_asc`는 가격 오름차순, `likes_desc`는 좋아요 수 내림차순. 같으면 상품 식별자 오름차순 |
| 오류 | `400 INVALID_REQUEST`: 정수가 아닌 `brandId`, 지원하지 않는 `sort`, 잘못된 `page`·`size` |
| 규칙 | 없거나 삭제된 `brandId`로 거르면 빈 목록을 돌려준다. |


### C-03. 상품 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, 고객 상품 |
| 오류 | `400 INVALID_REQUEST`: `productId`가 정수가 아님 · `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 |


### C-04. 좋아요 등록


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/products/{productId}/likes` |
| 입력 | path `productId` |
| 성공 | `201`, `data` 없음. 이미 좋아요한 상품이면 새로 만들지 않고 `200`이며, 좋아요 수는 그대로다. |
| 오류 | `400 INVALID_REQUEST`: `productId`가 정수가 아님 · `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 |


### C-05. 좋아요 취소


| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api/v1/products/{productId}/likes` |
| 입력 | path `productId` |
| 성공 | `200`, `data` 없음. 좋아요가 없어도 `200`이다. 상품이 삭제되었어도 자신의 좋아요를 취소한다. |
| 오류 | `400 INVALID_REQUEST`: `productId`가 정수가 아님 |


### C-06. 내 좋아요 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/users/{userId}/likes` |
| 입력 | path `userId`, query `page`, `size` |
| 성공 | `200`, 고객 상품 목록. 삭제된 상품은 담지 않는다. |
| 순서 | 좋아요한 시점 최신순 |
| 오류 | `400 INVALID_REQUEST`: `userId`가 정수가 아니거나 `page`·`size`가 잘못됨 · `404 USER_NOT_FOUND`: `userId`가 요청자가 아님 |


### C-07. 포인트 충전


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/points/charge` |
| 입력 | body `{ "amount": 10000 }`. `amount`는 양의 정수 |
| 성공 | `200`, `data`는 `{ "balance": 충전 후 잔액 }` |
| 오류 | `400 INVALID_REQUEST`: `amount` 누락, 정수가 아님, 표현 범위 초과 · `400 INVALID_CHARGE_AMOUNT`: 0 이하 · `409 POINT_BALANCE_LIMIT_EXCEEDED`: 충전 후 잔액이 표현 범위를 넘음. 어느 경우든 잔액은 그대로다. |


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
| 오류 | `400 INVALID_REQUEST`: `items`·`productId`·`quantity` 누락 또는 타입이 틀림 · `400 EMPTY_ORDER_ITEMS`: `items`가 비었음 · `400 INVALID_ORDER_QUANTITY`: `quantity`가 0 이하 · `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 |


### C-10. 주문 확정


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/orders/{orderId}/confirm` |
| 입력 | path `orderId` |
| 성공 | `200`, 주문 상세 `id`, `status=CONFIRMED`, `items`(상품 ID·이름, 수량, 단가, 품목 금액), `totalAmount`, `payment`(결제액 `amount`, 결제 시각 `paidAt`). 재고·잔액이 차감된다. |
| 오류 | `400 INVALID_REQUEST`: `orderId`가 정수가 아님 · `404 ORDER_NOT_FOUND`: 없거나 다른 고객의 주문 · `409 ORDER_ALREADY_CONFIRMED` · `409 PRODUCT_NOT_AVAILABLE`: 삭제된 상품이 있음 · `409 INSUFFICIENT_STOCK` · `409 INSUFFICIENT_POINT` · `409 POINT_CONFLICT`: 포인트 버전 충돌로 주문 확정 재시도 2회를 소진함. 최신 잔액을 확인한 뒤 다시 요청 · `500 INTERNAL_ERROR`: 한도 초과 충돌의 대상을 확인할 수 없거나 주문이 아직 DRAFT인 경우 등 내부 오류 |


### C-11. 내 주문 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/orders` |
| 입력 | query `page`, `size` |
| 성공 | `200`, 요청자의 주문 요약 목록. 각 항목은 `id`, `status`, `itemCount`, `totalAmount`를 담고, `CONFIRMED`일 때만 `paymentAmount`를 담는다. |
| 순서 | 주문 생성 최신순 |
| 오류 | `400 INVALID_REQUEST`: `page`·`size`가 잘못됨 |


### C-12. 내 주문 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/orders/{orderId}` |
| 입력 | path `orderId` |
| 성공 | `200`, 주문 상세 `id`, `status`, `items`(주문 당시 상품 ID·이름, 수량, 단가, 품목 금액), `totalAmount`. `CONFIRMED`일 때만 `payment`(결제액·시각)를 담는다. |
| 오류 | `400 INVALID_REQUEST`: `orderId`가 정수가 아님 · `404 ORDER_NOT_FOUND`: 없거나 다른 고객의 주문 |

## 관리자 API

관리자가 브랜드·상품·재고를 관리하고 구매자들의 주문을 조회하는 API다. (R-ACCESS-02)

관리자 조회는 삭제된 브랜드와 상품도 삭제 여부와 함께 보여 준다. 삭제된 대상은 수정, 재고 변경, 다시 삭제의 대상이 아니며 없는 대상으로 알린다. (P-ADMIN-06, P-ADMIN-07, R-ADMIN-13)

### A-01. 브랜드 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/brands` |
| 입력 | query `page`, `size` |
| 성공 | `200`, 관리자 브랜드 목록. 삭제된 브랜드도 담는다. |
| 순서 | 등록 최신순 |
| 오류 | `400 INVALID_REQUEST`: `page`·`size`가 잘못됨 |


### A-02. 브랜드 생성


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api-admin/v1/brands` |
| 입력 | body `{ "name": "브랜드" }` |
| 성공 | `201`, 관리자 브랜드 |
| 오류 | `400 INVALID_REQUEST`: `name` 누락 · `400 INVALID_BRAND_NAME`: 비었음, 공백만 있음, 앞뒤 공백을 뺀 길이가 50자 초과 · `409 DUPLICATE_BRAND_NAME` |


### A-03. 브랜드 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, 관리자 브랜드. 삭제된 브랜드도 돌려준다. |
| 오류 | `400 INVALID_REQUEST`: `brandId`가 정수가 아님 · `404 BRAND_NOT_FOUND`: 존재한 적이 없는 브랜드 |


### A-04. 브랜드 수정


| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId`, body `{ "name": "새 이름" }` |
| 성공 | `200`, 관리자 브랜드 |
| 오류 | `400 INVALID_REQUEST`: `brandId`가 정수가 아니거나 `name` 누락 · `400 INVALID_BRAND_NAME`: 이름 규칙 위반 · `404 BRAND_NOT_FOUND`: 없거나 삭제된 브랜드 · `409 DUPLICATE_BRAND_NAME`: 자신을 뺀 삭제되지 않은 브랜드와 이름이 같음 |


### A-05. 브랜드 삭제


| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, `data` 없음. 브랜드와 연결된 미삭제 상품 전체를 함께 논리 삭제하고, 다른 브랜드·상품과 기존 주문은 유지한다. |
| 오류 | `400 INVALID_REQUEST`: `brandId`가 정수가 아님 · `404 BRAND_NOT_FOUND`: 없거나 이미 삭제된 브랜드 |


### A-06. 상품 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/products` |
| 입력 | query `brandId`(선택), `page`, `size` |
| 성공 | `200`, 관리자 상품 목록. 삭제된 상품도 담는다. |
| 순서 | 등록 최신순 |
| 오류 | `400 INVALID_REQUEST`: `brandId`가 정수가 아니거나 `page`·`size`가 잘못됨 |


### A-07. 상품 생성


| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api-admin/v1/products` |
| 입력 | body `{ "brandId": 1, "name": "상품", "price": 3000 }` |
| 성공 | `201`, 관리자 상품. `stock`은 0이다. |
| 오류 | `400 INVALID_REQUEST`: 필드 누락, 타입이 틀림 · `400 INVALID_PRODUCT_NAME`: 이름이 비었음·공백만 있음·앞뒤 공백을 뺀 길이가 100자 초과 · `400 INVALID_PRODUCT_PRICE`: 가격이 1원~1,000,000,000원 밖 · `404 BRAND_NOT_FOUND`: 없거나 삭제된 브랜드 · `409 DUPLICATE_PRODUCT_NAME` |


### A-08. 상품 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, 관리자 상품. 삭제된 상품도 돌려준다. |
| 오류 | `400 INVALID_REQUEST`: `productId`가 정수가 아님 · `404 PRODUCT_NOT_FOUND`: 존재한 적이 없는 상품 |


### A-09. 상품 수정


| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/products/{productId}` |
| 입력 | path `productId`, body `{ "name": "새 이름", "price": 3500, "brandId": 1 }`. `brandId`는 선택이며, 보내면 현재 브랜드와 같아야 한다. |
| 성공 | `200`, 관리자 상품. 재고는 바뀌지 않는다. |
| 오류 | `400 INVALID_REQUEST`: `productId`가 정수가 아니거나 필드 누락·타입 오류 · `400 INVALID_PRODUCT_NAME` · `400 INVALID_PRODUCT_PRICE` · `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 · `409 DUPLICATE_PRODUCT_NAME` · `409 BRAND_CHANGE_NOT_ALLOWED`: `brandId`가 현재 브랜드와 다름. 어느 경우든 상품은 그대로다. |


### A-10. 상품 삭제


| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api-admin/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, `data` 없음. 삭제 여부만 바뀌고, 이 상품을 가리키는 좋아요와 주문 품목은 남는다. |
| 오류 | `400 INVALID_REQUEST`: `productId`가 정수가 아님 · `404 PRODUCT_NOT_FOUND`: 없거나 이미 삭제된 상품 |


### A-11. 상품 재고 변경


| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/products/{productId}/stock` |
| 입력 | path `productId`, body `{ "quantity": 10 }`. `quantity`는 0 이상인 최종 수량 |
| 성공 | `200`, 관리자 상품 |
| 오류 | `400 INVALID_REQUEST`: `productId`가 정수가 아니거나 `quantity` 누락·타입 오류 · `400 INVALID_STOCK_QUANTITY`: 음수 · `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 |


### A-12. 주문 목록


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/orders` |
| 입력 | query `buyerId`(선택), `page`, `size` |
| 성공 | `200`, 관리자 주문 요약 목록. 각 항목은 `id`, `buyerId`, `status`, `itemCount`, `totalAmount`와 `CONFIRMED`일 때만 `paymentAmount`를 담는다. `buyerId`가 있으면 그 구매자의 주문만 담는다. |
| 순서 | 주문 생성 최신순 |
| 오류 | `400 INVALID_REQUEST`: `buyerId`가 정수가 아니거나 `page`·`size`가 잘못됨 |


### A-13. 주문 상세


| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/orders/{orderId}` |
| 입력 | path `orderId` |
| 성공 | `200`, 관리자 주문 상세 `id`, `buyerId`, `status`, `items`(주문 당시 상품 ID·이름, 수량, 단가, 품목 금액), `totalAmount`. `CONFIRMED`일 때만 `payment`(결제액·시각)를 담는다. |
| 오류 | `400 INVALID_REQUEST`: `orderId`가 정수가 아님 · `404 ORDER_NOT_FOUND` |


## 응답 계약

각 엔드포인트의 성공·실패 응답이 따르는 형식, 필드, 오류 코드다.

### 응답 형식

모든 응답(관리자 `403` 제외)은 공통 형식을 쓴다.

```json
{ "meta": { "result": "SUCCESS" }, "data": { } }
```

- 성공은 `201 Created` 또는 `200 OK`이고 `meta.result`는 `SUCCESS`이다. 새 대상을 만든 요청은 `201`, 나머지는 `200`이다.
- 실패는 `오류 코드`의 상태 코드이고 `meta.result`는 `FAIL`, `meta.errorCode`와 `meta.message`를 채운다.

### 목록 응답

목록을 돌려주는 성공 응답의 `data`는 다음 필드를 가진다. 페이지 요청 규칙은 [공통 목록 규칙](#목록)을 따른다.


| 필드              | 뜻              |
| --------------- | -------------- |
| `content`       | 항목 배열          |
| `page`          | 요청한 페이지(0부터)   |
| `size`          | 요청한 페이지 크기     |
| `totalElements` | 조건에 맞는 전체 항목 수 |


### OpenAPI 스키마

요청·응답 필드의 타입, `required`, 배열 원소, 값 범위와 예시는 [OpenAPI 명세](../../apps/commerce-api/src/main/resources/static/openapi.yaml)의 각 `path`와 `components/schemas`에서 정의한다. 각 실패 응답의 `x-error-codes`는 그 엔드포인트와 HTTP 상태에서 허용하는 오류 코드다. HTTP 테스트는 실제 요청·응답 형식과 오류 코드가 이 명세를 따르는지 검사한다. 로컬 실행 후 `/swagger-ui.html`에서 같은 명세를 확인한다.



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
| `405` | `METHOD_NOT_ALLOWED`           | 경로는 있지만 허용하지 않는 method로 요청했다.                                                                                        | 허용하지 않는 요청 방식입니다.                     |
| `409` | `DUPLICATE_BRAND_NAME`         | 삭제되지 않은 브랜드 중에 같은 이름이 있다. 앞뒤 공백을 빼고, 대소문자를 구분해 비교한다.                                                                 | 이미 사용 중인 브랜드 이름입니다.                   |
| `409` | `DUPLICATE_PRODUCT_NAME`       | 같은 브랜드의 삭제되지 않은 상품 중에 같은 이름이 있다. 앞뒤 공백을 빼고, 대소문자를 구분해 비교한다.                                                          | 이 브랜드에 같은 이름의 상품이 있습니다.               |
| `409` | `ORDER_ALREADY_CONFIRMED`      | 이미 확정된 주문을 확정하거나 품목 수량을 바꾸려 했다.                                                                                      | 이미 확정된 주문입니다.                         |
| `409` | `BRAND_CHANGE_NOT_ALLOWED`     | 상품 수정에서 브랜드를 바꾸려 했다.                                                                                                 | 상품의 브랜드는 바꿀 수 없습니다.                   |
| `409` | `PRODUCT_NOT_AVAILABLE`        | 확정하려는 주문의 상품이 삭제되었다.                                                                                                 | 주문한 상품 중 판매하지 않는 상품이 있습니다.            |
| `409` | `INSUFFICIENT_STOCK`           | 확정하려는 주문의 수량보다 재고가 적다.                                                                                               | 재고가 부족합니다.                            |
| `409` | `INSUFFICIENT_POINT`           | 확정하려는 주문의 금액보다 잔액이 적다.                                                                                               | 포인트 잔액이 부족합니다.                        |
| `409` | `POINT_CONFLICT` | 주문 확정의 포인트 버전 충돌이 최대 2회 재시도 후에도 해소되지 않았다. 이번 확정의 변경은 롤백된다. | 포인트 잔액이 변경되었습니다. 최신 잔액을 확인한 뒤 다시 시도해 주세요. |
| `409` | `POINT_BALANCE_LIMIT_EXCEEDED` | 충전 후 잔액이 표현할 수 있는 범위를 넘는다.                                                                                           | 충전할 수 있는 한도를 넘었습니다.                   |
| `500` | `INTERNAL_ERROR`               | 요청과 관계없는 내부 오류이다. 예: 결제액이 없는 결제 결과, 0 이하의 수량으로 재고 차감([ADR-003](./decisions.md#adr-003-재고-차감-수량이-0-이하이면-내부-오류로-거절한다)) | 일시적인 오류가 발생했습니다.                      |


관리자 접근 필터가 막는 요청은 `403`이며 오류 코드 없이 응답한다.
