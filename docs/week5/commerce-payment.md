# Week 5 — 커머스 설계 (외부 PG 결제 연동)

이 문서는 `docs/week4/commerce-read.md`를 복사해 5주차 과제(외부 PG 결제 연동)를 반영한 현재 설계 기준이다. 4주차 문서는 제출 당시 기록으로 그대로 둔다. 1~20절은 4주차 설계를 그대로 이어받았고, 21절부터 5주차 내용을 추가한다.

> **표시 규칙** — `[확정]`: 승인 없이 변경하지 않는 합의. `[임시]`: 연속 구현 중 사용자 승인으로 정한 현재 기준이며, 변경은 제안·승인 후 이 문서와 구현·테스트에 함께 반영한다. 하위 절은 상위 절의 표시를 따르며, 하위 절에 별도 표시가 있으면 그 표시를 따른다. 구체적인 HTTP 계약과 잠금 전략은 6절의 구현 정책과 8~13절의 3주차 설계와 15~20절의 4주차 설계와 21절부터의 5주차 설계를 따른다. 작업 방식은 `AGENTS.md`를 따른다.

## 1. 버드뷰 [확정]

고객과 관리자는 하나의 API 서버와 DB를 사용한다. 고객은 상품 조회·좋아요·포인트·주문을 요청하고, 관리자는 브랜드·상품·재고를 관리하고 주문을 조회한다.

```mermaid
flowchart LR
    Customer[고객] -->|HTTP 요청| CustomerController
    Admin[관리자] -->|HTTP 요청| Security
    subgraph API[commerce-api 단일 API 서버]
        CustomerController["고객 Controller<br/>인바운드 어댑터"]
        Security["Spring Security<br/>ADMIN 접근 제어"]
        AdminController["관리자 Controller<br/>인바운드 어댑터"]
        Security -->|접근 허용| AdminController
        App["도메인별 ApplicationService<br/>입력 포트 역할의 공개 메서드<br/>조회·변경·소유권·트랜잭션·결과 조합"]
        Domain["도메인별 Entity·VO<br/>Brand · Product · Like · Point · Order<br/>업무 규칙·상태 변경"]
        Repo["Repository 포트<br/>Brand · Product · Like · Point · Order<br/>조회·저장·집계"]
        Adapter["도메인별 PersistenceAdapter<br/>아웃바운드 어댑터<br/>모델 변환·저장·조회 계약 구현"]
        Jpa["Spring Data JPA / 조회 쿼리<br/>JPA Entity · JOIN · 집계 · 페이지"]
        CustomerController --> App
        AdminController --> App
        App -->|규칙·행동 호출| Domain
        Domain -. 행동 결과 .-> App
        App --> Repo
        Repo -->|연결된 구현 호출| Adapter
        Adapter --> Jpa
        Adapter -. 포트 계약에 따른 결과 .-> App
        App -. 응답 데이터 .-> CustomerController
        App -. 응답 데이터 .-> AdminController
    end
    Jpa -->|조회·저장| DB[(단일 DB)]
    DB -. 결과 .-> Jpa
    Jpa -. 결과 .-> Adapter
    CustomerController -. HTTP 응답 .-> Customer
    AdminController -. HTTP 응답 .-> Admin
```

실선은 실행 중 요청·호출, 점선은 결과·응답이다. 반환 경로는 일부 축약했다. application이 도메인 행동과 포트 호출을 조율하며, domain은 DB를 직접 호출하지 않는다. 도메인별 Repository와 어댑터가 조회·저장을 함께 처리하며 별도 UseCase·Facade 계층은 두지 않는다.

## 2. 구조와 의존 [확정]

순수 도메인과 JPA 모델을 분리한 헥사고날 구조를 사용한다. **Controller → ApplicationService → Entity·VO / 출력 포트 → JPA 어댑터**를 기본 구성으로 한다.

| 계층 | 역할 | 허용하는 주요 의존 |
| --- | --- | --- |
| interfaces | 고객·관리자 HTTP 입력·응답, 식별 정보 전달, 오류 매핑 | application |
| application | 도메인별 ApplicationService에서 실행 순서·소유권·트랜잭션 조율, 출력 포트 정의 | domain, Spring 트랜잭션 |
| domain | Entity·VO의 상태와 업무 규칙 | Java, 필요한 도메인 타입 |
| infrastructure | 출력 포트 구현, JPA 저장·조회, 도메인 모델 변환 | application의 포트·결과 모델, domain, JPA |

```mermaid
flowchart LR
    subgraph Interfaces[interfaces]
        Controller["고객·관리자 Controller<br/>HTTP DTO"]
    end
    subgraph Application[application]
        Service["도메인별 ApplicationService<br/>공개 메서드가 입력 포트 역할"]
        Ports["출력 포트<br/>도메인별 Repository"]
        Result["입력·조회 결과 모델"]
        Service --> Ports
        Service --> Result
    end
    subgraph DomainLayer[domain]
        Domain["순수 Entity·VO"]
    end
    subgraph Infrastructure[infrastructure]
        Adapter["도메인별 PersistenceAdapter"]
        Jpa["Spring Data JPA · JPA Entity"]
        Adapter --> Jpa
    end
    Controller --> Service
    Controller --> Result
    Service --> Domain
    Ports -->|도메인 타입 사용| Domain
    Ports -->|조회 결과 계약| Result
    Adapter -->|인터페이스 구현| Ports
    Adapter -->|조회 결과 매핑| Result
    Adapter -->|도메인 변환| Domain
```

이 그림은 소스의 의존 방향이다. infrastructure가 application의 출력 포트를 구현한다. application은 infrastructure 구현에, domain은 다른 계층·Spring·JPA·HTTP에 의존하지 않는다. interfaces는 infrastructure를 직접 사용하지 않는다.

### 필요한 구성만 유지

- Controller는 인바운드 어댑터다. 고객·관리자 Controller와 DTO를 구분하되 같은 도메인을 사용한다.
- application은 Brand·Product·Like·Point·Order별 ApplicationService 하나로 시작한다. 공개 메서드가 입력 포트 역할을 하며 별도 UseCase 인터페이스·Facade·Command Handler는 두지 않는다. 입력·반환에 HTTP DTO를 사용하지 않는다.
- Repository는 아웃바운드 포트이고, PersistenceAdapter가 이를 구현해 Spring Data JPA를 사용한다.
- **결제 해석 책임 분리(2026-10-21 사용자 승인):** application의 순수 결과 객체 `PaymentLookupAssessment`가 PG 조회의 건수·보증 수준을 해석한다. 거래의 주문 ID·금액·저장 키 일치는 `PaymentRecord.compareTransaction`이 판단한다. 두 객체는 저장소·Spring·HTTP에 의존하지 않는다. 별도 ApplicationService·Facade는 추가하지 않는다.
- 도메인별 어댑터로 시작한다. ProductPersistenceAdapter처럼 해당 도메인의 Repository를 구현해 조회·저장을 함께 처리한다. 전체 도메인을 통합하거나 메서드마다 어댑터를 나누지 않는다. 단순 모델 변환은 내부 메서드로 처리하고 Mapper 클래스를 일괄 추가하지 않는다.
- 이름·설명까지 모두 VO로 만들거나 단순 규칙을 별도 도메인 서비스로 옮기지 않는다. 기존 경계를 유지하는 내부 리팩터링은 자율적으로 수행하고, 새 계층·입력 인터페이스 도입이나 서비스 책임 경계 변경은 이유와 영향을 설명하고 확인받는다.

| 서비스 | 책임 |
| --- | --- |
| BrandApplicationService | 브랜드 CRUD·조회, 브랜드와 연결 상품의 일괄 논리 삭제 |
| ProductApplicationService | 상품 CRUD·재고 설정·조회 |
| LikeApplicationService | 좋아요 등록·취소·내 목록 |
| PointApplicationService | 충전·잔액 조회 |
| OrderApplicationService | 주문 생성·확정·고객/관리자 조회 |

### 조회·변경 경계

CQRS를 적용하지 않는다. 도메인별 ApplicationService와 Repository가 조회·변경을 함께 처리한다. 별도 조회 전용 포트·서비스를 두지 않는다. 포트는 해당 application 기능의 `port` 패키지에 두며, 도메인 또는 application 타입을 사용한다. JPA Entity·Spring Data `Page`·HTTP DTO는 노출하지 않는다.

고객 상품 목록·상세와 변경 대상은 **ProductRepository**에서 Product로 조회한다. 상품 변경은 Product의 행동을 호출한 뒤 저장한다. 조회 결과 조합은 **ProductApplicationService**에서 수행한다.

1. ProductRepository에서 조건에 맞는 상품 페이지를 조회한다.
2. 페이지의 브랜드 ID 목록으로 BrandRepository에서 브랜드를 일괄 조회한다.
3. 페이지의 상품 ID 목록으로 ProductRepository에서 좋아요 수(`products.like_count`)를 일괄 조회한다(18절).
4. application 결과 DTO로 조합하고 Controller가 HTTP 응답으로 변환한다. 상세 조회도 같은 책임 배치를 따른다.

상품마다 추가 조회하지 않는다. 좋아요 순 정렬은 ProductPersistenceAdapter의 쿼리가 `products.like_count`와 인덱스로 정렬한 뒤 페이지 처리하며 Product 목록을 반환한다(17·18절). 페이지를 가져온 뒤 application에서 재정렬하지 않는다. SQL의 테이블 참조는 애그리거트 간 객체 연관관계를 뜻하지 않는다.

Product에 응답 조합용 브랜드명·좋아요 수를 추가하지 않고 결과 DTO로 상태를 변경하지 않는다. `like_count`는 DB 열로만 존재하며 Product 도메인 모델에 두지 않는다(18절). 브랜드 응답 필드가 바뀌면 브랜드 조회·application 결과 조합·HTTP DTO 등 관련 부분을 수정하며 Product의 재고 규칙은 유지한다. 별도 조회 포트가 줄어드는 대신 결과 조합 코드와 DB 조회 횟수는 늘어날 수 있다.

### 저장과 복원

도메인을 영속 기술에서 분리하기 위해 JPA 모델을 별도로 둔다. 그 대신 명시적 저장과 모델 변환이 필요하며, 필드 변경 시 도메인·영속 모델·매핑을 함께 수정하는 비용을 감수한다. 도메인 객체에는 더티 체킹이 적용되지 않지만 어댑터 내부의 JPA 관리 엔티티에는 적용할 수 있다.

도메인 객체는 JPA 관리 객체가 아니므로 행동 호출 후 Repository로 명시적으로 저장한다. 복원은 저장된 ID·상태·금액을 되살리는 작업이며 신규 생성·충전·확정 행동을 다시 실행하지 않는다. DRAFT 확정 시 가격 재산정 여부는 복원과 별개로 결정한다.

### AI와 설계 다듬기

- **상황:** 초안의 상품 조회 전용 포트를 바탕으로 AI와 상품 규칙·조회 조합의 책임 중복을 검토하고, 조회 전용 포트와 UseCase·Facade의 필요성을 비교했다.
- **대안:** 역할별 분리는 조회 최적화와 계약 명시에 유리하지만 관리할 구조가 늘고, 통합은 단순하지만 결과 조합 코드와 DB 호출이 증가할 수 있다.
- **선택:** **헥사고날의 경계는 유지하면서 과제에 불필요한 추상화를 줄이기 위해** CQRS와 별도 UseCase·Facade를 두지 않는다. ApplicationService의 공개 메서드는 입력 포트, Repository는 출력 포트로 사용한다. 다섯 도메인과 도메인별 Entity·VO를 유지하고 조회 결과는 application에서 조합한다.
- **재검토:** 실제 조회 성능 문제나 결과 조합의 복잡도가 커지면 전용 조회 구조를 검토한다.

## 3. 도메인 관계 [확정]

Brand·Product·Like·Point·Order의 도메인과 Entity·VO 역할은 유지한다. Entity·VO는 각 도메인 패키지에 함께 두며 별도의 공통 `entity`·`vo` 패키지로 나누지 않는다.

| 패키지 | 소속 타입 |
| --- | --- |
| domain.brand | Brand, BrandId |
| domain.product | Product, ProductId, Stock |
| domain.like | Like |
| domain.point | Point |
| domain.order | Order, OrderItem, Quantity |

공유 금액 VO는 `domain.common.Money`에 둔다. long의 0 이상 범위를 사용하고 연산 범위 초과를 거절한다.

### 애그리거트 사이의 관계

각 상자는 독립 애그리거트 루트다. User는 실습 사용자 식별 대상이며, 여기서 신규 애그리거트 설계를 추가하지 않는다. 화살표는 호출 순서가 아니라 **ID를 보관하는 쪽 → 참조 대상**이다.

```mermaid
flowchart LR
    Brand["Brand<br/>브랜드"]
    Product["Product<br/>상품"]
    Like["Like<br/>사용자–상품 관계"]
    User["User<br/>실습 사용자"]
    Order["Order<br/>주문"]
    Point["Point<br/>사용자별 잔액"]

    Product -->|BrandId| Brand
    Like -->|ProductId| Product
    Like -->|userId| User
    Order -->|구매자 ID| User
    Point -->|userId로 식별| User
    Order -->|내부 OrderItem의 ProductId| Product
```

