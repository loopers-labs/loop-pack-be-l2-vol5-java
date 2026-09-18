# commerce-api API 응답 스키마

[API 엔드포인트](./api-endpoints.md)의 성공·실패 응답이 따르는 형식, 필드, 오류 코드다. 근거는 [요구사항 문서](./requirements.md)의 요구사항 ID와 정책 ID를 가리킨다.

## 응답 형식

모든 응답(관리자 `403` 제외)은 공통 형식을 쓴다.

```json
{ "meta": { "result": "SUCCESS", "errorCode": null, "message": null }, "data": { } }
```

- 성공은 `201 Created` 또는 `200 OK`이고 `meta.result`는 `SUCCESS`이다. 새 대상을 만든 요청은 `201`, 나머지는 `200`이다. 돌려줄 값이 없으면 `data`는 `null`이다.
- 실패는 `오류 코드`의 상태 코드이고 `meta.result`는 `FAIL`, `meta.errorCode`와 `meta.message`를 채운다.

## 목록 응답

목록을 돌려주는 성공 응답의 `data`는 다음 필드를 가진다. 페이지 요청 규칙은 [API 엔드포인트](./api-endpoints.md)의 `목록`에 있다.

| 필드 | 뜻 |
| --- | --- |
| `content` | 항목 배열 |
| `page` | 요청한 페이지(0부터) |
| `size` | 요청한 페이지 크기 |
| `totalElements` | 조건에 맞는 전체 항목 수 |

## 응답 필드

| 이름 | 필드 | 쓰는 API |
| --- | --- | --- |
| 고객 브랜드 | `id`, `name` | C-01 |
| 고객 상품 | `id`, `name`, `price`, `brand`(`id`, `name`), `likeCount`, `soldOut` | C-02, C-03, C-06 |
| 주문 상세 | `id`, `status`, `items`(`productId`, `productName`, `quantity`, `unitPrice`, `amount`), `totalAmount`, `payment`(`amount`, `paidAt`) | C-09, C-10, C-12 |
| 주문 요약 | `id`, `status`, `itemCount`, `totalAmount`, `paymentAmount` | C-11 |
| 관리자 브랜드 | `id`, `name`, `deleted` | A-01 ~ A-04 |
| 관리자 상품 | `id`, `name`, `price`, `brand`(`id`, `name`), `stock`, `deleted` | A-06 ~ A-09, A-11 |
| 관리자 주문 상세 | 주문 상세 + `buyerId` | A-13 |
| 관리자 주문 요약 | 주문 요약 + `buyerId` | A-12 |

- `likeCount`는 그 상품의 좋아요 관계를 세어 얻는다. 상품에 저장하지 않는다. (R-LIKE-05)
- 고객 상품은 재고 수량 대신 품절 여부(`soldOut`)를 보여 주고, 관리자 상품은 좋아요 수 대신 재고와 삭제 여부를 보여 준다. (R-ACCESS-06, P-CATALOG-01, P-ADMIN-07, P-ADMIN-09)
- `status`는 `DRAFT` 또는 `CONFIRMED`이다. `DRAFT` 주문의 `payment`와 `paymentAmount`는 `null`이다. (R-ORDER-03, R-ORDER-12, P-ORDER-06)
- 주문 품목의 `productName`과 `unitPrice`는 주문을 생성할 때의 값이며, 상품이 수정되거나 삭제되어도 바뀌지 않는다. (P-ORDER-03, P-ORDER-07)

금액, 잔액, 가격은 원 단위 정수이고, 표현 범위는 64비트 정수이다.

## 오류 코드

오류 코드는 도메인 규칙 하나에 하나씩 둔다. domain과 application은 HTTP를 모르는 공통 예외에 이 코드를 담아 던지고, interfaces가 아래 상태 코드로 바꾼다.

