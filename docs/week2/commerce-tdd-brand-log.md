# 브랜드 도메인 TDD 실행 기록

2026-09-18 재고 규칙·사용자 식별·브랜드 이름 검증 다음으로 `Brand` 도메인 모델의 생성·이름 변경을 구현하고, 후속 TDD에서 논리 삭제 상태와 미삭제 상품 존재에 따른 삭제 조건 서비스를 연결했다. 현재 위치는 [TDD 계획의 3단계](commerce-tdd-plan.md#현재-단계와-이번-구현) 초반이다. P02와 BRAND-01~06의 첫 구현 기록을 유지하고, P00·P04·P05와 BRAND-LIFECYCLE-01~06·BRAND-DELETE-01~07의 후속 기록을 구분한다. 이 문서는 도메인 단위 단계의 이력이며, 이후 DB 매핑 결과는 [영속성 실행 기록](commerce-tdd-brand-persistence-log.md)에 분리한다. API-16·18의 유스케이스·HTTP 연결은 아직 미구현이다. 사용자 fixture는 유지하며 로그인 기능은 추가하지 않았다.

## 생성·이름 변경 단계의 구현 범위와 책임

| 파일 | 책임 |
| --- | --- |
| [Brand](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/Brand.java) | 현재 이름 소유, 생성·이름 변경, 실패 시 기존 이름 보존 |
| [BrandName](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandName.java) | 기존 공백 정리·필수값·코드 포인트 길이 규칙을 그대로 재사용 |
| [BrandTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandTest.java) | 생성·변경 경로의 규칙 연결, 실패 상태 보존·후속 변경과 경계 13개 실행 사례 |

`Brand`는 새 `BrandName`의 생성이 성공한 뒤 필드를 교체한다. 이름 검증을 중복 작성하지 않으며 별도 setter나 검증을 우회하는 생성자를 제공하지 않는다. 실패 사유는 기존 `BrandNameException`을 유지한다.

기존 Layer-first의 `domain/brand`에 추가하고 모듈·ArchUnit 규칙은 변경하지 않았다. 다른 계층·HTTP·JPA에 의존하지 않으며 이 단계에서는 이름만 다루므로 저장소 인터페이스를 추가하지 않았다. 순수 도메인 구현이 JPA 금지나 별도 persistence 모델 채택을 의미하지는 않는다.

## 생성·이름 변경의 실제 Red → Green → Refactor

| 순서·대상 | 실제 확인한 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / BRAND-01 | 최소 골격의 getName이 null을 반환하여 1개 중 1개 실패 | BrandName을 생성해 소유하고 정리된 이름을 반환. 1개 통과 |
| 2 / BRAND-02·03 | null·공백·한글/이모지 101자 생성 거절 4개는 기존 위임으로 통과. 이름 변경 골격은 기존 이름을 그대로 두어 6개 중 1개 실패 | 새 BrandName 생성 후 기존 필드 교체. 6개 통과 |
| 3 / BRAND-04~06 | 잘못된 이름 변경 4개, 한글/이모지 100자 생성·변경 2개, 실패 후 유효한 변경 1개가 기존 구현으로 통과 | 추가 구현 없이 전체 BrandTest 13개 통과 |
| 4 / Refactor | 생성·변경 테스트의 반복된 예외 사유 검증을 보조 메서드로 추출 | 상태 보존 assertion을 유지하고 전체 검사로 재검증 |

이미 통과하는 오류·경계 사례를 인위적으로 실패시키지 않았다. 기존 BrandNameTest의 13개 사례는 유지하며 이번 테스트는 Brand의 두 입력 경로가 해당 규칙을 사용하는지와 변경 전후 상태를 검증한다.

## 생성·이름 변경 단계의 검사 결과

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.domain.brand.BrandTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

리팩터링 후 전체 **86개 테스트**가 통과했다. 새 BrandTest 13개와 기존 73개(BrandNameTest·사용자 식별·재고·ArchUnit 포함)이며 실패·오류·건너뜀은 0개다. Checkstyle main/test의 위반도 0개다. 변경 파일과 구현 범위의 문서 일치를 검토했으며 `git diff --check`, 문서 9개의 로컬 링크 92개·앵커·코드 블록 검사도 통과했다.

## 논리 삭제 상태의 후속 TDD

`Brand`의 `deletedAt`을 단일 상태로 보관하고 `isDeleted()`는 해당 값의 존재 여부로 판단한다. 최초 삭제 때 전달받은 서버 시각을 기록하고, 재삭제는 이름과 최초 삭제 시각을 유지한다. 삭제된 브랜드의 이름 변경은 [BrandStateException](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandStateException.java)의 `DELETED_BRAND` 사유로 거절한다. 이름 값 자체의 실패는 기존 `BrandNameException`과 구분한다.

`delete(ZonedDateTime)`는 같은 `domain/brand` 패키지에서만 호출할 수 있다. 이 단계에서는 상태 전환만 구현했고, 아래 후속 TDD에서 도메인 서비스의 미삭제 상품 존재 검사와 연결했다. DB 잠금은 아직 없다. 삭제 시각은 HTTP에서 고객이 입력하는 값이 아니다. 첫 삭제에 서버 시각이 없으면 `Objects.requireNonNull`로 내부 호출 오류를 드러내며 상태를 유지한다. 이 검사를 HTTP 400 등 새 업무 정책으로 매핑하지 않았다.

[BrandLifecycleTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandLifecycleTest.java)는 다음 6개 규칙의 7개 실행 사례를 검증한다. 기존 BrandTest 13개와 BrandNameTest 13개는 그대로 유지했다.

| 순서·대상 | 실제 확인한 Red 또는 추가 사례 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / BRAND-LIFECYCLE-01·02 | 새 객체의 미삭제 상태는 통과. 삭제 골격이 시각을 기록하지 않아 2개 중 1개 실패 | deletedAt 저장과 삭제 여부 조회 구현 후 2개 통과 |
| 2 / BRAND-LIFECYCLE-03 | 다음 날 시각으로 재삭제하면 최초 시각이 덮어써져 3개 중 1개 실패 | 이미 삭제되었으면 추가 변경 없이 반환. 3개 통과 |
| 3 / BRAND-LIFECYCLE-04 | 삭제 후 새 이름·기존과 같은 이름의 변경이 모두 허용되어 5개 중 2개 실패 | 이름 변경 전에 삭제 상태를 검사해 DELETED_BRAND로 거절. 5개 통과 |
| 4 / BRAND-LIFECYCLE-05·06 | 변경한 이름의 삭제 후 보존은 통과. 첫 삭제에 null 시각을 넣어도 거절하지 않아 7개 중 1개 실패 | 첫 삭제 시각의 내부 필수 조건 추가. 7개 통과 |
| 5 / Refactor | 반복된 테스트 시각을 고정 fixture 상수로 추출 | 실제 시간 대기 없이 서로 다른 두 시각으로 재삭제를 검증하고 전체 검사 실행 |

삭제 상태와 잘못된 새 이름이 동시에 있는 요청의 HTTP 오류 우선순위는 확정하지 않았다. 삭제 후 변경 테스트는 유효한 이름으로 검증했다. 미래·과거 시각 제한, DB 시각 정밀도, 복원 기능도 추가하지 않았다. 이 단계 당시 BaseEntity 상속·JPA 매핑 선택은 후속 설계였다. 이후 영속성 단계에서 Brand 직접 매핑·BaseEntity 미상속을 적용했다.

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.domain.brand.BrandLifecycleTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

리팩터링 후 전체 **93개 테스트**가 통과했다. 새 생명주기 7개와 기존 86개이며 실패·오류·건너뜀은 0개다. 기존 브랜드 이름·생성·변경, 사용자 식별·재고 규칙과 ArchUnit도 포함한다. Checkstyle main/test의 위반은 0개다. 코드·문서 변경을 검토하고 `git diff --check`, 문서 9개의 로컬 링크 96개·앵커·코드 블록 검사도 통과했다.

## 미삭제 상품 존재 조건의 후속 TDD

브랜드의 상태 전환과 다른 도메인의 조회를 조합하는 책임을 분리했다. 다음 파일은 새로 추가했으며 기존 Brand·이름·생명주기 규칙과 테스트는 변경하지 않았다.

| 파일 | 책임 |
| --- | --- |
| [BrandDeletionService](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandDeletionService.java) | 브랜드 조회 → 재삭제 분기 → 미삭제 상품 존재 검사 → Brand 상태 전환·반환 |
| [BrandRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandRepository.java) | 삭제된 행도 포함한 브랜드 조회 약속 |
| [ProductRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/product/ProductRepository.java) | 해당 브랜드의 미삭제 상품 존재 조회 약속. 재고 0 포함·삭제 상품 제외 |
| [BrandDeletionException](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandDeletionException.java) | BRAND_NOT_FOUND·NON_DELETED_PRODUCTS_EXIST 사유. HTTP 응답과 분리 |
| [BrandDeletionServiceTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandDeletionServiceTest.java) | 조회 대역으로 삭제 조건·대상 구분·오류·재삭제·재시도 7개 사례 검증 |

서비스는 `brandId`로 조회한 객체만 변경한다. ID와 Brand를 별도 인자로 받지 않아 다른 브랜드의 상품 결과를 잘못 연결하는 위험을 줄였다. Brand에 저장 ID나 생성 전략을 추가하지 않았으며, 상품 전체를 읽어오거나 재고 수량을 서비스에서 판단하지 않는다.

두 조회 인터페이스는 domain이 소유하고 서비스는 infrastructure 구현을 알지 않는다. 이 단계에서는 기존 Layer-first와 모듈 경계를 유지했고 조회 구현·저장·트랜잭션·잠금·Spring 등록을 추가하지 않았다. 이 서비스의 성공은 반환 객체의 메모리 상태 변경을 뜻한다. 이후 DB 연결에서는 확정한 P09에 따라 필요한 잠금과 변경 저장을 하나의 유스케이스 안에서 보장해야 한다.

| 순서·대상 | 실제 확인한 Red 또는 추가 사례 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / BRAND-DELETE-01 | 서비스 골격이 null을 반환하고 브랜드도 변경하지 않아 1개 중 1개 실패 | 브랜드 조회 후 기존 삭제 행동 호출·반환. 1개 통과 |
| 2 / BRAND-DELETE-02 | 미삭제 상품이 있어도 삭제되어 2개 중 1개 실패 | 존재 조회가 true면 상태 변경 전에 NON_DELETED_PRODUCTS_EXIST로 거절. 2개 통과 |
| 3 / BRAND-DELETE-03 | 없는 브랜드에서 NoSuchElementException이 발생해 3개 중 1개 실패 | BRAND_NOT_FOUND로 구분하고 상품을 조회하지 않음. 3개 통과 |
| 4 / BRAND-DELETE-04 | 재삭제에도 상품을 다시 조회하여 4개 중 1개 실패 | 삭제된 Brand는 존재 검사 없이 그대로 반환. 4개 통과 |
| 5 / BRAND-DELETE-05~07 | 상품 조회 실패 시 상태 보존, 두 브랜드의 조회 ID·조건 격리, 조건 해소 후 재요청이 기존 구현으로 통과 | 추가 구현 없이 7개 통과 |
| 6 / Refactor | 반복된 실패 사유 assertion을 테스트 보조 메서드로 추출하고 서비스와 조회 계약의 책임을 명시 | 기존 상태 검증을 유지하고 전체 검사 실행 |

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.domain.brand.BrandDeletionServiceTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

리팩터링 후 전체 **100개 테스트**가 통과했다. 새 서비스 테스트 7개와 기존 93개이며 실패·오류·건너뜀은 0개다. Checkstyle main/test의 위반도 0개이며 ArchUnit을 포함한 기존 회귀가 통과했다. 코드·문서 변경을 검토하고 `git diff --check`, 문서 9개의 로컬 링크 105개·앵커·코드 블록 검사도 통과했다.

boolean 조회 대역은 서비스의 판단·상태 보존과 올바른 브랜드 ID 전달만 검증한다. 재고 0인 상품이 실제 조회에 포함되는지, 상품이 없거나 모두 삭제됐을 때 false인지, 다른 브랜드의 상품이 제외되는지는 실제 DB 조회 구현을 연결할 때 검증한다. 이를 이미 완료한 SQL·동시성 테스트로 기록하지 않는다.

## 남은 구현 순서

1. [고객 상세 조회 application](commerce-tdd-brand-query-log.md)의 정상·없음·삭제 처리는 후속 TDD에서 완료했다. 남은 HTTP 계약을 확인해 C01을 연결하고, 관리자 변경·삭제에 앞서 구체 잠금 범위를 정한다.
2. 상품 존재 조회의 DB 구현에서 미삭제 조건·재고 0 포함·브랜드 구분을 검증하고, 상품 등록과 브랜드 삭제의 경합·실패 롤백을 실제 DB 테스트로 보장한다.
3. 관리자 권한 진입·HTTP 오류 계약을 확정한 뒤 브랜드 API를 연결한다. 이어서 상품·좋아요·포인트·주문 순서로 진행한다.

이후 [영속성 TDD](commerce-tdd-brand-persistence-log.md)에서 ID·JPA 매핑·신규 저장·조회와 엔티티 변경 감지·감사 시각을 실제 MySQL로 검증했다. 삭제 조건 서비스는 여전히 메모리 상태 변경까지 담당하며, 수정·삭제 유스케이스·관리자 권한·행 잠금·HTTP와 A02·A04·A05/API-16·18 전체는 후속 범위다. 위의 86·93·100개는 각 도메인 단계 당시 검사 결과다.
