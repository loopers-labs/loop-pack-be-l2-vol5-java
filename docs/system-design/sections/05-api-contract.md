# 5. API 계약과 주요 규칙

이 장은 구현할 API의 입력, 성공 결과와 대표 오류를 정의한다. 모든 조합을 테스트 사례로 나열하기보다 도메인 규칙과 HTTP 계약을 연결하고, 구현 단계에서는 이 계약으로부터 정상 경로와 경계값·실패 테스트를 도출한다.

## 5.1 공통 계약과 입력 정책

### 요청자 식별과 접근 범위

- 고객 API는 `X-USER-ID` 헤더에 테스트 DB에 준비된 `UserModel`의 숫자 ID(`Long`)를 전달해 요청자를 식별한다. 이 값은 `jop0522` 같은 로그인 아이디가 아니다. `X-USER-ID`는 로컬 환경에서 요청자를 식별하기 위한 값이며, 로그인·인증 기능을 의미하지 않는다. interfaces 계층의 공통 요청자 식별 처리는 모든 고객 요청에서 헤더의 존재·숫자 형식과 User 존재 여부를 확인하고, 검증한 `userId`를 Controller에 전달한다. 헤더가 누락되거나 숫자로 변환할 수 없으면 `400 INVALID_REQUEST`, 숫자 ID에 해당하는 User가 없으면 `404 USER_NOT_FOUND`로 응답한다. 존재 여부 조회는 application의 `UserFacade`가 `UserRepository`를 통해 담당하며 각 기능의 Facade에서 같은 검증을 반복하지 않는다.
- 고객은 자신의 좋아요·포인트·주문만 조회하거나 변경할 수 있다. 다른 사용자의 소유 자원은 존재 여부를 노출하지 않고 해당 자원의 `NOT_FOUND` 오류로 처리한다.
- 관리자 API는 `/api-admin/**` 경로에 적용한 Spring Security 설정으로 구분한다. `ADMIN` 역할이 없는 일반 사용자와 식별되지 않은 요청은 모두 `403 Forbidden`으로 거절한다. 이 설정은 로컬 환경과 MockMvc 검증을 위한 접근 경계이며 운영용 로그인·토큰 발급·계정 관리 방식을 의미하지 않는다.
- 이 경계는 `com.loopers.config.AdminBoundaryConfig`의 `SecurityFilterChain` 하나로 구현한다. `securityMatcher("/api-admin/**")`로 관리자 경로에만 적용하고 `hasRole("ADMIN")`을 요구하며, 인증되지 않은 요청도 `401`이 아닌 `403`으로 응답하도록 `authenticationEntryPoint`에서 `sendError(403)`을 사용한다. 관리자용 SecurityFilterChain은 고객 API 요청에 적용되지 않으므로 기존 `X-USER-ID` 식별 규칙을 그대로 유지한다. CSRF 보호는 기본값 그대로 두며, 관리자 변경 요청(POST·PUT·DELETE) 테스트는 `SecurityMockMvcRequestPostProcessors.csrf()`로 유효한 CSRF 입력을 함께 보낸다. 역할 거절 테스트에도 유효한 CSRF 입력을 사용해, 거절 사유가 CSRF가 아니라 요청자 구분임을 확인한다.
- 이 설정은 관리자 경로에 접근 경계를 적용할 뿐 네트워크용 관리자 로그인을 제공하지 않는다. 관리자 API의 실행 검증은 MockMvc로 수행한다. 로컬 실행에는 `local`·`test` 프로파일에 `server.address: 127.0.0.1`을 적용해 외부에 노출하지 않는다.
- 경로 변수, 쿼리 파라미터와 `X-USER-ID` 헤더로 전달하는 모든 식별자는 숫자 형식이어야 한다. 숫자로 변환할 수 없으면 모두 `400`으로 거절하며, 안정적인 오류 코드는 검증 위치에 따라 다르다. `X-USER-ID` 헤더는 interfaces의 공통 요청자 식별 처리가 직접 검증하므로 `400 INVALID_REQUEST`로 응답한다. 경로 변수와 쿼리 파라미터의 숫자 변환 실패는 Spring이 Controller 진입 전에 발생시키는 `MethodArgumentTypeMismatchException`을 기존 `ApiControllerAdvice`가 처리하므로 `ErrorType.BAD_REQUEST`의 코드(`Bad Request`)를 유지한다. 숫자로 변환된 이후 대상이 존재하는지는 각 API의 조회 규칙에 따라 판단한다.

### 공통 응답과 상태 코드

응답 본문이 있는 성공과 업무 오류는 기존 `ApiResponse<T>` 형식을 사용한다.

응답 객체에서 값이 null인 필드는 기존 `JacksonConfig`의 `JsonInclude.Include.NON_NULL`에 따라 실제 JSON에서 생략한다. 이 규칙은 `data`, 성공 응답의 `meta.errorCode`·`meta.message`, DRAFT 주문의 결제 필드 등 응답 객체의 null 필드에 공통으로 적용한다. 아래 예시는 실제 JSON 형태이며, 표의 `null`·nullable 타입은 응답 객체의 값과 타입을 뜻한다.

```json
{
  "meta": {
    "result": "SUCCESS"
  },
  "data": {}
}
```

