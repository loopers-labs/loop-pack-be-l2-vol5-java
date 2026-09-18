# commerce-api 대표 흐름

과제가 제시한 세 흐름을 요청자부터 DB까지 계층을 따라 그린 것이다. 흐름마다 기대값과 대표 오류를 함께 적는다. 근거는 [요구사항 문서](./requirements.md)의 요구사항 ID와 정책 ID를 가리키고, 계층의 역할은 [설계 문서](./commerce-api-design.md)의 아키텍처를, 도메인 객체는 [도메인 관계](./domain-relations.md)를 따른다.



## 1. 관리자 변경 → 고객 조회


| 단계     | 요청                                                  | 기대값                                     |
| ------ | --------------------------------------------------- | --------------------------------------- |
| 준비     | —                                                   | 상품 P는 가격 3,000원이고 삭제되지 않았다.             |
| 관리자 수정 | `PUT /api-admin/v1/products/{productId}` (가격 3,500) | 가격이 3,500원으로 바뀐다.                       |
| 고객 조회  | `GET /api/v1/products/{productId}`                  | 가격 3,500원이 보인다. (R-ADMIN-09)            |
| 관리자 삭제 | `DELETE /api-admin/v1/products/{productId}`         | 삭제 여부만 바뀌고 기록은 남는다. (R-ADMIN-14)        |
| 고객 조회  | `GET /api/v1/products/{productId}`                  | 없는 대상 오류 (R-CATALOG-07, R-ADMIN-12)     |
| 관리자 조회 | `GET /api-admin/v1/products/{productId}`            | 삭제 여부와 함께 보인다. (P-ADMIN-07)             |
| 대표 오류  | `ADMIN` 역할이 없거나 식별되지 않은 관리자 요청                      | 필터가 403으로 거절하고, 상품은 그대로다. (R-ACCESS-04) |


```mermaid
sequenceDiagram
    autonumber
    actor A as 관리자
    actor C as 고객
    participant F as 관리자 접근 필터
    participant IF as interfaces
    participant AP as application
    participant DM as domain
    participant IN as infrastructure
    participant DB

    A->>F: PUT /api-admin/v1/products/{productId} (이름, 가격 3,500)
    F->>F: ADMIN 역할 확인 (R-ACCESS-04)
    F->>IF: 통과
    IF->>AP: 상품 수정 요청
    AP->>IN: 상품 찾기
    IN->>DB: 조회
    AP->>DM: Product에 이름과 가격 수정 요청
    DM-->>AP: 삭제된 상품이거나 값이 범위를 벗어나면 거절 (R-ADMIN-06, R-ADMIN-13)
    AP->>IN: 저장
    IN->>DB: 반영
    AP-->>IF: 수정된 상품
    IF-->>A: 성공, 가격 3,500

    C->>IF: GET /api/v1/products/{productId} (X-USER-ID)
    IF->>AP: 상품 상세 조회
    AP->>IN: 삭제되지 않은 상품과 브랜드, 좋아요 수 찾기
    IN->>DB: 조회
    AP-->>IF: 상품 정보
    IF-->>C: 가격 3,500 (R-ADMIN-09)

    A->>F: DELETE /api-admin/v1/products/{productId}
    F->>IF: 통과
    IF->>AP: 상품 삭제 요청
    AP->>IN: 상품 찾기
    AP->>DM: Product에 삭제 요청
    DM-->>AP: 이미 삭제된 상품이면 거절 (P-ADMIN-06)
    AP->>IN: 저장
    IN->>DB: 삭제 여부만 반영, 기록은 남김 (R-ADMIN-14)
    IF-->>A: 성공

    C->>IF: GET /api/v1/products/{productId} (X-USER-ID)
    IF->>AP: 상품 상세 조회
    AP->>IN: 삭제되지 않은 상품 찾기
    IN-->>AP: 없음
    AP-->>IF: 없는 대상
    IF-->>C: 없는 대상 오류 (R-CATALOG-07, R-ADMIN-12)

    A->>F: GET /api-admin/v1/products/{productId}
    F->>IF: 통과
    IF->>AP: 관리자 상품 상세 조회
    AP->>IN: 삭제 여부와 관계없이 상품 찾기
    IF-->>A: 상품 P, 삭제됨 (P-ADMIN-07)

    Note over A,F: 대표 오류: ADMIN 역할이 없거나 식별되지 않은 요청은 필터가 403으로 거절하고 interfaces에 닿지 않는다 (R-ACCESS-04)
```

