---
name: test-green-implement
description: 작성된 Red 테스트를 절대 수정하지 않고 최소 production 코드만 작성해 전체 테스트를 Green으로 만들 때 사용한다.
context: fork
agent: general-purpose
---

# test-green-implement

분리된 컨텍스트에서 구현한다. 시작하기 전에 `AGENTS.md`, `docs/week2/requirements.md` 전체, `test-plan.md`, `commerce-api-design.md`, `api-contract.md`, `api-response-contract.md`, `decisions.md`, 테스트와 현재 production 코드를 모두 읽는다.

1. 시작할 때 모든 테스트 파일의 목록과 해시를 기록한다.
2. Red 테스트 전체를 실행하고 실패 원인을 분석한다.
3. production 코드를 수정하기 전에, 현재 Red를 해결할 구체적인 작업 계획을 [Green TODO](./references/green-todo.md)에 구현 순서대로 기록한다. 각 TODO에는 요구사항 ID, 관찰한 실패, 필요한 최소 동작, 변경할 production 파일, 선행 작업, 테스트 명령, 완료 조건을 적는다.
4. 계획을 모두 기록한 뒤 TODO 순서대로 테스트를 통과시키는 최소 production 코드만 작성한다. 리팩터링이나 범위 밖 구현은 하지 않는다.
5. 관련 테스트가 Green이 된 항목만 `[x]`로 바꾼다.
6. 관련 테스트와 전체 테스트를 실행해 모두 Green인지 확인한다.
7. 테스트 파일의 목록과 해시가 시작할 때와 같은지 확인한다.
8. Checkstyle과 `ArchitectureTest`를 실행한다.

테스트 코드, 기대값, 요구사항, 검사 규칙은 어떤 이유로도 수정·삭제·완화하지 않는다. 테스트와 구현이 충돌하면 테스트를 고치지 말고 중단해 보고한다.

코드 주석이 하지 말라고 한 변경은 하지 않는다. 공용 모듈(`modules/`, `supports/`)은 수정하지 않는다. Green에 이런 변경이 필요하면 구현하지 말고 중단해 보고한다.

다음 의존 방향을 지킨다.

```text
interfaces ──▶ application ──▶ domain ◀── infrastructure
```

- `domain`은 `interfaces`, `application`, `infrastructure`에 의존하지 않는다.
- `application`은 `interfaces`, `infrastructure`에 의존하지 않는다.
- `interfaces`는 `infrastructure`에 의존하지 않는다.
- 외부 기술 구현은 `infrastructure`에 두고 `domain`의 포트를 구현한다.
- `domain`은 HTTP와 DB 기술을 알지 않는다.
