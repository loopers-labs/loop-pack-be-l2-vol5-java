# W9 4단계 — 완성 순위·공개 pointer·고객 API

> 이 문서는 해당 단계 당시의 기록이다. 이후 단계와 세 앱 전체 회귀의 최신 판정은 [6단계 최종 검증](final-verification.md)을 따른다.

2026-11-19. [3단계 점수·checkpoint 기록](score-checkpoint-tdd-log.md)에 이어 `weeklyRanking`의 세 번째 공개 Step과 고객 조회를 연결한다. 사용자 DB에는 접속하지 않고 테스트가 소유한 MySQL을 사용한다. 실제 PG → Outbox → Kafka → streamer → Batch → HTTP 전체 연결과 최종 전체 회귀는 후속 5·6단계다.

## 공개 방법 선택

| 방법 | 가능한 보장과 비용 | 이번 선택 |
| --- | --- | --- |
| 결과를 같은 TX에서 전체 교체 | 원자적으로 바꿀 수 있으나 이전 revision 보존과 대량 변경 관리가 필요 | 미선택 |
| COMPLETE 중 가장 높은 revision 조회 | 완성 즉시 공개하는 정책이면 가능. 요청에서 snapshot을 한 번 선택하면 결과 혼합도 막을 수 있음 | 미선택 |
| 기간별 공개 pointer | 어떤 완성본을 보여주는지 별도 행으로 명시하고 이전 결과를 보존 | 기존 설계를 유지 |

pointer는 메모리 주소가 아니라 `weekly_ranking_publication`의 기간 → snapshotId·revision 연결이다. pointer 자체가 원자성을 만드는 것은 아니다. 완성 결과 저장·manifest COMPLETE 및 결과 checksum·pointer 전환을 같은 DB TX로 묶는다.

## 구현 경계

- `CompletedRanking`은 고정 입력에서 상품별 수량을 다시 합산해 stage의 누락·추가·중복·잘못된 점수를 거절한다. score DESC/productId ASC로 순위를 붙인다.
- `WeeklyRankingPublicationService`와 JDBC 저장 포트가 자기 manifest를 잠그고 입력 checksum·완성 조건을 검증한다. score writer도 같은 manifest 잠금을 사용하므로 공개 검증과 stage 변경이 겹치지 않는다.
- [003 DDL](../../apps/commerce-batch/src/main/resources/db/schema/003-weekly-ranking-publication.sql)은 결과·순위 유일 제약과 pointer의 기간/snapshot/revision 복합 FK를 추가한다. 더 큰 revision만 pointer를 전진시키고 같은 공개 반복 시 저장 결과를 검증해 유지한다.
- Job은 `freezeWeeklyInput → calculateWeeklyScores → publishWeeklyRanking`으로 연결한다. 세 Step은 기존 실제 transaction manager와 영속 JobRepository를 사용하고, 예외 rollback 규칙을 유지한다.
- API는 `GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10`에서 공개 pointer와 manifest를 한 SELECT로 고른다. 이후 같은 불변 snapshot의 결과만 페이지 조회한다.
- 현재 상품·브랜드 공개 조건은 주 DB의 한 RR 읽기 TX에서 확인한다. 필터 후 limit, 현재 이름, 품절 포함, 과거 점수·저장 순위 간격을 유지한다. 두 DB 전체를 한 시점으로 묶는 분산 TX는 아니다.
- API의 별도 읽기 pool은 래퍼가 소유한다. DataSource·JdbcTemplate·transaction manager bean을 추가해 기존 JPA 자동설정을 바꾸지 않는다. 기본 OFF이고 연결 설정이 없으면 503이며 주 DB로 우회하지 않는다.

API 설정은 `commerce.ranking.query.*`이며 환경변수 `WEEKLY_RANKING_QUERY_ENABLED`, `WEEKLY_RANKING_QUERY_JDBC_URL`, `WEEKLY_RANKING_QUERY_USERNAME`, `WEEKLY_RANKING_QUERY_PASSWORD`로 제공한다. 활동/랭킹 DB의 읽기 권한 계정을 사용한다. 읽기 pool 최대 3, 연결 대기 1초, query 2초, driver connect 1초/socket 2초는 유한 대기를 위한 초기 기술값이며 성능 측정에 따른 최적값은 아니다. Hikari readOnly는 DB 계정 권한을 대신하지 않는다.

## 테스트 기대값과 Red

| 경계 | 기대값 |
| --- | --- |
| 손 계산 | 상품 7=5점/1위, 9=5점/2위, 8=1점/3위 |
| 내용 checksum | UTF-8 `7\t5\t1\n9\t5\t2\n8\t1\t3\n`의 SHA-256 `745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2` |
| 공개 TX 실패 | 실제 결과 INSERT 또는 pointer 변경 뒤 예외가 나면 새 결과/COMPLETE/pointer 모두 rollback, 이전 공개본 유지 |
| 반복·오래된 공개 | 같은 행·순위·checksum 보존, 새 revision을 이전 revision으로 되돌리지 않음 |
| 계산 실패·재시작 | 이전 pointer 유지, 같은 instance·새 execution으로 마지막 chunk 다음부터 진행해 정상 실행과 결과/checksum 일치 |
| 공개 commit 후 실패 | 독립 연결에 이미 새 공개본이 보임. 같은 instance 재시작은 공개 Step만 실행해 동일 결과 유지 |
| API | 미공개404, 완성 빈 주200, 입력400, 조회 불가503. 삭제 필터 후 limit·순위 간격·품절 포함·조회 중 pointer 변경에도 한 snapshot 유지 |

