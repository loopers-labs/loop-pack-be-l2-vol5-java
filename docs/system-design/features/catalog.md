# 카탈로그·브랜드·좋아요

기존 절 번호 참조: [4.2](points.md#42-포인트-충전).


기존 절을 기능별 기준 문서로 이동했다. 절 번호·계약·승인/검증 상태는 보존하며, 이동 자체는 새 설계 승인이나 실행 검증을 뜻하지 않는다.

읽기: [공통 API 계약](../common/api-contract.md)·[공통 모델](../common/domain-model.md)·[공통 의존](../common/architecture.md)을 함께 따른다.

고객 주문·PG API는 [주문 절](order-payment/api-contract.md#주문), 관리자 주문 API는 [주문 조회](order-payment/api-contract.md#주문-조회)에서 확인한다. 공통 DTO 설명과 null/생략 규칙은 공통 API 계약을 적용한다.

## 4.1 브랜드와 연관 상품 일괄 삭제

기존에 활성 상품이 있으면 브랜드 삭제를 거절하던 계약을, 브랜드와 연결 상품을 함께 삭제하는 계약으로 변경한다. 브랜드 삭제 시 연결된 미삭제 상품을 단일 bulk UPDATE로 Soft Delete하고, 브랜드 삭제와 하나의 트랜잭션으로 묶는다. 상품별 Entity 조회와 개별 UPDATE 반복을 피하기 위해 bulk 방식을 선택한다.

2026-10-07 브랜드 일괄 삭제의 구현·검증을 완료했다. 아래 계약과 검증 기준은 이후 회귀에도 유지한다. 실행계획 확인 범위는 4.1.6, 완료 근거는 4.1.7에 구분해 기록한다. 4.2~4.3의 공통 잠금·주문 동시성도 이후 구현·검증했으며 완료 근거는 4.3.5에 둔다.

### 4.1.1 대상과 보존 범위

상품 삭제 조건은 `brand_id = 요청한 brandId AND deleted_at IS NULL`이다. 재고가 0인 상품도 포함하며, 이미 삭제된 상품의 삭제·수정 시각과 다른 브랜드·상품은 변경하지 않는다. 연결 상품이 없는 활성 브랜드도 정상 삭제한다. 없는 브랜드와 이미 삭제된 브랜드는 상품 변경 전에 `BRAND_NOT_FOUND`로 거절한다.

상품에서는 `deleted_at`과 `updated_at`만 변경한다. 기존 재고·가격·생성 시각, Like 관계, StockHistory와 과거 Order·OrderItem의 품목·수량·단가·총액·포인트 결제 결과는 유지한다. 삭제는 재고 조정이나 거래 기록 삭제가 아니다.

### 4.1.2 호출 순서와 트랜잭션

```text
BrandAdminV1Controller.delete
  → Spring Transaction Proxy
    → BrandFacade.delete @Transactional
        1. BrandRepository.findActive(brandId)
           → 없거나 이미 삭제됐으면 BRAND_NOT_FOUND
        2. ProductRepository.softDeleteAllActiveByBrandId(brandId, deletedAt)
           → 단일 UPDATE 실행, 변경 행 수 0도 정상
        3. Brand.delete()
        4. BrandRepository.save(brand)
    → 정상 처리 후 commit 완료 시 Brand와 Product 전체 확정
    → commit 전 업무 거절·중간 처리 RuntimeException 전파 시 전체 rollback
```

`BrandFacade.delete()`를 기존 진입점과 하나의 쓰기 트랜잭션 경계로 유지한다. domain의 `ProductRepository`에 브랜드별 일괄 삭제 포트를 선언하고 infrastructure에서 bulk UPDATE를 구현한다. Facade에 JPQL·SQL을 두지 않고, 새 Domain Service나 별도 Removal Facade를 추가하지 않는다. Product별 `REQUIRES_NEW`, 단계별 독립 commit, 일부 성공 응답과 예외 삼키기는 사용하지 않는다.

commit 중 통신 장애로 완료 여부를 확인하지 못한 경우까지 오류 응답만으로 rollback을 단정하지 않는다. 이 한계의 기록은 장애 실험·복구 기능을 추가하라는 요구가 아니며, [5.1의 공통 실패 계약](../common/api-contract.md#51-공통-계약과-입력-정책)을 따른다.

확정한 Repository 계약은 `int softDeleteAllActiveByBrandId(Long brandId, ZonedDateTime deletedAt)`이다. domain의 `ProductRepository`에 선언하고, infrastructure의 `ProductRepositoryImpl`이 `ProductJpaRepository`의 `@Modifying` native SQL UPDATE에 위임한다. 반환값은 변경된 상품 수이며, 사전에 조회한 상품 수와 같아야 한다는 조건이나 최소 1개라는 조건은 두지 않는다.

기존 `BrandModel.delete(boolean hasActiveProducts)`의 상품 존재 여부 기반 거절 규칙은 제거하고, Brand 삭제는 상속한 `BaseEntity.delete()`를 사용한다. 단순히 부모 행동을 호출하기 위한 override는 추가하지 않는다. 삭제 거절을 위해 사용하던 `ProductRepository.existsActiveByBrandId()`와 대응하는 infrastructure·JPA Repository 메서드는 다른 사용처가 없다면 함께 제거한다. `BRAND_HAS_ACTIVE_PRODUCTS`도 변경 후 사용처가 없다면 제거하되, 부록의 과거 결정 설명은 보존한다. `BrandFixture`의 `delete(false)` 호출은 `delete()`로 변경하며, fixture는 데이터 준비 역할을 유지하고 일괄 삭제 유스케이스를 재현하지 않는다.

```sql
UPDATE product
   SET deleted_at = :deletedAt,
       updated_at = :deletedAt
 WHERE brand_id = :brandId
   AND deleted_at IS NULL;
```

상품들의 삭제·수정 시각에는 한 번 생성한 값을 사용한다. Brand는 기존 Entity 삭제 행동을 사용하며, Brand와 Product의 삭제 시각이 정확히 같아야 한다는 계약은 두지 않는다.

### 4.1.3 bulk와 영속성 컨텍스트

bulk UPDATE는 개별 `Product.delete()`, 변경 감지와 `@PreUpdate` 콜백을 거치지 않는다. 따라서 기존 `BaseEntity` 콜백에 맡기지 않고 `updated_at`도 쿼리에 명시한다. 상품별 업무 행동·검증·이벤트가 삭제에 추가되면 bulk 경로에서 이를 어떻게 보존할지 다시 검토한다.

이 흐름에서는 삭제 대상 Product Entity를 미리 조회하거나 bulk 이후 관리 객체를 계속 사용하지 않는다. Brand는 bulk 전에 조회하되 자신의 삭제 변경은 bulk 이후에 수행한다. 다만 이 순서만으로 다른 호출 경로에서 이미 적재한 Product까지 자동 동기화된다고 보장하지는 않는다.

bulk 전에 필요한 미반영 변경이 있다면 먼저 flush하고, 이후 영향을 받은 관리 객체를 다시 사용해야 한다면 재조회·refresh 또는 영속성 컨텍스트 정리를 판단한다. 무조건 `clear()`하면 아직 flush하지 않은 변경을 잃고 Brand도 분리될 수 있으므로 자동 clear를 기본 정책으로 두지 않는다. 현재 구현의 `@Modifying`은 `flushAutomatically`·`clearAutomatically`를 켜지 않으며 운영 흐름에 명시적 flush·clear를 추가하지 않았다. bulk 영속성 컨텍스트 테스트에서는 bulk 전에 적재한 Brand·Product가 계속 관리 상태이고 Product 객체가 자동 동기화되지 않는 점을 확인했다.

### 4.1.4 삭제 이후 사용 제한과 조회 방어

일괄 삭제된 상품은 고객·관리자 목록과 상세, 새 Like, 내 Like 목록, 새 주문, 관리자 수정·재고 변경·재삭제 대상에서 제외한다. 삭제 전에 만든 DRAFT 주문도 확정 시 잠금 조회로 상품 상태를 다시 확인해 하나라도 삭제됐다면 기존 `PRODUCT_NOT_FOUND`로 거절한다. 주문 확정은 4.3의 `Order → Point → Product` 순서를 따르므로 Point 검증·사용이 먼저 수행될 수 있지만, 실패 요청의 포인트·재고·주문·History 변경은 전체 rollback한다. Point 부족 등과 상품 삭제가 함께 있는 경우의 복합 오류 우선순위는 별도 계약으로 보존하지 않는다. 기존 자신의 Like 취소와 과거 주문 조회는 유지한다.

현재 `ProductQueryRepositoryImpl`은 상품 조회와 관련 count 쿼리에 Product와 Brand 양쪽의 `deletedAt IS NULL` 조건을 이미 적용한다. 이 조건을 유지하고 고객·관리자 상품 목록·상세와 내 Like 목록의 비노출을 검증한다. 삭제가 commit된 이후 시작된 새 조회에서, 경쟁으로 뒤늦게 등록된 상품도 삭제 Brand 조건으로 숨기는 방어 수단이다. 기존 스냅샷을 읽는 진행 중 트랜잭션에 즉시 반영된다는 보장은 하지 않는다.

조회 방어는 상품 행 자체의 삭제를 대신하지 않는다. 현재 명령용 `ProductRepository.findActive()`와 `findAllActiveByIds()`는 Product 자신의 삭제 여부만 확인하므로, `삭제 Brand + 미삭제 Product`가 남으면 화면에서 숨겨져도 상품 ID 기반 명령에서 사용할 가능성이 있다. 이번에는 이 한계를 기록하며 명령 경로의 Brand 조건 추가나 동시 등록 차단까지 구현한 것으로 간주하지 않는다.

### 4.1.5 동시성 범위와 향후 대안

브랜드 일괄 삭제 경로에는 별도의 명시적 잠금을 추가하지 않는다. 보장 범위는 한 삭제 요청에서 bulk UPDATE가 변경한 Product와 Brand 변경의 원자성이다. 4.3의 주문·개별 상품 변경 잠금 규칙을 이 bulk 경로까지 확장한 것은 아니다. Brand 삭제와 상품 등록·수정·재고 변경·DRAFT 확정·Like 또는 주문 생성 간 경쟁의 정합성은 별도 확장 범위다.

MySQL InnoDB의 REPEATABLE READ를 전제로, 일반 SELECT의 MVCC 스냅샷과 UPDATE의 배타적 잠금을 구분한다. bulk UPDATE는 사용 인덱스와 검색 범위에 따라 record·next-key/gap lock으로 INSERT를 대기시킬 수 있다. 그러나 잠금 해제 후 INSERT가 진행될 수 있으므로, 벌크 잠금 자체가 삭제된 Brand의 상품 등록을 거부하는 업무 규칙은 아니다. 실제 격리 수준과 실행계획은 구현·실험 시 확인한다.

```text
TX B: 활성 Brand 확인 후 상품 등록 준비
TX A: Product bulk 삭제, Brand 삭제
TX B: INSERT 시도 → 잠금과 겹치면 대기
TX A: commit 및 잠금 해제
TX B: Brand를 다시 검사하지 않고 INSERT·commit → 미삭제 상품 잔존 가능
```

이 경쟁 자체를 팬텀 리드로 부르지 않는다. 같은 조건의 반복 조회 결과가 달라지는 읽기 현상과, 삭제·등록의 업무 규칙이 엇갈리는 문제를 구분한다. RR의 일반 SELECT와 UPDATE가 대상으로 삼는 데이터도 반드시 같지는 않다.

다음 두 대안은 검토 후보로만 남기며 이번에는 구현하지 않는다.

- 사후 보정 배치: 삭제 Brand 아래의 미삭제 Product를 찾아 삭제한다. 보정 전 사용 가능성을 막지는 못하며, 배치를 구현하기 전에는 자동 복구도 보장하지 않는다.
- 공통 Brand 잠금: 상품 등록과 Brand 삭제 양쪽이 같은 Brand 행을 `FOR UPDATE`로 조회하고, 잠금을 획득한 뒤 최신 삭제 여부를 검사한다. 기존 Product 행만 잠그거나 삭제 경로만 Brand를 잠그는 것으로 새로운 상품 INSERT를 업무적으로 차단했다고 보장하지 않는다.

### 4.1.6 인덱스와 설계 재검토 기준

**2026-10-16 변경**: 상품 목록 인덱스를 라이팅 실험에서 고른 구성으로 정리하면서 `product(brand_id)` 단독 인덱스를 제거했다. 현재 product 보조 인덱스는 `(brand_id, price)`·`(brand_id, created_at)`·`(brand_id, deleted_at)`이다([4.4.1](product-cache.md#441-대상과-보존-계약)). 단독 인덱스는 세 복합 인덱스의 `brand_id` 선두 범위와 중복된다. D0(상품 100,000)에서 일괄 삭제 UPDATE를 EXPLAIN만 해 보니, 상품 809개 브랜드는 단독 인덱스 구성과 새 구성 모두 `brand_id` 범위 809행(새 구성은 `(brand_id, price)` 선택), 상품 20,000개 브랜드는 두 구성 모두 PRIMARY 전체 스캔이었다. 갱신 열 `deleted_at`을 포함한 `(brand_id, deleted_at)`은 이 UPDATE에 선택되지 않았다. 실제 UPDATE 시간·잠금은 측정하지 않았다. 인덱스 검사 테스트는 "brand_id 단독 인덱스 하나"에서 "합의한 세 복합 인덱스만 있고 같은 열 구성의 중복이 없음"으로 바꿨다. 아래 문단은 2026-10-07 결정 당시 기록이다.

이번 구현에 `product(brand_id)` 단독 인덱스를 추가한다. 목적은 bulk UPDATE에서 해당 Brand의 상품을 찾는 탐색 비용을 줄이는 것이다. `brand_id`로 대상을 찾고 `deleted_at IS NULL`을 추가 검사한다. 인덱스는 `ProductModel`의 `@Table(indexes = …)`에 `@Index(columnList = "brand_id")`로 선언하고, local·test의 기존 `ddl-auto: create` 설정에 따른 스키마 생성으로 반영한다. 새 마이그레이션 체계는 도입하지 않는다. 어노테이션 선언만으로 스키마 생성을 하지 않는 기존 DB까지 변경된다고 보장하지 않으며, 동일한 인덱스를 중복 생성하지 않는다. 실제 사용 여부는 실행계획으로 확인한다.

2026-10-07 브랜드 bulk의 EXPLAIN은 정상 삭제·rollback 증명이 아니라 `brand_id` 인덱스 선택의 보조 근거로 확인했다. 테스트에서 수집한 native bulk SQL·바인딩을 기준으로, 사용자가 기존 DB `loopers`의 버전·격리 수준·DDL·인덱스·건수를 확인한 뒤 `brand_id = 1 AND deleted_at IS NULL` 조건의 UPDATE에 EXPLAIN을 직접 실행했다. 당시 product는 0건이었고 결과는 `type=range`, `key=IDX1td6gorl25rsvufiiive2svlx`, `rows=1`, `Extra=Using where`였다. `rows=1`은 추정치이며 실제 대상 건수가 아니다.

테스트 DB의 단독 인덱스 생성·중복 없음과 실제 데이터의 bulk 동작 테스트를 함께 근거로 삼아, 사용자 결정으로 이번 브랜드 bulk의 인덱스 실행계획 확인 항목을 충족한 것으로 인정했다. 제안한 5,000건 삽입·ANALYZE·추가 EXPLAIN은 진행하지 않았으며, 데이터가 있는 조건의 bulk 실행계획·탐색 효율·성능은 미검증이다. 두 DB의 MySQL 버전과 격리 수준은 8.0.46·REPEATABLE-READ로 같지만, 사용자 테이블 collation은 utf8mb4_0900_ai_ci, Testcontainers 서버 옵션은 utf8mb4_general_ci이며 테스트 테이블의 실제 collation은 미확인이다.

`(brand_id, deleted_at)` 복합 인덱스는 필수가 아니다. 이미 삭제된 상품이 많이 누적되어 불필요한 탐색이 커지면 비교한다. 대부분 미삭제라면 탐색 감소 이점이 작을 수 있고, 복합 인덱스의 공간 및 삭제 시 인덱스 갱신 비용도 고려한다.

이번 실습에서는 성능 측정을 필수 검증 범위에 포함하지 않는다. commit을 포함한 삭제 API 응답시간 2초 이내, 3초 초과의 반복은 향후 설계 재검토를 위한 잠정 참고 기준으로만 둔다. 측정으로 도출한 수치나 성능 보장이 아니며, 운영 요구와 향후 측정 결과에 따라 조정한다. API timeout·거절 조건이나 자동 비동기 전환 규칙으로 사용하지 않는다.

- 응답 지연이 반복되거나 다른 요청의 잠금 대기가 커지면 실행계획·탐색량·인덱스·잠금 대기를 먼저 확인한다.
- 1,000개 같은 임의의 상품 수 API 상한은 추가하지 않는다. 향후 성능 측정 시 상품 수·전체 테이블 규모·동시 요청·실행 환경을 함께 기록한다.
- 개선 후에도 동기 처리가 적절하지 않으면 비동기 또는 분할 배치를 검토한다. 비동기는 DB 작업 자체를 줄이지 않으며, 분할 commit은 전체 원자성 계약을 바꾸므로 삭제 진행 상태·응답 의미·재시도와 실패 처리까지 별도 설계한다.

### 4.1.7 검증 계획

과거 완료 근거는 [브랜드 삭제 실행 기록](../verification/core-features.md#브랜드-일괄-삭제--2026-10-07-실행-기록)으로 이동했다.

실습의 핵심 검증은 정상 일괄 삭제와 중간 실패 시 전체 rollback이다. 기존 검증은 새 계약에서도 유효한 경우에만 유지한다. 브랜드 이름 검증·등록·조회·수정, 연결 상품 없는 Brand 삭제, 연결 상품이 모두 삭제된 Brand 삭제, 다른 Brand·Product 보존, 없는·이미 삭제된 Brand 거절은 유지한다. `활성 Product가 있으면 Brand 삭제 거절`처럼 사라진 규칙을 검증하는 도메인·통합·API 테스트는 제거한다. 기존 테스트마다 다른 테스트를 대응시켜 대체할 필요는 없으며, 새 일괄 삭제와 rollback 계약은 별도로 검증한다. 이는 폐기된 규칙의 검증을 정리하는 것이며, 새 설계에서도 유효한 테스트나 lint·ArchUnit 규칙을 완화하는 것은 아니다.

정상 일괄 삭제 테스트에서는 재고 0을 포함한 연결 미삭제 Product 전체와 Brand의 삭제, 연결 상품 없는 Brand 성공, 없는·삭제된 Brand 오류, 이미 삭제된 상품의 삭제·수정 시각 보존, 다른 Brand·Product와 과거 주문 보존을 확인한다.

rollback 통합 테스트는 Brand와 연결 Product 2개(하나는 재고 0), 다른 Brand의 Product와 과거 주문을 준비하고 먼저 commit한다. 테스트 전체를 하나의 부모 트랜잭션으로 감싸지 않으며, 실제 Spring bean의 `BrandFacade.delete()`를 프록시를 통해 호출해 유스케이스 트랜잭션이 시작·종료되게 한다.

rollback 테스트는 실제 MySQL에서 Product bulk UPDATE를 실행한 뒤, 테스트 구성의 다음 Brand 저장 경계에서 RuntimeException을 유발한다. 전체 Repository를 mock하지 않고, bulk 쿼리를 생략하거나 실제 SQL 실행 전에 실패시키지 않는다. 운영 코드에 테스트용 실패 분기·sleep·barrier를 넣지 않는다.

Facade 트랜잭션이 끝난 뒤 새로운 트랜잭션·영속성 컨텍스트에서 Brand와 모든 대상 Product의 삭제·수정 시각 등 상태가 작업 전과 같은지 확인한다. 활성 데이터 조회만으로 판단하지 않고 삭제 행도 조회할 수 있는 경로로 변경 전후 상태를 비교한다. 다른 대상과 과거 주문도 유지되고 예외가 호출자에게 전파되어야 한다. 단순 `save()` 호출 횟수나 같은 관리 객체의 값은 rollback 증거로 사용하지 않는다.

HTTP 테스트와 중간 실패 rollback 통합 테스트의 역할은 구분한다. HTTP에서는 관리자 삭제 성공의 `200`과 데이터 없는 성공 응답(내부 `data` 값은 null이며 실제 JSON에서는 생략), 없는·이미 삭제된 Brand의 `404 BRAND_NOT_FOUND`, 일반 사용자·익명 요청의 `403`을 실제 DB 결과와 연결해 확인한다. 성공 시 대상 전체가 삭제되고, 거절 시에는 Brand·Product가 변경되지 않아야 한다. 관리자 변경 요청과 역할 거절 테스트에는 기존 정책에 따라 유효한 CSRF 입력을 보낸다. 실패 주입에 의한 전체 rollback은 위 실제 MySQL Facade 통합 테스트로 증명하며, HTTP 계층에서 같은 실패 주입을 반복하는 것을 필수로 두지 않는다.

변경 영향에 대한 회귀 테스트로 삭제 이후 조회·사용 제한, 기존 Like 취소와 DRAFT 확정 거절을 검증한다. 영향받는 브랜드·상품·포인트·주문 테스트와 기존 lint(Checkstyle)·ArchUnit도 실행한다.

브랜드 삭제와 상품 등록의 동시 실행 테스트는 선택 확장이다. 4.1.5의 사후 보정 배치와 공통 Brand 잠금은 향후 대안으로 유지하되, 이번에 구현하거나 검증한 것으로 간주하지 않는다.

## 5.1 카탈로그 입력·DTO 계약

### 카탈로그 입력 정책 (5.1)

|항목|잠정 기준|잘못된 입력|
|---|---|---|
|브랜드명|앞뒤 공백 제거 후 1~100자|`400 INVALID_BRAND_NAME`|
|상품명|앞뒤 공백 제거 후 1~100자|`400 INVALID_PRODUCT_NAME`|
|상품 가격|1~100,000,000원인 정수|`400 INVALID_PRODUCT_PRICE`|
|재고 수량|0 이상의 정수|`400 INVALID_STOCK_QUANTITY`|

### 상품 목록 정렬 (5.1)

|정렬 값|주 정렬|동률의 보조 정렬|
|---|---|---|
|`latest`|생성 시각 내림차순|상품 ID 내림차순|
|`price_asc`|가격 오름차순|상품 ID 오름차순|
|`likes_desc`|좋아요 수 내림차순|상품 ID 내림차순|

### 카탈로그 요청 DTO (5.1)

|요청 모델|필드|
|---|---|
|`BrandSaveRequest`|`name: String`|
|`ProductCreateRequest`|`brandId: Long`, `name: String`, `price: Long`|
|`ProductUpdateRequest`|`name: String`, `price: Long`|
|`StockUpdateRequest`|`quantity: Long`|

### 카탈로그 응답 DTO (5.1)

|응답 모델|필드|
|---|---|
|`BrandResponse`|`id: Long`, `name: String`|
|`AdminBrandResponse`|`id: Long`, `name: String`|
|`ProductResponse`|`id: Long`, `brandId: Long`, `brandName: String`, `name: String`, `price: Long`, `likeCount: Long`, `stockQuantity: Long`|
|`AdminProductResponse`|`id: Long`, `brandId: Long`, `brandName: String`, `name: String`, `price: Long`, `stockQuantity: Long`|
|`LikeResponse`|`id: Long`, `userId: Long`, `productId: Long`|
|`StockResponse`|`productId: Long`, `quantity: Long`|

고객 상품 조회의 `stockQuantity`는 조회 시점의 현재 가용 재고 수량이다. 상품 목록·상세와 내 좋아요 목록에서 같은 `ProductResponse`를 사용한다. 별도의 품절 여부 필드는 이번 구현에서 우선 제공하지 않으며, 필요 여부는 기획 확인이 필요한 잠정 정책이다. 조회 이후 재고가 변경될 수 있으므로 주문 확정 시에는 저장된 OrderItem을 기준으로 재고를 다시 검증한다.

### 카탈로그 API별 data 타입 (5.1)

|API|data 타입|
|---|---|
|고객 브랜드 상세|`BrandResponse`|
|고객 상품 목록|`PageResponse<ProductResponse>`|
|고객 상품 상세|`ProductResponse`|
|좋아요 등록|`LikeResponse`|
|좋아요 취소|`null`|
|내 좋아요 목록|`PageResponse<ProductResponse>`|
|관리자 브랜드 목록|`PageResponse<AdminBrandResponse>`|
|관리자 브랜드 등록·상세·수정|`AdminBrandResponse`|
|관리자 브랜드 삭제|`null`|
|관리자 상품 목록|`PageResponse<AdminProductResponse>`|
|관리자 상품 등록·상세·수정|`AdminProductResponse`|
|관리자 상품 삭제|`null`|
|관리자 재고 변경|`StockResponse`|

## 5.2 고객 API

모든 고객 API는 `X-USER-ID` 헤더를 필수로 받는다.

이 절에 함께 기술한 PG 콜백은 고객 API가 아닌 외부 수신 경계이며 고객 헤더 규칙을 적용하지 않는다.

모든 고객 API는 공통 요청자 식별 단계에서 User 존재 여부까지 확인한다. 테스트에서는 저장된 User ID를 헤더에 사용하며, 저장되지 않은 ID는 `404 USER_NOT_FOUND`로 검증한다. Point API와 이전 POINT 기록에는 필요한 Point fixture를 준비하지만 PG 주문의 요청자에게 Point가 있어야 한다는 전제는 두지 않는다. 유효한 User가 다른 사람의 Like나 Order를 조회·변경하려는 경우에는 자원 소유권 규칙에 따라 각각 `LIKE_NOT_FOUND`, `ORDER_NOT_FOUND`로 응답한다.

### 브랜드·상품

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|브랜드 상세|`GET /api/v1/brands/{brandId}`|브랜드 ID|`200`, 활성 브랜드 정보|`BRAND_NOT_FOUND`|
|상품 목록|`GET /api/v1/products`|선택적 `brandId`, `page`, `size`, `sort`|`200`, 상품·브랜드·좋아요 수·현재 재고 수량과 페이지 정보|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|상품 상세|`GET /api/v1/products/{productId}`|상품 ID|`200`, 상품·브랜드·좋아요 수·현재 재고 수량|`PRODUCT_NOT_FOUND`|

고객 조회에는 삭제된 Brand와 Product를 노출하지 않는다. 상품 목록 요청에 `brandId`가 주어졌지만 해당 ID의 브랜드가 존재하지 않거나 삭제된 경우에는 `404`로 처리하지 않고 `200 OK`와 빈 페이지를 반환한다.

고객 상세의 상품명·Brand 이름·가격·재고만 JSON String cache-aside로 저장하며 좋아요 수는 DB 조회로 합성한다. 기존 응답 필드와 application 합성을 유지한다. 기본정보는 공통 afterCommit callback에서 commit 뒤 동기 DEL하고 시험 10초/평시 5분의 고정 TTL을 hit에서 연장하지 않는다. JSON 형식 오류는 miss·DB 재조회/재채움·로그, cache 후처리 실패만 있으면 DB 성공 결과를 유지한다. 좋아요 count key/증감은 없고 삭제 비노출·소유권·주문 판단은 완화하지 않는다. [4.4 상품 조회](product-cache.md#44-상품-조회)의 실패·늦은 fill·실제 만료/재채움 계약을 따른다.

### 좋아요

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|좋아요 등록|`POST /api/v1/products/{productId}/likes`|상품 ID|`201`, 생성된 Like 관계|`PRODUCT_NOT_FOUND`, `LIKE_ALREADY_EXISTS`|
|좋아요 취소|`DELETE /api/v1/products/{productId}/likes`|상품 ID|`200`, 데이터 없는 성공 응답|`LIKE_NOT_FOUND`|
|내 좋아요 목록|`GET /api/v1/users/{userId}/likes`|경로의 사용자 ID, 선택적 `page`, `size`, `sort`|`200`, 활성 상품에 대한 자신의 좋아요 페이지|`USER_NOT_FOUND`, `INVALID_PAGE_REQUEST`, `INVALID_SORT`|

- 같은 User와 Product의 Like는 하나만 존재한다. 중복 등록은 `409 LIKE_ALREADY_EXISTS`로 거절하고 좋아요 수를 변경하지 않는다.
- `LikeFacade`는 등록 시 `ProductRepository`로 Product의 존재와 삭제 여부를 확인하지만 Product를 변경하지 않고 Like만 생성해 저장한다.
- 취소할 Like 관계가 없으면 `404 LIKE_NOT_FOUND`로 응답한다.
- 삭제된 Product에는 새 Like를 등록할 수 없고 내 좋아요 목록에서도 제외한다. 다만 삭제 전에 생성한 자신의 Like 관계는 취소할 수 있으므로, 이 경우 Product의 삭제 여부와 관계없이 Like를 찾아 삭제한다.
- 내 좋아요 목록에서 `X-USER-ID`는 요청자를, 경로의 `{userId}`는 조회 대상을 식별한다. 공통 경계에서 확인한 요청자 ID와 경로의 사용자 ID를 비교하고, 두 값이 다르면 다른 사용자의 관계를 노출하지 않고 `404 USER_NOT_FOUND`로 응답한다. 두 값이 같으면 `LikeFacade`가 `ProductQueryRepository`로 자신의 좋아요 상품 목록을 조회한다.

## 5.3 관리자 API

모든 관리자 API는 Spring Security에서 인증된 `ADMIN` 역할을 요구한다.

### 브랜드

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|브랜드 목록|`GET /api-admin/v1/brands`|선택적 `page`, `size`, `sort`|`200`, 활성 브랜드 페이지|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|브랜드 등록|`POST /api-admin/v1/brands`|본문 `name`|`201`, 등록한 브랜드|`INVALID_BRAND_NAME`|
|브랜드 상세|`GET /api-admin/v1/brands/{brandId}`|브랜드 ID|`200`, 활성 브랜드 상세|`BRAND_NOT_FOUND`|
|브랜드 수정|`PUT /api-admin/v1/brands/{brandId}`|본문 `name`|`200`, 수정한 브랜드|`BRAND_NOT_FOUND`, `INVALID_BRAND_NAME`|
|브랜드 삭제|`DELETE /api-admin/v1/brands/{brandId}`|브랜드 ID|`200`, 브랜드와 연결 미삭제 상품 삭제 완료, JSON의 `data` 필드 생략|`BRAND_NOT_FOUND`|

`BrandFacade`가 Brand와 연결된 모든 미삭제 Product를 같은 트랜잭션에서 Soft Delete한다. 재고 0인 상품도 포함하고, 연결 상품이 없는 활성 Brand도 성공한다. 상품 존재에 따른 `409 BRAND_HAS_ACTIVE_PRODUCTS`는 더 이상 이 API의 거절 사유가 아니다. 없는·이미 삭제된 Brand는 기존 `404 BRAND_NOT_FOUND`를 유지한다. 다른 Brand·Product, 과거 주문의 품목·금액·결제 결과는 보존한다.

중간 실패는 일부 성공으로 응답하지 않으며 이번 요청의 Brand·Product 변경을 모두 rollback한다. 상세 호출 순서, 삭제 이후 행동, 동시성 보장 한계와 성능 재검토 기준은 [4.1](#41-브랜드와-연관-상품-일괄-삭제)을 따른다. 이번에는 상품 수 상한이나 비동기 접수 응답을 추가하지 않는다.

### 상품·재고

|기능|Method & Path|입력|성공 결과|대표 오류|
|---|---|---|---|---|
|상품 목록|`GET /api-admin/v1/products`|선택적 `page`, `size`, `sort`|`200`, 활성 상품 페이지|`INVALID_PAGE_REQUEST`, `INVALID_SORT`|
|상품 등록|`POST /api-admin/v1/products`|본문 `brandId`, `name`, `price`|`201`, 재고 0으로 등록한 상품|`INVALID_REQUEST`, `BRAND_NOT_FOUND`, `INVALID_PRODUCT_NAME`, `INVALID_PRODUCT_PRICE`|
|상품 상세|`GET /api-admin/v1/products/{productId}`|상품 ID|`200`, 활성 상품 상세|`PRODUCT_NOT_FOUND`|
|상품 수정|`PUT /api-admin/v1/products/{productId}`|본문 `name`, `price`|`200`, 수정한 상품|`PRODUCT_NOT_FOUND`, `INVALID_PRODUCT_NAME`, `INVALID_PRODUCT_PRICE`|
|상품 삭제|`DELETE /api-admin/v1/products/{productId}`|상품 ID|`200`, 데이터 없는 성공 응답|`PRODUCT_NOT_FOUND`|
|재고 변경|`PUT /api-admin/v1/products/{productId}/stock`|본문 `quantity`|`200`, 변경 후 최종 재고 수량|`PRODUCT_NOT_FOUND`, `INVALID_STOCK_QUANTITY`|

- `ProductFacade`는 등록 시 `BrandRepository`로 Brand의 존재와 삭제 여부를 확인하지만 Brand를 변경하지 않고 초기 Stock이 0인 Product만 생성해 저장한다.
- Product 수정은 Brand를 변경하지 않는다. 수정 요청에도 `brandId`를 받지 않으며 기존 관계를 유지한다.
- 재고 변경의 `quantity`는 증감량이 아니라 예약을 제외한 가용 재고의 최종 수량이다. 주문 예약을 초기화하지 않는다. Stock은 변경 전후 수량과 변경량을 담은 StockChange를 반환하고, `StockHistoryModel.changedByAdmin(productId, change)`가 관리자 변경 원인을 포함한 이력을 생성한다.
- 삭제된 Product는 수정과 재고 변경의 대상이 될 수 없으며 `404 PRODUCT_NOT_FOUND`로 응답한다.
- `ProductFacade.update/delete/changeStock`은 주문 차감과 같은 Product 행의 비관적 잠금 규칙에 참여한다. 대상 조회·잠금 후 기존 존재/활성·입력 검증 순서를 유지하며, 관리자 최종 설정은 보호된 현재 재고의 before/after로 StockHistory를 남긴다. 상품 수정·삭제는 재고 이력을 추가하지 않고 저장 주문의 단가·수량·금액을 바꾸지 않는다. 브랜드 bulk 경쟁까지 보장 범위를 확장하지 않는다.

### 잠정 관리자 조회 정책

Soft Delete된 Brand와 Product를 관리자 목록·상세에 포함할지는 기획 확인이 필요한 정책이다. 결정 전까지는 관리자 조회에도 활성 데이터만 반환하고, 삭제 데이터 조회는 이번 구현 범위에서 제외한다. 기획 결정으로 정책이 바뀌면 관리자 조회 계약과 테스트를 함께 수정한다. 대안별 비용과 잠정 선택의 근거는 [부록 A.13](../decisions/domain-and-api.md#a13-관리자-조회의-삭제-데이터-노출-정책)에서 비교한다.

이 잠정 정책과 무관하게 삭제된 대상의 수정·재고 변경·재삭제는 허용하지 않는다.

## 5.4 카탈로그 대표 규칙의 테스트 기대값

|규칙|주어진 상태와 요청|기대 결과|
|---|---|---|
|브랜드 필터 결과 `[잠정]`|존재하지 않거나 삭제된 `brandId`로 상품 목록 조회|`200 OK`, 비어 있는 페이지 반환|
|고객 상품의 재고 수량|재고 5인 상품을 조회한 뒤 관리자가 최종 재고를 2로 변경하고 다시 조회|상품 목록·상세와 내 좋아요 목록의 `stockQuantity`에 조회 시점의 수량을 반환하며, 주문 확정 시에도 재고를 다시 검증|
|좋아요 중복 방지|이미 Like가 있는 상품에 재등록|`409 LIKE_ALREADY_EXISTS`, 관계와 좋아요 수 유지|
|삭제 상품 좋아요 취소|삭제 전 생성한 자신의 Like 취소|성공하고 Like 관계 삭제|
|브랜드 일괄 삭제|재고 0과 양수인 미삭제 Product가 연결된 Brand 삭제|`200 OK`, Brand와 연결 미삭제 Product 삭제·비노출, 다른 대상과 과거 주문 유지|
|빈 브랜드 삭제|연결 Product가 없는 활성 Brand 삭제|`200 OK`, Brand 삭제|
|브랜드 삭제 중간 실패|실제 Product bulk UPDATE 후 다음 Brand 저장 경계에서 예외|예외 전파, 새 DB 조회에서 Brand와 대상 Product 전체 원상태|
|상품 재고 변경|현재 수량 5, 최종 수량 2 요청|재고 2와 변경 전후 값을 가진 StockHistory 저장|
|삭제 대상 재삭제|Soft Delete된 Brand 또는 Product 삭제|각각 `404 BRAND_NOT_FOUND`, `404 PRODUCT_NOT_FOUND`|

실행 결과: [브랜드 삭제 기록](../verification/core-features.md#브랜드-일괄-삭제--2026-10-07-실행-기록).