Order와 Product의 연결은 **OrderItem이 상품 ID를 보관한다는 뜻**이며, Order에 Product 객체나 별도 상품 ID 필드를 추가한다는 뜻이 아니다. ID 참조는 JPA 객체 연관관계를 강제하지 않는다.

### 애그리거트 내부의 책임

아래 상자 묶음은 각각 하나의 애그리거트다. 실선은 루트가 관리하는 내부 구성이다. Brand와 Like는 위 관계도에 표시하고, 재고·잔액·주문 품목의 내부 구성만 펼쳤다.

```mermaid
flowchart TB
    subgraph ProductAggregate["Product 애그리거트"]
        direction TB
        ProductRoot["Product · 루트<br/>가격: Money"]
        Stock["Stock · 불변 VO<br/>재고량·차감 가능 여부"]
        ProductRoot ---|재고 변경 관리| Stock
    end

    subgraph PointAggregate["Point 애그리거트"]
        direction TB
        PointRoot["Point · 루트<br/>충전·차감"]
        Balance["잔액: Money · 불변 VO"]
        PointRoot ---|잔액 변경 관리| Balance
    end

    subgraph OrderAggregate["Order 애그리거트"]
        direction TB
        OrderRoot["Order · 루트<br/>합계·결제액: Money<br/>상태·결제 결과"]
        Item["OrderItem 목록 · 불변 VO<br/>상품: ProductId<br/>수량: Quantity<br/>저장 단가: Money"]
        OrderRoot ---|품목·합계 관리| Item
    end
```

Money는 각 값의 타입이며, 여러 애그리거트가 하나의 잔액 객체를 공유한다는 뜻이 아니다. Point는 최초 잔액 조회·충전 시 생성하며 주문은 1~100개 품목을 허용한다.

| 관계·객체 | 책임과 경계 |
| --- | --- |
| Brand–Product | 독립 애그리거트다. Product는 BrandId로 소속을 표현한다. application이 상품 생성 시 활성 브랜드를 확인하고, 브랜드 삭제 시 연결된 미삭제 상품을 함께 논리 삭제한다(11절). |
| User–Like–Product | Like는 사용자–상품 관계를 표현한다. DB 유일 제약으로 중복 관계를 막고 좋아요 수는 관계에서 집계한다. |
| Order–OrderItem | Order가 품목·합계·상태·결제 결과를 관리한다. OrderItem은 상품 ID·수량·단가를 담는 불변 VO다. |
| Product–Stock | Product 루트가 재고 변경을 관리한다. Stock VO가 음수 재고와 재고 부족을 거절한다. |
| User–Point | Point는 사용자별 잔액을 관리하며 충전·차감을 수행한다. 잔액은 Money VO로 표현한다. |

Brand·Product·Like·Point·Order는 각각 애그리거트 루트다. 내부 변경은 루트의 행동을 통하며, 다른 애그리거트는 ID로 참조한다. 재고·잔액 연산과 상태 규칙은 Entity·VO가 수행하고 application에 중복 작성하지 않는다. StockRepository·OrderItemRepository는 만들지 않는다.

브랜드·상품은 논리 삭제한다. 브랜드를 삭제하면 재고 0을 포함한 연결된 미삭제 상품도 같은 트랜잭션에서 함께 논리 삭제한다(11절). 상품 수정 시 브랜드를 유지하고, 이미 삭제된 브랜드·상품의 수정과 삭제된 상품의 재고 변경을 거절한다. 기존 주문 품목이 연쇄 삭제되지 않게 한다.

## 4. 대표 흐름 [확정]

### 좋아요 등록·취소

- 등록: 요청자 확인 → 활성 상품 확인 → 사용자–상품 관계 저장 → 관계가 새로 생긴 경우에만 `like_count` +1(18절).
- 취소: 요청자 확인 → 자신의 관계 삭제 → 실제로 삭제된 경우에만 `like_count` −1(18절). 삭제된 상품에 남은 좋아요도 취소할 수 있다.
- 내 목록에서는 삭제 상품을 제외한다.

### 포인트 충전 → 주문 확정

아래는 3주차 기록이다. **5주차부터 주문 확정은 23절로 대체한다:** 트랜잭션 A에서 재고 확보·결제 대기를 저장하고 commit → 트랜잭션 밖 PG 호출 → 트랜잭션 B에서 결과 반영. 포인트는 차감하지 않으며 10,000원 충전 후 7,000원 PG 결제에도 잔액은 10,000원이다.

1. 요청자의 Point를 조회하고 충전 후 잔액을 저장한다.
2. 상품·수량을 확인하고 품목·단가·합계를 가진 DRAFT 주문을 저장한다. 생성 시 차감하지 않는다.
3. 최초 확정은 본인의 DRAFT 주문에 대해서만 수행하며 상품 사용 가능 여부·재고·잔액을 확인한다. 이미 CONFIRMED인 주문의 재요청은 409로 거절한다.
4. OrderApplicationService가 필요한 Repository를 직접 사용하고 Product·Point·Order의 행동을 호출한다. 변경 저장은 하나의 트랜잭션으로 묶고 실패하면 전체를 롤백한다.
5. 저장된 주문과 잔액을 조회한다. 가격 변경이 없는 경우 10,000원 충전 후 7,000원 결제 시 잔액은 3,000원이다.

트랜잭션의 롤백이 동시 요청의 이중 차감까지 해결하지는 않는다. 주문 → 상품 ID 오름차순 → 포인트 순서로 DB 행을 잠그며, 이벤트·분산 처리 계층은 추가하지 않는다.

### 관리자 변경 → 고객 조회

1. Controller 진입 전에 Spring Security가 관리자 접근을 확인한다. Controller가 입력을 변환하고 application이 상품을 조회한다.
2. Product의 정보·재고 변경 행동을 호출하고 DB에 저장한다.
3. 고객 조회 시 ProductApplicationService가 ProductRepository·BrandRepository·LikeRepository로 변경된 상품·브랜드·좋아요 수를 조회하고 결과를 조합한다.
4. 고객용 응답으로 반환한다.

## 5. 기본 API 계약 [확정]

### 공통

- 고객은 `X-USER-ID`로 식별하며 자신의 좋아요·포인트·주문만 다룬다.
- `/api-admin/**`는 ADMIN만 허용하고 일반·미식별 요청은 403으로 거절한다.
- 생성은 201, 조회·변경·삭제는 200이다. 입력 오류 400, 사용자 식별 누락·없는 사용자 401, 관리자 접근 거절 403, 없는 대상·타인 주문 404, 업무 상태 충돌 409, 잠금 대기 시간 초과·교착 503을 사용한다(12절).

### 구현 확정: 브랜드 생성·상세

- 관리자 생성은 `POST /api-admin/v1/brands`, 입력은 `{"name":"브랜드"}`이며 설명은 받지 않는다. 이름은 공백이 아닌 1~100자 문자열이다. 성공은 201과 `data.id`, 잘못된 입력은 400이다.
- 고객 상세는 `GET /api/v1/brands/{brandId}`, 성공은 200과 `data.id`, `data.name`이다. 없거나 삭제된 브랜드는 404다.
- 고객은 `X-USER-ID`로 식별한다. 누락·없는 사용자는 401, 숫자로 해석할 수 없는 ID는 400이다. 실습 사용자 테이블에는 테스트 fixture로 사용자를 준비하며 회원가입 API는 추가하지 않는다.
- Controller 응답은 기존 ApiResponse를 사용한다. 오류 코드는 기존 ErrorType의 HTTP reason phrase를 따른다. 관리자 일반·미식별 요청의 403은 과제 Security 설정의 sendError를 사용하며 ApiResponse 본문을 보장하지 않는다.
- BrandApiIntegrationTest에서 실제 DB·MockMvc로 생성→상세, 입력 오류, 고객 식별, 관리자 접근, 없는·삭제된 대상을 검증한다.

### 고객: 브랜드·상품·좋아요

| Method | Path | 입력 | 성공 결과 | 대표 오류 |
| --- | --- | --- | --- | --- |
| GET | `/api/v1/brands/{brandId}` | 브랜드 ID | 활성 브랜드 상세 | 없음·삭제된 브랜드 |
| GET | `/api/v1/products` | 브랜드 필터·페이지·정렬 | 브랜드·좋아요 수 포함 목록 | 잘못된 페이지·정렬·필터 형식 |
| GET | `/api/v1/products/{productId}` | 상품 ID | 브랜드·좋아요 수 포함 상세 | 없음·삭제된 상품 |
| POST | `/api/v1/products/{productId}/likes` | 요청자·상품 ID | 좋아요 관계 등록 | 없음·삭제된 상품; 중복 등록은 200 |
| DELETE | `/api/v1/products/{productId}/likes` | 요청자·상품 ID | 자신의 관계 취소 | 식별 오류; 없는 관계도 200 |
| GET | `/api/v1/users/{userId}/likes` | 요청자·경로 사용자 ID | 자신의 좋아요 목록, 삭제 상품 제외 | 타인 사용자 ID 접근 |

상품 정렬은 `latest`(생성 시각 내림차순), `price_asc`, `likes_desc`를 지원한다. 동률은 상품 ID 내림차순이며 page=0, size=20(1~100)을 사용한다.

### 고객: 포인트·주문

