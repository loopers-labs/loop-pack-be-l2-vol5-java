# 상품·브랜드·좋아요·포인트·주문 설계 문서 — Week 2

## 1. 버드뷰

```
고객 ── 상품·브랜드 조회 / 좋아요 / 포인트 / 주문 ─┐
                                              ├─ commerce-api ─→ DB
관리자 ── 브랜드·상품 CRUD / 재고 변경 / 주문 조회 ─┘
```

- 고객과 관리자는 같은 브랜드·상품 데이터를 다루지만 경로(`/api/v1/**` vs `/api-admin/v1/**`)와 권한이 다르다. 고객의 "조회"와 관리자의 "변경"은 별개 계약으로 취급한다.
- 서버·DB 1세트로 충분 — 별도 시스템으로 분리할 근거가 이번 범위에는 없다.

## 2. 구조와 의존 방향

| 레이어 | 맡는 일 | 직접 의존하면 안 되는 대상 |
|---|---|---|
| interfaces | 고객·관리자 입력과 응답, HTTP 오류 매핑 | infrastructure |
| application | domain의 행동·저장 약속을 이용한 유스케이스 조율 (모델 조회 → 행동 호출 → 저장 → 결과 구성) | interfaces, infrastructure |
| domain | 상태·규칙, 필요한 repository 약속(인터페이스) | interfaces, application, infrastructure |
| infrastructure | repository 약속의 JPA 구현 | (반대로 domain의 인터페이스에 의존) |

→ 3규칙(`domain`↛나머지 3개, `application`↛`interfaces`·`infrastructure`, `interfaces`↛`infrastructure`)은 `ArchitectureTest`로 실제 검사한다 (2단계, 이 문서 범위 밖). (근거: 과제 자료의 ArchUnit 예제 코드 3규칙)

## 3. 도메인 관계

```
Brand 1 ── N Product
User  1 ── N Like N ── 1 Product
User  1 ── N Order 1 ── N OrderItem ── 상품 식별자·수량·주문 시점 단가
User  1 ── PointBalance (잔액)
Order ── 품목 금액 합계 / 포인트 결제액 / 결제 결과
```

| 관계·규칙 | 책임 객체 | 비고 |
|---|---|---|
| 재고 유효성·차감 | `Stock` (Product가 소유) | `decrease(quantity)`가 0 이하·초과 수량 거절 |
| 좋아요 유일성·중복 방지 | `Like` (User–Product 관계 자체) | 카운터 없음 — 좋아요 수는 관계 개수로 조회 |
| 잔액과 충전·결제의 관계 | `PointBalance` | `charge(amount)`와 `pay(amount)`가 각자 조건을 지킴 |
| 품목 합계·상태·결제 결과 | `Order` | `OrderItem`은 주문 시점 단가를 스냅샷으로 보관 (상품 가격이 나중에 바뀌어도 기존 주문 금액 불변) |
| 브랜드 일괄 삭제 | `BrandFacade`(application)가 트랜잭션 경계를 소유, 상품 삭제는 `ProductService`·브랜드 삭제는 `BrandService`가 맡음 | 여러 상품과 브랜드를 한 번에 바꾸는 유스케이스라 단일 Entity 규칙이 아니라 application이 조율한다. 3주차에 "삭제 가능 여부 판단(거절)"이 "연결 상품 함께 삭제"로 바뀌어 별도 판단 규칙은 없어졌다 (7-1절) |

## 4. 대표 흐름 — 포인트 충전 → 주문 확정

```
① POST /api/v1/points/charge
   → PointBalance.charge(amount) → 잔액 저장 → 충전 후 잔액 응답

② POST /api/v1/orders
   → 상품 존재·미삭제·수량>0 확인 → OrderItem[] + 합계로 Order를 DRAFT로 저장 (차감 없음)

③ POST /api/v1/orders/{orderId}/confirm
   → 본인 소유 DRAFT 확인
   → 상품 존재·미삭제 재확인, 품목 총수량 기준 Stock.decrease()
   → PointBalance.pay(합계)
   → 결제액·결제 결과 저장 → 상태 CONFIRMED로 변경

④ GET /api/v1/orders/{orderId}, GET /api/v1/points
   → 확정된 결과를 재조회해 반영 확인
```

예시 수치: 잔액 0원 → 10,000원 충전 → 합계 7,000원 주문 확정 → 잔액 3,000원.

## 5. API 계약

**사용자 식별 표기**: 아래 표의 "(사용자 식별)"은 `X-USER-ID` 헤더로 전달되는 사용자 id를 의미한다. 각 컨트롤러가 헤더를 직접 파싱하지 않고, `@LoginUserId` 커스텀 어노테이션과 이를 처리하는 `HandlerMethodArgumentResolver`로 공통화했다 (헤더 누락·숫자 변환 실패는 이 공통 처리에서 400으로 응답). 실제 회원가입/인증 체계는 이번 범위 밖이라, 전달된 id가 실존 사용자인지는 별도로 검증하지 않는다.

### 고객 — 상품·브랜드·좋아요

| method | path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|
| GET | `/api/v1/brands/{brandId}` | - | 브랜드 상세 | 없는 대상 오류 (없거나 삭제됨) |
| GET | `/api/v1/products` | `brandId?`, `page`, `size`, `sort`(`LATEST`\|`PRICE_ASC`\|`LIKES_DESC`) | 목록 + 브랜드정보 + 좋아요수 | 잘못된 입력 오류 (정의되지 않은 정렬값) |
| GET | `/api/v1/products/{productId}` | - | 상세 + 브랜드정보 + 좋아요수 | 없는 대상 오류 |
| POST | `/api/v1/products/{productId}/likes` | (사용자 식별) | 좋아요 등록 결과 (이미 등록 상태면 그대로 성공 — 멱등, 6절 정책 참고) | 없는 대상 오류 (삭제된 상품) |
| DELETE | `/api/v1/products/{productId}/likes` | (사용자 식별) | 취소 결과 (관계 없어도 성공 — 멱등) | 없는 대상 오류 (상품 자체가 없음) |
| GET | `/api/v1/likes` | (사용자 식별), `page`, `size` | 내 좋아요 목록 (삭제 상품 제외) | - |

