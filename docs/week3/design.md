# 3주차 설계 문서 — 실패와 경쟁에서도 업무 규칙 지키기

2주차 설계(`docs/week2/design.md`)를 그대로 이어받는다. 이 문서는 이번 주에 **바뀌거나 새로 정한 것**만 적는다.

## 1. 이번 주 범위 — 사실과 결정

| 구분 | 내용 |
|---|---|
| 과제가 명시한 사실 | `DELETE /api-admin/v1/brands/{brandId}`는 연결된 미삭제 상품이 있으면 거절(409)하던 계약에서, **브랜드와 연결 상품을 함께 삭제·비노출**하는 계약으로 바뀐다. 재고 0인 상품도 대상이다. |
| 과제가 명시한 사실 | 일괄 삭제 도중 실패하면 이번 요청의 브랜드·상품 변경이 **전부** 취소돼야 한다. 일부 성공 응답이나 상품별 별도 commit은 허용하지 않는다. |
| 과제가 명시한 사실 | 주문 확정은 재고·포인트 차감, CONFIRMED 전이, 결제 결과 저장을 같은 DB 트랜잭션에서 처리하고, 같은 행을 바꾸는 다른 경로(관리자 재고 설정, 포인트 충전)도 선택한 동시성 규칙을 우회하지 않아야 한다. |
| 이번 주에 정할 결정 | 아래 각 섹션. |

이 변경으로 2주차 design.md 6번 섹션의 "삭제되지 않은 상품이 남아있는 브랜드는 삭제 불가(409)" **정책**은 폐기된다. 같은 문단의 "Brand·Product에 걸친 조율은 `BrandAdminFacade`(application)에서 한다"는 **위치** 결정은 유지한다 — 아래 2번 섹션.

## 2. 브랜드 일괄 삭제 — 트랜잭션 경계를 어디에 두는가

### 2주차 코드의 실제 경계 (변경 전)

`BrandAdminFacade.deleteBrand()`에 `@Transactional`이 없었다. 그래서 Facade가 호출하는 Service 메서드 하나하나가 각자의 `@Transactional`(`REQUIRED`)로 **별개의 물리 트랜잭션**을 시작하고 끝냈다 (`open-in-view: false`라 영속성 컨텍스트도 매번 따로).

```
BrandAdminFacade.deleteBrand()             ← 트랜잭션 없음
 ├ brandService.getBrand(id)               → 트랜잭션① 시작·종료
 ├ productService.hasActiveProduct(id)     → 트랜잭션② 시작·종료
 └ brandService.deleteBrand(id)            → 트랜잭션③ 시작·commit
```

디버거에서 `TransactionSynchronizationManager.isActualTransactionActive()`로 확인: Facade 안에서는 `false`, `BrandService.deleteBrand()` 안에서는 `true`.

이 구조에 상품 삭제를 그대로 끼워 넣으면, P1 삭제가 자기 트랜잭션으로 commit된 뒤 P2에서 실패했을 때 **브랜드 사용 가능 / P1 삭제 / P2 사용 가능**이라는 어긋난 상태가 남는다. 각 Service 메서드는 자기 몫을 정확히 했기 때문에 Service 단위 테스트로는 이 문제가 잡히지 않는다.

### 비교한 대안

| | A. `BrandAdminFacade.deleteBrand()`에 `@Transactional` | B. `BrandService.deleteBrand()` 안에서 상품까지 삭제 |
|---|---|---|
| 원자성 | Facade가 물리 트랜잭션을 시작하고, 안쪽 Service 메서드들은 `REQUIRED`로 참여 | `BrandService.deleteBrand()`의 기존 `@Transactional`로 자동 확보 |
| 새로 생기는 의존 | 없음 (Facade는 이미 `BrandService`·`ProductService`를 둘 다 앎) | `BrandService`(Brand aggregate의 domain 서비스)가 `ProductRepository`에 의존 |
| 2주차 결정과의 관계 | 4번 섹션 "조율은 application이 한다", 6번 섹션 "조율 위치는 `BrandAdminFacade`"를 유지 | 위 두 결정을 뒤집음 — cross-aggregate 규칙이 한 aggregate의 domain 서비스 안으로 들어감 |
| "브랜드 삭제 시 상품도 함께" 정책이 바뀌면 | Facade만 수정 | Brand domain 서비스 수정 (Product 쪽 변경 이유가 Brand 서비스로 번짐) |

