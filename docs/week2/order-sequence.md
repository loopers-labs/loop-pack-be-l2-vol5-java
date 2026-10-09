# 주문 생성·확정 시퀀스

> 변경일: 2026-10-09
>
> 현재 주문 생성의 공유락과 주문 확정의 트랜잭션·재시도 경계를 반영했다.

[요구사항](./requirements.md), [도메인 규칙](./domain-rules.yaml), [도메인 관계](./domain-relations.md), [API 계약](./api-contract.md)을 기준으로 주문의 상태 변화를 그린다. 잠금 대상과 선택 근거는 [3주차 ADR](../week3/ADR.md)에 정리했다.

## 주문 생성

```mermaid
sequenceDiagram
    autonumber
    actor C as 고객
    participant API as 주문 API
    participant A as application
    participant P as Product
    participant O as Order / OrderItem

    C->>API: POST /api/v1/orders (상품·수량)
    API->>A: 주문 생성 요청
    Note over A: Spring 프록시를 거쳐 @Transactional 시작
    A->>P: 중복 제거·ID 오름차순 개별 공유락 조회, 활성 여부 확인
    alt 상품이 없거나 삭제됨
        A-->>API: PRODUCT_NOT_FOUND
        API-->>C: 404
    else 주문 가능
        A->>O: 상품 ID·이름·단가와 수량으로 품목 생성
        O->>O: 품목 금액 합산, DRAFT 주문 생성
        A->>O: 주문 저장
        Note over A,P: commit 후 Product 공유락 해제
        A-->>API: 주문 상세
        API-->>C: 201, DRAFT·품목·합계 (payment 없음)
    end
    Note over P,O: 주문 생성만으로 재고와 포인트는 차감되지 않는다.
```

## 주문 확정

`DRAFT` 주문만 확정할 수 있다. 이미 `CONFIRMED`인 주문의 재확정은 `ORDER_ALREADY_CONFIRMED`로 거절한다.

```mermaid
sequenceDiagram
    autonumber
    actor C as 고객
    participant API as 주문 API
    participant A as OrderUseCase.confirm()
    participant TX as TransactionRetryExecutor / TransactionTemplate
    participant R as Repository
    participant DS as OrderConfirmService
    participant O as Order
    participant P as Product
    participant ST as Stock
    participant U as User
    participant PT as Point

    C->>API: POST /api/v1/orders/{orderId}/confirm
    API->>A: 요청자·주문 ID 전달
    A->>TX: execute(확정 작업, 재시도 2회)
    TX->>A: 새 트랜잭션에서 confirmInCurrentTransaction 실행
    Note over TX: 기존 트랜잭션이 없는 HTTP 경로는 REQUIRES_NEW
    A->>R: 주문 조회
    R-->>A: Order
    A->>O: 본인 주문 확인
    alt 주문이 없거나 다른 고객의 주문
        A-->>API: ORDER_NOT_FOUND
        API-->>C: 404
    else 본인 주문
        A->>R: User 일반 조회 → ID 오름차순 Product 공유락 → 같은 순서 Stock 배타락 → Point 일반 조회
        R-->>A: User·Product·Stock·Point
        A->>DS: 확정 요청(요청자·주문·상품들·재고들·포인트·시각)
        DS->>O: DRAFT 상태 확인
        DS->>P: 상품 사용 가능 여부 확인
        DS->>ST: 품목별 재고 확인
        DS->>PT: 주문 합계만큼 포인트 잔액 확인
        alt 상태·상품·재고·잔액 검사 실패
            DS-->>A: 해당 오류
            A-->>API: 확정 거절
            API-->>C: 409, 해당 오류 코드
            Note over O,ST: 확정 거절 시 주문·재고·잔액·결제 결과는 변경되지 않는다.
        else 모든 검사 통과
            DS->>O: 결제액·시각 기록, CONFIRMED로 변경
            DS-->>A: 확정된 주문
            A->>ST: 품목 수량만큼 변경·저장
            A->>PT: 주문 합계만큼 변경·저장
            A->>R: Order 저장·flush (Point·Order version 검사)
            A-->>TX: 확정 작업 반환
            TX-->>A: commit 성공 후 반환
            Note over O,ST: 재고·잔액·주문·결제 결과 함께 반영
            A-->>API: 주문 상세
            API-->>C: 200, CONFIRMED·품목·합계·payment
        end
    end
```

`OrderV1Controller` → Spring bean `OrderUseCase.confirm()` → `TransactionRetryExecutor.execute(..., 2)` → `TransactionTemplate` → `confirmInCurrentTransaction()` → repository 순서로 실행한다. `confirm()` 자체에는 `@Transactional`이 없으며, private 메서드의 자기 호출로 트랜잭션을 시작하는 것도 아니다. 트랜잭션은 실행기가 콜백을 실행하기 전에 시작한다.

- HTTP 요청처럼 기존 트랜잭션이 없으면 `REQUIRES_NEW`로 실행한다. 낙관적 충돌은 실패한 트랜잭션을 rollback한 뒤 새 트랜잭션에서 재조회·재검증하며 최대 2회 재시도한다(최초 포함 3회). 대기 시간은 `concurrency.transaction-retry.backoff-ms`로 설정하며 기본값은 0ms다.
- 이미 트랜잭션이 있는 호출에서는 기존 트랜잭션에 참여하고 실행기 내부에서 재시도하지 않는다. 이 경우 최종 commit은 외부 호출자의 책임이다.
- Product는 중복 제거한 ID를 정렬한 뒤 개별 조회로 공유락을 획득한다. Stock도 같은 순서로 배타락을 획득한다. 잠금은 commit·rollback까지 유지한다.
- Point·Order는 `@Version`으로 충돌을 검사한다. 충전도 Point 버전을 사용하되 자동 재시도하지 않는다.
- 업무 검증 오류나 저장 오류가 발생하면 주문·재고·잔액·결제 결과 전체를 rollback한다. 재고·잔액 부족 등 업무 예외는 낙관적 충돌 재시도 대상이 아니다.
- 재시도를 소진한 충돌에서 Spring 예외의 엔티티 이름, JPA 예외의 엔티티 또는 Hibernate 원인의 엔티티 이름으로 Point를 확인하면 `409 POINT_CONFLICT`로 안내한다. 그 외 충돌은 실패 트랜잭션 종료 후 새 조회 트랜잭션에서 본인 주문의 상태를 확인하고 실제 `CONFIRMED`일 때만 `ORDER_ALREADY_CONFIRMED`로 응답한다. 아직 DRAFT이거나 충돌 대상을 확인할 수 없으면 원래 예외를 전달해 기존 `500 INTERNAL_ERROR` 계약으로 처리하며, 재고·잔액 부족으로 바꾸지 않는다. 외부 트랜잭션에 참여하는 경우에는 실패한 경계에서 주문을 다시 조회하지 않는다.

정상 HTTP 경로에서는 commit이 끝난 뒤 성공 응답을 보낸다. 여러 repository의 `save()`는 독립 commit을 수행하지 않으며, 최종 `flush()`도 commit과 구분된다.
