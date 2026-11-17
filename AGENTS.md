# Loop Java 백엔드 작업 규칙

## 현재 근거와 진입점

- 현재 사용자 요청을 우선한다. 구현 상태는 현재 Git·코드·실제 검사 근거로 판단하며 지난 주차의 준비/승인/브랜치 지시를 재실행하지 않는다.
- [개발 안내](README.md), [개발 컨벤션](docs/week2/conventions.md), 해당 주차의 기준 문서를 읽는다.
- 세션 재개에는 `.agents/skills/loop-backend-delivery/SKILL.md`를 활용한다. `docs/week8/handoff.md`가 있으면 로컬 인계로 읽는다.
  이 파일은 Git 추적 대상에서 제외하며 다른 checkout에 없을 수 있다. 없으면 Git·코드·검사 근거로 재구성한다.
- 쿠폰의 현재 기준은 [설계](docs/week8/design.md), [구현 현황](docs/week8/completed/README.md), [검증 한계](docs/week8/verification-plan.md)다.
  C01부터 다시 시작하거나 도메인만/준비만 허용된 상태로 오해하지 않는다.
- 작업은 요청된 기능/문서 범위로 한정한다. 과거 기록만으로 다음 기능·리팩터링·원격 게시를 시작하지 않는다.

## 협업과 검증

| 역할 | 책임 |
|---|---|
| 메인 | 범위·구현·테스트 코드·수정·테스트 실행·커밋·결과 보고 |
| 리뷰 1명 | 비즈니스·기술/유지보수·사용자 경험을 함께 검토하고 검증 항목 제안 |
| QA | 변경·테스트 범위·실행 결과 읽기 검토. 기본적으로 테스트를 직접 재실행하지 않음 |

기능 변경은 메인 구현 → 리뷰 → 메인 보완·최소 관련 검사 → QA 결과 검토 → 보완·커밋 순서다.
리뷰 요청에 목적·대상·핵심 위험·검증 정보를 함께 전달한다. 재검토는 지적 부분과 변경 영향에 집중한다.
통과 후 새 변경·실패·미해결 우려가 없으면 같은 검사를 반복하지 않는다. 공통 설정 영향이 있을 때만 범위를 넓힌다.
문서/이력 전용 변경은 내용·링크·diff·Git 검증으로 간소화하고 앱 테스트와 별도 QA는 생략할 수 있다.
승인된 범위는 단계별 재확인 없이 완료한다. 미정 정책·의미가 불명확한 충돌은 질문한다.
환경 실패·미실행·0건·skip을 통과로 기록하지 않는다. 이전 실행/재사용 근거와 새 실행을 구분한다.

## 구조와 Java 관례

- Java 21, 생성자 주입, 기존 스타일과 `.editorconfig`의 본문 130자/테스트 예외를 따른다. 별표·미사용 import는 허용하지 않는다.
- domain/application/interfaces는 `<layer>.<context>.<kind>`, infrastructure는 `infrastructure.<kind>.<context>` 순서다.
  실제 업무 Context는 mall/shopping/ordering/pay/coupon이며 shared와 legacy example을 구분한다.
- domain은 순수 Java로 모델·업무 규칙·repository 계약을 소유하고 Spring·JPA·HTTP·다른 계층에 의존하지 않는다.
  저장 Entity와 도메인을 분리하고 도메인이 JPA BaseEntity를 상속하지 않게 한다.
- application은 순서·TX·쓰기 조건·외부 gateway/DAO 계약을 조율한다. interfaces와 infrastructure 구현에 의존하지 않는다.
  interfaces는 HTTP 변환과 오류 매핑, infrastructure는 저장·조회·외부 기술을 담당한다.
  infrastructure는 interfaces나 application의 Service 구현에 의존하지 않는다.
- 도메인은 create/restore와 의미 있는 행동을 제공한다. 실패 후 메모리 상태도 보존하며 변경 뒤 repository.save를 명시한다.
  EntityMapper는 infrastructure에서 저장 메타데이터·snapshot을 보존하며 업무 판단을 넣지 않는다.
- 쓰기는 Command/Result, 조회는 Criteria/View/QueryDao를 사용한다. QueryDao는 HTTP를 알지 못하며 상세 없음은 Controller가 처리한다.
  일반 조회는 QueryDSL projection을 사용한다. 운영 JDBC 의존은 실제 `LayerArchitectureTest`의 Jdbc* 규칙을 따른다.
