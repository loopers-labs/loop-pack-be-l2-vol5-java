# Week 9 — 주간 판매 랭킹

이 문서는 9주차 주간 랭킹의 결정을 적는다. 1~5절은 2단계(입력 고정과 점수 계산), 6~11절은 3단계(공개·확장·런처·고객 API)다.
나머지 설계(1~29절)는 `docs/week8/commerce-coupon.md`를 따른다.

- **[사용자 결정]**: 사용자가 정한 것(참모 결정 기록 번호). 바꾸려면 승인이 필요하다.
- **[참모 지시]**: D번호가 없는 참모 지시문·고정 규칙이다. 바꾸려면 참모 확인이 필요하다.
- **[구현 판단]**: 사용자 결정 범위 안에서 AI(PL·DEVELOPER)가 정한 내부 선택이다. 근거와 함께 적고 뒤집을 수 있다.

## 1. snapshot — 원본과 복사본

- **[사용자 결정] D148·D151** 주간 랭킹은 원본(`product_purchase_activity`, streamer가 쓰는 확정 구매 기록)을 직접 계산하지 않는다. 먼저 그 주의 구매를 snapshot 입력(`ranking_snapshot_input`)으로 복사해 고정하고, 계산은 복사본만 읽는다. 그래서 재시작·재계산이 같은 입력을 본다.
- snapshot 하나 = 한 주(period_start 월요일 00:00 UTC ~ 다음 월요일 00:00 UTC, 끝은 제외)의 한 revision. 이름은 `weekly-YYYYMMDD-rN`이다.
- **[사용자 결정] D151** snapshot 정보(`ranking_snapshot`)에는 상태 칸이 없다.
  - 고정됨 = 정보 행이 있다.
  - 계산됨 = 점수 stage 행 수가 입력의 서로 다른 상품 수와 같다(3단계 공개가 확인).
  - 공개됨 = pointer가 이 snapshot을 가리킨다(7절).
- **[사용자 결정] D159** 구간 끝은 저장하지 않고 period_start + 7일로 계산한다(같은 사실을 두 번 저장하지 않는다).

| 테이블 | 열 | 키 |
| --- | --- | --- |
| `ranking_snapshot`(snapshot 정보) | snapshot_id, period_start(DATE), revision, frozen_at(UTC), input_count, input_checksum(CHAR 64) | PK snapshot_id, UNIQUE(period_start, revision) |
| `ranking_snapshot_input`(복사본) | snapshot_id, order_id, product_id, quantity, confirmed_at | PK(snapshot_id, order_id, product_id) |
| `ranking_score_stage`(점수, D150) | snapshot_id, product_id, score(BIGINT) | PK(snapshot_id, product_id) |

- **[사용자 결정] D148** 같은 snapshotId 정보 행이 이미 있는데 기간·revision이 다르면 덮어쓰지 않고 실패한다. snapshot 입력을 UPDATE·DELETE하는 코드 경로는 없다(checksum 재확인·트리거는 범위 밖).

## 2. 한 TX 고정과 평범한 SELECT

- **[사용자 결정] D148·D149** Step 1 `freezeInput`(tasklet)은 step 트랜잭션 하나에서 순서대로 한다.
  1. 원본을 평범한 SELECT로 읽는다(`confirmed_at` 구간, `ORDER BY order_id, product_id`).
  2. 계약 위반 행(quantity ≤ 0 등)이 있으면 실패한다(건너뛰지 않는다).
  3. 앱에서 건수·checksum을 계산한다.
  4. snapshot 입력을 batch INSERT한다.
  5. snapshot 정보를 INSERT한다.
- 입력 행과 정보 행이 함께 commit되거나 함께 rollback된다. 반쪽 snapshot이 남지 않는다.
- **평범한 SELECT를 쓰는 이유:** `FOR UPDATE`나 `INSERT … SELECT`는 원본 행·범위에 잠금을 걸어, 고정하는 동안 streamer의 새 구매 기록 INSERT를 막는다. 기본 격리 수준(REPEATABLE READ)의 일관된 읽기로 그 시점의 행만 복사하고, 그 뒤에 들어온 늦은 구매는 이 snapshot에 넣지 않는다(새 revision으로 다시 고정할 때 들어간다). 격리 수준은 바꾸지 않는다.
- **[사용자 결정] D150** 점수 stage는 별도 테이블 `ranking_score_stage`다.
- **[참모 지시(지시문 2절)]** Step 2 `calculateWeeklyScores`(chunk)는 snapshot 입력의 상품을 읽어 상품별 quantity 합을 점수로 쓴다. writer는 절대값 upsert다(`score += …` 금지). 같은 chunk를 다시 써도 값이 같다.
- **[사용자 결정] D159** Step 2 reader는 `group by`로 상품별 합계까지 한 쿼리에서 읽는다(processor는 변환만). 재시작·절대값 쓰기는 그대로다.

