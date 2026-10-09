# 3주차 설계: 트랜잭션 경계와 동시성 제어

이 문서는 `docs/week2/design.md` 를 기준으로 **3주차에 바뀌거나 추가된 것만** 적는다. 절 번호(예: 2주차 2.4), 규칙 ID(BRD-02, ORD-10 …), 결정 번호(D-xx)는 2주차 문서를 그대로 이어 쓴다.

## 1. 범위

| 흐름 | 같은 성공 · 실패로 묶을 것 | 보존할 것 |
|---|---|---|
| 브랜드 일괄 삭제 | 브랜드와 연결된 미삭제 상품(재고 0 포함)의 삭제 | 다른 브랜드 · 상품, 기존 주문의 품목 · 수량 · 단가 · 총액 · 결제 결과 |
| 주문 최초 확정 | 재고 차감, 포인트 차감, CONFIRMED 변경, 결제액 · 결제 시각 저장 | 실패한 주문의 DRAFT, 변경 전 재고 · 잔액 |
| 같은 재고 · 잔액 · 주문의 동시 변경 | 읽기 · 검사 · 변경을 보호하는 행과 SQL | 성공한 업무와 최종 재고 · 잔액의 일치 |

- **가정: 인기 상품에는 동시 주문이 몰린다.** 동시성 전략은 이 가정 위에서 고른다(4.1).
- **범위 밖:** 환불, 새 요청키(멱등키) 체계, 커넥션 풀 고갈 대응(8장 한계로 기록), DB 밖의 대기열(Redis 등).

## 2. 브랜드 일괄 삭제

### 2.1 BRD-02 새 표현

2주차 3.2의 BRD-02 행을 다음으로 바꾼다.

| 규칙 ID | 유스케이스 | 규칙 | 필요한 상태의 주인 | 분류 |
|---|---|---|---|---|
| BRD-02 | 관리자 상품 생성 / 관리자 브랜드 삭제 | 삭제되지 않은 상품은 삭제되지 않은 브랜드에 속한다. 상품을 생성할 때 브랜드가 삭제되지 않았는지 확인하고, 브랜드를 삭제하면 그 브랜드의 삭제되지 않은 상품(재고 0 포함)을 모두 함께 삭제한다 | Brand / Product | 유스케이스의 협력 |

- **BRD-02:** 상품 생성과 브랜드 삭제는 같은 규칙의 두 입구다. 브랜드 삭제 입구는 "상품이 남았으면 거절"(2주차)에서 "**함께 삭제**"로 바뀌었다.
  - 불변식 문장은 그대로 두고 지키는 방법만 바꾼다.
  - 브랜드와 상품의 삭제는 한 트랜잭션으로 묶는다. 하나라도 실패하면 브랜드와 상품 모두 삭제 전 상태로 남는다.
  - 다른 브랜드의 상품과 과거 주문은 바꾸지 않는다. 주문 품목은 주문 시점의 스냅샷이고 상품을 식별자로만 가리킨다(2주차 2.3).
  - 삭제된 상품의 사용 제한은 기존 규칙이 그대로 지킨다: PRD-02(수정), PRD-04(재고 변경), LIK-01(좋아요), ORD-01(주문 생성), ORD-09(주문 확정). 좋아요 취소는 LIK-03에 따라 계속 허용한다.
  - 상품 수정은 PRD-03 때문에 이 규칙을 깨지 않는다. 복원 기능이 생기면 입구가 늘어난다.
- **"미노출"이 아니라 "삭제"로 쓴다.** 상품은 논리 삭제(`deletedAt`)이고, 삭제된 상품은 고객 화면뿐 아니라 관리자 목록 · 상세, 좋아요, 주문, 수정, 재고 변경에서도 빠진다(2주차 2.5). "미노출"은 관리자는 계속 관리하는 별도 상태로 읽힐 수 있다.

### 2.2 흐름

```
DELETE /api-admin/v1/brands/{brandId}
 → [관리자 접근 확인]                                   interfaces (기존 규칙 유지)
 ── BrandFacade 트랜잭션 ──
 → 살아 있는 브랜드 잠금 조회 (FOR UPDATE)              → BrandService     없으면 BRAND_NOT_FOUND
 → brand.delete()                                       메모리 변경, commit 때 UPDATE
 → 브랜드의 미삭제 상품 일괄 삭제 (bulk UPDATE)         → ProductService → ProductRepository
 commit: 브랜드 UPDATE flush
```

- **트랜잭션은 `BrandFacade.deleteBrand` 가 연다.** 브랜드와 상품 두 도메인을 다루므로 2주차 4.4의 "여러 도메인을 다루면 Facade가 트랜잭션을 연다"를 따른다(D-11). 도메인 서비스의 `@Transactional` 은 `REQUIRED` 로 합류한다.
- **브랜드를 먼저 확인한다.** 없거나 이미 삭제된 브랜드는 상품을 건드리기 전에 `BRAND_NOT_FOUND` 로 끝난다(D-27 유지).
- **상품 일괄 삭제는 `ProductService` 를 거친다.** 다른 도메인의 대상은 그 도메인의 `~Service` 로 다룬다(D-31). SQL은 저장 약속(`ProductRepository`)에 둔다.
- **연결 상품이 없는 브랜드도 정상 처리한다.** bulk UPDATE의 변경 행 수가 0이어도 브랜드는 삭제된다.
- **실제 SQL 순서:** 상품 bulk UPDATE가 실행 즉시 나가고, 브랜드 UPDATE는 commit 직전 flush 때 나간다. 메서드 호출 순서가 아니라 영속성 컨텍스트의 flush 시점이 정한다.
- **2주차 D-32는 대체된다.** 삭제 입구가 `BrandRepository.hasActiveProduct` 의 질문이 아니게 되므로, 이 메서드와 `infrastructure` 의 상품 조회 쿼리를 지운다.

### 2.3 상품 삭제는 bulk UPDATE로 한다

```sql
UPDATE product SET deleted_at = :now, updated_at = :now
WHERE brand_id = :brandId AND deleted_at IS NULL
```

- **이유:** 브랜드의 상품 수가 많아질 때 SQL 왕복을 N번에서 1번으로 줄인다.
  - 줄지 않는 것: 잠그는 행 수. 어느 방식이든 그 브랜드의 상품 행은 모두 잠기고 commit까지 유지된다.
- **잠금 순서는 실행 계획에 기댄다.** InnoDB는 행을 읽어 가며 잠근다. `brand_id` 보조 인덱스는 내부적으로 `(brand_id, id)` 순이라, 인덱스를 타면 ID 오름차순으로 잠긴다(4.3의 규칙과 맞음).
  - 인덱스를 타지 않고 전체 스캔하면 REPEATABLE READ에서 **다른 브랜드의 상품 행까지** 잠가 무관한 주문 확정을 막는다.
  - **구현할 때 `brand_id` 인덱스 존재와 `EXPLAIN` 결과를 확인한다.** 테스트는 `ddl-auto: create` 라 FK와 함께 인덱스가 생기지만, 운영은 `ddl-auto: none` 이라 스키마 관리 쪽 확인이 필요하다.
- **`deleted_at` · `updated_at` 은 `Clock` 기준 시각으로 쿼리에서 직접 설정한다.** bulk UPDATE는 엔티티를 거치지 않아 `BaseEntity` 의 `@PreUpdate` 가 실행되지 않는다. 시각은 주문처럼 `Clock` 에서 정한다(2주차 2.3).
  - 브랜드는 엔티티 행동 `brand.delete()`(`BaseEntity`)를 그대로 쓰므로 시스템 시각을 쓴다. 같은 요청에서 브랜드와 상품의 삭제 시각 출처가 다르다는 점은 감수한다.
