# API 계약

Commerce API의 HTTP 경로, 입력, 성공 응답과 대표 오류를 정리한다. 애그리게이트 규칙은 [도메인 모델](domain-model.md), 복잡한 상태 변경 흐름은 각 유스케이스 설계를, 공통 오류 변환 방식은 [오류 변환 설계](error-mapping.md)를 기준으로 한다.

## 정수 입력 계약

Body의 상품 가격·재고 수량, 주문 수량, `brandId`·`productId`와 포인트 충전액은 반올림하거나 소수 부분을 버리지 않고 변환한다. 값에 소수 부분이 있거나 해당 필드의 정수 표현 범위를 초과하면 `400 Bad Request`로 거절하고 기존 데이터를 유지한다.

- 값이 정수인 `100.0`, `1e2` 표기는 기존처럼 정확히 `100`으로 허용한다.
- 상품 가격·재고·ID·충전액은 `long`, 주문 수량은 `int`의 표현 범위 안이어야 한다. 변환 후에는 각 도메인의 가격·수량·잔액 범위와 대상 존재 여부를 검사한다.
- 필수 수치의 누락·null은 `400`이다. 숫자 변환은 HTTP DTO에서, 업무 범위 검증은 기존 Application·Domain에서 수행한다.

## 관리자 API

공통 Prefix: `/api-admin/v1`  
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
| 상품 등록 | `POST` | `/products` | Body: `brandId`, 이름(공백만 불가, 1~100자), 가격(1~100,000,000원) | `201 Created`<br/>재고 0으로 생성된 상품 정보 | `400 Bad Request`<br/>상품 이름·가격 검증 실패<br/>`404 Not Found`<br/>없거나 삭제된 Brand |
| 상품 상세 조회 | `GET` | `/products/{productId}` | Path: `productId` | `200 OK`<br/>상품·브랜드·재고·삭제 상태 정보 | `404 Not Found`<br/>없는 Product |
| 상품 수정 | `PUT` | `/products/{productId}` | Path: `productId`<br/>Body: 이름(공백만 불가, 1~100자), 가격(1~100,000,000원) | `200 OK`<br/>수정된 상품 정보 | `400 Bad Request`<br/>상품 이름·가격 검증 실패<br/>`404 Not Found`<br/>없거나 삭제된 Product |
| 상품 삭제 | `DELETE` | `/products/{productId}` | Path: `productId` | `200 OK`<br/>Product 논리 삭제<br/>재고·기존 Like·주문 정보는 유지 | `404 Not Found`<br/>없거나 이미 삭제된 Product |
| 상품 재고 변경 | `PUT` | `/products/{productId}/stock` | Path: `productId`<br/>Body: 최종 재고 수량 | `200 OK`<br/>변경된 재고 수량 | `400 Bad Request`<br/>0 미만 재고 수량<br/>`404 Not Found`<br/>없거나 삭제된 Product |

브랜드 이름, 상품 이름·가격이나 재고를 현재와 같은 값으로 설정해도 `200 OK`로 처리한다.
같은 필드의 수정 요청이 겹치면 마지막으로 반영된 요청의 값이 남는다. 관리자 재고 설정은 입력한 최종 수량을 적용한다.

### 주문

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| 주문 목록 조회 | `GET` | `/orders` | - | `200 OK`<br/>구매자·품목·상태·금액·결제 결과를 포함한 주문 목록 | 기능별 대표 오류 없음 |
| 주문 상세 조회 | `GET` | `/orders/{orderId}` | Path: `orderId` | `200 OK`<br/>구매자·품목·상태·금액·결제 결과 | `404 Not Found`<br/>없는 Order |

## 고객 API

공통 Prefix: `/api/v1`  
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

같은 사용자·상품의 좋아요를 동시에 등록해도 각 요청은 `200 OK`와 좋아요 상태를 반환하고 관계는 1건만 저장한다. 관계가 실제 추가된 요청만 커밋 후 목록 캐시를 무효화한다.

### 포인트·주문

포인트는 `1포인트 = 1원`이며 충전액은 저장 가능한 범위의 양의 정수여야 한다. 입력 금액의 소수 부분을 반올림하거나 버리지 않는다.

