# 요구사항 명세서 (W9): 같은 입력은 이어서 계산하고, 완성본만 공개한다

원문(`ref/week9/assignment.md`, `ref/week9/study.md`)을 구현과 테스트의 기준으로 쓸 수 있게 정리한 규칙표다. 테스트의 DisplayName 이 이 ID 를 쓴다. 결정의 근거와 뒤집을 조건은 `docs/week9/design.md` 에 있다.

| | |
|---|---|
| 원문 | Vol5 W9. W6·W7 의 구매 활동으로 주간 구매 수량 랭킹을 만든다. `commerce-batch` 에 주간 job 을 두고, 중단 뒤 같은 입력·같은 작업을 이어 가며, `commerce-api` 는 공개된 완성본만 조회한다 |
| 출처 | W2 의 표기(`원문`, `정함`, `선택`, `보안`, `범위`)에 W8 의 `실험`(측정으로 확인한 사실)을 쓴다 |

| 접두사 | 다루는 것 |
|---|---|
| `RNK` | 주간 결과의 계약: 기간, 소속, 점수, 순서 |
| `QRY` | 조회 API |
| `SNP` | 입력 고정(snapshot) |
| `JOB` | job·step 구성, 실행 식별, 재시작 |
| `CAL` | 점수 계산 step: reader, processor, writer |
| `PBL` | 완성본 공개 |
| `REV` | 늦은 구매와 새 revision |
| `EXP` | 실험. W8 번호를 이어 쓴다 |

## 같은 작업과 새 작업

| 구분 | 입력 | Batch 에서 | 규칙 |
|---|---|---|---|
| 재시작 | 고정한 입력 그대로 | 같은 JobInstance 의 새 JobExecution | `JOB-004` |
| 재계산 | 늦은 구매를 포함한 새 입력 | 새 snapshotId, 새 JobInstance | `REV-002` |

## RNK: 주간 결과

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `RNK-001` | 기간은 UTC 월요일 00:00 부터 다음 월요일 00:00 전까지다. 시작은 포함하고 끝은 제외한다. `periodStart` 가 월요일이 아니면 고정하지 않는다 | 원문 | `2026-08-17T00:00:00Z` 는 들어가고 `2026-08-16T23:59:59.999999Z`, `2026-08-24T00:00:00Z` 는 빠진다 |
| `RNK-002` | 활동이 속한 주는 그 활동의 `confirmedAt`(최초 주문 확정 시각)으로 정한다. 도착 시각이나 snapshot 시각으로 정하지 않는다 | 원문 | 늦게 도착한 지난주 구매는 지난주에 속한다 |
| `RNK-003` | 점수는 상품별 구매 `quantity` 합이고 정수다. W8 쿠폰 발급과 조회·좋아요는 섞지 않는다 | 원문 | |
| `RNK-004` | 순서는 점수 내림차순, 같으면 `productId` 오름차순이다. 순위는 이 순서의 1 부터의 위치이고 동점도 서로 다른 순위를 받는다 | 원문 | 7: 5점 1위, 9: 5점 2위, 8: 1점 3위 |
| `RNK-005` | 입력의 단위는 W6 consumer 가 남긴 `(orderId, productId)` 구매 활동이다. `eventId` 로 같은 주문의 두 번째 품목을 버리지 않는다 | 원문 | 주문 42 의 상품 7·8 이 모두 들어간다 |