Controller에 경계를 두는 안은 처음에 제외했다. Controller의 책임은 HTTP 변환이라 이 규칙이 바뀌는 이유와 무관하고, 같은 유스케이스를 다른 진입점(배치 등)에서 부르면 그쪽엔 경계가 없게 된다 — 트랜잭션 경계는 **유스케이스 단위**에 둔다.

### 결정: A — Facade에 `@Transactional`

도메인 서비스들은 각자 자기 aggregate만 안다. "브랜드 삭제 시 연결 상품도 함께 내린다"는 두 aggregate에 걸친 규칙이고, 그 조율이 이미 application(`BrandAdminFacade`)에 있으므로 **하나의 성공/실패로 묶는 경계도 조율이 일어나는 그 자리**에 둔다. 그래야 이 규칙이 바뀔 때 그 변화에 해당하는 코드(Facade)만 고치면 된다.

2주차 `OrderFacade.confirmOrder()`가 Order·Product·User 세 aggregate를 묶기 위해 Facade에 `@Transactional`을 둔 것과 같은 이유다.

### 이 결정이 따라오게 하는 규칙 (감수하는 것)

바깥에 경계를 하나 두면, **안쪽 메서드의 전파 속성과 예외를 어디서 잡느냐가 그 경계의 의미를 바꿀 수 있다.**

- 안쪽 Service 메서드가 `@Transactional`(`REQUIRED`)인 상태에서 Facade가 안쪽 예외를 catch하고 정상 반환하면 → 안쪽 프록시가 남긴 rollback-only 표시 때문에 **전체 rollback + `UnexpectedRollbackException`**. "일부 실패" 응답은 나가지 않는다.
- 안쪽에 애너테이션이 없고 예외가 프록시 밖 코드에서 났다면 → catch 후 정상 반환 시 **일부만 commit**.
- 안쪽이 `REQUIRES_NEW`라면 → 앞서 처리한 상품이 이미 독립 commit돼 되돌릴 수 없음.

따라서: **함께 rollback돼야 하는 유스케이스 안에서는 예외를 삼켜 일부 성공으로 바꾸지 않고, 안쪽 메서드에 `REQUIRES_NEW`를 쓰지 않는다.** 실패는 Facade 밖(프록시)까지 그대로 전파시킨다.

## 3. 연결 상품을 어떻게 삭제하는가 — 엔티티 방식 vs bulk UPDATE

soft delete라 "삭제"는 SQL로 `UPDATE product SET deleted_at = ...`이다. 상품 100개 브랜드 기준으로 비교했다.

| | 엔티티 방식 (조회 후 각 엔티티 `delete()`) | bulk UPDATE (`UPDATE product SET deleted_at = ? WHERE brand_id = ? AND deleted_at IS NULL`) |
|---|---|---|
| SQL | SELECT 1번 + UPDATE 100번 (commit 직전 dirty checking flush) | 1번 |
| 삭제 멱등 규칙 | `BaseEntity.delete()` 한 곳 | SQL의 `WHERE deleted_at IS NULL`에 다시 써야 함 — 규칙이 두 군데로 갈라짐 |
| `@PreUpdate` (`updatedAt` 갱신, `guard()`) | 실행됨 | 실행 안 됨 — `updated_at`을 SQL에 직접 넣지 않으면 옛 값으로 남음 |
| 영속성 컨텍스트 | DB와 일치 | 같은 트랜잭션에 이미 올라온 상품 엔티티는 여전히 `deletedAt = null`. 이후 그 엔티티가 다른 이유로 dirty해지면 전체 컬럼 UPDATE가 `deleted_at = NULL`을 다시 써 삭제를 되살릴 수 있음 → `@Modifying(clearAutomatically, flushAutomatically)` 같은 정리를 직접 책임져야 함 |

