# 커머스 기본 기능 설계 (W2)

> **설계의 의도를 객체의 책임과 실행 가능한 규칙으로 표현한다.**

고객·관리자의 요청을 `HTTP → application → domain → repository·DB → 응답`까지 연결하는 기본 기능(상품·브랜드·좋아요, 포인트·주문, 관리자)의 설계 문서.
확정된 내용만 단정적으로 적고, 구현하며 더 나은 판단이 나오면 이 문서를 고친다.

출처: `docs/note/week2/발제.md`, `docs/note/week2/실습 과제.md`

## week2 범위

week2의 목표는 **기본 API를 간단하게 구현하고 TDD를 적용**하는 것이다.

| 구분 | 내용 |
|---|---|
| 포함 | 과제 3장의 기본 API를 단순하게 구현, 대표 도메인 규칙에 TDD(Red → Green → Refactor), lint·ArchUnit 통과 |
| week3로 이관 | 동시성 제어(잠금·조건부 갱신), 브랜드-상품 일괄 삭제와 중간 실패 rollback, 삭제 시 주문 참조 보존 설계 |

삭제는 가급적 soft delete로 한다 (D-5). 삭제 관련 API는 과제의 "기본 삭제 조건" 동작만 단순하게 맞추고, 일괄 삭제·참조 보존 설계는 week3에서 한다.

---

## 1. 버드뷰

고객과 관리자는 하나의 `commerce-api` 서버를 통해 하나의 MySQL을 사용한다. 두 주체는 **경로 접두사로 구분**한다.

```
고객   ── 상품·브랜드 조회 / 좋아요 / 포인트 / 주문 ──→ /api/v1/**       ─┐
                                                                         ├─→ commerce-api ──→ MySQL
관리자 ── 브랜드·상품 CRUD / 재고 변경 / 주문 조회 ───→ /api-admin/v1/** ─┘   (단일 서버)       (단일 DB)
```

| 구성 요소 | 역할 | 비고 |
|---|---|---|
| 고객 | 상품·브랜드 조회, 좋아요, 포인트 충전·조회, 주문 생성·확정·조회 | 좋아요·포인트·주문은 `X-USER-ID` 헤더로 요청자 식별 (로컬 실습·테스트용, Security 필터가 확인, 2-7·D-19 참고). 상품·브랜드 조회는 식별하지 않음 (D-2) |
| 관리자 | 브랜드·상품 CRUD, 재고 변경, 구매자별 주문 조회 | `/api-admin/**`에 관리자 경계 적용 (`SecurityConfig`의 관리자 체인, Spring Security) |
| commerce-api | 요청 검증·유스케이스 조율·업무 규칙 실행 | 단일 Spring Boot 앱 |
| MySQL | 브랜드·상품·재고·좋아요·포인트·주문 저장 | 트랜잭션 경계는 application의 Facade |

- 같은 상품을 다뤄도 고객의 조회와 관리자의 변경은 **입력·권한·응답 필드가 다른 별개의 계약**이다.
- Redis·Kafka는 인프라에 구성돼 있으나 이번 기본 기능에서는 사용하지 않는다.
- 로컬 실습 전제: `server.address=127.0.0.1`, 공개 배포와 실제 개인정보 사용 없음. 관리자 로그인 같은 인증 수단은 범위 밖이다.

---

## 2. 구조와 의존

### 2-1. 패키지 배치 (layer-first)

`apps/commerce-api/src/main/java/com/loopers/` 아래에 계층별 패키지를 두고, 그 안에 기능(feature)을 배치한다.

```
com.loopers
├── interfaces.api.{feature}        고객 API (Controller, ApiSpec, Dto)
├── interfaces.api.admin.{feature}  관리자 API (클래스에 `Admin` 접두사, D-14)
├── application.{feature}           Facade(고객 `{Feature}Facade`, 관리자 `Admin{Feature}Facade`), Info
├── domain.{feature}                Model(Entity), Service, Repository(인터페이스)
├── infrastructure.{feature}        RepositoryImpl, JpaRepository
├── support.error                   CoreException, ErrorType
└── config                          설정 (SecurityConfig: 관리자·고객 보안 체인, CustomerIdentityFilter: 요청자 식별,
                                    SecurityErrorResponseWriter: 필터 단계 오류 응답)
```

feature: `brand`, `product`, `like`, `order`, `user`. 포인트 API는 `interfaces.api.point`·`application.point`에 두고, 모델은 `domain.user`(User의 `PointBalance`)를 사용한다.

### 2-2. 계층의 역할

| 계층 | 맡는 일 | 현재 코드의 예 (user·point) | 직접 의존하지 않을 것 |
|---|---|---|---|
| **interfaces** | 요청 파싱·입력 변환, 응답 변환, 에러의 HTTP 매핑 | `PointController`, `PointApiSpec`, `PointDto`, `ApiControllerAdvice` | infrastructure |
| **application** | 유스케이스 순서 조율, 트랜잭션 경계(`@Transactional`), 도메인 → `Info` 변환, 조회 결과 조합 | `PointFacade`, `PointInfo` | interfaces, infrastructure |
| **domain** | 상태·업무 규칙(생성자·행동에서 `CoreException`), Repository 인터페이스 | `User`, `UserService`, `UserRepository` | interfaces, application, infrastructure |
| **infrastructure** | domain Repository의 JPA 구현 (위임 어댑터) | `UserRepositoryImpl`, `UserJpaRepository` | 업무 규칙 중복 |

### 2-3. 의존 방향과 ArchUnit 규칙

화살표는 "소스 코드가 의존해도 되는 방향"이다.

```
interfaces ──→ application ──→ domain ←── infrastructure
     └───────────────────────────→ ┘
```

| from \ to | interfaces | application | domain | infrastructure |
|---|---|---|---|---|
| **interfaces** | - | ✅ | ✅ | ❌ |
| **application** | ❌ | - | ✅ | ❌ |
| **domain** | ❌ | ❌ | - | ❌ |
| **infrastructure** | 검사 안 함 | 검사 안 함 | ✅ | - |

❌ 칸은 `ArchitectureTest`(`apps/commerce-api/src/test/java/com/loopers/architecture/ArchitectureTest.java`)가 검사한다.

| 금지 의존 | 검사 메서드 |
|---|---|
| domain → interfaces · application · infrastructure | `domainDoesNotDependOnOuterLayers` |
| application → interfaces · infrastructure | `applicationDoesNotDependOnInterfacesOrInfrastructure` |
| interfaces → infrastructure | `interfacesDoesNotDependOnInfrastructure` |

- **interfaces → domain은 허용**한다 (설계 결정 D-1). 다만 응답 모델은 domain Entity가 아닌 `Info`/`Dto`로 변환해 노출하는 것을 관례로 한다.
- **infrastructure → application · interfaces는 ArchUnit 규칙이 없다.** 관례상 하지 않으며, 필요해지면 규칙을 추가한다 (규칙 변경은 확인 후).
- ArchUnit 패턴이 `..domain..`처럼 패키지명 포함 방식이므로, 계층 이름(`domain`, `application`, `interfaces`, `infrastructure`)을 다른 의미의 패키지명에 쓰지 않는다.

### 2-4. DIP: 저장 기술과 업무 규칙의 분리

```
domain.{feature}.{Feature}Repository (인터페이스)   ← 정의: domain
infrastructure.{feature}.{Feature}RepositoryImpl    ← 구현: JpaRepository에 위임
```

- application·domain은 구현체 이름을 모른다. 런타임에는 JPA 구현이 호출되지만 소스 의존은 domain 쪽으로 향한다.
- Repository 메서드의 입출력에 interfaces의 DTO를 쓰지 않는다.

### 2-5. 계층 밖 공통 코드

| 대상 | 위치 | 규칙 |
|---|---|---|
| `CoreException`, `ErrorType` | `support.error` | 모든 계층에서 사용 가능. `ErrorType`은 HTTP 상태를 모른다. message는 사용자가 판단해야 하는 경우 다음 행동을 안내한다 (D-3) |
| ErrorType → HTTP 상태 | `interfaces.api.ErrorTypeHttpStatusMapper` | 이 한 곳에서만 변환. `ErrorType` 추가 시 switch 매핑도 추가(컴파일 에러로 누락 방지) |
| `BaseEntity` | `modules:jpa`의 `com.loopers.domain` | 패키지상 domain으로 검사됨 (domain만 상속) |

### 2-6. 검사로 강제되지 않는 관례

문서·리뷰로 지키는 규칙이다.

- 트랜잭션 경계는 Facade의 `@Transactional` (조회는 `readOnly = true`), 도메인 Service에는 붙이지 않는다.
- 비즈니스 규칙 검증은 Model 생성자·행동 안에서 `CoreException`으로 던진다.
- Controller는 Facade만 호출하고 응답은 `ApiResponse<T>`로 감싼다.
- soft delete 대상 Entity의 조회 쿼리는 `deletedAt IS NULL` 조건을 빠뜨리지 않는다 (D-5).
- 요청 본문의 필수 누락은 Dto의 `@NotNull`과 Controller의 `@Valid`로 조회 전에 `INVALID_INPUT`(400)으로 거절하고, 공백·길이·범위 같은 값 규칙은 도메인에서만 검증한다 (D-15). `@Valid`를 빠뜨리면 NPE(500)가 나므로 필수 누락 테스트를 함께 쓴다.
- 목록 API의 `page`·`size` 규칙은 `support.paging.PageRequests`를, 응답 형식은 `interfaces.api.PageResponse<T>`를 쓴다 (D-16, D-17). 요청 파라미터의 기본값(`DEFAULT_PAGE`·`DEFAULT_SIZE`)과 Swagger 설명 문구(`PAGE_PARAM_DESCRIPTION`·`SIZE_PARAM_DESCRIPTION`)도 `PageRequests`의 상수라, 컨트롤러와 ApiSpec은 값을 직접 쓰지 않고 이 상수를 참조한다.
- 요청자 식별이 필요한 고객 API는 `SecurityConfig.IDENTIFIED_PATHS`에 경로를 등록한다. 등록하지 않으면 식별 없이 열리므로 그런 API마다 "헤더 누락 400" E2E 테스트를 함께 쓴다 (D-19).

### 2-7. 요청자 식별 (로컬 실습용)

자신의 데이터를 다루는 고객 API(좋아요·포인트·주문)는 `X-USER-ID` 헤더로 요청자를 밝힌다. 요청자에 따라 결과가 달라지지 않는 카탈로그 조회(브랜드 상세, 상품 목록·상세)는 식별하지 않는다 (D-2). 식별은 Security 필터 한 곳에서 한다 (D-19).

```bash
curl -H "X-USER-ID: 1" http://localhost:8080/api/v1/points
```

- `config.CustomerIdentityFilter`가 식별이 필요한 경로(`SecurityConfig.IDENTIFIED_PATHS`)에서만 헤더 형식과 사용자 존재(DB)를 확인하고, 통과하면 `userId`를 인증 주체로 둔다. Controller는 `@Requester Long`으로 받아 Facade에 `userId`만 넘긴다. 그 밖의 경로(카탈로그)는 헤더를 읽지도 사용자를 조회하지도 않는다.
- 소유권 확인(내 주문·내 좋아요)은 application에서 한다. 사용자 존재 확인은 필터가 맡으므로 Facade는 다시 조회하지 않는다(엔티티가 필요한 `PointFacade` 제외). 헤더·`HttpServletRequest`는 application 이하로 넘기지 않는다.
- 헤더 값은 위조할 수 있으므로 **로컬 실습·테스트 전용**이다. 운영 인증 방식(JWT 등)은 정하지 않았고 이번 범위 밖이다.
- 사용자 데이터는 fixture로 준비한다 (회원가입 API 없음).
- 관리자 경계는 `/api-admin/**`에 `SecurityFilterChain`을 적용하고 MockMvc의 관리자·일반·식별 없는 요청으로 검증한다 (로그인 기능 없음). 고객 체인(`/api/**`)과 한 클래스(`SecurityConfig`)에서 정의한다.

---

## 3. 도메인 관계

### 3-1. 업무 관계

```
Brand 1 ── N Product
User  1 ── N Like N ── 1 Product
User  1 ── N Order 1 ── N OrderItem ── 상품 식별자·주문 당시 상품명·수량·단가
User  1 ── 포인트 잔액
Order ── 품목 금액 합계 / 포인트 결제액·결제 결과
```

이 선은 **업무 관계**이다. 객체끼리 참조할지(객체 참조), 식별자만 보관할지(id 참조)는 조회와 변경에 필요한 정보를 보고 3-2에서 따로 정한다.

### 3-2. 모델 구성

발제의 기준을 따른다. **Entity**는 식별자와 이어지는 생명주기가 중요한 객체, **VO**는 값의 의미와 동등성이 중요한 객체이다. 모든 값을 VO로 감싸지는 않는다.