> 원래는 `/api/v1/users/{userId}/likes`로 설계했으나, 정식 인증이 없는 지금 단계에서는 경로의 `userId`와 헤더의 사용자가 다를 때 "접근 거절"을 어떤 상태 코드로 응답할지 결정할 근거가 없었다. 그래서 경로의 `{userId}`를 없애고 항상 "요청 헤더의 나"만 조회하도록 범위를 좁혔다 — 클라이언트가 남의 목록을 요청할 방법 자체가 없으므로, 권한 검사가 불필요해진다.

### 고객 — 포인트·주문

| method | path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|
| POST | `/api/v1/points/charge` | (사용자 식별), `amount`(양의 정수) | 충전 후 잔액 | 잘못된 입력 오류 (0 이하·누락·타입 오류·표현범위 초과) |
| GET | `/api/v1/points` | (사용자 식별) | 잔액 (0원 포함) | - |
| POST | `/api/v1/orders` | (사용자 식별), 품목 목록(상품id·수량) | DRAFT 주문(품목·수량·단가·합계) | 잘못된 입력 오류 (수량 0 이하), 없는 대상 오류 (삭제/존재하지 않는 상품) |
| POST | `/api/v1/orders/{orderId}/confirm` | (사용자 식별) | CONFIRMED 주문(결제액·결제 결과) | 재고·잔액 부족 거절, 없는 대상 오류 (본인 소유 아니거나 없음) |
| GET | `/api/v1/orders` | (사용자 식별), `page`, `size` | 내 주문 목록 | - |
| GET | `/api/v1/orders/{orderId}` | (사용자 식별) | 내 주문 상세 | 없는 대상 오류 (본인 것 아니거나 없음) |

### 관리자 (`/api-admin/v1/**`, `hasRole("ADMIN")` 필요 — 6단계에서 별도 검사)

| method | path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|
| GET | `/api-admin/v1/brands` | `page`, `size` | 브랜드 전체 목록 | - |
| POST | `/api-admin/v1/brands` | 브랜드 정보 | 생성된 브랜드 | 잘못된 입력 오류 |
| GET/PUT | `/api-admin/v1/brands/{brandId}` | 브랜드 정보(PUT) | 상세/수정 결과 | 없는 대상 오류 |
| DELETE | `/api-admin/v1/brands/{brandId}` | - | 삭제 결과 — 브랜드와 연결된 미삭제 상품(재고 0 포함)을 한 트랜잭션에서 함께 삭제 처리 (7-1절) | 없는 대상 오류 (없는 브랜드) |
| GET | `/api-admin/v1/products` | `page`, `size` | 상품 전체 목록 | - |
| POST | `/api-admin/v1/products` | 브랜드id·이름·가격·재고 | 생성된 상품 | 없는 대상 오류(브랜드), 잘못된 입력 오류(이름·가격 범위) |
| GET/PUT | `/api-admin/v1/products/{productId}` | 이름·가격(PUT, 브랜드 불변) | 상세/수정 결과 | 없는 대상 오류 |
| PATCH | `/api-admin/v1/products/{productId}/stock` | 최종 수량(0 이상) | 변경된 재고 | 잘못된 입력 오류 (음수) |
| GET | `/api-admin/v1/orders` | `page`, `size` | 구매자별 주문 목록 | - |
| GET | `/api-admin/v1/orders/{orderId}` | - | 주문 상세(품목·상태·금액·결제결과) | 없는 대상 오류 |

## 6. 정책 결정

| 정책 | 결정 | 이유 |
|---|---|---|
| `likes_desc` 동률 | 보조 정렬 = 상품 id 오름차순 | 별도 기준이 없으면 같은 좋아요 수 그룹의 순서가 실행마다 달라질 수 있음. id는 항상 유일·불변이라 결정적 순서 보장 (다만 구현 시점 문제로 현재는 `LATEST`로 대체 — `ProductSortType`의 TODO 참고) |
| 좋아요 중복 등록·취소 요청 | 오류 대신 **멱등 처리** (이미 있으면 등록 요청도 그대로 성공, 없으면 취소 요청도 그대로 성공) | "중복 방지"는 저장 레코드가 1개로 유지되면 충족됨. 클라이언트가 현재 상태를 몰라도 안전하게 재요청할 수 있어 토글形 API에 더 적합하다고 판단. (버린 대안: 중복 시 `CONFLICT` — 클라이언트가 매번 현재 상태를 먼저 조회해야 해서 번거로움) |
| 한 주문 내 같은 상품 중복 품목 | **합산** — 같은 `productId`가 여러 줄로 오면 수량을 더해 1개 `OrderItem`으로 저장 | 재고 확인이 "총수량 1회 확인"으로 단순해짐. (버린 대안: 거절 — 클라이언트 실수 유발 가능성이 더 크다고 판단) |
| 브랜드·상품 삭제 방식 | **논리 삭제** (삭제 시각을 별도 필드로 기록, 값이 있으면 삭제된 것으로 간주) | 이미 주문에 담긴 상품·브랜드 정보가 삭제로 함께 사라지면 `OrderItem`이 참조 무결성을 잃음. 물리 삭제는 과거 주문의 조회를 깨뜨릴 위험이 있어 제외 |
| 브랜드 삭제 시 연결 상품 (3주차 변경) | 연결된 미삭제 상품이 있으면 거절하던 계약을, **그 상품들(재고 0 포함)을 브랜드와 한 트랜잭션에서 함께 논리 삭제**하는 계약으로 바꾼다 | 3주차 과제 요구로 계약을 확장한다. 논리 삭제라 과거 주문이 가리키는 상품 행은 그대로 남는다. (버린 대안: ① 상품마다 별도 트랜잭션으로 삭제 — 중간 실패 시 일부만 삭제됨. ② DB cascade에 맡김 — 이 프로젝트에는 상품을 가리키는 JPA 연관관계·cascade 설정이 없고, 업무 결과를 DB 설정에 맡기지 않는다는 과제 조건에도 어긋남. ③ 물리 삭제 — 위 논리 삭제 결정과 같은 이유로 제외) |
| 상품 이름·가격 유효 범위 | 이름: 공백만 있는 문자열 거절 + 최대 100자. 가격: 0 이하 거절 + 최대 `100,000,000`(1억) | 명시적 상한이 없으면 입력 검증 테스트의 기대값을 정할 수 없음. 1억은 임의 상한 — 실제 상품 특성에 따라 재검토 가능 |
| 포인트 잔액 합산 상한 | `PointBalance.amount`는 `long` 사용, 충전 후 합계가 `1,000,000,000`(10억) 초과 시 충전 자체를 거절하고 기존 잔액 유지 | `long` 자체는 오버플로 여지가 거의 없지만, 실습 범위에서 "표현 범위 초과"를 실제로 테스트하려면 임의의 상한이 있어야 함 |