참고: Facade에 경계를 둔 덕분에 엔티티 방식에서 `deleteProduct(id)` 내부의 재조회는 1차 캐시에서 해결돼 SELECT가 다시 나가지 않는다. (경계가 없던 2주차 구조였다면 `SELECT 1 + SELECT 100 + UPDATE 100`.)

### 결정: 엔티티 방식

관리자가 가끔 브랜드 하나를 지우는 기능이라 빈도가 낮고, `1 + N`번의 SQL 비용을 감수할 수 있다. 그 대가로 삭제 규칙·수정 시각·검증 훅이 엔티티 한 곳에서 지켜지고, 영속성 컨텍스트와 DB가 어긋날 위험이 없다.

2주차 `Point.charge()`가 원자적 UPDATE를 택한 근거("현재 상태에 의존하는 규칙이 없고, 동시에 자주 일어날 수 있다")를 반대 방향으로 적용한 결과다 — 이번 기능은 빈도도 낮고 동시성 경쟁 대상도 아니므로 SQL을 줄여 얻는 것보다 엔티티 경로를 벗어나 잃는 것이 크다. 2주차 Point 2배 버그가 "매니지드 엔티티 변경과 엔티티를 우회하는 SQL을 한 트랜잭션에서 섞어서" 생겼다는 것도 이 판단의 근거다.

주의: 2주차 `getProducts(brandId, sort, pageable)`는 페이지네이션이 걸려 있어 재사용하면 첫 페이지만 삭제된다. "브랜드의 미삭제 상품 전부"를 주는 조회가 별도로 필요하다.

### 결정: `ProductService.deleteAllByBrandId(brandId)`

Facade가 루프를 돌며 `deleteProduct(id)`를 부르는 대신, `ProductService`가 "brandId로 고른 미삭제 상품 전부 삭제"를 한 메서드로 제공한다. `brandId`는 `ProductModel` 자신의 필드라 이건 Product aggregate 범위의 질문이고(2주차 `existsActiveByBrandId`와 같은 종류), "브랜드가 삭제되면 상품도 내린다"는 **이유**는 여전히 `BrandAdminFacade`만 안다. 2주차 6번 섹션의 "`ProductService`는 브랜드 삭제라는 개념을 모른다"를 지킨다.

## 4. 중간 실패 rollback 검증

`BrandRemovalTransactionTest` (운영 코드 변경 없음).

- **언제 SQL이 나가는가**: 엔티티 방식이라 상품 `UPDATE`는 commit 직전 flush 때까지 DB로 나가지 않는다. `brandService.deleteBrand()` 안의 `getBrand()`는 id 조회라 자동 flush를 일으키지 않고, `brandRepository.save()`도 관리 중인 엔티티라 SQL을 보내지 않는다. 그래서 `save()`에서 예외만 던지면 "DB에 아무것도 안 간 상태"에서 실패한 것이 되어 rollback을 검증하지 못한다.
- **실패 주입**: `@MockitoSpyBean BrandRepository`로 `save()`를 가로채 **flush → 같은 트랜잭션 안에서 네이티브 쿼리로 삭제 표시된 상품 수 기록 → 예외** 순서로 실행한다. 트랜잭션 안에서 본 값이 2라는 것이 "실제 변경 SQL이 나갔다"는 증거다.
- **확인**: 테스트 메서드에 `@Transactional`을 붙이지 않고, Facade 호출이 예외로 끝난 뒤 새로 읽은 DB에서 브랜드·연결 상품 2개(재고 0 포함)·다른 브랜드 상품이 전부 `deletedAt == null`, 과거 주문의 단가·수량·결제액이 그대로인지 본다.
- **테스트가 버그를 잡는지 확인**: Facade의 `@Transactional`을 잠시 빼고 같은 테스트를 돌리면 상품 2개의 `deletedAt`이 남은 채로 실패했다(브랜드만 rollback, 상품은 각자 commit = "일부만 반영"). 다시 붙이면 통과한다.