| 모델 | 분류 | 담는 것 / 행동 | 근거 |
|---|---|---|---|
| **Brand** | Entity (soft delete) | 이름(앞뒤 공백 제거 후 1~50자, 공백뿐인 문자열 불가), 수정 | 식별자와 생명주기(수정·삭제)가 있다 |
| **Product** | Entity (soft delete) | `brandId`, 이름(1~100자, 공백뿐인 문자열 불가), 가격 `Money`(1원 이상 1,000,000,000원 이하), 재고 `Stock`. `decreaseStock(q)`, `changeStock(n)` | 가격이 바뀌어도 같은 상품이다 |
| **Stock** | **VO** (Product에 내장) | 재고 수량(`int`). `decrease(q)`는 새 `Stock`을 반환. 0 이상, 0 이하 수량·보유량 초과 차감 거절 | 값의 규칙을 한 곳에 모은다. `PointBalance`와 같은 방식으로 일관 |
| **Like** | Entity (**복합키** `(productId, userId)`, `BaseEntity` 미상속) | `LikeId`(`@EmbeddedId`), 좋아요 시각 `likedAt`. hard delete (D-5) | 자체 생명주기(등록·취소)와 독립 조회(내 목록, 상품별 count)가 있다. PK가 곧 유일성이라 안 쓰는 컬럼이 없다 |
| **User** | Entity (최소) | 식별자, `PointBalance`. fixture로 생성. `chargePoint(m)`, `payPoint(m)` | 요청자 존재 확인(없는 사용자 `NOT_FOUND`)과 잔액 보유에 필요 |
| **PointBalance** | **VO** (User에 내장) | 잔액 `Money`. `charge(m)`, `pay(m)`는 새 `PointBalance`를 반환. 잔액 0 허용, 충전액 양수, 합산 범위 초과·잔액 초과 결제 거절 | 값 규칙을 한 곳에 모은다 (D-8) |
| **Money** | **VO** | 0 이상 정수 원(`long`). 연산은 새 값을 반환 | 단가·합계·잔액·결제액이 모두 사용 (D-7) |
| **Order** | Entity (Aggregate root) | `userId`, 상태, 품목 목록, 합계 `Money`, 결제 결과. `confirm(...)` | 상태가 바뀌는 Entity. 품목 중복 금지(D-6)와 상태 전이 규칙을 가진다 |
| **OrderItem** | Entity (Order의 하위, Order를 통해서만 접근) | `productId`, 주문 당시 상품명, 수량, 주문 당시 단가 `Money`, 품목 금액 | 주문 당시 값의 스냅샷. 상품이 바뀌거나 삭제돼도 주문 내역이 유지된다 (D-20). 별도 Repository 없이 Order와 함께 저장 |
| **OrderLine**, **OrderLines** | 값 (저장하지 않는 요청 값) | 고객이 요청한 품목 한 줄(`productId`, 수량)과 그 목록 | 상품을 조회하기 전에 판단할 수 있는 값 규칙(수량 1 이상, 품목 하나 이상, 중복 금지)을 도메인에 모은다 (D-20) |
| **OrderProduct** | 값 (저장하지 않음) | 상품의 이름·가격 | `Order`가 상품 Entity를 알지 않고 스냅샷 원본만 받게 한다 (D-20) |
| **OrderStatus** | enum | `DRAFT`, `CONFIRMED` | 값 목록이므로 별도 클래스가 필요 없다 |
| **PaymentResult** | **VO** (Order에 내장, DRAFT 동안 비어 있음) | 결제액 `Money`, 결제 후 잔액 `Money` (컬럼 `paid_amount`, `balance_after`, 확정 전에는 모두 null이라 값 자체가 null로 읽힌다. 결제 후 잔액 0원은 null이 아니다) | 확정 시점에 함께 생기고 함께 읽히는 값 (D-10, D-21) |

**VO로 만들지 않는 것**: 상품·브랜드 이름(문자열 검증은 Entity에서, 범위는 D-12), 수량(`int`, 양수 검증은 `OrderItem`·`Stock`), 식별자. 의미가 혼동되지 않고 검증 반복이 두 곳 이하이기 때문이다. 반복이 늘면 `Quantity` VO를 재검토한다.

### 3-3. 책임 배치 원칙

| 구분 | 위치 | 예 |
|---|---|---|
| 자기 상태의 규칙 | domain 모델의 생성자·행동 | 재고 차감 검증, 충전액 양수, 주문 상태 전이, 품목 중복 검증 |
| 다른 객체를 조회해야 하는 검증 | application(Facade) | 브랜드 존재 확인, 상품 삭제 여부, 소유권. 요청자 존재는 식별 필터가 확인한다 (D-19) |
| 조회 결과 조합 | application(Facade) → `Info` | 상품 + 브랜드 + 좋아요 수 응답 |

조회 결과 조합을 Product가 아니라 application에 두는 이유: 브랜드 응답이 바뀔 때 Product 모델이 영향을 받지 않고, Product는 자신의 상태와 규칙에 집중한다. 비용은 조합 코드가 application에 필요하다는 점이다.

### 3-4. 참조 방식과 모델 구성도

**Aggregate 사이는 id(`Long`)로 참조한다** (D-9).

```
Brand ◁── Product[brandId] ── Stock(VO), Money(VO)
                ▲
Like[userId, productId] ──▷ User ── PointBalance(VO) ── Money(VO)
                              ▲
Order[userId] ── OrderItem[productId, productName, qty, unitPrice:Money] ── PaymentResult(VO)
```

▲/▷는 id 참조, 선은 내장 또는 소유를 뜻한다.

| 구분 | 방식 | 예 |
|---|---|---|
| Aggregate 사이 | id 참조 | `Product.brandId`, `Like.userId·productId`, `Order.userId`, `OrderItem.productId` |
| Aggregate 안 | 객체 참조 | `Order` → `OrderItem` 컬렉션 |

- Repository는 Aggregate 단위이다: `BrandRepository`, `ProductRepository`, `LikeRepository`, `OrderRepository`, `UserRepository`. `Stock`, `PointBalance`, `OrderItem`, `PaymentResult`는 자체 Repository가 없다.
- domain Service는 `UserService`와 같은 방식으로 Aggregate별 저장 위임과 단순 조율을 맡는다. 여러 Aggregate를 엮는 유스케이스는 Facade가 맡는다.
- 상품 응답(상품 + 브랜드 + 좋아요 수)의 조합은 application이 한다. 목록에서는 `brandIds`를 모아 일괄 조회해 N+1을 피한다.
- 좋아요 수는 Like 행에서 집계한다 (D-12). 상세는 `count`, 목록은 `productIds`로 `group by` 일괄 집계, `likes_desc` 정렬은 Product 쿼리에서 Like 집계를 join해 수행한다. 카운터 컬럼은 두지 않는다.

---

## 4. 대표 흐름

대표 흐름은 **참고용 설계 산출물**이다. 책임 배치, 트랜잭션 경계, 오류가 나는 순서를 구현 전에 확인하고 테스트 케이스를 뽑는 지도로 쓴다. 코드나 테스트로 직접 옮기지 않는다.

표기: `요청 → Controller → Facade(@Transactional) → Service → Model → Repository`. **요청 하나가 트랜잭션 하나**이다.

### 4-1. 포인트 충전 → 주문 생성 → 주문 확정

시나리오: 잔액 0원 → 10,000원 충전 → 상품 A(3,000원) × 1 + 상품 B(2,000원) × 2 = 7,000원 주문 → 확정 → 잔액 3,000원. **세 요청은 각각 별도 트랜잭션**이다.

**① 충전** `POST /api/v1/points/charge`

요청자 존재는 식별 필터가 먼저 확인한다 (D-19). `PointFacade.charge(userId, amount)` → `UserService.get`(잔액을 바꿀 `User` 엔티티 조회) → `user.chargePoint(Money)`(0 이하·표현 범위 초과 `INVALID_INPUT`) → 저장 → 충전 후 잔액을 반환한다.

**② 주문 생성** `POST /api/v1/orders`

요청자 존재는 식별 필터가 먼저 확인하므로(D-19) 주문 생성은 사용자를 다시 조회하지 않는다. `OrderFacade.create(userId, items)` → `OrderLines`로 값 규칙 검사(빈 목록·수량 0 이하·중복 품목 `INVALID_INPUT`, 상품을 조회하기 전) → `ProductService`로 삭제되지 않은 상품 일괄 조회(없는·삭제된 상품이 있으면 `NOT_FOUND`, 메시지에 문제 상품 id를 모두 담음) → 재고 확인(재고가 0이거나 요청 수량보다 적은 상품이 있으면 `OUT_OF_STOCK`, 메시지에 상품명을 모두 담음, 재고는 차감하지 않음) → `Order.create(userId, lines, 상품 이름·가격)`(품목마다 주문 당시 상품명·단가 복사, 합계 계산, 범위 초과 `INVALID_INPUT`) → 저장 → DRAFT 주문을 반환한다. 재고·잔액은 차감하지 않는다(차감은 확정에서, D-20).

**③ 확정** `POST /api/v1/orders/{orderId}/confirm`

| 단계 | 하는 일 | 책임 | 실패 시 errorCode |
|---|---|---|---|
| 1 | 본인 주문 조회 (`userId`로 한정) | OrderService | `NOT_FOUND` |
| 2 | 사용자 조회 (포인트를 차감할 `User` 엔티티. 존재는 식별 필터가 먼저 확인) | UserService | `NOT_FOUND` |
| 3 | 주문이 DRAFT인지 확인 | `Order.ensureConfirmable()` | `ORDER_ALREADY_CONFIRMED` |
| 4 | 품목 상품 일괄 조회, 삭제·없음 확인 | ProductService | `PRODUCT_UNAVAILABLE` |
| 5 | 품목마다 `product.decreaseStock(qty)` | Product → Stock | `OUT_OF_STOCK` |
| 6 | `user.payPoint(order.total)` | User → PointBalance | `INSUFFICIENT_POINT` |
| 7 | `order.confirm(PaymentResult)`로 CONFIRMED 전환 | Order | (상태 재검증) |
| 8 | 변경된 Entity 저장 (변경 감지) | JPA | 예외 시 전체 rollback |

```
Controller → OrderFacade.confirm(userId, orderId)   ← @Transactional 시작
              ├ OrderService  : 본인 주문 조회
              ├ UserService   : 사용자 엔티티 조회 (존재는 식별 필터가 확인)
              ├ Order         : ensureConfirmable()
              ├ ProductService: 상품 일괄 조회 (삭제·없음 확인)
              ├ Product/Stock : decreaseStock(qty) × 품목 수
              ├ User/PointBalance : payPoint(total)
              └ Order         : confirm(PaymentResult(결제액, 결제 후 잔액))
            ← 정상 반환 시 commit, 예외 시 rollback
```

- 오류 우선순위는 단계 번호 순서이다. 3단계를 먼저 두는 이유는 이미 확정된 주문이 재고 부족 오류로 보이지 않게 하기 위해서이다. 7단계의 `confirm`이 상태를 다시 검증하는 것은 불변식 보호용이라 역할이 다르다.
- 트랜잭션 경계는 Facade이다. 5·6단계에서 예외가 나면 앞 단계의 변경이 모두 취소된다 (D-10).
- `PaymentResult`는 결제액과 결제 후 잔액이다. 예: 결제액 7,000원, 결제 후 잔액 3,000원. 결제액은 확정 시점의 현재 가격이 아니라 **주문 합계(주문 당시 단가 스냅샷)**이다 (D-21).
- 4~5단계는 Facade 안에서 "상품이 모두 살아 있는지 확인 → 품목마다 재고 차감"으로 묶여 있다 . 삭제된 상품은 주문 당시 상품명으로 알린다 (D-21).

### 4-2. 관리자 상품 수정 → 고객 상품 상세 조회

두 요청은 별도이다.

1. **관리자** `PUT /api-admin/v1/products/{productId}`: `AdminProductFacade.update` → 상품 조회(삭제·없음 `NOT_FOUND`) → `product.update(이름, 가격)`(브랜드는 바뀌지 않음, 검증 실패 `INVALID_INPUT`) → 저장.
2. **고객** `GET /api/v1/products/{productId}`: `ProductFacade.get`(`readOnly`) → 상품 조회 → `brandId`로 브랜드 조회 → 좋아요 수 집계 → `ProductDetailInfo`로 조합한다. 수정한 가격이 반영된다.

- 상품·브랜드·좋아요 수의 조합은 application이 한다 (D-9). 관리자 상품 응답은 D-16, 고객 상품 응답(브랜드 정보·좋아요 수 포함)은 D-18에서 정했다.
- 상품이 남은 브랜드는 삭제할 수 없으므로 week2에서는 삭제된 브랜드의 상품이 생기지 않는다. 일괄 삭제는 week3에서 다룬다.

### 4-3. 좋아요 등록·취소

- **등록** `POST /api/v1/products/{productId}/likes`: `LikeFacade.like` → 상품 조회(삭제·없음 `NOT_FOUND`) → 이미 있으면 그대로 성공, 없으면 `Like(userId, productId, likedAt)` 저장.
- **취소** `DELETE /api/v1/products/{productId}/likes`: `LikeFacade.unlike` → `delete(userId, productId)`. 행이 없어도 성공한다. 상품을 조회하지 않으므로 **삭제된 상품이어도, 한 번도 존재한 적 없는 `productId`여도 성공한다** (D-17).
- **내 목록** `GET /api/v1/users/{userId}/likes`: path `userId`가 요청자와 다르면 `FORBIDDEN`(조회 없이 비교만으로 판단하므로 사용자 존재 여부를 노출하지 않는다). 같으면 `page`·`size` 형식을 확인하고, 좋아요와 상품을 join한 한 번의 쿼리로 삭제된 상품을 제외하고 최근에 좋아요한 순으로 조회한다 (D-17).
- 요청자 존재 확인(없으면 `NOT_FOUND`)과 헤더 형식 확인은 세 API 모두 앞단의 식별 필터가 먼저 한다 (D-19).
- "타인" 판단 기준: 소유자가 URL에 드러나 있으면 `FORBIDDEN`, 조회로만 알 수 있으면(타인 주문) `NOT_FOUND`이다 (D-11).

---

## 5. 기본 기능 설계 (API 계약)

자신의 데이터를 다루는 고객 API(좋아요·포인트·주문)는 `X-USER-ID` 헤더가 필수다 (2-7). 브랜드 상세와 상품 목록·상세는 요청자를 식별하지 않아 헤더가 필요 없다 (D-2). 식별 오류는 기존 `ErrorType`으로 단순하게 처리하며, 식별이 필요한 경로에서는 Security 필터가 직접 응답한다 (D-2, D-19).

| 상황 | ErrorType |
|---|---|
| 헤더 누락·숫자가 아닌 값 | `INVALID_INPUT` |
| 존재하지 않는 사용자 | `NOT_FOUND` |
| path `userId`가 요청자와 다름 (소유자가 URL에 드러난 타인 리소스) | `FORBIDDEN` |

