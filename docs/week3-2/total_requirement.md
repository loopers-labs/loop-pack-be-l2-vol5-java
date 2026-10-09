# 3주차 후속 리팩토링 전체 요구사항

## 1. 목표와 기준 자료

3주차 R01·R02와 패키지 리팩토링(`volume-3/refacto`) 이후의 코드를 기준으로, 트레이드오프 비교로 줄일 수 있는 조회·쓰기 비용을 정리한다.
기능·API 계약은 바꾸지 않으며, 데이터 접근 방식과 쿼리·잠금·저장 경로의 비용만 다룬다.
최적화 하나를 요구사항 폴더 하나와 브랜치·PR 하나로 관리한다.

기준 자료는 다음과 같다.

- **2026-09-29 코드 분석 문답**: 트레이드오프로 최적화할 수 있는 항목 5개와 중요도 순서.
  1. 좋아요 수 10초 주기 전체 재집계 비용
  2. 좋아요순 상품 목록의 정렬 인덱스 부재와 매 요청 `COUNT`
  3. 주문 확정의 상품별 개별 잠금 조회와 저장 시 재조회
  4. repository 저장마다 재조회·`saveAndFlush`
  5. 좋아요 등록의 존재 확인·INSERT·예외 처리 3단계
- **같은 날 데이터 접근 기준 결정**: 조회는 QueryDSL, 쓰기는 JPA로 통일하고, JPA 쓰기와 섞이지 않는 배치 작업(좋아요 집계)만 JDBC를 사용한다.
- **2026-10-09 브랜드 삭제 조사**: 브랜드 삭제의 잠금 조회가 상품 테이블 전체를 잠그는 것을 측정으로 확인했고, 사용자 제안(애플리케이션에서 브랜드 삭제 후 상품 일괄 삭제)으로 R07을 추가했다. R05·R06은 같은 날 폐기했다.
- **같은 날 진행 방식 결정**: JDBC 전환과 좋아요 전체 재집계(항목 1)를 첫 요구사항으로 묶고, 항목 2~4를 요구사항 하나씩 진행한다. 항목 5는 좋아요 등록을 JPA로 옮기는 일이므로 첫 요구사항에 포함한다.
- 기존 [3주차 전체 요구사항](../week3/total_requirement.md), [R01 트레이드오프](../week3/r01-brand-bulk-delete/trade_off/total_trade_off.md), [R02 트레이드오프](../week3/r02-order-consistency/trade_off/total_trade_off.md), [패키지 리팩토링 결과](../refactor/result.md).

이 문서는 전체 목록·공통 기준·진행 상태를 관리한다. 상세 규칙과 시나리오는 개별 요구사항 문서를 기준으로 삼는다.
문서 작성은 구현이나 검증 완료를 의미하지 않는다.

## 2. 포함·제외 범위와 공통 정책

### 포함

- JdbcClient 기반 조회 DAO의 QueryDSL 전환, 좋아요 등록·취소의 JPA 전환.
- 좋아요 수 집계 방식, 좋아요순 목록 조회, 주문 확정 잠금 조회, repository 저장 경로의 비용 개선.
- 각 요구사항의 대안 비교, 구현 계획, 실행 SQL·쿼리 수 확인, 회귀 테스트, 결과와 회고.

### 제외

| 구분 | 제외 내용 | 적용 방식 |
|---|---|---|
| 기능·계약 | 새 API, 응답 필드·상태 코드 변경, 신규 업무 규칙 | 기존 HTTP 계약과 도메인 규칙 유지 |
| 접근 제어 | 인증·인가·본인 확인 | 3주차와 같이 `X-USER-ID` fixture 입력 유지 |
| 외부 인프라 | 캐시 서버·메시지 큐·별도 배치 애플리케이션 도입 | 필요하면 해당 요구사항의 남은 사항으로 기록 |

### 데이터 접근 기준

| 작업 | 도구 | 비고 |
|---|---|---|
| 조회 (`application.<context>.query` 계약의 구현) | QueryDSL | 엔티티가 아닌 Projection으로 Row·View를 만든다. |
| 쓰기 (UseCase·Service 경로, 쓰기 dao) | JPA | Spring Data 또는 HQL. |
| 배치 (좋아요 집계처럼 JPA 쓰기와 섞이지 않는 트랜잭션) | JDBC (`JdbcClient`) | 같은 트랜잭션 안에서 JPA 쓰기와 함께 쓰지 않는다. |

JDBC는 영속성 컨텍스트를 거치지 않아 flush되지 않은 JPA 변경을 보지 못한다. 배치로 범위를 한정하는 이유다.

### 유지하는 기존 정책

- 계층·Context 책임, ArchUnit·Checkstyle 규칙과 검사 완화 금지, TDD 원칙을 유지한다.
- R01의 브랜드 단위 저장·도메인 삭제 판단, R02의 비관적 잠금·잠금 순서 결정은 해당 요구사항에서 명시적으로 다시 비교하기 전까지 유지한다.
- 기존 week1~week3·refactor 문서는 보존한다. 바뀌는 범위는 이 문서와 개별 요구사항에 기록한다.

