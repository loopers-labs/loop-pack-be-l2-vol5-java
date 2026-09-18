# 사용자 식별·권한 TDD 실행 기록

2026-09-18 문서 순서의 사용자 연결·권한 중 공통 해석 기능과 고정 fixture·Spring 연결을 구현했다. [TDD 계획](commerce-tdd-plan.md#사용자-식별권한-해석의-tdd-계획)의 IDENTITY-01~10과 P03·P10을 관련 계약으로 삼으며, DB·HTTP 연결은 아직 미구현이다.

## 구현 범위와 책임

| 파일 | 책임 |
| --- | --- |
| [UserResolver](../../apps/commerce-api/src/main/java/com/loopers/application/user/UserResolver.java) | 외부 식별값의 필수 검증, 조회 계약 호출, 해석된 역할의 관리자 검사 |
| [UserResolutionException](../../apps/commerce-api/src/main/java/com/loopers/application/user/UserResolutionException.java) | INVALID_USER_ID·USER_NOT_FOUND·ADMIN_REQUIRED 사유. HTTP 상태·메시지 계약과 분리 |
| [UserIdentity](../../apps/commerce-api/src/main/java/com/loopers/domain/user/UserIdentity.java) · [UserRole](../../apps/commerce-api/src/main/java/com/loopers/domain/user/UserRole.java) | 내부 사용자 ID·역할의 불변 조회 결과 |
| [UserIdentityRepository](../../apps/commerce-api/src/main/java/com/loopers/domain/user/UserIdentityRepository.java) | 외부 식별값에 연결된 ID·권한의 조회 약속. DB 사용자 행 존재 확인과 구분 |
| [UserResolverTest](../../apps/commerce-api/src/test/java/com/loopers/application/user/UserResolverTest.java) | 조회 대역으로 식별·권한 규칙 11개 실행 사례 검증 |
| [FixtureUserIdentityRepository](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/user/FixtureUserIdentityRepository.java) | 승인된 세 매핑을 불변 Map 한 곳에서 관리하고 원문으로 조회 |
| [FixtureUserIdentityRepositoryTest](../../apps/commerce-api/src/test/java/com/loopers/infrastructure/user/FixtureUserIdentityRepositoryTest.java) | 고정 매핑·미등록·대소문자·공백 비교의 13개 실행 사례 검증 |
| [UserIdentityWiringTest](../../apps/commerce-api/src/test/java/com/loopers/application/user/UserIdentityWiringTest.java) | Spring 컴포넌트 탐색·주입 후 실제 식별·권한 규칙 검증 |

application은 domain의 조회 약속에 의존하며 HTTP DTO·infrastructure 구현을 알지 않는다. 처음에는 조회 대역으로 공통 기능만 구현했고, P03의 데이터·비교 규칙 승인 후 fixture 구현과 Spring 생성자 주입을 연결했다. 기존 Layer-first 패키지와 모듈 경계, ArchUnit 규칙을 유지했다.

## 공통 해석의 실제 Red → Green → Refactor

| 순서·대상 | 실제 Red 또는 추가 사례의 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / IDENTITY-01 | 최소 골격의 조회 결과가 null. 기대한 ID 42·CUSTOMER와 달라 1개 중 1개 실패 | 조회 약속의 결과 반환 후 1개 통과 |
| 2 / IDENTITY-02 | 없는 사용자에 예외가 없어 2개 중 1개 실패 | 빈 조회 결과를 USER_NOT_FOUND로 변환 후 2개 통과 |
| 3 / IDENTITY-03 | null·빈 문자열·공백·탭·개행에서 INVALID_USER_ID 대신 USER_NOT_FOUND가 발생하고 조회도 수행. 7개 중 5개 실패 | 조회 전 필수 검증 후 7개 통과 |
| 4 / IDENTITY-04 | 관리자 검사 골격이 null을 반환. 8개 중 1개 실패 | 기존 resolve를 호출하도록 구현 후 8개 통과 |
| 5 / IDENTITY-05 | 일반 고객도 관리자 검사에서 통과. 9개 중 1개 실패 | 해석된 역할이 ADMIN인지 확인하고 나머지는 ADMIN_REQUIRED로 거절. 9개 통과 |
| 6 / IDENTITY-06 | 관리자 검사에서도 누락·미등록을 구분하는 두 사례가 기존 구현으로 통과 | 구현 변경 없이 11개 통과 |
| 7 / Refactor | 반복된 업무 실패 사유 assertion을 테스트 보조 메서드로 추출 | 전체 검사에서 동일한 식별 테스트 11개를 포함해 46개 통과 |

이미 통과하는 사례를 인위적으로 실패시키지 않았고 테스트 기대값을 완화하지 않았다. 테스트의 `fixture-user → 42`, `fixture-admin → 7`은 조회 대역용 값이며 실제 실습 사용자 매핑이 아니다.

## 공통 해석 단계의 검사 결과

```shell
./gradlew :apps:commerce-api:test --tests 'com.loopers.application.user.UserResolverTest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain
```

리팩터링 후 전체 46개 테스트(사용자 식별 11개·재고 19개·ArchUnit 1개 포함)와 Checkstyle main/test가 통과했다. 실패·오류·건너뜀은 0개다.

이 단계에서는 고정 fixture의 값·역할과 원문 비교를 질문하고 답변을 기다렸다. 아래는 이후 승인에 따른 연결 기록이다.

## 고정 fixture·Spring 연결의 TDD

사용자가 `alice → 1/CUSTOMER`, `bob → 2/CUSTOMER`, `admin → 3/ADMIN`과 대소문자·앞뒤 공백을 구분하는 원문 일치를 승인했다. 매핑은 `FixtureUserIdentityRepository` 한 곳에만 두며 문자열 1·2·3으로 대체하거나 자동 회원을 생성하지 않는다.

| 순서·대상 | 실제 확인한 결과 | 구현·재검증 |
| --- | --- | --- |
| 1 / IDENTITY-07 | 빈 조회 골격에서 세 매핑이 모두 없어 3개 중 3개 실패 | 불변 Map의 원문 조회를 구현한 뒤 3개 통과 |
| 2 / IDENTITY-08·09 | 미등록·숫자 ID 문자열 4개와 대소문자·앞뒤 공백 차이 6개가 이미 거절됨 | 구현 변경 없이 fixture 테스트 13개 통과 |
| 3 / IDENTITY-10 | 컴포넌트 탐색 후 UserResolver Bean이 없어 연결 테스트 1개 실패 | UserResolver와 fixture 구현을 컴포넌트로 등록한 뒤 기존 공통 11개·fixture 13개·연결 1개, 총 25개 통과 |
| 4 / 구조·Refactor 검토 | 중복 매핑이나 추가 조회 계층이 필요하지 않음을 확인 | 기존 공통 해석·업무 예외와 생성자 주입을 유지하고 원문 조회 계약을 명시. 전체 검사 통과 |

연결 테스트의 Red는 새로 작성한 Spring 조립 계약의 등록 누락이며 DB 접속 실패를 업무 규칙 실패로 기록한 것이 아니다. 연결 후 실제 fixture를 통해 고객·관리자를 구분하고 원문이 다른 식별값은 USER_NOT_FOUND로 거절하는 것까지 검증했다.

전체 `:apps:commerce-api:check` 결과 **60개 테스트**가 통과했다. 사용자 관련 25개·재고 19개·ArchUnit 1개를 포함하며 실패·오류·건너뜀은 0개다. Checkstyle main/test도 통과했다.

이 결과는 API-21 전체나 P10의 HTTP 권한 우선 검증 완료가 아니다. 공개 조회의 헤더 생략·HTTP 오류 응답·초기 잔액은 이번 승인 대상이 아니며 남은 제안으로 유지한다. 실제 DB 사용자 행·잔액과의 연결은 영속성 구현 때, 헤더 바인딩과 관리자 진입 순서는 HTTP 구현 때 검증한다.