- 식별 헤더의 누락·형식 오류와 없는 사용자는 `CustomerIdentityFilter`가 같은 `ApiResponse` 형식으로 응답한다 (D-19). `ApiControllerAdvice`는 `MissingRequestHeaderException`을 `INVALID_INPUT`으로, `@Valid` 위반(`MethodArgumentNotValidException`)도 `INVALID_INPUT`으로 매핑한다 (D-15).
- 알려진 한계: 식별 필터는 `CoreException`과 헤더 형식 오류만 공통 `ApiResponse`로 응답한다. 사용자 조회 중 DB 장애 같은 다른 예외는 컨테이너 기본 500으로 나갈 수 있다 (D-19).
- **null 필드는 응답에서 생략된다** (`supports:jackson`의 `NON_NULL` 설정). 예: 확정 전(DRAFT) 주문의 응답에는 `payment`가 `null`이 아니라 키 자체가 없다 (D-22).

**성공 응답 원칙 (D-13)**: 돌려줄 데이터가 없는 성공도 HTTP 200과 `ApiResponse.success()`로 응답한다 (204 No Content를 쓰지 않는다). 아래 표의 "성공 (본문 없음)"은 이 응답을 뜻한다.

**오류 응답 원칙 (D-3)**

1. `errorCode`: 클라이언트가 **동작을 달리해야 하는 경우**마다 별도 코드를 둔다. 같은 코드면 같은 동작이다.
2. `message`: 사용자에게 보여 줄 수 있는 문장이다. 시스템이 처리할 수 없어 사용자가 판단해야 하는 경우에는 **다음 행동을 포함**한다. 예: "재고가 부족합니다. 수량을 줄여 다시 주문해 주세요."
3. HTTP 상태는 큰 분류(400/404/409/500)만 맡고, 세부 분기는 `errorCode`가 맡는다. 변환은 `ErrorTypeHttpStatusMapper` 한 곳에서 한다.
4. 클라이언트 분기가 필요 없는 오류는 범용 코드(`INVALID_INPUT` 등)를 쓰고 메시지로만 설명한다. 코드를 늘리지 않는다.

**주문 오류 계약**

| 상황 | errorCode | HTTP | 클라이언트 동작 | 메시지 방향 |
|---|---|---|---|---|
| 수량 0 이하, 품목 없음, 같은 상품이 중복된 품목 | `INVALID_INPUT` | 400 | 입력 수정 (중복은 병합 후 재요청) | 잘못된 값 설명. 예: "같은 상품이 중복되었습니다. 수량을 합쳐 다시 요청해 주세요." |
| 없는 주문, **타인 주문** | `NOT_FOUND` | 404 | 주문 목록으로 이동 | 존재 여부를 숨김 ("주문을 찾을 수 없습니다.") |
| 주문 생성 시 없는·삭제된 상품 | `NOT_FOUND` | 404 | 해당 상품 제거 후 재시도 | 문제 상품의 id를 요청 순서대로 모두 담음. 예: "존재하지 않는 상품이 포함되어 있습니다. (상품 ID: 3, 5)" |
| 이미 확정된 주문 | `ORDER_ALREADY_CONFIRMED` | 409 | 주문 상세 조회로 전환 | "이미 확정된 주문입니다. 주문 내역에서 결과를 확인해 주세요." |
| 재고 부족 (주문 생성·확정 모두) | `OUT_OF_STOCK` | 409 | 수량 조정 안내 | "재고가 부족합니다. 수량을 줄이거나 다른 상품을 선택해 주세요." 생성에서는 부족한 상품명을 모두 덧붙임. 예: "… (티셔츠, 모자)" |
| 잔액 부족 | `INSUFFICIENT_POINT` | 409 | 충전 화면으로 유도 | "포인트 잔액이 부족합니다. 포인트를 충전한 뒤 다시 시도해 주세요." |
| 확정 시 상품 삭제됨 | `PRODUCT_UNAVAILABLE` | 409 | 주문 폐기·재주문 안내 | "주문할 수 없는 상품입니다. 주문 상품을 확인해 주세요." 삭제된 상품의 주문 당시 이름을 모두 덧붙임. 예: "… (티셔츠, 모자)" |

- 업무 규칙 위반 4개 코드는 모두 409로 매핑한다. 기존 `CONFLICT`는 **현재 상태와 충돌하는 범용 코드**로 두고, 사용처는 메시지를 항상 직접 지정한다 (D-13).
### 5-1. 상품·브랜드·좋아요 (고객)

| 기능 | method · path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|
| 브랜드 상세 | `GET /api/v1/brands/{brandId}` | path `brandId` (요청자를 식별하지 않음, D-2) | 브랜드 정보 (id, 이름) | 없거나 삭제된 브랜드 → `NOT_FOUND`, `brandId` 형식 오류 → `INVALID_INPUT` |
| 상품 목록 | `GET /api/v1/products` | query `brandId`(선택), `sort`(`latest`·`price_asc`·`likes_desc`, 기본 `latest`), `page`(0 이상, 기본 0)·`size`(1~100, 기본 20). 요청자를 식별하지 않음 (D-2) | 상품 목록 (삭제 상품 제외, 동률은 상품 id 내림차순). 항목은 `id`, 이름, 가격, `brand`(`id`, 이름), `likeCount`이며 재고·시각은 담지 않고 `content`·`page`·`size`·`totalElements`·`totalPages`로 응답 (D-18) | 허용 값이 아닌 `sort`, 범위 밖 `page`·`size`, 숫자가 아닌 `brandId` → `INVALID_INPUT`. 존재하지 않거나 삭제된 `brandId`는 오류가 아니라 빈 목록 |
| 상품 상세 | `GET /api/v1/products/{productId}` | path `productId` (요청자를 식별하지 않음, D-2) | 상품 정보 (`id`, 이름, 가격, `brand`(`id`, 이름), `likeCount`. 재고·시각은 담지 않음, D-18) | 없거나 삭제된 상품 → `NOT_FOUND`, `productId` 형식 오류 → `INVALID_INPUT` |
| 좋아요 등록 | `POST /api/v1/products/{productId}/likes` | path `productId`, 요청자 | 성공 (200, 본문 없음). 이미 좋아요한 상품이어도 성공(멱등), 관계는 하나만 유지 | 없거나 삭제된 상품 → `NOT_FOUND` |
| 좋아요 취소 | `DELETE /api/v1/products/{productId}/likes` | path `productId`, 요청자 | 성공 (200, 본문 없음). 관계가 없어도 성공(멱등). 관계는 hard delete (D-5) | 상품을 조회하지 않아 상품 관련 오류 없음 (삭제된 상품이든 존재한 적 없는 `productId`든 성공). 헤더 누락·형식 오류 → `INVALID_INPUT`, 없는 사용자 → `NOT_FOUND` |
| 내 좋아요 목록 | `GET /api/v1/users/{userId}/likes` | path `userId`, 요청자, query `page`(0 이상, 기본 0)·`size`(1~100, 기본 20) | 내 좋아요 상품 목록 (삭제 상품 제외, 최근에 좋아요한 순). 항목은 `id`, `brandId`, 이름, 가격이며 `content`·`page`·`size`·`totalElements`·`totalPages`로 응답 | path `userId`가 헤더의 요청자와 다르면 → `FORBIDDEN`, 잘못된 `page`·`size` → `INVALID_INPUT`, 없는 요청자 → `NOT_FOUND` |

### 5-2. 포인트·주문 (고객)

| 기능 | method · path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|
| 포인트 충전 | `POST /api/v1/points/charge` | body `amount` (양의 정수) | 충전 후 잔액 | 누락·잘못된 타입·소수(`1.5`, `1000.0`)·0 이하·표현 범위 초과 → `INVALID_INPUT` (기존 잔액 유지), 충전 후 잔액이 표현 범위 초과 → `INVALID_INPUT` (기존 잔액 유지) |
| 잔액 조회 | `GET /api/v1/points` | 요청자 | 저장된 잔액 | 헤더 누락 → `INVALID_INPUT`, 없는 사용자 → `NOT_FOUND` |
| 주문 생성 | `POST /api/v1/orders` | body `items`: `[{productId, quantity}]` (요청자 식별 필요) | DRAFT 주문 `{id, status, items: [{productId, productName, quantity, unitPrice, amount}], totalAmount, orderedAt}` (확정 전이라 `payment`는 없음), 상품명·단가는 주문 당시 값, 재고·포인트 차감 없음 (D-20) | `items`·`productId`·`quantity` 누락, 형식 오류(소수·문자열·범위 초과 포함) → `INVALID_INPUT`. 품목 없음·수량 0 이하·같은 상품 중복 → `INVALID_INPUT` (상품 조회 전에 판단, 없는 상품이 섞여 있어도 이 오류가 먼저). 없거나 삭제된 상품 → `NOT_FOUND` (메시지에 상품 id 목록). 재고가 0이거나 요청 수량보다 적은 상품 → `OUT_OF_STOCK` (상품이 모두 존재할 때, 메시지에 상품명 목록, 재고는 차감하지 않음). 합계가 표현 범위 초과 → `INVALID_INPUT`. 헤더 누락 → `INVALID_INPUT`, 없는 사용자 → `NOT_FOUND` |
| 주문 확정 | `POST /api/v1/orders/{orderId}/confirm` | path `orderId` (요청자 식별 필요) | CONFIRMED 주문 `{id, status, items, totalAmount, payment: {paidAmount, balanceAfter}, orderedAt}`, 재고·포인트 차감, 결제액은 주문 합계 (D-21) | 없는·타인 주문 → `NOT_FOUND`, 이미 확정 → `ORDER_ALREADY_CONFIRMED`, 상품 삭제됨 → `PRODUCT_UNAVAILABLE` (메시지에 주문 당시 상품명), 재고 부족 → `OUT_OF_STOCK`, 잔액 부족 → `INSUFFICIENT_POINT`, `orderId` 형식 오류 → `INVALID_INPUT`, 헤더 누락 → `INVALID_INPUT`, 없는 사용자 → `NOT_FOUND`. 오류 우선순위는 4-1 표의 순서이고 실패하면 재고·잔액·주문 상태는 그대로 |
| 내 주문 목록 | `GET /api/v1/orders` | 요청자 (식별 필요), query `page`(0 이상, 기본 0)·`size`(1~100, 기본 20) | 내 주문만 최신순(id 내림차순 고정). 항목은 `id`, `status`, `totalAmount`, `itemCount`, `payment`(확정된 주문만), `orderedAt`이며 품목은 담지 않고 `content`·`page`·`size`·`totalElements`·`totalPages`로 응답 (D-22) | 잘못된 `page`·`size` → `INVALID_INPUT`, 헤더 누락 → `INVALID_INPUT`, 없는 사용자 → `NOT_FOUND` |
| 내 주문 상세 | `GET /api/v1/orders/{orderId}` | path `orderId` (요청자 식별 필요) | `{id, status, items: [{productId, productName, quantity, unitPrice, amount}], totalAmount, payment, orderedAt}`. 생성·확정 응답과 같은 모양, 품목은 주문 당시 값 (D-22) | 없거나 타인 주문 → `NOT_FOUND` (존재 여부를 숨김, D-11), `orderId` 형식 오류·헤더 누락 → `INVALID_INPUT`, 없는 사용자 → `NOT_FOUND` |

### 5-3. 관리자

| 기능 | method · path | 입력 | 성공 | 대표 오류 |
|---|---|---|---|---|
| 브랜드 목록·상세 | `GET /api-admin/v1/brands`, `GET /api-admin/v1/brands/{brandId}` | 목록: query `page`(0 이상, 기본 0)·`size`(1~100, 기본 20), 삭제되지 않은 브랜드를 id 내림차순으로 고정 | 브랜드 정보 (id, 이름, 생성·수정 시각). 목록은 `content`·`page`·`size`·`totalElements`·`totalPages` | 없거나 삭제된 브랜드 → `NOT_FOUND`, 잘못된 `page`·`size` → `INVALID_INPUT` |
| 브랜드 등록 | `POST /api-admin/v1/brands` | body 이름 (1~50자) | 저장된 브랜드 | 유효하지 않은 입력 → `INVALID_INPUT` |
| 브랜드 수정 | `PUT /api-admin/v1/brands/{brandId}` | body 이름 (1~50자) | 수정된 브랜드 | 없거나 삭제된 브랜드 → `NOT_FOUND`, 입력 오류 → `INVALID_INPUT` |
| 브랜드 삭제 | `DELETE /api-admin/v1/brands/{brandId}` | path `brandId` | 성공 (200, 본문 없음) | 삭제되지 않은 상품 연결(재고 0 포함) → `CONFLICT` ("연결된 상품이 있어 브랜드를 삭제할 수 없습니다. 상품을 먼저 삭제해 주세요."), 이미 삭제됐거나 없는 브랜드 → `NOT_FOUND` |
| 상품 목록·상세 | `GET /api-admin/v1/products`, `GET /api-admin/v1/products/{productId}` | 목록: query `page`(0 이상, 기본 0)·`size`(1~100, 기본 20), 삭제되지 않은 상품을 id 내림차순으로 고정 (브랜드 필터 없음) | 상품 정보 (id, brandId, 이름, 가격, 재고, 생성·수정 시각). 목록은 `content`·`page`·`size`·`totalElements`·`totalPages` | 없거나 삭제된 상품 → `NOT_FOUND`, 잘못된 `page`·`size` → `INVALID_INPUT` |
| 상품 등록 | `POST /api-admin/v1/products` | body `brandId`, `name`(1~100자), `price`(1~1,000,000,000), `stock`(0 이상, 초기 재고) | 저장된 상품 (위 형식) | 필수 누락·소수·이름·가격·재고 범위 위반 → `INVALID_INPUT`, 없거나 삭제된 브랜드 참조 → `NOT_FOUND` |
| 상품 수정 | `PUT /api-admin/v1/products/{productId}` | body `name`(1~100자), `price`(1~1,000,000,000) (브랜드·재고는 변경 불가) | 수정된 상품 (위 형식) | 없거나 삭제된 상품 → `NOT_FOUND`, 필수 누락·입력 오류 → `INVALID_INPUT` |
| 상품 삭제 | `DELETE /api-admin/v1/products/{productId}` | path `productId` | 성공 (200, 본문 없음) | 이미 삭제됐거나 없는 상품 → `NOT_FOUND` |
| 재고 변경 | `PUT /api-admin/v1/products/{productId}/stock` | body `stock`(최종 수량, 0 이상) | 변경된 상품 (위 형식) | 누락·음수·소수 → `INVALID_INPUT`, 없거나 삭제된 상품 → `NOT_FOUND` |
| 주문 목록·상세 | `GET /api-admin/v1/orders`, `GET /api-admin/v1/orders/{orderId}` | 목록: query `userId`(선택), `page`(0 이상, 기본 0)·`size`(1~100, 기본 20). `userId`를 주면 그 구매자의 주문만, 없으면 전체를 최신순으로 고정 | 목록 항목은 고객 목록 항목에 구매자 `userId`를 더한 모양(품목 없음). 상세는 구매자와 무관하게 조회하며 `userId`·품목(주문 당시 상품명·단가)·상태·합계·결제 결과·주문 시각 (D-22) | 없는 주문 → `NOT_FOUND`, `orderId`·`userId` 형식 오류와 잘못된 `page`·`size` → `INVALID_INPUT`. 주문이 없거나 존재하지 않는 `userId`는 오류가 아니라 빈 목록 |

