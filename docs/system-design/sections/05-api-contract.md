# 5. API 계약과 주요 규칙

이 장은 구현할 API의 입력, 성공 결과와 대표 오류를 정의한다. 모든 조합을 테스트 사례로 나열하기보다 도메인 규칙과 HTTP 계약을 연결하고, 구현 단계에서는 이 계약으로부터 정상 경로와 경계값·실패 테스트를 도출한다. PG 결제 내용은 구현 전의 목표 계약이며, `[미정]` 항목은 승인 전이므로 구현 기준으로 채택하지 않는다.

PG 응답의 선택 이유는 [A.24](./appendix-decisions.md#a24-결제-상태확인-상태금액의-공개-표현), HTTP·오류는 [A.25](./appendix-decisions.md#a25-http오류멱등-응답의-의미), 식별자·입력은 [A.26](./appendix-decisions.md#a26-식별자합성-입력외부-정보의-노출-범위)에 대안·비용·재검토 조건을 기록한다.

## 5.1 공통 계약과 입력 정책

### 요청자 식별과 접근 범위

- 고객 API는 `X-USER-ID` 헤더에 테스트 DB에 준비된 `UserModel`의 숫자 ID(`Long`)를 전달해 요청자를 식별한다. 이 값은 `jop0522` 같은 로그인 아이디가 아니다. `X-USER-ID`는 로컬 환경에서 요청자를 식별하기 위한 값이며, 로그인·인증 기능을 의미하지 않는다. interfaces 계층의 공통 요청자 식별 처리는 모든 고객 요청에서 헤더의 존재·숫자 형식과 User 존재 여부를 확인하고, 검증한 `userId`를 Controller에 전달한다. 헤더가 누락되거나 숫자로 변환할 수 없으면 `400 INVALID_REQUEST`, 숫자 ID에 해당하는 User가 없으면 `404 USER_NOT_FOUND`로 응답한다. 존재 여부 조회는 application의 `UserFacade`가 `UserRepository`를 통해 담당하며 각 기능의 Facade에서 같은 검증을 반복하지 않는다.
- 고객은 자신의 좋아요·포인트·주문만 조회하거나 변경할 수 있다. 다른 사용자의 소유 자원은 존재 여부를 노출하지 않고 해당 자원의 `NOT_FOUND` 오류로 처리한다.
- 관리자 API는 `/api-admin/**` 경로에 적용한 Spring Security 설정으로 구분한다. `ADMIN` 역할이 없는 일반 사용자와 식별되지 않은 요청은 모두 `403 Forbidden`으로 거절한다. 이 설정은 로컬 환경과 MockMvc 검증을 위한 접근 경계이며 운영용 로그인·토큰 발급·계정 관리 방식을 의미하지 않는다.
- 이 경계는 `com.loopers.config.AdminBoundaryConfig`의 `SecurityFilterChain` 하나로 구현한다. `securityMatcher("/api-admin/**")`로 관리자 경로에만 적용하고 `hasRole("ADMIN")`을 요구하며, 인증되지 않은 요청도 `401`이 아닌 `403`으로 응답하도록 `authenticationEntryPoint`에서 `sendError(403)`을 사용한다. 관리자용 SecurityFilterChain은 고객 API 요청에 적용되지 않으므로 기존 `X-USER-ID` 식별 규칙을 그대로 유지한다. CSRF 보호는 기본값 그대로 두며, 관리자 변경 요청(POST·PUT·DELETE) 테스트는 `SecurityMockMvcRequestPostProcessors.csrf()`로 유효한 CSRF 입력을 함께 보낸다. 역할 거절 테스트에도 유효한 CSRF 입력을 사용해, 거절 사유가 CSRF가 아니라 요청자 구분임을 확인한다.
- 이 설정은 관리자 경로에 접근 경계를 적용할 뿐 네트워크용 관리자 로그인을 제공하지 않는다. 관리자 API의 실행 검증은 MockMvc로 수행한다. 로컬 실행에는 `local`·`test` 프로파일에 `server.address: 127.0.0.1`을 적용해 외부에 노출하지 않는다.
- 커머스의 고객·주문·상품 등 내부 ID를 받는 경로 변수·쿼리 파라미터와 고객 `X-USER-ID`는 숫자 형식이어야 한다. PG의 providerOrderId·transactionKey·paymentAttemptKey는 별도 문자열 계약이며 이 숫자 ID 규칙의 대상이 아니다. 내부 숫자 ID가 숫자로 변환할 수 없으면 모두 `400`으로 거절하며, 안정적인 오류 코드는 검증 위치에 따라 다르다. `X-USER-ID` 헤더는 interfaces의 공통 요청자 식별 처리가 직접 검증하므로 `400 INVALID_REQUEST`로 응답한다. 경로 변수와 쿼리 파라미터의 숫자 변환 실패는 Spring이 Controller 진입 전에 발생시키는 `MethodArgumentTypeMismatchException`을 기존 `ApiControllerAdvice`가 처리하므로 `ErrorType.BAD_REQUEST`의 코드(`Bad Request`)를 유지한다. 숫자로 변환된 이후 대상이 존재하는지는 각 API의 조회 규칙에 따라 판단한다.

콜백은 고객의 주문 조작 요청이 아닌 별도 수신 경계다. 고객 `X-USER-ID`와 User 존재 조회를 콜백 인증으로 사용하지 않는다. PG 요청에 사용하는 합성 제공자 계정 `X-USER-ID: 135135`도 내부 고객 식별자를 대신하지 않는다.

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

- 조회와 완료 결과 반환은 `200 OK`, 새 Brand·Product·Like·Order 생성은 `201 Created`로 응답한다. PG 결제가 대기·결과 미확인이면 confirm/reconcile은 `202 Accepted`와 저장된 주문·결제 상태 및 조회 경로를 반환한다.
- `meta.result=SUCCESS`는 API 요청의 처리 결과다. 결제의 최종 성공은 주문·결제 상태로 판단하며 HTTP 2xx나 meta만으로 결제 성공을 추론하지 않는다.
- 삭제는 `200 OK`와 데이터 없는 `ApiResponse`로 응답한다. 내부 `data` 값은 null이며 실제 JSON에서는 해당 필드를 생략한다.
- 요청 형식과 값이 잘못된 경우 `400`, 대상이 없거나 접근할 수 없는 경우 `404`, 현재 상태나 중복 관계 때문에 수행할 수 없는 경우 `409`를 사용한다.
- 경로 변수로 대상을 지정하는 수정·변경 요청에서 본문 값 검증과 대상 조회가 모두 필요하면 대상 존재 여부를 먼저 판단한다. 존재하지 않거나 삭제된 대상에 잘못된 본문을 보낸 요청은 `400`이 아니라 `404`로 응답한다. Controller는 본문의 값 규칙을 직접 판단하지 않고 값을 그대로 도메인에 전달하며, 값 규칙 위반은 대상을 특정한 뒤에 드러난다. 상품 수정·재고 변경과 브랜드 수정이 여기에 해당한다.
- 생성 요청은 만들려는 대상이 아직 없으므로 이 순서를 적용하지 않고 각 API가 정한 순서를 따른다. 상품 등록은 본문의 `brandId`로 참조할 Brand를 먼저 조회하며, `brandId` 자체가 빠져 조회할 대상을 정할 수 없으면 `400 INVALID_REQUEST`로 거절한다. 주문 생성의 검증 순서는 [5.2](./05-api-contract.md#52-고객-api)의 주문 규칙에서 정한다.
- 요청과 무관하게 서버 내부 데이터의 불변식이 깨진 경우에는 `500`을 사용한다. Point 조회·충전에서 존재하는 User에게 Point가 없으면 `500 POINT_NOT_INITIALIZED`다. 신규 PG 주문에는 Point 존재 조건을 적용하지 않는다.
- 같은 HTTP 상태 안에서도 클라이언트가 실패 원인을 구분할 수 있도록 `PRODUCT_NOT_FOUND`, `LIKE_ALREADY_EXISTS`, `INSUFFICIENT_STOCK`과 같은 안정적인 업무 오류 코드를 사용한다. 기존 `ErrorType`을 유지하면서 각 업무 오류의 `HttpStatus`, 안정적인 코드와 기본 메시지를 추가한다. Domain은 발생한 업무 오류를 `CoreException`으로 표현하고, interfaces의 `ApiControllerAdvice`가 `ErrorType`의 정보를 사용해 실제 HTTP 응답을 생성한다.
- Soft Delete된 Brand·Product는 활성 자원을 대상으로 하는 API에서 존재하지 않는 것으로 처리한다. 이미 삭제된 대상을 다시 삭제하는 요청도 각각 `404 BRAND_NOT_FOUND`, `404 PRODUCT_NOT_FOUND`로 응답한다.
- commit 전 업무 거절·중간 RuntimeException이 전파되면 해당 로컬 TX의 Entity·History 변경은 rollback한다. PG 결제는 재고·시도를 준비하는 TX A와 외부 결과를 반영하는 TX B로 분리하며, TX A가 commit된 뒤의 PG 오류가 TX A를 자동 rollback하지 않는다.
- 확정 실패의 재고 반환은 별도 로컬 TX에서 수행하는 보상 처리다. 일반 HTTP 오류·timeout·조회 0건만으로 실패·반환을 결정하지 않는다. TX B의 성공·실패 반영과 예약 소비·반환은 같은 TX에서 보호한다.
- deadlock·lock timeout·DB 저장/commit 실패는 재고·포인트 부족이나 PG 확정 실패로 바꾸지 않는다. 기존 HTTP 500과 `ErrorType.INTERNAL_ERROR`를 유지하며 실제 `meta.errorCode`는 `Internal Server Error`다. 오류 응답만으로 commit 여부를 단정하지 않고, 이미 저장된 시도와 기존 외부 거래를 확인한다.
- TX A 전 생성 회로 차단은 503 PG_CREATE_UNAVAILABLE이며 주문 DRAFT·결제 행 없음·재고 효과 없음이다. 회복 후 고객이 같은 주문/키로 다시 confirm할 수 있다. TX A 후 실제 회로의 POST 진입 전 거절과 생성 POST의 확실한 최초 연결 풀 획득 timeout만 LOCAL_NOT_DISPATCHED로 확정하여 반환·PAYMENT_FAILED·HTTP 200으로 종결한다(원인 CIRCUIT_REJECTED/POOL_ACQUIRE_TIMEOUT, 판정은 [2.8](./02-architecture.md#생성-회로와-사전-차단)). 일반 timeout·HTTP 오류에는 이 정책을 적용하지 않는다.
- PG create 자동 재시도는 금지한다. 동일 시도 confirm 재요청은 저장 상태를 반환하고, reconcile은 기존 거래만 조회한다. 조회는 요청당 PG GET 최대 1회·자동 retry/polling/sleep 없음의 승인된 [2.8 정책](./02-architecture.md#28-pg-http-도구와-테스트-서버의-구성)을 따르며, 단계별 HTTP 제한과 별도 전체 deadline을 두지 않는 범위는 [2.9](./02-architecture.md#29-http-제한과-pg-회로의-초기-정책)를 따른다. 이전 POINT 구현의 사용자 재요청 규칙을 새 PG 결제 발송 규칙으로 사용하지 않는다.
- 브랜드·상품·포인트 등 PG를 호출하지 않는 기존 변경의 로컬 TX 계약은 유지한다. 관련 구현 근거는 각 흐름을 따른다.

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

- 상품 가격·저장 단가·품목 금액·주문 총액·성공 결제액은 도메인 내부에서 `Money(long 원)`로 표현한다. Money는 0을 허용하고 음수는 거절한다. 덧셈·수량 곱셈 overflow는 기존 `400 NUMERIC_OVERFLOW`, 상품 가격 범위 오류는 `400 INVALID_PRODUCT_PRICE`를 유지한다.
- 주문 생성 요청은 상품 ID·수량만 받는다. 활성 Product의 생성 당시 단가를 OrderItem에 저장하고 `수량 × 저장 단가`의 합으로 총액을 정한다. 이후 상품 가격으로 재계산하지 않는다.
- DRAFT 생성은 포인트·재고를 변경하지 않는다. confirm에서 저장 총액으로 PG 시도를 준비하며 PG 요청 금액을 고객 입력으로 받지 않는다. PG 호출 전 가용 재고를 선차감·예약한다.
- 신규 PG 주문은 포인트를 사용하지 않는다. PG 시도가 있으면 `pointAmount=0`이며 Point 잔액과 ORDER_USE 이력에 변화가 없다. Point 미생성·잔액 부족은 PG 결제 거절 사유가 아니다.
- 이전 POINT 주문의 실제 pointAmount·paidAmount·결과는 보존한다. 기존 전액 POINT 결제 기록에서는 두 수치와 주문 총액이 같지만 이를 신규 PG 결제 규칙으로 사용하지 않는다.
- PG 성공은 내부 주문 CONFIRMED로 반영하고 실제 성공 금액을 paidAmount로 반환한다. PG 대기·실패의 paidAmount는 null로 두어 JSON에서 생략한다. PG 시도의 요청 금액은 payment.amount로 별도 반환한다.

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
|`OrderConfirmRequest`|`paymentAttemptKey: String`, `cardType: String`, `cardNo: String`|
|`PaymentCallbackRequest`|`providerOrderId: String`, `transactionKey: String`, `status: String`|

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

DRAFT는 payment=null로 생략한다. PG 시도가 있는 주문은 위 PG 필드와 결제 상태를 반환한다. 이전 POINT 주문은 method=POINT·status=SUCCESS·orderUrl만 반환하고 과거 시도 키·PG 거래를 꾸며 만들지 않는다. verificationStatus는 payment.status와 별개다. NOT_CHECKED는 확인 전, VERIFIED는 유효한 PG 관찰 확인, UNAVAILABLE은 응답 유실·조회 불가·0건, CONFLICT는 복수 거래·식별/금액·종결 충돌을 뜻한다. PENDING 상태에서 조회에 실패해도 PENDING을 실패로 바꾸지 않고 verificationStatus로 확인 불가를 표현한다. TX A commit 뒤 PG 응답 전은 status=UNKNOWN·verificationStatus=NOT_CHECKED다. UNKNOWN만으로 발송 여부·timeout 발생을 단정하지 않는다. 공개 REQUESTED 상태는 추가하지 않는다. 최근 확인은 [3.6](./03-domain-model.md#36-pg-저장-모델과-제약)에 따라 DB에 마지막 반영한 관찰이다. 먼저 시작한 조회가 늦게 도착해 확인 정보를 갱신할 수 있으며, 결제 종결 보호와 무시할 늦은 관찰 규칙은 별도로 유지한다.

고객 orderUrl은 `/api/v1/orders/{orderId}`, 관리자 orderUrl은 `/api-admin/v1/orders/{orderId}`다. PG 주문의 reconcileUrl은 두 응답 모두 `/api/v1/orders/{orderId}/payment/reconcile`이다. URL 제공은 권한 부여가 아니며 대사 요청에는 고객 식별·주문 소유권 규칙을 그대로 적용한다.

고객 상품 조회의 `stockQuantity`는 조회 시점의 현재 가용 재고 수량이다. 상품 목록·상세와 내 좋아요 목록에서 같은 `ProductResponse`를 사용한다. 별도의 품절 여부 필드는 이번 구현에서 우선 제공하지 않으며, 필요 여부는 기획 확인이 필요한 잠정 정책이다. 조회 이후 재고가 변경될 수 있으므로 주문 확정 시에는 저장된 OrderItem을 기준으로 재고를 다시 검증한다.

`pointAmount`와 `paidAmount`는 결제 전 `DRAFT`의 응답 객체에서 null이며 JSON에서 생략한다. PG 시도가 있는 주문의 pointAmount는 0이고, 이전 POINT 주문은 저장된 실제 값을 반환한다. PG의 paidAmount는 성공 시에만 저장한 금액을 반환하고 대기·실패에서는 생략한다. 상품 이름은 OrderItem의 주문 시점 스냅샷으로 저장하지 않으므로 주문 응답은 상품 식별자만 제공하며, 현재 Product 이름을 주문 당시 정보처럼 조합하지 않는다.

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
|주문 생성·결제 시작·대사·내 주문 상세|`OrderResponse`|
|PG 콜백 수신|`null` — JSON data 생략|
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

이 절에 함께 기술한 PG 콜백은 고객 API가 아닌 외부 수신 경계이며 고객 헤더 규칙을 적용하지 않는다.

모든 고객 API는 공통 요청자 식별 단계에서 User 존재 여부까지 확인한다. 테스트에서는 저장된 User ID를 헤더에 사용하며, 저장되지 않은 ID는 `404 USER_NOT_FOUND`로 검증한다. Point API와 이전 POINT 기록에는 필요한 Point fixture를 준비하지만 PG 주문의 요청자에게 Point가 있어야 한다는 전제는 두지 않는다. 유효한 User가 다른 사람의 Like나 Order를 조회·변경하려는 경우에는 자원 소유권 규칙에 따라 각각 `LIKE_NOT_FOUND`, `ORDER_NOT_FOUND`로 응답한다.

### 브랜드·상품

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|브랜드 상세|`GET /api/v1/brands/{brandId}`|브랜드 ID|`200`, 활성 브랜드 정보|`BRAND_NOT_FOUND`|
|상품 목록|`GET /api/v1/products`|선택적 `brandId`, `page`, `size`, `sort`|`200`, 상품·브랜드·좋아요 수·현재 재고 수량과 페이지 정보|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|상품 상세|`GET /api/v1/products/{productId}`|상품 ID|`200`, 상품·브랜드·좋아요 수·현재 재고 수량|`PRODUCT_NOT_FOUND`|

고객 조회에는 삭제된 Brand와 Product를 노출하지 않는다. 상품 목록 요청에 `brandId`가 주어졌지만 해당 ID의 브랜드가 존재하지 않거나 삭제된 경우에는 `404`로 처리하지 않고 `200 OK`와 빈 페이지를 반환한다.

고객 상세의 상품명·Brand 이름·가격·재고만 JSON String cache-aside로 저장하며 좋아요 수는 DB 조회로 합성한다. 기존 응답 필드와 application 합성을 유지한다. 기본정보는 공통 afterCommit callback에서 commit 뒤 동기 DEL하고 시험 10초/평시 5분의 고정 TTL을 hit에서 연장하지 않는다. JSON 형식 오류는 miss·DB 재조회/재채움·로그, cache 후처리 실패만 있으면 DB 성공 결과를 유지한다. 좋아요 count key/증감은 없고 삭제 비노출·소유권·주문 판단은 완화하지 않는다. [4.4 상품 조회](./04-use-cases.md#44-상품-조회)의 실패·늦은 fill·실제 만료/재채움 계약을 따른다.

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

충전은 사용자 Point 행의 비관적 잠금을 유지하고 보호된 잔액에서 변경·History를 같은 트랜잭션으로 저장한다. 신규 PG 결제는 Point를 조회·잠금·차감하지 않는다. 일반 잔액 조회는 비잠금으로 유지한다.

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
- 이미 재고를 확보한 주문은 이후 상품 삭제와 구분해 결과를 마무리한다. 순서·저장 결과·중복 보호는 [주문 확정 흐름](./04-use-cases.md#43-주문-확정)을 따른다.

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
- 재고 변경의 `quantity`는 증감량이 아니라 예약을 제외한 가용 재고의 최종 수량이다. 주문 예약을 초기화하지 않는다. Stock은 변경 전후 수량과 변경량을 담은 StockChange를 반환하고, `StockHistoryModel.changedByAdmin(productId, change)`가 관리자 변경 원인을 포함한 이력을 생성한다.
- 삭제된 Product는 수정과 재고 변경의 대상이 될 수 없으며 `404 PRODUCT_NOT_FOUND`로 응답한다.
- `ProductFacade.update/delete/changeStock`은 주문 차감과 같은 Product 행의 비관적 잠금 규칙에 참여한다. 대상 조회·잠금 후 기존 존재/활성·입력 검증 순서를 유지하며, 관리자 최종 설정은 보호된 현재 재고의 before/after로 StockHistory를 남긴다. 상품 수정·삭제는 재고 이력을 추가하지 않고 저장 주문의 단가·수량·금액을 바꾸지 않는다. 브랜드 bulk 경쟁까지 보장 범위를 확장하지 않는다.

### 주문 조회

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|전체 주문 목록|`GET /api-admin/v1/orders`|선택적 `page`, `size`, `sort`|`200`, 구매자·품목·금액·상태·결제액을 포함한 전체 주문 페이지|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|주문 상세|`GET /api-admin/v1/orders/{orderId}`|주문 ID|`200`, 구매자·품목·상태·주문 총액·포인트 사용액·결제액|`ORDER_NOT_FOUND`|

관리자는 주문을 조회만 하며 상태를 변경하지 않는다. 고객 응답과 같은 의미의 payment 객체로 결제수단·시도 상태·최종 결과를 확인하고, 이전 POINT 결제 사실과 구매자 구분을 유지한다. 관리자 응답에도 고객용 reconcileUrl을 제공하지만, 대사를 수행하는 고객의 식별·소유권 검사를 생략하거나 관리자에게 대사 권한을 추가하지 않는다.

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
|브랜드 일괄 삭제|재고 0과 양수인 미삭제 Product가 연결된 Brand 삭제|`200 OK`, Brand와 연결 미삭제 Product 삭제·비노출, 다른 대상과 과거 주문 유지|
|빈 브랜드 삭제|연결 Product가 없는 활성 Brand 삭제|`200 OK`, Brand 삭제|
|브랜드 삭제 중간 실패|실제 Product bulk UPDATE 후 다음 Brand 저장 경계에서 예외|예외 전파, 새 DB 조회에서 Brand와 대상 Product 전체 원상태|
|브랜드 삭제 후 DRAFT 확정|삭제 전 만든 DRAFT의 상품이 Brand 일괄 삭제되어 commit됨, PG 시도 시작 전|`404 PRODUCT_NOT_FOUND`, 해당 요청의 차감·확정·History 없음, 저장 품목·금액 보존|
|상품 재고 변경|현재 수량 5, 최종 수량 2 요청|재고 2와 변경 전후 값을 가진 StockHistory 저장|
|삭제 대상 재삭제|Soft Delete된 Brand 또는 Product 삭제|각각 `404 BRAND_NOT_FOUND`, `404 PRODUCT_NOT_FOUND`|
|숫자 범위 초과|수량 합산이나 주문 총액 계산이 표현 범위를 초과|`400 NUMERIC_OVERFLOW`, 주문과 관련 상태를 저장하지 않음|

이전 POINT 동시성·실제 SQL 뒤 기술 실패의 준비·실행·판정 기준과 완료 근거는 [4.3.5](./04-use-cases.md#435-구현-단계-검증-계획)에 둔다. 대표 HTTP 오류는 DB 결과와 연결하되 경쟁 전체를 HTTP에서 중복 검증하지 않으며, 관련 유효 테스트·Checkstyle·ArchUnit은 유지한다. 이 표는 기대값을 정의하며 실행 결과를 대신하지 않는다. 브랜드 일괄 삭제는 2026-10-07 검증 완료했으며 근거·한계는 [4.1](./04-use-cases.md#41-브랜드와-연관-상품-일괄-삭제)에 둔다. 이전 POINT 주문 확정·동시성도 같은 날 구현·검증했으며 근거·한계는 4.3.5에 둔다. 이 결과는 신규 PG 연동의 실행 근거가 아니다.


## 5.5 로컬 PG HTTP 계약

커머스와 별도 HTTP 경계를 가진 테스트용 PG의 계약이다. 실제 카드·실금전·외부 PG 계정은 사용하지 않는다. 아래 생성·거래 조회·주문별 조회 세 요청은 고정 제공자 계정 `X-USER-ID: 135135`를 사용한다. 이 헤더는 커머스 고객 인증과 별개다.

|요청|입력|응답|
|---|---|---|
|POST /api/v1/payments|JSON orderId·cardType·cardNo·amount·callbackUrl|202 PENDING 또는 200 SUCCESS, transactionKey·orderId·amount·status|
|GET /api/v1/payments/{transactionKey}|문자열 거래 키|200과 거래 객체, 없는 거래는 404|
|GET /api/v1/payments?orderId={providerOrderId}|생성 때 사용한 동일 문자열 주문 ID|200과 거래 객체 배열. 빈 배열도 표현|

- orderId는 6자 이상 문자열이고 amount는 원화 정수다. 내부 숫자 orderId와 외부 providerOrderId는 구분하며 생성·주문별 조회에 동일한 매핑을 쓴다. 매핑은 LP-ORD-와 내부 양수 주문 ID의 최소 10자리 숫자를 결합한다. 예: 42 → LP-ORD-0000000042. 자릿수 초과를 자르지 않고 저장한 값을 재사용한다.
- cardType=TEST·cardNo=0000-0000-0000-0000의 합성 값만 사용한다. 생성 요청의 callbackUrl은 서버 로컬 설정으로 고정하고 고객이 임의 외부 주소를 지정하게 하지 않는다. 카드 번호는 DB·로그에 저장하지 않는다.
- 생성은 호출자 멱등 키를 받지 않으며 같은 orderId로 새 POST를 보내면 중복 거래가 생길 수 있다. paymentAttemptKey를 PG가 중복 제거해 준다고 가정하지 않는다.
- 정상 비동기 생성은 202·PENDING이다. 즉시 성공은 200·SUCCESS로 반환한다. 고정 PG 경계와 요청 주문·금액·거래 정보를 검증한 즉시 SUCCESS는 추가 조회 없이 공통 TX B로 반영한다.
- transactionKey는 `[A-Za-z0-9_-]{1,255}`이며 대소문자를 구분하고 trim하지 않는다. 콜백의 잘못된 키 형식은 400 INVALID_REQUEST, PG 응답의 잘못된 형식은 UNAVAILABLE 관찰로 처리한다. 형식이 유효한 콜백 알림의 거래 키는 권위 조회 대상으로만 사용하며, 저장 키와 다르다는 이유만으로 조회 전에 결합하거나 충돌을 확정하지 않는다. 그 키의 정상 거래 부재 404는 UNAVAILABLE이며 기존 업무 상태·저장 키·확보 재고를 유지한다. PG가 거래를 반환하면 조회 요청 대상과 응답의 일치, 내부 주문·저장 거래 관계를 검증하고 불일치는 기존 CONFLICT 계약을 따른다. 요청/응답 키 불일치와 내부 주문/저장 거래 불일치의 회로 분류는 [2.8절](./02-architecture.md#합의한-조회-회로-표본-분류)을 유지한다. 같은 제공자/계정·주문·금액의 유효 거래를 정확히 반환했지만 저장 키(KA)와 다른 키(KB)이면 회로 성공·업무 충돌이며, TX B가 종결 비교보다 먼저 IDENTITY_MISMATCH로 판정해 요약·확인 정보를 commit한 뒤 409 PAYMENT_RESULT_CONFLICT를 반환한다. KA 교체·KB 결합·업무 상태/재고 변경은 없고, KB 상태가 저장 종결과 반대여도 TERMINAL_MISMATCH로 비교하지 않는다. 주문별 조회의 유효 2건 이상은 기존 MULTIPLE_TRANSACTIONS다. 판정 순서의 원본은 [3.6](./03-domain-model.md#확인-정보의-반영-순서와-종결-처리)이다.
- 조회 GET의 200 이외 2xx 및 3xx는 계약 외 응답으로 회로 실패 집계 후 UNAVAILABLE로 처리한다. 3xx 자동/수동 redirect는 하지 않는다. 조회 202 본문의 PENDING·204를 빈 배열·3xx 본문의 성공/실패로 해석하지 않으며 Location 추가 호출도 없다. 생성 POST의 202 PENDING·200 SUCCESS 계약과 구분한다. 이유·처리·검증 기준은 [2.8절](./02-architecture.md#28-pg-http-도구와-테스트-서버의-구성)을 따른다.
- 거래 객체의 상태는 PENDING·SUCCESS·FAILED다. HTTP 2xx만으로 SUCCESS로 해석하지 않고, 응답 필드·타입·주문·금액·거래 관계를 검증한다. query는 URI builder로 인코딩하고 거래 키를 경로로 사용할 때도 올바르게 인코딩한다.

### 거래 생성 전 확정 거절

PG가 거래 저장·후속 처리 예약 전에 종료하고 해당 요청의 거래를 나중에도 생성하지 않는 결과만 다음 계약으로 표현한다.

```http
HTTP/1.1 422 Unprocessable Content
Content-Type: application/json

{"code":"TEST_PG_REJECTED_BEFORE_CREATE","orderId":"LP-ORD-0000000042","amount":7000,"transactionCreated":false}
```

고정 PG의 생성 응답에서 422, 정확한 code, boolean false, 요청 orderId·amount 일치를 모두 검증한 경우에만 내부 REJECTED_NO_EFFECT 관찰로 매핑한다. 로컬 회로 거절·생성 최초 풀 획득 timeout의 확실한 미발송은 별도 LOCAL_NOT_DISPATCHED(원인별 증거는 2.8)이며 이 PG 계약으로 위장하지 않는다. 이 결과는 PAYMENT_FAILED·예약 한 번 반환으로 연결하고 transactionKey를 꾸며 저장하지 않는다.

본문 없는 422·다른 code·다른 4xx/5xx·응답 유실·해석 불가 응답은 효과 0건의 증거가 아니다. 초기 관찰은 UNKNOWN으로 남기고 기존 거래를 확인한다. 조회 404·빈 목록도 이 생성 거절과 다르다.

### PG 생성·콜백 예시

```json
{
  "orderId": "LP-ORD-0000000042",
  "cardType": "TEST",
  "cardNo": "0000-0000-0000-0000",
  "amount": 7000,
  "callbackUrl": "http://127.0.0.1:8080/api/v1/payments/callback"
}
```

다음 거래 객체는 PENDING 응답에서는 202, 즉시 SUCCESS 응답에서는 200으로 반환한다. 아래 예시는 즉시 성공이다.

```json
{
  "transactionKey": "TEST-TX-0042",
  "orderId": "LP-ORD-0000000042",
  "amount": 7000,
  "status": "SUCCESS"
}
```

PG는 callbackUrl로 아래 알림을 보낼 수 있다. 콜백의 유실·중복·지연·순서 변경을 제어해 검증하며, 즉시 성공 응답 뒤 콜백이 와도 추가 효과가 없어야 한다. 거래 상태를 응답하는 것과 콜백 전달 성공은 서로 다른 사실이다.

```json
{
  "providerOrderId": "LP-ORD-0000000042",
  "transactionKey": "TEST-TX-0042",
  "status": "SUCCESS"
}
```

### PG 오류와 내부 오류의 구분

|상황|커머스의 판단|내부 주문·재고|
|---|---|---|
|생성 200 SUCCESS를 식별·금액 검증|최종 성공 관찰|공통 TX B 성공 반영|
|생성 202 PENDING|처리 중 거래 존재|미확정·예약 유지|
|유효한 거래 조회 FAILED|확정 실패|공통 TX B 실패·예약 반환|
|검증한 생성 전 확정 거절|외부 효과 0건|공통 TX B 실패·예약 반환|
|생성 응답 유실·timeout·형식 해석 불가|최종 결과를 확인하지 못함|UNKNOWN·예약 유지, create 재시도 없음|
|조회 실패·회로 차단·404·0건|이번 확인 불가. 기존 성공·실패로 종결할 증거 없음|기존 상태·예약 유지|
|복수 거래·파싱 가능한 식별/금액 불일치·상충 종결|결과 충돌|기존 종결 덮어쓰기·임의 반환 없음, PAYMENT_RESULT_CONFLICT|
|커머스 TX B 저장 실패|내부 기술 오류|해당 TX rollback·500. 같은 외부 거래를 대사해 재반영|

## 5.6 통신·실행 계약의 기준 위치

PG 구현에 필요한 정책은 아래 기준 절에 작성했다. 아래 항목은 현행 구현의 기준 계약이며, 계약 정의와 실제 실행 범위·한계는 구분한다. 실행 근거는 4.5의 I4·I5 기록을 따른다. `후속 상세 항목`이었던 이 절의 통신 수치·회로·실행 정책은 2026-10-20 사용자 승인으로 해소했다.

|대상|구현 기준|
|---|---|
|상태·저장|[3.5~3.6](./03-domain-model.md#35-pg-결제의-상태와-불변조건): 상태·필드·제약·POINT 보정|
|TX·처리 순서|[4.3](./04-use-cases.md#43-주문-확정): TX A/B·잠금·공통 결과 반영·경쟁 의사코드|
|HTTP/회로|[2.8~2.9](./02-architecture.md#28-pg-http-도구와-테스트-서버의-구성): 분류·빈/설정·fallback·초기값 근거·시간 한계|
|PG 코드/수명|[2.10](./02-architecture.md#210-테스트-pg-서버의-책임과-수명): 테스트 fixture·책임·상태·자원 정리|
|테스트 제어/수명|[5.7](#57-테스트-pg-제어와-상태-보존): 결정적 장애/콜백·관측·초기화·컨텍스트 재시작|
|검증|[4.5](./04-use-cases.md#45-pg-연동의-검증과-관찰): 계층별 필수 검증·회귀 전환·실행 근거|

## 5.7 테스트 PG 제어와 상태 보존

### fixture 제어 메서드와 거래 저장의 분리

테스트 준비/관측은 PgFixtureServer handle의 메서드로 수행한다. 별도 제어 HTTP endpoint·JSON 요청/응답·HTTP 400/404/409 규격은 만들지 않는다. 실제 PG 결제 생성/조회 세 HTTP와 PG → 커머스 콜백 HTTP는 유지한다. fixture는 커머스 DB/Entity를 직접 변경하지 않는다.

|fixture 메서드|입력/출력·효과|
|---|---|
|state()|불변 FixtureState snapshot: transactions(key/orderId/amount/status), counts(createReceived/lookupByKeyReceived/lookupByOrderReceived/callbackSent), activeRequests, activeCallbackJobs, gates. callbackUrl·카드·원문 본문/변경 가능한 내부 컬렉션은 노출하지 않음|
|setStatus(key, status)|존재 거래의 PENDING/SUCCESS/FAILED만 변경. 콜백 자동 전송·등록 plan 변경·커머스 DB 직접 변경 없음|
|enqueuePlan(operation, target, plan)|CREATE/LOOKUP_TRANSACTION/LOOKUP_ORDER + 대상(orderId/transactionKey/orderId)의 FIFO에 검증한 typed plan 하나 등록. 다음 실제 요청이 원자적으로 한 번 소비. 실제 거래 상태/카운터는 등록만으로 바뀌지 않음|
|sendCallbacks(key, count)|1~10회의 명시적 알림 전송. count는 의도한 전송 횟수이며 retry 횟수가 아님. 호출 때 거래/콜백 URL snapshot을 복사하고 각 알림을 독립 작업으로 기존 callback 전용 executor(worker 2개)에 제출한 뒤 CompletionStage<CallbackBatchResult> 반환. 모든 전송의 결과를 집계한 뒤 완료. 같은 호출의 알림은 병렬 실행될 수 있으며 수신/완료 순서를 보장하지 않음. 순차/병렬 선택 파라미터·전송 모드 없음. 테스트는 유한 기한으로 완료를 기다리고 각 전송의 HTTP 응답/통신 오류를 읽음. PG 상태 변경·추가 retry 없음|
|releaseGate(gateId)|등록 gate 해제. 반복 해제는 성공, 없는 gate는 잘못된 테스트 준비로 거절. 거래·plan·카운터 변경 없음|
|reset()|조용한 fixture의 거래·plan·journal/count·gate 초기화. 진행 중 결제 handler/콜백 job이 있으면 IllegalStateException, 기존 상태 유지. 새 fixture 사용을 기본으로 하며 재시작 테스트의 중간에는 reset 금지|

잘못된 enum/형식/범위·없는 거래/gate·잘못된 plan 조합은 IllegalArgumentException으로 테스트 준비를 실패시킨다. close 이후 새 제어/콜백 접수는 IllegalStateException으로 거절한다. 메서드 제어 오류를 PG HTTP 응답이나 커머스 업무 오류로 전달하지 않는다.

**역할·동시성:** 거래 저장·plan FIFO·관측 journal은 역할을 분리하고 각 변경/스냅샷은 동기화한다. reset의 busy 검사/전체 초기화와 새 handler/콜백 job 접수는 같은 lifecycle guard로 직렬화해 검사 직후 새 작업이 끼어들지 못하게 한다. 네트워크 대기·gate·콜백 응답 대기 중 store/registry/lifecycle 잠금을 유지하지 않는다. 병렬 테스트는 별도 fixture/임의 포트/거래/회로를 사용한다. 같은 대상의 경쟁 테스트에서 plan 적용 순서가 중요하면 gate/장벽으로 실제 수신 순서를 통제한다.

**관측 의미:** createReceived/각 lookupReceived는 해당 PG endpoint의 실제 수신 시 증가하고, 입력 검증 실패/응답 유실도 수신 사실은 남긴다. 거래 수는 실제 store 생성 결과이며 수신 횟수와 다르다. 회로 거절·풀 획득 실패는 PG 수신 0일 수 있다. callbackSent는 실제 알림 전송 시도 수이고 CallbackBatchResult의 HTTP 응답/통신 오류는 전송 결과다. count=2는 의도적 중복 알림 2회이며 실패 후 retry가 아니다. 준비/관측 메서드 호출은 PG HTTP 수신·콜백 전송·회로 표본에 포함하지 않는다. 콜백 HTTP 200도 그 자체로 PG 결제 성공의 증거는 아니다. 진행 중 state() 관측만으로 업무 완료를 단정하지 않고 관련 HTTP/콜백 작업 완료 뒤 새 커머스 API/DB 조회로 최종 결과를 확인한다.

**콜백 전송 결과와 검증 완료:** 콜백 송신 timeout 자체는 별도 실험·필수 시나리오로 두지 않는다. 실습의 timeout·회복 검증 대상은 커머스 → PG HTTP이며, 아래는 테스트 장치의 최소 완료 계약이다.

- CallbackBatchResult는 각 전송의 HTTP 응답 또는 통신 오류(연결 실패·송신 timeout·I/O 오류)를 나타낸다. 결과의 나열 순서를 커머스 수신/처리 완료 순서로 해석하지 않는다. CompletionStage가 완료됐다는 사실은 모든 전송 시도가 끝났다는 뜻일 뿐 커머스 업무 완료의 선언이 아니다.
- 정상 콜백 검증은 각 전송에서 기대한 HTTP ACK를 받은 뒤 새 커머스 API/DB 조회로 상태와 업무 효과를 확인한다. 검증·종결 반영과 같은 종결 재확인의 기대 ACK는 [PG 콜백 수신](#pg-콜백-수신)의 200이며, [4.3](./04-use-cases.md#43-주문-확정)의 콜백 처리 순서상 이 응답은 결과 반영 TX가 commit된 뒤 반환된다.
- 송신 timeout·통신 오류가 있거나 기대한 ACK를 받지 못한 실행은 정상 콜백 검증 통과로 세지 않는다. 뒤늦게 DB 상태가 기대값과 맞아졌다는 이유로 그 실행을 정상 콜백 통과로 바꾸지 않는다.
- 송신 오류는 커머스가 받지 못했는지, 처리 중인지, 이미 commit했는지 알 수 없는 전송 관측이다. 결제 FAILED·재고 반환·fixture나 테스트의 자동 재전송 근거로 사용하지 않는다.
- 송신 오류 뒤에도 커머스 처리가 진행 중일 수 있으므로 아래 종료 계약의 유한 대기·진행 작업 종료/자원 정리를 그대로 적용한다. 종료를 확인하지 못한 환경은 안전한 재사용·검증 통과로 기록하지 않는다. 업무 결과 polling이나 송신 timeout 복구 시나리오는 추가하지 않으며, 콜백이 오지 않은 주문의 회복은 기존 콜백 유실 → 고객 reconcile 검증이 맡는다.
- count≥2는 중복 알림 검증이다. worker 2개에서 병렬 실행될 수 있지만 그것만으로 실제 요청 겹침을 입증했다고 보지 않으며 동시 적용 통과 근거로 쓰지 않는다. 같은 주문 결과 반영의 실제 동시 적용은 [4.5 부모 잠금 검증](./04-use-cases.md#부모-잠금과-일반-조회의-실제-mysql-검증)의 장벽·SQL/처리 관측으로 겹침을 입증한다.

### 응답 plan

|필드|입력·기본값|
|---|---|
|createOutcome|CREATE에서만 PENDING(기본)/SUCCESS/REJECT_BEFORE_CREATE. 마지막 경우 거래 저장·콜백 예약 없이 5.5의 422 반환|
|reply|NORMAL(기본)/RAW/DELAY/CLOSE_EARLY/GATE|
|delayMs|DELAY에서 0~10000ms. header 전 실제 대기, 종료 시 중단. 업무 코드에 sleep을 넣는 것이 아님|
|gateId|GATE에서 필요한 비어 있지 않은 문자열. snapshot/CREATE 효과 이후 응답 전 대기. 최대 10초 안에 release가 없으면 서버가 응답을 종료하고 timeout을 journal에 기록|
|httpStatus·body·headers|RAW에서 필수 상태코드/문자열 본문과 선택 header map. HTTP/JSON 손상·외부 식별/금액·혼합 목록·redirect 응답을 저장소 수정 없이 재현|

RAW가 아닌 plan에 RAW 필드를 주거나 CREATE 외에 createOutcome을 주는 등 잘못된 조합은 enqueuePlan에서 IllegalArgumentException으로 거절한다. REJECT_BEFORE_CREATE는 reply=NORMAL만 허용해 확정 무효과 계약을 모호하게 만들지 않는다. plan 소비 뒤 실제 snapshot·거래 효과를 먼저 만들고 응답을 제어하므로 CREATE DELAY/닫힘 뒤에는 이미 거래가 존재한다. CLOSE_EARLY는 전체보다 긴 Content-Length를 선언한 뒤 일부 바이트만 전송하고 연결을 닫아 수신 도중 I/O 오류를 재현한다. 클라이언트가 parser 오류로 관찰할 가능성도 있으므로 해당 검증에서는 실제 예외 원인/카운터를 함께 확인한다. 연결 수립 자체의 실패와는 별도다.

아래 JSON은 typed plan의 설정값 표현 예시이며 제어 HTTP 요청 본문이 아니다.

```json
{"createOutcome":"PENDING","reply":"DELAY","delayMs":1500}
```

위 CREATE plan은 거래를 만든 뒤 응답만 지연한다. commerce는 1초 읽기 제한으로 UNKNOWN/예약 유지할 수 있으며, PG state에는 거래가 남는다. 그 거래를 status=SUCCESS로 변경한 뒤 commerce의 고객 reconcile을 호출하면 기존 거래를 조회해 TX B에 반영한다. 대사 시 PG create 카운터는 늘지 않아야 한다.

콜백 유실은 **상태만 바꾸고 sendCallbacks를 호출하지 않는 것**으로, 중복은 count로, 늦은/역순 알림의 HTTP 수신 순서는 전송 시점으로 제어한다. 늦은 생성/조회 관찰의 적용 순서와 종결 보호는 HTTP GATE·읽기 대기에 묶지 않고 [4.5의 업무 TX 경계 검증](./04-use-cases.md#업무-관찰-순서의-tx-경계-검증)에서 입증한다. 콜백 본문 status가 도착 순서와 달라도 commerce는 권위 조회로 판단한다. 정상 PG 저장소 자체를 잘못된 금액/다른 주문으로 바꾸기보다 RAW 응답으로 조회 계약 위반을 재현한다. 복수 거래는 동일 orderId로 직접 PG POST를 두 번 보내 만들 수 있고 commerce의 생성 멱등성 검증과 구분한다.

plan target은 서버 저장/요청과 같은 문자열 식별자로 직접 전달한다. PG 조회 URI는 adapter가 인코딩하고 Java 제어에는 URI 변환을 추가하지 않는다. reset 없이 시나리오를 시작할 때는 테스트별 고유 주문/거래와 plan을 사용한다. 서버가 콜백을 보낼 수 있는 URL은 `http`·`127.0.0.1`·명시 포트·`/api/v1/payments/callback`으로 제한하고 redirect를 따르지 않는다. 임의 외부 주소를 fixture 제어로 주입하지 않는다.

### 기본 PENDING 이후 정상 콜백 완료

아래 정상 경로를 독립된 필수 검증으로 둔다. 테스트 제어 순서는 즉시 성공/응답 손상/중복 시나리오와 분리한다.

1. 기본 생성 응답을 202 PENDING으로 두고 고객 confirm을 호출한다. 주문 PAYMENT_PENDING·결제 PENDING·확보 재고 유지·createReceived=1을 확인한다.
2. state()에서 해당 외부 주문 ID의 유일한 transactionKey를 확인하고 setStatus(key, SUCCESS)를 호출한다. 이때 콜백/조회/커머스 상태 변경은 아직 없다.
3. sendCallbacks(key, 1)을 호출해 실제 커머스 callback HTTP → 실제 PG 거래 GET 1회 → 공통 TX B 순서로 처리하고 CallbackBatchResult 완료를 유한 기한으로 기다린다. 전송 결과가 기대한 200 ACK인지 확인한다. 송신 timeout·통신 오류이면 위 전송 결과 계약대로 이 실행을 통과로 세지 않는다.
4. 새 주문 조회/DB TX에서 CONFIRMED·payment SUCCESS·paidAmount=저장 총액, 같은 거래 키, createReceived=1·lookupByKeyReceived=1·callbackSent=1, 재고 추가 차감/반환·포인트 변화 0을 확인한다. 한 번의 ORDER_RESERVATION만 유지한다.
5. 같은 방식의 PENDING → FAILED 정상 콜백은 PAYMENT_FAILED·재고/ORDER_RELEASE 한 번 반환·paidAmount 생략을 확인한다. 중복 알림(count)은 별도 사례에서 추가 업무 효과 0을 확인하고, 동시 경쟁은 4.5의 부모 잠금 검증이 맡는다.

### 테스트 시작·초기화·상태 보존

아래는 구현할 테스트 수명 계약이다. 별도 PG 프로세스/main·Gradle run task·pg-demo 프로필·데모 DB/기동 명령은 만들지 않는다. 현재 문서 변경으로 코드/DB를 생성하거나 초기화하지 않았다.

- 일반 테스트는 PgFixtureServer.start()로 loopback 임의 포트 서버를 먼저 시작하고 실제 URL을 commerce.pg.base-url에 주입한다. 이 PG 포트를 커머스 포트와 혼동하지 않는다. 문서 2.9 YAML의 URL은 형식 예시이며 테스트는 여러 테스트가 공유하는 고정 포트/평소 켜둔 서버에 의존하지 않는다. 테스트별 포트·fixture 격리를 유지한다.
- **콜백 HTTP 테스트의 커머스 포트·callback URL:** 커머스 기동 전에 테스트가 사용할 빈 포트를 선택한다. 그 값을 `server.port`와 `commerce.pg.callback-url`(`http://127.0.0.1:{선택 포트}/api/v1/payments/callback`)에 함께 주입한 뒤 컨텍스트를 기동한다. `server.port=0`으로 기동한 뒤 포트를 알아내 callback URL을 바꾸는 방식은 쓰지 않으며 PgHttpProperties의 불변 바인딩을 유지한다. 기동 완료 후 웹 서버의 실제 수신 포트와 주입한 callback URL의 포트·주소·경로가 일치하는지 확인한 뒤에만 confirm·콜백 검증을 시작한다.
- 빈 포트의 사전 선택은 포트 예약이 아니다. 선택 뒤 다른 프로세스가 점유해 bind에 실패하거나 기동 후 포트/URL이 불일치하면 해당 실행은 테스트 준비 실패로 기록하며 검증 통과로 세지 않는다. 별도 포트 예약 장치나 자동 재시도 정책은 두지 않는다.
- YAML 속성 비교는 테스트별 초기 상태·설정·요청열을 통제한다. 실제 Boot registry/adapter에 적용된 최종값과 회로 상태/표본·PG 수신/거래 수를 기록한다. 회로 순수 전이는 주입 clock으로 보완하고 HTTP timeout/상태 타이머의 실제 동작은 실제 시간으로 확인한다. 구체 글 실험값/결과는 구현 뒤 정한다.
- 테스트 DB는 기존 MySQL fixture를 사용하고 기존 로컬 loopers/POINT 데이터를 초기화하지 않는다. 새 테스트의 스키마 준비와 종료 정리는 해당 테스트 DB 안에서만 수행한다. 기존 데이터 전환의 보정/DDL 계약은 3.6을 유지하며 테스트용 빈 DB 생성과 혼동하지 않는다.
- 재시작 테스트는 같은 PG fixture와 MySQL container/스키마를 두 commerce 컨텍스트보다 긴 수명으로 유지한다. 첫 컨텍스트의 스키마 준비/주문·결제 생성 뒤 종료하고, 두 번째는 ddl-auto=validate로 시작한다. 두 컨텍스트 사이에 create/cleanup/TRUNCATE/reset을 실행하지 않는다. 기존 test/local의 create 설정을 두 번째 컨텍스트의 최종값으로 두지 않는다. 각 컨텍스트는 기동 전에 자기 커머스 포트와 callback URL을 위 절차대로 함께 주입하고 기동 후 일치를 확인하며, 두 컨텍스트가 같은 커머스 포트를 쓸 필요는 없다. 첫 컨텍스트 종료와 진행 요청/콜백·worker 정리는 아래 종료 계약을 따른다.
- 두 번째 컨텍스트의 필수 복구 경로는 고객 주문 조회 → reconcile이다. 같은 거래를 확인하고 create 추가 0·주문/재고 효과 한 번을 검증한다. PG 거래에 저장된 첫 컨텍스트의 callback URL을 두 번째 주소로 고치거나 이전 주소로 콜백을 받는 기능은 추가하지 않으며, 이전 주소로 향한 콜백 전송 결과를 복구 근거로 쓰지 않는다. 주문·결제·발송 기록은 DB에서 유지되고 client/회로 메모리 상태는 새로 시작한다. PG fixture까지 종료해 거래가 사라진 사례를 거래 복구 성공으로 기록하지 않는다.
- 종료 시 진행 commerce 요청/콜백·gate를 해제하고 실제 작업 종료를 확인한 뒤 commerce 컨텍스트/client → PG fixture → DB 순으로 정리한다. worker 종료를 확인하지 못하면 실패를 보고하고 해당 환경의 안전한 재사용/검증 통과로 기록하지 않는다. 일반 테스트는 매번 새 fixture를 사용하며 재시작 테스트의 두 컨텍스트 사이만 의도적으로 공유한다.