## QRY: 조회 API

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `QRY-001` | `GET /api/v1/rankings/weekly?periodStart=2026-08-17&limit=10` | 원문 | |
| `QRY-002` | `periodStart` 는 `yyyy-MM-dd` 날짜이고 월요일이어야 한다. 아니면 400 이다. 검증은 요청 record 의 타입이 하고 도메인까지 보내지 않는다 | 원문, 정함(수단) | `2026-08-18` 은 400 |
| `QRY-003` | `limit` 은 양수이고 상한이 있다. 상한과 생략 시 기본값은 Q-4 | 원문, 선택 | `limit=0` 은 400 |
| `QRY-004` | 응답은 `status`, `periodStart`, `periodEnd`, `snapshotId`, 입력 고정 시점, `items[]`(순위, `productId`, 점수, 상품 표시 정보)다 | 원문, 정함(이름) | |
| `QRY-005` | 공개 pointer 가 없으면 `NOT_PUBLISHED` 와 빈 `items` 다. 다른 주나 계산 중인 stage 를 대신 돌려주지 않는다 | 원문 | 계산 중에도 `NOT_PUBLISHED` |
| `QRY-006` | 구매가 없던 주의 완성본은 `PUBLISHED`, 그 `snapshotId`, 빈 `items` 다 | 원문 | `QRY-005` 와 응답으로 구분된다 |
| `QRY-007` | 고객 공개 조건(`PRODUCT-005`, 삭제되지 않은 상품)에 맞지 않는 상품은 뺀다. 저장된 순위는 다시 매기지 않으므로 순위에 빈 자리가 생길 수 있다 | 원문 | 2위 상품이 삭제되면 1위, 3위 |
| `QRY-008` | 공개 조건을 먼저 적용하고 `limit` 을 적용한다. 상품 이름 같은 표시 정보는 기존 상품 조회 규칙으로 현재 값을 붙인다 | 원문 | |
| `QRY-009` | pointer 와 그 snapshot 의 ranking 을 한 query 로 읽는다. 읽는 도중 pointer 가 바뀌어도 두 snapshot 의 행이 섞이지 않는다 | 원문(예시), 선택 | |

## SNP: 입력 고정

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `SNP-001` | snapshot 정보는 `snapshotId`, `periodStart`, `revision`, 상태, 입력 고정 시점, 입력 건수, 입력 checksum 을 갖는다 | 원문 | |
| `SNP-002` | snapshot 입력은 `(snapshotId, orderId, productId)` 마다 한 행이고 `quantity`, `confirmedAt` 을 갖는다. 유일 키의 칸 순서는 `(snapshot_id, product_id, order_id)` 다 | 원문, 정함(칸 순서) | 같은 snapshot 에 같은 주문·상품 두 행이면 유일 키 위반 |
| `SNP-003` | 원본 활동을 한 번의 일관된 읽기로 가져와 snapshot 입력과 snapshot 정보를 한 TX 로 commit 한다. `max(id)` 로 고정했다고 보지 않는다 | 원문, 정함(수단: INSERT…SELECT 한 문장) | |
| `SNP-004` | 고정이 실패하면 반쪽 입력이 계산 대상이 되지 않는다 | 원문 | 고정 TX rollback 뒤 입력 0 행, snapshot 정보 없음 |
| `SNP-005` | 고정을 마친 입력은 재시작 때 다시 복사하거나 더하지 않는다 | 원문 | 재시작 전후 입력 행 수와 checksum 이 같다 |
| `SNP-006` | 이미 있는 `snapshotId` 의 기간이나 revision 이 다르면 덮지 않고 충돌로 실패한다. 같으면 원본을 다시 읽지 않고 아무것도 바꾸지 않은 채 step 을 완료한다. 입력은 비교하지 않는다. 고정 뒤 도착한 구매가 있어도 이미 고정한 입력이 그 snapshot 의 입력이다(`SNP-005`, `REV-001`) | 원문, 정함(같음의 기준: 기간과 revision) | 고정 뒤 늦은 구매가 들어와도 다시 고정하면 COMPLETED, 입력 3 행, checksum 그대로 |
| `SNP-007` | 입력 checksum 은 `(orderId, productId)` 순으로 정렬한 행마다 `orderId`, `productId`, `quantity`, `confirmedAt` 을 TAB 으로 잇고 LF 로 끝낸 바이트의 SHA-256 hex 다. 마지막 행도 LF 로 끝난다. `confirmedAt` 은 UTC ISO-8601, 소수 6 자리(`EVT-003` 과 같은 형식)다. 실행 ID·실행 시각은 넣지 않는다 | 원문, 정함(형식) | |
| `SNP-008` | `snapshotId` 는 `weekly-{yyyyMMdd}-r{revision}`(예: `weekly-20260817-r1`)이고 launch parameter 로 받는다. 기간과 revision 으로 만든 값과 다르면 고정하지 않는다 | 정함(Q-1) | `weekly-20260817-r2` 에 revision 1 이면 FAILED, 입력 0 행 |

