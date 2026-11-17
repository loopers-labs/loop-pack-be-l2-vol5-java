# Aggregate / Domain Model

> **학습 기록 · 2주차 당시 기준.** 요구·선택·검증 수치는 해당 단계의 기록이다. 현재 구조·명령·정책 차이는 [현행 컨벤션](conventions.md)을 함께 확인한다.

Aggregate는 함께 지켜야 할 상태·규칙을 묶는 경계이며, Root의 행동으로 변경한다.
**Aggregate 경계와 DB 트랜잭션 범위가 항상 같지는 않다.** 주문 확정은 여러 Aggregate를 함께 저장한다.

신규 모델은 순수 Java로 작성하고 infrastructure의 JPA Entity와 분리한다.
`create`와 `restore` 모두 유효한 상태를 보장하며, 공개 setter 대신 의미 있는 행동으로 변경한다.
Money·Stock 같은 값 객체와 구체적인 이름은 [개발 컨벤션](conventions.md)을 따른다.

## 모델 관계

실선 합성은 Aggregate 내부 포함, 점선은 ID 참조다. JPA 객체 연관을 그대로 요구하는 그림이 아니다.

```mermaid
classDiagram
    Brand <.. Product : brandId
    User <.. Like : userId
    Product <.. Like : productId
    User <.. Order : userId
    Order "1" *-- "1..*" OrderItem : 내부 Entity
    Product <.. OrderItem : productId와 스냅샷
    User <.. Point : userId
    Point <.. PointBill : userId
    Order <.. OrderBill : orderId
    User <.. OrderBill : userId
```

## 상태와 행동

| 소유 | Root / 모델 | 핵심 상태 | 주요 행동 |
|---|---|---|---|
| Mall | Brand | id, name, description, deleted, createdAt | 생성, 정보 변경, 논리 삭제 |
| Mall | Product | id, brandId, name, description, price, stock, deleted, createdAt | 생성, 정보 변경, 재고 설정·차감, 논리 삭제 |
| Shopping | User | id | fixture로 식별 |
| Shopping | Like | userId, productId, createdAt | 관계 생성·제거 |
| Ordering | Order | id, userId, status, totalAmount, createdAt, items | 생성, 확정 |
| Ordering | OrderItem | productId, productName, unitPrice, quantity, amount | 주문 생성 시 구성 |
| Pay | Point | userId, balance | 충전, 결제 차감 |
| Pay | PointBill | id, userId, orderId, type, amount, createdAt | 변경 내역 기록 |
| Pay | OrderBill | id, orderId, userId, amount, status, createdAt | 성공 결제 기록 |

OrderItem은 Order 내부 Entity이며 단독 변경 API를 제공하지 않는다.
PointBill·OrderBill은 증가하는 이력을 별도 기록으로 보관하고 생성 후 수정하지 않는다.
PointBill의 amount는 양수이며 CHARGE/USE로 증감 방향을 구분한다. 충전의 orderId는 null이다.
OrderBill은 성공 시에만 생성하며 이번 범위의 상태는 PAID다. DRAFT에는 OrderBill이 없다.

## 불변식과 협력

| 모델 | 스스로 지킬 규칙 | application에서 함께 확인할 것 |
|---|---|---|
| Brand | 유효한 이름·설명, 삭제 후 변경 금지 | 활성 연결 상품이 없는지 |
| Product | 양수 가격, 0 이상 재고, 부족 차감·삭제 후 변경 금지 | 참조 브랜드의 존재·활성 여부 |
| Like | userId·productId의 유효성 | 상품 확인, 관계 유일성; 사용자 존재는 resolver에서 검사 |
| Order | 품목 한 개 이상, 양수 수량, 합계 일치, DRAFT에서만 확정 | 저장된 사용자 결제 연결, 현재 상품 상태, 재고·결제 결과 |
| Point | 음수 잔액 금지, 양수 충전·차감, 부족·범위 초과 거절 | 사용자와 주문의 연결 |
| PointBill | 양수 amount, 유효한 type·차감 후 잔액 | 실제 잔액 변경과 같은 트랜잭션 |
| OrderBill | 양수 결제액, orderId별 성공 기록 하나 | 주문 합계와 결제액 일치 |