- **영속성 컨텍스트를 비우지 않는다(`clearAutomatically` 사용 안 함).**
  - 브랜드는 이미 조회된 관리 엔티티다. clear하면 분리되어 이후의 브랜드 UPDATE가 예외 없이 사라진다.
  - 이 흐름은 bulk 뒤에 상품 엔티티를 다시 읽지 않으므로 오래된 엔티티 문제가 없다.
  - 이 함정은 "새 트랜잭션에서 브랜드 · 상품이 모두 삭제됐는지 재조회"하는 테스트가 잡는다(6.3).

| 버린 대안 | 얻을 수 있던 것 | 포기한 이유 |
|---|---|---|
| 엔티티 반복 (`FOR UPDATE ... ORDER BY id` 조회 후 `product.delete()`) | 엔티티 행동과 `@PreUpdate` 를 그대로 거침. 강의가 "이해하기 쉽다"고 소개한 방식 | 상품 수만큼 UPDATE가 나간다 |
| 잠금 조회 후 bulk UPDATE | 잠금 순서가 코드에 드러나 보임 | `ORDER BY` 는 결과 순서일 뿐 잠금 순서를 보장하지 않는다(정렬 전에 스캔하며 잠금). 결국 실행 계획에 기대는 것은 같고, id 목록을 메모리에 읽는 비용만 늘어난다 |

### 2.4 2주차 2.5 근거 3번의 갱신

| # | 근거 | 물리 삭제였다면 |
|---|---|---|
| 3 | 브랜드를 삭제하면 연결 상품도 함께 삭제하지만, 과거 주문 품목 · 좋아요가 그 상품의 식별자를 계속 가리킨다 (BRD-02) | 상품 행이 사라져 기존 참조가 깨진다. 주문까지 연쇄 삭제하면 과거 거래 사실이 사라진다 |

- **DB의 cascade로 업무 결과를 정하지 않는다.** 연쇄 삭제가 주문까지 지우면 트랜잭션은 그 잘못된 결과를 원자적으로 commit할 뿐이다.

### 2.5 계약 변경

| API | 성공 | 대표 오류 |
|---|---|---|
| `DELETE /api-admin/v1/brands/{brandId}` | 200 (연결 상품 함께 삭제) | `BRAND_NOT_FOUND` 404 |

- **`BRAND_HAS_PRODUCTS` 를 지운다.** 삭제 경로 말고 쓰는 곳이 없다.
- 함께 정리하는 곳
  - 제품 코드: `BrandErrorCode`, `ErrorStatusMapper`, `BrandService.delete` 의 검사, `BrandRepository.hasActiveProduct`(+ Impl, JPA 쿼리), `AdminBrandV1ApiSpec` 설명
  - 테스트: `BrandServiceTest` 거절 테스트, `AdminBrandV1ApiE2ETest` 409 테스트, `BrandRepositoryIntegrationTest` 의 `hasActiveProduct` 테스트, `FakeBrandRepository`
  - 2주차 문서: API 표(6.5), 삭제 오류 표, 오류 코드 표
  - 이 테스트들은 계약이 바뀌어 지우는 것이다. 검사를 우회하는 변경이 아니다.

## 3. 주문 확정의 원자성

**확정은 이미 `OrderFacade.confirmOrder` 한 트랜잭션이다(2주차 5.3, D-11).** 호출되는 도메인 서비스는 모두 `REQUIRED` 로 합류하고, 거절은 `CoreException`(RuntimeException)으로 Facade 밖까지 나가 전체가 rollback된다. 예외를 잡아 정상 반환하는 곳, 자기 호출, `REQUIRES_NEW` 는 없다.

3주차에 바뀌는 것은 원자성이 아니라 **동시 요청의 보호**(4장)와 **재시도 층**(4.4)이다.

- **확인 순서는 2주차 그대로다(D-16 유지).** 만료 → 상품 삭제 여부 → 재고 → 잔액. 재고와 잔액이 둘 다 부족하면 재고 부족을 알린다.
  - **버린 대안: 잔액을 먼저 확인한다.** 잔액이 부족한 요청은 상품 잠금 대기열에 서지 않아 대기열이 짧아진다.
  - **포기한 이유: 쓸데없는 충전을 유도한다.** 품절이면서 잔액도 부족한 고객이 `INSUFFICIENT_POINT` 를 받고 충전한 뒤 다시 시도하면 그제야 `OUT_OF_STOCK` 을 받는다. 사지 못할 주문을 위해 충전하게 된다.

## 4. 동시성 제어

### 4.1 대상별 전략

| 대상 | 누가 같은 행을 동시에 바꾸나 | 예상 충돌 | 전략 |
|---|---|---|---|
| 재고 (`product` 행) | 서로 다른 고객 다수 (인기 상품) | 높음 | **비관적 잠금** `SELECT ... FOR UPDATE` |
| 포인트 (`points` 행) | 같은 고객 본인 (창 두 개에서 동시 결제 · 충전) | 낮음 | **낙관적 잠금** `@Version` + 재시도 |
| 주문 상태 (`orders` 행) | 같은 주문의 중복 확정 (중복 클릭 · 재전송) | 낮음 | **낙관적 잠금** `@Version` + 재시도. 상태만 보호 |
| 브랜드 (`brand` 행) | 관리자의 수정 · 삭제, 상품 등록 | 낮음 | **비관적 잠금** (수정 · 삭제 `FOR UPDATE`, 상품 등록 `FOR SHARE`) |

#### 재고: 비관적 잠금

- **이유:** 인기 상품에 요청이 몰리면 낙관적 잠금은 한 라운드에 1건만 성공하고 나머지가 rollback 후 다시 시도한다. 시도 횟수가 요청 수의 제곱에 가깝게 늘고, 재시도 한도(총 3회)를 넘긴 요청은 재고가 남아 있어도 실패한다. 비관적 잠금은 줄을 서서 각자 한 번씩 기다리면 끝난다.
- **잠금을 쥐는 구간이 짧다.** 확정 트랜잭션에는 외부 호출이 없고 SELECT · UPDATE 몇 개뿐이다(수 ms~수십 ms). 실무에서 비관적 잠금을 꺼리는 주된 이유(잠금을 쥔 채 외부 호출)가 지금 구조에는 없다. 외부 결제 호출이 이 트랜잭션에 들어오면 다시 판단한다.
- **확인한 비용:** 잠금을 기다리는 요청은 커넥션을 쥔 채 기다린다(8장 한계).
- **재고 위치는 `product` 행에 그대로 둔다.** 2주차 2.4 "다시 검토할 조건"의 "동시성을 처리해야 할 때"를 검토했다.
  - 재고 행을 잠그면 이름 · 가격 수정도 막힌다. 하지만 상품 수정은 관리자 경로라 드물고, 막히는 편이 오히려 판매 중인 상품을 보호한다.
  - 별도 엔티티로 분리하면 삭제 상태가 두 곳에 생기는 2주차의 비용이 그대로 남는다.

#### 포인트: 낙관적 잠금 + 재시도

- **이유:** 포인트 행은 같은 고객의 요청끼리만 겹친다. 충돌이 드물어 대기 없이 진행하고, 충돌하면 다시 판단하는 편이 비용이 작다.
- **재시도는 주문 확정 유스케이스 전체를 다시 실행한다.** 실패한 트랜잭션은 통째로 rollback되므로 포인트만 따로 재시도할 수 없다. 재시도가 없으면 과제의 기대 결과("포인트 경쟁: 잔액 부족 1 · 기술 오류 0", "충전과 결제: 둘 다 성공")가 나오지 않는다.
- 충전도 같은 재시도 구조를 거친다(4.4).