- 관리자 API 접근: 관리자 허용, 일반 사용자·식별 없는 요청은 거절(403). 거절 시 DB를 바꾸지 않는다. 거절 본문은 과제 예시의 `sendError(403)`이 아니라 `ApiResponse.fail(FORBIDDEN)` 형식이다 (D-14).
- 삭제 정책(기본 삭제 조건): 삭제되지 않은 상품이 연결된 브랜드의 삭제는 거절(재고 0 포함). 삭제된 브랜드·상품은 고객 조회·새 주문에서 제외하고 수정·재고 변경 대상으로도 사용하지 않는다. 삭제는 soft delete(`BaseEntity.delete()`, `deletedAt`)로 처리한다 (D-5).

### 5-4. 주요 규칙의 기대값

과제에 명시된 기대값이다. 테스트와 연결한다.

| 규칙 | 입력 | 기대 결과 |
|---|---|---|
| 재고 차감 거절 | 재고 5에서 6 차감 | 거절, 재고 5 유지 |
| 재고 차감 | 재고 5에서 2 차감 | 재고 3 |
| 재고 전량 차감 | 재고 5에서 5 차감 | 재고 0 |
| 차감 수량 검증 | 0 이하 수량 | 거절 |
| 잔액 | 잔액 0 | 유효 (허용) |
| 충전 입력 | 충전액 0 이하 | 거절, 기존 잔액 유지 |
| 충전 후 잔액 | 잔액 + 충전액이 표현 범위 초과 | 거절, 기존 잔액 유지 |
| 충전 → 결제 | 잔액 0에서 10,000 충전 후 7,000 결제 | 잔액 3,000, 결제액 7,000 저장 |
| 주문 생성 | 품목 목록으로 생성 | DRAFT, 재고·잔액 차감 없음 |
| 이름 검증 | 공백뿐인 이름 / 브랜드 50자 초과 / 상품 100자 초과 | `INVALID_INPUT`, 저장 없음 |
| 가격 검증 | 가격 0원 / 1,000,000,000원 초과 | `INVALID_INPUT`, 저장 없음 |
| 주문 품목 중복 | 같은 상품이 두 품목으로 들어옴 | `INVALID_INPUT`으로 거절, 주문 저장 없음 |
| 생성 시 재고 확인 | 재고가 0이거나 요청 수량보다 적은 상품이 든 주문 | `OUT_OF_STOCK`로 거절, 주문 저장 없음, 재고 변경 없음 (재고와 수량이 같으면 성공) |
| 주문 확정 | 본인 DRAFT 주문 확정 | 재고·포인트 차감, CONFIRMED, 결제액·결과 저장 |
| 연결 시나리오 | 충전 API → 여러 품목 주문 확정 → 내 주문·잔액 조회 (잔액 0에서 10,000 충전, 7,000 결제) | 잔액 3,000, 결제액 7,000, 주문 CONFIRMED로 조회 |
| 좋아요 중복 등록 | 같은 사용자가 같은 상품에 좋아요 2회 | 둘 다 성공, 관계 1개, 좋아요 수는 1만 반영 |
| 좋아요 없이 취소 | 관계 없는 상품(존재한 적 없는 `productId` 포함)에 취소 | 성공, 좋아요 수 변화 없음 (0 미만 불가) |
| 삭제된 상품의 좋아요 | 삭제된 상품에 새 좋아요 / 자신의 기존 좋아요 취소 | `NOT_FOUND` / 취소 성공 |
| 확정 시 검증 | 상품 삭제됨 / 재고 부족 / 잔액 부족 | 각각 `PRODUCT_UNAVAILABLE` / `OUT_OF_STOCK` / `INSUFFICIENT_POINT`로 거절, 상태·재고·잔액 변경 없음 |

---

## 6. 설계 결정 기록

### D-1. interfaces → domain 직접 의존을 허용한다

- **상황**: 발제 예시의 금지 규칙에 `interfaces → domain`이 없다. Controller가 domain 타입(enum 등)을 쓸 수 있는지 정해야 했다.
- **결정**: 허용한다. 바깥 계층이 안쪽 계층(domain)에 의존하는 것은 레이어드 방향에 어긋나지 않고, 문서와 `ArchitectureTest`를 일치시킨다.
- **비용**: Controller가 domain 변경에 영향받을 수 있다. 이를 줄이기 위해 응답은 `Info`/`Dto`로 변환해 노출하는 관례를 둔다.
- **재검토 조건**: Controller·Dto에서 domain Entity를 직접 노출하는 사례가 생기면 `interfaces ↛ domain` 규칙 추가를 검토한다.

### D-2. 고객 식별은 로컬 실습·테스트용 `X-USER-ID` 헤더로 단순 구현한다

- **상황**: 과제는 로컬 실습용 식별만 요구하고, week2의 목표는 기본 API와 TDD다.
- **결정**: 자신의 데이터를 다루는 고객 API(좋아요·포인트·주문)는 `X-USER-ID` 헤더로 요청자를 식별한다. 오류는 기존 `ErrorType`을 쓴다 (누락·형식 오류 `INVALID_INPUT`, 없는 사용자 `NOT_FOUND`). 헤더 읽기와 사용자 존재 확인은 Security 필터 한 곳에서 한다 (D-19).
- **결정 (카탈로그 조회 예외)**: 요청자에 따라 결과가 달라지지 않는 브랜드 상세와 상품 목록·상세는 헤더를 요구하지 않고 사용자 존재도 확인하지 않는다. 식별 정보가 쓰이지 않는 조회에 헤더를 요구하면 요청마다 사용자 조회만 늘기 때문이다. 과제의 "식별 누락·없는 사용자를 거절하고, 자신의 좋아요·포인트·주문만 다루도록 확인"은 자신의 데이터를 다루는 기능에 해당한다고 읽었다.
- **비용**: 헤더는 위조할 수 있어 로컬 전용이다. 운영 인증을 도입하면 Controller 시그니처와 오류 계약을 바꿔야 한다. 카탈로그 조회에서 식별을 요구하지 않는 해석이 과제의 의도와 다르면 해당 경로를 `SecurityConfig.IDENTIFIED_PATHS`에 등록하고 Controller가 `@Requester`로 받게 하면 된다 (D-19).
- **재검토 조건**: 운영 인증 방식(JWT 등)을 정할 때.

### D-3. 오류는 의미 단위 errorCode와 행동 안내 메시지로 설계한다

- **상황**: 주문 확정 실패(재고 부족, 잔액 부족, 이미 확정 등)를 `CONFLICT` 하나로 두면 클라이언트가 원인을 구분하려고 메시지를 파싱해야 한다.
- **결정**: 클라이언트 동작이 달라지는 경우마다 errorCode를 둔다 (`ORDER_ALREADY_CONFIRMED`, `OUT_OF_STOCK`, `INSUFFICIENT_POINT`, `PRODUCT_UNAVAILABLE`은 409, 소유자가 URL에 드러난 타인 리소스 접근은 `FORBIDDEN` 403). 사용자가 판단해야 하는 경우의 메시지에는 다음 행동을 포함한다. 조회로만 알 수 있는 타인 주문은 존재 여부를 숨기려고 `NOT_FOUND`로 처리한다 (D-11).
- **비용**: 코드가 늘어나 `ErrorType`, 매퍼, 테스트를 함께 추가해야 한다. `CONFLICT`는 현재 상태와 충돌하는 범용 코드이며, 구현 시 기본 메시지를 "요청이 현재 상태와 충돌합니다."로 바꾸고 사용처는 메시지를 직접 지정한다 (D-13).
- **재검토 조건**: 코드가 10개를 넘거나, 클라이언트가 분기하지 않는 코드가 생기면 통합을 검토한다.
- **기각한 대안**: `CONFLICT` 하나 + 메시지로 구분. 문구가 바뀌면 클라이언트가 깨진다.

### D-4. 좋아요 등록·취소는 멱등 성공으로 처리한다

- **상황**: 같은 사용자의 중복 등록이나 없는 관계 취소를 오류로 볼지 정해야 했다. 좋아요 버튼은 더블 클릭·재시도가 흔하다.
- **결정**: 두 경우 모두 성공으로 처리하고 관계는 하나만 유지한다. 클라이언트 동작이 같으므로 별도 errorCode를 두지 않는다 (D-3). 삭제된 상품은 새 등록만 `NOT_FOUND`, 자신의 기존 관계 취소는 허용한다. 타인 `userId`의 목록 조회는 `FORBIDDEN`으로 처리한다 (D-11).
- **비용**: 클라이언트가 중복 요청이었는지 응답으로 알 수 없다. 순차 중복을 판단하려고 존재 확인 조회가 한 번 더 필요하다. `(product_id, user_id)` 복합 PK가 유일성을 보장한다 (좋아요 취소는 hard delete라 재등록 때 충돌하지 않는다, D-5, D-9).
- **재검토 조건**: 클라이언트가 "이미 좋아요함"을 구분해야 하면 별도 errorCode를 검토한다. 동시에 들어온 요청이 PK 제약에 걸리는 경우의 오류 계약은 week3에서 동시성 설계와 함께 정한다.
- **기각한 대안**: 중복 등록을 `CONFLICT`로 처리. 더블 클릭·재시도에서 사용자에게 불필요한 오류가 노출된다.

### D-5. 삭제는 가급적 soft delete로 하고, hard delete는 기준에 맞는 경우로 제한한다

- **상황**: 브랜드·상품은 주문 등이 참조하고, 삭제된 대상을 고객 조회와 새 주문에서 걸러야 한다.
- **결정**: soft delete(`BaseEntity.delete()`, `deletedAt`)를 기본으로 한다. hard delete는 다음 경우로 제한한다.
  1. 이력이 필요 없고 다른 데이터가 참조하지 않는 관계 데이터
  2. 법적으로 파기해야 하는 개인정보
  3. 보존 기간이 지난 임시·로그성 데이터

  이 프로젝트의 예외는 **좋아요 취소**뿐이다.
- **비용**: 조회마다 `deletedAt IS NULL` 조건이 필요하고, 빠뜨리면 삭제된 데이터가 노출된다. 삭제 행이 누적되고, 유니크 제약과 충돌할 수 있다.
- **재검토 조건**: 개인정보 파기 요구가 생기거나 삭제 행 누적이 성능 문제가 될 때. week3에서 브랜드-상품 일괄 삭제·주문 참조 보존과 함께 다시 본다.
- **기각한 대안**: 좋아요도 soft delete로 통일. 유니크 제약이 취소된 행과 충돌해 `deletedAt` 포함 제약과 `restore()` 재등록 처리가 필요한데, 보존할 이력 가치가 없다.

### D-6. 주문의 중복 상품 품목은 거절한다

- **상황**: 주문 생성 요청에 같은 상품이 두 품목으로 들어올 수 있다. 과제는 합산 또는 거절을 허용한다.
- **결정**: `INVALID_INPUT`으로 거절한다. "한 주문 안에서 상품은 한 번만 나온다"는 규칙을 `Order`가 품목 목록을 받는 생성 시점에 지킨다. 품목 수량이 곧 상품별 총수량이므로 재고 확인에 별도 합산이 필요 없다.
- **비용**: 클라이언트가 같은 상품을 병합해서 보내야 한다.
- **재검토 조건**: 클라이언트를 통제할 수 없어 병합 부담이 문제가 되면 합산으로 바꾼다. 그때는 병합 규칙(응답 품목 구성, 단가 기준)을 함께 정한다.
- **기각한 대안**: 서버가 합산. 병합 로직과 응답 구조 변화가 생기고, 중복 전송 같은 클라이언트 버그가 가려진다.

### D-7. 금액은 Money VO로 표현한다

- **상황**: 상품 단가, 포인트 잔액, 주문 합계·결제액이 모두 금액이고, 음수 방지 같은 검증이 여러 곳에 반복될 수 있다.
- **결정**: `Money`를 불변 값 객체로 도입한다. 단위는 `long`(원)이며 1포인트는 1원이다. 연산은 기존 값을 바꾸지 않고 새 `Money`를 반환한다.
  - `Money`가 지키는 것은 **값 하나의 유효성**(0 이상)이다. 0원은 유효하다.
  - **행동의 입력 조건**은 `Money`가 아니라 사용하는 객체가 지킨다. 충전액은 양수여야 한다는 규칙은 `PointBalance.charge()`가, 결제액이 잔액 이하여야 한다는 관계 규칙은 `PointBalance.pay()`가 맡는다. 그래서 잔액 0원은 유효하지만 충전 0원은 거절된다.