## 2. 좋아요 등록·취소


| 단계       | 요청                                          | 기대값                                               |
| -------- | ------------------------------------------- | ------------------------------------------------- |
| 준비       | —                                           | 상품 P의 좋아요 수는 0이다.                                 |
| 등록       | `POST /api/v1/products/{productId}/likes`   | 성공. 좋아요 수 0 → 1                                   |
| 같은 등록 반복 | `POST /api/v1/products/{productId}/likes`   | 성공. 새로 만들지 않아 좋아요 수는 1 그대로 (R-LIKE-02, P-LIKE-01) |
| 상품 조회    | `GET /api/v1/products/{productId}`          | 좋아요 수 1 (R-LIKE-05, R-LIKE-06)                    |
| 취소       | `DELETE /api/v1/products/{productId}/likes` | 성공. 좋아요 수 1 → 0                                   |
| 같은 취소 반복 | `DELETE /api/v1/products/{productId}/likes` | 성공. 좋아요 수 0 그대로 (P-LIKE-01)                       |
| 대표 오류    | 삭제된 상품에 등록                                  | 없는 대상 오류. 좋아요 수 그대로 (R-LIKE-07, P-LIKE-02)        |


```mermaid
sequenceDiagram
    autonumber
    actor C as 고객
    participant IF as interfaces
    participant AP as application
    participant DM as domain
    participant IN as infrastructure
    participant DB

    C->>IF: POST /api/v1/products/{productId}/likes (X-USER-ID)
    IF->>AP: 좋아요 등록 요청 (고객, 상품)
    AP->>IN: 고객과 상품 찾기
    IN->>DB: 조회
    AP->>DM: Product에 삭제 여부 확인
    DM-->>AP: 삭제되었으면 없는 대상으로 거절 (R-LIKE-07, P-LIKE-02)
    AP->>DM: 좋아요 중복 여부에 이미 있는지 확인
    DM-->>AP: 없음
    AP->>DM: Like 생성
    AP->>IN: 저장
    IN->>DB: 반영
    IF-->>C: 성공

    C->>IF: 같은 등록을 한 번 더
    IF->>AP: 좋아요 등록 요청
    AP->>DM: 좋아요 중복 여부에 이미 있는지 확인
    DM-->>AP: 있음 (R-LIKE-02)
    AP-->>IF: 새로 만들지 않고 성공 (P-LIKE-01)
    IF-->>C: 성공

    C->>IF: GET /api/v1/products/{productId} (X-USER-ID)
    IF->>AP: 상품 상세 조회
    AP->>IN: 상품과 브랜드 찾기, 좋아요 관계 세기
    IN->>DB: 조회
    IF-->>C: 좋아요 수 1 (R-LIKE-05, R-LIKE-06)

    C->>IF: DELETE /api/v1/products/{productId}/likes (X-USER-ID)
    IF->>AP: 좋아요 취소 요청 (고객, 상품)
    AP->>IN: 고객과 상품으로 Like 찾기
    IN-->>AP: Like
    AP->>DM: Like에 취소 요청 (고객)
    DM-->>AP: 본인의 좋아요가 아니면 거절 (R-LIKE-04)
    AP->>IN: 없애기
    IN->>DB: 반영
    IF-->>C: 성공, 좋아요 수 0

    C->>IF: 같은 취소를 한 번 더
    IF->>AP: 좋아요 취소 요청
    AP->>IN: 고객과 상품으로 Like 찾기
    IN-->>AP: 없음
    AP-->>IF: 성공 (P-LIKE-01)
    IF-->>C: 성공

    Note over C,DB: 상품이 삭제된 뒤에도 남아 있는 자신의 좋아요는 취소할 수 있다 (R-LIKE-08)
```

## 3. 포인트 충전 → 주문 확정