| Method | Path | 입력 | 성공 결과 | 대표 오류 |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/points/charge` | 요청자·양의 정수 amount | 충전 후 저장된 잔액 | 누락·타입·0 이하·합산 범위 초과 |
| GET | `/api/v1/points` | 요청자 | 자신의 저장된 잔액 | 식별 오류 |
| POST | `/api/v1/orders` | 상품 ID·수량 목록 | 품목·단가·합계·DRAFT 상태 저장 | 상품 없음·삭제됨, 양수가 아닌 수량 |
| POST | `/api/v1/orders/{orderId}/confirm` | 요청자·주문 ID·paymentAttemptKey·카드 정보(23절) | 트랜잭션 A → PG → 결과 반영, 결제 대기 202·종결 200, 포인트 미차감 | 타인 주문 404, 상품 사용 불가·재고 부족, 다른 시도 키 409(23절) |
| GET | `/api/v1/orders` | 요청자·조회 조건 | 내 주문 목록 | 식별·조회 입력 오류 |
| GET | `/api/v1/orders/{orderId}` | 요청자·주문 ID | 내 주문 품목·금액·상태·결제 결과 | 주문 없음·타인 접근 |

### 관리자

| Method | Path | 입력 | 성공 결과 | 대표 오류 |
| --- | --- | --- | --- | --- |
| GET | `/api-admin/v1/brands` | 조회 조건 | 관리자 브랜드 목록 | 조회 입력 오류 |
| POST | `/api-admin/v1/brands` | 브랜드 정보 | 브랜드 생성 | 유효하지 않은 정보 |
| GET | `/api-admin/v1/brands/{brandId}` | 브랜드 ID | 관리자 브랜드 상세 | 대상 없음 |
| PUT | `/api-admin/v1/brands/{brandId}` | 브랜드 ID·수정 정보 | 브랜드 정보 변경 | 대상 없음·삭제됨·입력 오류 |
| DELETE | `/api-admin/v1/brands/{brandId}` | 브랜드 ID | 브랜드와 연결된 미삭제 상품 일괄 논리 삭제 | 대상 없음; 반복 삭제 200 |
| GET | `/api-admin/v1/products` | 조회 조건 | 관리자 상품 목록 | 조회 입력 오류 |
| POST | `/api-admin/v1/products` | 브랜드 ID·상품 정보 | 상품 생성 | 브랜드 없음·삭제됨, 잘못된 상품 정보 |
| GET | `/api-admin/v1/products/{productId}` | 상품 ID | 관리자 상품 상세 | 대상 없음 |
| PUT | `/api-admin/v1/products/{productId}` | 상품 ID·이름·가격 등 허용된 정보 | 브랜드 유지, 상품 정보 변경 | 대상 없음·삭제됨·입력 오류 |
| DELETE | `/api-admin/v1/products/{productId}` | 상품 ID | 상품 논리 삭제 | 대상 없음; 반복 삭제 200 |
| PUT | `/api-admin/v1/products/{productId}/stock` | 상품 ID·0 이상 최종 수량 | 재고 설정 | 음수·타입 오류, 대상 없음·삭제됨 |
| GET | `/api-admin/v1/orders` | 구매자 등 조회 조건 | 구매자별 품목·상태·금액·결제 결과 목록 | 조회 입력 오류 |
| GET | `/api-admin/v1/orders/{orderId}` | 주문 ID | 관리자 주문 상세·결제 결과 | 주문 없음 |

## 6. 검증 범위와 구현 정책 [확정]

| 경계 | 확인할 내용 |
| --- | --- |
| domain | 재고 부족, 충전·잔액 범위, 주문 합계·상태; 대표 Stock 규칙에 TDD |
| application | 소유권, 브랜드 일괄 삭제 범위, 객체 협력 |
| DB | flush/clear 후 저장·재조회, 좋아요 유일 제약, 전체 롤백 |
| HTTP | 실제 구성 요소를 연결한 정상·대표 오류, 관리자 접근, 고객 소유권 |
| ArchUnit·Checkstyle | 계층 의존과 코드 규칙 |

신규·수정 커머스 테스트는 한글 `@DisplayName`을 사용한다. domain의 모든 경계값을 HTTP에서 반복하지 않는다. 기존 Example은 수정 대상에서 제외한다.

연속 구현 중 필요한 최소 정책을 임시 결정하도록 사용자 승인을 받았다. 아래 결정은 현재 구현과 테스트에 적용했으며 향후 변경 시 비용과 영향을 검토한다.

### 연속 구현의 임시 정책 [임시]

사용자 승인에 따라 미정 정책은 필요한 최소 단위로 선택하고 구현·테스트와 함께 기록한다. 상품은 이름 1~100자(공백만 입력 거절), 설명 없음, 가격은 0 이상 long, 재고는 0 이상 int로 정한다. 브랜드·상품 반복 삭제는 성공으로 처리한다.

관리자 브랜드·상품 목록·상세는 삭제 데이터를 포함한다. 목록은 page=0, size=20(1~100)을 기본으로 ID 내림차순 조회한다. 수정·재고 설정·삭제 성공은 200, 생성은 201, 입력 오류 400, 없는 대상 404, 삭제된 대상의 상태 변경은 409다. 상품 생성과 브랜드 삭제는 브랜드 행 잠금으로 직렬화하고, 브랜드 일괄 삭제는 브랜드 → 상품 ID 오름차순으로 잠근다(10절). 상품 변경은 상품 행 잠금을 사용한다.

좋아요 등록·취소는 200이며 중복 등록·없는 관계 취소도 상태 변화 없이 성공한다. 타인의 좋아요 목록 접근은 409로 거절한다. 상품 latest는 생성 시각 내림차순이며 모든 정렬 동률은 ID 내림차순이다. page=0, size=20(1~100), 잘못된 정렬·페이지·필터 형식은 400으로 거절한다. 상품 검색은 DB에서 수행하고 좋아요 수는 `products.like_count`를 읽으며(18절) application이 브랜드 정보를 일괄 조회해 조합한다.

포인트는 최초 잔액 조회·충전 시 0원 행을 생성한다. 충전 입력은 양의 long 정수이며 JSON 문자열·소수·누락·범위 초과는 400이다. 충전과 조회 성공은 200 및 data.balance다. 동일 사용자의 충전·결제는 포인트 행 잠금으로 직렬화한다. 최초 생성은 사용자 ID 유일 키와 DB upsert로 중복을 방지한다.

주문은 1~100개 품목, 양수 int 수량, 중복 상품 거절이다. 생성 시 단가를 고정하고 이후 상품 가격 변경은 반영하지 않는다. 생성은 201/DRAFT, 조회·확정은 200, 재확정과 재고·잔액 부족은 409, 타인 주문·없는 주문은 404로 거절한다. 0원 주문은 허용한다. 확정 잠금 순서는 주문 → 상품 ID 오름차순 → 포인트이며 전체를 한 DB 트랜잭션으로 묶는다. 성공 결제 결과는 SUCCESS, DRAFT는 NOT_PAID·결제액 0이다. 확정 실패는 DRAFT와 기존 잔액·재고를 유지한다.

주문 생성도 상품 ID 오름차순으로 상품 행을 잠근 뒤 단가·삭제 상태를 확인하고 DRAFT를 저장한다. 품목 응답 순서는 상품 ID 오름차순이다. 이는 생성과 상품 변경·삭제 사이의 경쟁을 방지하며 생성 시 차감하지 않는 정책은 유지한다.

## 7. 구현 제약 [확정]

1~6절을 보충하는 계층·모델·업무·접근 경계의 설계 제약이다.

### 계층·오류

- infrastructure는 HTTP 응답·권한·판매 정책을 결정하지 않는다. config는 실행 환경과 객체 연결만 담당하며 업무 규칙을 넣지 않는다.
- domain 내부의 ID 타입 참조는 허용한다. Entity가 Repository를 직접 호출하지 않는다.
- application의 Spring 트랜잭션 사용은 허용하며 필요한 출력 포트를 직접 주입한다. 별도 `XxxUseCase` 인터페이스·Facade·Command/Query Service·Dispatcher를 두지 않는다.
- 도메인 오류는 HTTP 상태 코드에 의존하지 않는다. interfaces가 확정된 오류 계약으로 변환한다. 예상하지 못한 예외를 일괄적으로 입력 오류로 처리하거나 내부 예외 내용을 응답에 노출하지 않는다.

### 애그리거트·모델

| 루트 | 정체성·내부 구성 |
| --- | --- |
| Brand | 브랜드 ID, 브랜드 정보·삭제 상태 |
| Product | 상품 ID, BrandId, Money, Stock, 삭제 상태 |
| Point | 사용자 ID, 잔액 Money |
| Like | 사용자 ID와 상품 ID의 조합으로 식별하는 관계 |
| Order | 주문 ID, 구매자 ID, OrderItem 목록, 합계·상태·결제액·결과 |

- `BrandId`, `ProductId`, `Money`, `Quantity`, `Stock`, `OrderItem`은 불변 VO다. 값 변경은 새 객체로 교체한다.
- 금액에 부동소수점을 사용하지 않고 덧셈·곱셈의 표현 범위 초과를 검사한다.
- 임의 setter나 수정 가능한 내부 컬렉션을 노출하지 않는다.
- Order가 품목과 합계를 관리한다. 외부 입력 합계를 신뢰하지 않고 서버가 조회한 단가와 수량으로 계산한다.
- OrderItem은 독립적인 수정·배송·취소가 없는 VO다. 저장용 JPA 행의 ID 때문에 도메인 Entity로 바꾸지 않는다.
- Brand는 상품 컬렉션을 갖지 않는다. 여러 루트를 함께 변경한다는 이유로 Product·Point를 Order 애그리거트에 포함하지 않는다.
- 목록의 필터·정렬·페이지 처리는 저장소 조회에서 수행한다. 전체 데이터를 읽어 메모리에서 페이지 처리하거나 목록의 각 항목마다 추가 조회하는 구현을 피한다.
- 기본 기능을 위해 이벤트·분산 트랜잭션·별도 조회 DB를 도입하지 않는다.

### 업무 규칙 보충

- 좋아요 수를 위한 별도 카운터를 기본 설계로 도입하지 않는다. 좋아요 취소 시 활성 상품 존재를 필수 조건으로 만들지 않는다.
- 1포인트는 1원이다. 잔액 0은 허용하며 충전·결제 실패 시 기존 잔액을 유지한다.
- 주문 확정 시 각 상품별 수량으로 재고를 확인한다.
- 삭제 상품은 새 주문·주문 확정·새 좋아요의 대상으로 사용하지 않는다.

### 고객·관리자·실행 환경

- 관리자 허용, 일반 사용자·미식별 요청 거절을 검증한다. 경로·클래스 이름만으로 권한을 보장한다고 가정하지 않는다.
- 입력 본문의 사용자 ID를 신뢰해 소유자를 결정하지 않는다.
- 고객 주문 조회와 관리자 조회는 `getMyOrder`와 `getAdminOrder`처럼 명시적인 메서드로 구분한다. `isAdmin` 플래그로 한 메서드의 조회 범위를 전환하지 않는다.
- 도메인에 관리자 여부나 Spring Security·HTTP 객체를 전달하지 않는다. 관리자도 같은 도메인 규칙을 따른다. 고객·관리자 응답 필드를 구분한다.
- 실습 실행은 `server.address=127.0.0.1`을 적용한다. 공개 배포와 실제 비밀정보·개인정보 사용을 하지 않는다. 모의 식별을 운영 인증으로 취급하지 않는다.

## 8. 3주차 범위

2주차의 브랜드·상품·포인트·주문을 이어서 개선한다. 패키지·도메인 책임, 고객·관리자 경계, Checkstyle·ArchUnit은 유지한다.

| 흐름 | 함께 성공·실패할 변경 | 보존할 것 |
| --- | --- | --- |
| 브랜드 일괄 삭제·비노출 | 브랜드와 연결된 미삭제 상품의 삭제 상태 | 다른 브랜드·상품, 과거 주문의 품목·수량·단가·총액·결제 결과 |
| 최초 주문 확정 | 재고 차감, 포인트 차감, CONFIRMED 전이, 결제액·결과 저장 | 실패한 주문의 DRAFT와 변경 전 재고·잔액 |
| 같은 재고·잔액의 동시 변경 | 읽기·검사·변경을 같은 행 잠금 안에서 수행 | 성공한 업무와 최종 재고·잔액의 일치 |

제외: 선택 확장(잠금 전략 비교, 주문 12건 실험, 브랜드 삭제와 상품 등록 동시 실행)은 시간이 남을 때 진행한다. 새 요청키·성공 응답 재사용, JVM `synchronized`, 이벤트·분산 트랜잭션은 도입하지 않는다.

## 9. 트랜잭션 경계

아래 주문 확정 경계는 3주차 기록이다. **5주차부터 주문 확정은 23절로 대체한다.** `OrderApplicationService.confirm`은 트랜잭션 없이 조율하고, `TransactionTemplate`으로 트랜잭션 A → 외부 PG 호출 → 결과 반영 트랜잭션 B를 분리한다. 포인트는 차감하지 않는다. B 예외는 rollback 완료 후 confirm에서 처리한다.

- **시작점:** Controller가 호출하는 ApplicationService 공개 메서드다. 서비스는 Spring bean으로 주입되어 호출이 프록시를 거친다. 같은 객체의 다른 `@Transactional` 메서드를 호출해 경계를 만들지 않는다.
- **선언:** `Order`·`Product`·`PointApplicationService`는 클래스 단위, `BrandApplicationService`는 변경 메서드 단위 `@Transactional`이다. 영속 어댑터에도 선언이 있지만 전파가 REQUIRED라 서비스 트랜잭션에 참여한다. 어댑터를 서비스 밖에서 직접 호출하면 저장마다 따로 commit되므로 변경은 항상 서비스 메서드에서 시작한다. `REQUIRES_NEW`·`NESTED`는 부분 commit을 만들므로 쓰지 않는다.
- **rollback:** 업무 예외(`RuleViolationException`·`InvalidValueException`·`*NotFoundException`)와 잠금 실패 예외는 모두 RuntimeException이다. 서비스 안에서 catch 후 정상 반환하지 않고 밖으로 전달해 전체를 rollback한다.
- **JPA와 JdbcTemplate:** 상품·주문·브랜드는 JPA, 포인트는 JdbcTemplate을 쓴다. 같은 DataSource라 JPA 트랜잭션 관리자가 묶은 커넥션에 JdbcTemplate도 참여하며, 주문 중간 실패 테스트(`OrderTransactionTest`)로 포인트 UPDATE까지 rollback되는 것을 확인했다.
- **flush:** JPA 변경은 commit 직전이나 쿼리 전에, JdbcTemplate UPDATE는 즉시 DB로 나간다. rollback 테스트는 실패 직전에 flush해 실제 변경 SQL이 나간 상태를 만든다.

| 흐름 | 호출 경로 (→ 는 프록시를 거치는 bean 호출) | 경계 |
| --- | --- | --- |
| 브랜드 일괄 삭제 | `AdminBrandController.delete` → `BrandApplicationService.delete` → `BrandPersistenceAdapter`(잠금·save) / `ProductPersistenceAdapter`(대상 조회·잠금·save) | `delete`의 `@Transactional` |
| 주문 확정 | `OrderController.confirm` → `UserIdentificationService.exists`(읽기 전용, 별도 트랜잭션) → `OrderApplicationService.confirm` → `Order`·`Product`·`PointPersistenceAdapter` | 클래스 `@Transactional` |
| 포인트 충전 | `PointController` → `PointApplicationService.charge` → `PointPersistenceAdapter` | 클래스 `@Transactional` |
| 관리자 재고 설정 | `AdminProductController` → `ProductApplicationService.setStock` → `ProductPersistenceAdapter` | 클래스 `@Transactional` |

경계 반례 검토 (`application`·`infrastructure` 커머스 코드 대상):

| 반례 | 확인 방법 | 결과 |
| --- | --- | --- |
| 자기 호출 | 서비스가 같은 객체의 공개 트랜잭션 메서드를 부르는지 검색 | 없음. 내부 호출은 private 메서드(`locked`, `compose`)뿐 |
| 예외 삼키기 | `catch` 검색 | 서비스·어댑터에 없음. interfaces 2곳은 DB 변경 전 ID 형식 오류를 400으로 바꾸는 변환 |
| 독립 commit | `REQUIRES_NEW`·`rollbackFor`·`noRollbackFor` 검색, 한 요청에서 변경 서비스를 여러 번 부르는지 확인 | 없음 |
| 빠진 잠금 대상 | 재고·잔액·주문 상태·삭제 상태를 바꾸는 모든 경로의 조회 확인 | 브랜드 삭제도 브랜드 → 상품 ID 오름차순으로 잠근다. 대상 상품 ID를 조회한 뒤 기본 키로 잠그는 구현은 11절을 따른다 |

## 10. 잠금 전략 [확정]

재고·잔액·주문 상태·삭제 상태는 모두 **행 쓰기 잠금(`SELECT … FOR UPDATE`) 후 도메인 규칙으로 판단**한다(비관적 잠금). 2주차 구현을 유지하고 모든 변경 경로가 따르는지 보강한다.

| 버린 대안 | 이유 |
| --- | --- |
| 조건부 갱신 (`UPDATE … WHERE stock >= ?`) | 재고 판단이 SQL로 옮겨져 `Stock`·`Money` 규칙과 중복된다. 주문 확정은 여러 행과 포인트를 함께 바꾸므로 트랜잭션·순서 제어는 여전히 필요하다 |
| 낙관적 잠금 (`@Version`) | 같은 행에 경쟁이 몰리는 시나리오(재고 5에 주문 8건)에서 충돌·재시도가 많고, 재시도 한도·재조회 구조가 추가된다 |

| 실패 처리 | 결정 |
| --- | --- |
| 잠금 대기 시간 | **3초.** `jpa.yml` 공통 데이터소스의 MySQL 세션 변수 `innodb_lock_wait_timeout=3`. JPA·JdbcTemplate에 같은 값이 적용된다. MySQL에는 문장 단위 대기 시간 문법이 없어 JPA 힌트에 의존하지 않는다. 기본값 50초 대기를 막고 Hikari 커넥션 대기 3초와 맞춘다 |
| 대기 초과·교착 응답 | **503**, "잠시 후 다시 시도해 주세요." 업무 거절(409·404)과 구분한다 |
| 재시도 | 서버 자동 재시도 없음. 클라이언트가 다시 요청한다 |
| rollback | 대기 초과 시 MySQL은 해당 문장만 되돌리므로, 예외를 catch하지 않고 전달해 Spring이 트랜잭션 전체를 rollback하게 한다 |
| 잠금 조회 규칙 | 잠글 엔티티를 같은 트랜잭션에서 먼저 일반 조회하지 않는다. 사전 조회가 필요하면 ID만 조회한다. 영속성 컨텍스트에 있던 엔티티는 잠금 조회 후에도 예전 값이 반환될 수 있다 |
| 잠금 구간 | 외부 호출·sleep을 두지 않는다 |

| 경로 | 잠금 순서 | 같은 행을 바꾸는 다른 경로 |
| --- | --- | --- |
| 주문 생성 | 상품(ID 오름차순) | — |
| 주문 확정 | 주문 → 상품(ID 오름차순) → 포인트 | 재고: 관리자 재고 설정 / 잔액: 충전 |
| 포인트 충전·잔액 조회 | 포인트 (최초 행은 user_id 유일 키 upsert) | 잔액: 주문 확정 |
| 관리자 상품 수정·재고 설정·삭제 | 상품 | 재고: 주문 확정 / 삭제 상태: 브랜드 일괄 삭제 |
| 상품 생성·브랜드 수정 | 브랜드 | — |
| 브랜드 일괄 삭제 (3주차) | 브랜드 → 상품(ID 오름차순) | 삭제 상태: 관리자 상품 삭제 |

같은 행을 바꾸는 경로는 모두 같은 행을 잠근 뒤 Entity 행동으로 변경한다. 상품을 잡은 채 브랜드를, 포인트를 잡은 채 상품을 기다리는 경로가 없고 여러 상품은 항상 ID 오름차순이므로 순환 대기가 생기지 않는다고 판단한다. 그래도 대기 초과·교착은 발생할 수 있다고 보고 503으로 처리한다. 잔액 조회가 쓰기 잠금을 거는 비용은 3주차 범위에서 바꾸지 않는다.

## 11. 브랜드 일괄 삭제·비노출 [확정]

`DELETE /api-admin/v1/brands/{brandId}`가 연결 상품이 있을 때 409로 거절하던 동작을, **브랜드와 연결된 미삭제 상품(재고 0 포함)을 함께 논리 삭제**하는 동작으로 바꾼다.

| 입력·상태 | 결과 |
| --- | --- |
| 활성 브랜드 (연결 상품 0개 이상) | 200, 브랜드와 연결된 미삭제 상품 전체 `deleted=true` |
| 이미 삭제된 브랜드 | 200, 변화 없음 |
| 없는 브랜드 | 404 |
| 일반 사용자·미식별 요청 | 403, DB 변경 없음 |
| 처리 중 예외 | 브랜드·상품 변경 전체 rollback |

처리 순서 (`BrandApplicationService.delete`, 한 트랜잭션):

1. 브랜드 행을 잠근다. 상품 생성도 이 행을 잠그므로 삭제 중 같은 브랜드에 상품이 추가되지 않는다. 이미 삭제됐으면 반환한다.
2. `ProductRepository.findActiveByBrandIdForUpdate`로 대상 상품을 잠근다. 미삭제 상품 **ID만** 일반 조회한 뒤 `WHERE id IN (…) ORDER BY id FOR UPDATE`로 기본 키만 잠그고, 잠근 뒤 읽은 상태가 미삭제인 행만 반환한다. `products.brand_id`에는 인덱스가 없어 `WHERE brand_id = ? FOR UPDATE`는 스캔한 모든 상품 행을 잠글 수 있기 때문이다. 인덱스 추가는 스키마 변경이라 이번 범위에서 제외한다.
3. 상품마다 `Product.delete()` 후 저장하고, 마지막에 `Brand.delete()` 후 저장한다.

- 사용처가 없어지는 `existsActiveByBrandId`는 위 메서드로 대체한다. Product에 새 필드를 추가하지 않는다.
- 주문 품목은 상품 ID와 주문 당시 단가를 저장하므로 상품 행을 남긴다. DB cascade로 주문을 지우지 않는다.
- 삭제 후에는 기존 `deleted` 검사가 그대로 적용된다. 고객 목록·상세와 내 좋아요 목록에서 제외, 새 좋아요·새 주문·확정 거절(삭제 전에 만든 DRAFT 포함, 404), 관리자 수정·재고 변경 거절. 자기 좋아요 취소는 유지한다.

## 12. 최초 주문 확정과 오류 분류

아래 포인트 확정은 3주차 기록이다. **5주차부터 23절로 대체한다:** 트랜잭션 A → PG → 결과 반영이며 포인트 미차감, 같은 시도 키는 현재 결과 반환이다. 트랜잭션 A 오류는 기존 오류 계약을 유지하고, PG 호출 이후 트랜잭션 B 저장 실패 응답은 결제 대기 202다.

`POST /api/v1/orders/{orderId}/confirm`의 2주차 구현을 유지하고 중간 실패·경쟁을 검증한다.

1. 주문 행을 잠그고 본인 주문인지 확인한다(아니면 404). 잠근 상태에서 DRAFT인지 확인한다(CONFIRMED면 409).
2. 품목을 상품 ID 오름차순으로 순회하며 상품을 잠그고 삭제 여부를 확인한 뒤 `Product.decreaseStock`으로 차감·저장한다. 중복 품목은 생성 시 거절하므로 상품별 총수량은 품목 수량과 같다.
3. 포인트 행을 잠그고 `Point.pay`로 차감·저장한 뒤 `Order.confirm`으로 CONFIRMED·결제액·SUCCESS를 저장한다.

어느 단계에서 실패하든 앞의 차감까지 전부 rollback된다. 상태 검사를 잠금 후에 하므로 같은 주문의 동시 확정은 하나만 성공한다.

| 분류 | 예외 | HTTP |
| --- | --- | --- |
| 업무 거절 | `RuleViolationException` (재고·잔액 부족, 재확정, 삭제 상품 변경) | 409 |
| 업무 거절 | `Product`·`Order`·`BrandNotFoundException` (삭제·없음·타인 주문) | 404 |
| 입력 오류 | `InvalidValueException`, 형식 오류 | 400 |
| 기술 오류 | 잠금 대기 초과·교착 (`PessimisticLockingFailureException` 계열) | 503 |
| 기술 오류 | 그 밖의 예외 | 500 |

기술 오류를 재고·잔액 부족으로 바꾸지 않으며, 동시성 테스트도 이 분류로 집계한다.

## 13. 검증 계획

공통: 기존 `MySqlTestContainersConfig`(MySQL 8.0)와 `DatabaseCleanUp`을 쓴다. 데이터는 worker 시작 전에 commit하고, 테스트를 부모 트랜잭션으로 감싸지 않으며, 서비스 트랜잭션이 끝난 뒤 새 조회로 확인한다. 실패 주입은 테스트 설정에서 실제 어댑터를 감싼 `@Primary` 저장소가 지정 순번의 저장에서 `EntityManager.flush()` 후 RuntimeException을 던지는 방식이며, 운영 코드에는 실패용 분기·API·sleep을 넣지 않는다.

| 테스트 | 준비·실행 | 기대 결과 |
| --- | --- | --- |
| `BrandRemovalTransactionTest` 정상 | 브랜드 B, 상품 P1·P2(P2 재고 0), 다른 브랜드 상품 Q, P1을 산 CONFIRMED 주문 | B·P1·P2 삭제, Q·주문의 품목·단가·총액·결제 결과 유지 |
| 〃 중간 실패 | 두 번째 상품 저장에서 flush 후 실패 | 새 조회에서 B·P1·P2 미삭제, Q·주문 유지 |
| 〃 삭제 후 제한 | 일괄 삭제 후 | 11절 삭제 후 제한 전부, 과거 주문 조회 결과 유지 |
| `BrandApiIntegrationTest` | 재고 0 상품이 연결된 브랜드 삭제 / 일반·미식별 DELETE(유효한 CSRF) | 200·브랜드와 상품 삭제(2주차 409 기대값 변경) / 403·변경 없음 |
| `OrderTransactionTest` | 여러 품목 DRAFT, 재고·포인트 UPDATE 후 주문 저장에서 실패 | DRAFT·NOT_PAID, 재고·잔액 변경 전과 같음 |
| `OrderConcurrencyTest` 재고 | 재고 5, 서로 다른 DRAFT 8건(각 1개), 충분한 잔액 | 성공 5·재고 부족 3·기술 오류 0·최종 재고 0, 확정 주문만 수량·결제액 반영 |
| 〃 포인트 | 잔액 10,000원, 서로 다른 상품(각 4,000원·재고 10)의 DRAFT 3건으로 상품 잠금 직렬화를 피하고 같은 잔액에서 경쟁 | 성공 2·잔액 부족 1·기술 오류 0·최종 잔액 2,000원. 요청별 성공은 CONFIRMED·SUCCESS·결제액 4,000원·재고 9, 거절은 DRAFT·NOT_PAID·결제액 0원·재고 10 |
| 〃 충전과 결제 | 잔액 10,000원에 2,000원 충전과 7,000원 확정 동시 | 둘 다 성공·기술 오류 0·최종 잔액 5,000원 |
| `LockTimeoutTest` | 테스트 트랜잭션이 상품 행을 잠근 동안 같은 상품 확정 | 약 3초 후 잠금 실패(HTTP 503), 상태 변화 없음. 잠금 해제 후 같은 요청 성공 |
| `AdminStockLockTest` | 다른 트랜잭션이 상품을 삭제 처리하며 행을 잠근 동안 관리자 재고 설정 | 관리자 요청이 행 잠금 대기에 들어간 뒤 해제되면 삭제 상태를 읽고 거절(409), 삭제 유지·재고 변화 없음 |
| `LostUpdateControlTest` (대조군) | 아래 설명 | 성공 2·최종 재고 4, `2 + 4 ≠ 5` |

- 재고·잔액 부족 rollback, 여러 품목 정상 확정, 같은 주문 동시 확정은 2주차 `OrderApiIntegrationTest`로 회귀 검사한다. HTTP 연결은 브랜드 삭제와 주문 확정 대표 사례로 확인한다.
- **대조군:** 재고 5를 commit한 뒤 독립 트랜잭션 두 개가 잠금 없는 SELECT로 읽고, 둘 다 5를 읽은 것을 확인한 다음(읽기 후 장벽) 각자 상수 4를 조건·version 없이 저장·commit한다. `stock = stock - 1` SQL은 쓰지 않는다. 장벽은 대조군에만 두며 timeout·SQL 오류는 재현으로 세지 않는다. 이 테스트의 통과는 실제 주문 정합성의 증거가 아니다.
- **동시성 실행 규칙:** 실제 서비스 테스트는 시작 latch만 맞춘다. 각 worker는 실제 ApplicationService bean을 호출하고 결과를 성공·업무 거절·기술 오류로 수집한다. 대기에는 제한 시간을 두고 timeout을 업무 거절로 세지 않으며 finally에서 executor를 정리한다. 테스트 커넥션 풀 10개는 worker 8개와 테스트 스레드에 충분하다.
- **불변식:** `초기 재고 − 성공 수량 합 = 최종 재고`, `초기 잔액 + 성공 충전 합 − 성공 결제 합 = 최종 잔액`, 실패 확정은 DRAFT·NOT_PAID·결제액 0, `성공 + 업무 거절 + 기술 오류 = 전체 요청`. 최종 재고가 음수가 아니라는 확인만으로 끝내지 않는다.

```bash
./gradlew :apps:commerce-api:test --tests '*BrandRemovalTransactionTest' --tests '*OrderTransactionTest'
./gradlew :apps:commerce-api:test --tests '*OrderConcurrencyTest' --tests '*LostUpdateControlTest' --tests '*LockTimeoutTest' --tests '*AdminStockLockTest'
./gradlew :apps:commerce-api:check
```

## 14. 3주차 변경 내역·미확인 사항·과제 대조 (3주차 제출 기록)

2주차 문서 대비 변경: 2절 서비스 책임(삭제 전 확인 → 일괄 논리 삭제), 3절 관계 표·본문(재고 0이어도 삭제 거절 → 함께 삭제), 5절 공통 상태 코드(503 추가)와 DELETE 브랜드 대표 오류(연결 상품 존재 → 대상 없음), 6절 검증 범위(삭제 조건 → 일괄 삭제 범위)와 임시 정책(409 거절 제거, 브랜드 → 상품 잠금), 8~14절 추가. 2주차 문서는 제출 기록으로 수정하지 않는다.

검증 결과와 미확인 사항:

- (확인됨) JPA와 JdbcTemplate의 같은 트랜잭션 참여는 `OrderTransactionTest`, 세션 변수 잠금 대기 시간 3초 적용은 `LockTimeoutTest`로 확인했다.
- 잠금 순서 분석은 코드 검토 결과이며 교착이 없다는 실험 증거는 아니다.
- (확인됨) 2026-10-06 macOS에서 Java 21과 기존 Testcontainers의 `mysql:8.0` 설정으로 `:apps:commerce-api:check --rerun-tasks`를 실행해 테스트 111건(실패·오류·skip 0), Checkstyle 위반 0, ArchUnit 3건 통과를 확인했다. CI 환경 실행은 별도 확인이 필요하다.

| 과제 체크리스트 | 근거 | 상태 |
| --- | --- | --- |
| 재고 0 포함 연결 상품과 브랜드가 함께 삭제되고 다른 대상은 유지 | 11절, `BrandRemovalTransactionTest`·`BrandApiIntegrationTest` | 완료 |
| 실제 DB 변경 뒤 실패를 유발해 전체 rollback을 별도 재조회로 확인 | 13절 실패 주입 | 완료 |
| 과거 주문·기존 접근·삭제 후 사용 제한 유지 | 11절, 삭제 후 제한·403 테스트 | 완료 |
| 재고·포인트·주문 확정이 함께 commit 또는 rollback | 12절, `OrderTransactionTest` | 완료 |
| 선택한 제어가 실제 SQL에 적용되고 다른 경로가 우회하지 않음 | 10절 경로표, 동시성·`LockTimeoutTest`·`AdminStockLockTest`, 잠금 제거 변이 검사 | 완료 |
| 재고·포인트 경쟁, 충전과 결제의 결과와 최종 DB 상태 일치 | `OrderConcurrencyTest` | 완료 |
| 대조군과 실제 서비스 검증 구분, 대기·자원 정리 | 13절 | 완료 |
| 프록시·예외·전파·잠금 범위와 diff 확인 | 9절 반례 검토, 구현별 diff 검토 | 완료 |
| 회귀 테스트·lint·ArchUnit 실행, 선택 이유 반영 | 10절, `check` | 완료 |

제출물: 변경 코드·테스트, 이 설계 문서, PR 설명(트랜잭션 경계·선택한 제어·핵심 결과).

## 15. 4주차 범위

3주차의 기능·계약·트랜잭션 경계·잠금 전략을 유지한 채 고객 상품 조회(목록·상세)의 성능을 개선한다. 개선 전 측정 결과와 원인 분석은 `docs/week4/query-performance.md`(측정 기록, 설계 기준 아님)에 있다.

| 순서 | 개선 | 상태 |
| --- | --- | --- |
| ① | 인덱스(17절)와 좋아요 수 비정규화(18절) | 구현·측정 완료(20절) |
| ② | 목록 캐시(cache-aside, 19절) | 구현·측정 완료(20절) |

제외: 깊은 페이지(offset 이후 커서 방식)는 선택 심화로 두고 이번 범위에서 계약을 바꾸지 않는다. 고객 조회의 트랜잭션 분리(요청당 트랜잭션 2개, 제어문 10개)는 3주차 확정 경계라 관찰만 기록한다.

## 16. 조회 계약과 측정 대상 [확정]

조회 API 계약(5절)은 바꾸지 않는다. 정렬 `latest`·`price_asc`·`likes_desc`, 동률은 상품 ID 내림차순, page=0·size=20(1~100), 삭제 상품 제외를 그대로 유지한다. **성능 개선은 개선 전후 응답 결과가 같을 때만 개선으로 센다.**

| 측정 대상 | 요청 | 비고 |
| --- | --- | --- |
| R1 (과제 측정 대상) | `GET /api/v1/products?brandId=1&sort=price_asc&page=0&size=20` | 큰 브랜드 + 가격순 |
| R3 | 작은 브랜드(200) `likes_desc` | 개선 전 결과를 얻을 수 있는 유일한 좋아요순 비교 대상 |
| R4 | `GET /api/v1/products/{productId}` | 상세, 좋아요 수 포함 |

- 측정 환경·인자는 baseline과 같게 한다(10만 `baseline`, 1,000만 `baseline-10m`). 가능하면 개선 전후를 같은 세션에서 쌍으로 잰다.
- 인덱스는 쿼리별 `EXPLAIN`으로 사용 인덱스와 `Using filesort` 제거를 확인한다.
- 인덱스로 늘어난 공간은 `information_schema`로 재서 결과와 함께 기록한다.

## 17. 인덱스 [확정]

원칙: `=`로 찾는 열을 앞에, 정렬 열을 뒤에 둔다. 복합 인덱스는 앞 열이 `=`로 고정될 때만 뒤 열의 정렬을 쓸 수 있으므로, 브랜드 필터 목록과 전체 목록은 인덱스를 따로 둔다. 인덱스는 지금 실제로 쓰는 쿼리 기준으로 정하고, 쿼리가 바뀌면 다시 검토한다.

| 테이블 | 인덱스 | 대상 쿼리 | 근거 |
| --- | --- | --- | --- |
| products | `(brand_id, price ASC, id DESC)` | 브랜드 가격순 | 정렬 방향이 섞여(`ASC, DESC`) 역방향 스캔으로 해결 불가 → 내림차순 인덱스(MySQL 8) |
| products | `(brand_id, created_at)` | 브랜드 최신순 | `DESC, DESC` 같은 방향 → 역방향 스캔. 끝에 붙는 PK(id)까지 내림차순 |
| products | `(created_at DESC, id DESC)` | 전체 최신순 | 브랜드를 고정하지 않으므로 별도 인덱스 |
| products | `(price ASC, id DESC)` | 전체 가격순 | 위와 같음 |
| products | `(brand_id, like_count DESC, id DESC)` | 브랜드 좋아요순 | 18절 |
| products | `(like_count DESC, id DESC)` | 전체 좋아요순 | 18절 |
| product_likes | `(product_id)` 추가 | 좋아요 수 초기 채우기·정합성 점검 | 평소 조회는 `like_count`를 읽음. 채우기·점검 때 상품별 개수를 세는 데 필요 |
| product_likes | `UNIQUE(user_id, product_id)` 유지 | 중복 방지, 좋아요 취소, 내 좋아요 목록 | 기존 인덱스 |

- `deleted`는 인덱스에 넣지 않고 WHERE로 거른다. 목록은 어차피 본 테이블에서 행을 읽어서 확인 비용이 작고, 현재 데이터의 삭제 비율이 약 5%라 버려지는 행이 적다. **삭제 상품 비율이 크게 늘면 다시 검토한다**(분포에 기댄 판단).
- 상품 쓰기(등록·수정·재고·좋아요 수)는 목록 조회보다 드물어, 보조 인덱스 6개의 쓰기·공간 비용을 감수한다.
- 인덱스는 JPA 엔티티의 `@Index`로 선언하고 `ddl-check`로 실제 DDL(정렬 방향 포함)을 확인한다.

## 18. 좋아요 수 비정규화 [확정]

좋아요 수는 `product_likes`를 세어야 나오는 값이라 `products` 인덱스로 미리 정렬할 수 없다. 그래서 `products.like_count`에 미리 세어 둔다. 원본은 계속 `product_likes`이고 `like_count`는 그 요약 복사본이다.

**저장 위치.** `products` 테이블의 열(`bigint not null default 0`). 브랜드 좋아요순을 인덱스 하나로 처리하려면 `brand_id`와 같은 테이블에 있어야 한다.

**도메인·JPA.** Product 도메인 모델에는 두지 않는다(3절). JPA 엔티티에서는 `insertable=false, updatable=false`인 읽기 전용 열로 매핑한다. Hibernate는 매핑된 모든 열을 UPDATE하므로, 쓰기 가능한 열이면 상품 저장 시 읽어 둔 값으로 덮어쓸 수 있다. 지금은 모든 상품 저장 경로가 행 잠금을 먼저 걸어 안전하지만, 그 안전성을 코드에 적히지 않은 약속에 맡기지 않고 구조로 막는다.

**변경 책임.** `ProductRepository`의 좋아요 수 조정 메서드(예: `adjustLikeCount(id, ±1)`)만 `like_count`를 바꾼다. `LikeApplicationService`가 좋아요 관계 저장·삭제와 같은 트랜잭션 안에서 호출한다.

**갱신 규칙.**

1. 원자적 갱신 `UPDATE products SET like_count = like_count + ? WHERE id = ?`. 앱이 값을 읽어 계산하지 않는다(갱신 손실 방지).
2. 실제로 바뀐 경우에만 ±1. 관계 INSERT·DELETE의 **영향 행 수**로 판단한다. 먼저 SELECT로 존재를 확인하지 않는다(확인 후 행동 경쟁). 중복 등록·없는 관계 취소는 3주차 계약대로 상태 변화 없이 성공하며 `like_count`도 변하지 않는다.
3. 등록 경로는 3주차대로 상품 행을 잠근 뒤(`FOR UPDATE`) 저장한다. 취소 경로는 원자적 갱신이 행 잠금을 잡으므로 별도 잠금을 추가하지 않는다.
4. 구현 주의: 현재 저장 SQL(`insert … on duplicate key update`)은 MySQL JDBC 기본 설정에서 중복이어도 영향 행 수가 1로 보일 수 있다. 중복 시 0이 나오는 방식(`INSERT IGNORE` 등)으로 바꾸고 실제 MySQL 테스트로 확인한다.

**조회.** 목록·상세의 좋아요 수와 좋아요순 정렬은 모두 `like_count`를 읽는다(정렬 값과 표시 값 일치). 상품마다 집계하지 않는다.

**초기 채우기.** 기존 데이터는 상품 id 구간을 나눠 여러 번 `UPDATE`해 채운다(구간마다 짧은 트랜잭션). 측정용 seed도 같은 방식으로 채운다.

**검증.**

| 확인할 것 | 방법 |
| --- | --- |
| 등록·취소·중복 등록·없는 관계 취소 후 `like_count`가 원본 개수와 같음 | 실제 MySQL 통합 테스트, 새 트랜잭션에서 재조회 |
| 같은 상품에 동시 등록·취소 후 `like_count` = `count(product_likes)` | 동시성 테스트(2절 동시성 규칙: 성공·업무 거절·기술 오류 합계, 최종 DB 불변식) |
| 상품 수정·재고 변경·주문 확정 후 `like_count` 유지 | 통합 테스트 |
| 정렬·표시 결과가 개선 전과 같음 | 10만 규모 결과 스냅샷 `compare` |

## 19. 목록 캐시 [확정, 수치는 임시]

**원칙.** 주문 확정 같은 결정은 늘 원본 DB와 잠금(10절)으로 한다. 캐시는 **보여 주기용**이라 낡아도 과다 판매는 생기지 않는다. 낡으면 곤란한 값은 캐시하지 않거나 바뀌는 순간 무효화한다.

**대상.** 고객 상품 목록(`GET /api/v1/products`)만 cache-aside로 캐시한다. 상품 상세는 `like_count` 도입 후 PK 조회 한 번이라 캐시하지 않는다.

**캐시 내용(값 분류).**

| 값 | 낡아도 되나 | 바뀌는 빈도 | 처리 |
| --- | --- | --- | --- |
| 페이지의 상품 ID 순서, 이름, 브랜드 이름, 좋아요 수 | 된다 | — | 캐시, TTL로 갱신 |
| 가격 | 안 된다(보여 준 가격 ≠ 결제 가격) | 드묾(관리자) | 캐시, **변경 시 무효화**. 정렬 기준이라 순서와 함께 캐시해야 순서·표시가 어긋나지 않는다 |
| 삭제 여부 | 안 된다(비노출 규칙) | 드묾(관리자) | 캐시, 변경 시 무효화 + 응답 전 PK로 한 번 더 확인해 삭제 상품 제외 |
| 재고 | 안 된다 | 잦음(주문마다) | **캐시하지 않음.** 요청마다 페이지 상품 ID로 PK 일괄 조회 |

응답 형식·정렬 계약(5절)은 바꾸지 않는다. 캐시 적중이든 아니든 같은 응답이어야 한다(재고·삭제 확인 결과만 최신으로 반영).

**키와 무효화 — 전체 버전 하나.**

- 버전 키 `products:list:version`(정수). 목록 키 `products:list:v{버전}:brand={id|all}:sort={sort}:page={p}:size={s}`.
- 무효화는 `INCR products:list:version` 한 번. 옛 버전 키는 아무도 읽지 않다가 TTL로 사라진다.
- 이유: 상품 하나가 바뀌면 그 상품이 표시되는 모든 정렬·모든 페이지(가격이 바뀌면 다른 페이지로 이동)와 전체 목록을 지워야 해 키를 특정할 수 없다. 브랜드별 버전으로 나눠도 전체 목록은 매번 무효화되고 관리자 변경은 드물어, 전체 버전 하나로 단순하게 간다.

**무효화 시점 — commit 후.** 트랜잭션 안(commit 전)에 버전을 올리면, 그 사이 들어온 요청이 commit 전 값을 읽어 새 버전 키에 채우고 이후 무효화 기회가 없어 TTL 동안 낡은 값이 남는다. 그래서 `AFTER_COMMIT`에서 올린다. 버전을 먼저 읽고 DB를 읽으므로, commit 전 값을 읽은 느린 요청은 옛 버전 키에만 쓴다(아무도 읽지 않음).

| 쓰기 경로 | INCR | 이유 |
| --- | --- | --- |
| 상품 생성·수정(이름·가격)·삭제 | O | 새 상품 노출, 가격 무효화, 비노출 |
| 브랜드 이름 변경·일괄 삭제 | O | 목록에 브랜드 이름 표시, 비노출 |
| 재고 설정·주문 확정 | X | 재고는 캐시하지 않음 |
| 좋아요 등록·취소 | X | 좋아요 수는 TTL로 갱신 |

**Redis 연결.** 버전 읽기·올리기와 캐시 읽기·쓰기는 **master**로 한다. 기본 템플릿은 `REPLICA_PREFERRED`라 복제 지연 중 replica에서 옛 버전을 읽으면 무효화가 늦게 반영된다.

**장애 시 동작.** Redis 읽기·쓰기·INCR이 실패하거나 타임아웃이면 요청은 실패시키지 않고 DB 조회로 응답한다(인덱스 적용 후라 DB가 감당 가능하다는 전제, 17절). commit 후 INCR이 실패하면 낡은 값은 최대 TTL 동안 남는다(TTL이 안전망). 실패는 로그로 남긴다.

**알려진 위험 — 캐시 스탬피드.** `INCR` 직후에는 모든 목록 키가 한꺼번에 비어, 그 사이 들어온 요청이 모두 miss로 DB에 간다. 관리자 변경이 드물고 인덱스 적용 후 목록 조회가 수 ms라 영향이 작다고 보고 별도 장치는 두지 않는다(miss 측정으로 확인). 규모가 커지면 검토할 것: 한 요청만 채우는 락(single-flight), 버전 범위 축소(브랜드별), TTL 지터, 읽기 복제 DB(단 복제 지연 중 miss가 옛 값을 새 버전 키에 채울 수 있어 무효화 설계를 다시 봐야 한다).

**수치 [임시].** 실제 트래픽이 없어 가정으로 정한 시작값이며 측정 후 조정한다.

| 값 | 시작값 | 가정 | 바꿀 신호 |
| --- | --- | --- | --- |
| 목록 캐시 TTL | 1분 | 무효화 실패 시 낡은 가격 최대 1분, 좋아요 수 최대 1분 지연 허용 | 적중률이 낮고 DB 부하가 크면 늘림, 낡은 표시가 문제면 줄임 |
| Redis 명령 타임아웃 | 1초 | 평소 Redis 응답은 수 ms. 일시 지연(GC 등)을 장애로 오판하지 않기 위함 | Redis p99가 충분히 낮으면 줄임(장애 시 요청이 기다리는 시간과 스레드 점유 감소) |

**검증.** 실제 MySQL·Redis(Testcontainers) 통합 테스트로 한다.

| 확인할 것 | 방법 |
| --- | --- |
| 첫 요청 miss → 저장, 두 번째 hit에서 DB 목록 조회 없음, 응답 동일 | 통합 테스트 |
| 위 INCR 표의 각 쓰기 후 다음 목록이 새 값, X 경로는 버전 그대로 | 통합 테스트 |
| hit여도 재고는 최신, 삭제 상품 제외 | 통합 테스트 |
| commit 전 버전 증가 없음, 롤백 시 증가 없음 | 통합 테스트 |
| 늦은 채움: commit 전 값을 읽은 요청이 무효화 뒤 쓰더라도 이후 요청은 새 값 | 포트 구현을 감싼 래치로 순서 통제 |
| Redis 장애·타임아웃 시 DB로 정상 응답 | 테스트에서 직접 띄운 Redis 컨테이너만 중지, finally 복구, FLUSHALL 금지 |
| 성능 | 캐시 hit·miss 각각의 R1~R3, 적중률. 결과가 같을 때만 개선으로 셈 |

## 20. 4주차 변경 내역·측정 결과·한계

3주차 문서 대비 변경: 2절 조회 순서 3단계(좋아요 수 집계 → `like_count` 일괄 조회)와 좋아요순 정렬 방식(JOIN·집계 → `like_count` 인덱스 정렬, 2026-10-15 사용자 승인), 2절 Product 책임(`like_count`는 도메인 밖 DB 열), 4절 좋아요 등록·취소 흐름(`like_count` ±1), 6절 임시 정책의 좋아요 집계 문장, 15~20절 추가. API 계약·정렬 결과는 바꾸지 않았다. 19절 목록 캐시 설계는 2026-10-15 사용자와 확정했다(TTL·타임아웃은 임시).

**측정 결과** (상세는 `docs/week4/query-performance.md` 4절, R1 p50)

| 단계 | 10만 | 1,000만 | 비고 |
| --- | ---: | ---: | --- |
| 개선 전 | 153.6ms | 3,771ms | 전체 스캔+filesort, 좋아요 집계 인덱스 전체 스캔 |
| 인덱스 + like_count (17·18절) | 14.6ms | 24.6ms | 좋아요순(10만 R3) 약 19.5초 → 7.8ms |
| 목록 캐시 hit / miss (19절) | 13.1 / 21.8ms | 27.7 / 34.4ms | 1,000만은 측정 흔들림이 hit 이득보다 큼 |

- 모든 단계에서 응답 결과가 개선 전과 같다(`compare` 다름 0건, 개선 전 TIMEOUT 항목은 비교 불가).

**비용과 한계**

- **인덱스**: 1,000만 규모 `products` 인덱스가 1,694MB로 데이터보다 크다. 상품 쓰기가 조회보다 드물어 감수한다(17절).
- **캐시 이득이 작다**: 인덱스 이후 SQL 시간이 2ms대라 hit 이득은 약 2ms(12%)다. 남은 시간(HTTP·JSON·트랜잭션)은 캐시로 줄지 않는다. 캐시는 보조이며, 지금 규모에서는 응답 시간보다 DB 조회 수 절감(목록 SELECT 4 → 2)이 주된 효과다.
- **miss 비용**: Redis 왕복 3번과 재고 PK 조회 때문에 캐시 없음보다 약 3~7ms 느리다. 목록 변경이 잦아 적중률이 낮으면 손해다.
- **Redis 장애**: 응답은 정확하다(전부 200, 캐시 없음과 같은 목록). 대신 요청마다 명령 타임아웃만큼(약 1초) 늦어진다. 연결이 거부될 때도 1초를 기다린다.
- **버전 키 유실**: 영속화가 꺼진 Redis를 재시작하자 버전 키가 사라져 v0부터 다시 시작했다. 데이터 키도 함께 사라지면 문제가 없지만, 버전 키만 메모리 부족으로 밀려나면(eviction) 옛 번호로 돌아가 아직 TTL이 남은 낡은 키를 읽을 수 있다. 낡은 값은 최대 TTL(1분)만큼 남는다.
- **스탬피드**: 19절 기록대로 `INCR` 직후 모든 목록 키가 비지만, miss 측정에서 조회가 수 ms라 영향이 작음을 확인했다.

**19절 임시 수치 판단 (2026-10-16).** TTL 1분·타임아웃 1초를 **유지**한다. 실제 트래픽이 없어 적중률·Redis p99 같은 조정 근거가 아직 없기 때문이다. 대신 장애가 이어질 때 요청마다 1초가 더해지는 문제는 **서킷 브레이커**(연속 실패 시 일정 시간 Redis를 건너뛰고 바로 DB로 응답)로 푸는 것을 다음 개선 후보로 둔다.

**확인된 사항** (작성 당시 미확인이던 것)

- 인덱스·비정규화의 효과와 결과 동일성: 측정으로 확인(위 표).
- `@Index`의 정렬 방향(`DESC`): Hibernate DDL 반영 확인(`ddl-check`).
- 중복 좋아요 영향 행 수: `on duplicate key update`가 중복에도 1을 돌려주는 것을 실제 MySQL 테스트로 확인하고 `INSERT IGNORE`로 바꿈.

## 21. PG 연결 포트와 결과 분류 [확정, 수치는 임시]

5주차 1단계 범위는 외부 PG를 부르는 연결 계층(포트·어댑터·레지스트리)이다. 업무 코드가 PG 응답을 어떻게 해석하고 주문에 반영할지는 이 절의 범위가 아니다.

**포트는 업무 쪽 소유.** 업무 코드는 application이 정의한 `PaymentGateway` 포트만 안다. PG마다 infrastructure의 어댑터가 그 PG의 HTTP·JSON을 포트 모양으로 변환한다. Controller·application·domain은 HTTP 상태 코드·JSON·네트워크 예외를 모른다. 2절의 infrastructure 역할(출력 포트 구현)에 외부 PG 호출 어댑터를 포함한다.

```mermaid
flowchart LR
    App["application<br/>(다음 단계의 결제 흐름)"] --> Registry["PaymentGatewayRegistry<br/>providerId → 포트, 우선순위 목록"]
    Registry --> Port["PaymentGateway 포트<br/>create · verifyOrder"]
    AdapterA["test-pg 어댑터"] -->|구현| Port
    AdapterN["다른 PG 어댑터 …"] -->|구현| Port
    AdapterA -->|HTTP| PG[(테스트 PG)]