## 5. 삭제된 상품과 좋아요 — 읽을 때 거른다

상품이 soft delete돼도 `likes` 테이블의 행은 남는다. 과제 요구는 "삭제 상품은 내 좋아요 목록에서 제외"와 "기존 자기 좋아요 취소는 유지".

| | A. 읽을 때 거르기 | B. 삭제할 때 좋아요 행도 같이 삭제 |
|---|---|---|
| 목록 조회 | 삭제된 상품을 결과에서 뺌 | 행이 없어 자연히 안 나옴 |
| 브랜드 삭제 트랜잭션 | Brand·Product 두 aggregate 그대로 | Brand·Product·Like 세 aggregate — 바꿀 행·실패 지점·rollback 범위가 커지고, Like가 "브랜드 삭제"라는 남의 규칙 때문에 바뀜 |
| 상품을 되살린다면(`restore()`) | 좋아요가 그대로 다시 보임 | "U가 P1을 좋아했다"는 사실이 이미 사라져 복구 불가 |

### 결정: A — 읽을 때 거른다

"유저가 상품을 좋아했다"는 상품이 지금 판매되는지와 무관한 사실이다. 브랜드 삭제의 목적은 상품을 고객의 **사용 대상에서 빼는 것**이지 그 사실을 없애는 것이 아니다 — 상품을 hard delete하지 않은 이유, 과거 주문을 건드리지 않는 이유와 같은 줄기다(없애려는 대상과 보존할 사실의 구분). (`restore()`를 부르는 API는 현재 없어 복구 자체는 범위 밖이고, 위 표의 복구 행은 무엇이 보존할 사실인지 드러내는 렌즈로 썼다.)

구현은 2주차의 조합 원칙 그대로: `LikeFacade.getMyLikes()`가 좋아요한 productId 목록을 받고, `ProductService.getProductsByIds()`(IN 쿼리 한 번, 삭제·부재 상품 제외)로 살아있는 상품만 추려 좋아요 순서대로 남긴다. Like와 Product는 서로를 모른다.

- 자기 좋아요 취소는 상품을 조회하지 않으므로 삭제된 상품에도 그대로 동작한다(200, 행 실제 삭제) — 테스트로 확인.
- 삭제된 상품에 새 좋아요는 기존대로 404.

## 6. 상품 행을 바꾸는 모든 경로가 같은 잠금에 참여한다

### 발견한 문제 — 잠그지 않은 경로가 잠근 경로의 결과를 덮어쓴다

2주차에 주문 확정의 재고 차감은 비관적 락(`findForUpdate`)으로 보호했다. 그런데 같은 상품 행을 바꾸는 다른 경로 — 관리자 상품 수정(`updateProduct`), 재고 설정(`changeStock`), 삭제(`deleteProduct`), 브랜드 일괄 삭제(`deleteAllByBrandId`) — 는 일반 SELECT로 읽고 있었다. `ProductModel`에 `@DynamicUpdate`가 없어 Hibernate는 UPDATE 때 **안 바꾼 컬럼까지 전부** 메모리 값으로 다시 쓴다.

```
재고 10
1. 관리자: updateProduct — 일반 SELECT (재고 10을 메모리에 보유)
2. 고객:   confirmOrder — findForUpdate, 10 → 8, commit
3. 관리자: 가격만 바꿨지만 UPDATE ... stock_remaining = 10 ... commit
→ 주문은 성공(2개 판매·포인트 차감)했는데 재고는 10. 10 − 2 ≠ 10, 예외도 음수도 없이 조용히 틀림.
```