## 7. 3주차 — 트랜잭션 경계와 동시 변경 흐름

2주차 구현을 Controller부터 repository까지 코드로 따라가 현재 상태를 확인한 뒤, 3주차에 바꿀 지점을 적는다. 각 흐름은 **시작점·호출 경로·예외·잠금 순서**를 표시한다. (잠금 순서는 7-3절에서 다룬다.)

### 7-1. 브랜드 일괄 삭제 — `DELETE /api-admin/v1/brands/{brandId}`

| 같은 성공·실패로 묶을 것 | 보존할 것 |
|---|---|
| 브랜드의 삭제 처리 + 연결된 미삭제 상품 전체(재고 0 포함)의 삭제 처리 | 다른 브랜드·상품, 기존 주문 정보(품목·수량·단가·총액·결제 결과) |

**현재 흐름 (2주차 구현)**

```
Controller.deleteBrand
 → BrandFacade.deleteBrand                       (트랜잭션 없음)
     ① ProductService.hasActiveProductsByBrand   → 자기 트랜잭션(읽기 전용)을 열고 닫음
        미삭제 상품이 있으면 CONFLICT로 거절
     ② BrandService.deleteBrand                  → 별도 트랜잭션: 브랜드 삭제 시각 기록
```

거절하던 동작이라 상품은 이 흐름에서 바뀌지 않았다. 상품까지 지우도록 호출만 늘리면 상품 삭제와 브랜드 삭제가 **각자의 트랜잭션에서 따로 commit**되어, 중간에 실패하면 일부만 남는다.

**변경 후 흐름**

```
Controller.deleteBrand(brandId)
 → [프록시] BrandFacade.deleteBrand(brandId)            ◀ @Transactional — 시작·종료 지점
     ① BrandService.getBrandForUpdate(brandId)          브랜드 잠금 조회 (없으면 NOT_FOUND)
     ② ProductService.deleteAllActiveByBrand(brandId)   미삭제 상품 id를 오름차순으로 읽고(재고 0 포함),
                                                        하나씩 잠금 조회해 삭제 여부를 다시 확인한 뒤 삭제 처리
     ③ BrandService.deleteBrand(brandId)                브랜드 삭제 처리·저장 (이미 잠근 행)
 ← 정상: 한 번에 commit — 브랜드와 모든 상품의 삭제 시각이 함께 반영
 ← 예외: 어느 단계에서든 RuntimeException(CoreException 포함)이 Facade 밖으로 나오면 rollback
```

| 항목 | 결정 | 이유 |
|---|---|---|
| 트랜잭션 시작점 | `BrandFacade.deleteBrand`에 `@Transactional` | 상품·브랜드 두 서비스에 걸친 변경이라 한 서비스 메서드 안에 둘 수 없다. `OrderFacade`가 이미 Facade 메서드에 `@Transactional`을 두는 방식과 같다. (버린 대안: 각 Service 메서드의 `@Transactional`만 사용 — 서비스 호출마다 트랜잭션이 따로 열려 commit이 갈라짐) |
| 예외와 rollback | 별도 설정 없이 기본 규칙 사용 | `CoreException`은 `RuntimeException`이라 기본 규칙으로 rollback된다. 이 흐름에서는 예외를 try-catch로 삼키거나 `REQUIRES_NEW`·별도 트랜잭션으로 상품만 따로 commit하지 않는다. |

**프록시를 거치는 호출**

| 호출 | 프록시 경유 | 비고 |
|---|---|---|
| Controller → `BrandFacade.deleteBrand` | 예 | `@Transactional`이 붙은 Facade가 프록시 빈이라 트랜잭션은 여기서 시작 |
| `BrandFacade` → `ProductService.deleteAllActiveByBrand` | 예 | 이미 열린 트랜잭션이 있어 새로 열지 않고 참여 (기본 전파 규칙) |
| `BrandFacade` → `BrandService.deleteBrand` | 예 | 위와 동일 |
| `BrandService.deleteBrand` → `getBrandForUpdate` | 아니오 (같은 객체 안 호출) | 프록시를 거치지 않지만 이미 열린 쓰기 트랜잭션 안이라 잠금 조회에 문제 없음. 같은 트랜잭션이라 이미 잠근 행을 한 번 더 조회한다 |

**롤백 범위**

| 구분 | 내용 |
|---|---|
| 실패 시 원상태로 돌아가는 것 | 브랜드의 삭제 시각, 이번 요청이 삭제 처리한 모든 상품의 삭제 시각 |
| 이 흐름이 바꾸지 않는 것 | 다른 브랜드·상품, 주문·주문 품목(읽지도 쓰지도 않음), 좋아요 행(남겨 두고 조회에서 제외, 취소는 기존대로 허용) |
| 응답 | 실패하면 오류 응답만 반환한다. 일부 성공 응답은 없다 |

**과거 주문이 유지되는 근거**: 주문 품목은 상품을 `productId` 값으로만 가리키고(상품을 가리키는 JPA 연관관계·cascade 설정 없음, 코드에서 확인), 주문 시점 단가를 `OrderItem`에 보관한다(3절). 상품 행을 지우지 않고 삭제 시각만 기록하면 과거 주문의 조회 결과는 달라지지 않는다.

**경계 케이스**

| 상황 | 결과 |
|---|---|
| 연결 상품이 없는 브랜드 | 상품 삭제 0건, 브랜드만 삭제, 정상 응답 |
| 재고 0인 상품이 연결됨 | 재고와 무관하게 삭제 대상에 포함 |
| 이미 삭제된 상품이 섞여 있음 | 대상에서 제외 (기존 삭제 시각 유지) |
| 없는 브랜드 | 기존 없는 대상 오류, 변경 없음 |
| 이미 삭제된 브랜드를 다시 삭제 | 기존 동작 유지 — 삭제는 멱등이고 관리자 조회는 삭제된 브랜드도 찾는다 (코드로 확인, 서비스·API 흐름에서 이를 검증하는 기존 테스트는 없음) |
| 관리자가 아니거나 식별되지 않은 요청 | 기존 관리자 접근 규칙으로 403, 서비스가 호출되기 전에 차단되어 변경 없음 |

**결정 — 상품의 삭제 처리는 잠금 조회 후 `delete()` (7-3절 전략과 같은 규칙)**