## 3. 요구사항과 기준 항목 대응

| ID | 요구사항 | 기준 항목 | 상세 문서 |
|---|---|---|---|
| R03 | JDBC 조회 전환과 좋아요 집계 개선 | 데이터 접근 기준, 항목 1, 항목 5 | [R03 명세](r03-jdbc-and-like-aggregation/requirement.md) |
| R04 | 좋아요순 상품 목록 조회 개선 | 항목 2 | [R04 명세](r04-like-sort-index/requirement.md) |
| R05 | 주문 확정 잠금 조회 개선 | 항목 3 | [R05 명세](r05-order-confirm-lock/requirement.md) |
| R06 | repository 저장 경로 개선 | 항목 4 | [R06 명세](r06-save-flush/requirement.md) |
| R07 | 브랜드 삭제의 상품 일괄 삭제 전환 | 2026-10-09 브랜드 삭제 조사 | [R07 명세](r07-brand-delete-bulk/requirement.md) |
| R08 | 테스트 코드의 JDBC 제거 | 2026-10-09 JDBC 사용처 조사 | [R08 명세](r08-test-jdbc-removal/requirement.md) |
| R09 | 주문 확정 파사드 전환 | 2026-10-09 사용자 제안 다이어그램 | [R09 명세](r09-order-confirm-facade/requirement.md) |

ID는 3주차 R01·R02와 브랜치 이름이 겹치지 않도록 R03부터 이어서 붙인다.

## 4. 진행 순서·브랜치·상태

기준 브랜치는 `volume-3/main`이다. `volume-3/refacto`가 병합된 뒤 최신 `volume-3/main`에서 분기한다.
요구사항별 브랜치는 이름만 먼저 확정하며, 실제 생성은 해당 작업을 시작할 때 수행한다.