주문 쪽 락은 규칙대로 동작했다. `SELECT ... FOR UPDATE`는 같이 잠금을 요청하는 트랜잭션끼리만 서로 기다리게 하고 일반 SELECT는 통과시킨다 — **잠금은 같은 행을 바꾸는 모든 경로가 같은 규칙으로 요청할 때만 효과가 있는 약속**이다.

원인은 두 겹이다: ① 관리자 경로가 잠금 없이 읽고 판단한다 ② 안 바꾼 컬럼까지 옛 값으로 다시 쓴다.

### 비교한 대안

| | A. 관리자 경로도 `findForUpdate` | B. `@DynamicUpdate` |
|---|---|---|
| ② 안 바꾼 컬럼 덮어쓰기 | 해결 — 잠근 뒤 읽으니 모든 컬럼이 최신 | 해결 — 바뀐 컬럼만 UPDATE |
| ① 잠금 없이 읽은 값으로 판단 | 해결 — 판단이 실행 시점까지 유효 | **그대로** — 예: 재고 설정이 "삭제 안 됨"을 확인한 뒤 그 사이 상품이 삭제되면, 삭제된 상품의 재고를 바꿈 |
| 비용 | 관리자 요청이 진행 중인 주문 확정을 잠깐 기다림. 여러 상품을 잠그는 경로는 잠금 순서를 맞춰야 함 | Hibernate 전용 애너테이션, UPDATE SQL이 매번 달라짐 |
| 2주차와의 관계 | 재고·포인트 차감에 쓴 메커니즘 재사용 | 새 메커니즘 추가 |

### 결정: A — 상품 행을 바꾸는 모든 경로가 비관적 락에 참여

- `ProductService`의 `updateProduct`·`changeStock`·`deleteProduct`·`decreaseStock`이 하나의 `getActiveProductForUpdate(id)`(잠금 조회 + 삭제 여부 확인)를 거친다. 매니지드 엔티티라 `save()`를 다시 부르지 않는다.
- 브랜드 일괄 삭제는 여러 상품을 잠그므로 주문 확정과 같은 **`productId` 오름차순**으로 잠근다(`ORDER BY p.id ASC` + `PESSIMISTIC_WRITE`) — 서로 다른 순서로 잠가 생기는 교착을 피한다.
- `product.brand_id`에 인덱스(`idx_product_brand_id`)를 둔다. InnoDB 잠금 읽기는 조건에 맞는 행이 아니라 **훑은 행**을 잠그므로, 인덱스가 없으면 브랜드 하나를 지우는 동안 테이블의 모든 상품이 잠겨 다른 브랜드의 주문 확정까지 멈춘다.

### 검증 — 순서를 우연에 맡기지 않는 재현 (`ProductWritePathLockTest`)

테스트가 직접 연 트랜잭션이 상품을 잠그고 변경을 flush한 채 commit을 미룬다 → 다른 스레드에서 실제 서비스 메서드 호출 → **DB의 `information_schema.PROCESSLIST`에서 그 호출이 상품 행을 기다리는 게 보이면** 그제서야 commit → 최종 DB 상태 확인. sleep으로 타이밍을 맞추거나 운영 코드에 장벽을 넣지 않는다.

| 경합 | 기대 결과 | 잠금을 일반 조회로 되돌렸을 때 |
|---|---|---|
| 주문 차감(10→8) 중 관리자 가격 수정 | 재고 8, 가격·이름은 새 값 | 재고 **10** (갱신 유실) |
| 상품 삭제 중 관리자 재고 설정 | 404, 재고·삭제 상태 그대로 | 예외 없이 삭제된 상품의 재고 변경 |
| 브랜드 상품 하나 차감 중 브랜드 일괄 삭제 | 둘 다 삭제, 차감된 재고 8 유지 | 재고 **10** (갱신 유실) |

세 테스트 모두 잠금을 되돌리면 실패하고 다시 붙이면 통과하는 것을 확인했다.

## 7. 주문 확정 — 원자성과 경쟁