## 3. checksum 규칙 [참모 지시: D번호 없는 고정 규칙]

- 입력 행을 `order_id, product_id` 오름차순으로 놓고, 행마다 `orderId\tproductId\tquantity\tconfirmedAt\n`(마지막 행에도 `\n`)을 이어 붙인다.
- confirmedAt은 UTC `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'`이다.
- UTF-8 바이트의 SHA-256 소문자 hex다. 빈 입력은 빈 문자열의 SHA-256(`e3b0c442…b855`)이다.

## 4. Job 실행 [사용자 결정 D152]

- job `weeklyRanking`, 식별 parameter `periodStart`(월요일 날짜)·`snapshotId`. incrementer는 없다. 같은 parameter로 다시 실행하면 같은 JobInstance의 재시작이다.
- revision은 3단계 런처가 job 시작 전에 정해 snapshotId에 담는다. 이번 단계 job은 snapshotId에서 revision을 읽는다.
- **[사용자 결정] D152** snapshotId의 날짜가 periodStart와 다르면 실패한다(이름·기간 어긋남 방지).
- **[참모 지시(w9s2-1)]** revision은 앞자리 0을 받지 않는다(`r01` 거절). 같은 revision을 가리키는 이름이 둘 있으면 안 된다. snapshotId는 정규식 `weekly-(\d{8})-r([1-9]\d*)`로 읽는다.
- **[구현 판단]** revision은 1 이상이다. periodStart가 월요일이 아니면 실패한다. 이 검사들과 날짜 불일치는 JobParametersValidator가 Job 시작 전에 거절한다(JobParametersInvalidException). Step 1은 같은 snapshotId가 다른 기간·revision으로 이미 고정돼 있으면 덮어쓰지 않고 실패한다(D148의 두 번째 방어).
- **[구현 판단]** frozen_at은 주입한 Clock 값이다. chunk 크기는 설정값이다.

## 5. 원본 테이블 정의는 한 벌 [사용자 결정 D153]

- `product_purchase_activity`의 정의는 streamer 엔티티(`ProductPurchaseActivityJpaEntity`) 한 벌뿐이다. batch는 SQL로 다시 정의하지 않는다.
- batch 테스트에서만 그 엔티티 클래스를 테스트 classpath에 빌려 와 JPA(ddl-auto)가 테이블을 만들게 한다.
- streamer 출력 전체가 아니라 그 클래스 파일 하나만 복사하는 이유: 출력 전체를 넣으면 streamer의 listener·handler·Kafka 설정이 batch 테스트 컨텍스트에 섞인다.
- batch 운영 코드는 streamer에 의존하지 않고 JDBC로 읽는다. 8주차 설계 27절 경계 메모(D75, batch가 대사를 위해 다른 앱의 테이블을 JDBC로 읽음)의 범위를 주간 랭킹의 `product_purchase_activity` 읽기까지 넓힌다.

## 6. 완성 랭킹과 공개 pointer [사용자 결정 D155·D156·D157]

| 테이블 | 열 | 키 |
| --- | --- | --- |
| `ranking_result`(완성 랭킹) | snapshot_id, calc_rank, product_id, score | PK(snapshot_id, calc_rank), UNIQUE(snapshot_id, product_id) |
| `ranking_publication`(공개 pointer) | period_start, snapshot_id | PK(period_start) |