- R08의 테스트 JDBC 제거 기록과 현재 쿠폰 테스트의 직접 JDBC 사용은 차이가 있다. 이를 새 코드 전반의 허용으로 확대하거나
  문서 정리를 이유로 코드·ArchUnit 규칙을 바꾸지 않는다.

## 현재 동작에서 보존할 경계

- PG 주문 확정은 짧은 준비 TX → TX 밖 제공자 호출 → 관찰 저장 → 공통 결과 반영이다.
  과거 POINT 단일 TX 설명을 현재 ConfirmOrderFacade에 적용하지 않는다. [상태/회복 기준](docs/week5/states/total.md)을 따른다.
- `X-USER-ID`는 fixture 식별이며 실제 인증이 아니다. 필요한 사용자 존재·주문/쿠폰 소유권·관리자 fixture 검사는 기능 계약을 따른다.
  과거의 소유권 검사 제외 결정을 현행 주문 API에 적용하지 않는다.
- 좋아요 관계는 즉시 반영하고 likeCount는 commit 뒤 증감분을 모아 5초 fixed delay로 저장한다. 시작 시 전체 재집계로 누락을 복구한다.
  과거의 매 10초 전체 COUNT 설명을 현행 구현으로 사용하지 않는다.
- 주문 확정 이벤트는 최종 Outbox 경로를 사용한다. Inbox와 모든 구매 활동은 같은 TX이며 DB commit 뒤 ack한다.
  [이벤트 계약](docs/week6-7/r01-order-events-inbox/event-contract.md)과 [복구 절차](docs/week6-7/r03-delivery-recovery/operations.md)를 따른다.
- 쿠폰 총량·중복은 DB 쿠폰/슬롯/issuedCount의 같은 TX, 처리 여력은 인스턴스별 Semaphore, Redis는 누적 집계다.
  종료 불명 permit 보류·로컬 닫힘·지정 실행 복구를 유지하며 HTTP timeout을 실행 종료로 간주하지 않는다.
- 신규 기능은 application과 infrastructure를 기능별로 함께 완성한다. 과거 쿠폰 구현 단계의 presentation 마지막 순서를 다른 작업에 자동 적용하지 않는다.

## 테스트와 환경

- 업무 규칙은 핵심 정상·경계·실패 후 상태를 TDD로 검증한다. 단순 getter나 구현 복사 검사는 늘리지 않는다.
- 도메인 단위, 협력 단위, 실제 DB/외부 기술, HTTP 경계의 책임을 구분한다. 전체 rollback·동시성·DDL 제약은 실제 DB 근거를 확인한다.
- JUnit은 한글 DisplayName, 필요한 Nested, arrange/act/assert와 기존 AssertJ 관례를 따른다.
- 공용 IntegrationTest/E2ETest 구성을 재사용하되 독립 DB/Redis/프로세스가 필요한 시험의 경계를 보존한다.
  현재 테스트는 MySQL뿐 아니라 Redis·Kafka·PG도 사용한다. [레이어별 실행 안내](docs/test/application.md)를 따른다.
- Checkstyle·ArchUnit은 실제 설정·대상과 실행 결과를 확인한다. 테스트 기대값·업무 규칙·검사 규칙을 완화해 통과시키지 않는다.
- Docker 접근과 Gradle/JDK/cache는 현재 세션에서 확인한다. 다른 세션의 권한·검사 성공을 그대로 가정하지 않는다.
  반복되는 환경 거절은 우회하지 말고 실행 가능한 확인과 필요한 사용자 실행 명령을 구분한다.

## 변경 보존과 Git

- 사용자 수정·staging·미추적 파일을 보존한다. 기존 Example 개편, 무관한 설정·데이터·브랜치 정리를 섞지 않는다.
- 사용자 JMeter 변경은 명시적 요청이 없으면 수정하거나 staging하지 않는다. 요청 범위의 파일만 명시적으로 staging한다.
- 커밋은 `type: 한글 요약`, 빈 줄, 변경 이유·예외·실제 검증을 설명하는 상세 bullet로 작성한다.
  긴 본문은 UTF-8 파일과 `git commit --file`을 사용하고 커밋 뒤 해시·본문·상태를 확인한다.
- push/PR/병합/이력 재작성은 현재 사용자 요청에 포함된 범위에서만 수행한다. 과거 작업의 게시 권한을 새 요청에 자동 적용하지 않는다.
- 로컬 인계에는 다음 작업·정확한 HEAD·검증 근거·환경 한계를 남긴다. 인계 본문을 다시 추적하거나 오래된 진행 상태를 지침에 복사하지 않는다.