- **비용**: JPA 매핑 설정(`@Embeddable` 등)이 필요하다. 재고 수량처럼 금액이 아닌 값에는 쓰지 않는다.
- **재검토 조건**: 통화나 소수 단위가 필요해지면 타입을 바꾼다.
- **기각한 대안**: `long`을 그대로 쓰고 `PointBalance`·`Product` 등에서 각자 검증. 검증이 반복되고 빠뜨릴 위험이 있다.

### D-8. 포인트 잔액은 User에 내장된 PointBalance VO로 둔다

- **상황**: 포인트 잔액을 어디에 두고, User를 Entity로 둘지 정해야 했다. 충전은 잔액을 늘리고 결제는 잔액 이하만 차감하는 규칙이 있다.
- **결정**: `User`(Entity)가 `PointBalance`(VO)를 내장한다. `PointBalance`는 잔액 `Money`를 갖고 `charge(Money)`, `pay(Money)`가 새 `PointBalance`를 반환한다. 충전액이 양수여야 한다는 입력 조건, 합산 범위 초과, 잔액 초과 결제 거절은 `PointBalance`가 지킨다. `User`는 `chargePoint`, `payPoint`로 위임하고 결과로 필드를 교체한다.
- **근거**: 테이블이 `users` 하나이고, 규칙이 값 객체에 모이며, 요청자 존재 확인 때문에 User Entity가 어차피 필요하다.
- **비용**: 포인트가 바뀔 때마다 users 행이 갱신되므로, 나중에 User에 다른 변경 필드가 생기면 같은 행에서 경합할 수 있다. `Money`를 내장한 `@Embedded` 중첩의 컬럼명 지정이 필요하다.
- **재검토 조건**: 포인트 이력·적립·만료 요구가 생기면 별도 Entity로 분리한다. week3에서 users 행 잠금이 문제가 될 때도 재검토한다.
- **기각한 대안**: 별도 `PointBalance` Entity(1:1). 테이블과 조회가 늘고 현재는 이점이 없다.

### D-9. Entity·VO 구성과 Aggregate 사이 id 참조

- **상황**: 도메인 모델의 분류와 참조 방식을 한 번에 정해야 했다.
- **결정**: 3-2의 표대로 정한다. VO는 `Money`, `Stock`, `PointBalance`, `PaymentResult`, Entity는 `Brand`, `Product`, `Like`, `User`, `Order`, `OrderItem`이다. `Like`는 `(productId, userId)` 복합키를 쓰고 `BaseEntity`를 상속하지 않는다. Aggregate 사이는 id로 참조하고, 조회 결과 조합은 application이 한다. `Order`가 소유하는 `OrderItem` 컬렉션은 `LAZY`로 두며, 트랜잭션 밖에서 읽지 못하므로 application이 `Info`로 변환해 내보낸다 (D-20).
- **근거 (id 참조)**:
  1. `@ManyToOne`은 기본이 EAGER라 `Order → User`, `OrderItem → Product → Brand`가 연쇄 로딩된다. LAZY는 설정 누락과 `LazyInitializationException` 위험이 있고, 목록은 브랜드 N+1이 생긴다.
  2. `Product`가 `List<Like>`를 가지면 `getLikes().size()`가 좋아요 행을 전부 로딩한다. 연관관계를 써도 집계 쿼리와 조합 코드는 필요하다.
  3. soft delete(D-5) 필터가 걸린 Brand를 `product.getBrand()`로 따라가면 null이나 `EntityNotFoundException`이 날 수 있다. id 참조는 삭제된 브랜드 취급을 코드가 명시한다.
  4. week3에서 `Product`를 `FOR UPDATE`로 잠글 때 조인된 Brand 행까지 영향을 줄 수 있다 (DB·쿼리에 따라). id 참조는 읽는 행이 분명하다.
  5. `new Like(1L, 10L)`처럼 연관 객체 없이 단위 테스트를 쓸 수 있다.
  6. 응답이 바뀌어도 모델은 바뀌지 않고 조합 코드만 바뀐다.
- **근거 (Like 복합키)**: PK가 곧 유일성이라 대리키, `updatedAt`, `deletedAt` 같은 안 쓰는 컬럼이 없다. 검증 흐름(등록은 존재 확인 후 저장, 취소는 삭제하고 영향 행 0이어도 성공, 재등록은 새 행 INSERT)은 대리키와 같다.
- **비용**: 조합 코드를 직접 써야 하고 목록 조회에서 일괄 조회가 필요하다. `likes_desc` 정렬은 Like 집계 쿼리가 필요하다. `Like`는 `BaseEntity`를 벗어나 `likedAt`을 직접 관리하고, `LikeId`의 `equals`/`hashCode` 보일러플레이트가 필요하다. (W2 당시에는 `product_id` 조건 집계를 위한 보조 인덱스도 필요하다고 보았으나, 실제 기본키가 `(product_id, user_id)`라 기본키가 이미 그 조회를 받친다. D-17 비용의 W4 정정 참고.)
- **재검토 조건**: 수량 검증이 반복되면 `Quantity` VO를 도입한다. 재고 경합이 문제가 되면 `Stock` 분리를 week3에서 검토한다.
- **기각한 대안**: `@ManyToOne` 연관관계, 읽기 전용 단방향 LAZY만 허용하는 절충안(규칙이 섞이고 soft delete 문제가 남음), `Like`의 대리키(`BaseEntity` 상속).

### D-10. 주문 확정은 단일 트랜잭션에서 도메인 행동이 각자 검증하고, 예외 시 전체 rollback한다

- **상황**: 확정은 재고, 포인트, 주문 상태를 함께 바꾸고 중간에 실패할 수 있다. 변경 전에 전체를 먼저 검사할지, 각 행동이 검증하고 실패 시 취소할지 정해야 했다.
- **결정**: Facade의 `@Transactional` 안에서 4-1의 순서로 도메인 행동이 각자 규칙을 검증한다. 예외가 나면 앞 단계의 변경이 모두 취소된다. 오류 우선순위는 4-1 표의 단계 순서이다.
- **비용**: fake Repository 기반 application 테스트에서는 실패 후 메모리 객체가 이미 변경돼 있을 수 있다. 그래서 실패 시 재고·잔액 유지는 실제 DB 통합 테스트에서 flush/clear 후 재조회로 확인한다.
- **재검토 조건**: 메모리에서도 부분 변경을 막아야 하는 요구가 생기거나, week3의 동시성 설계에서 검사 순서와 잠금 순서를 바꿀 때.
- **기각한 대안**: 변경 전에 전체 재고·잔액을 먼저 일괄 검사. 도메인 행동의 검증과 중복되어 규칙이 두 곳에 생긴다.
- **구현 메모**: 확정은 변경 감지로 저장하고 별도 `save`를 부르지 않는다. 롤백은 `@Transactional` 없는 통합 테스트(`OrderFacadeTest`)에서 실패 뒤 재고·잔액·주문 상태를 저장소에서 다시 읽어 확인한다 (재고 차감 후 잔액 부족, 앞 품목 차감 후 뒤 품목 재고 부족 등). 세부 결정은 D-21.

### D-11. 타인 리소스 접근은 소유자가 URL에 드러나는지에 따라 구분한다

- **상황**: 타인의 `userId`로 좋아요 목록을 요청하는 경우와, 타인의 주문을 조회·확정하는 경우를 어떤 오류로 처리할지 정해야 했다.
- **결정**: 소유자가 URL에 드러나 있으면(`/users/{userId}/likes`) `FORBIDDEN`(403), 조회를 해야만 알 수 있으면(타인 주문) `NOT_FOUND`(404)로 처리한다.
- **근거**:
  - `userId ≠ 요청자`는 조회 없이 비교만으로 판단되므로 사용자 존재 여부가 노출되지 않는다. 이때 404를 주면 "그 사용자가 없다"는 거짓 정보가 된다.
  - 타인 주문은 조회가 있어야 판단되므로, 404로 숨겨야 순차 ID로 주문 존재 여부를 열거하지 못한다.
  - 클라이언트 처리도 다르다. 타인 주문은 없는 주문과 같이 목록으로 보내면 되고, 타인 `userId` 호출은 재시도해도 소용없는 호출 오류이다.
- **비용**: `ErrorType.FORBIDDEN`을 추가하고 `ErrorTypeHttpStatusMapper`에 403 매핑과 테스트를 갱신해야 한다. "타인" 처리가 두 가지가 된다.
- **재검토 조건**: 주문도 403으로 통일해야 하는 요구가 생기거나 운영 인증을 도입할 때.
- **기각한 대안**: 모두 `NOT_FOUND`는 목록 요청에서 의미가 부정확하다. 모두 `FORBIDDEN`은 순차 ID로 주문 존재 여부를 알 수 있다.

### D-12. 좋아요 수는 관계에서 집계하고, 이름·가격 범위는 정해진 값으로 검증한다

- **상황**: 상품 목록·상세의 좋아요 수와 `likes_desc` 정렬을 어떻게 구할지, 그리고 이름·가격의 허용 범위를 정해야 했다.
- **결정 (좋아요 수)**: **(W4 대체: 좋아요 수의 출처는 별도 집계 테이블 `product_like_counts`이고 좋아요 TX가 함께 ±1 한다. `product-query-design.md` D-33)** Like 행에서 집계한다. 상세는 `count(*) ... where product_id = ?`(기본키 `(product_id, user_id)`의 접두), 목록은 페이지의 `productIds`로 `group by product_id`를 일괄 집계한다. `likes_desc` 정렬은 Product 쿼리에서 Like 집계를 left join하고 `order by coalesce(cnt, 0) desc, id desc`로 수행한다. 이 쿼리는 Product만 반환해 domain 타입을 유지하고, 표시용 수는 일괄 집계로 따로 조회한다. `Product.likeCount` 같은 카운터는 두지 않는다.
- **결정 (범위)**: 값은 아래와 같다.

  | 대상 | 규칙 | 위반 시 |
  |---|---|---|
  | Brand 이름 | 앞뒤 공백 제거 후 1~50자, 공백뿐인 문자열 불가 | `INVALID_INPUT` |
  | Product 이름 | 앞뒤 공백 제거 후 1~100자, 공백뿐인 문자열 불가 | `INVALID_INPUT` |
  | Product 가격 | 1원 이상 1,000,000,000원 이하 (0원 상품 불가) | `INVALID_INPUT` |
  | 재고 | 0 이상 정수(`int`) | `INVALID_INPUT` |
  | 브랜드 이름 중복 | 허용 (유일성 제약 없음) | - |

  브랜드·상품은 설명 같은 부가 필드 없이 최소 필드로 시작한다.
- **근거**: 관계에서 집계하면 관계와 카운터를 일치시킬 책임이 없고, 과제도 관계에서 조회하라고 한다. 가격 상한은 `단가 × 수량` 합계가 `long`을 넘지 않게 하려는 것이다. 이름 중복을 허용하면 `CONFLICT` 처리와 유니크 제약이 필요 없다.
- **비용**: `likes_desc`는 Like 행 수에 비례해 느려질 수 있고, 집계가 정렬용과 표시용으로 두 번 일어난다.
- **재검토 조건**: `likes_desc` 목록이 느려지면 카운터 비정규화를 week3 동시성 설계와 함께 검토한다. → W4에서 충족되어 카운터 테이블로 대체했다(D-33). 이름 유일성이 필요해지면 `CONFLICT`와 유니크 제약을 추가한다.
- **기각한 대안**: 카운터 비정규화(경합과 일치 책임이 생김. W4에서 `products` 컬럼이 아니라 별도 집계 테이블로 도입), 가격 0원 허용(무료 상품 요구가 없음), 브랜드 이름 유일성(과제 요구 없음).

### D-13. 브랜드 삭제 거절은 CONFLICT + 메시지로, 삭제 API 성공은 200 + ApiResponse.success()로 처리한다

- **상황**: 삭제되지 않은 상품이 연결된 브랜드의 삭제 거절에 어떤 코드를 쓸지, 삭제 API의 성공 응답을 어떻게 할지 정해야 했다.
- **결정 (거절)**: `CONFLICT`에 메시지를 직접 지정한다. 예: "연결된 상품이 있어 브랜드를 삭제할 수 없습니다. 상품을 먼저 삭제해 주세요." `CONFLICT`는 현재 상태와 충돌하는 범용 코드로 정의한다.
- **결정 (성공)**: HTTP 200과 `ApiResponse.success()`로 응답한다. 이미 삭제됐거나 없는 대상의 삭제는 `NOT_FOUND`이다. 좋아요 취소는 멱등 성공이지만, 관리자 삭제는 존재하는 대상을 지우는 명시적 행위이고 삭제된 대상은 없는 것으로 취급한다 (D-5).
- **근거**: 관리자 화면이 이 오류에서 프로그램적으로 다르게 동작할 필요가 없고 메시지가 다음 행동을 안내하면 충분하다 (D-3 원칙 4). week3에서 이 거절이 "브랜드와 상품을 함께 삭제"로 바뀌어 사라질 동작이라 전용 코드를 추가하지 않는다. 모든 응답이 `ApiResponse` 형식이므로 204 No Content는 형식과 맞지 않는다.
- **비용**: `CONFLICT`의 기본 메시지가 맞지 않아 기본 메시지 변경과 "항상 메시지를 직접 지정" 규칙이 필요하다. 클라이언트가 원인을 코드가 아니라 메시지로 구분한다.
- **재검토 조건**: week3에서 일괄 삭제로 바뀌면 이 거절을 제거한다. 클라이언트가 이 오류에서 분기해야 하면 전용 코드를 둔다.
- **기각한 대안**: `BRAND_HAS_PRODUCTS` 전용 코드(사라질 동작에 코드를 추가), 204 No Content(`ApiResponse` 형식 불일치).

