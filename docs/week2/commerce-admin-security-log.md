# 관리자 접근 지원 설정 보완 기록

과제 원문의 관리자 실행 방식과 기존 구현의 차이를 사용자 지적으로 확인했다. 관리자 경로는 고객용 `X-USER-ID` fixture와 분리해 Spring Security의 `ROLE_ADMIN`으로 검사하고, 실제 controller·application·JPA repository·MySQL을 연결한 MockMvc로 검증한다. 네트워크 로그인은 추가하지 않는다.

## 변경 책임

- [AdminBoundaryConfig](../../apps/commerce-api/src/main/java/com/loopers/config/AdminBoundaryConfig.java): `/api-admin/**`에만 SecurityFilterChain 적용, ADMIN 역할 허용, 일반·미식별 요청 403, 기본 CSRF 유지. 기존 JSON 오류 형태를 entry point/access-denied handler에 연결한다.
- [AdminRequester](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/AdminRequester.java): Spring Authentication을 HTTP와 독립된 UserRole로 변환한다. 관리자 controller는 고객 헤더를 받지 않는다.
- [AdminAuthorization](../../apps/commerce-api/src/main/java/com/loopers/application/user/AdminAuthorization.java): 관리자 유스케이스의 역할 검증을 담당한다. application은 Spring Security 타입이나 fixture 관리자 문자열에 의존하지 않는다.
- 기존 `AdminAccessFilter`를 제거한다. 고객의 외부 식별 문자열과 DB ID 매핑은 기존 fixture·UserResolver를 유지한다.

## 기대값

[AdminSecurityApiTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/commerce/AdminSecurityApiTest.java)는 A01~A13 각각에 관리자 성공, 일반 사용자 403, 미식별 403을 검증한다. 모든 쓰기 거절 사례에도 유효한 CSRF를 넣어 권한 거절을 검증하고, 별도 사례에서 관리자라도 CSRF 누락·불일치 시 403임을 확인한다. 거절 전후 6개 테이블의 전체 행·필드가 동일해야 한다.

추가로 `X-USER-ID: admin`만으로 접근하거나 USER 역할을 승격할 수 없음, fixture에 없는 이름이라도 ADMIN 역할이면 성공함, 고객/없는 사용자 헤더가 ADMIN 역할에 영향을 주지 않음, 잘못된 경로·본문보다 권한 오류가 먼저임, 고객 충전·공개 조회가 관리자 보안 체인의 영향을 받지 않음을 검증한다.

## Red · Green · Refactor

2026-09-18 14:17 KST, production 변경 전 `AdminSecurityApiTest` 57개 중 56개가 실패하고 고객 경로 회귀 1개가 통과했다. 관리자 Authentication 입력이 무시되어 400을 반환하고, 미식별/USER 요청도 403 대신 400을 반환했다. `X-USER-ID: admin`만 보낸 위조 요청은 403 기대와 달리 200이었다. 환경 문제가 아닌 실제 관리자 경계 계약의 실패를 확인했다.

**Green:** 지원 설정과 역할 전달 경계를 구현한 뒤 2026-09-18 14:22:56 KST 최종 `./gradlew :apps:commerce-api:check --console=plain -q`가 exit 0으로 끝났다. `AdminSecurityApiTest` **57개 모두 통과**했으며 CSRF 누락·불일치 14개 사례의 `403`과 JSON `FORBIDDEN` 검증도 포함한다. 테스트 클래스 빌드 시각 14:21:48이 마지막 소스 변경 14:20:07 이후임을 확인했다.

전체 **51개 테스트 클래스·506개 사례**, 실패·오류·건너뜀 0개다. 충전부터 주문 조회까지 연결한 시나리오 1개, ArchUnit 1개와 기존 고객·Example·저장·동시성 회귀도 포함한다. Checkstyle 검사 결과 및 최종 통합 범위는 [완료 체크리스트](commerce-completion-checklist.md#최신-통합-검사)에 기록했다.

**Refactor:** 관리자 문자열 매핑과 오래된 필터를 제거하고, Spring Security → HTTP 역할 변환 → application 역할 검증으로 책임을 나눴다. application의 관리자 서비스는 fixture 조회 의존성을 제거했고 HTTP·Spring Security 타입을 받지 않는다. 주문 서비스의 고객 소유권·구매자 필터에 필요한 UserResolver 의존성은 유지한다. 기존 관리자 HTTP 테스트도 MockMvc의 인증 사용자·CSRF 입력으로 전환하되 업무 상태·응답 검증을 유지한다. 관리자 누락/미등록 헤더에 대한 옛 400/404 기대값은 과제의 권한 입력 계약으로 바뀌므로 403 검증으로 교체한다. 고객의 누락/미등록 식별 오류는 변경하지 않는다.

## 실행 입력과 회귀 범위

관리자 요청은 MockMvc에서 `with(user("operator").roles("ADMIN"))`로 입력한다. POST·PUT·DELETE에는 `with(csrf())`도 넣는다. 일반 사용자 거절은 `roles("USER")`, 미식별 거절은 사용자 후처리 없이 실행한다. 임의의 `X-USER-ID: admin` HTTP 헤더는 관리자 권한을 만들지 않는다.

관리자 application의 입력은 UserRole이며 이름·식별 문자열을 별도로 해석하지 않는다. 고객용 UserResolver와 fixture 단위 검증은 보존했다. 기존 UserResolver의 식별 기반 역할 확인 도우미는 관리자 HTTP/service 경로에서 사용하지 않는다. 구매자 주문 필터와 응답의 사용자 식별값 변환은 고객용 매핑 책임으로 유지한다.

기존 관리자 HTTP 테스트의 CRUD·삭제 상태·페이지·오류·동시성 기대값과 실제 DB 검증을 유지하면서 요청자 입력만 지원 설정에 맞췄다. 기존 Example와 고객 공개 조회·고객 fixture 처리도 전체 회귀에 포함한다. JPA, Layer-first, ArchUnit의 계층 의존 규칙은 변경하지 않았다.