#### 주문 상태: 낙관적 잠금, 상태만 보호

- **이유:** 포인트 때문에 확정 재시도 층이 이미 필요하므로 추가 비용이 거의 없다. 중복 확정은 드물어 헛일 비용도 작다.
- **동작:** 중복 확정 요청은 상품 잠금을 기다린 뒤에도 일반 읽기로는 주문이 DRAFT로 보인다. commit 때 주문 버전 충돌로 실패하고, 재시도에서 CONFIRMED를 읽어 기존 `ORDER_ALREADY_CONFIRMED` 로 끝난다. 기존 상태 오류 계약이 유지된다.

| 버린 대안 | 얻을 수 있던 것 | 포기한 이유 |
|---|---|---|
| 재고에 낙관적 잠금 + 재시도 | 잠금 대기가 없고, 재시도 대기 동안 커넥션을 반납한다 | 몰리는 상황에서 재시도가 폭증하고 한도 초과가 재고 부족과 섞일 위험이 있다. 비교 실험(6.6)으로 차이를 확인한다 |
| 재고에 조건부 갱신 (`stock = stock - ? WHERE stock >= ?`) | 검사와 차감이 한 문장. 잠금 구간이 짧다 | "0 < 수량 ≤ 재고" 규칙이 `Product.decrease` 에서 SQL로 옮겨가 "규칙은 각 객체가 지킨다"(D-29)와 어긋난다 |
| 포인트에 비관적 잠금 | 재시도 층이 필요 없다 | 충돌이 드문 행에 매번 잠금 대기 비용을 낸다 |
| 주문 행을 첫 관문으로 잠금 (`FOR UPDATE`) | 중복 확정이 시작에서 기다렸다가 재시도 없이 바로 상태 오류로 끝난다 | 재시도 층과 별개로 잠금 규칙이 하나 더 생긴다. 트랜잭션 시간이 늘어나는 것은 아니고, 같은 주문의 두 번째 요청만 기다린다 |

### 4.2 같은 행을 바꾸는 경로

**같은 행을 바꾸는 경로 중 하나라도 보호 규칙을 우회하면 안전하지 않다.** Hibernate는 기본 설정에서 변경 감지 UPDATE에 **모든 컬럼**을 다시 쓴다. 재고를 건드리지 않는 상품 수정도 오래 읽어 둔 `stock` 을, 브랜드 수정도 오래 읽어 둔 `deleted_at` 을 다시 쓴다.

| 행 | 경로 | 보호 |
|---|---|---|
| `product` | 주문 확정 (재고 차감) | `FOR UPDATE`, ID 오름차순, 한 번에 조회 |
| `product` | 관리자 재고 설정 (`ProductService.changeStock`) | `FOR UPDATE` |
| `product` | 관리자 상품 수정 (`ProductService.update`) | `FOR UPDATE` |
| `product` | 관리자 상품 삭제 (`ProductService.delete`) | `FOR UPDATE` |
| `product` | 브랜드 일괄 삭제 | bulk UPDATE (2.3) |
| `points` | 포인트 충전 (`PointService.charge`) | `@Version`, 재시도 |
| `points` | 주문 확정 (결제) | `@Version`, 재시도 |
| `orders` | 주문 확정 | `@Version`, 재시도 |
| `brand` | 관리자 브랜드 수정 (`BrandService.update`) | `FOR UPDATE` |
| `brand` | 브랜드 일괄 삭제 | `FOR UPDATE` |
| `brand` | 상품 등록 (`AdminProductFacade.createProduct`, 브랜드를 바꾸지 않고 기댐) | `FOR SHARE` |

- **잠금 조회는 일반 조회와 별도의 메서드로 둔다.** 고객 조회 화면까지 잠그지 않는다. 기존 `getActive~` 는 그대로 두고 잠금용 메서드를 추가한다.
- **상품 수정 경로도 잠그는 이유(버린 대안: `@DynamicUpdate`):** 바뀐 컬럼만 쓰면 이름 · 가격 수정은 `stock` 을 덮지 않는다. 하지만 재고 설정(절댓값)은 여전히 잠금이 필요하고, "어느 경로가 안전한가"를 컬럼 단위로 따져야 한다. 같은 행은 같은 규칙으로 지키는 편이 설명하기 명료하다.
- **브랜드 행을 잠그는 이유 (반례):**
  - 수정 + 삭제: 수정이 오래된 `deleted_at = NULL` 을 다시 써서 **브랜드가 되살아나고 상품은 삭제된 채 남는다.**
  - 등록 + 삭제: 등록이 오래된 스냅샷으로 "살아 있음"을 보고 INSERT해 **삭제된 브랜드에 살아 있는 상품**이 남는다(BRD-02 위반).
  - FK는 막지 못한다. INSERT의 FK 확인이 brand 행에 공유 잠금을 걸어 잠깐 줄을 세우지만, 논리 삭제라 brand 행은 여전히 존재해 확인을 통과한다.
- **상품 등록은 `FOR SHARE` 로 참여한다.** "브랜드를 바꾸지는 않지만 내가 끝날 때까지 바뀌면 안 된다"를 정확히 표현한다. 같은 브랜드의 상품 등록끼리는 서로 막지 않고, 브랜드 수정 · 삭제만 기다리게 한다.
  - 상품 등록은 brand 행을 쓰지 않아 공유 잠금을 배타 잠금으로 올릴 일이 없다. 그래서 공유 잠금의 전형적인 교착이 생기지 않는다.
  - JPA `PESSIMISTIC_READ` 는 MySQL 8에서 `... for share` 로 나가는 것을 테스트 SQL 로그로 확인했다.

| 버린 대안 (brand 행) | 포기한 이유 |
|---|---|
| `@Version` | 브랜드에는 재시도 층이 없어 충돌이 그대로 409로 나간다. 재시도 없는 유일한 낙관적 대상이 된다 |
| `@DynamicUpdate` | 되살아나지는 않지만 삭제된 브랜드의 이름이 바뀌는 일이 남는다(BRD-01을 조용히 어김) |
| 상품 등록도 `FOR UPDATE` | 잠금 종류는 하나로 통일되지만, 브랜드를 바꾸지 않는데도 배타 잠금이라 같은 브랜드의 등록이 직렬화된다 |

### 4.3 잠금 순서

**자원 종류 사이: brand → product. 같은 종류의 여러 행: ID 오름차순.**

| 경로 | 잠금 순서 |
|---|---|
| 브랜드 일괄 삭제 | brand `FOR UPDATE` → product (bulk, 인덱스 순 = ID 오름차순) |
| 상품 등록 | brand `FOR SHARE` → product INSERT |
| 브랜드 수정 | brand `FOR UPDATE` |
| 주문 확정 | product `FOR UPDATE` (ID 오름차순) → commit 때 orders · product · points UPDATE |
| 상품 수정 · 재고 설정 · 삭제 | product `FOR UPDATE` (한 행) |
| 포인트 충전 | commit 때 points UPDATE (한 행) |

- brand를 잠그는 경로는 모두 brand를 먼저 잡고, 상품만 잠그는 경로는 brand를 잡지 않는다. 여러 상품을 잠그는 경로(주문 확정, 브랜드 일괄 삭제)는 모두 ID 오름차순이다. 그래서 서로 기다리는 고리가 생기지 않는다.
- 순서를 맞춰 교착 위험을 줄일 뿐, 모든 교착이 사라진다고 단정하지 않는다. 교착이 나면 이 규칙이 어딘가에서 지켜지지 않았다는 신호로 본다(4.5).