## JOB: job 과 재시작

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `JOB-001` | `weeklyRanking` job 은 입력 고정(Tasklet) → 점수 계산(Chunk) → 완성본 공개(Tasklet) 세 step 이다 | 원문, 정함(Tasklet/Chunk) | |
| `JOB-002` | 식별 parameter 는 `periodStart`, `revision`, `snapshotId` 다. `run.id`, 현재 시각, 실패 스위치를 식별 parameter 로 넣지 않는다 | 원문 | |
| `JOB-003` | step 은 실제 DB transaction manager 를 쓴다. JobRepository 는 업무 테이블과 같은 DataSource 의 JDBC 저장소이고 결과 writer 와 같은 TX 에 참여한다. `ResourcelessTransactionManager`, 메모리 저장소를 쓰지 않는다 | 원문 | |
| `JOB-004` | 계산 step 이 실패하면 같은 식별 parameter 로 같은 JobInstance 의 새 JobExecution 을 시작한다. 완료한 step 은 다시 실행하지 않고, 계산은 마지막 commit 다음 상품부터 이어 간다 | 원문 | 상품 9 에서 잠금 대기 초과로 FAILED, 점수 7·8. 재실행은 같은 instance, 실행된 step 은 계산 step 하나, write 1, 점수 7·8·9 |
| `JOB-005` | step 은 단일 스레드로 실행한다 | 정함 | |
| `JOB-006` | `spring.batch.job.name` 이 실행할 job 을 고른다. 테스트는 자동 실행을 끄고 명시적으로 launch 한다 | 원문 | |
| `JOB-007` | 랭킹 계산 전에 §6 입력을 `probe-1` 로 고정하고 상품 7·8·9 를 chunk 2 로 읽는 연결 테스트를 둔다. 확인용 테이블 `batch_restart_probe` 는 테스트 전용이다 | 원문 | 첫 실행 FAILED, 확인 행 7·8, `read.count=2`. 재실행은 같은 instance, 새 execution, 확인 행 7·8·9 |

## CAL: 점수 계산

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `CAL-001` | reader 는 고정 입력의 서로 다른 `productId` 를 오름차순으로 읽는다. 상품 하나가 item 하나다. 다음 페이지는 `productId > 마지막 값` 으로 읽고, 마지막 값을 `snapshotProductReader.start.after` 로 저장한다 | 원문, 정함(keyset·key) | 첫 실패 뒤 저장값 `{product_id=8}` |
| `CAL-002` | processor 는 같은 snapshot 의 입력에서 그 상품의 `quantity` 합을 구한다. 현재 활동 테이블을 읽지 않는다 | 원문 | 7: 5, 8: 1, 9: 5 |
| `CAL-003` | writer 는 `(snapshotId, productId)` 에 절대 점수를 쓴다. 같은 입력을 다시 처리해도 `score = total` 로 수렴한다 | 원문 | 같은 상품을 두 번 써도 한 행, 점수 5 |
| `CAL-004` | 재시작 실험의 chunk 크기는 2 다. 처리량을 잰 값이 아니다 | 원문 | |
| `CAL-005` | 입력이 계약에 맞지 않으면 skip 하지 않고 step 을 실패시킨다 | 원문 | |

