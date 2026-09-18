# 관리자 브랜드 상세 조회 TDD 실행 기록

> 이 문서는 당시 TDD 실행 기록이다. 과제 피드백 P15 반영으로 관리자 헤더·fixture 검사를 Spring Security의 ADMIN 역할과 MockMvc·CSRF 검증으로 교체했다. 현재 구현과 재검증은 [관리자 Security 보완 기록](commerce-admin-security-log.md)을 따른다. 당시 실패·통과 수치는 보존한다.

2026-09-18 [고객 상세 조회 application](commerce-tdd-brand-query-log.md)에 이어 관리자용 상세 조회 application을 구현했다. 확정 규칙인 관리자 권한 우선 확인(P10)과 관리자 조회의 삭제 행 포함(P05)을 연결한다. A03의 HTTP 연결·응답 계약은 이번 구현 범위에 포함하지 않는다.

## 구현 범위와 책임

| 파일 | 책임 |
| --- | --- |
| [AdminBrandQueryService](../../apps/commerce-api/src/main/java/com/loopers/application/brand/AdminBrandQueryService.java) | 요청자 관리자 권한 확인 후 브랜드 조회, 읽기 전용 트랜잭션, 내부 상세 결과 반환 |
| [AdminBrandQueryServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/AdminBrandQueryServiceIntegrationTest.java) | 실제 MySQL에서 정상·없음·삭제 포함, 요청자 오류 우선순위와 전체 브랜드 상태 보존 검증 |

기존 `UserResolver.requireAdmin`, 삭제 행을 포함하는 domain의 `BrandRepository.findById`, application의 `AdminBrandInfo.from(Brand)`와 `BrandQueryException.Reason.BRAND_NOT_FOUND`를 재사용한다. 엔티티·저장소·HTTP·의존 검사 기준을 변경하지 않는다. `AdminBrandInfo`의 내부 필드를 HTTP 응답 계약 확정으로 확대하지 않는다.

## 실제 Red → Green → Refactor

| 순서·대상 | 실제 확인한 Red | Green·재검증 |
| --- | --- | --- |
| 1 / ADMIN-BRAND-QUERY-01 | 서비스 골격의 `null` 결과로 1개 중 1개 실패 | 저장소에서 요청 ID를 조회하고 `AdminBrandInfo.from`으로 반환하여 1개 통과. 요청한 두 번째 브랜드와 저장된 감사 시각·DB 상태 보존 검증 |
| 2 / ADMIN-BRAND-QUERY-02·03 | 없는 ID에서 `NoSuchElementException`이 발생해 3개 중 1개 실패. 삭제 포함 사례는 기존 조회로 이미 통과 | 없음은 `BrandQueryException.Reason.BRAND_NOT_FOUND`로 구분하여 3개 통과. 삭제 행의 삭제 시각·감사 시각과 전체 행 상태 보존 확인 |
| 3 / ADMIN-BRAND-QUERY-04~06 | 요청자 검사가 없어 정상 조회되거나 `BRAND_NOT_FOUND`가 먼저 발생해 7개 중 4개 실패 | 조회 전에 `UserResolver.requireAdmin`을 호출하여 7개 통과. 일반 고객의 존재/없는 대상 요청, 요청자 누락, 미등록 요청자의 없는 대상 요청을 구분하여 검증 |

리팩터링 검토에서 프로덕션의 추가 추상화는 필요하지 않았다. 사용자 식별·권한, 결과 변환, 조회 실패 사유를 기존 타입에 위임하며 중복 규칙을 만들지 않았다. 반복되는 요청자 실패·DB 상태 검증은 테스트 보조 메서드로 모았고, 마지막 7개 통과 결과는 이 구조를 포함한다. 이미 통과한 삭제 포함 사례를 인위적으로 실패시키지 않았다.

테스트 전체에 `@Transactional`을 붙이지 않고 준비 데이터를 커밋한 뒤 Spring 빈을 호출한다. 전체 브랜드 행의 ID·이름·생성·수정·삭제 시각을 SQL로 조회해 호출 전후를 비교한다. `readOnly = true` 선언만으로 쓰기가 차단된다고 가정하지 않는다.

삭제 상태는 SQL fixture로 준비한다. 이 검증을 삭제 유스케이스나 브랜드 삭제·상품 등록의 경합 검증 완료로 기록하지 않는다.

## 검사 결과와 남은 범위

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.application.brand.AdminBrandQueryServiceIntegrationTest' --console=plain -q
```

2026-09-18 마지막 실행에서 신규 통합 테스트 **7개 통과**, 실패·오류·건너뜀 0개를 XML 결과로 확인했다. ADMIN-BRAND-QUERY-04는 대상 존재/없음의 두 실행 사례다. 이번 실행은 해당 클래스에 한정하며 전체 회귀·Checkstyle·ArchUnit 통과를 대신하지 않는다. 전체 결과는 [완료 체크리스트](commerce-completion-checklist.md)에 별도로 기록한다. 테스트 기대값과 의존 검사 규칙을 삭제·완화하지 않았다.

최초 기본 sandbox 실행은 Gradle 캐시 잠금 파일 접근 제한으로 시작하지 못했다. 권한을 확대한 실행으로 실제 MySQL 테스트를 진행했으며, 이 환경 실패를 TDD Red로 계산하지 않았다.

A03 HTTP 연결과 입력 바인딩보다 앞선 관리자 진입 검사, 관리자 목록, 수정·삭제의 저장 및 행 잠금, 미삭제 상품 존재 SQL과 운영 DDL은 이 상세 조회 application의 완료 증거에 포함하지 않는다.