### 트랜잭션 경계와 잠금 순서 (2주차 구조 유지)

`OrderFacade.confirmOrder()`가 하나의 물리 트랜잭션을 시작하고, 안쪽 Service 메서드들은 `REQUIRED`로 참여한다.

```
Controller → [OrderFacade 프록시: 트랜잭션 시작]
  ├ orderService.getOwnedOrderForUpdate   Order 잠금 → 소유자·DRAFT 확인
  ├ productService.decreaseStock (productId 오름차순)   Product 잠금 → 삭제 여부 재확인 → 차감
  ├ userService.payPoint                  User 잠금 → 잔액 확인 → 차감
  └ order.confirm(총액) → orderService.save
[정상 반환 → commit / 예외 → 전체 rollback]
```

잠금 순서 `Order → Product(오름차순) → User`. 이번 주에 잠금에 참여하게 된 관리자 상품 경로·브랜드 일괄 삭제는 Product만 (오름차순으로) 잠그므로 이 순서와 충돌하지 않는다.

### 같은 포인트 행을 바꾸는 두 방식은 맞물리는가

| 경로 | 방식 |
|---|---|
| 결제 `payPoint` | `findForUpdate` → 엔티티 `pay()` → dirty checking UPDATE |
| 충전 `chargePoint` | `UPDATE user SET point_balance = point_balance + :amount WHERE id = :id` |

충전은 `FOR UPDATE`를 쓰지 않지만 잠금 규칙을 우회하지 않는다. `UPDATE`는 그 자체가 **잠금 읽기**라 최신 커밋 버전을 읽고 행을 잠그며, 결제가 쥔 잠금을 기다린다. 그리고 쓸 값을 애플리케이션이 스냅샷으로 계산하지 않고 **DB가 문장 안에서 최신값 기준으로** 계산한다.

6번 섹션의 관리자 상품 수정 버그와 비교하면, 기준은 "`FOR UPDATE`를 썼는가"가 아니라 **"쓰는 값이 스냅샷에서 나왔는가"**다 — 관리자 수정은 일반 SELECT(스냅샷)로 읽은 재고를 절대값으로 다시 썼고, 충전은 읽지 않고 상대값을 DB에 맡긴다.

### 검증

**갱신 유실 음성 대조군** (`LostUpdateControlTest`) — 제품 구현이 아님. 두 트랜잭션이 커밋된 재고 5를 잠금 없는 SELECT로 읽고, 장벽에서 둘 다 5를 읽은 것을 확인한 뒤 각자 상수 4를 저장한다. 성공 2 / 최종 재고 4 / `5 − 2 ≠ 4`를 assertion으로 확인한다. 이 결과는 실제 서비스의 정합성 증거가 아니라 "왜 틀리는지"의 대조군이다.

**경쟁 시나리오** (`OrderConcurrencyTest`) — 실제 `OrderFacade`·`UserFacade`를 시작 latch로만 맞춰 동시 실행. 요청별 결과를 성공 / 업무 거절(재고 부족·잔액 부족, `CoreException` BAD_REQUEST의 메시지로 구분) / 기술 오류(그 외 전부 — 잠금 시간 초과·교착 등)로 집계하고, 모든 worker 종료 후 새로 읽은 DB로 불변식을 대조한다.

| 시나리오 | 결과 | 불변식 |
|---|---|---|
| 재고 5, 1개씩 사는 서로 다른 주문 8개 | 확정 5 · 재고 부족 3 · 기술 오류 0 · 최종 재고 0 | `초기 재고 − CONFIRMED 주문 수량 합 = 최종 재고`, 거절 주문은 DRAFT, 구매자 잔액 합 = 초기 합 − 성공 결제액 |
| 한 사용자 잔액 10,000, **서로 다른 상품**의 4,000원 주문 3개 | 확정 2 · 잔액 부족 1 · 기술 오류 0 · 최종 잔액 2,000 | `초기 잔액 − 성공 결제액 합 = 최종 잔액`, 거절된 주문 상품의 재고 유지 |
| 잔액 10,000, 2,000 충전 + 7,000 확정 동시 | 둘 다 성공 · 기술 오류 0 · 최종 5,000 | `초기 잔액 + 성공 충전 − 성공 결제 = 최종 잔액` |

