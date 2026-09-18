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
| 브랜드 삭제 가능 여부 | `Brand`+`Product` 조회 결과를 본 application 판단 | 단일 Entity 규칙이 아니라 여러 상품을 함께 살피는 판단이라 도메인 서비스 또는 application에 둔다 |

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
| DELETE | `/api-admin/v1/brands/{brandId}` | - | 삭제 결과 | 삭제 거절 (연결된 미삭제 상품 존재, 재고 0 포함) |
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
| 상품 이름·가격 유효 범위 | 이름: 공백만 있는 문자열 거절 + 최대 100자. 가격: 0 이하 거절 + 최대 `100,000,000`(1억) | 명시적 상한이 없으면 입력 검증 테스트의 기대값을 정할 수 없음. 1억은 임의 상한 — 실제 상품 특성에 따라 재검토 가능 |
| 포인트 잔액 합산 상한 | `PointBalance.amount`는 `long` 사용, 충전 후 합계가 `1,000,000,000`(10억) 초과 시 충전 자체를 거절하고 기존 잔액 유지 | `long` 자체는 오버플로 여지가 거의 없지만, 실습 범위에서 "표현 범위 초과"를 실제로 테스트하려면 임의의 상한이 있어야 함 |

---
*이 문서는 구현하며 판단이 바뀌면 그때그때 갱신한다 (완성 후 고정하는 문서가 아님).*