### D-14. 관리자 API는 `admin` 패키지와 `Admin` 접두사로 나누고, 거절은 ApiResponse 형식의 403으로 응답한다

- **상황**: 첫 관리자 API(브랜드)를 만들면서 패키지·클래스 분리, 관리자 경계의 거절 응답, 고객·관리자 응답 필드, 관리자 목록 조건을 정해야 했다.
- **결정 (분리)**: interfaces는 `interfaces.api.admin.{feature}`에 `Admin{Feature}ApiSpec`·`Admin{Feature}Controller`·`Admin{Feature}Dto`를 둔다. application은 같은 `application.{feature}` 안에서 Facade만 `Admin{Feature}Facade`로 나누고 `admin` 하위 패키지는 두지 않는다. `Info`는 고객·관리자가 함께 쓰고 응답 Dto가 필요한 필드만 고른다.
- **결정 (경계)**: 과제가 제시한 `config.AdminBoundaryConfig`(`/api-admin/**` → `hasRole("ADMIN")`)를 쓰되, 거절(미식별·권한 부족 모두)은 `sendError(403)` 대신 403과 `ApiResponse.fail("FORBIDDEN", ...)` 본문으로 쓴다. 이 체인은 `/api-admin/**`에만 적용된다. 고객 체인(`/api/**`)은 따로 있으며 식별 필터가 맡는다 (D-19).
- **결정 (응답)**: 브랜드 응답은 고객 `{id, name}`, 관리자 `{id, name, createdAt, updatedAt}`이다. 등록·수정 시각은 관리에 필요하지만 고객에게는 불필요한 내부 정보이다.
- **결정 (목록)**: 관리자 브랜드 목록은 `page`(0 이상, 기본 0)·`size`(1~100, 기본 20)를 받고, 삭제되지 않은 브랜드를 id 내림차순으로 고정해 `content`·`page`·`size`·`totalElements`·`totalPages`로 응답한다. 범위를 벗어난 값은 `INVALID_INPUT`이다. 정렬은 Repository 메소드 이름에 고정해 호출하는 쪽의 `Sort`에 영향받지 않는다.
- **근거**: Spring은 빈 이름을 클래스 단순명으로 만들어서, 패키지만 다르고 이름이 같은 `BrandController` 두 개는 기동 시 빈 이름 충돌이 난다. 접두사로 이름도 구분하면 `admin` 경로와 패키지가 대응한다. Security 필터는 Controller보다 앞에서 동작해 `ApiControllerAdvice`가 거절을 처리하지 못하므로, 모든 응답이 `ApiResponse` 형식이라는 원칙을 지키려면 거절 본문을 설정에서 직접 써야 한다. 브랜드가 계속 늘 수 있어 전체 조회는 피한다.
- **비용**: 과제 예시 코드보다 설정이 길다(`ObjectMapper`로 직접 응답을 쓴다). 고객·관리자 Dto가 따로 있어 같은 필드를 두 번 선언한다. `Page`/`Pageable`을 domain Repository 인터페이스와 Facade 반환에 쓴다(계층 규칙에는 어긋나지 않는다). `page`·`size` 검증이 관리자 브랜드 Facade에 있어 상품 목록에서 같은 규칙을 다시 쓸 때 공용으로 뽑을지 판단한다.
- **재검토 조건**: 운영 인증을 도입하면 `AdminBoundaryConfig`(이후 `SecurityConfig`의 관리자 체인으로 통합, D-19)를 교체한다(로그인 기능은 범위 밖). 관리자 기능이 늘어 `admin` 패키지가 커지면 application도 `admin` 하위 패키지로 나누는 것을 검토한다.
- **기각한 대안**: 기능 패키지 안에 고객·관리자 Controller를 함께 두기(관리자 경계가 패키지로 드러나지 않음), 패키지만 다르고 이름이 같은 Controller(빈 이름 충돌, `@RestController("...")`로 이름을 직접 지정해야 함), 과제 예시의 `sendError(403)`(응답 형식 불일치), 목록 전체 조회(페이징 없음).

### D-15. 입력 검증은 "형식은 Dto, 값 규칙은 도메인"으로 나눈다

- **상황**: 필수 누락 검사가 Dto마다 `required(...)` 호출이라 읽기 어려웠고, 이름 필드만 빠져 있어 이름이 빠진 요청이 존재하지 않는 브랜드·상품을 가리키면 400이 아니라 404가 났다(Facade가 조회를 먼저 한다). 또 Jackson 기본값이 정수 필드의 소수(`1.5`)를 소수점을 버리고 받아들여, 성공 응답을 받아도 입력과 다른 금액·수량이 저장됐다.
- **결정**: (1) 요청 본문의 필수 누락은 Dto의 `@NotNull`과 Controller의 `@Valid`로 조회 전에 `INVALID_INPUT`(400)으로 거절한다. `ApiControllerAdvice`가 `MethodArgumentNotValidException`을 `INVALID_INPUT`으로 바꾸고, `@NotNull` 위반 메시지는 "`{field}` 는 필수입니다."이다. (2) 공백뿐·길이·범위 같은 값 규칙은 도메인(`Brand`, `Product`, `Money`, `Stock`)에만 둔다. Dto에는 `@NotBlank`·`@Size`·`@Min`을 쓰지 않고, 도메인의 `null` 방어는 안전망으로 남긴다. (3) `supports:jackson`에서 `ACCEPT_FLOAT_AS_INT`를 꺼서, 정수 필드에 소수(`1.5`, `89000.9`, `1000.0` 포함)가 오면 400으로 거절한다. (4) 본문을 읽지 못한 오류의 메시지는 문제 필드의 위치를 담는다. 배열 원소는 `items[1].quantity`처럼 인덱스로 표기하고, `int`·`long` 범위를 넘는 값은 범위 초과로 안내한다(`ApiControllerAdvice`, `RequestBodyErrorPaths`).
- **근거**: 선언부에서 필수 필드가 바로 읽히고 Swagger 스키마에도 필수로 표시된다. 값 규칙이 한 곳에만 있어 Dto와 도메인이 어긋나지 않는다. 다른 객체를 조회해야 하는 검증(3-3)은 형식 오류보다 뒤에 두는 것이 일관된 순서이다. 소수를 버리면 성공 응답과 저장값이 달라 금액 오류가 조용히 생긴다.
- **비용**: Controller에서 `@Valid`를 빠뜨리면 컴파일러가 못 잡고 `Long`을 풀 때 NPE(500)가 난다(필수 누락 테스트가 잡는다). `supports:jackson`은 `commerce-batch`·`commerce-streamer`와 공용이라 두 앱의 요청 파싱도 소수를 거절한다. `1000.0`처럼 값이 정수여도 소수 표기이면 거절한다. 공백뿐인 이름이 존재하지 않는 브랜드를 가리키면 값 규칙보다 존재 확인이 먼저라 404가 난다.
- **재검토 조건**: 필수 외 형식 제약(`@Pattern` 등)이 필요해지면 도메인 규칙과 중복되지 않는지 먼저 본다. batch·streamer에서 소수 표기를 받아야 하는 입력이 생기면 모듈별 설정으로 나눈다.
- **기각한 대안**: `required(...)` 메소드 호출(가독성), Dto에 `@NotBlank`·`@Size`·`@Min`까지 사용(같은 규칙이 두 곳에 생김), 소수 허용 유지(조용한 데이터 변형).

### D-16. 관리자 상품 응답·목록은 브랜드와 같은 규칙으로 두고, 브랜드 삭제 시 상품 확인은 Facade가 한다

- **상황**: 관리자 상품 API의 응답 필드·목록 조건과, 브랜드 삭제 거절에서 상품 연결 여부를 어디서 확인할지 정해야 했다.
- **결정 (응답·목록)**: 관리자 상품 응답은 `{id, brandId, name, price, stock, createdAt, updatedAt}`이고 등록·수정·상세·목록·재고 변경이 같은 형식을 쓴다. 브랜드 이름은 담지 않는다(id 참조, D-9). 재고 변경은 `PUT /{productId}/stock`에 `{stock}`(최종 수량)을 보내고 변경된 상품 전체를 응답한다. 목록은 브랜드 필터 없이 `page`·`size` 규칙과 id 내림차순을 쓴다(D-14). `page`·`size` 검증은 `support.paging.PageRequests`로 공용화했다.
- **결정 (고객과의 관계)**: 과제가 고객·관리자 응답 필드의 구분을 요구하므로 구분은 유지하되, `ProductInfo`·Repository 쿼리·`ProductService`·페이징 규칙은 공유하고 관리자 응답 record만 따로 둔다. 고객 상품 응답은 브랜드 정보·좋아요 수를 담아 따로 정한다.
- **결정 (브랜드 삭제)**: `AdminBrandFacade.delete`가 `ProductService.hasActiveProducts`로 삭제되지 않은 상품(재고 0 포함)이 있는지 확인하고, 있으면 `CONFLICT`+메시지로 삭제 전에 거절한다 (D-13). 상품 등록 시 브랜드 존재 확인은 `AdminProductFacade`가 `BrandService`로 한다 (3-3).
- **근거**: 브랜드 이름까지 담으려면 목록마다 브랜드를 일괄 조회하는 조합 코드가 필요하다. 응답을 고객과 완전히 같게 하면 과제의 "필드 구분" 요구와 충돌하고 재고 노출 여부도 정해야 한다. 한 Aggregate가 다른 Aggregate를 조회해야 하는 확인은 Facade의 몫이다.
- **비용**: "삭제되지 않은 상품이 있는 브랜드는 삭제할 수 없다"는 규칙이 두 Aggregate에 걸쳐 있어 트랜잭션 수준으로 보장되지 않는다. 확인과 삭제 사이에 다른 요청이 상품을 등록하면 깨질 수 있으며, 이 동시성 경쟁은 week3(동시성 제어, 일괄 삭제)에서 다룬다. 목록에 브랜드 이름이 없어 화면이 브랜드를 따로 조회해야 하고, 브랜드 필터가 없어 상품이 많으면 브랜드별로 보기 어렵다.
- **재검토 조건**: 관리자 화면에서 브랜드별 조회나 브랜드 이름 표시가 필요하면 필터와 조합을 추가한다. week3에서 일괄 삭제로 바뀌면 이 거절을 제거한다.
- **기각한 대안**: 고객·관리자 응답을 완전히 같게 두기(과제 요구와 충돌), 브랜드 이름을 포함한 응답(목록 조합 코드 필요), 상품 존재 확인을 `Brand` 모델에서 하기(다른 Aggregate를 조회하게 됨).

### D-17. 좋아요 취소는 상품을 조회하지 않고 항상 성공시키고, 내 목록은 삭제 상품 제외를 단일 쿼리로 처리한다

- **상황**: 설계 안에서 취소 규칙이 어긋났다. 4-3은 "취소는 상품을 조회하지 않는다"고 했고, 5-1 표는 취소의 대표 오류로 "없는 상품 → `NOT_FOUND`"를 적었다. 또 내 좋아요 목록의 응답 필드·페이징·정렬·삭제된 상품 처리 방식이 정해져 있지 않았다.
- **결정 (취소)**: 취소는 상품을 조회하지 않아, 관계가 없으면 아무것도 하지 않고 200으로 응답한다. 삭제된 상품과 한 번도 존재한 적 없는 `productId`도 같다. 등록은 상품 조회(없거나 삭제됨 `NOT_FOUND`) → 이미 있으면 그대로 성공, 없으면 저장이다. 요청자 존재 확인은 식별 필터가 앞단에서 한다 (D-19).
- **결정 (내 목록)**: 항목은 `id`, `brandId`, 이름, 가격이고(재고·시각 제외), `page`(0 이상, 기본 0)·`size`(1~100, 기본 20)를 받아 `PageResponse`로 응답한다. 정렬은 최근에 좋아요한 순이며 같은 시각이면 상품 id 내림차순으로 고정한다. 삭제된 상품 제외는 좋아요와 상품을 id로 join하는 한 번의 쿼리(`ProductRepository.findAllActiveLikedBy`)에서 처리한다. 검사 순서는 요청자 존재(식별 필터, `NOT_FOUND`) → 소유자 비교(`FORBIDDEN`) → 페이지 형식(`INVALID_INPUT`) → 조회이다 (D-19).
- **근거**: 취소의 목적은 "내 관계를 없애는 것"이라 상품의 상태와 무관하고, 상품을 조회하면 삭제된 상품의 관계를 취소할 방법을 따로 만들어야 한다. 좋아요를 먼저 페이징한 뒤 삭제된 상품을 걸러 내면 페이지가 비고 총 개수가 틀리므로 한 쿼리가 필요하다. 소유자 비교를 먼저 하면 존재하지 않는 `userId`를 보내도 사용자 존재 여부가 드러나지 않는다 (D-11). 형식 오류를 조회 전에 거절하는 기준은 D-15와 같다.
- **비용**: 존재한 적 없는 `productId`로 취소해도 오류를 알려 주지 않아 클라이언트의 잘못된 id를 걸러 내지 못한다. 등록은 존재 확인 후 저장이라 같은 사용자의 동시 중복 등록은 기본키 충돌로 실패할 수 있고, 이 오류 계약은 week3(D-4)에서 정한다. 목록 항목에는 재고·시각·좋아요한 시각이 없으며 고객 상품 응답이 정해지면 통합한다. 복합키 엔티티의 `save`가 `merge`라 새 행에도 조회가 한 번 더 나간다. D-12에서 정한 `product_id` 보조 인덱스(`idx_likes_product_id`)는 고객 상품 조회 구현 때 추가했다 (D-18). **(W4 정정)** 당시에는 기본키가 `(user_id, product_id)`라 `product_id` 조건 집계가 인덱스를 못 탄다고 적었으나 사실이 아니다. 실제 DDL의 기본키는 `(product_id, user_id)`였다(Hibernate 기본 컬럼 정렬 전략이 컬럼 이름 순으로 재배열하므로 선언 `(userId, productId)`와 무관). 그래서 이 보조 인덱스는 기본키 접두와 겹쳤고, W4에서 제거했다(제거 전후 지연·응답 차이 없음). 반대로 `user_id` 조건(내 좋아요 목록)에는 쓸 인덱스가 없어 `user_id` 인덱스(`idx_likes_user_id`)를 추가했다.
- **재검토 조건**: 클라이언트가 잘못된 `productId`를 구분해야 하면 취소에 `NOT_FOUND`를 검토한다(존재한 적 없는 id만 거절). 고객 상품 응답이 정해지면 내 목록 항목을 그에 맞춘다. 좋아요 수가 많아져 집계가 느려지면 D-12의 카운터 비정규화를 week3와 함께 검토한다. → W4에서 카운터 테이블로 대체했다(D-33).
- **기각한 대안**: 취소 시 존재한 적 없는 `productId`를 `NOT_FOUND`로 거절(조회 하나가 늘고 "관계가 없으면 성공"과 규칙이 섞임), 좋아요를 먼저 페이징한 뒤 삭제된 상품을 걸러 내기(짧은 페이지와 잘못된 총 개수), 목록 응답에 재고·시각 포함(고객 상품 응답 확정 전에 노출 범위를 넓히게 됨).

