# 커머스 API 계약

[전체 설계](commerce-erd-draft.md) · [정책 선택과 설계 검토](commerce-policy-decisions.md) · [TDD 계획](commerce-tdd-plan.md)

고객 계약 C01~C12와 관리자 계약 A01~A13의 입력·응답·오류를 정의한다. 업무 규칙과 세 대표 흐름은 전체 설계를 참조한다. 제안의 확정 여부는 정책 선택과 각 항목의 표시를 함께 확인한다.

## 기본 API 계약

아래 **25개 method/path와 핵심 업무 조건은 제공된 과제 명세**를 따른다. 응답 필드·HTTP 상태·오류 코드·페이지 기본값 등 과제에 값이 지정되지 않은 내용은 **구현과 테스트를 구체화하기 위한 계약 제안**이다. 특히 [정책 선택표](commerce-policy-decisions.md#구현-전에-확인할-정책-선택)의 항목은 사용자 확인 전까지 확정된 업무 규칙으로 취급하지 않는다. 기존 예시 API의 응답 코드는 변경하지 않으며, 아래 커머스 API는 아직 구현되지 않았다.

### 공통 입력·식별·응답

| 항목 | 계약 또는 제안 |
| --- | --- |
| 고객 식별 | 본인 데이터 API는 필수 `X-USER-ID`를 사용한다. 누락·빈 문자열·공백만 있는 값은 `400 INVALID_REQUEST`를 제안한다. 문자열을 숫자 ID로 강제하지 않는다. 상품·브랜드 공개 조회 C01~C03에는 헤더를 요구하지 않는 안을 제안한다. |
| 사용자 연결 제안 | fixture에서 `alice → user.id=1`, `bob → user.id=2`, `admin → user.id=3`과 권한을 준비한다. 요청 헤더는 이 매핑으로 DB 행을 찾고, 잔액·주문·좋아요 FK는 DB ID를 사용한다. 최초 요청에서 사용자를 자동 생성하지 않는다. 초기 잔액은 0을 기본값으로 제안하며 테스트는 필요한 잔액을 명시한다. 운영 인증이나 회원가입 API를 추가하지 않는다. |
| URL의 사용자 ID 제안 | C06의 `{userId}`와 관리자 주문 필터 `userId`는 `alice` 같은 외부 식별 문자열이다. 고객은 헤더와 같은 사용자의 목록만 조회할 수 있다. fixture 매핑의 구체적인 저장 위치는 구현 전에 정한다. |
| 관리자 식별 제안 | 같은 `X-USER-ID`를 해석한 뒤 fixture의 관리자 권한을 확인한다. 유효한 고객이 관리자 API를 호출하면 `403 FORBIDDEN`, 매핑되지 않은 요청자는 `404 USER_NOT_FOUND`다. 관리자 경로에 접근했다는 사실만으로 권한을 부여하지 않는다. |
| 경로·쿼리의 자원 ID | `brandId`, `productId`, `orderId`는 양의 정수, `Long` 범위로 제안한다. 형식 오류·0·음수·표현 범위 초과는 `400 INVALID_REQUEST`다. 사용자 식별 문자열과 구분한다. |
| JSON 입력 제안 | 본문이 필요한 요청은 `application/json`을 사용한다. 필수 값 누락·null·잘못된 타입·허용하지 않은 필드는 `400 INVALID_REQUEST`다. 숫자 문자열이나 소수를 정수로 자동 변환하지 않는다. 본문이 없는 API에는 요청 DTO를 만들지 않는다. |
| 숫자 범위 제안 | 금액·포인트는 원 단위 `Long` 정수, 최댓값은 `9,223,372,036,854,775,807`이다. 수량·재고는 `Integer`, 최댓값은 `2,147,483,647`이다. 주문 수량은 1 이상, 재고·잔액은 0 이상, 충전액은 1 이상이다. 연산 후 값뿐 아니라 덧셈·곱셈 과정의 범위 초과도 검출한다. |
| 이름·가격 제안 | 브랜드명·상품명은 앞뒤 공백 제거 후 1~100 Unicode 코드 포인트, 가격은 1원 이상으로 제안한다. 가격 0 허용 여부는 [정책 선택표](commerce-policy-decisions.md#구현-전에-확인할-정책-선택)에서 확인한다. 이름 중복 금지는 요구하지 않는다. |
| 성공 응답 | 기존 `ApiResponse`를 이어받아 `meta.result=SUCCESS`, `data`에 아래 지정한 결과를 넣는다. `meta.errorCode`, `meta.message`는 생략한다. 생성은 표에서 지정한 `201`, 나머지는 `200`을 제안한다. |
| 실패 응답 | `meta.result=FAIL`, `meta.errorCode`와 `meta.message`를 반환하고 `data`는 생략한다. 실패한 변경 요청은 부분 저장하지 않는다. 아래 오류 코드와 상태는 커머스 기능의 새 계약 제안이며 현재 `ErrorType`에 모두 구현되어 있다는 뜻이 아니다. |
| 처리 순서 | 형식 검증 → 요청자·권한/소유권 확인 → 재요청 분기 → 업무 검증 → 변경·저장 순서다. 주문 재확정은 본인 여부 확인 후 기존 결과를 반환하며 현재 상품을 다시 검사하지 않는다. |

사용자 연결 방식은 물리 ERD의 확정 사항이 아니다. fixture 매핑 대신 `user`에 유일한 외부 식별자 컬럼을 추가하는 방식도 가능하며, 구현 시 하나로 정한다. 두 방식을 동시에 별도 사용자 원장처럼 유지하지 않는다.

### 응답 데이터와 입력 DTO 제안

다음 이름은 계약표를 간결하게 읽기 위한 데이터 형태이며 실제 클래스 이름을 강제하지 않는다. JSON은 camelCase를 사용하므로 관계 집계값 `like_count`는 응답에서 `likeCount`로 표현한다. 시각은 시간대가 있는 ISO 8601 문자열을 제안한다. 공통 직렬화 설정에 따라 null 필드는 생략한다.

| 형태 | 필드·의미 |
| --- | --- |
| `BrandView` | `brandId`, `name` |
| `ProductView` | `productId`, `name`, `price`, `stockQuantity`, `brand: BrandView`, `likeCount` |
| `LikeResult` | `productId`, `liked: boolean`, `likeCount` |
| `BalanceView` | `balance` — 0도 정상 값 |
| `OrderItemView` | `productId`, `productName`(스냅샷), `unitPrice`(스냅샷), `quantity`, `subtotal` |
| `OrderView` | `orderId`, `status`, `items: OrderItemView[]`, `totalAmount`, `paidAmount`, `createdAt`, `confirmedAt`. DRAFT의 `paidAmount`·`confirmedAt`은 생략하며 CONFIRMED는 `paidAmount=totalAmount`와 확정 시각을 반환한다. 결제 성공 결과는 이 상태·결제액·시각으로 표현한다. |
| `AdminBrandView` | `BrandView` + `createdAt`, `updatedAt`, `deletedAt` |
| `AdminProductView` | `ProductView` + `createdAt`, `updatedAt`, `deletedAt` |
| `AdminOrderView` | `OrderView` + 구매자의 외부 식별값 `userId`. 고객 응답에는 다른 구매자 정보나 관리자용 삭제 시각을 넣지 않는다. |
| `Page<T>` | `items: T[]`, `page`, `size`, `totalElements`, `totalPages`. 결과가 없거나 마지막 페이지를 넘으면 `items=[]`; `totalElements`는 필터를 적용한 전체 개수다. |
| `BrandInput` | `{"name":"브랜드 A"}` — POST·PUT 모두 name 필수 |
| `ProductCreateInput` | `{"brandId":10,"name":"상품 A","price":1000,"stockQuantity":5}` — 네 필드 필수, 미삭제 브랜드 참조 |
| `ProductUpdateInput` | `{"name":"상품 A 수정","price":1200}` — 두 필드 필수. `brandId`, `stockQuantity`는 받지 않는 안을 제안하며 포함 시 `400 INVALID_REQUEST`. 브랜드는 유지하고 재고는 전용 API로 변경한다. |
| `StockInput` | `{"stockQuantity":3}` — 필수, 0 이상인 최종 재고 |
| `OrderCreateInput` | `{"items":[{"productId":101,"quantity":2}]}` — 비어 있지 않은 배열, 각 productId·quantity 필수. 상품명·단가·합계는 요청으로 받지 않고 서버가 계산한다. |

관리자 목록·상세는 삭제된 행도 조회하여 `deletedAt`으로 구분하는 안을 제안한다. 관리자 조회 가능 여부와 수정 가능 여부는 다르며, 삭제 대상의 수정·재고 변경은 허용하지 않는다. 고객에게는 삭제된 상품·브랜드를 노출하지 않는다.

### 목록 필터·페이지·정렬 제안

| 대상 | 입력과 기본값 | 정렬·오류 기준 |
| --- | --- | --- |
| 고객 상품 C02 | 선택 `brandId`, `page=0`, `size=20`, `sort=latest` | `brandId`는 일치 필터다. 없는/삭제된 브랜드 필터는 빈 페이지를 제안한다. 형식이 잘못된 ID는 400이다. 상품과 소속 브랜드 모두 미삭제인 결과만 포함한다. |
| 상품 정렬 `latest` | 생성일 최신순 | `product.created_at DESC, product.id DESC` |
| 상품 정렬 `price_asc` | 가격 낮은순 | `product.price ASC, product.id DESC` |
| 상품 정렬 `likes_desc` | 관계에서 계산한 좋아요 수 많은순 | `likeCount DESC, product.id DESC`. 좋아요 0인 상품도 포함하고, 전체 결과를 정렬한 뒤 페이지를 자른다. |
| 내 좋아요 C06 | `page=0`, `size=20` | `like.created_at DESC, like.id DESC`. 본인 관계 중 미삭제 상품·브랜드만 포함한다. |
| 내 주문 C11 | `page=0`, `size=20` | 본인 주문만 `order.created_at DESC, order.id DESC` |
| 관리자 브랜드 A01 | `page=0`, `size=20` | `brand.created_at DESC, brand.id DESC` |
| 관리자 상품 A06 | 선택 `brandId`, `page=0`, `size=20`, `sort=latest` | 고객 상품과 같은 세 정렬·동률 기준. 관리자 조회는 삭제 행도 포함한다. |
| 관리자 주문 A12 | 선택 `userId`(구매자), `status`(`DRAFT` 또는 `CONFIRMED`), `page=0`, `size=20` | 조건이 있으면 모두 적용하고 `order.created_at DESC, order.id DESC`. 없는 구매자 필터는 빈 페이지를 제안한다. 잘못된 status는 400이다. |
| 페이지 공통 | page는 0 이상, size는 1~100인 정수 | 음수 page, size 0·101, 소수·문자·표현 범위 초과, 미지원 sort는 `400 INVALID_REQUEST`. 잘못된 값을 기본값으로 바꾸지 않는다. |

동률 기준은 같은 데이터 상태에서 순서를 결정한다. 페이지 요청 사이에 상품·가격·좋아요 수가 바뀌는 상황까지 스냅샷으로 고정한다는 보장은 현재 추가하지 않는다.

### 고객 API 계약표

`C01~C03`은 공개 조회, 나머지는 `X-USER-ID`로 본인 데이터를 처리한다. 모든 입력 형식 오류에는 공통 `400 INVALID_REQUEST`를 적용한다. 본인 API에는 공통 요청자 오류도 적용하며, 표의 마지막 열은 그 밖의 대표 오류와 저장 결과를 명시한다.

| ID | method/path | 입력 | 성공 결과 | 대표 오류·기대값 |
| --- | --- | --- | --- | --- |
| C01 | `GET /api/v1/brands/{brandId}` | 경로 brandId | `200`, `BrandView` | 없거나 삭제됨: `404 BRAND_NOT_FOUND` |
| C02 | `GET /api/v1/products` | brandId·page·size·sort | `200`, `Page<ProductView>` | 잘못된 필터·페이지·정렬: 400. 조건에 맞는 상품이 없으면 200 빈 페이지 |
| C03 | `GET /api/v1/products/{productId}` | 경로 productId | `200`, `ProductView` | 상품 또는 소속 브랜드가 없거나 삭제됨: `404 PRODUCT_NOT_FOUND` |
| C04 | `POST /api/v1/products/{productId}/likes` | 경로 productId, 본문 없음 | `200`, `LikeResult(liked=true)`, 관계 생성 후 집계값 | 상품·브랜드가 없거나 삭제됨: `404 PRODUCT_NOT_FOUND`. 중복은 기존 관계를 유지하고 200 반환하는 안을 제안하며 개수를 더 늘리지 않음 |
| C05 | `DELETE /api/v1/products/{productId}/likes` | 경로 productId, 본문 없음 | `200`, `LikeResult(liked=false)`, 본인 관계 제거 후 집계값 | 없는 상품: `404 PRODUCT_NOT_FOUND`. 삭제된 상품도 본인 관계 취소 가능. 이미 관계가 없으면 200과 현재 개수를 반환하는 안을 제안 |
| C06 | `GET /api/v1/users/{userId}/likes` | 외부 사용자 식별 문자열, page·size | `200`, `Page<ProductView>` | 헤더와 다른 사용자 또는 없는 사용자: `404 USER_NOT_FOUND`. 삭제 상품 관계는 목록에서만 제외 |
| C07 | `POST /api/v1/points/charge` | `{"amount":3000}` | `200`, `BalanceView`, 잔액 2000이면 DB·응답 모두 5000 | 유효하지 않은 amount: 400. 잔액 합산 범위 초과: `409 POINT_BALANCE_LIMIT_EXCEEDED`. 실패 시 잔액 유지 |
| C08 | `GET /api/v1/points` | 본문 없음 | `200`, 요청자의 저장된 `BalanceView` | 없는 요청자: `404 USER_NOT_FOUND`. 잔액 0은 `200 {balance:0}` |
| C09 | `POST /api/v1/orders` | `OrderCreateInput` | `201`, 생성한 DRAFT `OrderView`, 재고·포인트 차감 없음 | 없는/삭제 상품·브랜드: `404 PRODUCT_NOT_FOUND`. 잘못된 수량·빈 items: 400. 중복 처리와 금액 범위는 [정책 선택표](commerce-policy-decisions.md#구현-전에-확인할-정책-선택)·아래 오류표 참조. 실패 시 주문·항목 저장 없음 |
| C10 | `POST /api/v1/orders/{orderId}/confirm` | 경로 orderId, 본문 없음 | `200`, 저장된 CONFIRMED `OrderView`. 생성 금액으로 차감하고 기존 확정 재요청도 동일한 결과 반환 | 없거나 타인 주문: `404 ORDER_NOT_FOUND`. DRAFT에 삭제/없는 상품·브랜드: `404 PRODUCT_NOT_FOUND`, 재고·포인트 부족: 각 409. 저장 수량이 양수가 아닌 서버 데이터 오류는 `500 INTERNAL_ERROR`. 실패 시 DRAFT·재고·잔액 유지 |
| C11 | `GET /api/v1/orders` | page·size | `200`, `Page<OrderView>`, 본인 주문만 조회 | 잘못된 페이지: 400. 주문이 없으면 200 빈 페이지 |
| C12 | `GET /api/v1/orders/{orderId}` | 경로 orderId | `200`, `OrderView`, 현재 상품이 삭제돼도 저장 스냅샷 조회 | 없거나 타인 주문: 동일한 `404 ORDER_NOT_FOUND`·메시지 |

C10은 DRAFT의 저장된 상품·브랜드와 양수 수량을 검증한다. C09의 요청 수량 오류는 400이지만, C10에는 수량 입력이 없으므로 저장된 수량의 불변식 위반을 클라이언트 입력 오류로 분류하지 않는다. 이미 CONFIRMED인 주문은 소유권 확인 후 저장 결과를 반환하는 기존 합의를 유지하므로, 이 분기에는 현재 상품 상태 검증을 추가하지 않는다. `POST` 충전·주문 생성은 확정 재요청과 같은 멱등성을 자동으로 갖지 않는다.

### 관리자 API 계약표

모든 관리자 API는 공통 요청자·관리자 권한 검사를 먼저 적용한다. 권한 없는 요청은 `403 FORBIDDEN`이며 데이터가 바뀌지 않는다. 유효하지 않은 ID·본문·필터·페이지는 `400 INVALID_REQUEST`다. 아래 조회 응답에 추가한 관리 필드와 삭제 반복 응답은 계약 제안이다.

| ID | method/path | 입력 | 성공 결과 | 대표 오류·기대값 |
| --- | --- | --- | --- | --- |
| A01 | `GET /api-admin/v1/brands` | page·size | `200`, `Page<AdminBrandView>` | 잘못된 페이지: 400. 데이터가 없으면 200 빈 페이지 |
| A02 | `POST /api-admin/v1/brands` | `BrandInput` | `201`, 저장한 `AdminBrandView` | 이름 누락·공백·길이 초과: 400, 저장 없음 |
| A03 | `GET /api-admin/v1/brands/{brandId}` | 경로 brandId | `200`, `AdminBrandView` | 행이 없음: `404 BRAND_NOT_FOUND`. 삭제 행은 삭제 시각과 함께 관리자에게 조회 |
| A04 | `PUT /api-admin/v1/brands/{brandId}` | 경로 brandId, `BrandInput` | `200`, 수정한 `AdminBrandView` | 없음/삭제됨: `404 BRAND_NOT_FOUND`, 이름 오류: 400. 실패 시 기존 값 유지 |
| A05 | `DELETE /api-admin/v1/brands/{brandId}` | 경로 brandId, 본문 없음 | `200`, `{brandId,deleted:true}` | 없음: `404 BRAND_NOT_FOUND`. 미삭제 상품 연결: `409 BRAND_HAS_PRODUCTS`(재고 0 포함). 이미 삭제된 행에는 같은 성공 결과 반환하는 안을 제안 |
| A06 | `GET /api-admin/v1/products` | brandId·page·size·sort | `200`, `Page<AdminProductView>` | 잘못된 조건: 400. 조건에 맞는 행이 없으면 200 빈 페이지 |
| A07 | `POST /api-admin/v1/products` | `ProductCreateInput` | `201`, 저장한 `AdminProductView` | 없는/삭제 브랜드: `404 BRAND_NOT_FOUND`. 이름·가격·재고 오류: 400, 저장 없음 |
| A08 | `GET /api-admin/v1/products/{productId}` | 경로 productId | `200`, `AdminProductView` | 행이 없음: `404 PRODUCT_NOT_FOUND`. 삭제 행도 조회 가능 |
| A09 | `PUT /api-admin/v1/products/{productId}` | 경로 productId, `ProductUpdateInput` | `200`, 수정한 `AdminProductView`, brandId·재고 유지 | 없음/삭제됨: `404 PRODUCT_NOT_FOUND`. 이름·가격 오류 또는 brandId·stockQuantity 포함: 400, 기존 값 유지 |
| A10 | `DELETE /api-admin/v1/products/{productId}` | 경로 productId, 본문 없음 | `200`, `{productId,deleted:true}`, 주문이 있어도 삭제 | 없음: `404 PRODUCT_NOT_FOUND`. 주문·주문항목은 삭제하지 않음. 이미 삭제된 행은 같은 성공 결과를 제안 |
| A11 | `PUT /api-admin/v1/products/{productId}/stock` | 경로 productId, `StockInput` | `200`, `{productId,stockQuantity}`, 현재 10에 3 요청이면 최종 3 | 없음/삭제됨: `404 PRODUCT_NOT_FOUND`. 음수·타입·범위 오류: 400, 기존 재고 유지 |
| A12 | `GET /api-admin/v1/orders` | userId·status·page·size | `200`, `Page<AdminOrderView>` | 잘못된 조건: 400. 구매자·상태 조건에 맞는 주문이 없으면 200 빈 페이지 |
| A13 | `GET /api-admin/v1/orders/{orderId}` | 경로 orderId | `200`, `AdminOrderView`, 구매자·품목·상태·금액·결제 결과 | 없음: `404 ORDER_NOT_FOUND`. 관리자 권한이면 다른 구매자의 주문도 조회 가능 |

### 대표 오류와 상태 보존 제안

1주차의 `INVALID_REQUEST`, `ORDER_NOT_FOUND`, `INTERNAL_ERROR` 의미를 이어받는다. 아래 메시지를 계약 테스트의 기대값으로 제안하며, 타인 주문과 없는 주문은 코드·메시지가 동일해야 한다. 기존 예시 API의 `Bad Request`, `Not Found` 관찰 계약과 별도로 적용한다.

| HTTP | errorCode | message | 대표 상황·보존할 상태 |
| --- | --- | --- | --- |
| 400 | `INVALID_REQUEST` | `요청 값이 올바르지 않습니다.` | 필수 입력·타입·범위·쿼리 오류. 변경 없음 |
| 404 | `USER_NOT_FOUND` | `사용자를 찾을 수 없습니다.` | fixture에 없는 요청자 또는 다른 사용자의 내 좋아요 목록 접근. 변경 없음 |
| 403 | `FORBIDDEN` | `관리자 권한이 필요합니다.` | 관리자 API를 일반 고객이 호출. 변경 없음 |
| 404 | `BRAND_NOT_FOUND` | `브랜드를 찾을 수 없습니다.` | 브랜드 없음 또는 미삭제 대상을 요구하는 작업에서 삭제됨 |
| 404 | `PRODUCT_NOT_FOUND` | `상품을 찾을 수 없습니다.` | 상품 없음 또는 해당 작업에서 사용 불가능한 삭제 상태. DRAFT 확정 실패 시 주문·차감 상태 유지 |
| 404 | `ORDER_NOT_FOUND` | `주문을 찾을 수 없습니다.` | 고객에게 없는 주문과 타인 주문을 구분하지 않음 |
| 409 | `BRAND_HAS_PRODUCTS` | `삭제되지 않은 상품이 연결되어 있습니다.` | 브랜드 삭제 실패, 브랜드·상품 상태 유지 |
| 409 | `INSUFFICIENT_STOCK` | `상품 재고가 부족합니다.` | 확정 실패, DRAFT·모든 상품 재고·잔액 유지 |
| 409 | `INSUFFICIENT_POINTS` | `포인트가 부족합니다.` | 확정 실패, DRAFT·모든 상품 재고·잔액 유지 |
| 409 | `POINT_BALANCE_LIMIT_EXCEEDED` | `포인트 잔액의 허용 범위를 초과합니다.` | amount 자체는 유효하지만 잔액 합산 초과. 기존 잔액 유지 |
| 409 | `ORDER_AMOUNT_LIMIT_EXCEEDED` | `주문 금액의 허용 범위를 초과합니다.` | 유효한 상품 단가·수량으로 계산한 소계·총액이 표현 범위 초과. 주문 저장 없음. 저장 데이터 손상 등 예상하지 못한 서버 계산 오류와 구분 |
| 400 | `DUPLICATE_ORDER_ITEM` | `동일 상품을 중복해서 주문할 수 없습니다.` | 중복 품목 정책을 거절로 선택할 때만 적용, 주문 저장 없음 |
| 500 | `INTERNAL_ERROR` | `일시적인 오류가 발생했습니다.` | 예상하지 못한 서버 오류. 내부 상세를 노출하지 않고 변경 트랜잭션 롤백 |

지원하지 않는 HTTP 메서드·Content-Type 등의 프레임워크 오류도 실제 API 테스트에서 별도 점검한다. 현재 공통 예외 처리에는 지원하지 않는 메서드의 405 오류가 500으로 변환되는 문제가 남아 있다. 커머스 입력·접근 오류도 자동으로 올바르게 매핑된다고 가정하지 않는다.
