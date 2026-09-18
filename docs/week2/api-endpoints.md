# commerce-api API 엔드포인트

과제가 요구한 기본 API의 method, path, 입력, 성공, 대표 오류와 주요 규칙의 기대값이다. 근거는 [요구사항 문서](./requirements.md)의 요구사항 ID와 정책 ID를 가리키고, 요청이 지나는 계층은 [대표 흐름](./representative-flows.md)을 따른다.

## 공통 규칙

### 요청자 식별

| 대상 | 식별 | 실패하면 |
| --- | --- | --- |
| [고객 API](#고객-api) (C-01 ~ C-12) | `X-USER-ID` 요청 헤더 | `401 USER_NOT_IDENTIFIED`. 헤더가 없거나, 숫자가 아니거나, 없는 사용자이면 같은 결과로 거절한다. (R-ACCESS-05, P-ACCESS-01) |
| `/api-admin/v1/**` | `ADMIN` 역할 | `403`. 관리자 접근 필터가 거절하며 컨트롤러에 닿지 않는다. 응답 본문은 [API 응답 스키마](./api-response-schema.md)의 공통 응답 형식이 아니다. (R-ACCESS-04) |

- 고객 요청의 식별은 과제가 요구한 고객 API에만 적용한다. 같은 `/api/v1` 아래에 있는 기존 starter의 Example API(`/api/v1/examples/**`)는 식별하지 않는다.
- 고객은 자신의 좋아요·포인트·주문만 다룬다. 다른 고객의 것을 요청하면 없는 대상으로 알린다. (R-ACCESS-03, P-ACCESS-02)
- 관리자의 변경은 이후 고객 조회에 반영된다. (R-ADMIN-09)

### 응답

응답 형식, 응답 필드, 오류 코드는 [API 응답 스키마](./api-response-schema.md)를 따른다. 아래 각 API의 `성공` 칸에 적힌 응답 이름(고객 상품, 주문 상세 등)과 `대표 오류` 칸의 오류 코드도 그 문서를 가리킨다.

- 새 대상을 만드는 요청(A-02, A-07, C-09, 처음 등록하는 C-04)의 성공은 `201 Created`, 나머지 성공은 `200 OK`이다.
- 실패한 요청은 저장된 상태를 바꾸지 않는다.

### 목록

목록을 돌려주는 요청은 모두 같은 페이지 규칙을 쓴다. 상품 목록의 정책(P-CATALOG-05, P-CATALOG-06, P-CATALOG-07)을 다른 목록에도 똑같이 적용한다.

| 항목 | 규칙 |
| --- | --- |
| `page` | 0부터 시작한다. 기본값은 0이다. 음수이면 `400 INVALID_REQUEST` |
| `size` | 1~100이다. 기본값은 20이다. 범위를 벗어나면 `400 INVALID_REQUEST` |
| 마지막 페이지를 넘는 `page` | 빈 목록을 돌려준다. |
| 응답 | [API 응답 스키마](./api-response-schema.md)의 목록 응답을 따른다. |
| 순서 | 요청마다 적는다. 순서 기준이 같으면 식별자 오름차순으로 정한다. |

## 고객 API

고객이 브랜드·상품을 조회하고 좋아요·포인트·주문 기능을 쓰는 API다. (R-ACCESS-01)

### C-01. 브랜드 상세

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, 고객 브랜드 |
| 대표 오류 | `404 BRAND_NOT_FOUND`: 없거나 삭제된 브랜드 |
| 근거 | R-CATALOG-01, R-CATALOG-07, R-ADMIN-12 |

### C-02. 상품 목록

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/products` |
| 입력 | query `brandId`(선택), `sort`(`latest`·`price_asc`·`likes_desc`, 기본 `latest`), `page`, `size` |
| 성공 | `200`, 고객 상품 목록. 삭제되지 않은 상품만 담는다. |
| 순서 | `latest`는 등록 최신순, `price_asc`는 가격 오름차순, `likes_desc`는 좋아요 수 내림차순. 같으면 상품 식별자 오름차순 |
| 대표 오류 | `400 INVALID_REQUEST`: 지원하지 않는 `sort`, 잘못된 `page`·`size` |
| 규칙 | 없거나 삭제된 `brandId`로 거르면 빈 목록을 돌려준다. |
| 근거 | R-CATALOG-02, R-CATALOG-03, R-LIKE-05, R-CATALOG-04, R-CATALOG-05, R-CATALOG-06, R-CATALOG-08, R-ADMIN-12, P-CATALOG-02, P-CATALOG-03, P-CATALOG-04, P-CATALOG-06, P-CATALOG-08 |

### C-03. 상품 상세

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, 고객 상품 |
| 대표 오류 | `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 |
| 근거 | R-CATALOG-02, R-CATALOG-03, R-CATALOG-07, R-LIKE-05, R-ADMIN-12 |

### C-04. 좋아요 등록

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/products/{productId}/likes` |
| 입력 | path `productId` |
| 성공 | `201`, `data` 없음. 이미 좋아요한 상품이면 새로 만들지 않고 `200`이며, 좋아요 수는 그대로다. |
| 대표 오류 | `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 |
| 근거 | R-LIKE-01, R-LIKE-02, R-LIKE-06, R-LIKE-07, P-LIKE-01, P-LIKE-02 |

### C-05. 좋아요 취소

| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api/v1/products/{productId}/likes` |
| 입력 | path `productId` |
| 성공 | `200`, `data` 없음. 좋아요가 없어도 `200`이다. 상품이 삭제되었어도 자신의 좋아요를 취소한다. |
| 대표 오류 | 공통 오류 외에는 없다. |
| 근거 | R-LIKE-01, R-LIKE-04, R-LIKE-06, R-LIKE-08, P-LIKE-01 |

### C-06. 내 좋아요 목록

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/users/{userId}/likes` |
| 입력 | path `userId`, query `page`, `size` |
| 성공 | `200`, 고객 상품 목록. 삭제된 상품은 담지 않는다. |
| 순서 | 좋아요한 시점 최신순 |
| 대표 오류 | `404 USER_NOT_FOUND`: `userId`가 요청자가 아님 |
| 근거 | R-LIKE-03, R-LIKE-04, R-LIKE-07, P-ACCESS-02 |

### C-07. 포인트 충전

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/points/charge` |
| 입력 | body `{ "amount": 10000 }`. `amount`는 양의 정수 |
| 성공 | `200`, `data`는 `{ "balance": 충전 후 잔액 }` |
| 대표 오류 | `400 INVALID_REQUEST`: `amount` 누락, 정수가 아님, 표현 범위 초과 · `400 INVALID_CHARGE_AMOUNT`: 0 이하 · `409 POINT_BALANCE_LIMIT_EXCEEDED`: 충전 후 잔액이 표현 범위를 넘음. 어느 경우든 잔액은 그대로다. |
| 근거 | R-POINT-01, R-POINT-03, R-POINT-04, R-POINT-06, R-POINT-07, R-POINT-08 |

### C-08. 내 잔액 조회

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/points` |
| 입력 | 없음 |
| 성공 | `200`, `data`는 `{ "balance": 저장된 잔액 }`. 충전한 적이 없으면 0이다. |
| 대표 오류 | 공통 오류 외에는 없다. |
| 근거 | R-POINT-02, R-POINT-05, P-POINT-01 |

### C-09. 주문 생성

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/orders` |
| 입력 | body `{ "items": [ { "productId": 1, "quantity": 2 } ] }` |
| 성공 | `201`, 주문 상세. `status`는 `DRAFT`, `payment`는 `null`이다. 재고와 잔액은 그대로다. |
| 대표 오류 | `400 INVALID_REQUEST`: `items`나 `quantity` 누락, 정수가 아님 · `400 EMPTY_ORDER_ITEMS`: `items`가 비었음 · `400 INVALID_ORDER_QUANTITY`: `quantity`가 0 이하 · `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 |
| 규칙 | 같은 `productId`가 여러 번 오면 수량을 합산해 품목 하나로 만든다. 단가와 상품 이름은 지금 값으로 기록하고, 합계는 품목 금액의 합이다. |
| 근거 | R-ORDER-01, R-ORDER-02, R-ORDER-03, R-ORDER-04, R-ORDER-05, R-ORDER-06, R-ORDER-15, P-ORDER-01, P-ORDER-02, P-ORDER-03, P-ORDER-07 |

### C-10. 주문 확정

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api/v1/orders/{orderId}/confirm` |
| 입력 | path `orderId` |
| 성공 | `200`, 주문 상세. `status`는 `CONFIRMED`이고 `payment`에 결제액과 결제 시점이 있다. 각 상품의 재고와 고객의 잔액이 차감된다. |
| 대표 오류 | `404 ORDER_NOT_FOUND`: 없거나 다른 고객의 주문 · `409 ORDER_ALREADY_CONFIRMED` · `409 PRODUCT_NOT_AVAILABLE`: 삭제된 상품이 있음 · `409 INSUFFICIENT_STOCK` · `409 INSUFFICIENT_POINT` |
| 규칙 | 하나라도 통과하지 못하면 주문 상태, 재고, 잔액을 모두 그대로 둔다. |
| 근거 | R-ACCESS-03, R-ORDER-07, R-ORDER-08, R-ORDER-09, R-ORDER-10, R-ORDER-11, R-ORDER-12, P-ACCESS-02, P-ORDER-04, P-ORDER-06 |

### C-11. 내 주문 목록

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/orders` |
| 입력 | query `page`, `size` |
| 성공 | `200`, 요청자의 주문 요약 목록 |
| 순서 | 주문 생성 최신순 |
| 대표 오류 | 공통 오류 외에는 없다. |
| 근거 | R-ORDER-13, R-ORDER-14, P-ORDER-08, P-ORDER-09 |

### C-12. 내 주문 상세

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api/v1/orders/{orderId}` |
| 입력 | path `orderId` |
| 성공 | `200`, 주문 상세. 삭제된 상품의 품목도 저장된 값으로 보여 준다. |
| 대표 오류 | `404 ORDER_NOT_FOUND`: 없거나 다른 고객의 주문 |
| 근거 | R-ORDER-13, R-ORDER-14, P-ACCESS-02, P-ORDER-07, P-ORDER-08 |

## 관리자 API

관리자가 브랜드·상품·재고를 관리하고 구매자들의 주문을 조회하는 API다. (R-ACCESS-02)

관리자 조회는 삭제된 브랜드와 상품도 삭제 여부와 함께 보여 준다. 삭제된 대상은 수정, 재고 변경, 다시 삭제의 대상이 아니며 없는 대상으로 알린다. (P-ADMIN-06, P-ADMIN-07, R-ADMIN-13)

브랜드·상품 이름은 앞뒤 공백을 빼고 저장한다. 길이와 중복은 뺀 이름으로 판단하고, 중복은 대소문자를 구분해 비교한다. (P-ADMIN-01, P-ADMIN-02)

### A-01. 브랜드 목록

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/brands` |
| 입력 | query `page`, `size` |
| 성공 | `200`, 관리자 브랜드 목록. 삭제된 브랜드도 담는다. |
| 순서 | 등록 최신순 |
| 근거 | R-ADMIN-01, P-ADMIN-07, P-ADMIN-08 |

### A-02. 브랜드 생성

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api-admin/v1/brands` |
| 입력 | body `{ "name": "브랜드" }` |
| 성공 | `201`, 관리자 브랜드 |
| 대표 오류 | `400 INVALID_REQUEST`: `name` 누락 · `400 INVALID_BRAND_NAME`: 비었음, 공백만 있음, 앞뒤 공백을 뺀 길이가 50자 초과 · `409 DUPLICATE_BRAND_NAME` |
| 근거 | R-ADMIN-01, R-ADMIN-15, P-ADMIN-01 |

### A-03. 브랜드 상세

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, 관리자 브랜드. 삭제된 브랜드도 돌려준다. |
| 대표 오류 | `404 BRAND_NOT_FOUND`: 존재한 적이 없는 브랜드 |
| 근거 | R-ADMIN-01, P-ADMIN-07 |

### A-04. 브랜드 수정

| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId`, body `{ "name": "새 이름" }` |
| 성공 | `200`, 관리자 브랜드 |
| 대표 오류 | `400 INVALID_REQUEST`: `name` 누락 · `400 INVALID_BRAND_NAME`: 이름 규칙 위반 · `404 BRAND_NOT_FOUND`: 없거나 삭제된 브랜드 · `409 DUPLICATE_BRAND_NAME`: 자신을 뺀 삭제되지 않은 브랜드와 이름이 같음 |
| 근거 | R-ADMIN-01, R-ADMIN-13, R-ADMIN-15, P-ADMIN-01 |

### A-05. 브랜드 삭제

| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api-admin/v1/brands/{brandId}` |
| 입력 | path `brandId` |
| 성공 | `200`, `data` 없음. 삭제 여부만 바뀌고, 상품이 가리키던 브랜드 참조는 남는다. |
| 대표 오류 | `404 BRAND_NOT_FOUND`: 없거나 이미 삭제된 브랜드 · `409 BRAND_HAS_PRODUCTS`: 삭제되지 않은 상품이 연결됨(재고 0인 상품 포함) |
| 근거 | R-ADMIN-01, R-ADMIN-02, R-ADMIN-03, R-ADMIN-14, P-ADMIN-06, [ADR-001](./decisions.md) |

### A-06. 상품 목록

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/products` |
| 입력 | query `brandId`(선택), `page`, `size` |
| 성공 | `200`, 관리자 상품 목록. 삭제된 상품도 담는다. |
| 순서 | 등록 최신순 |
| 근거 | R-ADMIN-04, P-ADMIN-07, P-ADMIN-08 |

### A-07. 상품 생성

| 항목 | 계약 |
| --- | --- |
| 요청 | `POST /api-admin/v1/products` |
| 입력 | body `{ "brandId": 1, "name": "상품", "price": 3000 }` |
| 성공 | `201`, 관리자 상품. `stock`은 0이다. |
| 대표 오류 | `400 INVALID_REQUEST`: 필드 누락, 타입이 틀림 · `400 INVALID_PRODUCT_NAME`: 이름이 비었음·공백만 있음·앞뒤 공백을 뺀 길이가 100자 초과 · `400 INVALID_PRODUCT_PRICE`: 가격이 1원~1,000,000,000원 밖 · `404 BRAND_NOT_FOUND`: 없거나 삭제된 브랜드 · `409 DUPLICATE_PRODUCT_NAME` |
| 근거 | R-ADMIN-04, R-ADMIN-05, R-ADMIN-06, P-ADMIN-02, P-ADMIN-03, P-ADMIN-05 |

### A-08. 상품 상세

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, 관리자 상품. 삭제된 상품도 돌려준다. |
| 대표 오류 | `404 PRODUCT_NOT_FOUND`: 존재한 적이 없는 상품 |
| 근거 | R-ADMIN-04, P-ADMIN-07, P-ADMIN-09 |

### A-09. 상품 수정

| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/products/{productId}` |
| 입력 | path `productId`, body `{ "name": "새 이름", "price": 3500, "brandId": 1 }`. `brandId`는 선택이며, 보내면 현재 브랜드와 같아야 한다. |
| 성공 | `200`, 관리자 상품. 재고는 바뀌지 않는다. |
| 대표 오류 | `400 INVALID_REQUEST`: 필드 누락, 타입이 틀림 · `400 INVALID_PRODUCT_NAME` · `400 INVALID_PRODUCT_PRICE` · `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 · `409 DUPLICATE_PRODUCT_NAME` · `409 BRAND_CHANGE_NOT_ALLOWED`: `brandId`가 현재 브랜드와 다름. 어느 경우든 상품은 그대로다. |
| 근거 | R-ADMIN-04, R-ADMIN-06, R-ADMIN-07, R-ADMIN-13, P-ADMIN-02, P-ADMIN-03, P-ADMIN-04, P-ADMIN-05 |

### A-10. 상품 삭제

| 항목 | 계약 |
| --- | --- |
| 요청 | `DELETE /api-admin/v1/products/{productId}` |
| 입력 | path `productId` |
| 성공 | `200`, `data` 없음. 삭제 여부만 바뀌고, 이 상품을 가리키는 좋아요와 주문 품목은 남는다. |
| 대표 오류 | `404 PRODUCT_NOT_FOUND`: 없거나 이미 삭제된 상품 |
| 근거 | R-ADMIN-04, R-ADMIN-14, R-LIKE-08, P-ADMIN-06, [ADR-001](./decisions.md) |

### A-11. 상품 재고 변경

| 항목 | 계약 |
| --- | --- |
| 요청 | `PUT /api-admin/v1/products/{productId}/stock` |
| 입력 | path `productId`, body `{ "quantity": 10 }`. `quantity`는 0 이상인 최종 수량 |
| 성공 | `200`, 관리자 상품 |
| 대표 오류 | `400 INVALID_REQUEST`: `quantity` 누락, 정수가 아님 · `400 INVALID_STOCK_QUANTITY`: 음수 · `404 PRODUCT_NOT_FOUND`: 없거나 삭제된 상품 |
| 근거 | R-ADMIN-08, R-ADMIN-13 |

### A-12. 주문 목록

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/orders` |
| 입력 | query `buyerId`(선택), `page`, `size` |
| 성공 | `200`, 관리자 주문 요약 목록. `buyerId`가 있으면 그 구매자의 주문만 담는다. |
| 순서 | 주문 생성 최신순 |
| 근거 | R-ADMIN-10, R-ADMIN-11, P-ADMIN-10, P-ORDER-08, P-ORDER-09 |

### A-13. 주문 상세

| 항목 | 계약 |
| --- | --- |
| 요청 | `GET /api-admin/v1/orders/{orderId}` |
| 입력 | path `orderId` |
| 성공 | `200`, 관리자 주문 상세 |
| 대표 오류 | `404 ORDER_NOT_FOUND` |
| 근거 | R-ADMIN-10, R-ADMIN-11, P-ORDER-07 |

## 제공하지 않는 기능

| 기능 | 이유 | 근거 |
| --- | --- | --- |
| `DRAFT` 주문의 품목 수량 변경 | 도메인 모델과 정책에는 있지만, 과제가 요구한 API에 없어 제공하지 않는다. | P-ORDER-05 |

## 주요 규칙의 기대값

| 규칙 | 조건 | 기대값 | 근거 |
| --- | --- | --- | --- |
| 재고 차감 | 재고 5에서 6개 차감 | 거절. 재고 5 그대로 | R-ORDER-08, R-ORDER-10 |
| 재고 차감 | 재고 5에서 2개 차감 | 재고 3 | R-ORDER-11 |
| 재고 설정 | 최종 수량 0 | 성공. 재고 0 | R-ADMIN-08 |
| 재고 설정 | 최종 수량 -1 | `400 INVALID_STOCK_QUANTITY`. 재고 그대로 | R-ADMIN-08 |
| 충전 | 잔액 0에서 10,000원 충전 | 잔액 10,000 | R-POINT-06 |
| 충전 | 0원 또는 음수 충전 | `400 INVALID_CHARGE_AMOUNT`. 잔액 그대로 | R-POINT-04, R-POINT-08 |
| 잔액 | 한 번도 충전하지 않음 | 잔액 0 | R-POINT-05, P-POINT-01 |
| 주문 확정 | 잔액 10,000, 합계 7,000 | `CONFIRMED`, 결제액 7,000, 잔액 3,000 | R-ORDER-11, R-ORDER-12 |
| 주문 확정 | 잔액 5,000, 합계 7,000 | `409 INSUFFICIENT_POINT`. 주문 `DRAFT`, 재고와 잔액 그대로 | R-ORDER-09, R-ORDER-10 |
| 주문 확정 | 이미 `CONFIRMED`인 주문 | `409 ORDER_ALREADY_CONFIRMED`. 결제액과 잔액 그대로 | P-ORDER-04 |
| 주문 생성 | 상품 A 2개와 상품 A 3개 | 품목 A 5개 하나. 확정할 때 재고 5 이상이어야 한다 | R-ORDER-15, P-ORDER-02 |
| 주문 생성 | 품목 0개 | `400 EMPTY_ORDER_ITEMS` | P-ORDER-01 |
| 주문 생성 | 수량 0 | `400 INVALID_ORDER_QUANTITY` | R-ORDER-06 |
| 주문 조회 | 주문 뒤 상품 가격이 3,000원에서 3,500원으로 바뀜 | 품목 단가는 3,000 그대로 | P-ORDER-03, P-ORDER-07 |
| 좋아요 | 같은 상품에 두 번 등록 | 처음은 `201`, 두 번째는 `200`. 좋아요 수 1 | R-LIKE-02, P-LIKE-01 |
| 좋아요 | 좋아요한 상품이 삭제됨 | 내 목록에서 빠지고, 취소는 `200` | R-LIKE-07, R-LIKE-08 |
| 브랜드 삭제 | 재고 0인 상품만 연결됨 | `409 BRAND_HAS_PRODUCTS` | R-ADMIN-02, R-ADMIN-03 |
| 상품 생성 | 가격 0원 | `400 INVALID_PRODUCT_PRICE` | P-ADMIN-03 |
| 상품 생성 | 같은 브랜드에 삭제되지 않은 같은 이름의 상품이 있음 | `409 DUPLICATE_PRODUCT_NAME` | P-ADMIN-02 |
| 상품 생성 | 같은 브랜드에 삭제된 같은 이름의 상품만 있음 | 성공 | P-ADMIN-02 |
| 관리자 요청 | `ADMIN` 역할이 없음 | `403`. 상태 그대로 | R-ACCESS-04 |
| 고객 요청 | `X-USER-ID`가 없거나 없는 사용자 | `401 USER_NOT_IDENTIFIED` | R-ACCESS-05, P-ACCESS-01 |