- **[사용자 결정] D155** 두 테이블의 정의는 batch(주는 쪽)의 `sql/create-ranking-tables.sql` 한 벌이다. api 운영 코드는 JPA 엔티티 없이 SQL(JdbcTemplate)로 읽는다. api 테스트는 batch SQL 파일을 빌드 때 복사해 그대로 실행한다(내용을 다시 정의하지 않는다).
- **[사용자 결정] D156** 완성 랭킹은 상위 K = 150개까지 저장한다(API 최대 100의 1.5배). "몇 위까지 채웠나"는 새 칸 없이 그 snapshot의 최대 calc_rank로 판단한다.
- **[사용자 결정] D157** 저장은 계산 순위(calc_rank: 삭제 여부와 무관하게 점수로 매긴 순위), 고객 응답은 표시 순위(삭제 상품을 뺀 뒤 1부터)다. 계산 순위는 결과 checksum·이후 판매자용으로 남긴다.
- **[구현 판단]** pointer에는 revision 칸을 두지 않는다. 공개된 snapshot의 revision은 snapshot 정보와 조인해 읽는다(컬럼 최소화, 같은 사실 두 번 저장 금지).

## 7. 공개 Step [사용자 결정 D159 · 참모 지시(지시문 A-2)]

job `weeklyRanking`의 Step 3 `publishWeeklyRanking`(tasklet)은 step 트랜잭션 하나에서 순서대로 한다.

1. 완성 확인: 점수 stage 행 수가 snapshot 입력의 서로 다른 상품 수와 같아야 한다. 다르면 실패하고 아무것도 바꾸지 않는다.
2. 순위: stage를 `score DESC, product_id ASC`로 정렬해 상위 K까지 완성 랭킹에 INSERT한다. 이미 있는 순위는 건드리지 않고 최대 calc_rank 다음부터만 추가한다.
3. pointer: 그 주의 pointer가 없으면 INSERT한다. 있으면 **조건부 UPDATE**(지금 공개된 snapshot의 revision < 이 snapshot의 revision일 때만)다. 영향 0행이면 덮지 않는다(D159 오래된 실행 보호).
4. 결과 checksum: 그 snapshot의 전체 계산 순위(stage를 같은 정렬로)를 `productId\tscore\tcalcRank\n`으로 이어 붙인 UTF-8 바이트의 SHA-256 소문자 hex다. 새 칸 없이 계산 함수로 두고 로그·테스트에서 쓴다.

- 1~3이 같은 TX라 랭킹 INSERT 뒤 pointer 전에 실패하면 새 랭킹 행도 남지 않는다. 같은 Step이 다시 돌면 2(최대 순위 다음부터)·3(같은 snapshot이면 변화 없음)이 같은 결과로 수렴한다.
- 공개 TX commit 뒤 Spring Batch가 공개 Step 실행 기록을 마지막으로 저장하다 실패하면(w9t 테스트): pointer·완성 랭킹은 그 snapshot으로 남고 고객 쿼리도 그 snapshot을 읽는다. Batch 기록은 job UNKNOWN, 공개 Step STARTED로 남고, 같은 parameter로 다시 실행하면 Spring Batch가 `JobExecutionAlreadyRunningException`으로 거절한다. 공개 상태는 pointer가 기준이며 Batch 기록과 다를 수 있다. 런처는 pointer로 끝남을 판단해 새 revision으로 진행한다(9절, w9d).
- **[사용자 결정(w9t-1)] 전제:** 공개 Step은 한 TX이고 두 번 해도 결과가 같다(위 1~3). 그래서 pointer가 r(또는 그보다 새 revision)이면 r의 완성 랭킹은 모두 들어가 있다. 런처의 "끝남" 판단(9절)은 이 전제에 기댄다. 공개 Step에 외부 부수효과(알림 등)가 붙으면 pointer만으로 끝남을 판단할 수 없으므로 이 판단을 다시 본다.
- **[구현 판단]** 더 새 revision이 이미 공개돼 있으면 덮지 않고 Step은 COMPLETED로 끝나며 종료 코드 `NEWER_PUBLISHED`와 로그로 구분한다. 실패로 만들지 않는 이유: 이 snapshot의 계산·순위 저장은 끝났고 공개하지 않는 것이 의도한 결과다. 실패로 두면 재시작해도 같은 결과라 운영자가 끝없이 재시작하게 된다.
- **[구현 판단]** K 기본값은 설정 `commerce.ranking.size`(150)이고, Job parameter `rankingSize`(식별값)로 바꿀 수 있다.
- **[구현 판단]** "더 새 공개본 있음"은 조건부 UPDATE·INSERT가 실제로 pointer를 바꿨는지(영향 행 수)로 가른다. 바꾸지 않았을 때만 지금 commit된 pointer를 잠금 읽기(`FOR SHARE`)로 확인해 종료 코드·로그를 정한다. 평범한 SELECT는 REPEATABLE READ에서 TX 앞부분의 옛 값을 보므로, 공개 TX 도중 다른 TX가 더 새 revision을 공개해도 알아채지 못한다.
- 빈 pointer에 두 공개가 동시에 들어오면 둘 다 INSERT를 시도하고 진 쪽은 PK 충돌로 Step이 FAILED가 된다(그 TX의 완성 랭킹 행도 rollback). 진 쪽을 재시작하면 이번에는 행이 있어 조건부 UPDATE로 가므로 revision이 큰 쪽으로 수렴한다.

