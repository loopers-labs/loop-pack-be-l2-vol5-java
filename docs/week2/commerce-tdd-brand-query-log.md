# 고객 브랜드 상세 조회 TDD 실행 기록

2026-09-18 [브랜드 영속성](commerce-tdd-brand-persistence-log.md)에 이어 고객용 상세 조회 application을 구현했다. 확정 규칙인 “존재하며 삭제되지 않은 브랜드만 고객에게 조회”를 실제 저장소에 연결한다. C01의 HTTP 응답·헤더 계약은 후속 범위다.

## 구현 범위와 책임

| 파일 | 책임 |
| --- | --- |
| [BrandQueryService](../../apps/commerce-api/src/main/java/com/loopers/application/brand/BrandQueryService.java) | ID 조회, 고객 노출 가능 여부 판단, 읽기 전용 트랜잭션, 내부 결과 구성 |
| [BrandInfo](../../apps/commerce-api/src/main/java/com/loopers/application/brand/BrandInfo.java) | ID·이름을 담는 application 소유 불변 결과. HTTP DTO와 구분 |
| [BrandQueryException](../../apps/commerce-api/src/main/java/com/loopers/application/brand/BrandQueryException.java) | 없음·삭제의 동일한 BRAND_NOT_FOUND 사유. HTTP 상태와 독립 |
| [BrandQueryServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandQueryServiceIntegrationTest.java) | 실제 MySQL에서 정상·없음·삭제 조회와 모든 행의 상태 보존 검증 |

기존 Layer-first의 `application/brand`에 배치하고 domain의 `BrandRepository`만 주입받는다. domain·infrastructure·모듈·ArchUnit 규칙은 변경하지 않았다. 저장소의 `findById`는 삭제 행도 반환하며, 고객용 서비스에서만 이를 제외하므로 관리자 조회·재삭제가 사용할 저장 계약을 유지한다. 결과에 변경 가능한 JPA 엔티티를 노출하지 않는다.

## 실제 Red → Green → Refactor

| 순서·대상 | 실제 확인한 Red | Green·재검증 |
| --- | --- | --- |
| 1 / BRAND-QUERY-01 | 서비스 골격이 null을 반환해 요청 ID·이름 기대값과 불일치. 1개 중 1개 실패 | 저장소 조회 후 BrandInfo 구성. 서로 다른 두 브랜드 중 요청한 두 번째 ID와 DB 상태 보존까지 1개 통과 |
| 2 / BRAND-QUERY-02 | 없는 ID에서 NoSuchElementException이 발생. 2개 중 1개 실패 | BrandQueryException의 BRAND_NOT_FOUND로 구분. 2개 통과 |
| 3 / BRAND-QUERY-03 | 삭제된 브랜드가 정상 반환되어 예외 기대값 실패. 3개 중 1개 실패 | 삭제 상태를 검사하고 같은 BRAND_NOT_FOUND로 거절. 3개 통과 |
| 4 / Refactor | 없음·삭제의 중복 예외 처리를 Optional.filter와 하나의 실패 경로로 정리 | DB 스냅샷·실패 사유 검증도 테스트 보조 메서드로 추출. 기존 기대값을 유지하고 전체 검사 실행 |

테스트 전체에 `@Transactional`을 붙이지 않는다. 준비 데이터를 커밋한 뒤 Spring이 주입한 서비스 빈을 호출하여 실제 조회 트랜잭션과 저장소 연결을 검증한다. 호출 전후 모든 브랜드의 ID·이름·생성·수정·삭제 시각을 DB에서 읽어 비교한다. `readOnly = true` 선언 자체가 쓰기를 차단한다고 가정하지 않는다.

삭제 상태는 SQL fixture로 준비한다. 이 검증을 삭제 유스케이스나 브랜드 삭제·상품 등록의 경합 검증 완료로 기록하지 않는다. ID는 실제 DB가 생성하며 테스트 전용 ID setter·반사 접근·엔티티 mock을 추가하지 않았다.

## 검사 결과

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.application.brand.BrandQueryServiceIntegrationTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

리팩터링 후 전체 **111개 테스트**가 통과했다. 신규 고객 조회 통합 3개와 기존 108개이며 실패·오류·건너뜀은 0개다. Checkstyle main/test 위반은 0개이고 ArchUnit도 통과했다. 테스트 기대값이나 의존 검사 기준을 삭제·완화하지 않았다.

## 현재 단계와 남은 범위

이 기록의 111개 검사 당시에는 [10단계 계획](commerce-tdd-plan.md#현재-단계와-이번-구현)의 3단계인 브랜드 CRUD를 진행 중이며 HTTP 연결은 0/25개였다. 후속 [C01 HTTP TDD](commerce-tdd-brand-http-log.md)에서 승인된 공개 조회·정확한 응답·입력 오류·DB 상태 보존을 구현·검증하여 현재 HTTP 완료 수는 1/25개다.

C01 계약은 별도 사용자 승인 P11을 반영했다. 관리자 [신규 등록](commerce-tdd-brand-registration-log.md)·[상세 조회](commerce-tdd-admin-brand-query-log.md) application도 후속 구현했다. 관리자 HTTP·목록·수정·삭제의 트랜잭션·행 잠금·상품 존재 SQL과 운영 DDL은 남아 있다. 이후 상품·좋아요·포인트·주문을 연결하며 최신 전체 결과는 [완료 체크리스트](commerce-completion-checklist.md#최신-통합-검사)를 따른다.