브랜드 행을 잠근 뒤, 브랜드의 미삭제 상품 id를 오름차순으로 조회하고 하나씩 잠금 조회해 삭제 여부를 다시 확인한 다음 `delete()`를 호출한다. 이 상품 행은 주문 확정·관리자 재고 설정이 `stock`을 바꾸는 행과 같으므로, 7-3절의 잠금 규칙과 순서(브랜드 → 상품 id 오름차순)를 따른다.

| 방식 | 판단 |
|---|---|
| 채택: 잠금 조회 후 변경 감지 UPDATE | 도메인 메서드(`delete()`)와 `updated_at` 갱신 규칙을 그대로 쓰고, 잠금을 잡은 뒤에 읽으므로 낡은 재고 값을 덮어쓰지 않는다 |
| 버린 대안: `UPDATE ... SET deleted_at=… WHERE brand_id=? AND deleted_at IS NULL` | 재고 컬럼은 건드리지 않지만 도메인 메서드와 `updated_at` 갱신 규칙을 우회하고, 규칙을 도메인에 두기 위해 조건부 갱신을 버린 7-3절의 이유와 어긋난다 |

**검증 시 유의점**: `delete()`는 필드만 바꾸고 UPDATE는 flush 시점에 나간다. 중간 실패 테스트가 "변경 SQL이 나간 뒤" 실패를 만들려면 실패 주입 위치(브랜드 저장 단계)보다 먼저 flush가 일어나야 하므로, 주입 위치와 flush 시점을 함께 정한다.

**기존 계약 변경의 영향 (처리 결과)**
- "연결된 미삭제 상품이 있으면 409" 계약은 폐기했고, `AdminV1ApiE2ETest`의 옛 계약 테스트는 새 계약(함께 삭제)을 검증하도록 바꿨다.
- `ProductService.hasActiveProductsByBrand`는 제거했다. `ProductRepository.existsActiveByBrandId`는 `ProductRepositoryTest`가 직접 검증하고 있어 남겼다.
- 새 repository 약속은 브랜드의 미삭제 상품 **id 목록**을 id 오름차순으로 읽는 `findActiveIdsByBrandId`다. 엔티티를 읽으면 잠금 없이 로드되어 뒤의 잠금 조회로 상태가 갱신되지 않으므로 id만 읽는다 (7-4절 반례 7).

### 7-2. 최초 주문 확정 — `POST /api/v1/orders/{orderId}/confirm`

| 같은 성공·실패로 묶을 것 | 보존할 것 |
|---|---|
| 재고 차감, 포인트 차감, 주문 상태 CONFIRMED 변경, 결제액·결제 결과 저장 | 실패한 주문의 DRAFT 상태, 변경 전 재고·잔액 |

**현재 흐름 (2주차 구현, 코드로 확인)**

```
Controller.confirmOrder(orderId, userId)
 → [프록시] OrderFacade.confirmOrder             ◀ @Transactional — 시작·종료 (이미 있음)
     ① OrderService.getDraftOrderOwnedBy         주문 조회 후 본인 소유·DRAFT만 통과 (잠금 없음)
     ② 품목마다 ProductService.decreaseStock     미삭제 상품 조회 → Stock.decrease → 부족하면 BAD_REQUEST
     ③ PointService.pay                          잔액 조회 → PointModel.pay → 부족하면 CONFLICT → 저장
     ④ OrderService.confirmOrder                 DRAFT → CONFIRMED + 결제액 기록 → 저장
 ← 정상: commit / 예외: rollback
```

**원자성은 구조상 이미 한 트랜잭션이다.** 서비스의 `@Transactional`(`getDraftOrderOwnedBy`·`decreaseStock`·`pay`·`confirmOrder`)은 모두 Facade가 연 트랜잭션에 참여한다. 코드 전체에 `REQUIRES_NEW`·`TransactionTemplate`이 없고, 예외를 삼키는 try-catch도 없다(`LoginUserIdArgumentResolver`의 숫자 변환 catch만 있음). 그래서 이 흐름에서 3주차에 새로 필요한 것은 (가) 변경 SQL이 나간 뒤 실패해도 전부 되돌아간다는 **증거(테스트)**와 (나) **동시 요청 보호**다.

**변경 후 흐름 (7-3절의 잠금 규칙과 순서 적용)**

```
Controller.confirmOrder(orderId, userId)
 → [프록시] OrderFacade.confirmOrder                ◀ @Transactional — 시작·종료 (변경 없음)
     ① 주문 잠금 조회(본인 소유) → 잠근 뒤 DRAFT 확인 (아니면 404)
     ② 품목을 상품 id 오름차순으로 정렬 → 하나씩 상품 잠금 조회 → 미삭제 확인 → Stock.decrease
     ③ 포인트 잠금 조회 → PointModel.pay
     ④ 주문 CONFIRMED + 결제액 기록 (이미 잠근 주문)
 ← 정상: commit (모든 잠금 해제) / 예외: rollback (모든 잠금 해제)
```

바뀌는 것은 읽는 방식(잠금 조회)과 순서(품목 정렬)뿐이고, 트랜잭션 시작점과 호출 순서(주문 → 상품 → 포인트 → 주문 상태)는 현재와 같다.

**프록시를 거치는 호출**

| 호출 | 프록시 경유 | 비고 |
|---|---|---|
| Controller → `OrderFacade.confirmOrder` | 예 | 트랜잭션은 여기서 시작 |
| `OrderFacade` → `OrderService`·`ProductService`·`PointService` | 예 | 이미 열린 트랜잭션에 참여 |
| `ProductService.decreaseStock` → `getActiveProductForUpdate` | 아니오 (같은 객체 안 호출) | 이미 열린 쓰기 트랜잭션 안이라 잠금 조회에 문제 없음 |

**실패 종류와 현재 응답**

| 상황 | 현재 응답 | 근거 |
|---|---|---|
| 없는 주문, 남의 주문, DRAFT가 아닌 주문 | NOT_FOUND | `getDraftOrderOwnedBy`가 본인 소유·DRAFT만 통과시킴 |
| 삭제된 상품이 품목에 있음 (삭제 전에 만든 DRAFT) | NOT_FOUND | `decreaseStock` → `getProduct`가 미삭제 상품만 조회하므로 확정할 때 다시 확인되는 구조 |
| 재고 부족 | BAD_REQUEST | `Stock.decrease`가 "수량 0 이하"와 같은 코드·문구로 거절 |
| 잔액 부족 | CONFLICT | `PointModel.pay` |
| CONFIRMED 주문이 `confirm()`에 도달 | CONFLICT | `OrderModel.confirm`. 순차 재요청은 위 DRAFT 필터에 걸려 여기까지 오지 못하고, 동시 요청은 둘 다 DRAFT로 읽어 통과한다 |