### D-18. 고객 상품 목록·상세는 브랜드 정보와 좋아요 수를 조합해 응답하고, 재고는 노출하지 않는다

- **상황**: 고객 상품 조회의 정렬 동률, 잘못된 입력, 브랜드 필터, 응답 필드, 재고 노출 여부를 정해야 했다.
- **결정 (입력)**: `sort`는 `latest`(기본)·`price_asc`·`likes_desc`이며 소문자와 정확히 일치해야 한다. `page`(0 이상, 기본 0)·`size`(1~100, 기본 20)는 `PageRequests`를 쓴다. 허용 값이 아닌 `sort`, 범위 밖 `page`·`size`, 숫자가 아닌 `brandId`·`productId`는 `INVALID_INPUT`이다. 값이 빈 문자열인 파라미터(`?sort=`, `?size=`)는 Spring이 생략으로 보고 기본값을 쓰며, 그 동작을 그대로 따른다.
- **결정 (정렬)**: 동률은 모두 상품 id 내림차순으로 이어 정렬한다(`latest`는 곧 id 내림차순). `likes_desc`는 상품에 좋아요를 left join하고 `group by`로 집계해 `좋아요 수 내림차순, id 내림차순`으로 정렬하며 좋아요 0개 상품도 포함한다. count 쿼리는 join하지 않는다. `ProductRepository.findAllActive(brandId, sort, pageable)`가 정렬을 구현에서 고정해, 호출하는 쪽의 `Sort`에 영향받지 않는다.
- **결정 (브랜드 필터)**: `brandId`가 없으면 전체, 존재하지 않거나 삭제된 브랜드이면 오류가 아니라 빈 목록(200)이다. 브랜드를 따로 조회하지 않는다.
- **결정 (응답)**: 상세와 목록 항목은 `{id, name, price, brand: {id, name}, likeCount}`이다. 목록은 `PageResponse`로 감싼다. 재고 수량도 품절 여부도 담지 않고 생성·수정 시각도 담지 않는다. 재고는 관리자 응답에만 있으며(D-16), 재고 부족은 주문 생성·확정 때 `OUT_OF_STOCK`으로 알린다. 조합은 application의 `ProductFacade`가 하고 `ProductDetailInfo(ProductInfo, BrandInfo, likeCount)`로 전달한다.
- **결정 (N+1 방지)**: 목록은 상품 페이지(+count), 브랜드 일괄 조회, 좋아요 수 일괄 집계 쿼리로 조합해 항목마다 조회하지 않는다. 좋아요가 없는 상품은 집계에 없으므로 0으로 채운다.
- **결정 (인덱스)**: 좋아요 수 집계는 기본키 `(product_id, user_id)`의 접두로 받는다(W2에서 둔 `likes.product_id` 보조 인덱스 `idx_likes_product_id`는 기본키와 겹쳐 W4에서 제거 — D-17 비용의 W4 정정 참고). 브랜드 필터와 브랜드 삭제 시 상품 확인을 위해 `products.brand_id`(`idx_products_brand_id`)에 보조 인덱스를 둔다.
- **결정 (불변식이 깨졌을 때)**: 상품이 있는 브랜드는 삭제할 수 없으므로 상품의 브랜드는 항상 존재해야 한다(`brand`는 null이 아니다). 브랜드를 찾지 못하면 숨기지 않고 `INTERNAL_ERROR`(500)와 "상품 N 의 브랜드 M 을(를) 찾을 수 없습니다." 메시지로 드러낸다. 브랜드 삭제와 상품 등록이 동시에 일어나는 경쟁(D-16)에서만 생길 수 있다.
- **근거**: 목록 화면은 상품마다 브랜드와 좋아요 수가 필요해 한 번에 내려주는 편이 호출을 줄인다. 정렬마다 동률 기준이 같으면 페이지를 넘길 때 항목이 겹치거나 빠지지 않는다. 존재하지 않는 브랜드 필터를 오류로 보면 목록 화면이 삭제된 브랜드의 링크에서 깨지므로 빈 목록이 더 단순하다. 불변식이 깨진 데이터를 `null`로 흘려보내면 원인을 찾기 어렵다.
- **비용**: 재고를 노출하지 않으므로 품절 상품도 목록에 나오고 주문을 만들 때(`OUT_OF_STOCK`) 알게 된다. `likes_desc`는 조건에 맞는 상품 전체의 좋아요를 집계하므로 데이터가 많아지면 느리고, 그때는 D-12의 카운터 비정규화를 검토한다(→ W4에서 카운터 테이블로 개선, D-33). 깨진 상품이 하나라도 있으면 그 목록 페이지 전체가 500이다. 메시지에 내부 id가 응답에 담긴다(로컬 실습 범위라 허용).
- **재검토 조건**: 품절 표시가 필요하면 `soldOut` 같은 파생 값을 추가한다. week3에서 동시성을 다루면 불변식 위반 500은 사라져야 한다.
- **기각한 대안**: 목록에 브랜드·좋아요 수 없이 id만 내려주기(클라이언트가 항목마다 추가 호출), 존재하지 않는 브랜드 필터를 `NOT_FOUND`로 거절(삭제된 브랜드 링크에서 목록이 깨짐), 재고 수량 노출(관리자 전용 정보를 고객에 열게 됨), 브랜드를 못 찾으면 `brand`를 null로 응답(원인 추적이 어려움).

### D-19. 고객 요청자 식별은 Security 필터 한 곳에서 하고, 식별이 필요한 경로를 목록으로 관리한다

- **상황**: `@RequestHeader("X-USER-ID")`가 Controller 5곳에 반복되고 사용자 존재 확인(`userService.get`)이 Facade 여러 곳에 흩어져 있었다. 카탈로그 조회에서는 식별이 필요 없다(D-2).
- **결정 (필터)**: `config.CustomerIdentityFilter`가 헤더 형식과 사용자 존재(DB)를 확인한다. 헤더 누락과 숫자가 아닌 값은 400 `INVALID_INPUT`, 없는 사용자는 404 `NOT_FOUND`로 직접 응답하고 다음으로 넘기지 않는다. 통과하면 `userId`(`Long`)를 인증 주체로 두고 Controller는 `@Requester Long`으로 받는다. 오류 계약은 기존 그대로 두고 401로 바꾸지 않는다. 필터 단계의 오류는 `ApiControllerAdvice`가 처리하지 못하므로 `SecurityErrorResponseWriter`가 같은 `ApiResponse` 형식으로 쓴다(관리자 403도 이 클래스를 함께 쓴다).
- **결정 (경로 목록)**: 식별이 필요한 경로를 `SecurityConfig.IDENTIFIED_PATHS` 하나로 관리하고 그 경로에서만 필터가 동작한다. 현재 `/api/v1/points/**`, `/api/v1/products/*/likes`, `/api/v1/users/*/likes`, `/api/v1/orders/**`이다(주문 경로는 확정·조회 API도 이 접두사로 이미 포함된다). 그 밖의 경로(카탈로그)는 헤더를 읽지도 사용자를 조회하지도 않는다. 권한 규칙도 같은 목록을 쓰며(`authenticated`, 나머지 `permitAll`) 필터가 이미 거절하므로 안전망이다.
- **결정 (설정 위치)**: `AdminBoundaryConfig`를 `SecurityConfig`로 바꾸고 관리자 체인(`/api-admin/**`, `@Order(1)`)과 고객 체인(`/api/**`, `@Order(2)`)을 한 클래스에서 정의한다. 고객 체인은 CSRF를 끄고 세션을 만들지 않는다(STATELESS). 헤더로 식별해 쿠키 세션이 없고 기존 E2E도 CSRF 토큰 없이 호출하기 때문이다. 관리자 체인은 그대로다. 필터는 빈으로 등록하면 모든 요청의 서블릿 필터로도 등록되므로 `SecurityConfig`가 직접 만들어 고객 체인에만 넣는다. 과제 예시의 `config.AdminBoundaryConfig`와 클래스 이름이 달라진다.
- **결정 (등록 누락 방지)**: 목록에 등록하지 않은 식별 API는 식별 없이 열리고 인증 주체가 익명이 된다. 이때 `@Requester` 파라미터를 만드는 `RequesterArgumentResolver`가 인증 정보가 없거나 주체가 `Long`이 아니면 `INTERNAL_ERROR`(500)와 "요청자 식별 정보가 없습니다. 식별이 필요한 경로로 등록됐는지 확인하세요."로 거절한다. 400·403이나 조용한 성공으로 보이지 않고 설정 실수로 드러나며, 요청자를 받는 모든 컨트롤러(좋아요·포인트·주문)가 같은 보장을 받는다. 식별이 필요한 API마다 "헤더 누락 400" E2E 테스트를 쓰는 규칙도 유지하고 CLAUDE.md에 적었다.
- **결정 (신뢰 경계)**: 요청자 id를 믿는 경계는 이 필터와 `@Requester` 리졸버다. Facade는 필터를 거친 존재하는 사용자의 id가 `null`이 아니라는 전제로 존재를 다시 조회하지도 `null`을 방어하지도 않는다(`LikeFacade`·`OrderFacade`·`PointFacade`의 클래스 주석에 적는다). 엔티티가 필요한 `PointFacade`·`OrderFacade.confirm`은 조회를 유지한다.
- **근거**: 헤더 읽기와 존재 확인을 한 곳에 두면 API마다 반복이 사라지고 오류 계약이 일관된다. 헤더가 오면 어디서든 검증하는 방식은 카탈로그에도 사용자 조회와 헤더 오류가 생기므로, 식별이 필요한 경로를 직접 관리하는 쪽을 택했다.
- **비용**:
  - 포인트·주문 확정 요청은 필터와 Facade에서 사용자를 두 번 조회해 기본키 조회가 1회 늘어난다. `open-in-view: false`라 영속성 컨텍스트가 달라 1차 캐시로 줄지 않는다. 좋아요는 `LikeFacade`의 조회를 옮긴 것이라 쿼리 수가 같다. 캐시는 측정 근거 없는 최적화이고 삭제 반영이 늦어지므로 두지 않는다.
  - 필터가 `config`에서 `domain.user.UserService`에 의존한다(ArchUnit은 `config`를 제한하지 않는다).
  - 검사 순서상 요청자 존재 확인이 항상 맨 앞이다. 존재하지 않는 요청자가 남의 `userId` 목록을 요청하면 `FORBIDDEN`이 아니라 `NOT_FOUND`이고, 잘못된 `page`·`size`도 400보다 404가 먼저다. 경로의 `*`가 형식을 가리지 않아 `/products/abc/likes`도 헤더 확인이 먼저다.
  - 등록을 빠뜨린 경로는 식별 없이 열린다. 요청자 `null` 검증은 HTTP 경계(`RequesterArgumentResolver`)에서만 하므로 경계 밖에서 Facade를 직접 호출하면 보호되지 않는다. 그래서 `null` 테스트는 `RequesterArgumentResolverTest`에 있다.
  - 알려진 한계: 필터는 `CoreException`(없는 사용자)과 `NumberFormatException`(헤더 형식)만 `ApiResponse`로 바꿔 쓴다. 사용자 조회 중 DB 장애 같은 다른 예외는 필터 단계라 `ApiControllerAdvice`가 받지 못하고 컨테이너 기본 500 응답으로 나갈 수 있으며, 이때 본문이 공통 `ApiResponse` 형식이 아니다.
- **재검토 조건**: 운영 인증(JWT 등)을 정할 때는 서명된 토큰을 DB 없이 검증하는 방식으로 이 필터를 대체한다. 식별 경로가 늘어 목록 관리가 번거로워지면 애노테이션 기반 등록을 검토한다. 필터의 비-`CoreException` 예외 처리는 DB 의존을 없애는 운영 인증 도입 때 함께 사라지므로, 그 전에 DB 장애 응답 형식이 문제가 되면 필터에서 `RuntimeException`을 `INTERNAL_ERROR`로 쓰는 것을 검토한다.
- **기각한 대안**: 헤더가 오면 어디서든 검증(카탈로그에도 사용자 조회와 헤더 오류가 생김), 공개 경로를 예외로 두기(사용자가 식별 필요 경로 목록을 직접 관리하는 쪽을 선택), Controller마다 `@RequestHeader`와 `ArgumentResolver`(반복과 존재 확인 분산이 남음), Facade에서 다시 조회(필터와 책임이 겹치고 요청마다 조회가 늘어남), 필터에서 사용자 캐시(측정 근거 없음, 삭제 반영 지연), 요청자를 별도 타입으로 도입(시그니처가 모두 바뀌고 application이 그 타입에 의존), Facade마다 `null` 가드를 호출(한 번 만들었으나 Facade가 늘 때마다 호출을 잊을 수 있고 `PointFacade`처럼 빠진 곳이 이미 있어 경계 한 곳 검증으로 바꿈), 내장 `@AuthenticationPrincipal(errorOnInvalidType = true)`(타입이 맞지 않을 때 `ClassCastException`으로 일반 500이라 원인 메시지가 사라지고 인증 정보가 아예 없으면 `null`이 그대로 들어옴).