## PBL: 완성본 공개

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `PBL-001` | 계산 step 이 끝난 뒤 점수가 입력의 서로 다른 상품 수만큼 있는지 확인하고, `RNK-004` 순서로 순위를 매겨 완성 ranking 에 쓴다 | 원문 | 개수가 다르면 공개하지 않고 실패 |
| `PBL-002` | 완성 확인, ranking 쓰기, 공개 pointer 변경은 한 TX 다 | 원문 | |
| `PBL-003` | 계산 중, 계산 실패, 공개 TX rollback 에는 기존 pointer 와 API 결과가 그대로다 | 원문 | |
| `PBL-004` | 같은 snapshot 의 공개를 다시 해도 행이 늘거나 순위가 바뀌지 않는다 | 원문 | |
| `PBL-005` | 더 새 revision 이 이미 공개돼 있으면 pointer 를 되돌리지 않는다. 조건부 갱신으로 지킨다 | 원문 | |
| `PBL-006` | 공개 TX 가 commit 된 뒤 JobExecution 이 FAILED 로 남을 수 있다. 재실행한 공개 step 은 같은 결과로 수렴하고, 공개한 결과를 지우지 않는다 | 원문 | |
| `PBL-007` | 결과 checksum 은 순위 순으로 `productId`, `score`, `rank` 를 TAB 으로 잇고 LF 로 끝낸 바이트의 SHA-256 hex 다 | 원문, 정함(형식) | |
| `PBL-008` | 같은 기간의 계산은 직렬로 실행한다. 운영에서 한 번에 하나만 launch 한다(Q-3) | 원문, 범위(수단) | |
| `PBL-009` | 과거 활동, snapshot, 점수 행은 지우지 않는다 | 원문 | |

## REV: 늦은 구매와 revision

| ID | 규칙 | 출처 | 관찰값 |
|---|---|---|---|
| `REV-001` | 고정 뒤 도착한 같은 주의 구매는 그 snapshot 의 입력과 재시작 결과를 바꾸지 않는다 | 원문 | |
| `REV-002` | 늦은 구매를 반영하려면 그 주의 새 revision snapshot 을 명시적으로 만들어 다시 계산한다. revision 은 launch 할 때 주고, 그 기간에 이미 있는 가장 큰 revision 보다 커야 한다. 아니면 고정하지 않는다 | 원문, 정함(Q-2) | r2 가 있을 때 r1 을 launch 하면 FAILED, r1 입력 0 행 |
| `REV-003` | 새 revision 을 공개하기 전에는 이전 revision 이, 공개한 뒤에는 새 revision 이 일관되게 보인다 | 원문 | |
| `REV-004` | W7 의 같은 이벤트를 다시 발행해도 구매 활동과 점수가 늘지 않는다. 중복은 W6 의 DB 경계(`ACT-002`)가 막는다 | 원문 | |

## EXP: 실험

| ID | 재는 것 | 출처 | 결과 |
|---|---|---|---|
| `EXP-026` | §6 재시작. 입력 고정 → chunk 2 로 상품 7·8 commit → 상품 9 직전 실패 → FAILED, 같은 instance, 저장 위치, stage 2 행, 기존 공개본 유지 → 같은 parameter 로 재실행 → 9 만 계산하고 완료 | 원문 | `WeeklyRankingRestartTest`. 상품 9 의 점수 행을 다른 커넥션이 잡아 잠금 대기 초과로 FAILED, 저장 위치 `{product_id=8}`, stage 7·8. 재실행은 같은 instance, 실행된 step 은 계산 하나, write 1, stage 7·8·9. reader 의 `saveState` 를 끄면 저장 위치가 없어 실패한다. 기존 공개본 유지는 공개 step 을 만든 뒤 더한다 |
| `EXP-027` | 정상 한 번 실행(별도 snapshot)과 실패·재시작의 입력 checksum, 결과 checksum, 기대 점수·순위가 같다. 재시작 대상의 stage·metadata 를 지워 정상 실행으로 꾸미지 않는다 | 원문 | |
| `EXP-028` | §7 연결. PG 확정 → Outbox → Kafka → 구매 활동 → snapshot → job → API. 재발행, 늦은 구매, 새 revision, 경계 시각, 빈 주, 미공개, 삭제 상품. 한 실행으로 할지는 Q-6 | 원문 | |
| `EXP-029` | 입력 고정이 붙잡는 heap 이 입력 행 수에 비례하는가. heap 512MB, young 16MB, 행 10 만·20 만·40 만, 각 3 회. GC 직후 live heap 최댓값에서 실행 전 기준선을 뺀다. 40 만 행 중앙값이 32MB 미만이어야 한다. `./gradlew --offline :apps:commerce-batch:experimentTest` | 실험 | 설계 14 장. 결과 전체를 받으면 22 → 45 → 92MB(행당 약 240B), 행 단위로 받으면 0 → 0 → 0MB |
| `EXP-031` | 행 단위로 받는 checksum 읽기가 결과 전체를 받는 것보다 느린가. 기본 GC, heap 1GB, checksum 읽기만, 행 10 만·40 만, 웜업 3 회 뒤 두 방식을 번갈아 10 회. 중앙값 비율이 1.2 이하여야 한다. `./gradlew --offline :apps:commerce-batch:speedExperimentTest` | 실험 | 설계 14 장. 10 만 92 → 91ms(0.99), 40 만 392 → 376ms(0.96) |
| `EXP-030` | 실패한 입력 고정 TX 의 rollback 시간과 그동안 원본 테이블에 걸리는 metadata lock. 행 수에 따라 잰다 | 실험 | 측정 전. 200 만 행 고정이 실패한 뒤 rollback 이 1 분에 약 2 만 행씩 줄고 `truncate table purchase_activity` 가 기다리는 것을 한 번 보았다 |