**품목 처리 순서**: `createOrder`가 `groupingBy`(기본 `HashMap`)로 같은 상품을 합산해 품목을 저장하므로, 저장·처리 순서가 상품 id 오름차순이라는 보장이 없다. 잠금 순서를 정하려면 확정할 때 상품 id 오름차순으로 정렬해 처리해야 한다.

**롤백 범위**

| 구분 | 내용 |
|---|---|
| 실패 시 원상태로 돌아가는 것 | 이번 확정에서 차감한 모든 상품의 재고, 포인트 잔액, 주문 상태(DRAFT 유지)와 결제액(없음) |
| 이 흐름이 바꾸지 않는 것 | 다른 주문, 품목에 없는 상품, 다른 사용자의 잔액 |

**기존 테스트의 한계와 보완**: `OrderV1ApiE2ETest`의 재고 부족(400)·잔액 부족(409) 테스트는 실패 뒤 재고·잔액이 그대로인지 확인하지만, 실패가 도메인 규칙 검사에서 일어난다. 변경 감지는 UPDATE를 flush 때 내보내므로 그 시점에 변경 SQL이 실제로 나갔는지는 보장되지 않는다. 그래서 `OrderTransactionTest`가 재고·포인트 변경을 flush한 뒤 주문 저장 단계에서 실패시키고, 요청이 끝난 뒤 새로 조회해 전부 원래대로인지 확인한다. 결제만 `REQUIRES_NEW`로 바꿔 보면 이 테스트가 `expected: 100000 but was: 75000`으로 실패한다.

**계약 결정 — 이미 CONFIRMED인 주문의 재확정**: 현재 동작인 **404(NOT_FOUND)를 유지**한다. 순차 재요청은 지금도 `getDraftOrderOwnedBy`의 DRAFT 필터에서 404가 되므로 계약이 바뀌지 않는다. 동시에 같은 주문을 확정하는 요청도 **잠금을 잡은 뒤 DRAFT 여부를 다시 확인**(구현 요건)하면 늦게 온 요청이 같은 404가 된다. `OrderModel.confirm`이 던지는 CONFLICT는 모델 내부의 마지막 방어선으로 남긴다. (버린 대안: 409로 통일 — 순차 재요청의 응답이 404에서 409로 바뀌어 기존 계약이 달라진다)

### 7-3. 같은 재고·잔액의 동시 변경

**보호할 행과 현재의 읽기 → 검사 → 변경**

| 경로 | 바꾸는 행 | 현재 동작 |
|---|---|---|
| 주문 확정 — 재고 | `product`(id) | 상품 조회 → `Stock.decrease`로 부족 검사 → 변경 감지 UPDATE (읽은 값에서 뺀 **새 값**을 씀) |
| 주문 확정 — 포인트 | `user_point`(user_id) | 잔액 조회 → `PointModel.pay`로 부족 검사 → 변경 감지 UPDATE (새 잔액 값) |
| 포인트 충전 | `user_point`(user_id) | 잔액 조회 → `PointModel.charge`(상한 검사) → 변경 감지 UPDATE |
| 관리자 재고 설정 | `product`(id) | 상품 조회 → `changeStock(quantity)`로 값 교체 → 변경 감지 UPDATE |
| 주문 확정 — 상태 | `orders`(id) | 주문 조회(본인·DRAFT) → `OrderModel.confirm` → 변경 감지 UPDATE |
| 브랜드 일괄 삭제 (7-1절) | `product`(id) 여러 행 | 상품 조회 → `delete()` → 변경 감지 UPDATE |

위 표는 잠금 도입 전의 동작이다. 도입 뒤 실제로 나가는 잠금 SQL은 아래 "경로별 적용과 잠금 순서"에 있다.

**잠금 도입 전에는 보호 장치가 없었다.** `@Lock`·`@Version`·조건부 UPDATE가 코드에 없고(검색으로 확인), 일반 SELECT는 행을 잠그지 않는다. 그래서 두 트랜잭션이 같은 값을 읽고 각자 새 값을 쓴다. 트랜잭션 경계도 경로마다 다르다. `OrderFacade.confirmOrder`만 Facade가 트랜잭션을 열고, `PointFacade.charge`·`ProductFacade.changeStock`은 서비스 메서드 하나가 자기 트랜잭션이다. 세션은 요청 전체로 이어지지 않는다(`open-in-view: false`).

또한 Hibernate 기본 UPDATE는 바꾸지 않은 컬럼도 함께 쓴다(`ProductModel`·`PointModel`·`OrderModel` 모두 `@DynamicUpdate` 없음). 서로 다른 컬럼을 바꾸는 경로끼리도 덮어쓸 수 있다. 예를 들어 재고 차감이 읽어 둔 `deleted_at`(NULL)을 그대로 다시 쓰면, 그 사이 브랜드 삭제가 기록한 삭제 시각이 되살아날 수 있다.

**현재 구조에서 깨지는 시나리오**

| 시나리오 | 준비·실행 | 기대 결과 | 현재 구조에서 깨지는 방식 |
|---|---|---|---|
| 재고 경쟁 | 재고 5, 서로 다른 DRAFT 8개를 동시에 확정 | 성공 5·재고 부족 3·기술 오류 0·최종 재고 0 | 여러 요청이 재고 5를 함께 읽고 각자 줄인 값을 써서, 성공이 5건을 넘고 최종 재고가 성공 수량과 맞지 않을 수 있다 |
| 포인트 경쟁 | 잔액 10,000원, 4,000원 DRAFT 3개 | 성공 2·잔액 부족 1·기술 오류 0·최종 잔액 2,000원 | 3건 모두 10,000을 읽고 각자 6,000을 써서 3건 성공·최종 6,000이 될 수 있다 |
| 충전과 결제 | 잔액 10,000원에서 충전 2,000 + 결제 7,000 동시 | 둘 다 성공·기술 오류 0·최종 5,000원 | 둘 다 10,000을 읽고 12,000 또는 3,000을 써서 마지막 commit 값만 남는다 |
| 같은 주문 중복 확정 | 같은 DRAFT 주문을 동시에 두 번 확정 | 한 번만 반영 | 둘 다 DRAFT로 읽어 통과하고 재고·잔액이 두 번 차감될 수 있다 |

