# 작업 규칙

## 기준 문서

- `docs/week2/requirements.md` — 규칙 ID(`BRAND-001` 등)와 기대 응답. **출처 표시**를 지킨다:
  `원문`(요구사항에 있음) · `정함`(우리가 채움) · `선택`(원문이 선택지를 줌) · `보안` · `범위`.
  출처가 `정함`인 것을 원문인 양 다루지 않는다 — 바꿀 수 있는 것을 못 바꾼다고 오해하게 된다.
- `docs/week2/design.md` — 결정과 **그 이유**. 구현하며 판단이 바뀌면 문서를 고친다.
  결정마다 **근거와 뒤집을 조건**을 함께 적는다. 그러지 않으면 같은 논쟁이 다시 열린다.
  **수정 이력은 남기지 않는다** — "한때 이랬다" · "앞선 판단을 고친다" 는 설계가 아니다.
  대안을 적을 때는 과거형이 아니라 **지금 고를 수 있는 선택지**로 적는다.

## 합의된 것

- **계층 의존**: `interfaces → application → domain ← infrastructure`.
  `domain` 은 바깥을 모른다. ArchUnit 이 검사한다.
- **패키지는 개념으로 나눈다**(`domain/point`), 종류로 나누지 않는다(`entity/`, `service/`).
  엔티티와 JPA 리포지토리는 **package-private** 이다 — 도메인을 우회하는 코드가 컴파일되지 않는다.
- **실패는 클래스가 아니라 상수다.** `throw new DomainException(DomainError.INSUFFICIENT_STOCK)`.
  성질(`Failure`)은 `RULE_VIOLATION` · `UNIDENTIFIED` · `INVALID_REFERENCE` 셋이고,
  **상태 코드로 바꾸는 일은 `ApiControllerAdvice.statusOf` 한 곳**이다(409 · 404 · 400).
  `statusOf` 는 `default` 없는 `switch` 라 성질을 더하면 컴파일이 막는다. 새 실패는 `DomainError` 상수 한 줄.
  `IllegalArgumentException` 은 **프로그래밍 오류**를 뜻하고 500 이 된다 —
  사용자 입력 규칙은 도메인까지 보내지 말고 **타입**으로 만들어 요청 record 에서 걸러 400 으로 낸다.
- **원시값에 타입을 만드는 기준**: 불변식이 있고, 혼동 가능한 동형 타입이 있을 때.
  입력값에는 문맥 타입(`ChargeAmount` · `Price` · `OrderQuantity`), 계산 결과에는 `Money` 를 쓴다.
- **트랜잭션 경계는 application 에만 있다.** 도메인에는 `@Transactional` 도 `spring-tx` 임포트도 없고
  ArchUnit 둘이 지킨다. 혼자 커밋되면 안 되는 연산(`OrderConfirmation.confirm` · `PointService.use` ·
  `ProductService.deductStock`)은 **스스로를 막지 못하므로 호출자가 하나뿐이라는 사실에 기댄다.**
  두 번째 호출자를 만들려 할 때는 먼저 묻는다.
- **애그리거트를 걸치는 규칙은 도메인 서비스가 진다.** 확정은 `OrderConfirmation` 이고,
  바깥은 좁은 계약(`StockDeduction` · `PointUsage`)으로만 안다 — 서비스를 통째로 주입받지 않는다.
- **검증은 한 곳**: 같은 규칙을 Dto 와 도메인 양쪽에 쓰지 않는다. 타입을 공유한다.
- **주석을 쓰지 않는다.** `apps/` 아래는 주석 0 줄이다. 설명이 필요하면 이름을 고치거나 `design.md` 에 적는다.
- **테스트 더블을 두지 않는다.** 규칙은 더블 없이 애그리거트 테스트로 확인하고(74 개가 스프링·DB 없이 돈다),
  저장·동시성·매핑은 **실제 DB 통합 테스트**로 본다. 가짜 저장소가 필요해지면 그것은
  규칙이 애그리거트 밖으로 샜다는 신호다.

## 미정 정책은 먼저 묻는다

`requirements.md` 의 **물을 질문**은 Q-1 ~ Q-6 모두 닫혔다.
그 답에 걸리는 코드를 쓸 때는 **추측해서 진행하지 말고 먼저 묻는다.**
답이 정해지면 질문을 결정으로 옮기고, 근거를 `design.md` 에 적는다.

## 작업 방식

1. **무엇을 바꿀지 먼저 제안한다** — 책임·파일·관련 테스트를 말하고 시작한다.
2. **작은 기능 하나씩.** red → green → refactor. red 를 확인받고 green 으로 간다.
3. **green 직후 커밋한다.** 다음 red 를 쌓아 두면 되돌릴 지점이 사라진다.
4. 변경마다 관련 테스트 · `check`(Checkstyle) · ArchUnit 결과를 확인한다.

## 하지 않는 것

- **검사를 통과시키려고 테스트·기대값·규칙을 지우거나 완화하지 않는다.**
  실패는 대개 설계가 틀렸다는 신호다 — 이번 주에만 세 번 그랬다
  (VO 를 컨트롤러에서 만들어 500 · 길이 규칙이 도메인에만 있어 500 ·
  유니크 위반이 저장소 try 밖에서 터짐).
- **정책 변경이나 범위 밖 개편은 이유와 영향을 설명하고 확인을 받는다.**
- 요구사항에 없는 규칙을 스키마로 굳히지 않는다(유니크 제약·외래키 포함).
- 문서에 "지금은 괜찮다" 만 적지 않는다. **뒤집을 조건**을 함께 적는다.

## 실행

```bash
./gradlew :apps:commerce-api:test            # 전체 테스트
./gradlew :apps:commerce-api:check           # 테스트 + Checkstyle
./gradlew :apps:commerce-api:test --tests '*ArchitectureTest'
```

이 환경에서는 gradle 을 직접 실행할 수 없다(네트워크 제한). **빌드·테스트는 사용자가 실행**하고,
결과를 보고 다음으로 간다.

**git 은 실행하지 않는다.** 이 환경에서 돌리면 `.git/index.lock` 이 남고 마운트가 그것을 지우지 못한다.
커밋·삭제가 필요하면 **명령을 만들어 사용자에게 건네고**, 사용자가 자기 셸에서 실행한다.
파일 삭제도 같다 — 마운트에서 `rm` 이 막혀 있다.
