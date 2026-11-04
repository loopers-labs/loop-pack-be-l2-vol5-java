# 6~7주차 현재 인계

## 작업 기준

- 사용자 요청: 6~7주차 통합 진행 + consumer Inbox, 현재 요청은 초기 브랜치/설정.
- 출발점: `volume-5/main`의 사용자 로컬 커밋 `b4e499d`를 포함한다.
- 기준: `volume-6-7/main`. 초기 준비 후 첫 작업은 `volume-6-7/r01-order-events-inbox`다.
- 현재 checkout/실제 준비 결과는 `git status --short --branch`, `git log -3 --oneline`과
  [준비 검증](initial-setup.md)을 확인한다. 원격 게시/PR 생성은 하지 않는다.

## 읽는 순서

1. [통합 요구사항](total_requirement.md)
2. [개발 계획](development-plan.md)
3. [환경·명령과 미수정 설정](environment.md)
4. [W6 과제 원문](reference/week6-implementation-quest.md), [W7 과제 원문](reference/week7-implementation-quest.md)
5. [5주차 최종 점검](../week5/final-audit.md)과 실제 공통 PG 결과 반영 코드

## 다음 작업

R01의 상세 설계부터 시작한다. 최초 확정 TX·기존 confirmedAt/OrderRecord·품목 snapshot·소비 저장 경계를 확인한다.
이벤트 계약/공유 위치와 Inbox·활동 모델/DB 유일 제약·전체 TX·충돌 정책을 구체화하고,
작은 기능 단위 TDD와 저장소의 기능 변경 리뷰 규칙을 따른다.

최종 구조는 Outbox지만 W6 직접 발행 A(누락)·B(소비 완료)의 실제 준비 경로를 보존해야 한다.
제품 우회 스위치나 Outbox 삭제로 이 조건을 만들지 않는다.

초기 fixture 연결 성공은 업무 기능 완료가 아니다. Inbox·Outbox·relay·실제 업무 listener와 W6/W7 실험은 남아 있다.
사용자의 `docs/week4/r01-product-list-index/jmeter/product-list-ui.jmx` 수정은 보존하고 staging하지 않는다.
출발 커밋이 삭제한 과거 `docs/week5/handoff.md`도 복원하지 않는다. 이번 인계는 새로운 경로를 사용한다.