## 8. K 확장 [사용자 결정 D156]

- 확장은 추가만 한다. K를 늘리면 K+1위부터 행을 추가하고 1~K위·pointer는 바꾸지 않는다.
- **[구현 판단]** 확장은 작은 job `weeklyRankingExtend`(Step 하나, 7절과 같은 공개 tasklet)로 한다. 식별값은 `snapshotId`·`rankingSize`라 같은 snapshot도 K가 다르면 새 JobInstance다(COMPLETED 재실행 거절을 피한다). 실행: `--job.name=weeklyRankingExtend snapshotId=weekly-20260817-r1 rankingSize=200`.
  - 런처 옵션 대신 job을 고른 이유: 런처는 "새 revision 만들기·실패 재시작"만 하고, 확장은 이미 있는 snapshot 하나를 가리키므로 식별값이 다르다. 고정·계산을 다시 돌 필요도 없다.
- 확장도 7절 3의 조건부 UPDATE를 거친다. 같은 snapshot이 공개돼 있으면 변화가 없고, 더 새 공개본이 있으면 덮지 않는다.
- **[구현 판단] 전제:** 확장 대상은 `weeklyRanking` 실행이 COMPLETED된 snapshot이다. 확장 job은 그 실행 상태를 따로 검사하지 않는다. 고정되지 않았거나 점수 계산이 끝나지 않은 snapshot은 공개 tasklet의 완성 확인(7절 1)에서 실패한다. 공개 Step에서만 실패한 snapshot을 확장 job으로 돌리면 그 snapshot이 공개될 수 있으므로(조건부 UPDATE는 그대로 적용), 그런 snapshot은 확장 job이 아니라 런처 재시작으로 마무리한다.

## 9. 런처 [사용자 결정 D159 · 참모 지시(지시문 A-4)]

- 실행: `--job.name=weeklyRanking --spring.batch.job.enabled=false --ranking.period=2026-08-17 [--ranking.size=150]`. 공개 API는 없다.
- 그 기간에 실패·중단된 실행(마지막 실행이 COMPLETED가 아닌 JobInstance)이 있으면 그 parameter 그대로 재시작한다(번호를 새로 계산하지 않는다). 없으면 그 기간의 최대 revision + 1로 `weekly-YYYYMMDD-rN`을 만들어 실행한다.
- **[사용자 결정(w9t-1)] 끝남은 pointer로 판단한다(w9d).** 실패·중단된 실행이라도 그 주 pointer의 revision이 그 실행의 revision 이상이면 끝난 것으로 보고 건너뛴다. 남은 실행 중 최대 revision을 재시작하고, 없으면 최대 revision + 1로 새로 실행한다. 건너뛴 실행의 revision도 최대 계산에 들어간다. Batch 기록은 고치지 않는다(UNKNOWN·STARTED·FAILED 그대로 남는다).
  - 이유: 공개는 마지막 Step이고 한 TX라(7절 전제) pointer가 r이면 r의 랭킹은 모두 들어가 있다. pointer가 r보다 새 revision이면 r을 다시 공개해도 덮지 않는다(7절 3). 어느 쪽이든 r을 다시 돌릴 일이 없다. Batch 기록은 "다시 돌려야 하나"를 보는 작업 일지이고, 공개 여부의 기준은 pointer다.
  - **[구현 판단]** pointer revision은 `WeeklyRankingRepository`가 pointer → snapshot 정보 조인으로 읽는다(새 칸 없음, 평범한 SELECT, pointer가 없으면 0으로 본다). 런처는 Job 밖에서 한 번 읽는다.
  - 감수한 위험 1: 런처는 실행 목록을 먼저 읽고 pointer를 나중에 읽는다. 어떤 실행이 pointer를 읽기 전에 공개를 commit했는데 아직 STARTED로 마무리 기록 중이면, 런처는 그 실행을 건너뛰고 새 revision을 시작하므로 두 실행이 잠깐 겹친다. pointer는 조건부 교체(지금 revision < 새 revision, 7절 3)라 순서가 뒤집히지 않는다. pointer를 읽은 뒤에 commit된 공개는 옛 pointer로 판단되므로 겹치지 않고, 그 실행의 재시작 시도나 같은 r+1 JobInstance 실행이 Spring Batch에서 거절된다. 이 경쟁은 테스트로 재현하지 않았다.
  - 감수한 위험 2: Batch 기록은 틀린 채 남는다(공개까지 끝났는데 UNKNOWN·STARTED로 보인다). 모니터링에는 실패로 보이므로 공개 판단은 pointer로 한다.