#### 주문 확정 안의 순서

```
확정 요청 → OrderConfirmRetrier (@Retryable)
          ── OrderFacade 트랜잭션 ──
          → 주문 + 품목 조회 (일반 읽기, @Version)          ORD-06, ORD-07, ORD-08
          → 품목의 상품들 잠금 조회 (ID 오름차순, 한 번에)    product FOR UPDATE
          → 품목 순서대로 삭제 여부 확인 · product.decrease   ORD-09, ORD-10, PRD-06
          → Point 조회 (일반 읽기, @Version) → point.pay      ORD-11, PNT-03
          → order.confirm(결제액, 현재 시각)                   ORD-12
          commit: orders · product · points UPDATE flush
```

- **주문을 먼저 읽는다.** 어떤 상품을 잠글지는 주문 품목을 읽어야 안다.
- **잠그는 순서와 확인 순서는 다르다.** 잠금은 `WHERE id IN (...) FOR UPDATE` 로 ID 오름차순으로 한 번에 잡는다(기본 키를 값 순서대로 찾아간다). 삭제 여부 확인과 차감은 품목 순서로 돈다. 그래서 "처음 실패한 품목"(D-16, D-33)의 기대값이 유지된다.
- **오래 쥐는 잠금은 상품뿐이다.** 주문 · 포인트 행은 commit 직전 flush의 UPDATE 때 잠깐 잡힌다.
- **flush 때 UPDATE 순서는 제어하지 않는다.** Hibernate는 영속성 컨텍스트에 들어온 순서로 UPDATE를 낸다(`hibernate.order_updates` 로 바꿀 수 있음). 상품 행은 이미 잠겨 있고, 주문 행은 같은 주문끼리만, 포인트 행은 같은 고객끼리만 겹치므로 어떤 순서로 나가도 교착 고리가 생기지 않는다.

### 4.4 재시도 층

```
OrderV1Controller.confirmOrder
 → OrderConfirmRetrier.confirmOrder   (@Retryable, 트랜잭션 없음)
   → OrderFacade.confirmOrder         (@Transactional)   ← 시도마다 새 트랜잭션

PointV1Controller.charge
 → PointChargeRetrier.charge          (@Retryable, 트랜잭션 없음)
   → PointFacade.charge               (트랜잭션 없음)
     → PointService.charge            (@Transactional)   ← 시도마다 새 트랜잭션
```

- **재시도는 실패한 트랜잭션 바깥에서, 매 시도를 새 트랜잭션으로 실행한다.** rollback된 경계 안에서 예외를 삼키고 계속하거나, 오래된 객체로 UPDATE만 반복하지 않는다.
- **재시도 클래스를 따로 둔다(`~Retrier`).** 기존 Facade 코드와 Facade 단위 테스트는 그대로 둔다. 충전과 확정이 "재시도 bean → Facade" 같은 모양이다.
  - 충전은 트랜잭션이 `PointService` 에서 열린다. 한 도메인만 다루므로 2주차 4.4 규칙과 맞다.
  - `OrderV1Controller` 는 생성 · 조회는 `OrderFacade` 를, 확정만 `OrderConfirmRetrier` 를 부른다.

| 버린 대안 | 얻을 수 있던 것 | 포기한 이유 |
|---|---|---|
| Facade가 재시도하고 트랜잭션은 별도 bean으로 | Controller와 진입점이 그대로 | "여러 도메인을 다루면 Facade가 트랜잭션을 연다"(2주차 4.4)에 예외가 생기고, 조율 코드와 테스트가 Facade 밖으로 옮겨간다 |
| Facade 안에서 `TransactionTemplate` | 새 클래스가 없다 | 이 메서드만 프로그래밍 방식 트랜잭션이라 다른 Facade와 표현이 다르다 |
| 같은 메서드에 `@Retryable` + `@Transactional` | 변경이 가장 작다 | `@EnableRetry` 의 기본 order가 트랜잭션보다 바깥이라 동작은 한다. 하지만 올바른 동작이 코드에 보이지 않는 advice 순서에 달려 있다 |

#### 설정

```java
@Retryable(
    retryFor = OptimisticLockingFailureException.class,
    maxAttempts = 3,
    backoff = @Backoff(delay = 50, multiplier = 2, maxDelay = 200, random = true)
)
```

- **도구:** Spring Retry(`spring-retry` + AOP). Spring Boot 3.4.4(Spring Framework 6.2)라 프레임워크 내장 `@Retryable`(7.0부터)은 쓸 수 없다. `@EnableRetry` 는 `config/` 에 둔다.
- **재시도 대상:** `org.springframework.dao.OptimisticLockingFailureException`(부모 타입). 버전 충돌이 경로에 따라 다른 하위 타입으로 와도 놓치지 않는다.
  - `CoreException`(재고 부족, 잔액 부족, 이미 확정됨 등 업무 거절)은 재시도하지 않고 바로 나간다.
  - 비관적 잠금 실패(`PessimisticLockingFailureException` 계열: 잠금 대기 시간 초과, 교착)는 `OptimisticLockingFailureException` 의 하위 타입이 아니므로 재시도하지 않는다.
  - 첫 충전 동시 insert의 유니크 위반(`DataIntegrityViolationException`)도 재시도하지 않는다(8장 한계).
- **횟수: 총 3회**(최초 1회 + 재시도 2회).
- **대기: 지수 백오프 + 랜덤 지터.** 시도 사이 대기는 대략 1회차 실패 후 50~100ms, 2회차 실패 후 100~200ms. 최악의 경우 대기 합계 약 300ms에 트랜잭션 3번 분량이 더해진다.
  - 50ms에서 시작하는 이유: 확정 트랜잭션은 수 ms~수십 ms에 끝난다. 먼저 성공한 요청이 commit을 마칠 만큼만 기다리면 되고, 사용자가 체감하지 않는 범위다.
  - 지터를 섞는 이유: 같이 실패한 요청들이 같은 시각에 다시 출발하면 또 부딪힌다.
  - 비교 실험(6.6)의 실측으로 조정할 수 있다.
- **과제 시나리오에서 총 3회로 충분한가:** 낙관적 잠금은 한 라운드에서 최소 1건은 commit한다. 포인트 경쟁(요청 3개)은 최악의 경우에도 3라운드 안에 모두 끝나고(마지막은 잔액 부족), 충전과 결제(요청 2개)는 2라운드 안에 끝난다. 그래서 "기술 오류 0 · 한도 초과 0"이 이론상 성립한다.

#### 한도 초과 응답

- **재시도 클래스에는 `@Retryable` 만 둔다. `@Recover` 는 쓰지 않는다.** 3회 모두 충돌하면 충돌 예외가 그대로 밖으로 나가고, `ApiControllerAdvice` 가 응답으로 바꾼다.

  ```java
  // ErrorType
  CONCURRENT_UPDATE_CONFLICT("다른 요청과 동시에 처리되어 완료하지 못했습니다. 잠시 후 다시 시도해 주세요.")
  ```

  - `ErrorStatusMapper`: `CONCURRENT_UPDATE_CONFLICT` → 409
  - `ApiControllerAdvice`: `OptimisticLockingFailureException` 핸들러 추가, `warn` 로그