관계·결제의 중복은 DB의 `(userId, productId)`, `orderId` 유일성으로도 보호한다.
Shopping의 상품별 좋아요 집계 테이블은 조회용 파생 데이터이며 Product Aggregate의 상태가 아니다.
관계 변경과 별도 트랜잭션에서 시작 시·10초 간격으로 전체 갱신한다. 실패 시 이전 집계 값을 유지한다.
주문 상태 검사와 차감은 같은 트랜잭션에서 수행하며 순차 재확정은 거절한다.
동시 요청의 중복 차감·갱신 유실 방지를 위한 잠금·버전 검증·재시도와 동시성 테스트는 다음 학습으로 미룬다.
이번 범위에서는 DB 유일성 제약과 단일 요청의 전체 롤백을 검증하며 동시 요청 안전성을 보장한다고 해석하지 않는다.

## 주문 상태

```mermaid
stateDiagram-v2
    [*] --> DRAFT: 주문 생성
    DRAFT --> CONFIRMED: 재고·결제·기록 저장 성공
    DRAFT --> DRAFT: 확정 실패 및 전체 롤백
    CONFIRMED --> CONFIRMED: 재확정은 409 / 변경 없음
```

CONFIRMED는 결제를 마친 상태다. 취소·환불·결제 대기 상태는 추가하지 않는다.
주문 생성 후 품목 수정 API가 없으며 단가·상품명 스냅샷은 현재 상품 변경과 무관하게 유지한다.

## 저장 경계

| 유스케이스 | 함께 저장하는 상태 |
|---|---|
| 주문 생성 | Order + OrderItem 전체 |
| 포인트 충전 | Point + CHARGE PointBill |
| 주문 확정 | 모든 Product 재고 + Point + USE PointBill + OrderBill + Order 상태 |
| 좋아요 등록·취소 | 헤더로 지정한 사용자의 Like 관계 |

한 품목의 재고 부족이나 결제 실패도 확정 전체를 실패시킨다.
부분 차감 후 보상 데이터를 추가하는 대신 요청 전 상태로 롤백한다.
트랜잭션은 application의 UseCase 구현 Service가 소유하고, 변경된 도메인은 repository의 `save`로 저장한다.
RepositoryImpl은 저장을 조율하고 EntityMapper는 도메인 복원·신규 Entity 변환·기존 Entity 반영을 담당한다.
기존 Entity의 ID·생성 시각 등 저장 메타데이터는 변환 중 보존한다. domain은 JPA 변경 감지에 의존하지 않는다.

주문 확정만 예외로, 검증은 그대로 domain 객체(`Product.decreaseStock`, `Point.use`, `Order.confirm`)가 수행하되
실제 저장은 개별 repository의 `save` 대신 `ConfirmOrderWriter`(application 계약) 하나가 JDBC로 모아서 처리한다.
품목·기록 수만큼 반복되는 저장 라운드트립을 줄이기 위한 선택이며, 근거는 [통합한 당시 구현 기록](README.md#당시-구현과-결정)의 PR06 선택을 따른다.

값의 범위는 [정책](02-business-policies.md), 조회 형태는 [Read Model](06-read-models.md)에 정의한다.

## 후속 결정: PointBill 잔액 필드

위 초기 모델의 차감 후 잔액 설명과 달리 PR06 문답에서는 balanceAfter를 저장하지 않기로 선택했다.
이력 조회 API가 없는 당시 범위의 결정이며 [통합한 구현 기록](README.md#당시-구현과-결정)을 따른다.
이 초기 표를 현재 필드 추가 요구로 해석하지 않는다.