- 같은 기간을 동시에 실행하면 같은 이름(같은 JobInstance)이 되어 하나만 진행하고 나머지는 거절된다.
- **[사용자 결정] D159** 늦은 구매(고정 뒤 들어온 같은 주 구매)는 사람이 런처로 다시 실행해 다음 revision(마지막 + 1)을 만든다. 자동 감지·정기 재실행·자동 확장은 이번 범위가 아니며 기록만 한다.
- **[사용자 결정] D159** 주 끝 뒤 고정 전에 기다릴 시간(주 끝 + 1h, [임시])은 바깥 실행기의 실행 시각으로 둔다(앱 설정 키는 두지 않는다). 주 경계가 UTC 월요일 00:00이므로 매주 월요일 01:00 UTC(= 10:00 KST)에 런처를 실행한다. 대사 job의 "실행 주기는 바깥 스케줄러 몫"(8주차 문서 27절)과 같은 원칙이다. 값의 근거는 평소 파이프라인(주문 확정 → Outbox → Kafka → 구매 활동) 지연이다.
  - 근거: 평소 지연은 relay 회차·Kafka·consumer를 합쳐 초 단위이고, 발행 누락은 대사 job(유예 `commerce.reconcile.grace` 5분) 뒤 재발행으로 들어오므로 그 여유를 넉넉히 덮는 값으로 둔다. 실측(C 측정·4단계)으로 다시 정한다.
  - **[구현 판단]** 런처는 실행 시각을 보지 않고 "주 끝 + 대기" 전 실행을 거절하지 않는다(실행 시각은 바깥 실행기 몫, w9r R1). 운영자는 주 끝 + 대기 뒤에 런처를 실행한다. 그 전에 실행해도 늦은 구매는 다음 revision(마지막 + 1)으로 다시 계산한다.
- **[구현 판단]** 런처는 `ranking.period`가 있을 때만 등록되는 `ApplicationRunner`다. Spring Boot 기본 job 실행기가 parameter 없이 같은 job을 함께 돌리지 않도록 `spring.batch.job.enabled=false`를 요구하고, 없으면 시작할 때 실패한다. 종료 코드는 마지막 실행이 COMPLETED면 0, 아니면 1이다.
- **[구현 판단]** 최대 revision과 실패한 실행은 Batch metadata(그 기간의 `weeklyRanking` JobInstance)에서 찾는다. Step 1에서 실패해 snapshot 정보 행이 없는 실행도 찾아야 하기 때문이다.
- **런처의 남은 우려(이번 범위에서 고치지 않음):**
  - `weeklyRanking`의 모든 JobInstance를 500개씩 훑는다. 이력이 쌓이면 실행 전 조회가 길어진다(C 측정에서 확인).
  - 공개까지 끝난 STARTED·UNKNOWN 실행(pointer revision ≥ 그 실행 revision)은 w9d에서 풀렸다. 런처가 건너뛰고 새 revision으로 진행한다.
  - 공개 전 단계에서 프로세스가 죽어 STARTED로 남은 실행(pointer revision < 그 실행 revision)은 여전히 같은 parameter 재시작을 시도하고, Spring Batch가 실행 중으로 보고 거절한다. 사람이 그 실행을 FAILED로 바꾼 뒤 다시 실행해야 한다.
  - ABANDONED·UNKNOWN도 pointer revision ≥ 그 실행 revision이면 건너뛴다(w9d). 공개 전 단계의 ABANDONED·UNKNOWN만 재시작 대상으로 잡힌다. ABANDONED는 재시작할 수 없어 거절되고 그 주는 더 진행하지 않는다(새 revision을 만들지 않는다). 사람이 판단한다.
  - 재시작할 때는 실패한 실행의 parameter를 그대로 쓰므로 이번 `--ranking.size`는 무시된다. K를 늘리려면 완료 뒤 확장 job을 쓴다.

