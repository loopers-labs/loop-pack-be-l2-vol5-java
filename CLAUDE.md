# loop-pack-be-l2-vol5-java — Claude 작업 안내

> 이 파일은 **안내판**이다. 설계·규칙 본문은 `docs/`에, 작업 절차는 `.claude/skills/`에 있다. 같은 규칙을 여기에 다시 쓰지 않는다.

## 프로젝트

- Loopers BE L2 과제 저장소. Java 21 · Spring Boot 3.4 · JPA(MySQL) · QueryDSL. 빌드 스크립트만 Gradle Kotlin DSL(`.kts`)이고 코드는 Java다.
- 모듈: `apps/commerce-api`(과제 구현 대상) · `apps/commerce-batch` · `apps/commerce-streamer` · `modules/*`(jpa·redis·kafka 설정) · `supports/*`.
- 계층(layer-first): `interfaces → application(Facade) → domain ← infrastructure`.

## 기준 문서

| 문서 | 내용 |
|---|---|
| `docs/week2/design.md` | 2주차 설계 — 정책(P-*), 규칙과 기대값(USR·BRD·PRD·LIK·PNT·ORD-*), API 계약, ADR, JPA 구현 메모, 테스트 경계 |
| `docs/week1/order-discount-contract.md` | 1주차 — 오류 구분 기준(400/404/409), snapshot, 소유권 404 |

## AI 작업 규칙 (과제 지정)

1. 합의한 계약·기대값·패키지 의존을 따른다. 미정 정책은 먼저 질문한다.
2. 이번 기능에서 변경할 책임·파일·관련 테스트를 먼저 제안한다.
3. 작은 기능을 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한다.
4. 검사를 통과시키기 위한 테스트·기대값·규칙 삭제나 완화는 하지 않는다.
5. 정책·검사 기준 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.

## 협업 방식

- 학습자는 이 과제로 TDD와 JPA 엔티티 설계를 익히는 중이다. **결정은 학습자가 한다.**
- 뻔한 부분은 초안을 만들고, **책임 배치 · 대안의 비용 · 테스트 기대값 · 기능 범위 · 커밋/PR**에서만 멈춰 묻는다. 한 번에 2~3개 이하로, 개념은 구체적인 예로 설명한다.
- 설계 문서 결정 표기: ✅ 합의 · 🔧 AI 기본값 · 🤔 결정 필요. 구현 중 판단이 바뀌면 `design.md` 본문과 변경 이력을 함께 고친다.
- 리뷰 피드백(사람·AI)은 코드와 대조해 사실을 확인한 뒤 반영한다. 합의된 ADR과 충돌하면 반영하지 않고 먼저 묻는다.

## 작업 절차

| 상황 | 사용할 것 |
|---|---|
| 새 기능의 엔티티·책임 배치를 설계한다 (DDD 전술 패턴, SOLID) | 스킬 `domain-modeling` |
| 기능 하나를 구현한다 (제안 → 구현 → 검증 → 보고) | 스킬 `feature-slice` |
| 도메인 규칙을 테스트 먼저 구현한다 | 스킬 `tdd` |
| "끝났다·통과한다"고 말하기 전, 커밋 전 | 스킬 `verify` |
| 커밋·PR 전에 diff를 검토받는다 | 스킬 `review` → 리뷰어 에이전트 `domain`·`persistence`·`api-contract`·`test` 중 필요한 1~3명 (읽기 전용) |

## 검사 명령

```bash
./gradlew :apps:commerce-api:test --tests '*PointGroupTest'     # 바꾼 규칙의 테스트 (이름은 대상에 맞게)
./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'   # 계층 의존
./gradlew check -x test                                          # 전체 모듈 Checkstyle
./gradlew :apps:commerce-api:check                               # 최종: 테스트 + Checkstyle
```

- 통합·E2E 테스트는 Testcontainers(MySQL)를 쓰므로 Docker가 필요하다. Docker Engine 29 이상에서는 `~/.docker-java.properties`에 `api.version=1.44`가 있어야 한다.
- 결과는 `BUILD SUCCESSFUL`만이 아니라 테스트 건수·실패·skip으로 보고한다 (`build/test-results/test/TEST-*.xml`).

## Git

- 브랜치는 주차별 `volume-N`(현재 `volume-2`). PR 설명은 `.github/pull_request_template.md` 형식을 따른다.
- 커밋 메시지: `type: 한국어 요약` + 필요하면 `-` 목록 본문. type은 `feat`·`fix`·`test`·`refactor`·`docs`·`chore`.
- **커밋·PR에 Claude·AI 도구 표기(`Co-Authored-By`, `Claude-Session`, "Generated with …")를 넣지 않는다.**
- TDD 대표 규칙은 Red(`test:`) → Green(`feat:`) → Refactor(`refactor:`)를 각각 커밋해 PR에서 변화를 보여 준다.
- 커밋·푸시는 학습자 확인 후에 한다. 푸시된 이력 재작성(강제 푸시)은 명시적 요청이 있을 때만 한다.

## 이 안내를 바꿀 때

- 짧게 유지한다. 규칙 본문이 길어지면 `docs/`로 옮기고 여기에는 위치만 적는다.
- 리뷰에서 틀리거나 쓸모없는 지적이 반복되면 해당 리뷰어의 "확인할 것"을 고친다 (`review` 스킬 "리뷰어를 다듬는 법").
- `tdd`·`verify`·`review` 스킬은 obra/superpowers(MIT, © 2025 Jesse Vincent)를 고쳐 만들었고, 각 파일 첫머리에 원본을 적었다.
