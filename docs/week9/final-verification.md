# W9 6단계 — 최종 회귀와 채점 근거 대조

2026-11-19. [5단계 전체 흐름 검증](full-chain-ranking-tdd-log.md)에 이어 현재 코드와 [W9 원문 9항목](grading-criteria.md)을 대조한다. 1~5단계의 실행 기록과 과제·학습 원문은 보존하고, 이번 전체 회귀 결과는 이 문서에 별도로 남긴다.

**현재 코드의 W9-C01~09는 모두 충족으로 판단한다.** 세 앱 전체 회귀 195개 스위트·2,441개 테스트가 통과했고 실패·오류·skip은 모두 0이다. 세 앱 ArchUnit과 Checkstyle 보고서 12개의 위반 0을 확인했다. 이번 독립 코드·원문 대조에서 추가 기능 수정이 필요한 결함은 발견하지 못했으며, 생산 코드와 테스트 기대값을 변경하지 않고 최신 문서 상태와 증거 연결을 보완했다. 구현·검증 완료와 실제 제출 완료는 구분한다.

## 이번 대조 범위

- `commerce-batch`의 입력 고정·점수·checkpoint·완성본 공개, `commerce-api`의 고객 랭킹 HTTP, `commerce-streamer`의 기존 구매 활동 소비를 함께 확인한다.
- W5 PG·주문·예약, W6 소비 중복 보호, W7 Outbox·relay, W8 쿠폰의 기존 기대값과 트랜잭션·락 경계를 유지한다. 기존 회귀 통과를 W9 신규 기능의 통과 근거로 대신하지 않는다.
- 채점 원문에 없는 성능 합격선, 다중 Batch JVM 자동 조율, 실제 metadata 장애의 무조건 자동 복구를 추가 완료 조건으로 만들지 않는다. 현재 설계의 범위와 검증하지 않은 상황을 구분한다.
- 이번 검사에서는 테스트가 소유한 격리 자원을 사용한다. 사용자 DB·공유 Redis·기존 topic/group에 연결하거나 이행 SQL을 적용하지 않는다. 커밋·푸시는 하지 않는다.

## W9-C01~09의 구현·테스트 근거