## 물을 질문

| # | 질문 | 관련 규칙 |
|---|---|---|
| ~~Q-1~~ | ~~`snapshotId` 의 형식과 정하는 주체~~ `weekly-{yyyyMMdd}-r{revision}` 을 launch parameter 로 받고 job 이 기간·revision 과 맞는지 확인한다. 사람이 읽고 기간과 revision 을 바로 알 수 있다. **뒤집을 조건**: 한 기간에 revision 말고 다른 축(예: 점수 기준)으로 snapshot 을 나눠야 할 때 | `SNP-008` |
| ~~Q-2~~ | ~~revision 을 정하는 방법~~ launch 할 때 명시적으로 준다. 그 기간의 가장 큰 revision 보다 커야 하고 아니면 고정 step 이 실패한다. 원문이 "명시적으로 증가" 를 요구하고, 자동으로 매기면 같은 launch 를 두 번 했을 때 의도하지 않은 새 revision 이 생긴다. `(period_start, revision)` 유일 제약이 동시에 같은 revision 을 만드는 것을 막는다. **뒤집을 조건**: 스케줄러가 늦은 구매를 감지해 자동으로 재계산해야 할 때 | `REV-002` |
| ~~Q-3~~ | ~~같은 기간의 동시 계산을 직렬로 만드는 수단~~ 운영에서 한 기간에 한 번에 하나만 launch 한다고 범위로 둔다. 스케줄러와 자동 launch 가 범위 밖이라 동시에 launch 하는 주체가 없다. 근거는 설계 13 장. **뒤집을 조건**: 자동 launch 나 재시도가 생길 때 | `PBL-008` |
| Q-4 | `limit` 의 상한과 기본값. 원문은 "최대 100 등" 이다. 제안: 상한 100, 생략하면 10 | `QRY-003` |
| Q-5 | `periodStart` 를 생략하면 400 인가, 지난주로 보는가. 제안: 400 | `QRY-002` |
| Q-6 | §7 을 한 실행으로 하는가. W6·W7 은 앱별 테스트와 공유 계약으로 나눴다(W6 Q-4, W7 Q-3). 원문은 "한 연결 테스트" 다. 이번에는 commerce-api, streamer, batch 세 앱이다 | `EXP-028` |
| Q-7 | `purchase_activity` 에 `confirmed_at` 인덱스를 둘 것인가. streamer 의 스키마를 바꾼다 | `SNP-003` |
| ~~Q-8~~ | ~~commerce-batch 의 계층~~ `com.loopers.batch.job.ranking` 한 패키지에 package-private 으로 둔다. step 이 TX 경계이고 DB 없이 판단할 애그리거트가 없다. 근거는 설계 11 장. **뒤집을 조건**: 반품 차감 같은 도메인 판단이 생길 때 | `JOB-003` |

## 범위 밖

Redis 실시간 랭킹, 새 분석 이벤트(원문: 먼저 만들어야 하는 과제가 아니다. 쓰지 않는 근거는 설계 12 장). 조회·좋아요를 섞은 점수(원문). 운영 스케줄러와 job 자동 launch(정함. 이번에는 launch parameter 를 받아 실행하는 데까지).