```

**여러 PG 교체 가능, 하드코딩 금지.** PG는 3개 이상으로 늘어날 수 있다.

- 어댑터마다 고유 `providerId`를 갖는다. 레지스트리가 모든 `PaymentGateway` 빈을 providerId로 모은다.
- 우선순위는 설정 목록 `payment.providers.priority`로 둔다. 레지스트리는 `get(providerId)`와 우선순위 순서 목록만 제공하며 전환 로직은 없다.
- providerId 중복, 우선순위에 없는 어댑터, 어댑터가 없는 우선순위 항목, 빈 목록이면 애플리케이션 시작을 실패시킨다.
- PG 추가 = 어댑터 클래스 + 설정 추가. 업무 코드는 바꾸지 않는다.

**어댑터는 사실만 보고하고 판단하지 않는다.** 서브 PG 전환·재조회·실패 확정은 애플리케이션이 다음 단계에서 정한다. 어댑터 안에서 재시도·fallback·다른 PG 호출을 하지 않는다.

**생성(create) 결과 분류 — 의심스러우면 결과 모름.**

| 결과 | 조건 (전부 만족할 때만) |
| --- | --- |
| 미발송 `NotSent` | 연결 단계 실패가 확실함: 연결 거부·connect timeout. 요청 바이트가 나갔을 가능성이 있으면 미발송이 아니다 |
| 접수됨 `Accepted` | 202 + 파싱 가능한 JSON + 비어 있지 않은 `transactionKey` + 응답 `orderId`·`amount`가 보낸 값과 일치 + `status`가 `PENDING`·`SUCCESS`·`FAILED` 중 하나 |
| 효과 없는 거절 `RejectedNoEffect` | 422 + `code == "TEST_PG_REJECTED_BEFORE_CREATE"` + `transactionCreated == false` + `orderId`·`amount` 일치 |
| 결과 모름 `Unknown` | 그 밖의 모든 경우: read timeout, 연결 후 끊김, 본문 없는 422, 다른 4xx·5xx(500에 위 거절 본문이 붙어 있어도), 깨진 JSON, 값 불일치, 알 수 없는 status |

- 예외 분류는 원인 타입으로 한다. 연결 단계 예외만 미발송이고, 그 밖의 IO 예외와 판단이 애매한 예외는 결과 모름이다.
- `Unknown`에는 원인 요약을 담는다. 응답 본문·카드 번호는 담지 않는다.
- **create 자동 재전송 금지.** 어댑터도 HTTP 클라이언트도 create를 다시 보내지 않는다(테스트 PG에 멱등 키가 없고, 같은 orderId 재POST를 중복 제거하지 않는다고 가정한다).

**조회 포트는 의도만 표현한다.** `verifyOrder(providerOrderId, storedTransactionKey)`는 이 주문의 거래 확인 요청이다. 저장된 거래 키는 없을 수 있다. 결과는 거래 목록 + 보증 수준(`ALL`: 해당 주문의 거래 전부 / `SINGLE`: 한 건만 확인) 또는 조회 실패·호출 안 함(`NotCalled`)이다. 어댑터는 조회 방법을 선택하고 사실만 보고하며 애플리케이션이 24절에 따라 해석한다. test-pg는 주문 ID 목록 조회를 사용해 `ALL`로 보고하고 기존 키·주문 ID 조회는 어댑터 내부로 옮긴다. 5xx·timeout·깨진 JSON·연결 실패는 조회 실패다. 0건·404는 효과 없음의 증거가 아니다.

**테스트 PG 계약 (과제 제공).**

| 호출 | 요청 | 응답 |
| --- | --- | --- |
| 생성 | `POST /api/v1/payments` `{orderId, cardType, cardNo, amount, callbackUrl}` | 202 `{transactionKey, orderId, amount, status}` / 효과 0건 확정 거절 422 `{"code":"TEST_PG_REJECTED_BEFORE_CREATE", orderId, amount, "transactionCreated":false}` |
| 키 조회 | `GET /api/v1/payments/{transactionKey}` | 200 거래 / 404 |
| 주문 조회 | `GET /api/v1/payments?orderId=` | 200 거래 배열(빈 목록 가능) |

- 공통 헤더 `X-USER-ID`(제공자 계정, 설정값). `orderId`는 6자 이상 문자열(예: `LP-ORD-0000000042`), `amount`는 원화 정수(JSON 숫자).
- base URL·callback URL·`X-USER-ID`는 설정이다. 조회 query는 URI builder로 인코딩한다.
- 조회 응답의 거래 모양은 생성 응답과 같은 `{transactionKey, orderId, amount, status}`로 가정한다.

**카드 번호.** 합성 값만 쓰고 DB·로그·예외 메시지·`toString`에 남기지 않는다.

**수치 [임시].**

| 값 | 시작값 | 가정 |
| --- | --- | --- |
| connect timeout | 1초 | 같은 망의 PG 연결은 수 ms. 연결 단계 실패를 빨리 확정한다 |
| read timeout | 3초 | 연결 후 응답(헤더·본문)까지의 상한. 넘으면 결과 모름 |

**다음 단계에서 결정.** 주문 흐름과 결제 시도 기록, 트랜잭션 분리, 주문·결제 상태, 재고 예약, callback 수신, 서킷 브레이커, 결과 모름의 재조회, 서브 PG 전환.

## 22. 결제 기록과 과거 포인트 결제 이관 [확정]

5주차 2a단계 범위는 결제 정보를 주문에서 결제 기록으로 분리하고 과거 포인트 결제를 이관하는 것이다. PG 결제 흐름(confirm 교체)은 2b(23절)에서 구현한다. 이 절은 3·7절의 Order 구성(결제액·결과)과 5절 확정·주문 조회의 결제 결과 서술을 대체한다.

- **책임 분리:** 주문은 주문(품목·금액·상태)만 안다. 결제수단·결제액·결제 결과는 결제 기록이 가진다.
- **결제 기록(`PaymentRecord`, 테이블 `payment_records`):** 포인트와 PG 결제를 모두 담는다. 주문당 결제 기록은 하나다(주문 ID 유일). 2a에서는 포인트 결제에 필요한 열만 만들고, PG용 열(시도 키·providerId·providerOrderId·거래 키 등)은 2b에서 추가한다.
- **이관 대상:** 과거 포인트 주문(CONFIRMED)의 결제액·결과는 결제 기록(수단 POINT, 성공)으로 이관한다. DRAFT 주문은 결제가 없으므로 옮기지 않는다.
- **이관 방식:** SQL 스크립트로 하고, 통합 테스트로 건수·금액 일치를 검증한다. 스크립트는 다시 실행해도 결과가 같아야 한다(이미 옮긴 주문은 건너뜀). 마이그레이션 도구(Flyway 등)는 도입하지 않는다.
- **주문의 `paid_amount`·`payment_result` 열:** Order 도메인·코드에서는 더 이상 쓰지 않는다. DB 열은 이관 검증이 끝날 때까지 이관 원본으로 남기고, 삭제는 별도 작업으로 한다.
- **주문 조회:** `GET /api/v1/orders`, `GET /api/v1/orders/{orderId}`는 결제 정보를 결제 기록에서 읽고, 응답에 결제수단(`paymentMethod`: `POINT` / `PG` / 결제 전이면 없음)을 추가한다. 기존 필드(결제액·결제 결과)는 의미를 유지한다.
- **관리자 주문 조회:** 관리자 주문 조회도 같은 결제 정보를 보여 준다.

## 23. PG 결제 흐름 [확정, 구현은 2b]

- **트랜잭션 A:** 주문 잠금 → 본인·DRAFT 확인 → 상품 ID 오름차순 잠금·재고 확보(가용 감소) → 결제 기록(`PaymentRecord`: PG, 상태=발송 시작, 시도 키, providerId, providerOrderId) 저장 → 주문 결제 대기. commit 후 PG create(트랜잭션 밖, 잠금 없음). **트랜잭션 B:** 결과 반영.
- **발송 시작 = "보냈을 수도 있음".** 재시작·결과 모름이면 create를 다시 보내지 않고 조회한다(0건이어도 재전송하지 않음).
- **providerOrderId** = `"LP-ORD-"` + 주문 ID 10자리. 실패 후 재결제가 생기면 시도 번호를 붙여야 한다.
- **재고 선점은 confirm 시점.** 선점 상태는 주문 상태로 표현한다: DRAFT=선점 없음, 결제 대기=확보, CONFIRMED=소비(추가 차감 없음), 결제 실패=반환. 결제 실패 전이와 재고 반환은 같은 트랜잭션이다. 부분 취소·여러 시도가 생기면 별도 예약 기록을 재검토한다.
- **시도 키(`paymentAttemptKey`):** 프론트가 결제 화면 진입 시 한 번 만든 UUID이며 재시도 때 같은 키를 쓴다. 결제 기록에 유일 제약을 둔다. 같은 주문+같은 키=재시도(지금 상태 반환), 같은 주문+다른 키=거절, 같은 키+다른 주문·금액=거절. 키는 요청 식별용이고 1회 발송은 주문 상태 잠금이 지킨다.
- **결과 반영(트랜잭션 B)은 하나의 경계로 모은다.** callback 본문은 신호일 뿐이고 PG 조회로 확인한다. 비관적 잠금: 주문 `FOR UPDATE` → Order 도메인이 판단 → 실패면 상품 ID 오름차순 잠금 후 반환. 잠금 순서는 confirm과 같다(10절의 잠금 후 판단 방식).
- 결과 반영은 호출 목적(CREATE·CALLBACK·MANUAL·AUTOMATIC)을 명시해 전달한다. 확인 필요 해제는 기존처럼 MANUAL의 확정 보고만 허용하며 boolean 인자로 권한을 표현하지 않는다.

- **트랜잭션 나누는 방식 (2026-10-21 사용자 승인):** confirm은 `@Transactional` 없이 조율만 한다. 트랜잭션 A와 결과 반영(트랜잭션 B)은 `OrderApplicationService` 안에서 `TransactionTemplate`으로 경계를 만든다. 같은 객체의 `@Transactional` 메서드를 부르면 프록시를 거치지 않아 경계가 생기지 않기 때문이다(9절). 결과 반영은 공개 메서드 하나(PG가 보고한 결과 하나를 받아 반영)로 두고, 3단계 reconcile·callback도 이 메서드를 쓴다. 이에 따라 `OrderApplicationService`의 클래스 단위 `@Transactional`(9절)은 메서드 단위 선언과 `TransactionTemplate`으로 바뀐다. 2절 서비스 구성은 유지한다.
- **확인 경로:** 결제 대기 202 응답의 확인 경로는 주문 상세 `GET /api/v1/orders/{orderId}`다(새 API 없음).

**상태 전이**

| 지금 상태 \ 들어온 일 | confirm | 결과: 성공 | 결과: 실패 | 결과: 모름 |
| --- | --- | --- | --- | --- |
| DRAFT | → 결제 대기 (트랜잭션 A, PG 시작) | 변화 없음 + 이상 기록 | 변화 없음 + 이상 기록 | 변화 없음 + 이상 기록 |
| 결제 대기 | 같은 키 → 지금 상태 반환(202) / 다른 키 → 거절 | → CONFIRMED (예약 소비) | → 결제 실패 (재고 반환 1회) | 유지 (이후 조회) |
| CONFIRMED | 같은 키 → 저장 결과 반환 / 다른 키 → 거절 | 변화 없음 | 변화 없음 + 충돌 기록 | 변화 없음 |
| 결제 실패 | 같은 키 → 저장 결과 반환 / 다른 키 → 거절 | 변화 없음 + 충돌 기록 | 변화 없음 | 변화 없음 |

- "결과: 실패"는 PG `FAILED`와 효과 없는 거절(21절)을 포함한다. "결과: 모름"은 `PENDING`·결과 모름·조회 실패다.
- **confirm 응답:** 결제 대기 202 + 상태 + 확인 경로 / 종결(CONFIRMED·결제 실패) 200 + 결과 / 다른 키·키 재사용 409 / 재고 부족·삭제 상품(PG 0회) 기존 409·404 / 타인·없는 주문 404 / 잠금 대기 초과 503. HTTP 코드는 요청 처리 결과, 결제 결과는 본문이다.
- **PG 호출 이후 트랜잭션 B 저장 실패:** rollback 후 주문을 새로 읽어 결제 대기 202 + 확인 경로를 반환한다. 주문 ID·PG 상태를 ERROR 로그에 남기며 카드 번호·예외 원문은 남기지 않는다. 트랜잭션 A 실패는 기존 오류 응답이다.
### 서브 PG 전환 [확정]

- 결제는 한 번만 수행한다. 연결 단계 실패가 확실한 `NotSent`일 때만 `payment.providers.priority`의 다음 PG로 전환한다. `Unknown`·접수됨·효과 없는 거절에서는 전환하지 않는다.
- 다음 PG create 전에 짧은 트랜잭션에서 주문 `FOR UPDATE` → 결제 대기·현재 providerId·결과 PENDING 확인 → providerId를 다음 PG로 변경하고 PG 상태 `SEND_STARTED` 저장·commit → 트랜잭션 밖 create 순서를 지킨다. providerOrderId는 유지한다.
- 이후 조회·reconcile·callback은 결제 기록의 현재 providerId 어댑터를 사용한다. 전환 저장 후 발송 전에 중단돼도 같은 키 재요청은 재발송하지 않는다.
- 모든 PG가 미발송이면 결과 반영 경계에서 `ALL_NOT_SENT`로 결제 실패·재고 반환 1회 처리한다. PG가 하나면 그 PG 미발송 즉시 실패다.
- 시도한 providerId와 결과 분류는 INFO 로그에 남긴다. 카드 번호·응답 원문은 남기지 않는다.

- **조회 해석·충돌·이상 기록:** 24절, 자동 재조회·조회 서킷 브레이커는 25절을 따른다. timeout 수치는 21절의 임시 기준을 유지한다.


## 24. 결과 확정 경로: callback·reconcile·이상 기록 [확정]

### 입력 경로와 경계

- **과제 원문 계약 반영(2026-10-21 사용자 지시):** 고객 본인 주문 `POST /api/v1/orders/{orderId}/payment/reconcile`을 제공한다. X-USER-ID 식별·소유권을 PG 조회 전에 확인하며 타인·없는 주문은 404, 식별 누락은 401, PG 결제가 아니면 409다. 기존 관리자 reconcile은 확장 API로 유지한다.
- 고객 reconcile은 미완료 PAYMENT_PENDING이면 202 + 주문 ID·주문 상태·결제 결과·PG 시도 상태(`providerStatus`)·주문 상세 조회 경로(`statusUrl`), 종결이면 200 + 주문 결과를 반환한다. 실제 조회 실패도 미완료이면 202 + 현재 상태이며 LOOKUP_FAILED를 기록한다. 회로 미호출은 기존 503 오류 계약을 적용하며 상태를 바꾸지 않는다.
- confirm의 202와 고객·관리자 주문 조회도 providerStatus로 PENDING·UNKNOWN·NEEDS_REVIEW를 구분한다. 포인트·결제 전 주문에는 PG 상태가 없다. 기존 응답 필드는 유지한다.
- 고객의 명시적 reconcile도 수동 확인 경로이며 일치하는 확정 결과로 확인 필요를 해제할 수 있다. 관리자 미완료 200·실제 PG 실패 200·회로 미호출 503의 기존 확장 계약은 유지한다.


- callback: `POST /api/v1/payments/callback`, 과제 본문 `{providerOrderId, transactionKey, status}`. 기존 `orderId` 이름은 providerOrderId의 별칭으로 계속 받으며 `amount`는 선택 입력(있으면 기존 숫자·0 이상 검증 유지)이다. 본문은 신호일 뿐 status·금액·키를 결제 결과에 반영하지 않는다. providerOrderId로 결제 기록을 찾고 저장된 providerId·providerOrderId·거래 키로 PG를 조회한다.
- 처리 순서: 결제 기록 찾기 → 트랜잭션 밖·잠금 없이 PG 조회 → 결과 반영 경계. 확인 필요(`NEEDS_REVIEW`) 기록은 callback 조회에서 제외한다(D41).
- callback 키는 저장된 거래 키가 있으면 그 키와 비교한다. 저장 키가 없으면 조회 결과가 정확히 1건이고 주문 ID·금액이 일치할 때 그 거래 키와 비교한다. 비교 대상이 없으면(조회 실패·0건·2건 이상·불일치) 키 불일치 이상을 기록하지 않는다. 다르면 `CALLBACK_KEY_MISMATCH`(영구적)만 기록하고 확인 필요로 바꾸지 않으며 조회 결과 반영은 그대로 진행한다.
- callback은 형식이 맞으면 조회 성공·실패와 무관하게 200. 모르는 orderId는 200 + WARN, 이상 테이블에는 쓰지 않는다. 본문 형식 오류는 400.
- 관리자: `POST /api-admin/v1/orders/{orderId}/payment/reconcile`. 기존 관리자 보안 경계를 사용한다. 저장된 값으로 조회 → 결과 반영 경계이며 확인 필요도 처리한다. 응답은 관리자 주문 조회와 같은 모양 200, 없는 주문 404, PG 결제가 아닌 주문 409. 회로 열림으로 호출하지 못한 경우는 25절에 따라 503 + 기존 ApiResponse 오류 형식이며 주문·재고·스케줄·이상 기록을 바꾸지 않는다. 실제 PG 조회 실패는 기존 200 + 현재 상태·일시적 이상 기록이다.
- 조회 결과와 create 보고는 `OrderApplicationService`의 결과 반영 경계에서 공통 내부 반영 메서드를 재사용한다. 트랜잭션 하나, 주문 → 상품 ID 오름차순 잠금, PG 호출은 그 밖을 지킨다.

### 조회 결과 해석

`PaymentLookupAssessment`는 조회 결과를 미호출·미확정·확인 필요·반영 가능한 보고로 해석하고, 저장 키 또는 검증된 일치 단일 거래 키를 callback 비교 근거로 제공한다. `OrderApplicationService`는 주문 잠금 아래 현재 결제 기록으로 이 해석을 수행한 뒤 기존 결과 반영·이상 기록·스케줄 저장을 조율한다. 해석 객체가 상태를 변경하거나 PG를 호출하지 않는다.

| 결과 | 주문·결제 기록 | 이상 종류 |
| --- | --- | --- |
| 조회 실패 | 결제 대기·PG 상태 유지 | `LOOKUP_FAILED` (일시적) |
| 호출 안 함 `NotCalled` | 주문·PG 상태 유지, 로그만. callback 200, 자동 조회 횟수 미집계·다음 시각만 연기 | 없음 |
| 0건 | 결제 대기·PG 상태 유지. 저장 키 존재 여부도 요약에 기록 | `NO_TRANSACTION` (일시적) |
| 1건, 주문 ID·금액·저장 키(있다면) 일치 | PENDING: 유지·키 저장 / SUCCESS: CONFIRMED / FAILED: 결제 실패·재고 반환 1회 | 없음 |
| 1건 불일치 | 결제 대기·`NEEDS_REVIEW`, 거래 키 유지 | `TRANSACTION_MISMATCH` (영구적) |
| 2건 이상, 보증 ALL | 결제 대기·`NEEDS_REVIEW`, 거래 키 유지 | `DUPLICATE_TRANSACTIONS` (영구적) |
| 2건 이상, 보증 SINGLE | 결제 대기·`NEEDS_REVIEW`, 거래 키 유지 | `COVERAGE_CONTRACT_VIOLATION` (영구적) |

보증 SINGLE은 그 주문 거래 전부를 확인한 것이 아니므로 전체 거래의 중복 여부를 판단하지 않는다. 다만 SINGLE로 2건 이상을 보고하면 보증 계약 위반으로 격리한다. `PaymentTransactionsLookup.Found`는 보증 수준을 항상 명시하며 기본값 ALL 보조 생성자는 두지 않는다. 종결 주문에 반대 결과는 상태를 덮어쓰지 않고 `CONFLICTING_RESULT`(영구적), DRAFT에 결과는 변화 없이 `RESULT_BEFORE_PAYMENT`(영구적)로 기록하며 WARN 로그도 유지한다.

주문이 결제 대기가 아니면 조회 실패·0건은 이상 테이블에 쓰지 않고 INFO 로그만 남긴다. 종결 주문의 반대 결과와 callback 키 불일치는 기존 영구 이상 기록 규칙을 유지한다.

확인 필요는 주문 상태가 아니라 결제 기록 PG 상태다. PENDING·결과 모름·조회 실패는 확인 필요를 지우지 않는다. **수동 reconcile이 일치하는 확정 성공·실패를 얻었을 때만** 확인 필요에서 벗어난다. callback 키 불일치 이상은 영구적이며 자동 해결하지 않는다.

test-pg 어댑터는 조회 응답의 다른 orderId 거래도 걸러내지 않고 사실대로 보고하며, 애플리케이션이 불일치·중복으로 해석한다.

### 이상 기록

- `payment_anomalies`: 주문 ID(`order_id`), 종류(`kind`), 처음 시각(`first_seen_at`), 마지막 시각(`last_seen_at`), 횟수(`occurrences`), 마지막 PG 값 요약(`last_summary`), 해결 시각(`resolved_at`), 내부 ID. `(order_id, kind)` 유일 제약.
- 결과 반영 트랜잭션 안, 주문 FOR UPDATE 잠금 아래 없으면 INSERT, 있으면 횟수·마지막 시각·요약을 갱신한다. 해결된 종류가 재발하면 해결 시각을 비우고 횟수를 올린다.
- 주문이 CONFIRMED·결제 실패로 종결되는 경계에서 미해결 일시적 이상만 해결한다. 영구적 이상은 자동 해결하지 않는다.
- 시각은 주입한 `Clock`을 사용한다. PG 요약에는 카드 번호·원문 응답을 담지 않고 상태·건수·일치 여부만 담는다.

스케줄러·자동 재조회·backoff·조회 서킷 브레이커는 25절을 따른다. 이상 해결 API·주문 결제 열 삭제·토스 어댑터는 범위 밖이다.


### 한계

모든 PG 미발송 뒤 트랜잭션 B 저장이 실패하거나 서브 PG 전환 저장이 실패하면, "모든 PG 미발송" 사실이 남지 않아 결제 실패로 확정할 수 없다. 결제 대기 → 재조회 0건 → 4단계 한도 후 확인 필요로 간다. 이중 결제는 없고 재고만 묶인다. 4단계 한도 처리는 25절을 따른다.


## 25. 결과 모름의 자동 재조회와 서킷 브레이커 [확정, 수치는 임시]

### 재조회 상태와 결과 반영

- `payment_lookup_schedules`: `order_id`(주문 ID·기본 키·유일), `next_lookup_at`(다음 조회), `actual_attempts`(PG에 실제로 물어본 횟수), `created_at`·`updated_at`(생성·갱신 시각).
- 트랜잭션 A에서 결제 대기 전이와 같은 트랜잭션으로 행을 만든다. 첫 시각은 주입 Clock의 지금 + 첫 간격에 full jitter를 적용한 값이다. create 전 중단돼도 재조회 대상에 남는다.
- 결과 반영 경계의 주문 잠금 아래 CONFIRMED·결제 실패 종결 또는 NEEDS_REVIEW가 되면 스케줄을 삭제한다. create 응답·callback·수동 reconcile·자동 재조회 모두 같은 경계를 사용한다.
- 자동 경로는 callback과 같이 NEEDS_REVIEW를 조회하지 않는다. 현재 결제 기록 providerId로 트랜잭션 밖에서 PG를 조회하고 기존 검증·결과 반영 경계에 넣는다. 자동 경로는 callback 키 비교를 하지 않는다.
- 같은 결과 반영 트랜잭션·주문 잠금 아래 실제 조회 횟수 +1 후 미확정이면 다음 시각을 갱신한다. NotCalled는 횟수·이상 기록을 늘리지 않고 다음 시각만 미룬다.
- 실제 조회 12회 한도에서 여전히 결제 대기면 NEEDS_REVIEW + 영구 이상 `LOOKUP_LIMIT_EXHAUSTED` + 스케줄 삭제로 전환한다. 실패로 확정하거나 재고를 반환하지 않는다(D42).

### 간격과 실행 설정 [임시]

- `payment.lookup.initial-delay=5s`, `payment.lookup.multiplier=2`, `payment.lookup.max-delay=1m`, `payment.lookup.max-attempts=12`.
- 명목 간격은 5→10→20→40→60→60초이며 full jitter는 0~명목 간격 사이에서 선택한다. 무작위 선택은 주입 가능하게 하고, Clock은 기존 주입 Clock을 쓴다.
- 바꿀 신호: 한도 소진 건수, 결제 대기 체류 시간 p99, PG 장애 지속 시간. 명목 합 약 9분이며 full jitter 평균은 그 절반 정도다.
- `payment.lookup.scheduler.enabled=true`, `payment.lookup.scheduler.fixed-delay=1s`, `payment.lookup.batch-size=100`. 테스트 프로필에서는 스케줄 실행을 끄고 한 번 실행 메서드를 직접 호출한다.
- @Scheduled 고정 지연으로 다음 시각 ≤ 지금인 행을 다음 시각·주문 ID 순으로 최대 batch-size건 읽는다. 주문 하나의 예외는 잡아 로그에 남기고 다음 주문을 계속 처리한다.

### PG별 조회 서킷 브레이커 [임시]

- `resilience4j-circuitbreaker:2.4.0`을 직접 사용한다. Spring Boot 3.4.4·Java 21을 유지하며 Boot 자동 설정 스타터는 추가하지 않는다.
- infrastructure에서 PG 어댑터를 감싼다. providerId별 별도 회로이며 verifyOrder만 적용하고 create는 그대로 전달한다. application·domain은 Resilience4j에 의존하지 않는다.
- `payment.lookup.circuit.sliding-window-size=20`, `minimum-number-of-calls=10`, `failure-rate-threshold=50`, `open-duration=30s`, `half-open-calls=3`. 횟수 기반, 열림→반열림 자동 전환은 끄고 기존 Clock을 주입한다.
- 조회 Failed(5xx·timeout·연결 실패·깨진 응답)는 실패 표본, 거래 있음·0건 Found는 성공 표본이다(D45). 회로가 호출을 거절하면 포트의 NotCalled로 보고한다.
- NotCalled: callback은 200 + 로그, 자동 경로는 횟수·이상 기록 없이 다음 시각만 연기한다. 관리자 reconcile은 503 + 기존 ApiResponse 오류 형식이며 주문·재고·스케줄·이상 기록은 유지한다(사용자 승인). 남은 열림 시간을 정확히 제공할 수 있으면 Retry-After(초)를 붙이고, 그렇지 않으면 생략 사실을 보고한다.

회로 열림은 우리가 정책상 호출하지 않아 확인을 시도하지 않은 경우라 관리자 응답은 503이다. 실제 PG 500·timeout은 호출 후 실패를 관찰한 것이므로 기존 200 + 현재 상태 + LOOKUP_FAILED 기록을 유지한다.

### 한계

서버 한 대를 가정한다. 여러 대면 동일 주문을 중복 조회할 수 있지만 조회에는 외부 효과가 없고 반영은 주문 잠금으로 직렬화되므로 정확성 문제는 아니고 낭비다. 다중 서버가 되면 FOR UPDATE SKIP LOCKED 선점 또는 ShedLock을 검토한다.

PG 장애로 회로가 계속 열리면 한도가 줄지 않아 결제 대기가 길어질 수 있다. callback·수동 reconcile 경로는 남아 있다. 다중 서버 선점·timeout 수치 변경·create 회로·이상 해결 API·주문 결제 열 삭제·토스 어댑터는 범위 밖이다.