- **`ErrorType`(범용)에 두는 이유:** 재시도 층은 충돌이 주문의 버전인지 포인트의 버전인지 모른다. 업무 규칙 위반이 아니라 처리 과정의 충돌이다.
- **기존 `CONFLICT` 를 쓰지 않는 이유:** 메시지가 "이미 존재하는 리소스입니다."라 뜻이 다르고, 테스트에서 한도 초과를 따로 집계할 수 없다.
- **503이 아니라 409인 이유:** 503은 서버를 쓸 수 없다는 뜻이다. 지금은 리소스의 현재 상태와의 충돌이다.
- **한도 초과는 재고 · 잔액 부족으로 바꾸지 않는다.** 별도 코드와 `warn` 로그로 원인을 구분한다.
- **감수하는 비용**
  - 재시도 bean이나 Facade를 직접 부르는 테스트는 `CONCURRENT_UPDATE_CONFLICT` 가 아니라 원래 충돌 예외를 본다. 경쟁 테스트는 "`OptimisticLockingFailureException` = 한도 초과"로 집계하고, 409 응답 연결은 HTTP 테스트 한 건으로 확인한다.
  - `interfaces` 가 Spring DAO 예외 타입을 안다. 우리 `infrastructure` 패키지가 아니라 ArchUnit 규칙에는 걸리지 않고, `ApiControllerAdvice` 는 이미 Spring Web 예외들을 직접 처리한다.
  - 지금은 `@Version` 행을 바꾸는 경로(확정 · 충전)가 모두 재시도를 거친다. 그래서 이 핸들러에 도달하면 곧 한도 초과다. 재시도 없는 쓰기 경로가 생기면 1회 충돌도 같은 응답으로 나가지만 "잠시 후 다시 시도" 안내는 그때도 틀리지 않는다.

| 버린 대안 | 포기한 이유 |
|---|---|
| `@Recover` 에서 `CoreException(CONCURRENT_UPDATE_CONFLICT)` 로 변환 | Spring Retry는 재시도 대상이 아닌 예외도 recovery를 찾는다. 맞는 `@Recover` 가 없으면 `ExhaustedRetryException` 으로 감싸져 500이 되어, 기존 업무 오류 계약(409 등)이 깨질 수 있다. `notRecoverable` 설정이나 다시 던지는 `@Recover` 같은 추가 대응이 필요하다 |
| `RetryTemplate` 으로 직접 작성 | 흐름이 코드에 다 보이지만 선언적 `@Retryable` 보다 코드가 늘어난다 |

### 4.5 잠금 대기 한도와 잠금 실패 응답

- **잠금 대기 한도: 3초.** MySQL 기본값(`innodb_lock_wait_timeout` 50초)은 인기 상품에 몰렸을 때 사용자를 너무 오래 기다리게 한다.
  - 트랜잭션 하나가 10ms라면 대기열이 약 300건 쌓여야 3초에 닿는다. 과제 시나리오(8건)에서는 닿지 않는다.
- **설정 위치: commerce-api의 `application.yml` 에서만 커넥션 초기화 SQL로 덮어쓴다.**

  ```yaml
  datasource:
    mysql-jpa:
      main:
        connection-init-sql: SET SESSION innodb_lock_wait_timeout = 3
  ```

  - 공통 `modules/jpa/src/main/resources/jpa.yml` 은 commerce-api · commerce-batch · commerce-streamer가 함께 쓴다. 여기에 넣으면 오래 기다리는 게 정상일 수 있는 배치까지 3초 제한을 받는다.
  - **감수하는 비용:** 상품 잠금뿐 아니라 commerce-api의 모든 행 잠금 대기가 3초가 된다.

    | 경로 | 3초에 닿는 상황 | 판단 |
    |---|---|---|
    | 관리자 상품 수정 · 재고 설정 · 삭제 | 판매 중인 인기 상품 | 관리자가 다시 시도. 판매를 막지 않는 편이 낫다 |
    | 브랜드 일괄 삭제 | 연결 상품 중 인기 상품이 있음 | 전체 rollback 후 다시 시도. 정합성은 지켜짐 |
    | flush 때 주문 · 포인트 UPDATE | 같은 주문 · 같은 고객끼리만 잠깐 겹침 | 닿을 일이 거의 없음 |

  - **버린 대안:** 잠금 조회 직전에 `SET SESSION`. 상품 잠금에만 적용되지만, 풀에 반납된 커넥션에 설정이 남아 다른 요청으로 새어 나가 되돌리는 코드가 필요하다.
  - Hibernate의 잠금 대기 힌트(`jakarta.persistence.lock.timeout`)는 MySQL에서 0(`NOWAIT`) 말고는 무시되는 것으로 알고 있다. 구현할 때 실제 SQL로 확인한다.
  - MySQL의 대기 시간 초과는 기본값(`innodb_rollback_on_timeout=OFF`)에서 **해당 문장만** 취소한다. 지금 구조는 예외가 Facade 밖으로 나가 Spring이 전체를 rollback하므로 문제없다. 이 예외를 잡아 계속하는 코드를 두지 않는다.
- **잠금 실패 응답: 500, 오류 코드 하나.** 잠금 대기 시간 초과와 교착을 같은 코드로 응답하고, 원인은 로그의 예외 타입으로 구분한다.

  ```java
  // ErrorType (이름은 구현할 때 확정)
  LOCK_ACQUISITION_FAILED("요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.")
  ```

  - 지금처럼 `INTERNAL_ERROR` 로 떨어지면 응답만으로 잠금 실패와 진짜 버그를 구분할 수 없다.
  - 클라이언트가 할 일은 두 경우 모두 "잠시 후 다시 시도"로 같아서 코드를 나누지 않는다.
  - **503이 아니라 500인 이유:** 로드밸런서나 서킷브레이커가 503을 인스턴스 이상으로 보고 트래픽을 빼는 경우가 있다.
  - **교착은 재시도하지 않고 오류로 내보낸다.** 잠금 순서(4.3)를 지키면 생기지 않아야 하므로, 생겼다면 설계 버그 신호다. `error` 로그로 남긴다. 잠금 대기 시간 초과는 `warn`.
  - 잠금 대기 시간 초과는 Hibernate `PessimisticLockException` 을 감싼 Spring `PessimisticLockingFailureException` 으로 오는 것을 테스트로 확인했다. 핸들러는 부모 타입 `PessimisticLockingFailureException` 을 받아 `CannotAcquireLockException` 등 하위 타입도 함께 처리하고, 원인 SQL 오류 코드(교착 1213)로 로그 수준을 나눈다.
  - 기술 오류를 품절 · 잔액 부족으로 숨기지 않는다.

### 4.6 REPEATABLE READ에서의 동작

MySQL InnoDB 기본 격리 수준(REPEATABLE READ)에서 이 조합이 어떻게 맞물리는지 적는다.

- **일반 읽기**는 트랜잭션의 첫 일반 읽기 시점의 스냅샷을 계속 본다. 주문 확정에서는 ① 주문 조회 시점이다.
- **잠금 읽기(`FOR UPDATE` · `FOR SHARE`)와 UPDATE**는 스냅샷이 아니라 최신 commit 값을 본다.
- 그래서 재고는 항상 최신 값으로 판단하고, 주문 · 포인트는 오래된 스냅샷을 볼 수 있지만 commit 때 `@Version` 이 그 차이를 잡는다.