| 기능 | Method | Path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|---|
| 포인트 충전 | `POST` | `/points/charge` | Body: `amount` | `200 OK`<br/>충전 후 잔액 | `400 Bad Request`<br/>누락·잘못된 타입·양의 정수가 아닌 `amount`, 충전 후 잔액 범위 초과 |
| 내 포인트 잔액 조회 | `GET` | `/points` | - | `200 OK`<br/>저장된 포인트 잔액 | 기능별 대표 오류 없음 |
| 주문 생성 | `POST` | `/orders` | Body: 주문 품목 | `201 Created`<br/>중복 품목을 합산해 품목, 수량, 단가와 합계를 저장한 `DRAFT` Order 반환<br/>재고와 포인트 미차감<br/>이후 Brand·Product가 삭제돼도 저장된 DRAFT 보존 | `400 Bad Request`<br/>주문 품목 누락·빈 목록·null 품목, `productId` 누락·null 또는 수량 입력 오류<br/>`404 Not Found`<br/>상품 조회 시 없거나 삭제된 Product |
| 주문 확정 | `POST` | `/orders/{orderId}/confirm` | Path: `orderId` | `200 OK`<br/>재고·포인트 차감, 결제 정보 저장 후 `CONFIRMED` Order | `404 Not Found`<br/>주문이 없거나 타인 소유<br/>`409 Conflict`<br/>DRAFT가 아닌 Order, 상품 사용 불가, 재고 부족 또는 잔액 부족 |
| 내 주문 목록 조회 | `GET` | `/orders` | - | `200 OK`<br/>내 주문의 품목·수량·금액·상태·결제액 목록 | 기능별 대표 오류 없음 |
| 내 주문 상세 조회 | `GET` | `/orders/{orderId}` | Path: `orderId` | `200 OK`<br/>내 주문의 품목·수량·금액·상태·결제액 | `404 Not Found`<br/>조회할 수 없는 Order |

## 상세 유스케이스

- [브랜드와 연결 상품 일괄 삭제](use-cases/brand-deletion.md)
- [주문 확정과 포인트 결제](use-cases/order-confirmation.md)
- [고객 상품 상세 조회](use-cases/product-detail.md)
- [상품 목록 조회와 캐시](use-cases/product-listing.md)

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
| 충전과 차감의 동시 갱신 | 잔액 `10,000`에서 `2,000` 충전과 `7,000` 주문 차감 동시 실행 | 두 요청 모두 성공, 최종 잔액 `5,000` |

## 상품 단독 삭제 기대값

| 규칙 | 주어진 상태·입력 | 기대값 |
|---|---|---|
| 활성 Product 삭제 | 활성 Brand에 연결된 Product 삭제 요청 | 성공, Product만 논리 삭제되고 Brand는 활성 상태 유지 |
| 재고 수량 보존 | 재고가 0 또는 양수인 Product 삭제 요청 | 삭제 성공, 재고 수량은 변경되지 않음 |
| 기존 관계·주문 보존 | Product에 Like 또는 기존 Order·OrderItem이 존재 | 삭제 후에도 Like 관계와 주문 스냅샷을 유지하고, 기존 Like 취소 허용 |
| 이미 삭제된 Product | 삭제된 Product에 삭제 요청 | `404 Not Found`, 기존 삭제 상태와 삭제 시각 유지 |
| 삭제 후 변경 시도 | 삭제된 Product의 수정·재고 변경 요청 | `404 Not Found`, Product 상태와 재고 유지 |
| 동일 Product 삭제 경쟁 | 같은 Product에 두 삭제 요청 실행, 한 요청이 commit | `200` 한 건, 다른 요청은 갱신 행 수 0으로 `404`, 기존 삭제 시각 유지 |
| 수정이나 재고 설정 후 삭제 | 수정 또는 재고 설정이 먼저 commit된 뒤 삭제 UPDATE 실행 | 삭제 성공, 변경된 이름과 가격 또는 재고 보존 |
| 삭제 후 수정이나 재고 설정 | 삭제가 먼저 commit된 뒤 수정 또는 재고 설정 UPDATE 실행 | 미삭제 조건을 만족하지 못해 `404`, 기존 상품 정보와 재고 및 삭제 상태 유지 |