Red 실행 기록:

- `/private/tmp/w9-publication-job-red.log`: 기존 빈 주 Job에 세 번째 공개 Step이 없어서 1개 실패.
- `/private/tmp/w9-publication-red.log`: 공개 모델/서비스 stub 상태에서 23개 중 22개 실패.
- `/private/tmp/w9-ranking-api-red.log`: API 입력/조회 stub 상태에서 21개 실패.

## 공개 commit과 Batch 최종 상태의 간격

Job 통합 테스트는 테스트 전용 `ChunkListener.afterChunk`에서 독립 DB 연결로 새 pointer·COMPLETE·결과가 커밋됐음을 먼저 확인한 뒤 실패를 주입한다. 이는 **공개 commit 뒤 최종 Batch 완료 상태 저장 전 실패**이며, 실패한 실행은 FAILED로 남는다. 같은 DB와 metadata를 보존한 새 application context에서 같은 instance로 재시작한다.

이 실험을 실제 metadata SQL 장애나 강제 JVM 종료와 동일하다고 주장하지 않는다. 실제 상태 저장 장애는 UNKNOWN, 강제 종료는 STARTED 등으로 남을 수 있다. 종료 여부와 공개 DB 사실을 확인하기 전 metadata를 임의로 FAILED로 바꾸거나 입력/결과를 지워 재실행하지 않는다. 이런 운영 경계와 전체 흐름은 후속 검증에서 구분한다.

## 최신 검사 결과

Batch 전체 검사: `./gradlew :apps:commerce-batch:check --console=plain`

- `/private/tmp/w9-publication-final-batch-check.log`: **15개 suite·143개 테스트**, 실패·오류·skip 0. ArchUnit과 main/test Checkstyle 통과. `BUILD SUCCESSFUL`, 종료 코드 0.
- 추가 공개 경합 검증에는 최초 서로 다른 revision 공개, 기존 pointer에 대한 이전 COMPLETE 재공개/새 revision 공개, 이미 열린 RR view 뒤 다른 실행이 공개한 결과의 재검증이 포함된다.
- 정상/계산 재시작: `weekly-20260817-r2`, instance `8`, execution `12 → 13`, score Step `27 → 28`, 저장 위치 `2`, 재시작 상품 `[9]`. 관찰된 writer/checkpoint 연결 식별값은 `15/15`다.
- 공개 commit 후 실패/재시작: 같은 snapshot, instance `6`, execution `9 → 10`. 결과는 `7:5:1, 9:5:2, 8:1:3`이고 위 결과 checksum이 유지됐다. 이 번호는 이번 격리 실행에서 관찰한 값이며 다음 실행의 고정 기대값이 아니다.

API 관련 검사:

```bash
./gradlew :apps:commerce-api:test \
  --tests '*ranking*' --tests '*ArchitectureTest' \
  --tests '*ProductQueryApiE2ETest' --tests '*PgOrderConfirmApiE2ETest' \
  --tests '*CouponQueryHttpIntegrationTest' --tests '*CommerceJsonBoundaryApiE2ETest' \
  :apps:commerce-api:checkstyleMain :apps:commerce-api:checkstyleTest --console=plain
```

- `/private/tmp/w9-ranking-api-final-check.log`: **8개 suite·91개 테스트**, 실패·오류·skip 0. 랭킹 신규 34개(입력16·서비스7·실제 HTTP11)와 기존 상품·PG 결제·쿠폰·JSON 계약·ArchUnit을 포함한다. API 전체 테스트를 실행했다는 뜻은 아니다.
- 별도 main/ranking MySQL과 실제 HTTP에서 130개 순위 후보를 3페이지로 읽었다. 첫 페이지 이후 새 pointer·상품 삭제를 커밋해도 진행 중 응답은 기존 snapshot과 같은 상품 RR view를 사용하고, 다음 요청은 새 snapshot을 읽었다.
- 각각의 소유 MySQL 컨테이너를 pause/unpause하여 랭킹 DB 장애와 상품 DB 장애의 503·같은 공개본 복구를 확인했다. 설정 OFF도 503이며 기존 JPA manager와 기본 JdbcTemplate은 main DB 연결을 그대로 사용했다.
- 첫 API 실행은 격리 테스트의 EntityScan에 기존 `BrandNameConverter`가 빠져 초기화 실패했다. 생산 변환기를 테스트 scan에 포함하여 수정했다. 오류 기대값이나 업무 규칙은 바꾸지 않았다.
- 양 앱 ArchUnit 통과. main/test Checkstyle 보고서 4개의 위반 합계 0. 최종 API 실행도 `BUILD SUCCESSFUL`, 종료 코드 0.
- 합계 **234개 테스트 통과**. 종료 후 `org.testcontainers=true` 라벨의 남은 컨테이너 0개와 `git diff --check` 통과를 확인했다. 사용자 DB·공유 Redis·기존 topic/group을 사용하거나 삭제하지 않았다. 커밋·푸시도 수행하지 않았다.

5단계의 실제 주문/PG/Outbox/Kafka/streamer부터 이어지는 검증과 6단계의 최종 전체 회귀·제출 정리는 남아 있다. 이번 두 앱의 검사 범위를 이전 주 전체 기능이나 전체 W9 채점 완료로 확대하지 않는다.