| 단계    | 요청                                           | 기대값                                                                                     |
| ----- | -------------------------------------------- | --------------------------------------------------------------------------------------- |
| 준비    | —                                            | 고객 잔액 0원. 상품 A는 2,000원·재고 5, 상품 B는 3,000원·재고 3                                          |
| 충전    | `POST /api/v1/points/charge` (amount 10,000) | 잔액 0 → 10,000 (R-POINT-06)                                                              |
| 주문 생성 | `POST /api/v1/orders` (A 2개, B 1개)           | `DRAFT`, 합계 7,000. 재고와 잔액은 그대로 (R-ORDER-03, R-ORDER-04)                                 |
| 주문 확정 | `POST /api/v1/orders/{orderId}/confirm`      | `CONFIRMED`, 결제액 7,000. 재고 A 5 → 3, B 3 → 2. 잔액 10,000 → 3,000 (R-ORDER-11, R-ORDER-12) |
| 잔액 조회 | `GET /api/v1/points`                         | 3,000 (R-POINT-02)                                                                      |
| 주문 조회 | `GET /api/v1/orders/{orderId}`               | `CONFIRMED`, 품목 A 2개·B 1개, 합계와 결제액 7,000 (R-ORDER-14)                                   |
| 대표 오류 | 잔액이 주문 금액보다 적을 때 확정                          | 잔액 부족으로 거절. 재고, 잔액, 주문 상태 모두 그대로 (R-ORDER-09, R-ORDER-10)                               |


```mermaid
sequenceDiagram
    autonumber
    actor C as 고객
    participant IF as interfaces
    participant AP as application
    participant DM as domain
    participant IN as infrastructure
    participant DB

    C->>IF: POST /api/v1/points/charge (X-USER-ID, amount 10,000)
    IF->>IF: 식별 정보와 입력 형식 확인 (R-ACCESS-05, R-POINT-04)
    IF->>AP: 충전 요청 (고객, 10,000)
    AP->>IN: 고객 찾기
    IN->>DB: 조회
    AP->>DM: User에 충전 요청
    DM-->>AP: 잔액 10,000 (R-POINT-06)
    AP->>IN: 저장
    IN->>DB: 반영
    IF-->>C: 성공, 잔액 10,000

    C->>IF: POST /api/v1/orders (X-USER-ID, A 2개, B 1개)
    IF->>AP: 주문 생성 요청
    AP->>IN: 상품 A, B 찾기
    IN->>DB: 조회
    AP->>DM: 각 Product에 삭제 여부와 지금 이름·가격 확인
    AP->>DM: Order 생성 요청 (A 2개 2,000원, B 1개 3,000원)
    DM-->>AP: DRAFT 주문, 합계 7,000 (R-ORDER-02, R-ORDER-03)
    AP->>IN: 저장
    IN->>DB: 반영
    IF-->>C: 성공, DRAFT, 합계 7,000. 재고와 잔액은 그대로 (R-ORDER-04)

    C->>IF: POST /api/v1/orders/{orderId}/confirm (X-USER-ID)
    IF->>AP: 주문 확정 요청 (고객, 주문)
    AP->>IN: 주문, 상품 A·B, 구매자 찾기
    IN->>DB: 조회
    AP->>DM: 도메인 서비스 주문 확정에 확정 요청
    alt 모두 통과
        DM->>DM: 재고 A 5→3, B 3→2 차감. 잔액 10,000→3,000 결제. CONFIRMED, 결제액 7,000
        DM-->>AP: 확정된 주문
        AP->>IN: 바뀐 주문, 상품 A·B, 고객을 한 번에 저장
        IN->>DB: 한 트랜잭션으로 반영
        AP-->>IF: 확정 결과
        IF-->>C: 성공, CONFIRMED, 결제액 7,000 (R-ORDER-11, R-ORDER-12)
    else 잔액이 주문 금액보다 적음
        DM-->>AP: 아무것도 바꾸지 않고 거절 (R-ORDER-09, R-ORDER-10)
        AP-->>IF: 잔액 부족
        IF-->>C: 잔액 부족 오류. 재고, 잔액, 주문 상태 그대로
    end

    C->>IF: GET /api/v1/points (X-USER-ID)
    IF->>AP: 잔액 조회
    AP->>IN: 고객 찾기
    IN->>DB: 조회
    IF-->>C: 잔액 3,000 (R-POINT-02)

    C->>IF: GET /api/v1/orders/{orderId} (X-USER-ID)
    IF->>AP: 주문 상세 조회
    AP->>IN: 고객의 주문 찾기
    IN->>DB: 조회
    IF-->>C: CONFIRMED, 품목 A 2개·B 1개, 결제액 7,000 (R-ORDER-14)
```