| 항목 | 판정 | 현재 코드에서 확인하는 동작 | 테스트와 기록의 근거 |
| --- | --- | --- | --- |
| W9-C01 실제 구매 활동 → 랭킹 API | 충족 | 실제 주문의 PG 성공·Outbox·Kafka·streamer 활동을 고정 입력으로 계산해 고객 HTTP로 제공한다. 같은 사건을 다시 소비해도 구매 활동과 점수가 늘지 않는다. | [PgOrderWeeklyRankingEndToEndTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/PgOrderWeeklyRankingEndToEndTest.java)의 `duplicateEventsDoNotIncreaseScoresAndLatePurchaseOnlyEntersANewRevision`. 실제 별도 offset의 재발행과 DUPLICATE 소비 완료까지 대조한다. [5단계 기록](full-chain-ranking-tdd-log.md) |
| W9-C02 수량·동점·여러 품목 | 충족 | `orderId·productId`별 전체 활동의 quantity를 합산하고 score 내림차순·productId 오름차순으로 순위를 저장한다. | [WeeklyRankingSnapshotIntegrationTest](../../apps/commerce-batch/src/test/java/com/loopers/application/ranking/WeeklyRankingSnapshotIntegrationTest.java)의 `freezesAllFourDocumentedItemsAndCommitsAnExactManifest`, [WeeklyRankingJobIntegrationTest](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingJobIntegrationTest.java)의 정상/재시작 대조, 전체 흐름 테스트. 상품 7=5점/1위, 9=5점/2위, 8=1점/3위의 독립 기대값을 확인한다. |
| W9-C03 늦은 구매·새 revision | 충족 | 완성된 입력은 불변이다. 같은 작업 재시작은 기존 입력을 사용하고 늦은 구매는 원래 주의 새 snapshot·revision에서만 반영한다. | `WeeklyRankingSnapshotIntegrationTest.sameKeyReusesStoredInputWithoutReadingLateSourceAndNewRevisionIncludesIt`와 전체 흐름의 r2 입력 고정 뒤 실제 늦은 이벤트 전달. r2는 4행·5/5/1, 새 r3는 5행·9/5/1이며 공개 HTTP도 비교한다. |
| W9-C04 동일 instance 재시작 | 충족 | 고정된 식별 parameter와 영속 JobRepository로 같은 JobInstance의 새 JobExecution을 실행한다. 마지막 commit의 reader 위치부터 이어 간다. | [WeeklyRankingCheckpointProbeIntegrationTest](../../apps/commerce-batch/src/test/java/com/loopers/job/ranking/WeeklyRankingCheckpointProbeIntegrationTest.java)의 작은 chunk=2 실험, `WeeklyRankingJobIntegrationTest.newContextRestartsSameInstanceAtProductNineUsingFrozenInputAndPersistentCheckpoint`, 전체 흐름의 새 Batch JVM 재개. 위치 2와 재시작 방문 상품 `[9]`를 확인한다. |
| W9-C05 reader·writer·공동 TX | 충족 | snapshot의 서로 다른 productId를 오름차순으로 읽고 절대 점수를 저장한다. 실제 점수와 checkpoint가 같은 데이터 소스·transaction manager·connection의 TX에 참여한다. | [WeeklyRankingScoreStoreIntegrationTest](../../apps/commerce-batch/src/test/java/com/loopers/application/ranking/WeeklyRankingScoreStoreIntegrationTest.java)의 `writingTheSameAbsoluteScoreTwiceKeepsOneRowAndTheSameValue`, `WeeklyRankingJobIntegrationTest.actualScoreAndCheckpointSqlRollBackTogetherAndRestartPreservesFirstChunk`. 실제 SQL 후 runtime·checked 실패 및 checkpoint SQL 후 실패에서 공동 rollback을 확인한다. [3단계 기록](score-checkpoint-tdd-log.md) |
| W9-C06 정상/재시작 결과 동일 | 충족 | 정상 실행과 실패 후 재시작의 입력·결과 checksum, 상품별 점수·순위를 함께 비교한다. 재시작 사이에 입력·stage·metadata를 초기화하지 않는다. | `WeeklyRankingJobIntegrationTest.newContextRestartsSameInstanceAtProductNineUsingFrozenInputAndPersistentCheckpoint`와 전체 흐름의 정상 r1/재시작 r2 비교. 생산 serializer를 그대로 호출해 만든 기대값만으로 판정하지 않고 손 계산과 독립 checksum을 사용한다. |
| W9-C07 실패 시 공개본 유지 | 충족 | 계산 실패·공개 TX rollback은 이전 공개본을 유지한다. 공개 commit 뒤 Batch 최종 상태 기록 전 실패는 실제 공개 사실과 별도로 판단한다. | [WeeklyRankingPublicationIntegrationTest](../../apps/commerce-batch/src/test/java/com/loopers/application/ranking/WeeklyRankingPublicationIntegrationTest.java)의 `failureAfterActualResultInsertRollsBackAndPreservesThePreviousPointer`와 `failureAfterActualPointerUpdateRollsBackResultsCompletionAndPointerTogether`. `WeeklyRankingJobIntegrationTest.publicationCommitSurvivesFailureBeforeFinalBatchStatusAndSameInstanceRestartsOnlyPublication`은 commit 후 실패와 같은 instance 공개 재개를 확인한다. 계산 실패 뒤 이전 공개본 HTTP는 전체 흐름 테스트에서 확인한다. 아래 주입 범위 구분 참조. |
| W9-C08 반복 공개·revision 보호 | 충족 | 같은 COMPLETE snapshot 공개는 기존 결과를 검증하고 보존한다. 조건부 pointer 갱신으로 오래된 revision이 새 공개본을 덮지 않는다. 기본 운영은 같은 기간의 계산을 직렬 실행한다. | `WeeklyRankingPublicationIntegrationTest.repeatingTheSamePublicationLeavesRowsAndManifestAndPointerUnchanged`, `anOlderRevisionCannotReplaceANewerPublicationAndOtherWeekIsPreserved`와 공개 경합 테스트. 전체 Job의 직렬 운영 범위와 짧은 공개 TX 잠금은 아래에서 구분한다. |
| W9-C09 조회 경계·회귀·정적 검사 | 충족 | 미공개 404와 구매 0건의 완성본 200, UTC 기간·입력 검증, 현재 상품/브랜드 공개 조건 후 limit, 저장된 순위 간격을 유지한다. 관련 회귀·lint·ArchUnit과 소유 자원 종료를 확인한다. | [WeeklyRankingHttpIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/ranking/WeeklyRankingHttpIntegrationTest.java), [WeeklyRankingInputTest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/ranking/WeeklyRankingInputTest.java), snapshot UTC 나노 경계 테스트. 이번 세 앱 전체 회귀·정적 검사와 소유 자원 정리 결과는 아래 표에 기록했다. |

입력 고정은 [2단계 기록](snapshot-freeze-tdd-log.md), 실제 writer/checkpoint 연결은 [3단계 기록](score-checkpoint-tdd-log.md), 공개·API는 [4단계 기록](publication-api-tdd-log.md), 실제 주문부터의 연결은 [5단계 기록](full-chain-ranking-tdd-log.md)을 근거로 한다. 과거 기록의 당시 검사 범위와 건수를 이번 전체 회귀 결과로 바꾸지 않는다.

