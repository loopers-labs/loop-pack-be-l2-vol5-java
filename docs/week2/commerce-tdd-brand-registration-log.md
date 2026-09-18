# 관리자 브랜드 등록 application TDD

> 이 문서는 당시 TDD 실행 기록이다. 과제 피드백 P15 반영으로 관리자 헤더·fixture 검사를 Spring Security의 ADMIN 역할과 MockMvc·CSRF 검증으로 교체했다. 현재 구현과 재검증은 [관리자 Security 보완 기록](commerce-admin-security-log.md)을 따른다. 당시 실패·통과 수치는 보존한다.

2026-09-18 전체 구현 완료 목표의 일부로, 확정된 관리자 권한·브랜드 이름·신규 저장 규칙을 연결했다. A02의 HTTP 계약을 임의로 확정하지 않으며 관리자 경로의 진입 게이트는 후속 범위다.

## 책임과 구현

| 파일 | 책임 |
| --- | --- |
| [BrandRegistrationService](../../apps/commerce-api/src/main/java/com/loopers/application/brand/BrandRegistrationService.java) | 관리자 확인 → Brand 생성 → 저장 → 내부 결과 반환을 하나의 트랜잭션으로 처리 |
| [AdminBrandInfo](../../apps/commerce-api/src/main/java/com/loopers/application/brand/AdminBrandInfo.java) | ID·이름·생성·수정·삭제 시각의 불변 application 결과 |
| [BrandRegistrationServiceIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/brand/BrandRegistrationServiceIntegrationTest.java) | 실제 MySQL 등록·권한·이름 오류·같은 이름의 별도 등록 검증 |

application은 기존 `UserResolver`와 domain의 `BrandRepository`에 의존한다. 이름 검증은 `BrandName`, 생성 상태는 `Brand`, DB 저장은 기존 infrastructure에 맡긴다. HTTP DTO나 저장 구현 타입을 참조하지 않는다. 생성자에 문자열을 전달하기 전에 관리자 여부를 확인하므로 고객의 잘못된 이름도 권한 사유를 우선한다. HTTP 바인딩보다 앞선 관리자 검사까지 완료했다는 의미는 아니다.

신규 INSERT만 다루며 기존 브랜드 행의 수정·삭제와 구체 행 잠금 정책은 구현하지 않았다. `AdminBrandInfo` 필드는 내부 결과이며 나머지 HTTP 응답 초안의 승인을 대신하지 않는다.

## 실제 Red → Green → Refactor 검토

| 순서 | 실제 결과 | 반영 |
| --- | --- | --- |
| BRAND-REGISTER-01 | null 반환 골격 때문에 1개 중 1개 실패 | Brand 생성·저장 및 AdminBrandInfo 변환 후 1개 통과 |
| BRAND-REGISTER-02 | 권한 확인 없이 이름 검증부터 실행해 ADMIN_REQUIRED 대신 EMPTY_NAME 발생. 2개 중 1개 실패 | UserResolver.requireAdmin을 가장 먼저 실행해 2개 통과 |
| 경계 확장 | 고객의 정상/잘못된 이름, 관리자의 null·공백·101자, 같은 이름의 별도 등록을 추가 | 기존 구현으로 7개 통과. 이미 통과한 사례는 인위적으로 실패시키지 않음 |
| Refactor 검토 | 검증 책임을 기존 도메인에 위임하고 결과 변환을 AdminBrandInfo.from으로 모은 구조 확인 | 추가 공통 추상화나 저장소 구현 변경 없이 유지. 반복 경계 데이터는 MethodSource 사용 |

잘못된 입력에서는 기존 DB 행 전체를 전후 비교하고 추가 저장이 없는지 확인한다. 같은 이름의 등록은 두 ID·두 행을 확인한다. 요청마다 데이터가 준비·커밋되고 테스트 종료 후 정리된다.

## 검증

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.application.brand.BrandRegistrationServiceIntegrationTest' --console=plain -q
```

이 증분의 통합 테스트 **7개 통과**, 실패·오류·건너뜀 0개다. 후속 C01 HTTP·관리자 상세·주문 수량을 합친 전체 검사에서도 156개 통과·Checkstyle 위반 0개·ArchUnit 통과를 확인했고 [최신 결과](commerce-completion-checklist.md#최신-통합-검사)에 기록했다.

전체 목표는 API 25개와 API-01~24, 실제 DB 제약·동시성·롤백·운영 DDL까지이며 이 등록 서비스만으로 A02 또는 전체 목표를 완료 처리하지 않는다.