| 동시 실행 | 결과 |
|---|---|
| 같은 상품을 여러 고객이 확정 | ID 오름차순 잠금에서 줄을 섬. 각자 최신 재고로 판단. 재고가 떨어지면 `OUT_OF_STOCK` |
| 같은 고객의 다른 주문 2개 (상품이 겹치지 않음) | 둘 다 같은 잔액을 봄 → 먼저 commit한 쪽 성공, 나중 쪽은 UPDATE 0행 → 버전 충돌 → 재시도에서 최신 잔액으로 다시 판단 |
| 같은 주문을 두 번 확정 | 두 번째는 상품 잠금을 기다린 뒤에도 DRAFT로 보임 → commit 때 주문 버전 충돌 → 재시도에서 CONFIRMED를 보고 `ORDER_ALREADY_CONFIRMED` |
| 충전과 확정 | 충전은 포인트 행 하나만 잠깐 잡음 → 교착 고리 없음. 나중 commit이 버전 충돌 → 재시도 |
| 브랜드 삭제와 상품 등록 | brand 행에서 줄을 섬. 등록이 먼저면 그 상품도 bulk에 함께 삭제, 삭제가 먼저면 등록이 `BRAND_NOT_FOUND` |
| 브랜드 수정과 삭제 | brand 행에서 줄을 섬. 삭제가 먼저면 수정이 `BRAND_NOT_FOUND`. 되살아나지 않음 |

- **오래된 잔액으로 인한 거절:** 확정이 상품 잠금을 기다리는 사이 같은 고객의 충전이 commit되면, 확정은 충전 전 잔액(스냅샷)으로 판단한다. 그 잔액으로는 부족하지만 실제로는 충분한 경우 `INSUFFICIENT_POINT` 로 거절되고, 업무 거절이라 재시도하지 않는다. **요청 시작 시점 기준의 판단으로 받아들인다**(8장 한계). 포인트 조회 위치를 앞으로 옮겨도 스냅샷 시점은 같아서 달라지지 않는다.

## 5. 구조와 이름

### 5.1 이름 규칙 추가 (2주차 4.2)

| 위치 | 접미사 | 하는 일 |
|---|---|---|
| `application/<기능>` | `~Retrier` | 낙관적 충돌 시 유스케이스를 새 트랜잭션으로 다시 실행한다. 트랜잭션을 열지 않고 `~Facade` 를 부른다. `@Retryable` 만 둔다 |

- 이번에 생기는 것: `application/order/OrderConfirmRetrier`, `application/point/PointChargeRetrier`

### 5.2 2주차 4.4 "BRD-02 삭제 입구" 행의 변경

| 규칙 | 조율하는 곳 | 호출 |
|---|---|---|
| BRD-02 생성 입구 | `AdminProductFacade` | `BrandService` (살아 있는 브랜드, `FOR SHARE`) → `ProductService.create(브랜드, …)`. Product 생성자가 한 번 더 확인한다 |
| BRD-02 삭제 입구 | `BrandFacade` | `BrandService` (살아 있는 브랜드, `FOR UPDATE`) → `brand.delete()` → `ProductService` (브랜드의 미삭제 상품 일괄 삭제) |

## 6. 테스트

### 6.1 공통 규칙

- 기존 MySQL Testcontainers fixture를 쓴다. 메모리 DB나 repository mock은 실제 잠금 · rollback의 증거가 아니다.
- 초기 데이터는 worker 시작 전에 commit한다. 테스트 전체를 부모 트랜잭션으로 감싸지 않는다.
- rollback · 최종 상태 확인은 서비스 트랜잭션이 끝난 뒤 새 경계(`JdbcTemplate` 또는 새 트랜잭션의 저장소 조회)에서 재조회한다. 같은 영속성 컨텍스트에서 다시 보는 것은 확인이 아니다.
- 대기와 future에는 제한 시간을 두고, `finally` 에서 대기를 풀고 executor를 정리한다. timeout은 기대한 업무 거절로 세지 않는다.
- 요청별 결과를 수집한다: 성공 / 업무 거절(`CoreException`, 코드별) / 한도 초과(`OptimisticLockingFailureException`) / 기술 오류(그 밖). 예외를 출력만 하고 버리지 않는다.
- `Thread.sleep` 이나 반복 횟수 증가로 우연한 실패를 기다리지 않는다. 제품 코드에 테스트용 장벽 · sleep · 실패 스위치를 두지 않는다.

### 6.2 갱신 유실 대조군 (테스트 전용)

- 테스트 소스에서 독립된 트랜잭션 두 개가 commit된 재고 5를 **잠금 없는 SELECT** 로 읽는다. 두 값이 5임을 확인한 뒤(장벽) 쓰기를 허용하고, 각자 상수 4를 조건 · version 없이 저장한다.
- 두 commit 뒤 **성공 2 / 최종 재고 4, 그리고 2 + 4 ≠ 5** 를 assertion으로 확인한다. 불변식 위반이 재현됐음을 확인하는 음성 대조군이다.
- `stock = stock - 1` 형태의 SQL은 쓰지 않는다. 최소 두 worker · connection을 확보하고, timeout이나 SQL 오류는 갱신 유실 재현으로 세지 않는다.
- 이 장벽은 대조군 안에만 둔다. 대조군 통과는 실제 주문 정합성의 증거가 아니다.

### 6.3 중간 실패 (rollback)

**실패 주입: 마지막 서비스 단계를 `@MockitoSpyBean` 으로 감싸 실제로 실행 → `EntityManager.flush()` → `RuntimeException`.** 두 테스트에 같은 패턴을 쓴다.

- **이유:** 실패 시점까지 DB에 실제로 나간 변경이 많을수록 rollback 증거가 강하다. 보내지 않은 변경은 rollback 전에도 원래 값이라 재조회로 아무것도 증명하지 못한다.
- spy는 서비스 트랜잭션과 같은 스레드에서 실행되므로 테스트에 주입한 `EntityManager` 가 그 트랜잭션에 묶여 있다.
- **SQL이 나갔다는 증거:** flush 직후 같은 트랜잭션 안에서 네이티브 쿼리로 바뀐 값을 읽어 기록한 뒤 예외를 던진다. 마지막에 "트랜잭션 안에서는 바뀌어 보였고, 끝난 뒤 재조회하면 원래 값"을 함께 단언한다.
- 주입하는 예외는 `RuntimeException` 계열이다. checked exception은 기본 규칙으로 rollback되지 않는다.

| 테스트 | 준비 | 실패 주입 | 기대 결과 |
|---|---|---|---|
| 브랜드 정상 | 브랜드 1개, 연결 상품 2개(하나는 재고 0), 다른 브랜드 상품, 과거 주문 | - | 브랜드 · 연결 상품 모두 삭제. 다른 브랜드 상품 · 과거 주문 유지 |
| 브랜드 중간 실패 | 위와 같음 | `ProductService` 일괄 삭제를 실제 실행(상품 bulk) → flush(브랜드 UPDATE) → 예외 | 브랜드 · 연결 상품 모두 삭제 전 상태. 다른 대상 · 과거 주문 유지 |
| 주문 정상 | 여러 품목의 DRAFT, 충분한 재고 · 잔액 | - | 재고 · 잔액 차감, CONFIRMED, 결제액 · 결제 시각 저장 |
| 주문 중간 실패 | 위와 같음 | `OrderService.confirm` 을 실제 실행 → flush(재고 · 잔액 · CONFIRMED · 결제 결과 UPDATE) → 예외 | DRAFT · 재고 · 잔액 · 결제 결과가 변경 전과 같음 |

### 6.4 경쟁 (실제 서비스)

- worker의 **시작만** 맞추고, 각 요청이 독립된 트랜잭션에서 실제 Retrier · Facade · repository · MySQL을 통과한다. 모든 worker가 끝난 뒤 DB를 다시 읽는다.