| 순서 | ID | 폴더 | 작업 브랜치 | PR 대상 | 선행 | 상태 |
|---|---|---|---|---|---|---|
| 1 | R03 | `r03-jdbc-and-like-aggregation/` | `volume-3/r03-jdbc-and-like-aggregation` | `volume-3/main` | refacto 병합 | 구현·검증 완료([결과](r03-jdbc-and-like-aggregation/result.md)), [PR #16](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/16) 병합 완료 |
| 2 | R04 | `r04-like-sort-index/` | `volume-3/r04-like-sort-index` | `volume-3/main` | R03 | 구현·검증 완료([결과](r04-like-sort-index/result.md)), [PR #18](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/18) 병합 완료 |
| 3 | R05 | `r05-order-confirm-lock/` | `volume-3/r05-order-confirm-lock` | `volume-3/main` | R04 | 폐기(2026-10-09, 트레이드오프 문답 전 사용자 결정). 문서만 보존 |
| 4 | R06 | `r06-save-flush/` | `volume-3/r06-save-flush` | `volume-3/main` | R03 필수 | 폐기(2026-10-09, 트레이드오프 문답 전 사용자 결정). 문서만 보존 |
| 5 | R07 | `r07-brand-delete-bulk/` | `volume-3/r07-brand-delete-bulk` | `volume-3/main` | R04 | 트레이드오프 결정([선택 현황](r07-brand-delete-bulk/trade_off/total_trade_off.md)), 구현·검증 완료([결과](r07-brand-delete-bulk/result.md)), R04 병합 후 main 기준으로 리베이스, [PR #19](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/19) 병합 완료 |
| 6 | R08 | `r08-test-jdbc-removal/` | `volume-3/r08-test-jdbc-removal` | `volume-3/main` | R07 | 트레이드오프 결정([선택 현황](r08-test-jdbc-removal/trade_off/total_trade_off.md)), 구현·검증 완료([결과](r08-test-jdbc-removal/result.md)), R07 병합 후 main 기준으로 리베이스, [PR #20](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/20) 병합 완료 |
| 7 | R09 | `r09-order-confirm-facade/` | `volume-3/r09-order-confirm-facade` | `volume-3/main` | R08 | 트레이드오프 결정([선택 현황](r09-order-confirm-facade/trade_off/total_trade_off.md)), 구현·검증 완료([결과](r09-order-confirm-facade/result.md)), R08 병합 후 main 기준으로 리베이스, [PR #21](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/21) 리뷰 중 |

- R04는 R03에서 정한 좋아요 수 저장 위치·갱신 방식을 전제로 하므로 R03 뒤에 둔다.
- R06은 JPA 쓰기 트랜잭션 안에 JdbcClient가 남아 있지 않아야 flush 시점을 옮길 수 있으므로 R03 병합 후에만 시작한다.
- 작업 브랜치끼리 직접 병합하거나 미리 쌓지 않는다. 앞 요구사항이 병합된 뒤 최신 main에서 다음 브랜치를 만든다.
- R05·R06은 트레이드오프 문답을 시작하기 전에 사용자 결정으로 폐기했다. 브랜치는 만들지 않았고 `requirement.md`만 기록으로 남긴다. 다시 진행하려면 최신 main 기준으로 현재 코드를 다시 확인한 뒤 문답부터 시작한다.
- 상태는 실제 진행에 맞춰 갱신한다. PR 생성 후 링크를 추가하고, 병합을 확인한 뒤 병합 완료로 표시한다.

## 5. 요구사항별 작업 절차와 문서 양식

초기 준비에서는 이 문서, 공용 [트레이드오프 템플릿](_template.md), 네 `requirement.md`만 작성한다. 나머지 문서는 해당 요구사항을 진행할 때 만든다.

1. 최신 main에서 작업 브랜치를 만들고, 요구사항의 현재 코드·정책·미정 사항을 사용자와 확인한다.
2. 미정 사항을 트레이드오프 주제로 나누고, **주제 하나당 사용자와 문답을 거쳐 `trade_off/번호-주제.md` 하나를 추가**한다. 결정마다 `trade_off/total_trade_off.md`를 갱신한다.
3. 모든 주제가 결정되면 `plan.md`에 커밋 단위 구현·검증 순서를 작성하고 사용자와 합의한다.
4. 합의한 `plan.md`를 기준으로 구현을 **Sonnet 에이전트에 위임**한다.
   - 위임 프롬프트에는 `plan.md`·`requirement.md`·관련 트레이드오프 링크, 커밋 컨벤션, 검사 완화 금지, 완료 조건을 포함한다.
   - worktree에서 실행하면 git에서 제외된 `apps/commerce-api/src/test/resources/docker-java.properties`를 복사해야 Testcontainers가 Docker에 연결된다.
   - 에이전트 결과는 diff·커밋 단위·테스트 결과를 직접 확인한 뒤 받아들인다. 에이전트 보고만으로 완료로 판단하지 않는다.
5. 실제 검증 결과, 계획과의 차이, 남은 문제, 회고를 `result.md`에 작성한다.
6. 해당 요구사항의 코드·테스트·문서를 하나의 PR로 리뷰하고 병합한다.

| 문서 | 작성 항목 |
|---|---|
| `requirement.md` | 목적 / 현재 동작과 변경점 / 포함·제외 범위 / 규칙 / 시나리오와 기대 결과 / 완료 조건 / 미정 사항 |
| `trade_off/total_trade_off.md` | 주제 링크: 옵션 / 옵션. 채택은 굵게, 미채택은 취소선, 검토 전·검토 중·보류는 별도 표시 |
| `trade_off/번호-주제.md` | [템플릿](_template.md)을 복사해 작성. 판단할 문제 / 인용구 결정 요약 / Mermaid 비교 흐름 / 장단점 비교표 / 옵션별 판단 / 남은 사항 |
| `plan.md` | 변경 책임·구조 / 실제 호출·트랜잭션 경계 / 커밋별 구현 순서 / 검증 계획 / 완료 체크리스트 |
| `result.md` | 구현 결과 / 실제 검증 결과 / 계획과의 차이 / 남은 문제 / 회고 |

- 시나리오는 **사전 상태 / 실행 / 기대 결과 / 유지되어야 할 상태**로 구분한다.
- 미정 사항에는 채택 표시나 취소선을 사용하지 않는다. 문답으로 결정한 뒤에만 표시한다.
- 커밋 메시지는 기존 컨벤션(`type: 한국어 한 문장`)을 따르며, 커밋 하나는 한 문장으로 설명할 수 있는 변경 하나로 한다.
- PR 본문은 선택한 대안·핵심 결과·문서 링크를 요약한다. 긴 원시 로그는 복사하지 않는다.

## 6. 공통 검증 기준

### 초기 문서 준비

상대 링크, ID·폴더·브랜치 대응, 요구사항에 적은 코드 경로의 실제 존재 여부를 확인한다.
코드 변경이 없으므로 빌드·테스트를 실행하지 않는다.

### 각 요구사항 구현

- [3주차 공통 검증 기준](../week3/total_requirement.md#6-공통-검증-기준)을 그대로 적용한다. 잠금·롤백·동시성은 실제 MySQL 8.0 fixture로 검증한다.
- 성능 개선은 전후 비교로 증명한다. 실행 SQL과 요청당 쿼리 수를 기록하고, 인덱스가 관련된 변경은 가능하면 `EXPLAIN` 결과를 남긴다.
- 조회 전환은 기존 조회 테스트·E2E 테스트의 응답 계약이 바뀌지 않는 것으로 확인한다. 테스트 기대값을 바꿔 통과시키지 않는다.
- 관련 회귀 테스트와 `./gradlew :apps:commerce-api:check`를 실행하고 실행 건수·실패·skip·종료 결과를 기록한다.
- 실행하지 못했거나 선택하지 않은 검증은 그 한계를 `result.md`에 명시한다.
