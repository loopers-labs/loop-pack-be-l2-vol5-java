# 6~7주차 현재 인계

## 작업 기준

- 사용자 요청: 6~7주차 통합 진행 + consumer Inbox. 초기 준비 후 R01 이벤트 계약·Inbox 상세 설계까지 요청했다.
- 출발점: `volume-5/main`의 사용자 로컬 커밋 `b4e499d`를 포함한다.
- 기준: `volume-6-7/main`. 현재 작업 브랜치는 `volume-6-7/r01-order-events-inbox`다.
- 현재 checkout/실제 준비 결과는 `git status --short --branch`, `git log -3 --oneline`과
  [준비 검증](initial-setup.md)을 확인한다. 원격 게시/PR 생성은 하지 않는다.

## 읽는 순서

1. [통합 요구사항](total_requirement.md)
2. [R01 상세 설계](r01-order-events-inbox/plan.md)와 연결된 이벤트 계약·Inbox 저장·검증 계획
3. [개발 계획](development-plan.md)
4. [환경·명령과 미수정 설정](environment.md)
5. [W6 과제 원문](reference/week6-implementation-quest.md), [W7 과제 원문](reference/week7-implementation-quest.md)
6. [5주차 최종 점검](../week5/final-audit.md)과 실제 공통 PG 결과 반영 코드

## 다음 작업

사용자가 질문·트레이드오프 논의 누락을 지적했다. R01 문서는 추천 초안이며 확정안이 아니다.
[트레이드오프와 질문](r01-order-events-inbox/trade-offs.md)의 공통 계약 위치·시각 저장·Inbox 비교 근거부터 답변을 받는다.
그 뒤 사건 ID·스키마 호환·오류 처리 등 남은 선택을 논의하고 문서를 확정한다. 미합의 선택에 의존한 구현은 진행하지 않는다.
업무 코드는 아직 구현하지 않았다. 구현에 들어가면 작은 기능 단위 TDD와 전체 TX/DB 제약 보호를 유지한다.
기능 변경은 기존 세 관점 서브에이전트 리뷰 규칙을 따른다. 이번 문서 작업에는 해당 독립 리뷰를 실행하지 않았다.

최종 구조는 Outbox지만 W6 직접 발행 A(누락)·B(소비 완료)의 실제 준비 경로를 보존해야 한다.
제품 우회 스위치나 Outbox 삭제로 이 조건을 만들지 않는다.

초기 fixture 연결 성공은 업무 기능 완료가 아니다. Inbox·Outbox·relay·실제 업무 listener와 W6/W7 실험은 남아 있다.
사용자의 `docs/week4/r01-product-list-index/jmeter/product-list-ui.jmx` 수정은 보존하고 staging하지 않는다.
출발 커밋이 삭제한 과거 `docs/week5/handoff.md`도 복원하지 않는다. 이번 인계는 새로운 경로를 사용한다.