## 공개 commit과 Batch metadata의 실패 경계

[과제 원문](assignment-original.md#-5-완성본만-공개하기)은 공개 TX commit과 최종 Batch metadata 저장을 같은 순간으로 가정하지 않도록 요구한다. 이미 공개된 결과가 있다면 실제 snapshot·pointer를 먼저 확인하고, 공개 단계를 다시 실행하더라도 같은 결과로 수렴해야 한다.

| 장면 | 주입·관찰한 범위 | 해석과 유지할 경계 |
| --- | --- | --- |
| 계산 중 실패 | 상품 7·8과 reader 위치 2의 commit 뒤 9번 직전 실패 | 기존 공개 pointer와 HTTP 결과를 유지한다. 같은 입력·같은 instance의 새 execution에서 9번부터 재개한다. |
| 공개 TX 안에서 실패 | 실제 결과 INSERT 뒤 또는 실제 pointer UPDATE 뒤 예외 | 결과 행·COMPLETE·pointer가 함께 rollback된다. Batch DB 통합 테스트에서 이전 pointer·결과를 재조회한다. 이 rollback 주입과 HTTP 재조회를 한 테스트에서 수행했다고 확대하지 않는다. |
| 공개 commit 뒤 최종 완료 기록 전 실패 | 테스트 전용 `ChunkListener.afterChunk`에서 별도 DB 연결로 COMPLETE·pointer·결과 commit을 먼저 확인한 뒤 예외 | 실행은 FAILED로 남지만 새 공개본은 보존된다. 같은 DB를 유지한 새 context에서 같은 instance로 공개 Step만 재실행해 결과 행·checksum 불변을 확인한다. |
| 실제 metadata SQL 오류·강제 JVM 종료 | 위 `afterChunk` 주입과 다른 장애이며 동일하게 재현했다고 주장하지 않는다. | metadata 저장 오류는 UNKNOWN, 강제 종료는 STARTED 등으로 남을 수 있다. 이전 프로세스와 DB 작업의 종료·실제 공개 사실·metadata를 확인하기 전 상태를 임의로 FAILED로 바꾸거나 새 `run.id`로 우회하지 않는다. 자동 복구 완료를 주장하지 않는다. |

실제 metadata SQL 실패 자체를 주입하지 않았다는 한계와, 공개 commit 후 최종 완료 기록 전 실패를 실제 DB로 검증했다는 사실을 함께 남긴다. 단순히 JobExecution이 FAILED라는 이유로 정상 공개본을 지우거나 pointer를 이전 revision으로 되돌리지 않는다.

## 동일 기간 직렬 실행의 범위

[선택한 실행 계약](../week2/commerce-policy-decisions.md#w9-주간-랭킹의-선택)은 **한 전용 실행 주체에서 동기 JobLauncher로 한 작업을 끝낸 뒤 다음 작업을 실행하는 방식**이다. 기간·revision을 직접 지정하고, 같은 주를 여러 Batch JVM에서 동시에 계산하지 않는다. 자동 스케줄·다중 실행 주체 조율은 이번 기본 범위에 없다.

manifest 잠금은 stage 쓰기와 완성본 공개의 충돌을 보호한다. 기간별 pointer의 조건부 갱신은 공개 revision의 후퇴를 막는다. 이 짧은 DB TX 잠금과 JobRepository의 동일 instance 중복 실행 방지를, 서로 다른 revision까지 포함한 전체 Job의 분산 락으로 해석하지 않는다.

다중 Batch JVM의 같은 기간 동시 계산을 허용하려면 별도의 실행 소유권·중단 후 복구 정책과 검증을 추가해야 한다. 현재 원문의 기본 직렬 실행은 위 운영 범위에서 적용한다.

## 이번 전체 검사 결과

다음 명령으로 기존 결과를 재사용하지 않고 세 앱의 일반 `check` 전체를 실행했다.

```bash
./gradlew :apps:commerce-api:check :apps:commerce-batch:check :apps:commerce-streamer:check --rerun-tasks --console=plain
```

| 확인 범위 | 이번 실행 결과 |
| --- | --- |
| commerce-api | 171개 스위트·2,199개 테스트 통과 |
| commerce-batch | 15개 스위트·143개 테스트 통과 |
| commerce-streamer | 9개 스위트·99개 테스트 통과 |
| 합계 | **195개 스위트·2,441개 테스트**, 실패·오류·skip **모두 0** |
| ArchUnit | 세 앱의 `ArchitectureTest` 모두 통과. 위 테스트 수에 포함 |
| Checkstyle | 실제 실행된 보고서 **12개·위반 0**. 세 앱 main/test 6개와 공통 JPA·Redis·Kafka·Jackson 및 testFixtures 6개 |
| Gradle 결과·소요시간 | **BUILD SUCCESSFUL in 14m 54s**, 46개 작업 실행 |
| 소유 자원 정리 | 종료 후 Testcontainers 라벨의 남은 컨테이너 0개. Gradle wrapper·Batch probe·streamer 자식 JVM 0개 |
| 최종 diff·문서 정합성 | `git diff --check` 통과. 최신 안내의 6단계 연결과 로컬 문서/코드 링크 확인 |

실행 로그는 `/private/tmp/w9-final-regression-20261119.log`, JUnit XML·Checkstyle XML 집계는 `/private/tmp/w9-final-regression-20261119.json`에 있다. [API 보고서](../../apps/commerce-api/build/reports/tests/test/index.html) · [Batch 보고서](../../apps/commerce-batch/build/reports/tests/test/index.html) · [streamer 보고서](../../apps/commerce-streamer/build/reports/tests/test/index.html). 임시 파일과 build 보고서는 이후 실행으로 바뀔 수 있으므로 핵심 결과는 이 문서에 보존한다.

API의 기존 별도 측정 태그 `ttl-measurement`, `coupon-load-measurement`는 일반 `check` 대상이 아니다. 이번에는 해당 장시간 TTL·10만 건 부하 실험을 다시 실행하지 않았다. 위 skip 0은 **이번 일반 회귀 대상**의 결과이며 별도 측정까지 모두 실행했다는 뜻이 아니다. 검사 제외나 assertion 완화는 새로 추가하지 않았다.

### 이번 전체 실행의 W9 연결 증거

| 관찰값 | 실제 결과 |
| --- | --- |
| 소유 topic / group | `w9-ranking-orders-f9c52ae6-72ec-45a3-864f-0ac3ef2065e1` / `w9-ranking-activity-fa35a523-27af-47d7-9f81-6c6d48bba5b2` |
| 실제 주문·최초 확정 시각 | 주문 `[1, 2, 3, 4]`, `2026-08-20T12:34:56.123456Z` |
| 같은 사건의 실제 재발행 | partition `1`, offset `0`과 `2`. 소비 완료 뒤 활동·점수 추가 효과 0 |
| 정상 / 실패·재개 / 새 revision | r1 instance `1`, r2 instance `2`의 execution `2 → 3`, 새 r3 instance `3` |
| 입력·점수 | source `4 → 5`행, r1/r2/r3의 frozen 입력 `4/4/5`행. r1/r2는 상품 7=5·9=5·8=1, r3만 7=9·9=5·8=1 |
| 정상 신규 주문 준비 뒤 추가 업무 | PG create 증가 `0`, 주문·시도·재고·포인트 비교 `businessUnchanged=true` |
| r1/r2 입력 checksum | `919ca445ee4cc3f635beedae5e2d72ab0c567625616253768b58169e980299ad` |
| r3 입력 checksum | `215f1bcff42f4bf59e39b2465c27be2e6f56efa578dcf9354c264893eafc1125` |
| r1/r2 결과 checksum | `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2` |
| r3 결과 checksum | `210769764542f291dd603421f23ea3eca287b9d05ec2ed199a827d206b454aa4` |

위 값은 이번 `PgOrderWeeklyRankingEndToEndTest`의 JUnit XML `w9_full_chain` 출력과 assertion에서 확인했다. 이전 5단계 실행의 topic/group을 덮어쓰거나 재사용하지 않았다.

테스트는 Testcontainers의 소유 MySQL·Redis·Kafka와 고유 topic/group, 로컬 HTTP 테스트 PG, 소유 Batch/streamer 자식 JVM을 사용한다. 재시작 시나리오 도중 같은 DB·입력·stage·Batch metadata·소비 상태를 보존하며, 시나리오가 끝난 뒤 소유 자원만 정리한다. 사용자 DB나 공유 자원을 정리 대상으로 삼지 않는다.

이번 전체 실행은 첫 실행에서 통과했다. 테스트·기대값·정적 검사 규칙을 삭제하거나 완화하지 않았다. 검사 소요시간은 서비스 처리량이나 응답 속도 개선 수치가 아니다.

## 완료와 제출 상태

W9-C01~09의 구현·검증 근거와 전체 회귀를 확인하고 [구현 체크리스트](implementation-checklist.md)의 6단계를 완료로 갱신했다. 이번 과제의 구현·검증에서 추가 필수 보완 사항은 발견하지 못했다. 위의 다중 JVM·실제 metadata 장애 자동 복구 및 대용량 처리 성능은 검증했다고 주장하지 않는 범위다.

PR·기술 글 작성과 실제 제출 여부는 구현·검증 완료와 별도다. 이번 작업에서는 커밋·푸시·PR 제출을 하지 않았다. 남은 제출 준비는 PR·기술 글 작성/제출이며, 실제 제출 완료 여부를 추정하지 않는다.