### D-20. 주문 품목은 주문 당시 상품명·단가를 복사해 저장하고, 값 규칙은 상품 조회 전에 도메인이 판단한다

- **상황**: 주문 생성에서 품목에 무엇을 저장할지, 값 규칙 위반과 없는 상품 중 어느 오류를 먼저 알릴지, `Order`가 상품을 어떻게 받을지 정해야 했다. 발제의 `OrderItem`은 "상품 식별자·수량·단가"였다.
- **결정 (스냅샷)**: `OrderItem`은 상품 id, **주문 당시 상품명**, 수량, 주문 당시 단가, 품목 금액(단가 × 수량)을 저장하고, 합계는 품목 금액의 합이다. 브랜드명은 저장하지 않는다. 주문은 항상 `DRAFT`로 만들어지며 재고와 포인트를 건드리지 않는다.
- **결정 (모델)**: 요청 한 줄은 `OrderLine(productId, quantity)`, 목록은 `OrderLines`(하나 이상, 수량 1 이상, 같은 상품 중복 금지 D-6)이며 저장하지 않는다. `Order.create(userId, OrderLines, Map<Long, OrderProduct>)`는 상품 Entity가 아니라 이름·가격만 가진 `OrderProduct`를 받아 `domain.order`가 `domain.product`에 의존하지 않는다. 맵에 품목의 상품이 없으면 호출하는 쪽의 오류이므로 `INTERNAL_ERROR`로 거절한다(정상 흐름에서는 Facade가 먼저 404로 거르므로 일어나지 않는 안전망이다).
- **결정 (검사 순서)**: 요청자 확인 → 값 규칙(`OrderLines`, `INVALID_INPUT`) → 상품 일괄 조회와 존재 확인(`NOT_FOUND`) → 재고 확인(`OUT_OF_STOCK`) → 합계 범위(`INVALID_INPUT`). 값 규칙이 먼저라서 잘못된 요청은 DB를 조회하지 않고, 수량 0과 없는 상품이 함께 있으면 404가 아니라 400이다. 조회가 먼저인 관리자 API(D-15의 비용)와 순서가 다르다.
- **결정 (재고 확인)**: 생성 때도 재고가 0이거나 요청 수량보다 적은 상품이 있으면 상품이 모두 존재함을 확인한 뒤 `OUT_OF_STOCK`(409)으로 거절하고, 부족한 상품명을 요청 순서대로 모두 메시지에 담는다. 재고는 확인만 하고 차감하지 않으며(차감은 확정), 재고와 수량이 같으면 성공한다. 처음부터 살 수 없는 주문이 결제 단계까지 가지 않게 하려는 것이다. 생성과 확정 사이에 다른 주문이나 관리자가 재고를 바꿀 수 있어 확정의 차감 시점 확인은 그대로 둔다. 생성의 재고 확인은 안내일 뿐 보장이 아니다(동시성은 week3).
- **결정 (오류 메시지)**: 없거나 삭제된 상품은 요청 순서대로 모든 id를 메시지에 담아 한 번에 빼고 다시 요청할 수 있게 한다.
- **결정 (계층)**: Facade는 `interfaces`가 도메인 타입을 알지 않도록 application의 입력 형식 `OrderItemCommand(productId, quantity)`를 받아 `OrderLine`으로 바꾼다. `Order.items`는 `LAZY`이고 `OrderInfo`로의 변환은 트랜잭션 안에서 한다. `/api/v1/orders/**`를 `IDENTIFIED_PATHS`에 등록해 식별 필터가 적용되며(D-19), Facade는 사용자를 다시 조회하지 않는다.
- **근거**: 단가를 얻으려고 상품을 이미 조회하므로 상품명 저장에 추가 조회가 없다. 단가만 복사하면 주문 내역에 "당시 가격 + 현재 이름"이 섞이고, 삭제된 상품의 이름을 보여 주려면 삭제된 상품을 읽는 조회가 따로 필요하다. 값 규칙을 도메인 타입에 두면 Facade의 `if`로 새어 나가지 않고 `Order.create`가 검증을 건너뛸 수 없다. `Order`가 상품 전체가 아니라 필요한 값만 받으면 Aggregate 사이를 id로 분리한 D-9의 취지가 유지되고 단위 테스트에 상품 Entity가 필요 없다.
- **비용**: 상품명이 품목에 중복 저장되고, 이후 상품명을 고쳐도 기존 주문에는 반영되지 않는다(주문 내역으로는 맞는 동작이다). 타입이 늘었다(`OrderLine`, `OrderLines`, `OrderProduct`, `OrderItemCommand`). 맵 누락 검사가 Facade의 존재 확인과 겹쳐 보이지만 하나는 사용자 입력 오류(404), 하나는 호출 계약 위반(500)이라 의미가 다르다. 주문 목록에서 품목을 지연 로딩하면 N+1이 생기므로 batch fetch와 쿼리 수 테스트로 다룬다 (D-22).
- **재검토 조건**: 브랜드명 등 표시 정보가 더 필요해지면 스냅샷 필드를 늘린다. 주문 생성의 상품 조회가 느려지면 상품 id 인덱스 조회를 점검한다. 결제 수단이나 배송 정보가 생기면 `Order` 필드를 다시 나눈다.
- **기각한 대안**: 상품 id·수량·단가만 저장하고 조회할 때 상품을 읽기(삭제된 상품 조회 필요, 현재 이름과 당시 가격이 섞임), 조회를 먼저 하고 `Order.create`가 값 규칙을 검사하기(잘못된 요청도 DB를 조회하고 없는 상품 404가 먼저 나감), `Order.create`가 `Product`를 직접 받기(Aggregate 의존), Facade가 `OrderLine`을 직접 받기(interfaces가 도메인 타입을 알게 됨), 중복 상품 합산(D-6).

### D-21. 주문 확정은 주문 합계를 결제하고, 결제 결과를 응답에 담으며, 실패하면 전체를 되돌린다

- **상황**: 주문 확정에서 결제액의 기준, 응답에 담을 결제 결과, 삭제된 상품과 재고 부족의 안내, 롤백 검증 방식을 정해야 했다.
- **결정 (결제액)**: 결제액은 확정 시점의 현재 가격이 아니라 **주문 합계**(`order.totalAmount`, 주문 당시 단가 스냅샷)이다. 그 사이 관리자가 가격을 바꿔도 결제액은 주문 당시 합계다.
- **결정 (응답)**: 주문 응답에 `payment: {paidAmount, balanceAfter}` 객체를 둔다. 확정 전(DRAFT) 주문은 결제 결과가 없어 응답에서 `payment` 키가 빠진다(null 필드는 생략하는 JSON 설정, D-22). 생성·확정·조회가 같은 응답 모양을 쓴다.
- **결정 (실패 안내)**: 삭제됐거나 없는 상품은 모두 모아 **주문 당시 상품명**으로 `PRODUCT_UNAVAILABLE` 메시지에 담는다(품목에 이름을 저장해 두었으므로 삭제된 상품도 이름으로 알린다, D-20). 확정의 재고 부족은 품목 순서대로 차감하다 처음 부족한 품목에서 멈추며 `ErrorType` 기본 문구를 쓴다.
- **결정 (순서와 저장)**: 오류 우선순위는 4-1 표의 단계 순서이다. 이미 확정된 주문이 다른 오류로 보이지 않게 확정 가능 여부를 앞에서 확인하고, `Order.confirm` 안의 재검증은 불변식 보호용으로 둔다. 별도 `save` 없이 변경 감지로 주문·재고·잔액을 한 트랜잭션에서 저장한다. 포인트를 차감할 `User` 엔티티가 필요해 사용자를 조회하며 `PointFacade`와 같은 이중 조회가 생긴다 (D-19).
- **근거**: 결제액을 주문 합계로 두면 고객이 주문 화면에서 본 금액과 실제 결제 금액이 같다. 결제 결과를 응답에 같은 모양으로 두면 생성·확정·조회가 하나의 응답 타입을 쓴다. 롤백을 실제 DB에서 재조회로 확인해야 변경 감지 기반 저장의 부분 반영을 놓치지 않는다 (D-10).
- **비용**: 확정 시점에 상품 가격이 달라져도 주문 당시 가격으로 결제되므로 가격 변경이 기존 DRAFT 주문에는 반영되지 않는다. 같은 주문을 동시에 확정하거나 같은 재고를 동시에 차감하는 경쟁은 막지 않아(week3) 이중 결제·재고 초과 차감이 가능하며, 순차 요청에서만 정합성을 보장한다. 확정 전 주문은 `payment` 키가 없어 클라이언트가 없는 경우를 처리해야 한다.
- **재검토 조건**: 결제 수단이 포인트 외로 늘거나 결제 이력이 필요하면 `PaymentResult`를 별도 Entity로 분리한다. week3에서 동시성(잠금·조건부 갱신)을 다룰 때 검사·차감 순서와 잠금 순서를 함께 본다.
- **기각한 대안**: 확정 시점의 현재 가격으로 결제(주문 화면의 금액과 달라짐), 결제 결과를 응답에서 빼고 조회로만 확인(확정 직후 한 번 더 호출해야 함), 결제를 별도 Payment Entity로 분리(결제 수단이 포인트 하나라 현재는 과함), 변경 전에 전체 검사 후 차감(D-10).

### D-22. 주문 조회는 고객 목록을 요약으로, 관리자 목록을 구매자 선택 필터로 두고, 모든 주문 응답에 주문 시각을 담는다

- **상황**: 주문 조회에서 내 주문 목록의 항목과 페이징, 관리자의 구매자별 조건, 응답에 담을 시각, 지연 로딩 품목의 N+1을 정해야 했다.
- **결정 (고객 목록)**: 내 주문만(요청자로 한정, `userId` 파라미터 없음) 최신순(id 내림차순)으로 고정해 조회한다. 항목은 `{id, status, totalAmount, itemCount, payment, orderedAt}`이며 품목은 담지 않는다. `page`(0 이상, 기본 0)·`size`(1~100, 기본 20)는 `PageRequests`로 검증하고 `PageResponse`로 응답한다. 상태 필터는 두지 않는다.
- **결정 (고객 상세)**: `getMine`으로 본인 주문만 조회하고 없는 주문과 타인 주문은 같은 `NOT_FOUND`다 (D-11). 응답은 생성·확정과 같은 주문 응답이다.
- **결정 (관리자)**: 목록은 query `userId`를 선택으로 받는다. 주면 그 구매자의 주문만, 없으면 전체이고, 주문이 없거나 존재하지 않는 `userId`는 오류가 아니라 빈 목록이다(구매자를 따로 조회하지 않는다, D-18의 `brandId`와 같은 방식). 항목은 고객 목록 항목에 `userId`를 더한 모양이다. 상세는 구매자와 무관하게 조회하며 `userId`를 담고 없으면 `NOT_FOUND`다. 경계와 패키지는 D-14를 따른다 (`interfaces.api.admin.order`, `AdminOrderFacade`).
- **결정 (주문 시각)**: 생성 시각(`createdAt`)을 `orderedAt`으로 생성·확정·상세·목록의 고객·관리자 응답 모두에 담는다.
- **결정 (응답 표현)**: `supports:jackson`이 null 필드를 생략하므로(`NON_NULL`) 확정 전 주문은 `payment`가 `null`이 아니라 키가 없다. D-21의 "`payment: null`" 서술은 이 의미로 읽는다.
- **결정 (N+1)**: 품목은 지연 로딩이며 `modules:jpa`의 `default_batch_fetch_size: 100`으로 목록에서 한 번의 `IN` 조회로 읽는다. 목록은 "주문 페이지 + 총 개수 + 품목 일괄 조회"로 주문 수와 무관하게 **정확히 SQL 3개**이며, `itemCount`는 이 컬렉션의 크기다. `OrderListQueryCountTest`가 Hibernate 통계로 주문 3건·8건에서 3개임을 고정하고, `default_batch_fetch_size`를 1로 덮으면 주문 4건에서 6개가 되어 실패하는 것을 확인했다.
- **근거**: 목록에 품목까지 담으면 응답이 커지고 컬렉션 fetch join은 페이징과 함께 쓸 수 없다. 요약과 상세로 나누면 목록은 가볍고 상세는 한 번에 전체를 본다. `userId` 선택 필터는 관리자가 전체 현황과 구매자별 조회를 한 API로 하게 한다. 주문 내역은 시각이 있어야 구분된다.
- **비용**: 목록에서 품목을 보려면 상세를 추가로 호출해야 한다. 정렬은 최신순 하나이고 상태·기간 필터가 없다. 관리자의 전체 조회는 주문이 많아지면 총 개수 쿼리 비용이 커진다. 존재하지 않는 `userId`(오타 등)가 오류가 아니라 빈 목록으로 보인다. batch fetch 설정은 모듈 공용이라 다른 지연 컬렉션에도 적용된다.
- **재검토 조건**: 상태·기간 필터나 다른 정렬이 필요해질 때, 주문 수가 많아 총 개수 쿼리가 느려질 때, 목록 화면에서 품목을 바로 보여야 할 때.
- **기각한 대안**: 목록에 품목까지 포함(응답이 크고 컬렉션 fetch join은 페이징 불가), 관리자 `userId` 필수(전체 현황을 볼 수 없음), 존재하지 않는 `userId`를 `NOT_FOUND`로 거절(사용자 조회가 늘고 D-18의 `brandId` 방식과 어긋남), 주문 시각을 관리자 응답에만 담기(고객이 주문 내역을 구분하지 못함), 상태 필터 도입(과제 범위 밖).
