# 커머스 기본 기능 설계 (W2)

> **설계의 의도를 객체의 책임과 실행 가능한 규칙으로 표현한다.**

고객·관리자 요청을 `HTTP → application → domain → repository·DB`까지 연결하는 기본 기능(상품·브랜드·좋아요, 포인트·주문, 관리자)의 설계다. API 계약, 오류 계약, 결정 기록 상세는 [AI용 상세](commerce-design.ai.md)에 있다. 브랜드 삭제와 동시성은 [W3](../week3/transaction-concurrency-design.md), 좋아요 수는 [W4](../week4/product-query-design.md), 주문 결제는 [W5](../week5/pg-payment-design.md)에서 바뀌었다.

## 핵심 결정

| 결정 | 이유 |
|---|---|
| 고객과 관리자는 경로 접두사(`/api/v1`, `/api-admin/v1`)로 나누고, 하나의 서버·DB를 쓴다 | 같은 상품도 입력·권한·응답이 다른 별개의 계약이다 |
| 계층은 interfaces → application → domain ← infrastructure이고, `ArchitectureTest`가 금지 의존을 검사한다 | 의존 방향을 문서가 아니라 검사로 지킨다 |
| 포트는 그것을 쓰는 계층이 소유한다(DIP). infrastructure → application은 포트 구현에 한해 허용한다 | domain Service가 쓰는 저장소는 domain에, Facade만 쓰는 캐시는 application에 둔다 |
| 자기 상태의 규칙은 domain 모델이, 다른 객체를 조회해야 하는 검증은 Facade가 맡는다 | 규칙을 한곳에 모으고 모델이 다른 모델을 모르게 한다 |
| Aggregate 사이는 id로 참조하고, 금액·재고·포인트는 값 객체(`Money`, `Stock`, `PointBalance`)로 둔다 | 값 규칙을 한 곳에 모은다 |
| 주문 품목은 주문 당시 상품명·단가를 복사해 저장한다 | 상품이 바뀌거나 삭제돼도 주문 내역이 유지된다 |
| 삭제는 soft delete를 기본으로 하고, 좋아요 같은 관계만 hard delete한다 | 참조 보존이 우선이다 |
| 요청자는 Security 필터 한 곳에서 `X-USER-ID`로 식별하고, 식별이 필요한 경로를 목록으로 관리한다(로컬 실습용) | Facade는 식별된 값만 받는다 |
| 타인 주문은 존재를 숨겨 `NOT_FOUND`로, 소유자가 URL에 드러난 타인 리소스는 `FORBIDDEN`으로 응답한다 | 정보 노출을 막는다 |
| 오류는 클라이언트가 동작을 달리해야 할 때만 별도 코드를 두고, 메시지에 다음 행동을 담는다 | 코드를 늘리지 않으면서 사용자가 고칠 수 있게 한다 |
| 입력 검증은 형식은 Dto(`@Valid`), 값 규칙은 도메인이 한다 | 필수 누락은 조회 전에 400으로 거절한다 |
| 좋아요 등록·취소는 멱등하게 성공한다 | 재요청이 오류가 되지 않게 한다 |

## 구조

```
interfaces.api.{feature}        고객 API          interfaces.api.admin.{feature}  관리자 API (Admin 접두사)
application.{feature}           Facade, dto       domain.{feature}                Model, Service, Repository
infrastructure.{feature}        RepositoryImpl    support.error / config          공통 오류, 보안 설정
```

feature: `brand`, `product`, `like`, `order`, `user`(포인트 API는 `point`).

## 도메인

```
Brand 1 ── N Product        User 1 ── N Like N ── 1 Product
User  1 ── N Order 1 ── N OrderItem(상품명·수량·단가 스냅샷)       User ── 포인트 잔액(PointBalance)
```

## 오류 계약

| 상황 | 코드 |
|---|---|
| 필수 누락·형식·값 범위 위반 | `INVALID_INPUT` (400) |
| 없는 리소스, 타인 주문 | `NOT_FOUND` (404) |
| 이미 확정된 주문 / 재고 부족 / 잔액 부족 / 확정 시 상품 삭제 | `ORDER_ALREADY_CONFIRMED` / `OUT_OF_STOCK` / `INSUFFICIENT_POINT` / `PRODUCT_UNAVAILABLE` (409) |
| 관리자 경계 위반, 타인 리소스 URL | `FORBIDDEN` (403) |

## 알려진 한계

- `X-USER-ID` 헤더는 위조할 수 있어 로컬 실습·테스트 전용이다. 운영 인증은 정하지 않았다.
- 식별 필터는 `CoreException`과 헤더 형식 오류만 공통 응답으로 바꾸고, DB 장애 같은 예외는 기본 500으로 나갈 수 있다.
- 식별 경로 목록에 등록하지 않으면 식별 없이 열려, 그런 API마다 "헤더 누락 400" 테스트가 필요하다.
- infrastructure → interfaces 의존은 ArchUnit 규칙이 없고 관례로만 막는다.
