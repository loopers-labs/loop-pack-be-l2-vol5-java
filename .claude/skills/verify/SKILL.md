---
name: verify
description: 작업이 끝났다·통과한다·고쳤다고 말하기 전, 그리고 커밋·푸시·PR 전에 반드시 사용한다. 이 저장소의 검사 명령을 새로 실행하고 출력(테스트 건수·실패·skip·종료 코드)과 git diff를 확인한 뒤에만 결과를 보고하게 한다. 서브에이전트가 "완료"라고 보고했을 때도 diff로 직접 확인한다.
---

# Verify — 증거 먼저, 주장은 나중

> 원본: https://github.com/obra/superpowers `verification-before-completion` (MIT, © 2025 Jesse Vincent). 이 저장소에 맞게 고쳤다.

## 철칙

```
이번에 새로 실행한 검사 출력 없이 완료를 주장하지 않는다
```

## 절차

1. **무엇이 이 주장을 증명하나** — 아래 표에서 명령을 고른다.
2. **실행** — 전체 명령을 새로 실행한다. 이전 실행 결과를 재사용하지 않는다.
3. **읽기** — 종료 코드와 테스트 건수·실패·skip을 읽는다. `BUILD SUCCESSFUL`만 보고 넘기지 않는다 — 테스트가 0건 실행돼도 성공으로 끝난다.
4. **대조** — 출력이 주장을 뒷받침하는가? 아니라면 실제 상태를 그대로 보고한다.
5. 그 다음에만 보고한다.

| 주장 | 필요한 증거 | 부족한 것 |
|---|---|---|
| 규칙 테스트 통과 | `./gradlew :apps:commerce-api:test --tests '*X*'` + `build/test-results/test/TEST-*X*.xml`의 `tests·failures·errors·skipped` | "통과할 것이다" |
| 계층 의존을 지킴 | `--tests '*ArchitectureTest'` 통과 | 컴파일 성공 |
| lint 통과 | `./gradlew check -x test`에서 Checkstyle 위반 0 | 일부 모듈만 실행 |
| 최종 | `./gradlew :apps:commerce-api:check` 종료 코드 0 + 전체 테스트 건수 | 대상 테스트만 통과 |
| Red 확인 | assertion 실패 줄 (컴파일 오류 아님) | "실패했다" |
| 검사가 실제로 잡는다 | 일부러 위반을 넣었을 때 실패 → 되돌린 뒤 통과 | 검사가 통과한다는 사실만 |
| 요구를 충족함 | `docs/week2/design.md` 규칙 ID별 대조 | 테스트 통과 |
| 에이전트 작업 완료 | `git diff --stat`과 변경 내용을 직접 확인 | 에이전트의 성공 보고 |

## 기능 하나를 마칠 때 보고 형식

```markdown
### 변경
- 책임: (어떤 객체가 어떤 규칙을 맡게 됐나)
- 파일: (git diff --stat 요약)

### 실행한 검사
| 명령 | 결과 |
|---|---|
| ./gradlew :apps:commerce-api:test --tests '*PointGroupTest' | tests=8 failures=0 skipped=0 |
| ./gradlew :apps:commerce-api:test --tests '*ArchitectureTest' | 통과 |
| ./gradlew check -x test | Checkstyle 위반 0 |

### 설계 대조
- PNT-05 ✅ `PointGroupTest.…` / PNT-08 ⏳ 다음 기능

### 남은 것 · 바뀐 판단
- (설계 문서에 반영할 것, 학습자에게 물을 것)
```

## 멈춰야 하는 신호

- "아마", "~일 것이다", "보인다"로 결과를 말하려 한다.
- 검사를 돌리기 전에 "완료"라고 쓰려 한다.
- 커밋·푸시를 앞두고, 마지막 검사 실행이 이번 변경보다 이전이다.
- Docker가 없어 통합 테스트를 못 돌렸다 → 통과라고 하지 말고 "실행하지 못함 — 이유"로 보고한다.
