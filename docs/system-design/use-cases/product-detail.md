# 고객 상품 상세 조회

고객의 상품 상세 조회 결과를 Application에서 조합하는 책임을 정리한다. HTTP 요청·응답 계약은 [API 계약](../api-contracts.md), 도메인 규칙은 [도메인 모델](../domain-model.md)을 기준으로 한다.

## 조회 흐름

고객이 활성 상품·브랜드 정보와 좋아요 수를 확인하는 흐름이다.

```mermaid
sequenceDiagram
    participant C as 고객
    participant CAC as 고객 상품 API
    participant APP as Application
    participant PR as Product Repository
    participant BR as Brand Repository
    participant LR as Like Repository
    participant DB as DB

    Note over PR,DB: Repository는 Domain의 조회·저장 계약이며,<br/>실행 시 Infrastructure의 JPA 구현체가 주입된다.

    C->>CAC: 상품 상세 조회 요청
    CAC->>APP: 상품 상세 조회 유스케이스 호출
    APP->>PR: 삭제되지 않은 Product 조회
    PR->>DB: 상품 조회
    DB-->>PR: Product
    PR-->>APP: Product
    APP->>BR: 활성 Brand 조회
    BR->>DB: 일반 조회 (잠금 없음)
    DB-->>BR: Brand
    BR-->>APP: Brand
    APP->>LR: Product의 Like 수 조회
    LR->>DB: Like 수 조회
    DB-->>LR: Like 수
    LR-->>APP: Like 수
    APP-->>CAC: 고객용 상품 상세 조회 결과
    CAC-->>C: 성공 응답
```

## 결과 조합 책임

상품 상세 조회는 각 도메인의 조회 결과를 Application에서 조합한다.

| 단계 | 담당 | 결과 |
| --- | --- | --- |
| 상품 조회 | `ProductRepository` | `Product` |
| 브랜드 조회 | `BrandRepository` | `Brand` |
| 좋아요 수 조회 | `LikeRepository` | 좋아요 수 |
| 결과 조합 | `ProductFacade` | `CustomerProductInfo` |
| API 응답 변환 | interfaces | 응답 DTO |

`ProductFacade`는 활성 `Product`와 `Brand`, 해당 상품의 좋아요 수로 `CustomerProductInfo`를 만든다. interfaces 계층은 이를 API 응답 DTO로 변환한다.

각 도메인 모델은 자신의 상태와 규칙을 맡고, Repository는 해당 도메인의 조회·저장 계약을 제공한다. 고객 응답에 필요한 조합은 Application에 두므로, 응답 필드가 바뀌면 `CustomerProductInfo`와 응답 DTO를 갱신한다. 브랜드 로고처럼 Brand 자체의 상태를 추가할 때는 Brand 모델도 함께 변경한다.

여러 Repository를 호출하고 Application에서 결과를 조합하는 비용을 감수한다.
