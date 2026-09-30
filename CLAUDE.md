# loopers-java-spring-template (Loopers BE VoL 5)

Spring Boot 멀티모듈 프로젝트. 이 과정의 목표는 "동작하는 코드"가 아니라 **설계 판단력** — 불변식을 먼저 정의하고, 책임의 위치를 근거와 함께 정하는 것.

## 모듈 구조

- `apps/*` : 실행 가능한 Spring Boot 애플리케이션 (`commerce-api`, `commerce-batch`, `commerce-streamer`)
- `modules/*` : 특정 도메인에 의존하지 않는 재사용 설정 (`jpa`, `redis`, `kafka`)
- `supports/*` : 부가 기능 add-on (`jackson`, `logging`, `monitoring`)

## 앱 내부 계층 (예: commerce-api)

```
interfaces/api  → application (Facade/Info) → domain (Model/Service/Repository interface) → infrastructure (RepositoryImpl/JpaRepository)
```

- **domain 모델이 불변식을 캡슐화한다.** Service/Facade는 조회·조합·저장 순서만 담당하고 검증 순서 자체를 알아서는 안 된다 (`docs/week1/order-discount-contract.md`의 "책임 경계" 참고). 검증을 Service에 흩어두면 기능마다 복사되고 한 곳만 빠뜨려도 보안 구멍이 된다.
- 도메인 리포지토리는 인터페이스로 domain에 두고, 구현은 infrastructure에서 (포트/어댑터).

## 응답·에러 컨벤션

- 모든 API 응답은 `ApiResponse<T>` 래퍼 (`meta.result`: `SUCCESS`/`FAIL`, 실패 시 `data`는 항상 `null`).
- 예외는 `CoreException` + `ErrorType`(`INTERNAL_ERROR` 500 / `BAD_REQUEST` 400 / `NOT_FOUND` 404 / `CONFLICT` 409) 네 가지로만 매핑한다. `errorCode`는 별도 업무 코드가 아니라 HTTP reason phrase.
- 400 vs 409 기준: **요청자가 입력을 바꾸면 해결되는가** → 그렇다면 400, 리소스 상태를 먼저 바꿔야 하면 409.
- 인가 실패(소유자 불일치)와 리소스 부재는 도메인 레벨에서는 구분해서 던지고, 변환 계층에서만 동일 응답(404)으로 합친다 — 응답 차이로 정보가 노출되지 않게.

## 설계 문서 워크플로우 (`docs/weekN/*.md`)

각 주차 과제는 코드 이전에 설계 계약 문서를 먼저 쓴다: 요구 분류 → **불변식(INV-xxx)과 반례** → 유스케이스 → 처리 흐름(mermaid) → 외부 계약/내부 경계. 코드를 리뷰하거나 구현할 때는 이 문서에 정의된 INV를 먼저 확인하고, 코드가 그 불변식을 어디서(어느 계층에서) 지키는지 근거를 대며 진행한다. 문서에 "보류/거절"로 명시된 범위는 임의로 구현하지 않는다.

## 빌드/테스트

```shell
./gradlew build
./gradlew :apps:commerce-api:test
docker-compose -f ./docker/infra-compose.yml up       # local 인프라
docker-compose -f ./docker/monitoring-compose.yml up  # prometheus/grafana
```

## AI 작업 규칙 (Round 2 실습 지시사항)

- 합의한 계약·기대값·패키지 의존을 따른다. **미정 정책은 구현하기 전에 먼저 질문한다.**
- 이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안하고, 승인 후에 구현한다.
- 작은 기능 단위로 구현하고, 매번 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
- 검사를 통과시키기 위해 테스트·기대값·규칙을 삭제하거나 완화하지 않는다.
- 정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.
- 계층 의존 규칙(`domain`은 `interfaces`/`application`/`infrastructure`에 의존 금지, `application`은 `interfaces`/`infrastructure`에 의존 금지, `interfaces`는 `infrastructure`에 의존 금지)은 `ArchitectureTest`로 강제된다 — 위반하는 코드를 제안하지 않는다.

## 개발 규칙 (Round 2부터)

- Checkstyle 연결 (`apps/commerce-api/build.gradle.kts`, `config/checkstyle/checkstyle.xml`), `maxWarnings = 0`.
- ArchUnit (`apps/commerce-api/src/test/java/com/loopers/architecture/ArchitectureTest.java`)로 계층 의존 검사.
- 최종 검사: `./gradlew :apps:commerce-api:check`

## Round 2 도메인 (신규 구현 대상)

`Brand 1─N Product`, `User 1─N Like N─1 Product`, `User 1─N Order 1─N OrderItem`, `User 1─포인트 잔액`. 좋아요는 카운터가 아니라 User–Product 관계로 저장(개수는 관계에서 집계). 주문은 생성(DRAFT, 차감 없음) / 확정(CONFIRMED, 재고·포인트 차감, 결제 결과 저장) 2단계로 분리하고, 확정 후 값은 재계산하지 않는다(week1 INV-004와 동일 원칙).

## 학습 개념 정리 (`docs/glossary.md`)

사용자가 스스로 모른다고 밝힌 개념(예: N+1, fetch join, Spring 애너테이션 등)이 나오면, 그 자리에서 설명하는 것과 별개로 `docs/glossary.md`에 항목을 추가한다. 형식: 개념명 → 쉬운 설명(이 프로젝트의 구체적 예시로) → 왜/어디서 필요했는지. 이미 있는 개념을 다시 설명해야 할 때는 새로 쓰기 전에 이 파일부터 확인한다.

## 블로그 소재 수집 (`docs/blog-notes.md`)

대화 중 다음 성격의 순간이 나오면, 그 자리 설명과 별개로 `docs/blog-notes.md`에 항목을 추가한다(상황→판단→근거→왜 소재가 되는지 형식). Technical Writing Quest 주제 예시(실습 문서) 기준:
- 설계 판단이나 이해가 바뀐 순간 ("전엔 이렇게 생각했는데, 이제 보니 아니었다")
- 트레이드오프를 의식적으로 검토하고 지금은 보류/미채택하기로 한 순간 (근거 포함)
- AI가 제안한 대안을 비교해서 채택하거나 수정한 순간
- lint·ArchUnit·동작 테스트가 실제로 뭔가를 잡아낸 순간
사용자가 매주 이 목록에서 하나를 골라 실제 글로 쓴다 — 이 파일 자체가 글이 되는 건 아니다.

## 주의

- 이 프로젝트는 표준 Spring Boot 컨벤션을 따른다 — 사용자 본업(사내 자체 프레임워크)과는 별개이므로 Spring 관례를 그대로 적용해도 된다.