**성공·업무 거절·기술 오류 집계 기준**: 성공은 정상 응답, 업무 거절은 `CoreException`(재고 부족 BAD_REQUEST, 잔액 부족 CONFLICT), 기술 오류는 그 외 예외(잠금 대기 초과·교착·낙관적 충돌 한도 초과·제약 위반 등)로 나눈다. 재고 경쟁과 포인트 경쟁은 요청마다 서로 다른 주문이라 400(재고 부족)과 409(잔액 부족)로 구분할 수 있다.

**범위 밖으로 둔 한계**: 잔액 행이 아직 없는 사용자의 동시 최초 충전은 `user_id` 유니크 제약 위반(기술 오류)이 될 수 있다. 이번 시나리오는 이미 잔액 행이 있는 사용자만 다룬다.

**결정 — 전부 비관적 잠금 (`SELECT ... FOR UPDATE`)**

재고·포인트·주문 상태를 바꾸는 모든 경로가 그 행을 잠금 조회로 먼저 읽고, 읽은 값으로 기존 도메인 메서드(`Stock.decrease`·`PointModel.pay`·`OrderModel.confirm`)가 검사·변경한다. 과제는 하나로 통일하라고 요구하지 않고 "같은 행을 바꾸는 모든 경로가 선택한 규칙을 우회하지 않게" 하라고 한다. 행마다 다른 방식을 고를 이점이 없어 하나로 통일한다.

| 구분 | 내용 |
|---|---|
| 고른 이유 | ① 경합이 한 행에 몰린다 — 재고 행 하나에 8건, 포인트 행 하나에 3건. 줄을 세우면 기대 결과(확정 5·재고 부족 3·기술 오류 0)가 재시도 설정 없이 정해진다. ② 도메인 규칙이 제자리에 남는다 — 기존 `StockTest`·`PointModelTest`·`OrderModelTest`가 규칙을 계속 검증한다. ③ 지금 트랜잭션 구조와 맞는다 — `OrderFacade.confirmOrder`가 이미 한 트랜잭션이라 repository에 잠금 조회만 추가하면 된다. ④ 점검이 쉽다 — 상품 행을 쓰는 경로 5개(확정·관리자 재고 설정·상품 수정·상품 삭제·브랜드 일괄 삭제)와 포인트 행을 쓰는 경로 2개(충전·결제)가 모두 "잠금 조회로 먼저 읽는다"는 하나의 규칙을 따르는지만 보면 된다. ⑤ 낡은 값 덮어쓰기가 사라진다 — 잠금을 잡은 뒤에 읽으므로 모든 컬럼 UPDATE가 낡은 값을 쓰지 못한다 (`updateProduct`가 재고를, 재고 차감이 `deleted_at`을 덮어쓰는 경우) |
| 버린 대안: 낙관적 잠금 | 이 시나리오는 충돌이 많아 재시도가 낭비된다. 재시도 한도가 요청 수(재고 경쟁은 8건이면 최대 7번)보다 작으면 기술 오류가 생겨 기대 결과가 깨진다. 재시도를 트랜잭션 밖으로 빼는 구조 변경, 엔티티의 version 컬럼, 편집 충돌 응답 계약이 필요하다 |
| 버린 대안: 조건부 갱신 | 검사가 SQL(`stock >= ?`)로 옮겨가 `Stock.decrease`·`PointModel.pay` 규칙이 중복되거나 우회된다. 0행 갱신의 원인(부족·삭제·없음)을 따로 읽어 구분해야 하고, 벌크 UPDATE는 영속성 컨텍스트와 `updated_at` 갱신도 우회한다 |
| 버린 대안: 낙관적·비관적 혼합 | 편집 경로는 한 요청 안(수 ms)에서 읽고 쓰므로 충돌이 드물어 비관적 잠금도 대기가 거의 없다. 이득 없이 version 컬럼, 충돌 응답 계약, 설명할 방식만 늘어난다 |
| 비용 | 같은 행의 요청은 직렬화되고, 대기하는 동안 DB 커넥션을 잡으며, 잠금은 commit까지 유지된다. 처리량은 측정하지 않았다 |
| 확인한 것 | 잠금 순서를 어기면(품목 정렬을 빼면) 반대 순서 주문 테스트에서 MySQL 교착이 나고, 이 오류는 업무 거절이 아니라 **기술 오류**로 집계된다 |
| 아직 확인하지 않은 한계 | 테스트 컨테이너의 실제 잠금 대기 시간 설정(`innodb_lock_wait_timeout`)은 확인하지 않았다. 처리량도 측정하지 않았다 |
| 재검토 조건 | 인기 상품 한 행의 대기가 문제가 될 만큼 처리량 목표가 생기면, 먼저 트랜잭션 범위를 줄이고 그다음 조건부 갱신을 검토한다 |

**적용 규칙**
1. 보호 대상 행(`product`·`user_point`·`orders`·`brand`)을 바꾸는 경로는 그 행을 잠금 조회로 **먼저** 읽고, 그 값으로 검사·변경한다. 잠금 없이 읽은 값으로 판단하지 않는다. 같은 트랜잭션에서 그 행을 **처음 로드하는 조회가 잠금 조회**여야 한다. 이미 잠금 없이 로드된 엔티티는 나중에 잠금 조회를 해도 상태가 갱신되지 않을 수 있다 (7-4절 반례 7).
2. 잠금 조회는 읽기 전용이 아닌 트랜잭션 안에서 실행하고, 잠금은 commit 또는 rollback까지 유지한다.
3. 상태 확인(DRAFT 여부, 삭제 여부)은 **잠금을 잡은 뒤**에 한다. 같은 주문을 동시에 확정하면 늦게 온 요청이 잠금 뒤 재확인에서 DRAFT가 아님을 보고 404가 된다 (7-2절 결정).
4. 잠금 조회 약속은 domain의 repository 인터페이스에 두고 `@Lock`은 infrastructure 구현에서만 쓴다. domain은 JPA 잠금 기술에 의존하지 않는다.
5. 금지: JVM 전역 `synchronized`, 잠금 구간의 테스트용 장벽·`sleep`, 새 요청키 체계, 성공 응답 재사용 기능.

**경로별 적용과 잠금 순서**

잠금 순서의 기준은 **주문 → 브랜드 → 상품(id 오름차순) → 포인트**다. 모든 경로가 이 순서의 부분집합만, 같은 방향으로 잠근다.

