# 관리자 브랜드와 공통 HTTP TDD 기록

> 이 문서는 당시 TDD 실행 기록이다. 과제 피드백 P15 반영으로 관리자 헤더·fixture 검사를 Spring Security의 ADMIN 역할과 MockMvc·CSRF 검증으로 교체했다. 현재 구현과 재검증은 [관리자 Security 보완 기록](commerce-admin-security-log.md)을 따른다. 당시 실패·통과 수치는 보존한다.

[전체 설계](commerce-erd-draft.md) · [API 계약](commerce-api-contract.md) · [완료 체크리스트](commerce-completion-checklist.md)

## 범위와 책임

2026-09-18, 승인된 A01~A05와 API-16·18·21·24를 연결했다. 기존 C01의 입력·응답 계약은 유지한다.

- `AdminBrandController`는 엄격한 JSON·양의 ID·페이지 입력과 응답을 처리한다.
- 기존 등록·상세 application에 `AdminBrandService`의 목록·수정·삭제를 추가했다. 변경은 READ_COMMITTED 트랜잭션으로 브랜드를 잠근 뒤 수행한다.
- `BrandDeletionService`의 기존 미삭제 상품 존재 규칙을 재사용한다. 재고 0인 상품도 삭제를 막고, 재삭제는 최초 시각을 보존한다.
- JPA `BrandRepository`의 잠금 조회와 `BrandQueryRepository`의 페이지 조회를 infrastructure에서 구현했다. 수정 응답의 감사 시각은 flush 이후 구성한다.
- `AdminAccessFilter`는 application의 `UserResolver`를 호출하여 JSON·경로 바인딩보다 관리자 권한을 먼저 검사한다.
- `CommerceErrors`와 `CommerceWebConfiguration`은 커머스 경로의 오류를 처리한다. 컨트롤러 선택 전에 발생하는 405·415·406도 포함하며 기존 Example 오류 처리기는 유지한다.

## 실제 Red → Green → Refactor

| 단계 | 관찰한 결과 |
| --- | --- |
| A02·A03 Red | `AdminBrandApiE2ETest` 최초 3개가 미구현 경로의 404로 실패. 기대값은 등록 201·권한 403·잘못된 JSON 필드 400 |
| A02·A03 Green | 등록·삭제 행 상세·권한 우선·엄격 JSON 연결 후 통과. 추가한 405·415·406와 요청자 오류 검증까지 4개 통과 |
| A01·A04·A05 Red | `AdminBrandMutationApiE2ETest` 최초 3개가 미구현 메서드의 405로 실패 |
| A01·A04·A05 Green | 목록·수정·삭제를 연결한 뒤 3개 통과. 브랜드 목록 동률 정렬·페이지, 재삭제 보존, 재고 0 상품의 삭제 차단 검증 |
| Refactor | C01 전용 advice·ID 예외를 공통 커머스 오류 매핑·`StrictInput`으로 통합. 기존 C01 15개 회귀 유지 |
| 경계 보강 | 잘못된 페이지 7개와 이름 오류·저장 보존 1개를 추가하여 관리자 변경 테스트 11개 통과. 새 Red로 부풀리지 않고 추가 회귀 검증으로 구분 |

다음 명령의 해당 테스트가 통과했다. 서로 다른 실행의 개수를 단순 합산해 전체 회귀 수로 사용하지 않는다.

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.interfaces.api.brand.*' --console=plain
```

`BrandV1ApiE2ETest` 15개, `AdminBrandApiE2ETest` 4개, `AdminBrandMutationApiE2ETest` 11개가 현재 HTTP 증거다. 상품·브랜드 관계와 실제 DB 저장을 사용하며 실패 후 행을 비교한다.

## 잠금 검증

`BrandConcurrencyIntegrationTest`의 브랜드 삭제/상품 등록 경합과 실제 잠금 타임아웃 두 사례가 통과했다. 삭제된 브랜드에 미삭제 상품이 남지 않으며, 3초 잠금 대기 후 409 `CONCURRENT_MODIFICATION`과 행 보존을 확인했다. 커넥션 획득 타임아웃과 구분하여 MySQL 세션의 `innodb_lock_wait_timeout=3`을 검사한다.

실제 교착 상태를 만들고 피해 트랜잭션의 롤백·오류 변환을 검사하는 추가 사례도 후속 주문·브랜드 통합 실행에서 통과했다. 정상 업무는 승인된 잠금 순서를 따르며, 교착 오류 fixture만 두 브랜드의 잠금 순서를 반대로 만든다.

전체 API·동시성·운영 SQL의 최종 결과는 [완료 체크리스트](commerce-completion-checklist.md)에 기록한다. 이 증분의 통과를 주문과 전체 목표의 완료로 간주하지 않는다.

## 단일 JSON 문서 경계와 사용자 조회 포트 보완

후속 HTTP 검토에서 `JsonNode`로 읽는 요청이 첫 JSON 객체 뒤의 추가 토큰을 무시하는 문제를 발견했다. `{"amount":3000} {}`·`{"amount":3000} true`·`{"amount":3000} invalid`는 하나의 유효한 JSON 문서가 아니므로 승인된 입력 오류 계약에 따라 거절해야 한다. 중복 JSON 필드의 별도 정책을 추가한 것은 아니다.

`CommerceJsonBoundaryApiE2ETest`를 먼저 실행한 결과 신규 7개 중 5개가 실제 Red였다. 잘못된 본문이 충전에서 200, 브랜드·주문 생성에서 201로 처리됐다. 관리자 경로의 권한 우선 사례와 정상 JSON 뒤의 공백·개행 사례는 기존 구현으로 통과했다.

`CommerceWebConfiguration.extendMessageConverters`에서 `JsonNode`에만 기존 ObjectMapper의 복사본을 등록하고 `FAIL_ON_TRAILING_TOKENS`를 활성화했다. 바인딩 전에 전체 문서의 끝을 확인하므로 잘못된 본문은 `400 INVALID_REQUEST`가 되고 잔액·브랜드·주문·항목을 변경하지 않는다. 기존 Example DTO와 전역 값 변환 설정은 변경하지 않았다. 관리자 필터는 계속 본문을 읽기 전에 권한을 검사한다.

같은 검증에서 `UserIdentityRepository.findExternalId(long)`의 기본 빈 결과 구현을 제거해 필수 포트로 바꿨다. 실제 fixture는 기존 단일 Map으로 정방향·역방향을 모두 조회한다. 기존 사용자 식별 단위 테스트는 모든 기대값·조회 횟수를 유지한 명시적 대역으로 옮겼고, 사용하지 않는 역조회 호출은 `AssertionError`로 드러내도록 했다.

```shell
./gradlew :apps:commerce-api:test \
  --tests 'com.loopers.interfaces.api.commerce.CommerceJsonBoundaryApiE2ETest' \
  --tests 'com.loopers.application.user.UserResolverTest' \
  --tests 'com.loopers.application.user.UserIdentityWiringTest' \
  --tests 'com.loopers.infrastructure.user.FixtureUserIdentityRepositoryTest' \
  --tests 'com.loopers.interfaces.api.ExampleV1ApiE2ETest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
```

Green은 신규 JSON 경계 7개·사용자 식별 25개·기존 Example 3개, 총 35개 통과다. 실패·오류·건너뜀 0개, Checkstyle main/test 위반 0개를 확인했다. 전체 회귀 결과는 이후 최종 통합 검사와 구분한다.
