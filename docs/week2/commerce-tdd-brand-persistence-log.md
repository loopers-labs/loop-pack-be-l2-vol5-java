# 브랜드 영속성 TDD 실행 기록

2026-09-18 [브랜드 도메인 규칙](commerce-tdd-brand-log.md)에 이어 실제 MySQL 저장·조회 매핑을 연결했다. 현재는 [10단계 계획의 3단계](commerce-tdd-plan.md#현재-단계와-이번-구현)를 진행 중이며, 브랜드 CRUD의 application·HTTP 연결 완료와 구분한다.

## 구현 범위와 책임

| 파일 | 책임 |
| --- | --- |
| [Brand](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/Brand.java) | 직접 JPA 매핑, Long IDENTITY ID, 생성·수정 시각. 기존 이름·삭제 규칙 유지 |
| [BrandRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/brand/BrandRepository.java) | 저장과 삭제 행을 포함하는 ID 조회의 domain 계약 |
| [BrandRepositoryImpl](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/brand/BrandRepositoryImpl.java) · [BrandJpaRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/brand/BrandJpaRepository.java) | Spring 주입과 실제 JPA 저장·조회 |
| [BrandNameConverter](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/brand/BrandNameConverter.java) | BrandName과 이름 컬럼의 변환. 검증 규칙은 기존 값객체 이용 |
| [BrandRepositoryIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/brand/BrandRepositoryIntegrationTest.java) | MySQL 왕복·경계·롤백·감사 시각의 8개 실행 사례 |
| [BrandDeletionServiceTest](../../apps/commerce-api/src/test/java/com/loopers/domain/brand/BrandDeletionServiceTest.java) | 저장 계약 추가에 맞춰 조회 대역 수정. 기존 7개 기대값 유지 |

앞서 추천한 **Brand 직접 매핑·BaseEntity 미상속** 방식으로 진행했다. BaseEntity의 public delete/restore를 물려받지 않아 기존 package-private 삭제 행동과 단일 deletedAt 상태를 유지한다. 기존 BaseEntity·모듈·ArchUnit 규칙은 변경하지 않았다. 공통 감사 기반을 새로 만들거나 다른 도메인의 영속성 방식까지 결정하지 않았다.

Layer-first 배치와 DIP를 유지한다. domain의 저장 계약을 infrastructure가 구현하며, 입력·반환 타입은 Brand다. converter는 infrastructure에서 `autoApply`로 등록하여 domain이 구현 클래스에 의존하지 않게 했다. 이름 검증을 저장 구현에 중복 작성하지 않는다. JPA 복원용 protected 생성자 외에 ID·삭제 상태 setter나 복원 기능을 추가하지 않았다.

## 실제 Red → Green → Refactor

| 순서·대상 | 실제 확인한 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / BRAND-PERSIST-01 | 저장 골격이 입력 객체만 반환하여 ID가 null. 1개 중 1개 실패 | ID·컬럼·이름 변환·생성 시각과 JPA 저장 연결. 실제 이름 컬럼까지 1개 통과 |
| 2 / BRAND-PERSIST-02 | 조회 골격이 빈 결과를 반환하여 NoSuchElementException. 2개 중 1개 실패 | ID 조회를 JPA에 위임. 별도 트랜잭션·다른 객체 재조회까지 2개 통과 |
| 3 / BRAND-PERSIST-03~06 | 없는 ID, 한글·이모지 각각 100자, 삭제 행 조회, INSERT 롤백은 기존 구현으로 통과 | 추가 구현 없이 총 7개 통과. 이미 통과하는 사례를 인위적으로 실패시키지 않음 |
| 4 / BRAND-PERSIST-07 | 이름은 변경되지만 updatedAt이 과거 fixture 시각에 머묾. 8개 중 1개 실패 | PreUpdate에서 수정 시각 갱신. createdAt 보존과 함께 8개 통과 |
| 5 / Refactor | 반복된 트랜잭션 준비·신규 저장을 테스트 보조 메서드로 정리하고 저장 계약의 책임을 명시 | 기존 assertion을 유지하고 전체 회귀·Checkstyle·ArchUnit 재검증 |

테스트 전체에 `@Transactional`을 붙이지 않고 `TransactionTemplate`으로 저장·조회 트랜잭션을 분리했다. `isNotSameAs`와 컬럼 직접 조회로 메모리 객체만 돌려주는 구현이 통과하지 못하게 했다. 삭제 행과 과거 감사 시각은 SQL fixture로 준비하며, 시간 대기 없이 변경 감지를 검증한다. 날짜는 실제 instant를 비교해 기존 UTC 정규화 설정을 따른다.

삭제 행 fixture는 저장소의 삭제 포함 조회 계약을 검증한다. 삭제 조건 서비스의 실제 DB 저장·경합 검증을 대신하지 않는다. 저장소 대역의 save는 AssertionError를 던져 삭제 조건 서비스가 임의로 저장 책임까지 가져가면 기존 단위 테스트가 실패하도록 했다.

## 검사 결과

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.infrastructure.brand.BrandRepositoryIntegrationTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

리팩터링 후 전체 **108개 테스트**가 통과했다. 신규 MySQL 통합 8개와 기존 100개이며 실패·오류·건너뜀은 0개다. Checkstyle main/test 위반은 0개이고 ArchUnit도 통과했다. 기존 도메인 규칙과 의존 검사 기준을 삭제하거나 완화하지 않았다.

## 현재 단계와 남은 범위

- 완료: 재고 수량 규칙, 사용자 식별·권한·fixture 연결, 브랜드 이름·생명주기·삭제 조건, Brand의 실제 저장·조회 및 변경 감지·감사 시각 매핑.
- 후속 완료: [고객 브랜드 상세 조회 application](commerce-tdd-brand-query-log.md)에서 정상·없음·삭제 처리와 DB 상태 보존을 검증했다. 수정·삭제 유스케이스의 관련 행 잠금·상품 존재 SQL·실패 롤백은 아직 남아 있다.
- 후속 HTTP 완료: P11 승인 뒤 [C01 브랜드 상세](commerce-tdd-brand-http-log.md)를 연결하여 HTTP 완료 수는 1/25개가 됐다. 이 영속성 기록 당시의 108개 검사·0/25개 상태와 구분하며 최신 결과는 [완료 체크리스트](commerce-completion-checklist.md#최신-통합-검사)를 따른다. 관리자 HTTP와 상품·좋아요·포인트·주문은 후속 범위다.

이 기록의 MySQL 검증은 Testcontainers와 테스트 설정의 `ddl-auto=create`를 사용했다. 후속 [브랜드 스키마 TDD](commerce-tdd-brand-schema-log.md)에서는 수동 SQL만 적용한 별도 MySQL과 JPA `validate`로 11개 사례를 검증했다. 전체 6개 테이블의 스키마·마이그레이션, 수정·삭제 유스케이스의 동시성·잠금·권한과 관리자 HTTP 연결은 후속 범위다. 단계마다 작업량이 달라 현재 테스트 수를 전체 완성률로 환산하지 않는다.