모든 목록 API의 페이지 정보는 다음 `PageResponse<T>` 구조로 `ApiResponse.data`에 담는다. `page`와 `size`, `totalPages`는 32비트 정수, `totalElements`는 64비트 정수로 표현한다. `totalElements`는 목록의 기준 자원 수이며, 주문 목록에서는 Order 수를 뜻한다. 주문 목록은 Order 단위로 페이지를 조회한 뒤 각 주문에 속한 모든 OrderItem을 응답에 포함한다. API별 응답은 [5장](./05-api-contract.md#5-api-계약과-주요-규칙)에 명시된 정보만 포함하고, Entity를 직접 직렬화하거나 생성·수정 시각과 같은 내부 필드를 계약 없이 추가하지 않는다.

```json
{
  "items": [],
  "page": 0,
  "size": 20,
  "totalElements": 0,
  "totalPages": 0
}
```

실패 응답은 `meta.result`를 `FAIL`로 설정하고 안정적인 업무 오류 코드와 메시지를 제공한다. 응답 객체의 `data`는 null이며 실제 JSON에서는 생략한다.

```json
{
  "meta": {
    "result": "FAIL",
    "errorCode": "POINT_NOT_INITIALIZED",
    "message": "포인트 정보가 초기화되지 않았습니다."
  }
}
```

- 조회와 상태 변경은 `200 OK`, 새 Brand·Product·Like·Order 생성은 `201 Created`로 응답한다.
- 삭제는 `200 OK`와 데이터 없는 `ApiResponse`로 응답한다. 내부 `data` 값은 null이며 실제 JSON에서는 해당 필드를 생략한다.
- 요청 형식과 값이 잘못된 경우 `400`, 대상이 없거나 접근할 수 없는 경우 `404`, 현재 상태나 중복 관계 때문에 수행할 수 없는 경우 `409`를 사용한다.
- 경로 변수로 대상을 지정하는 수정·변경 요청에서 본문 값 검증과 대상 조회가 모두 필요하면 대상 존재 여부를 먼저 판단한다. 존재하지 않거나 삭제된 대상에 잘못된 본문을 보낸 요청은 `400`이 아니라 `404`로 응답한다. Controller는 본문의 값 규칙을 직접 판단하지 않고 값을 그대로 도메인에 전달하며, 값 규칙 위반은 대상을 특정한 뒤에 드러난다. 상품 수정·재고 변경과 브랜드 수정이 여기에 해당한다.
- 생성 요청은 만들려는 대상이 아직 없으므로 이 순서를 적용하지 않고 각 API가 정한 순서를 따른다. 상품 등록은 본문의 `brandId`로 참조할 Brand를 먼저 조회하며, `brandId` 자체가 빠져 조회할 대상을 정할 수 없으면 `400 INVALID_REQUEST`로 거절한다. 주문 생성의 검증 순서는 [5.2](./05-api-contract.md#52-고객-api)의 주문 규칙에서 정한다.
- 요청과 무관하게 서버 내부 데이터의 불변식이 깨진 경우에는 `500`을 사용한다. 예를 들어 존재하는 User에게 Point가 없으면 `500 POINT_NOT_INITIALIZED`로 응답한다.
- 같은 HTTP 상태 안에서도 클라이언트가 실패 원인을 구분할 수 있도록 `PRODUCT_NOT_FOUND`, `LIKE_ALREADY_EXISTS`, `INSUFFICIENT_STOCK`과 같은 안정적인 업무 오류 코드를 사용한다. 기존 `ErrorType`을 유지하면서 각 업무 오류의 `HttpStatus`, 안정적인 코드와 기본 메시지를 추가한다. Domain은 발생한 업무 오류를 `CoreException`으로 표현하고, interfaces의 `ApiControllerAdvice`가 `ErrorType`의 정보를 사용해 실제 HTTP 응답을 생성한다.
- Soft Delete된 Brand·Product는 활성 자원을 대상으로 하는 API에서 존재하지 않는 것으로 처리한다. 이미 삭제된 대상을 다시 삭제하는 요청도 각각 `404 BRAND_NOT_FOUND`, `404 PRODUCT_NOT_FOUND`로 응답한다.
- commit 전 업무 거절·중간 처리 RuntimeException이 전파되면 해당 요청의 Entity·History 변경은 전체 rollback하여 최종 DB 상태에 남기지 않는다. 실패 전에 변경 SQL이 실행됐더라도 독립 commit이나 일부 성공으로 남기지 않는다.
- DB 변경은 하나의 트랜잭션으로 전체 commit 또는 rollback한다. commit 중 통신 장애로 완료 여부를 확인하지 못한 경우에는 기술 오류로 처리하며 오류 응답만으로 rollback을 단정하지 않는다. 이 결과 불확실성의 장애 실험·복구 기능은 이번 구현·검증 범위에 추가하지 않는다.
- deadlock·lock timeout·DB 저장/commit 등 기술 실패는 재고·Point 부족으로 바꾸지 않고 기존 HTTP 500과 `ErrorType.INTERNAL_ERROR` 처리를 유지한다. 현재 응답 `meta.errorCode`는 enum 이름이 아닌 `Internal Server Error`다. 내부 재시도나 새 충돌 오류 코드는 추가하지 않는다.
- 재시도는 사용자가 재요청 여부를 결정하는 정책이다. 서버는 실패 확인 후 추가 재실행 없이 기존 오류 응답으로 요청을 종료하고, 재요청은 새 트랜잭션에서 현재 상태를 판단한다. 비관적 잠금 대기는 유지하므로 즉시 실패나 응답시간 상한을 보장하지 않으며 모든 업무 오류에 재요청을 요구하지 않는다. 상세 이유와 비용은 [부록 A.3.3](./appendix-decisions.md#a33-재시도설정검증의-경계)을 따른다.

공통 식별·입력 형식 오류는 모든 API에 적용하며, API별 표의 대표 오류에서는 반복해 적지 않는다.

Spring Security 필터에서 거절되는 관리자 요청은 `403` 상태만 계약으로 보장하며, `ApiResponse` 본문 검증 대상에서는 제외한다.

`ErrorType`이 `HttpStatus`를 포함하므로 domain이 support를 통해 HTTP 개념에 간접 의존하는 비용을 받아들인다. 같은 업무 오류를 HTTP 외의 인터페이스에서 재사용하거나 인터페이스별로 서로 다른 상태 매핑이 필요해지면 업무 오류 코드와 HTTP 상태를 분리하고 interfaces에 별도 매퍼를 두는 방안을 다시 검토한다.

### 잠정 입력 정책

기능 구현과 경계값 테스트를 시작할 수 있도록 다음 값을 잠정 기준으로 사용한다. 기획 결정 전까지 구현을 보류하지 않고 이 값을 적용하며, 관련 테스트도 현재 값을 기준으로 작성한다.

|항목|잠정 기준|잘못된 입력|
|---|---|---|
|브랜드명|앞뒤 공백 제거 후 1~100자|`400 INVALID_BRAND_NAME`|
|상품명|앞뒤 공백 제거 후 1~100자|`400 INVALID_PRODUCT_NAME`|
|상품 가격|1~100,000,000원인 정수|`400 INVALID_PRODUCT_PRICE`|
|주문 수량|품목별 1 이상의 정수|`400 INVALID_ORDER_QUANTITY`|
|재고 수량|0 이상의 정수|`400 INVALID_STOCK_QUANTITY`|
|포인트 충전액|1 이상의 정수|`400 INVALID_POINT_AMOUNT`|
|모든 목록 페이지|`page=0`, `size=20`, 최대 `size=100`|`page < 0`, `size < 1` 또는 `size > 100`이면 `400 INVALID_PAGE_REQUEST`|

수량 합산, 품목 금액, 주문 총액, 포인트 잔액 계산이 저장 타입의 표현 범위를 넘으면 `400 NUMERIC_OVERFLOW`로 거절한다. 브랜드명·상품명·가격 범위, 페이지 기본값·최대 크기, 상품의 초기 재고 0과 브랜드 필터 결과 처리는 요구사항에 명시되지 않은 구현용 가이드이므로 **기획 확인이 필요하다**. 모든 목록의 페이지·정렬 통일 역시 상품 목록 외에는 추가로 선택한 잠정 API 정책이다. 값이나 처리 방식이 변경되면 API 계약과 관련 테스트를 함께 수정한다.

모든 목록은 선택적 `page`, `size`, `sort`를 받으며 기본 정렬은 `latest`로 한다. 상품 목록의 정렬별 기준과 동률의 보조 정렬은 다음과 같다.

|정렬 값|주 정렬|동률의 보조 정렬|
|---|---|---|
|`latest`|생성 시각 내림차순|상품 ID 내림차순|
|`price_asc`|가격 오름차순|상품 ID 오름차순|
|`likes_desc`|좋아요 수 내림차순|상품 ID 내림차순|

상품 목록을 제외한 내 좋아요·내 주문·관리자 브랜드·상품·주문 목록은 `latest`와 `oldest`만 지원한다. 정렬 기준 모델은 내 좋아요 목록은 Like, 내 주문과 관리자 주문 목록은 Order, 관리자 브랜드 목록은 Brand, 관리자 상품 목록은 Product다. `latest`는 각 모델의 생성 시각 내림차순과 ID 내림차순, `oldest`는 생성 시각 오름차순과 ID 오름차순으로 정렬한다. `sort`를 생략하면 `latest`를 사용하고, 해당 목록에서 지원하지 않는 값은 `400 INVALID_SORT`로 거절한다. 이 기본값과 보조 정렬 기준도 **기획 확인이 필요한 잠정 정책**이며, 모든 목록으로 페이지 조회를 확대한 이유와 비용은 [부록 A.14](./appendix-decisions.md#a14-목록-응답과-페이지-조회의-범위)에서 비교한다.

### 주문 금액 규칙

- 상품 가격, 주문 당시 단가, 품목 금액, 주문 총액과 결제액은 도메인 내부에서 `Money(long 원)`로 표현한다. Money는 0을 허용하지만 음수 금액은 허용하지 않으며, 덧셈·수량 곱셈에서 `long` 범위를 넘으면 `400 NUMERIC_OVERFLOW`로 거절한다. 상품 가격이 허용 범위를 벗어나면 기존 계약대로 `400 INVALID_PRODUCT_PRICE`로 응답해야 하며, Money의 음수 거절 규칙이 이 오류 코드를 대체하지 않는다. 주문 수량의 양수 조건은 주문 입력·모델의 규칙으로 검증한다.
- 포인트 잔액과 포인트 사용액은 Money로 표현하지 않는다. 현재 정책에서는 차감한 포인트 수치가 결제한 원화 금액의 수치와 같으며, 주문 확정 시 Order가 이를 비교한다.
- 주문 생성 요청은 상품 식별자와 수량만 받는다. 단가는 요청값을 신뢰하지 않고 주문 생성 시점의 Product 가격을 OrderItem에 저장한다.
- 주문 총액은 각 OrderItem의 `수량 × 주문 당시 단가`로 계산한 품목 금액의 합이다.
- 주문 생성 시에는 포인트와 재고를 차감하지 않고 `DRAFT`로 저장한다.
- 주문 확정 시 주문 총액 전부를 포인트로 차감하며, 사용할 포인트 금액은 별도로 입력받지 않는다.
- 포인트 사용액은 주문 확정 시 실제로 차감한 포인트이고, 결제액은 포인트로 결제된 금전적 가치다. 현재 전액 포인트 결제 정책에서는 주문 총액, 포인트 사용액과 결제액의 수치가 서로 같다.
- 주문 확정과 결제의 성공 결과는 Order의 `CONFIRMED` 상태로 기록한다.

### API 요청·응답 DTO 스키마

아래 필드명과 구조를 API 계약으로 사용한다. ID·금액·수량·좋아요 수는 Java DTO에서 `Long`으로 표현하고, 도메인 내부의 Money는 원 단위 `Long` 값으로 변환해 전달한다. `status`는 문자열 Enum 값으로 반환한다. 응답 DTO에는 아래에 명시한 필드만 포함하며 Entity, 생성·수정 시각과 내부 이력 필드를 직접 노출하지 않는다. 고객용 DTO와 관리자용 DTO의 필드가 같더라도 API 경계를 구분하기 위해 각각 `{Domain}V1Dto`와 `{Domain}AdminV1Dto`에 선언한다. 관리자 주문 응답 안의 품목도 고객 품목 응답과 필드가 같지만 같은 이유로 `AdminOrderItemResponse`를 따로 선언하고 고객용 응답 모델을 참조하지 않는다.

요청 본문 모델은 다음과 같다. 경로 변수와 `X-USER-ID`는 본문 필드에 중복해서 받지 않는다.

|요청 모델|필드|
|---|---|
|`BrandSaveRequest`|`name: String`|
|`ProductCreateRequest`|`brandId: Long`, `name: String`, `price: Long`|
|`ProductUpdateRequest`|`name: String`, `price: Long`|
|`StockUpdateRequest`|`quantity: Long`|
|`PointChargeRequest`|`amount: Long`|
|`OrderItemRequest`|`productId: Long`, `quantity: Long`|
|`OrderCreateRequest`|`items: List<OrderItemRequest>`|

응답 모델의 정확한 필드는 다음과 같다.

|응답 모델|필드|
|---|---|
|`BrandResponse`|`id: Long`, `name: String`|
|`AdminBrandResponse`|`id: Long`, `name: String`|
|`ProductResponse`|`id: Long`, `brandId: Long`, `brandName: String`, `name: String`, `price: Long`, `likeCount: Long`, `stockQuantity: Long`|
|`AdminProductResponse`|`id: Long`, `brandId: Long`, `brandName: String`, `name: String`, `price: Long`, `stockQuantity: Long`|
|`LikeResponse`|`id: Long`, `userId: Long`, `productId: Long`|
|`PointResponse`|`balance: Long`|
|`StockResponse`|`productId: Long`, `quantity: Long`|
|`OrderItemResponse`|`productId: Long`, `quantity: Long`, `unitPrice: Long`, `amount: Long`|
|`OrderResponse`|`id: Long`, `status: String`, `orderTotal: Long`, `usedPointAmount: Long?`, `paymentAmount: Long?`, `items: List<OrderItemResponse>`|
|`AdminOrderItemResponse`|`productId: Long`, `quantity: Long`, `unitPrice: Long`, `amount: Long`|
|`AdminOrderResponse`|`id: Long`, `userId: Long`, `status: String`, `orderTotal: Long`, `usedPointAmount: Long?`, `paymentAmount: Long?`, `items: List<AdminOrderItemResponse>`|

고객 상품 조회의 `stockQuantity`는 조회 시점의 현재 재고 수량이다. 상품 목록·상세와 내 좋아요 목록에서 같은 `ProductResponse`를 사용한다. 별도의 품절 여부 필드는 이번 구현에서 우선 제공하지 않으며, 필요 여부는 기획 확인이 필요한 잠정 정책이다. 조회 이후 재고가 변경될 수 있으므로 주문 확정 시에는 저장된 OrderItem을 기준으로 재고를 다시 검증한다.

`usedPointAmount`와 `paymentAmount`는 `DRAFT` 주문의 응답 객체에서 null이며 실제 JSON에서는 생략한다. `CONFIRMED` 주문에서는 확정 시 기록한 값을 반환한다. 상품 이름은 OrderItem의 주문 시점 스냅샷으로 저장하지 않으므로 주문 응답은 상품 식별자만 제공하며, 현재 Product 이름을 주문 당시 정보처럼 조합하지 않는다.

각 API의 `ApiResponse.data` 타입은 다음과 같다.

|API|data 타입|
|---|---|
|고객 브랜드 상세|`BrandResponse`|
|고객 상품 목록|`PageResponse<ProductResponse>`|
|고객 상품 상세|`ProductResponse`|
|좋아요 등록|`LikeResponse`|
|좋아요 취소|`null`|
|내 좋아요 목록|`PageResponse<ProductResponse>`|
|포인트 충전·잔액 조회|`PointResponse`|
|주문 생성·확정·내 주문 상세|`OrderResponse`|
|내 주문 목록|`PageResponse<OrderResponse>`|
|관리자 브랜드 목록|`PageResponse<AdminBrandResponse>`|
|관리자 브랜드 등록·상세·수정|`AdminBrandResponse`|
|관리자 브랜드 삭제|`null`|
|관리자 상품 목록|`PageResponse<AdminProductResponse>`|
|관리자 상품 등록·상세·수정|`AdminProductResponse`|
|관리자 상품 삭제|`null`|
|관리자 재고 변경|`StockResponse`|
|관리자 전체 주문 목록|`PageResponse<AdminOrderResponse>`|
|관리자 주문 상세|`AdminOrderResponse`|

## 5.2 고객 API

모든 고객 API는 `X-USER-ID` 헤더를 필수로 받는다.

모든 고객 API는 공통 요청자 식별 단계에서 User 존재 여부까지 확인한다. 테스트에서는 User와 Point를 fixture로 테스트 DB에 저장한 뒤 생성된 User ID를 헤더에 사용하며, 저장되지 않은 ID는 `404 USER_NOT_FOUND`로 검증한다. 유효한 User가 다른 사람의 Like나 Order를 조회·변경하려는 경우에는 자원 소유권 규칙에 따라 각각 `LIKE_NOT_FOUND`, `ORDER_NOT_FOUND`로 응답한다.

### 브랜드·상품

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|브랜드 상세|`GET /api/v1/brands/{brandId}`|브랜드 ID|`200`, 활성 브랜드 정보|`BRAND_NOT_FOUND`|
|상품 목록|`GET /api/v1/products`|선택적 `brandId`, `page`, `size`, `sort`|`200`, 상품·브랜드·좋아요 수·현재 재고 수량과 페이지 정보|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|상품 상세|`GET /api/v1/products/{productId}`|상품 ID|`200`, 상품·브랜드·좋아요 수·현재 재고 수량|`PRODUCT_NOT_FOUND`|

고객 조회에는 삭제된 Brand와 Product를 노출하지 않는다. 상품 목록 요청에 `brandId`가 주어졌지만 해당 ID의 브랜드가 존재하지 않거나 삭제된 경우에는 `404`로 처리하지 않고 `200 OK`와 빈 페이지를 반환한다.

### 좋아요

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|좋아요 등록|`POST /api/v1/products/{productId}/likes`|상품 ID|`201`, 생성된 Like 관계|`PRODUCT_NOT_FOUND`, `LIKE_ALREADY_EXISTS`|
|좋아요 취소|`DELETE /api/v1/products/{productId}/likes`|상품 ID|`200`, 데이터 없는 성공 응답|`LIKE_NOT_FOUND`|
|내 좋아요 목록|`GET /api/v1/users/{userId}/likes`|경로의 사용자 ID, 선택적 `page`, `size`, `sort`|`200`, 활성 상품에 대한 자신의 좋아요 페이지|`USER_NOT_FOUND`, `INVALID_PAGE_REQUEST`, `INVALID_SORT`|

- 같은 User와 Product의 Like는 하나만 존재한다. 중복 등록은 `409 LIKE_ALREADY_EXISTS`로 거절하고 좋아요 수를 변경하지 않는다.
- `LikeFacade`는 등록 시 `ProductRepository`로 Product의 존재와 삭제 여부를 확인하지만 Product를 변경하지 않고 Like만 생성해 저장한다.
- 취소할 Like 관계가 없으면 `404 LIKE_NOT_FOUND`로 응답한다.
- 삭제된 Product에는 새 Like를 등록할 수 없고 내 좋아요 목록에서도 제외한다. 다만 삭제 전에 생성한 자신의 Like 관계는 취소할 수 있으므로, 이 경우 Product의 삭제 여부와 관계없이 Like를 찾아 삭제한다.
- 내 좋아요 목록에서 `X-USER-ID`는 요청자를, 경로의 `{userId}`는 조회 대상을 식별한다. 공통 경계에서 확인한 요청자 ID와 경로의 사용자 ID를 비교하고, 두 값이 다르면 다른 사용자의 관계를 노출하지 않고 `404 USER_NOT_FOUND`로 응답한다. 두 값이 같으면 `LikeFacade`가 `ProductQueryRepository`로 자신의 좋아요 상품 목록을 조회한다.

### 포인트

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|포인트 충전|`POST /api/v1/points/charge`|본문 `amount`|`200`, 충전 후 잔액|`INVALID_POINT_AMOUNT`, `NUMERIC_OVERFLOW`, `POINT_NOT_INITIALIZED`|
|포인트 잔액 조회|`GET /api/v1/points`|추가 입력 없음|`200`, 현재 잔액|`POINT_NOT_INITIALIZED`|

1포인트는 1원으로 계산한다. 잔액 0은 유효하지만 충전 요청 0은 유효하지 않다. 공통 요청자 식별 단계에서 User 존재 여부를 확인하며, 존재하는 User에게 fixture로 함께 준비되어야 할 Point가 없으면 `PointFacade`는 새 Point를 만들지 않고 데이터 불변식 위반인 `500 POINT_NOT_INITIALIZED`로 응답한다. Point 조회·저장과 트랜잭션은 PointFacade가 담당하고, 충전 금액 검증과 잔액 변경은 PointModel이 담당한다.

충전은 주문 결제와 같은 사용자 Point 행의 비관적 잠금에 참여해 보호된 잔액에서 변경·History를 같은 트랜잭션으로 저장한다. 일반 잔액 조회는 비잠금으로 유지한다.

### 주문

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|주문 생성|`POST /api/v1/orders`|본문 `items[{productId, quantity}]`|`201`, 품목·주문 총액과 `DRAFT` 상태|`INVALID_ORDER_ITEMS`, `INVALID_ORDER_QUANTITY`, `PRODUCT_NOT_FOUND`, `NUMERIC_OVERFLOW`|
|주문 확정|`POST /api/v1/orders/{orderId}/confirm`|주문 ID, 요청 본문 없음|`200`, 품목·주문 총액·포인트 사용액·결제액과 `CONFIRMED` 상태|`ORDER_NOT_FOUND`, `ORDER_NOT_CONFIRMABLE`, `PRODUCT_NOT_FOUND`, `INSUFFICIENT_POINT`, `INSUFFICIENT_STOCK`|
|내 주문 목록|`GET /api/v1/orders`|선택적 `page`, `size`, `sort`|`200`, 품목·금액·상태·결제액을 포함한 자신의 주문 페이지|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|내 주문 상세|`GET /api/v1/orders/{orderId}`|주문 ID|`200`, 품목별 상품 ID·수량·주문 당시 단가·품목 금액, 주문 총액·포인트 사용액·결제액과 상태|`ORDER_NOT_FOUND`|

- 주문 품목은 하나 이상이어야 하며 각 요청 품목의 수량이 1 이상인지 먼저 검증한다. 그다음 같은 Product의 수량을 합산하고 표현 범위를 확인해 하나의 OrderItem으로 저장한다. 중복 품목을 거절하는 대안과 합산을 선택한 이유는 [부록 A.8](./appendix-decisions.md#a8-주문의-중복-상품-품목-처리)에서 비교한다.
- 주문 생성 시 `OrderFacade`가 트랜잭션 안에서 활성 Product를 조회하고 요청한 Product가 모두 존재하는지 확인한 뒤 Order를 저장한다. 순수 Domain Service인 `OrderService`는 품목별 양수 수량 검증, 중복 수량 병합과 합산 오버플로 검증, 준비된 Product의 주문 당시 가격을 사용한 OrderItem·초안 Order 생성을 담당한다. 이 과정에서는 Product·재고·포인트를 변경하지 않는다.
- `DRAFT` 주문의 포인트 사용액과 결제액은 아직 결제가 발생하지 않았으므로 응답 객체에서 null이며 실제 JSON에서는 생략한다. `CONFIRMED` 주문에는 주문 확정 시 기록한 값을 반환한다.
- 주문 확정은 저장된 OrderItem을 기준으로 처리한다. 고객이 소유한 `DRAFT` 주문만 확정할 수 있으며, 이미 확정된 주문은 `409 ORDER_NOT_CONFIRMABLE`로 거절한다.
- 주문이 없거나 요청자가 소유자가 아니면 모두 `404 ORDER_NOT_FOUND`로 응답한다.
- 포인트나 어느 한 상품의 재고가 부족하면 각각 `409 INSUFFICIENT_POINT`, `409 INSUFFICIENT_STOCK`으로 응답하고 주문 확정 과정의 모든 변경을 롤백한다.
- 최초 확정의 소유권·DRAFT 확인부터 전체 commit까지 `Order → Point → Product(ID 오름차순)` 비관적 잠금 규칙을 적용한다. 동일 주문의 동시 확정은 성공 1건과 나머지 `ORDER_NOT_CONFIRMABLE`이며 추가 차감·History가 없다. 새 요청 키·성공 응답 재사용은 제공하지 않는다.
- 상품 존재·삭제 여부는 잠금 조회로 다시 검증한다. Point 부족 등과 상품 삭제가 함께 있는 복합 오류의 기존 노출 순서는 별도 계약으로 보존하지 않는다. Point 검증·사용 후 상품 오류가 나더라도 해당 요청의 전체 변경은 rollback한다. 단독 상품 삭제 오류를 검증할 때에는 충분한 Point를 준비한다.
- commit 전 업무 거절·중간 실패로 rollback된 요청 자신의 부분 변경은 남지 않지만 공유 자원에 대한 다른 성공 요청의 변경은 유지한다. 동일 주문 거절 뒤 공유 Order는 성공자의 CONFIRMED이며, 이미 CONFIRMED인 주문 거절도 기존 결제 결과를 유지한다. commit 중 통신 장애의 결과 불확실성은 5.1의 공통 실패 계약을 따른다. 상세 보호·쿼리 선택·검증 기준은 [4.3](./04-use-cases.md#43-주문-확정)을 따른다.

## 5.3 관리자 API

모든 관리자 API는 Spring Security에서 인증된 `ADMIN` 역할을 요구한다.

### 브랜드

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|브랜드 목록|`GET /api-admin/v1/brands`|선택적 `page`, `size`, `sort`|`200`, 활성 브랜드 페이지|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|브랜드 등록|`POST /api-admin/v1/brands`|본문 `name`|`201`, 등록한 브랜드|`INVALID_BRAND_NAME`|
|브랜드 상세|`GET /api-admin/v1/brands/{brandId}`|브랜드 ID|`200`, 활성 브랜드 상세|`BRAND_NOT_FOUND`|
|브랜드 수정|`PUT /api-admin/v1/brands/{brandId}`|본문 `name`|`200`, 수정한 브랜드|`BRAND_NOT_FOUND`, `INVALID_BRAND_NAME`|
|브랜드 삭제|`DELETE /api-admin/v1/brands/{brandId}`|브랜드 ID|`200`, 브랜드와 연결 미삭제 상품 삭제 완료, JSON의 `data` 필드 생략|`BRAND_NOT_FOUND`|

`BrandFacade`가 Brand와 연결된 모든 미삭제 Product를 같은 트랜잭션에서 Soft Delete한다. 재고 0인 상품도 포함하고, 연결 상품이 없는 활성 Brand도 성공한다. 상품 존재에 따른 `409 BRAND_HAS_ACTIVE_PRODUCTS`는 더 이상 이 API의 거절 사유가 아니다. 없는·이미 삭제된 Brand는 기존 `404 BRAND_NOT_FOUND`를 유지한다. 다른 Brand·Product, 과거 주문의 품목·금액·결제 결과는 보존한다.

중간 실패는 일부 성공으로 응답하지 않으며 이번 요청의 Brand·Product 변경을 모두 rollback한다. 상세 호출 순서, 삭제 이후 행동, 동시성 보장 한계와 성능 재검토 기준은 [4.1](./04-use-cases.md#41-브랜드와-연관-상품-일괄-삭제)을 따른다. 이번에는 상품 수 상한이나 비동기 접수 응답을 추가하지 않는다.

### 상품·재고

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|상품 목록|`GET /api-admin/v1/products`|선택적 `page`, `size`, `sort`|`200`, 활성 상품 페이지|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|상품 등록|`POST /api-admin/v1/products`|본문 `brandId`, `name`, `price`|`201`, 재고 0으로 등록한 상품|`INVALID_REQUEST`, `BRAND_NOT_FOUND`, `INVALID_PRODUCT_NAME`, `INVALID_PRODUCT_PRICE`|
|상품 상세|`GET /api-admin/v1/products/{productId}`|상품 ID|`200`, 활성 상품 상세|`PRODUCT_NOT_FOUND`|
|상품 수정|`PUT /api-admin/v1/products/{productId}`|본문 `name`, `price`|`200`, 수정한 상품|`PRODUCT_NOT_FOUND`, `INVALID_PRODUCT_NAME`, `INVALID_PRODUCT_PRICE`|
|상품 삭제|`DELETE /api-admin/v1/products/{productId}`|상품 ID|`200`, 데이터 없는 성공 응답|`PRODUCT_NOT_FOUND`|
|재고 변경|`PUT /api-admin/v1/products/{productId}/stock`|본문 `quantity`|`200`, 변경 후 최종 재고 수량|`PRODUCT_NOT_FOUND`, `INVALID_STOCK_QUANTITY`|

- `ProductFacade`는 등록 시 `BrandRepository`로 Brand의 존재와 삭제 여부를 확인하지만 Brand를 변경하지 않고 초기 Stock이 0인 Product만 생성해 저장한다.
- Product 수정은 Brand를 변경하지 않는다. 수정 요청에도 `brandId`를 받지 않으며 기존 관계를 유지한다.
- 재고 변경의 `quantity`는 증감량이 아니라 변경 후의 최종 수량이다. Stock은 변경 전후 수량과 변경량을 담은 StockChange를 반환하고, `StockHistoryModel.changedByAdmin(productId, change)`가 관리자 변경 원인을 포함한 이력을 생성한다.
- 삭제된 Product는 수정과 재고 변경의 대상이 될 수 없으며 `404 PRODUCT_NOT_FOUND`로 응답한다.
- `ProductFacade.update/delete/changeStock`은 주문 차감과 같은 Product 행의 비관적 잠금 규칙에 참여한다. 대상 조회·잠금 후 기존 존재/활성·입력 검증 순서를 유지하며, 관리자 최종 설정은 보호된 현재 재고의 before/after로 StockHistory를 남긴다. 상품 수정·삭제는 재고 이력을 추가하지 않고 저장 주문의 단가·수량·금액을 바꾸지 않는다. 브랜드 bulk 경쟁까지 보장 범위를 확장하지 않는다.

### 주문 조회

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|전체 주문 목록|`GET /api-admin/v1/orders`|선택적 `page`, `size`, `sort`|`200`, 구매자·품목·금액·상태·결제액을 포함한 전체 주문 페이지|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|주문 상세|`GET /api-admin/v1/orders/{orderId}`|주문 ID|`200`, 구매자·품목·상태·주문 총액·포인트 사용액·결제액|`ORDER_NOT_FOUND`|

관리자는 주문을 조회만 하며 상태를 변경하지 않는다.

### 잠정 관리자 조회 정책

Soft Delete된 Brand와 Product를 관리자 목록·상세에 포함할지는 기획 확인이 필요한 정책이다. 결정 전까지는 관리자 조회에도 활성 데이터만 반환하고, 삭제 데이터 조회는 이번 구현 범위에서 제외한다. 기획 결정으로 정책이 바뀌면 관리자 조회 계약과 테스트를 함께 수정한다. 대안별 비용과 잠정 선택의 근거는 [부록 A.13](./appendix-decisions.md#a13-관리자-조회의-삭제-데이터-노출-정책)에서 비교한다.

이 잠정 정책과 무관하게 삭제된 대상의 수정·재고 변경·재삭제는 허용하지 않는다.

## 5.4 대표 규칙의 테스트 기대값

아래 표는 API 전체 테스트 목록이 아니라 구현 전에 기대값을 고정해야 하는 대표 사례다. 실패 사례는 HTTP 응답뿐 아니라 기존 상태가 유지되는지도 함께 확인한다.

|규칙|주어진 상태와 요청|기대 결과|
|---|---|---|
|숫자가 아닌 요청자 식별자|고객 API의 `X-USER-ID`에 `jop0522` 전달|`400 INVALID_REQUEST`, 기능별 조회·상태 변경을 진행하지 않음|
|없는 요청자 거절|모든 고객 API에 저장되지 않은 User ID를 `X-USER-ID`로 전달|`404 USER_NOT_FOUND`, 기능별 조회·상태 변경을 진행하지 않음|
|목록 페이지·정렬 `[잠정]`|상품 외 목록에 `page=0`, `size=2`, `sort=oldest` 요청|생성 시각·ID 오름차순의 첫 두 항목과 전체 자원 수를 `PageResponse`로 반환|
|주문 목록의 품목|품목이 여러 개인 주문을 포함한 내 주문 또는 관리자 주문 목록 조회|Order 단위로 페이지를 나누고 해당 주문의 모든 OrderItem을 포함|
|포인트 충전|잔액 0, 충전액 10,000|잔액 10,000과 PointHistory 저장|
|유효하지 않은 충전|잔액 1,000, 충전액 0|`400 INVALID_POINT_AMOUNT`, 잔액과 History 유지|
|포인트 초기화 불변식|존재하는 User에게 Point가 없는 상태에서 잔액 조회 또는 충전|`500 POINT_NOT_INITIALIZED`, Point와 PointHistory를 새로 생성하지 않음|
|브랜드 필터 결과 `[잠정]`|존재하지 않거나 삭제된 `brandId`로 상품 목록 조회|`200 OK`, 비어 있는 페이지 반환|
|고객 상품의 재고 수량|재고 5인 상품을 조회한 뒤 관리자가 최종 재고를 2로 변경하고 다시 조회|상품 목록·상세와 내 좋아요 목록의 `stockQuantity`에 조회 시점의 수량을 반환하며, 주문 확정 시에도 재고를 다시 검증|
|좋아요 중복 방지|이미 Like가 있는 상품에 재등록|`409 LIKE_ALREADY_EXISTS`, 관계와 좋아요 수 유지|
|삭제 상품 좋아요 취소|삭제 전 생성한 자신의 Like 취소|성공하고 Like 관계 삭제|
|중복 주문 품목 합산|같은 상품을 수량 2와 3으로 요청|수량 5인 OrderItem 하나로 저장하고 총수량 기준으로 금액 계산|
|주문 생성과 차감 분리|유효한 여러 품목으로 주문 생성|`DRAFT` 저장, 재고와 포인트는 유지|
|DRAFT 주문 결제 정보|`DRAFT` 주문 상세 조회|주문 총액·DRAFT 상태 반환, 포인트 사용액과 결제액은 내부 null이며 JSON에서는 생략|
|주문 확정 성공|총액 7,000, 포인트 잔액 10,000, 충분한 재고|차감 후 포인트 잔액 3,000, 포인트 사용액 7,000, 결제액 7,000, 품목별 재고 차감, PointHistory와 품목별 StockHistory 저장, `CONFIRMED`|
|포인트 부족|주문 총액보다 포인트가 적음|`409 INSUFFICIENT_POINT`, 주문·포인트·재고·History 유지|
|재고 부족|충분한 Point, 한 품목의 재고가 주문 수량보다 적음|`409 INSUFFICIENT_STOCK`, 해당 요청의 주문·포인트·모든 재고·History 변경 없음|
|주문 중복 확정|이미 `CONFIRMED`인 주문 확정|`409 ORDER_NOT_CONFIRMABLE`, 모든 상태 유지|
|브랜드 일괄 삭제|재고 0과 양수인 미삭제 Product가 연결된 Brand 삭제|`200 OK`, Brand와 연결 미삭제 Product 삭제·비노출, 다른 대상과 과거 주문 유지|
|빈 브랜드 삭제|연결 Product가 없는 활성 Brand 삭제|`200 OK`, Brand 삭제|
|브랜드 삭제 중간 실패|실제 Product bulk UPDATE 후 다음 Brand 저장 경계에서 예외|예외 전파, 새 DB 조회에서 Brand와 대상 Product 전체 원상태|
|브랜드 삭제 후 DRAFT 확정|삭제 전 만든 DRAFT의 상품이 Brand 일괄 삭제되어 commit됨, 충분한 Point|`404 PRODUCT_NOT_FOUND`, 해당 요청의 차감·확정·History 없음, 저장 품목·금액 보존|
|상품 재고 변경|현재 수량 5, 최종 수량 2 요청|재고 2와 변경 전후 값을 가진 StockHistory 저장|
|삭제 대상 재삭제|Soft Delete된 Brand 또는 Product 삭제|각각 `404 BRAND_NOT_FOUND`, `404 PRODUCT_NOT_FOUND`|
|숫자 범위 초과|수량 합산이나 주문 총액 계산이 표현 범위를 초과|`400 NUMERIC_OVERFLOW`, 주문과 관련 상태를 저장하지 않음|

3주차 동시성·실제 SQL 뒤 기술 실패의 준비·실행·판정 기준과 완료 근거는 [4.3.5](./04-use-cases.md#435-구현-단계-검증-계획)에 둔다. 대표 HTTP 오류는 DB 결과와 연결하되 경쟁 전체를 HTTP에서 중복 검증하지 않으며, 관련 유효 테스트·Checkstyle·ArchUnit은 유지한다. 이 표는 기대값을 정의하며 실행 결과를 대신하지 않는다. 브랜드 일괄 삭제는 2026-10-07 검증 완료했으며 근거·한계는 [4.1](./04-use-cases.md#41-브랜드와-연관-상품-일괄-삭제)에 둔다. 주문 확정·동시성도 같은 날 구현·검증했으며 근거·한계는 4.3.5에 둔다.