| 경로 | 잠그는 행 (순서대로) |
|---|---|
| 주문 확정 | 주문(id) → 상품(품목의 상품 id 오름차순) → 포인트(user_id) |
| 포인트 충전 | 포인트(user_id) |
| 관리자 재고 설정 | 상품(id) |
| 상품 수정·삭제 | 상품(id) |
| 브랜드 일괄 삭제 | 브랜드(id) → 상품(브랜드의 미삭제 상품 id 오름차순) |
| 브랜드 이름 수정 | 브랜드(id) |

순서를 이렇게 정한 이유:
- 주문 확정이 쓰는 자원 순서가 이미 주문 → 상품 → 포인트이고 현재 호출 순서도 같아서(주문 조회 → 재고 차감 → 포인트 결제 → 주문 상태 변경), 이 순서를 기준으로 삼으면 확정 경로의 변경이 가장 작다.
- 나머지 경로는 이 순서의 부분집합이라 서로 반대 방향으로 잠그는 경로가 없고, 따라서 교착이 생기는 순환이 없다.
- 같은 종류 안에서는 id 오름차순이다. 겹치는 상품을 사려는 두 확정, 확정과 브랜드 일괄 삭제가 같은 순서로 상품을 잠근다.
- 상품은 **id를 오름차순으로 정렬한 뒤 하나씩** 잠금 조회한다. `IN (...) FOR UPDATE` 한 문장으로 묶으면 행을 잠그는 순서가 실행 계획에 달려 있어, 코드만으로는 순서를 보장했다고 말할 수 없다. 품목은 `createOrder`의 `HashMap` 저장 순서에 기대지 않고 확정할 때 정렬한다.
- 브랜드 일괄 삭제는 브랜드를 잠근 뒤 미삭제 상품 id를 오름차순으로 조회하고, 하나씩 잠금 조회하며 삭제 여부를 다시 확인하고 `delete()`한다.

**SQL 로그로 확인한 잠금 순서** (상품 2개짜리 주문 확정, 상품 2개 브랜드 삭제를 각각 한 번 실행한 `OrderTransactionTest`·`BrandRemovalTransactionTest` 로그):

```
주문 확정
  select ... from orders  where id=? and user_id=? for update
  select ... from product where id=? for update      (상품 1)
  select ... from product where id=? for update      (상품 2)
  select ... from user_point where user_id=? for update

브랜드 일괄 삭제
  select ... from brand   where id=? for update      (Facade)
  select ... from product where id=? for update      (상품 1)
  select ... from product where id=? for update      (상품 2)
  select ... from brand   where id=? for update      (deleteBrand — 같은 트랜잭션이라 이미 잠근 행)
```

설계한 순서(주문 → 상품 id 오름차순 → 포인트, 브랜드 → 상품)와 같다. 로그에는 상품 id 값이 찍히지 않으므로 오름차순 자체는 로그가 아니라 아래 교착 테스트로 확인한다.

**동시성 테스트 결과** (`OrderConcurrencyTest`, 실제 MySQL, 6건 통과)

| 시나리오 | 확인한 것 |
|---|---|
| 재고 경쟁 | 재고 5에 DRAFT 8개 → 성공 5·재고 부족 3·기술 오류 0·최종 재고 0 |
| 포인트 경쟁 | 잔액 10,000원에 4,000원 3개 → 성공 2·잔액 부족 1·기술 오류 0·최종 2,000원 |
| 충전과 결제 | 둘 다 성공·최종 잔액 5,000원 |
| 같은 주문 중복 확정 | 한 번만 반영, 나머지는 404 |
| 관리자 재고 설정과 확정 | 최종 재고가 두 직렬 실행 결과 중 하나 |
| 반대 순서 품목 | 같은 상품 두 개를 서로 반대 순서로 담은 주문들이 교착 없이 모두 성공 |

**테스트가 보호 장치를 실제로 잡는지 확인한 변경 실험** (실험 뒤 모두 원복하고 검색으로 확인)

| 변경 | 결과 |
|---|---|
| 상품 잠금 조회를 일반 조회로 되돌림 | 경쟁 테스트 6건 중 4건 실패 |
| 포인트·주문 잠금 조회를 일반 조회로 되돌림 | 기대한 3건만 실패 |
| 확정 때 품목 정렬을 뺌 | 반대 순서 테스트가 MySQL 교착으로 실패 (기술 오류로 집계됨) |
| `PointService.pay`를 `REQUIRES_NEW`로 바꿈 | `OrderTransactionTest`만 실패 (`expected: 100000 but was: 75000`) |

**범위 밖으로 둔 한계 (추가)**: 브랜드 일괄 삭제와 상품 등록의 동시 실행은 선택 확장이라 다루지 않는다. 삭제 중인 브랜드에 새 상품이 등록될 수 있다.

### 7-4. 반례 검토 (설계와 현재 코드의 대조)

설계(7-1~7-3)와 현재 코드를 놓고 자기 호출·예외 삼키기·독립 commit·빠진 잠금 대상의 반례를 찾았다. 아래 표는 구현 전에 AI(Claude)가 코드를 읽고 검색한 **정적 대조**다. 구현 뒤 diff·검색·테스트로 다시 확인한 결과는 표 아래 "구현 뒤 확인"에 적었다.