| 시나리오 | 준비 | 기대 결과 |
|---|---|---|
| 재고 경쟁 | 재고 5, 1개씩 사는 서로 다른 DRAFT 주문 8개, 구매자별 충분한 잔액 | 확정 5 · 재고 부족 3 · 한도 초과 0 · 기술 오류 0 · 최종 재고 0. 확정된 주문의 수량 · 차감액만 반영 |
| 포인트 경쟁 | 한 고객 잔액 10,000원, 서로 다른 4,000원 DRAFT 주문 3개(서로 다른 상품), 충분한 재고 | 확정 2 · 잔액 부족 1 · 한도 초과 0 · 기술 오류 0 · 최종 잔액 2,000원. 거절된 주문의 재고 유지 |
| 충전과 결제 | 잔액 10,000원에서 2,000원 충전과 7,000원 확정을 함께 실행, 충분한 재고 | 두 요청 성공 · 기술 오류 0 · 최종 잔액 5,000원 |
| 같은 주문 중복 확정 | 같은 DRAFT를 동시에 2번 확정 | 성공 1 · `ORDER_ALREADY_CONFIRMED` 1. 재고 · 잔액은 한 번만 차감 |
| 브랜드 삭제 + 상품 등록 (선택 확장) | 같은 브랜드에 삭제와 등록을 동시에 | 등록 성공이면 그 상품도 삭제됨, 아니면 등록이 `BRAND_NOT_FOUND`. 어느 쪽이든 **삭제된 브랜드에 살아 있는 상품 0개** |
| 브랜드 수정 + 삭제 | 같은 브랜드에 수정과 삭제를 동시에 | 삭제된 브랜드가 되살아나지 않음. 삭제가 먼저면 수정은 `BRAND_NOT_FOUND` |

- 포인트 경쟁은 서로 다른 상품을 사게 해 상품 잠금이 포인트 충돌을 가리지 않게 한다. 재고를 충분히 두어 잔액 보호의 실패를 재고 부족이 가리지 않게 한다.
- 다음 식을 요청 결과와 DB의 주문 · 품목 · 잔액으로 함께 확인한다. 최종 재고가 음수가 아니라는 확인만으로 끝내지 않는다.
  - 수량: 초기 재고 − 성공 주문의 품목 수량 합 = 최종 재고
  - 잔액: 초기 잔액 + 성공 충전액 합 − 성공 결제액 합 = 최종 잔액
  - 집계: 성공 + 업무 거절 + 한도 초과 + 기술 오류 = 전체 요청
- 한도 초과가 나와 기대 결과와 다르면 별도로 기록해 원인을 확인한다.

### 6.5 재시도 동작

AOP라 fake 저장 구현을 쓰는 Facade 단위 테스트로는 확인할 수 없다. Spring 컨텍스트에서 Facade를 `@MockitoBean` 으로 바꿔 끼워 DB 없이 확인한다.

| 경우 | 기대 |
|---|---|
| 충돌 2번 후 성공 | Facade 호출 3번, 성공 응답 |
| 충돌 3번 | Facade 호출 3번, `OptimisticLockingFailureException` 이 그대로 나감 |
| `CoreException` | Facade 호출 1번, 원래 오류 코드 그대로 |

- HTTP 연결: 한도 초과가 409 `CONCURRENT_UPDATE_CONFLICT` 로, 잠금 실패가 500 `LOCK_ACQUISITION_FAILED` 로 나가는지 대표 사례로 확인한다.

### 6.6 비교 실험 (선택 확장, 대안 구현은 테스트 쪽에만)

- 재고 경쟁을 비관적 잠금(제품 코드)과 대안(테스트 쪽 구현: 낙관적 잠금 + 재시도 등)으로 각각 실행한다.
- 관찰 항목: 소요 시간, 시도 · 재시도 횟수, 한도 초과 건수, 최종 재고 일치 여부.
  - 재시도 횟수는 테스트 설정에 `RetryListener` 를 등록해 제품 코드를 건드리지 않고 센다.
- 요청 수를 8 → 12, 그리고 커넥션 풀 크기(테스트 프로필 10개)보다 크게 늘렸을 때 **다른 API가 커넥션을 얻지 못해 실패하는지** 관찰한다(8장 한계).
- 실험한 시나리오만 검증됐다고 쓴다.
- **결과:** [experiment-stock-strategy.md](experiment-stock-strategy.md) (2026-10-09 실행)

### 6.7 실행

```bash
./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'
./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
./gradlew :apps:commerce-api:check
```

- 수정 중에는 관련 테스트로 확인하고, 완료 시 영향받는 기존 브랜드 · 상품 · 포인트 · 주문 테스트와 lint · ArchUnit을 함께 실행한다.

## 7. 결정 요약

### 7.1 추가된 결정

| # | 결정 | 버린 대안 | 위치 |
|---|---|---|---|
| D-44 | BRD-02: 브랜드를 삭제하면 그 브랜드의 미삭제 상품(재고 0 포함)을 함께 삭제한다. 한 트랜잭션, 실패 시 전체 원상태 | 상품이 남으면 거절(2주차) | 2.1 |
| D-45 | 브랜드 일괄 삭제의 트랜잭션은 `BrandFacade` 가 연다. 브랜드 잠금 조회 → `brand.delete()` → `ProductService` 일괄 삭제 | 도메인 서비스가 조율 | 2.2 |
| D-46 | 상품 일괄 삭제는 bulk UPDATE. `deleted_at` · `updated_at` 을 `Clock` 기준으로 직접 설정하고, 영속성 컨텍스트를 비우지 않는다 | 엔티티 반복 / 잠금 조회 후 bulk | 2.3 |
| D-47 | `BRAND_HAS_PRODUCTS` 와 `BrandRepository.hasActiveProduct` 를 지운다 | 남겨 둠 | 2.5 |
| D-48 | 재고는 비관적 잠금(`FOR UPDATE`). 재고는 `product` 행에 그대로 둔다 | 낙관적 잠금 / 조건부 갱신 / 재고 엔티티 분리 | 4.1 |
| D-49 | 포인트 · 주문 상태는 낙관적 잠금(`@Version`). 주문은 상태만 보호한다 | 비관적 잠금 / 주문 행을 첫 관문으로 잠금 | 4.1 |
| D-50 | `product` 행을 쓰는 모든 경로(확정 · 재고 설정 · 수정 · 삭제)는 잠금 조회한다 | `@DynamicUpdate` | 4.2 |
| D-51 | `brand` 행: 수정 · 삭제는 `FOR UPDATE`, 상품 등록은 `FOR SHARE` | `@Version` / `@DynamicUpdate` / 등록도 `FOR UPDATE` / 막지 않음 | 4.2 |
| D-52 | 잠금 순서: brand → product, 같은 종류의 여러 행은 ID 오름차순. 확정은 잠금 순서(ID)와 확인 순서(품목)를 분리한다. flush 순서는 제어하지 않는다 | 품목 순서로 잠금 | 4.3 |
| D-53 | 확정의 확인 순서는 2주차 그대로(잔액은 맨 뒤) | 잔액 먼저 확인 | 3 |
| D-54 | 재시도는 `~Retrier` 가 `~Facade` 를 감싼다(`OrderConfirmRetrier`, `PointChargeRetrier`). Spring Retry, `OptimisticLockingFailureException`, 총 3회, 50ms부터 지수 백오프(최대 200ms) + 지터 | Facade 재시도 + 트랜잭션 bean / `TransactionTemplate` / 같은 메서드에 둘 다 | 4.4 |
| D-55 | 한도 초과는 `@Recover` 없이 `ApiControllerAdvice` 에서 409 `CONCURRENT_UPDATE_CONFLICT` 로 바꾼다 | `@Recover` 변환 / `RetryTemplate` | 4.4 |
| D-56 | 잠금 대기 한도 3초. commerce-api에서만 `connection-init-sql` 로 설정 | 기본 50초 / 공통 `jpa.yml` / 잠금 조회 직전 `SET SESSION` | 4.5 |
| D-57 | 잠금 대기 시간 초과 · 교착은 재시도하지 않고 500 `LOCK_ACQUISITION_FAILED` 하나로 응답. 원인은 로그로 구분(교착 `error`, 시간 초과 `warn`) | `INTERNAL_ERROR` 그대로 / 코드 둘 / 503 / 교착 재시도 | 4.5 |
| D-58 | 중간 실패는 마지막 서비스 단계를 spy로 실제 실행 → flush → `RuntimeException` 으로 주입한다 | Hibernate Interceptor · StatementInspector / 저장소 데코레이터 | 6.3 |

