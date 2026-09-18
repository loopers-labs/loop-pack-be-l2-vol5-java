# 프로젝트 작업 규칙

AI와 함께 이 저장소에서 기능을 구현할 때 따르는 규칙입니다.

---

## 합의한 기준을 따른다

- 설계 문서(`docs/`)에 합의한 계약·기대값·패키지 의존을 따른다.
- 설계 문서에 없거나 결정되지 않은 정책은 구현하기 전에 먼저 질문한다. 임의로 정하지 않는다.

## 구현 전에 먼저 제안한다

- 이번 기능에서 변경할 책임, 파일, 관련 테스트를 먼저 제안하고 확인을 받는다.

## 작게 구현하고 결과를 확인한다

- 작은 기능 하나를 구현하고 diff와 관련 테스트·lint·ArchUnit 결과를 확인한 뒤 다음 기능으로 넘어간다.
- 검사 명령

```bash
./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'
./gradlew :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest
./gradlew :apps:commerce-api:check
```

## 검사를 우회하지 않는다

- 검사를 통과시키기 위해 테스트·기대값·규칙을 삭제하거나 완화하지 않는다.
- 정책·검사 기준 변경이나 범위 밖 개편이 필요하면 이유와 영향을 설명하고 확인을 받는다.

---

## 패키지 의존 규칙

`ArchitectureTest` 가 검사한다.

| 계층 | 의존하면 안 되는 계층 |
|---|---|
| `domain` | `interfaces`, `application`, `infrastructure` |
| `application` | `interfaces`, `infrastructure` |
| `interfaces` | `infrastructure` |