| # | 분류 | 반례 | 코드에서 확인한 것 | 설계 대응 |
|---|---|---|---|---|
| 1 | 자기 호출 | 잠금 조회를 같은 클래스의 다른 메서드로 호출하면 그 메서드의 `@Transactional`(특히 `readOnly=true`)이 적용되지 않는다 | 서비스 안의 같은 객체 호출(`getBrandForAdmin`·`getProductForAdmin`·`getProduct`)은 모두 이미 열린 쓰기 트랜잭션 안에서 일어나 문제가 없다. 다만 `getDraftOrderOwnedBy`는 `readOnly=true`다 | 잠금 조회는 쓰기 트랜잭션 안에서만 호출하고, 잠금 조회 메서드에는 `readOnly`를 붙이지 않는다 (구현 때 확인) |
| 2 | 잠금 범위 | Facade에 트랜잭션이 없는 경로에서 "잠금 조회"와 "변경"을 서로 다른 서비스 호출로 나누면, 첫 호출이 끝날 때 잠금이 풀려 보호가 사라진다 | `PointFacade.charge`·`ProductFacade.changeStock`은 서비스 메서드 하나(`charge`·`changeStock`)가 읽기와 변경을 한 트랜잭션으로 끝낸다 | 이 경로는 잠금·검사·변경을 서비스 메서드 하나 안에서 끝낸다. 나누려면 Facade에 `@Transactional`을 둔다 |
| 3 | 예외 삼키기 | 재시도나 부분 실패 처리를 하려고 서비스·Facade에서 예외를 잡고 진행하면, 이미 바뀐 재고가 그대로 commit되거나 rollback-only 충돌이 난다 | 서비스·Facade에 try-catch가 없다 (`LoginUserIdArgumentResolver`의 숫자 변환 catch만 있음) | 서비스·Facade에서 예외를 잡지 않는다. 결과를 분류하려고 예외를 잡는 것은 테스트 worker에만 둔다 |
| 4 | 예외 삼키기 (분류) | 잠금 대기 초과·교착이 재고·잔액 부족으로 둔갑해 집계된다 | 서비스에서 올라오는 예외 중 `CoreException`만 업무 오류 코드(4xx)로 바뀌고, DB 예외 등 나머지는 `ApiControllerAdvice`의 `Throwable` 핸들러에서 500이 된다 | 업무 거절은 `CoreException`, 그 외는 기술 오류로 분류한다. 구현 뒤 잠금 오류가 기술 오류로 집계되는지 확인한다 |
| 5 | 독립 commit | `REQUIRES_NEW`나 별도 트랜잭션으로 상품·포인트·주문을 따로 commit하면 일부만 반영된다 | `REQUIRES_NEW`·`Propagation`·`TransactionTemplate`이 코드에 없다 | 도입하지 않는다. 브랜드 삭제도 상품마다 별도 트랜잭션을 쓰지 않는다 (7-1절) |
| 6 | 빠진 잠금 대상 | 설계 표에 없는 쓰기 경로가 상품·포인트·주문 행을 바꾼다 | 쓰기 경로를 훑었다. `createOrder`는 주문 INSERT만 하고 상품은 읽기만 하며, `LikeService`는 좋아요 행만 쓰고, 상품·브랜드 생성은 INSERT다 | 추가 대상 없음. 주문 생성 시 읽은 상품 가격이 직후 바뀌는 경우는 이번 불변식 범위 밖이다 |
| 7 | 빠진 잠금 대상 | 같은 트랜잭션에서 먼저 잠금 없이 로드한 엔티티는, 나중에 잠금 조회를 해도 상태가 갱신되지 않을 수 있다 (영속성 컨텍스트가 이미 가진 인스턴스를 쿼리 결과로 덮어쓰지 않는다) | 현재 `confirmOrder`는 `getDraftOrderOwnedBy`로 주문을 **가장 먼저, 잠금 없이** 읽는다 | 그 행을 트랜잭션에서 처음 로드하는 조회가 잠금 조회여야 한다. 7-3절 적용 규칙 1에 반영했고, 확정 흐름의 ①을 "주문 잠금 조회"로 바꾼다. 구현 때 테스트로 확인한다 |
| 8 | 빠진 잠금 대상 | 상품 수정·삭제·재고 설정이 기존의 잠금 없는 조회를 그대로 쓴다 | `updateProduct`·`changeStock`·`deleteProduct`가 모두 `getProductForAdmin`(잠금 없음)을 쓴다 | 이 세 경로를 잠금 조회로 바꾼다 (7-3절 경로 표). 구현 뒤 잠금 없는 조회를 쓰는 쓰기 경로가 남지 않았는지 검색으로 확인한다 |
| 9 | 잠금 순서 | 두 경로가 반대 방향으로 잠가 교착이 난다 | 현재는 잠금이 없다. 확정은 주문 → 상품 → 포인트 순으로 호출한다 | 7-3절의 순서(주문 → 브랜드 → 상품 id 오름차순 → 포인트)를 따르면 순환이 없다. 구현 뒤 품목 정렬과 실제 잠금 순서를 SQL 로그로 확인한다 |

**구현 뒤 확인**

| # | 확인한 것 | 방법 |
|---|---|---|
| 1 | `getDraftOrderOwnedBy`에서 `readOnly`를 뺐고, 잠금 조회를 호출하는 서비스 메서드는 모두 쓰기 트랜잭션이다 | 코드 대조 |
| 2 | 충전·재고 설정·상품 수정·삭제·결제는 잠금 조회와 변경이 서비스 메서드 하나 안에 있다. 확정·브랜드 삭제는 Facade가 트랜잭션을 연다 | 코드 대조 |
| 3 | 서비스·Facade에 예외를 잡는 코드를 추가하지 않았다 | diff 대조 |
| 4 | 교착이 기술 오류로 집계된다 | 품목 정렬을 빼는 변경 실험 (7-3절) |
| 5 | 결제를 `REQUIRES_NEW`로 바꾸면 롤백 테스트가 실패해, 한 트랜잭션이라는 가정을 테스트가 지킨다. 실험 뒤 원복했고 코드에 `REQUIRES_NEW`는 없다 | 변경 실험, 검색 |
| 6 | 추가 잠금 대상은 없었다 | 쓰기 경로 재검토 |
| 7 | 확정은 주문을, 상품 계열 경로는 상품을 잠금 조회로 처음 로드한다. 브랜드 일괄 삭제는 엔티티 목록 대신 상품 id 목록(`findActiveIdsByBrandId`)만 읽고 하나씩 잠금 조회한다 | 코드 대조, SQL 로그 |
| 8 | 잠금 없이 상품·브랜드를 읽는 곳은 관리자 단건 조회(`getProductForAdmin`·`getBrandForAdmin`)와 잔액 조회 같은 읽기 전용 GET 경로뿐이다. 쓰기 경로에는 없다 | 검색 |
| 9 | 잠금 순서가 설계와 같다 | SQL 로그(7-3절), 반대 순서 주문 테스트 |
| 금지 항목 | `synchronized`는 main·test 어디에도 없다. `Thread.sleep`은 `ConcurrentRunnerTest`(러너 자체 테스트)에만 있고 동시성 시나리오·잠금 구간에는 없다 | 검색 |

**범위 밖으로 둔 반례**: 브랜드 일괄 삭제와 상품 등록의 동시 실행(삭제 중인 브랜드에 새 상품 등록), 잔액 행이 없는 사용자의 동시 최초 생성.

---
*이 문서는 구현하며 판단이 바뀌면 그때그때 갱신한다 (완성 후 고정하는 문서가 아님).*