포인트 경쟁은 일부러 **상품을 서로 다르게** 두었다. 같은 상품이면 상품 잠금이 우연히 결제까지 직렬화해 사용자 잠금이 빠져도 통과할 수 있다. 실제로 `payPoint`의 잠금을 일반 조회로 바꿔 돌리면:
- 포인트 경쟁: 확정 **3**·잔액 부족 0 — 잔액 10,000원으로 12,000원 결제 (상품별 잠금은 같은 사용자의 포인트를 보호하지 못한다)
- 충전+결제: 최종 **3,000원** — 결제가 스냅샷 10,000을 읽고 3,000을 써서 충전 2,000을 덮어씀 (안전한 원자적 충전도, 다른 경로 하나가 우회하면 그 결과가 사라진다)
- 재고 경쟁은 그대로 통과 — 각 테스트가 의도한 잠금만 검증한다는 확인

**중간 실패** (`OrderTransactionTest`) — 여러 품목 주문의 확정에서, `@MockitoSpyBean OrderRepository`의 `save()`를 가로채 **flush → 트랜잭션 안에서 재고·잔액·주문 상태를 네이티브 쿼리로 기록 → 예외**. 트랜잭션 안에서는 재고 8·잔액 6,000·상태 CONFIRMED가 보였고(실제 SQL이 나감), 끝난 뒤 새로 읽은 DB는 DRAFT·결제액 null·재고 10/10·잔액 10,000으로 전부 변경 전과 같다.

## 8. 과제 체크리스트 대응

| 항목 | 근거 |
|---|---|
| 재고 0 포함 연결 상품과 브랜드가 함께 삭제되고 다른 대상은 유지 | `BrandAdminV1ApiE2ETest`, `ProductServiceIntegrationTest.DeleteAllByBrandId` |
| 실제 DB 변경 뒤 실패 유발, 전체 rollback을 별도 재조회로 확인 | `BrandRemovalTransactionTest`, `OrderTransactionTest` |
| 과거 주문 정보·금액·결제 결과와 접근·삭제 후 사용 제한 유지 | `BrandAdminV1ApiE2ETest`(과거 주문, 고객 상세 404, USER 403 + DB 무변경), `UserLikeV1ApiE2ETest`, `LikeV1ApiE2ETest` |
| 재고·포인트·주문 확정이 함께 commit/rollback | `OrderTransactionTest` |
| 선택한 제어가 실제 호출·SQL에 적용, 같은 행을 바꾸는 기존 경로가 우회하지 않음 | `ProductWritePathLockTest`, `OrderConcurrencyTest`(충전+결제) |
| 재고·포인트·충전+결제 경쟁의 성공·거절·기술 오류와 최종 DB 상태 | `OrderConcurrencyTest` |
| 재현용 대조군과 실제 서비스 검증 구분, 대기·자원 정리 | `LostUpdateControlTest` vs `OrderConcurrencyTest` — 모든 대기에 제한 시간, finally에서 latch 해제·executor 종료 |
| lint·ArchUnit·회귀 테스트 | `./gradlew :apps:commerce-api:check` — 206개 통과 |

선택 확장(다른 잠금 전략 비교, 재고 요청 12개 실험, 브랜드 삭제와 상품 등록 동시 실행)은 하지 않았다. 특히 브랜드 삭제와 같은 브랜드의 상품 등록이 겹치면 삭제된 브랜드를 참조하는 상품이 생길 수 있는데, 이 경쟁은 검증하지 않았다.
- 중간 실패를 테스트에서 어떻게 주입하고, rollback을 어떻게 별도 재조회로 확인할지