## 10. 고객 API [사용자 결정 D157·D158]

`GET /api/v1/rankings/weekly?periodStart=YYYY-MM-DD&limit=N`

| 항목 | 계약 |
| --- | --- |
| 식별 | **[사용자 결정(w9s3-1)]** 비로그인 공개다. `X-USER-ID`를 요구하지 않고, 헤더가 와도 무시한다(다른 고객 API의 `X-USER-ID` 규칙은 그대로) |
| 입력 | periodStart 필수, 날짜 형식이 아니거나 월요일이 아니면 400. limit 기본 10, 1~100 밖이면 400 |
| 성공 200 | `status`(`PUBLISHED`/`NOT_PUBLISHED`), `periodStart`, `periodEnd`(계산, 제외), `snapshotId`, `frozenAt`, `items[]` |
| items | `rank`(표시 순위), `productId`, `name`, `price`, `brandId`, `brandName`, `score`, `soldOut` |
| 공개본 없음 | `NOT_PUBLISHED`, snapshotId·frozenAt null, 빈 items |
| 구매 0건 주 | `PUBLISHED` + 빈 items |

- **[사용자 결정] D157** 응답의 `rank`는 표시 순위만이다(삭제 상품을 뺀 뒤 1부터). 저장은 계산 순위, 고객 응답은 표시 순위다.
- **[사용자 결정] D158** 삭제 상품(`products.deleted`)만 뺀다(상품 목록과 같은 공개 규칙). 품절은 빼지 않고 `soldOut`(재고 0)으로 표시한다. 재고에서 읽으며 DB 칸을 더하지 않는다.
- **[사용자 결정] D158** 배치는 삭제를 보지 않는다(구매 사실만). API가 products와 조인해 거른다. 과거 활동·점수·랭킹 행은 지우지 않는다.
- 응답은 공개된 snapshot 기준 시점이다(`frozenAt`까지 고정된 구매). 고정 뒤 들어온 늦은 구매는 다음 revision이 공개될 때 보인다.
- **[구현 판단]** 완성 랭킹을 앞에 두고 pointer 서브쿼리가 가리키는 snapshot의 행을 PK(snapshot_id, calc_rank) 순서로 읽으며 products(deleted = false)·brands·snapshot 정보를 조인해 거른 뒤 limit을 건다. 정렬 순서가 PK와 같아 nested loop 계획이면 limit개를 채운 곳에서 멈춘다. 0행이면 pointer와 snapshot 정보를 따로 읽어 "공개본 없음 / 남은 행 없음"을 가른다. 두 읽기는 한 읽기 전용 TX(REPEATABLE READ의 같은 읽기 시점)라 그 사이 새 공개본이 commit돼도 섞이지 않는다. 표시 순위는 응답을 만들 때 1부터 매긴다.
- **[구현 판단]** 완성 랭킹은 K(150)까지만 있으므로 삭제 상품이 많으면 limit보다 적게 나올 수 있다. 이때 K 확장(8절)으로 채운다.

## 11. 범위 밖과 규모가 커질 때

- **로컬 DB 안내:** 3단계에서 `ranking_snapshot.period_end`를 지웠다. SQL 파일은 `CREATE TABLE IF NOT EXISTS`라 이전 정의가 남은 로컬 DB는 바뀌지 않아 INSERT가 실패한다. 로컬은 `ranking_snapshot`(필요하면 랭킹 테이블 전체)을 지우고 다시 만든다. 테스트 컨테이너는 매번 새로 만들어 영향이 없다.

- 자동 감지(늦은 구매)·정기 실행·자동 K 확장·스케줄러는 범위 밖이다(9절).
- **[참모 지시(지시문 A-6 문서 요구)]** Step 1 고정은 지금 한 TX다. 규모가 커지면 "같은 TX 안에서 페이징 → 여러 TX + snapshot 정보의 고정 중 상태"로 간다. 그때 상태 칸을 더하는 결정을 다시 한다.
- 4단계에서 실제 이벤트(주문 확정 → 구매 활동)부터 API까지 한 흐름으로 확인한다.