### 7.2 바뀌거나 대체된 2주차 결정

| 2주차 | 변경 |
|---|---|
| D-02 | "삭제 입구는 BRD-02가 지킨다"의 방식이 거절에서 함께 삭제로 바뀜(D-44) |
| D-07 | 유지. 2.4 "다시 검토할 조건"의 동시성 조건을 검토하고 재고를 `product` 행에 그대로 둠(D-48) |
| D-16 | 유지. 잔액 먼저 확인하는 안을 검토하고 버림(D-53) |
| D-32 | 대체. 브랜드 삭제 입구가 `BrandRepository` 의 질문이 아니라 `BrandFacade` 의 조율이 됨(D-45, D-47) |

## 8. 알려진 한계

| 한계 | 위치 |
|---|---|
| **커넥션 풀 고갈.** 잠금을 기다리는 요청은 커넥션을 쥔 채 기다린다(풀 40개, 커넥션 획득 대기 3초). 인기 상품에 풀 크기 이상이 몰리면 무관한 API까지 커넥션을 얻지 못하고 500으로 실패한다. 대응 방안(대기 시간 조정, 확정 동시 실행 수 제한, 확정 전용 풀 분리, DB 밖 대기열)은 이번에 구현하지 않고 비교 실험에서 관찰한다 | 4.1, 6.6 |
| 확정이 상품 잠금을 기다리는 사이의 충전은 보이지 않아, 오래된 잔액 기준으로 `INSUFFICIENT_POINT` 를 받을 수 있음 (요청 시작 시점 기준의 판단) | 4.6 |
| 첫 충전 동시 요청은 유니크 위반으로 한쪽이 실패하며 재시도하지 않음 (2주차 한계 유지) | 4.4 |
| 브랜드 일괄 삭제는 한 트랜잭션이라, 상품이 아주 많으면 그 브랜드 상품 전부를 오래 잠그고 undo log가 커진다. 나눠 처리하면 원자성 요구가 깨진다 | 2.3 |
| bulk UPDATE의 잠금 범위 · 순서는 `brand_id` 인덱스를 타는 실행 계획에 기댄다 | 2.3 |
| 잠금 대기 3초가 commerce-api의 모든 행 잠금 대기에 적용된다 | 4.5 |
| 잠금 순서를 맞춰도 모든 교착이 사라진다고 단정할 수 없다 | 4.3 |
| 2주차 한계 "같은 상품의 동시 확정 시 초과 판매, 이름 수정과 재고 차감의 덮어쓰기"는 해소 (D-48, D-50) | 2주차 2.4 |

## 9. AI와 설계 다듬기

### 9.1 검토 질문

- 브랜드 일괄 삭제와 주문 확정의 호출 흐름에서 프록시를 거치는 시작점, 같이 rollback할 변경, 잠금 순서는 어디인가?
- 현재 설계를 깨뜨리는 중간 실패와 경쟁 순서는 무엇인가?

### 9.2 반례와 선택

| # | 반례 · 지적 | 선택 | 결정 |
|---|---|---|---|
| 1 | 상품 수정은 재고를 건드리지 않아도 모든 컬럼 UPDATE로 오래된 `stock` 을 덮어쓴다. 브랜드 일괄 삭제의 `product.delete()` 도 같다 | `product` 행을 쓰는 모든 경로를 잠금 조회 | D-50 |
| 2 | 브랜드 수정이 오래된 `deleted_at = NULL` 을 다시 써서 삭제된 브랜드가 되살아난다 | brand 행 `FOR UPDATE` | D-51 |
| 3 | 브랜드 삭제와 상품 등록이 겹치면 삭제된 브랜드에 살아 있는 상품이 남는다. FK는 논리 삭제를 모른다 | 상품 등록이 `FOR SHARE` 로 참여 | D-51 |
| 4 | 재시도가 충전에만 있으면 포인트 경쟁 · 충전과 결제의 기대 결과가 나오지 않는다 | 확정 유스케이스 전체를 재시도 | D-54 |
| 5 | `@Recover` 가 충돌 예외만 받으면 업무 거절 예외가 `ExhaustedRetryException` 으로 감싸져 500이 될 수 있다 | `@Recover` 를 쓰지 않고 Advice에서 변환 | D-55 |
| 6 | 잔액을 먼저 확인하면 품절 상품을 위해 쓸데없는 충전을 유도한다 | 확인 순서 유지 | D-53 |
| 7 | 잠금 조회의 `ORDER BY id` 는 잠금 순서를 보장하지 않는다(정렬 전에 스캔하며 잠금) | 잠금 조회 후 bulk 대신 bulk만 | D-46 |
| 8 | bulk UPDATE에 `clearAutomatically` 를 쓰면 이미 조회한 브랜드가 분리되어 브랜드 삭제가 조용히 사라진다 | clear 사용 안 함, 재조회 테스트로 확인 | D-46 |
| 9 | 잠금 대기 한도를 공통 `jpa.yml` 에 넣으면 배치 앱까지 3초 제한을 받는다 | commerce-api에서만 설정 | D-56 |
| 10 | 비관적 잠금의 대기 요청이 커넥션을 쥐어 풀이 고갈될 수 있다 | 한계로 기록하고 실험에서 관찰 | 8장 |

### 9.3 정정한 설명

- "bulk UPDATE는 ID 오름차순 보장이 없다" → `brand_id` 보조 인덱스가 `(brand_id, id)` 순이라 인덱스를 타면 오름차순이다. 정확한 위험은 실행 계획에 기댄다는 점이다.
- "같은 메서드에 `@Retryable` + `@Transactional` 은 깨지기 쉽다" → 기본 order로는 올바르게 동작한다. 정확한 비용은 동작 근거가 코드에 드러나지 않는다는 점이다.
- "주문 행을 첫 관문으로 잠그면 트랜잭션이 길어진다" → 트랜잭션 시간은 같고, 같은 주문의 두 번째 요청만 기다린다.

### 9.4 구현할 때 확인할 것

- ~~`PESSIMISTIC_READ` 가 MySQL 8에서 `FOR SHARE` 로 나가는지~~ → 확인함 (`for share`)
- Hibernate 잠금 대기 힌트가 MySQL에서 무시되는지 (그래서 `connection-init-sql` 이 필요한지)
- 잠금 대기 시간 초과 · 교착의 실제 예외 타입 → 시간 초과는 `PessimisticLockingFailureException`(원인 Hibernate `PessimisticLockException`)으로 확인함. 교착은 재현하지 않음
- `brand_id` 인덱스 존재와 bulk UPDATE의 `EXPLAIN` → 테스트 스키마(`ddl-auto: create`)에서 FK 인덱스가 생기고, bulk UPDATE는 그 인덱스를 `range` 로, 확정의 일괄 잠금은 `PRIMARY` 를 `range` 로 탐을 확인함. 운영 스키마의 인덱스는 확인하지 않음
- 재시도 대상이 아닌 `CoreException` 이 `@Recover` 없이 원래 예외 그대로 나가는지