| 상태 | `meta.errorCode` | 던지는 곳 | 뜻 | `meta.message` |
| --- | --- | --- | --- | --- |
| `400` | `INVALID_REQUEST` | interfaces | 요청의 형식이 틀렸다. 필드 누락, 타입 오류, 깨진 JSON, 잘못된 페이지·정렬 조건 | 요청 형식이 올바르지 않습니다. 입력값을 확인해 주세요. |
| `400` | `INVALID_STOCK_QUANTITY` | domain (Stock) | 재고 수량이 0보다 작다. | 재고 수량은 0 이상이어야 합니다. |
| `400` | `INVALID_PRODUCT_NAME` | domain (Product) | 상품 이름이 비었거나 공백만 있거나, 앞뒤 공백을 뺀 길이가 100자를 넘는다. | 상품 이름은 공백이 아닌 1~100자여야 합니다. |
| `400` | `INVALID_PRODUCT_PRICE` | domain (Product) | 상품 가격이 1원~1,000,000,000원 밖이다. | 상품 가격은 1원 이상 1,000,000,000원 이하여야 합니다. |
| `400` | `INVALID_BRAND_NAME` | domain (Brand) | 브랜드 이름이 비었거나 공백만 있거나, 앞뒤 공백을 뺀 길이가 50자를 넘는다. | 브랜드 이름은 공백이 아닌 1~50자여야 합니다. |
| `400` | `INVALID_CHARGE_AMOUNT` | domain (Point) | 충전액이 0 이하이다. | 충전액은 1 이상이어야 합니다. |
| `400` | `INVALID_ORDER_QUANTITY` | domain (OrderItem) | 주문 수량이 0 이하이다. | 주문 수량은 1 이상이어야 합니다. |
| `400` | `EMPTY_ORDER_ITEMS` | domain (Order) | 주문에 품목이 없다. | 주문할 상품을 하나 이상 담아 주세요. |
| `401` | `USER_NOT_IDENTIFIED` | interfaces, application | `X-USER-ID`가 없거나 숫자가 아니거나 없는 사용자이다. | 사용자를 확인할 수 없습니다. |
| `404` | `BRAND_NOT_FOUND` | application, domain (Brand) | 브랜드가 없다. 또는 고객 조회·상품 생성에서 삭제된 브랜드이거나, 삭제된 브랜드를 수정·삭제하려 했다. | 브랜드를 찾을 수 없습니다. |
| `404` | `PRODUCT_NOT_FOUND` | application, domain (Product) | 상품이 없다. 또는 고객 조회·새 주문·좋아요에서 삭제된 상품이거나, 삭제된 상품을 수정·재고 변경·삭제하려 했다. | 상품을 찾을 수 없습니다. |
| `404` | `ORDER_NOT_FOUND` | application | 주문이 없거나 다른 고객의 주문이다. | 주문을 찾을 수 없습니다. |
| `404` | `USER_NOT_FOUND` | application | 경로의 사용자가 요청자가 아니다. | 사용자를 찾을 수 없습니다. |
| `404` | `LIKE_NOT_FOUND` | domain (Like) | 다른 고객의 좋아요를 취소하려 했다. 좋아요 취소 API는 요청자의 좋아요만 찾으므로 응답으로는 나오지 않는다. | 좋아요를 찾을 수 없습니다. |
| `404` | `NOT_FOUND` | interfaces | 요청한 경로가 없다. | 요청한 경로를 찾을 수 없습니다. |
| `405` | `METHOD_NOT_ALLOWED` | interfaces | 경로는 있지만 허용하지 않는 method로 요청했다. | 허용하지 않는 요청 방식입니다. |
| `409` | `DUPLICATE_BRAND_NAME` | domain (브랜드 이름 중복 여부) | 삭제되지 않은 브랜드 중에 같은 이름이 있다. 앞뒤 공백을 빼고, 대소문자를 구분해 비교한다. | 이미 사용 중인 브랜드 이름입니다. |
| `409` | `DUPLICATE_PRODUCT_NAME` | domain (상품 이름 중복 여부) | 같은 브랜드의 삭제되지 않은 상품 중에 같은 이름이 있다. 앞뒤 공백을 빼고, 대소문자를 구분해 비교한다. | 이 브랜드에 같은 이름의 상품이 있습니다. |
| `409` | `BRAND_HAS_PRODUCTS` | domain (브랜드 삭제 가능 여부) | 삭제되지 않은 상품이 연결된 브랜드를 삭제하려 했다. | 연결된 상품이 있어 브랜드를 삭제할 수 없습니다. |
| `409` | `ORDER_ALREADY_CONFIRMED` | domain (Order) | 이미 확정된 주문을 확정하거나 품목 수량을 바꾸려 했다. | 이미 확정된 주문입니다. |
| `409` | `BRAND_CHANGE_NOT_ALLOWED` | domain (Product) | 상품 수정에서 브랜드를 바꾸려 했다. | 상품의 브랜드는 바꿀 수 없습니다. |
| `409` | `PRODUCT_NOT_AVAILABLE` | domain (주문 확정) | 확정하려는 주문의 상품이 삭제되었다. | 주문한 상품 중 판매하지 않는 상품이 있습니다. |
| `409` | `INSUFFICIENT_STOCK` | domain (Stock) | 확정하려는 주문의 수량보다 재고가 적다. | 재고가 부족합니다. |
| `409` | `INSUFFICIENT_POINT` | domain (Point) | 확정하려는 주문의 금액보다 잔액이 적다. | 포인트 잔액이 부족합니다. |
| `409` | `POINT_BALANCE_LIMIT_EXCEEDED` | domain (Point) | 충전 후 잔액이 표현할 수 있는 범위를 넘는다. | 충전할 수 있는 한도를 넘었습니다. |
| `500` | `INTERNAL_ERROR` | 모든 계층 | 요청과 관계없는 내부 오류이다. 예: 결제액이 없는 결제 결과, 0 이하의 수량으로 재고 차감([ADR-003](./decisions.md#adr-003-재고-차감-수량이-0-이하이면-내부-오류로-거절한다)) | 일시적인 오류가 발생했습니다. |

관리자 접근 필터가 막는 요청은 `403`이며 오류 코드 없이 응답한다.

상태 코드는 누가, 무엇 때문에 거절하는지로 나눈다.

| 상태 | 기준 |
| --- | --- |
| `400` | 요청의 형식이 틀리면 interfaces가, 값이 도메인 규칙을 벗어나면 domain이 거절한다. 같은 요청은 언제 보내도 실패한다. |
| `401` | 요청자를 식별하지 못했다. |
| `404` | 대상이 없다. 다른 고객의 대상과 삭제된 대상도 없는 대상으로 다룬다. |
| `405` | 허용하지 않는 method이다. |
| `409` | 형식과 값은 맞지만, 저장된 상태를 보고 도메인이 업무 규칙으로 거절한다. 상태가 바뀌면 같은 요청이 성공할 수 있다. |
| `500` | 요청과 관계없는 내부 오류이다. |

같은 규칙을 어디서 확인하든 같은 코드가 나오도록, 값의 범위·공백·개수는 domain에서만 확인한다. interfaces는 필드 누락, 타입, JSON 형식만 확인한다.
