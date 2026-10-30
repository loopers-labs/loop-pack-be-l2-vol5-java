# 공통 API 계약

기존 절을 기능별 기준 문서로 이동했다. 절 번호·계약·승인/검증 상태는 보존하며, 이동 자체는 새 설계 승인이나 실행 검증을 뜻하지 않는다.

기능별 API/DTO: [카탈로그](../features/catalog.md)·[포인트](../features/points.md)·[주문·결제](#51-공통-계약과-입력-정책). 고객 공통 설명은 [5.2 도입](../features/catalog.md#52-고객-api), 관리자 공통 설명은 [5.3 도입](../features/catalog.md#53-관리자-api)에 보존했다.

이 장은 구현할 API의 입력, 성공 결과와 대표 오류를 정의한다. 모든 조합을 테스트 사례로 나열하기보다 도메인 규칙과 HTTP 계약을 연결하고, 구현 단계에서는 이 계약으로부터 정상 경로와 경계값·실패 테스트를 도출한다. PG 결제 내용은 구현 전의 목표 계약이며, `[미정]` 항목은 승인 전이므로 구현 기준으로 채택하지 않는다.

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

모든 목록 API의 페이지 정보는 다음 `PageResponse<T>` 구조로 `ApiResponse.data`에 담는다. `page`와 `size`, `totalPages`는 32비트 정수, `totalElements`는 64비트 정수로 표현한다. `totalElements`는 목록의 기준 자원 수이며, 주문 목록에서는 Order 수를 뜻한다. 주문 목록은 Order 단위로 페이지를 조회한 뒤 각 주문에 속한 모든 OrderItem을 응답에 포함한다. API별 응답은 [5장](api-contract.md)에 명시된 정보만 포함하고, Entity를 직접 직렬화하거나 생성·수정 시각과 같은 내부 필드를 계약 없이 추가하지 않는다.

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
- 생성 요청은 만들려는 대상이 아직 없으므로 이 순서를 적용하지 않고 각 API가 정한 순서를 따른다. 상품 등록은 본문의 `brandId`로 참조할 Brand를 먼저 조회하며, `brandId` 자체가 빠져 조회할 대상을 정할 수 없으면 `400 INVALID_REQUEST`로 거절한다. 주문 생성의 검증 순서는 [5.2](../features/order-payment/api-contract.md#주문)의 주문 규칙에서 정한다.
- 요청과 무관하게 서버 내부 데이터의 불변식이 깨진 경우에는 `500`을 사용한다. Point 조회·충전에서 존재하는 User에게 Point가 없으면 `500 POINT_NOT_INITIALIZED`다. 신규 PG 주문에는 Point 존재 조건을 적용하지 않는다.
- 같은 HTTP 상태 안에서도 클라이언트가 실패 원인을 구분할 수 있도록 `PRODUCT_NOT_FOUND`, `LIKE_ALREADY_EXISTS`, `INSUFFICIENT_STOCK`과 같은 안정적인 업무 오류 코드를 사용한다. 기존 `ErrorType`을 유지하면서 각 업무 오류의 `HttpStatus`, 안정적인 코드와 기본 메시지를 추가한다. Domain은 발생한 업무 오류를 `CoreException`으로 표현하고, interfaces의 `ApiControllerAdvice`가 `ErrorType`의 정보를 사용해 실제 HTTP 응답을 생성한다.
- Soft Delete된 Brand·Product는 활성 자원을 대상으로 하는 API에서 존재하지 않는 것으로 처리한다. 이미 삭제된 대상을 다시 삭제하는 요청도 각각 `404 BRAND_NOT_FOUND`, `404 PRODUCT_NOT_FOUND`로 응답한다.
- commit 전 업무 거절·중간 RuntimeException이 전파되면 해당 로컬 TX의 Entity·History 변경은 rollback한다. PG 결제는 재고·시도를 준비하는 TX A와 외부 결과를 반영하는 TX B로 분리하며, TX A가 commit된 뒤의 PG 오류가 TX A를 자동 rollback하지 않는다.
- 확정 실패의 재고 반환은 별도 로컬 TX에서 수행하는 보상 처리다. 일반 HTTP 오류·timeout·조회 0건만으로 실패·반환을 결정하지 않는다. TX B의 성공·실패 반영과 예약 소비·반환은 같은 TX에서 보호한다.
- deadlock·lock timeout·DB 저장/commit 실패는 재고·포인트 부족이나 PG 확정 실패로 바꾸지 않는다. 기존 HTTP 500과 `ErrorType.INTERNAL_ERROR`를 유지하며 실제 `meta.errorCode`는 `Internal Server Error`다. 오류 응답만으로 commit 여부를 단정하지 않고, 이미 저장된 시도와 기존 외부 거래를 확인한다.
- TX A 전 생성 회로 차단은 503 PG_CREATE_UNAVAILABLE이며 주문 DRAFT·결제 행 없음·재고 효과 없음이다. 회복 후 고객이 같은 주문/키로 다시 confirm할 수 있다. TX A 후 실제 회로의 POST 진입 전 거절과 생성 POST의 확실한 최초 연결 풀 획득 timeout만 LOCAL_NOT_DISPATCHED로 확정하여 반환·PAYMENT_FAILED·HTTP 200으로 종결한다(원인 CIRCUIT_REJECTED/POOL_ACQUIRE_TIMEOUT, 판정은 [2.8](../features/order-payment/pg-client.md#생성-회로와-사전-차단)). 일반 timeout·HTTP 오류에는 이 정책을 적용하지 않는다.
- PG create 자동 재시도는 금지한다. 동일 시도 confirm 재요청은 저장 상태를 반환하고, reconcile은 기존 거래만 조회한다. 조회는 요청당 PG GET 최대 1회·자동 retry/polling/sleep 없음의 승인된 [2.8 정책](../features/order-payment/pg-client.md#28-pg-http-도구와-테스트-서버의-구성)을 따르며, 단계별 HTTP 제한과 별도 전체 deadline을 두지 않는 범위는 [2.9](../features/order-payment/pg-client.md#29-http-제한과-pg-회로의-초기-정책)를 따른다. 이전 POINT 구현의 사용자 재요청 규칙을 새 PG 결제 발송 규칙으로 사용하지 않는다.
- 브랜드·상품·포인트 등 PG를 호출하지 않는 기존 변경의 로컬 TX 계약은 유지한다. 관련 구현 근거는 각 흐름을 따른다.

공통 식별·입력 형식 오류는 모든 API에 적용하며, API별 표의 대표 오류에서는 반복해 적지 않는다.

Spring Security 필터에서 거절되는 관리자 요청은 `403` 상태만 계약으로 보장하며, `ApiResponse` 본문 검증 대상에서는 제외한다.

`ErrorType`이 `HttpStatus`를 포함하므로 domain이 support를 통해 HTTP 개념에 간접 의존하는 비용을 받아들인다. 같은 업무 오류를 HTTP 외의 인터페이스에서 재사용하거나 인터페이스별로 서로 다른 상태 매핑이 필요해지면 업무 오류 코드와 HTTP 상태를 분리하고 interfaces에 별도 매퍼를 두는 방안을 다시 검토한다.

### 잠정 입력 정책

기능 구현과 경계값 테스트를 시작할 수 있도록 다음 값을 잠정 기준으로 사용한다. 기획 결정 전까지 구현을 보류하지 않고 이 값을 적용하며, 관련 테스트도 현재 값을 기준으로 작성한다.

|항목|잠정 기준|잘못된 입력|
|---|---|---|
|모든 목록 페이지|`page=0`, `size=20`, 최대 `size=100`|`page < 0`, `size < 1` 또는 `size > 100`이면 `400 INVALID_PAGE_REQUEST`|

수량 합산, 품목 금액, 주문 총액, 포인트 잔액 계산이 저장 타입의 표현 범위를 넘으면 `400 NUMERIC_OVERFLOW`로 거절한다. 브랜드명·상품명·가격 범위, 페이지 기본값·최대 크기, 상품의 초기 재고 0과 브랜드 필터 결과 처리는 요구사항에 명시되지 않은 구현용 가이드이므로 **기획 확인이 필요하다**. 모든 목록의 페이지·정렬 통일 역시 상품 목록 외에는 추가로 선택한 잠정 API 정책이다. 값이나 처리 방식이 변경되면 API 계약과 관련 테스트를 함께 수정한다.

모든 목록은 선택적 `page`, `size`, `sort`를 받으며 기본 정렬은 `latest`로 한다. 상품 목록의 정렬별 기준과 동률의 보조 정렬은 다음과 같다.

해당 표는 [상품 목록 정렬](../features/catalog.md#상품-목록-정렬-51)에 있다.

상품 목록을 제외한 내 좋아요·내 주문·관리자 브랜드·상품·주문 목록은 `latest`와 `oldest`만 지원한다. 정렬 기준 모델은 내 좋아요 목록은 Like, 내 주문과 관리자 주문 목록은 Order, 관리자 브랜드 목록은 Brand, 관리자 상품 목록은 Product다. `latest`는 각 모델의 생성 시각 내림차순과 ID 내림차순, `oldest`는 생성 시각 오름차순과 ID 오름차순으로 정렬한다. `sort`를 생략하면 `latest`를 사용하고, 해당 목록에서 지원하지 않는 값은 `400 INVALID_SORT`로 거절한다. 이 기본값과 보조 정렬 기준도 **기획 확인이 필요한 잠정 정책**이며, 모든 목록으로 페이지 조회를 확대한 이유와 비용은 [부록 A.14](../decisions/domain-and-api.md#a14-목록-응답과-페이지-조회의-범위)에서 비교한다.

### API 요청·응답 DTO 스키마

아래 필드명과 구조를 API 계약으로 사용한다. ID·금액·수량·좋아요 수는 Java DTO에서 `Long`으로 표현하고, 도메인 내부의 Money는 원 단위 `Long` 값으로 변환해 전달한다. `status`는 문자열 Enum 값으로 반환한다. 응답 DTO에는 아래에 명시한 필드만 포함하며 Entity, 생성·수정 시각과 내부 이력 필드를 직접 노출하지 않는다. 고객용 DTO와 관리자용 DTO의 필드가 같더라도 API 경계를 구분하기 위해 각각 `{Domain}V1Dto`와 `{Domain}AdminV1Dto`에 선언한다. 관리자 주문 응답 안의 품목도 고객 품목 응답과 필드가 같지만 같은 이유로 `AdminOrderItemResponse`를 따로 선언하고 고객용 응답 모델을 참조하지 않는다.

요청 본문 모델은 다음과 같다. 경로 변수와 `X-USER-ID`는 본문 필드에 중복해서 받지 않는다.

기능별 표: [카탈로그](../features/catalog.md#51-카탈로그-입력dto-계약) · [포인트](../features/points.md#51-포인트-입력dto-계약) · [주문·결제](#51-공통-계약과-입력-정책).

응답 모델의 정확한 필드는 다음과 같다.

기능별 표: [카탈로그](../features/catalog.md#51-카탈로그-입력dto-계약) · [포인트](../features/points.md#51-포인트-입력dto-계약) · [주문·결제](#51-공통-계약과-입력-정책).

각 API의 `ApiResponse.data` 타입은 다음과 같다.

기능별 표: [카탈로그](../features/catalog.md#51-카탈로그-입력dto-계약) · [포인트](../features/points.md#51-포인트-입력dto-계약) · [주문·결제](#51-공통-계약과-입력-정책).

## 5.4 대표 규칙의 테스트 기대값

분리된 기능별 기대값: [카탈로그](../features/catalog.md#54-카탈로그-대표-규칙의-테스트-기대값) · [포인트](../features/points.md#54-포인트-대표-규칙의-테스트-기대값) · [주문·결제](#54-대표-규칙의-테스트-기대값).

아래 표는 API 전체 테스트 목록이 아니라 구현 전에 기대값을 고정해야 하는 대표 사례다. 실패 사례는 HTTP 응답뿐 아니라 기존 상태가 유지되는지도 함께 확인한다.

|규칙|주어진 상태와 요청|기대 결과|
|---|---|---|
|숫자가 아닌 요청자 식별자|고객 API의 `X-USER-ID`에 `jop0522` 전달|`400 INVALID_REQUEST`, 기능별 조회·상태 변경을 진행하지 않음|
|없는 요청자 거절|모든 고객 API에 저장되지 않은 User ID를 `X-USER-ID`로 전달|`404 USER_NOT_FOUND`, 기능별 조회·상태 변경을 진행하지 않음|
|목록 페이지·정렬 `[잠정]`|상품 외 목록에 `page=0`, `size=2`, `sort=oldest` 요청|생성 시각·ID 오름차순의 첫 두 항목과 전체 자원 수를 `PageResponse`로 반환|
|숫자 범위 초과|수량 합산이나 주문 총액 계산이 표현 범위를 초과|`400 NUMERIC_OVERFLOW`, 주문과 관련 상태를 저장하지 않음|

이전 POINT 동시성·실제 SQL 뒤 기술 실패의 준비·실행·판정 기준과 완료 근거는 [4.3.5](../verification/legacy-point.md#435-구현-단계-검증-계획)에 둔다. 대표 HTTP 오류는 DB 결과와 연결하되 경쟁 전체를 HTTP에서 중복 검증하지 않으며, 관련 유효 테스트·Checkstyle·ArchUnit은 유지한다. 이 표는 기대값을 정의하며 실행 결과를 대신하지 않는다. 브랜드 일괄 삭제는 2026-10-07 검증 완료했으며 근거·한계는 [4.1](../features/catalog.md#41-브랜드와-연관-상품-일괄-삭제)에 둔다. 이전 POINT 주문 확정·동시성도 같은 날 구현·검증했으며 근거·한계는 4.3.5에 둔다. 이 결과는 신규 PG 연동의 실행 근거가 아니다.
