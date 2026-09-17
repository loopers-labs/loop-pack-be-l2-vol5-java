---
name: feature-slice
description: 과제의 기능 하나(관리자 브랜드 CRUD, 상품 목록 조회, 좋아요 등록·취소, 포인트 충전, 주문 생성·확정 등)를 구현할 때 사용한다. 구현 전에 이번 기능의 정책·변경할 책임·파일·테스트를 제안해 확인받고, 작은 단위로 구현한 뒤 diff와 테스트·lint·ArchUnit 결과를 보고하며, 판단이 바뀌면 설계 문서를 고치게 한다. 여러 기능을 한꺼번에 구현하려 할 때, 설계 문서에 없는 정책을 코드로 정하려 할 때도 이 스킬을 따른다.
---

# Feature Slice — 기능 하나를 끝까지

> 과제 AI 작업 규칙 1~5와 발제의 "좋아요 기능 요청 예시"를 절차로 옮겼다.

## 1. 제안 — 구현 전, 학습자 확인 필요

`docs/week2/plan.md`에서 이 기능의 규칙 ID·API 계약·ADR을 찾아 아래 형식으로 제안한다.

```text
이번 기능: (예: 상품 좋아요 등록·취소)
정책: (적용할 규칙 ID와 한 줄 요약. 문서에 없는 정책이 필요하면 🤔로 따로 묻는다)
구조: 계층별로 만들거나 바꿀 클래스와 책임
  - interfaces: …
  - application: … (Facade 메서드 하나 = 트랜잭션 하나, ADR-11)
  - domain: … (엔티티·VO·도메인 서비스가 지킬 규칙)
  - infrastructure: …
허용 변경: 이 기능과 관련 테스트. 다른 기능·전체 패키지는 바꾸지 않는다.
테스트: 경계별 목록 (domain 단위 / application / repository·DB flush→clear / HTTP)
        정상 + 대표 오류 + 거절 시 기존 값 유지
검증: 관련 테스트, ArchitectureTest, Checkstyle
금지: 기대값·테스트·lint·ArchUnit 규칙을 승인 없이 삭제하거나 완화하지 않는다.
```

- 새 엔티티나 책임 배치가 필요하면 먼저 `domain-modeling` 스킬로 "누가 답하는가"와 화살표를 정한다.
- 도메인 규칙이 들어가면 테스트 기대값 목록은 `tdd` 스킬 0단계대로 학습자가 확정한다.
- 기능이 크면 나눈다. 예: 관리자 브랜드 CRUD / 고객 브랜드 상세.

## 2. 구현

- 도메인 규칙은 `tdd` 스킬로 구현한다.
- application·infrastructure·interfaces도 테스트와 함께 쓴다. 경계와 방식은 `plan.md` 12장을 따른다.
- 스타터 관례를 따른다: `*Model`, `*Facade`/`*Info`, `*V1Controller`/`*V1Dto`/`*V1ApiSpec`, `*Repository`/`*RepositoryImpl`/`*JpaRepository`, `CoreException(ErrorType)`, `ApiResponse`.
- 구현 중 설계와 다른 판단이 필요하면 **코드로 먼저 정하지 않고** 멈춰서 묻는다.
- JPA 함정은 `plan.md` 11장을 확인한다 (`@Table(name)`, `save()` 반환값, 전역 삭제 필터 금지 등).

## 3. 검증과 보고

- `verify` 스킬의 명령을 실행하고 그 보고 형식으로 알린다.
- 기능을 마치면 `review` 스킬로 diff를 검토받는다. 지적은 코드와 대조해 사실을 확인한 뒤, 반영 여부를 학습자와 정한다.

## 4. 마무리

- 판단이 바뀌었으면 `plan.md` 본문·결정 표기·변경 이력을 함께 고친다.
- 커밋 메시지를 제안하고 학습자 확인 후 커밋한다 (`type: 한국어 요약`, AI 도구 표기 없음).
- 다음 기능으로 넘어가기 전에, 이번 기능이 채운 과제 체크리스트 항목을 한 줄로 알린다.
