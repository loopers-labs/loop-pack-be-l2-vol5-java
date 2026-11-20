> **같은 입력의 계산을 이어 하는 것과, 새 입력으로 다시 계산하는 것은 다릅니다.**
> 

<aside>
🎯

**Summary**

</aside>

주문 확정 이벤트가 상품 구매 활동으로 쌓였습니다. 이제 이 사실을 모아 **주간 구매 수량 랭킹**을 제공합니다. 긴 집계가 중간에 멈춰도 같은 작업을 이어 가고, 사용자는 계산 중인 조각이 아니라 공개된 완성본을 읽도록 만듭니다.

- **배치 구조**: Job·Step·Reader·Processor·Writer의 책임을 나눕니다.
- **실행 식별**: 업무 작업과 한 번의 실행 시도를 구분합니다.
- **입력 고정**: 늦은 활동이 들어와도 재시작 입력을 몰래 바꾸지 않습니다.
- **커밋**: chunk 결과와 checkpoint를 같은 DB 경계로 연결합니다.
- **공개**: 집계 중간 결과와 고객에게 보이는 랭킹을 분리합니다.

<aside>
📌

**Keywords**

</aside>

- **업무 범위** — batch, business window, snapshot, late arrival
- **실행** — JobInstance, JobExecution, StepExecution, JobRepository
- **재시작** — chunk, checkpoint, ExecutionContext, idempotent writer
- **결과** — ranking, deterministic order, publication pointer, checksum

---

<aside>
🧠

**Learning**

</aside>

## 📊 실제 구매를 주간 랭킹으로 연결하기

이번 랭킹의 점수는 **해당 주에 확정된 상품 구매 수량의 합**입니다. 조회·좋아요·매출을 임의의 가중치로 섞지 않고 이미 쌓인 한 종류의 사실에서 시작합니다.

```
PG 확정 주문
  → OrderConfirmed Outbox
  → Kafka
  → 상품별 구매 활동
  → 주간 입력 snapshot
  → 상품별 구매 수량
  → 공개된 주간 랭킹 API
```

주문 이벤트 하나에 여러 상품이 있을 수 있습니다. 따라서 eventId만 보고 “이미 처리한 입력”이라고 두 번째 품목을 버리면 구매 수량이 빠집니다. 입력의 업무 단위는 **orderId·productId로 식별한 구매 활동**이며 W6 consumer가 이를 한 번만 저장합니다.

| 상품 | 확정 주문의 수량 | 주간 점수 |
| --- | --- | --- |
| 상품 7 | 주문 42의 2개 및 주문 43의 3개 | 5 |
| 상품 8 | 주문 42의 1개 | 1 |
| 상품 9 | 주문 44의 5개 | 5 |

점수 내림차순·동점이면 productId 오름차순으로 정하면 상품 7, 9, 8 순서입니다. 같은 입력에서 같은 결과를 내려면 **동점 규칙도 계산의 일부**여야 합니다.

## 🏭 실시간 처리와 Batch

> **Batch**는 정해진 업무 범위의 여러 항목을 묶어 처리하는 방식입니다. 반드시 밤에만 실행하거나 한 번에 모든 데이터를 메모리에 올려야 하는 것은 아닙니다.
> 

| 방식 | 장점 | 고려할 비용 |
| --- | --- | --- |
| 요청 때 집계 | 현재 데이터를 바로 계산 | 같은 비싼 계산 반복 |
| 이벤트마다 증분 반영 | 빠른 갱신 | 중복·보정·순서 처리 |
| 기간 배치 | 고정 범위 재계산·검증에 적합 | 결과 지연·재시작·완성본 공개 |

이번에는 주간 결과와 복구 가능한 계산에 집중합니다. Redis 실시간 랭킹이나 새 분석 이벤트를 먼저 만들어야 시작할 수 있는 과제가 아닙니다.

## 🧩 Spring Batch의 책임 나누기

| 구성 요소 | 맡는 것 | 이번 예 |
| --- | --- | --- |
| Job | 하나의 업무 흐름 | weeklyRanking |
| Step | 흐름의 한 단계 | 입력 고정, 점수 계산, 공개 |
| ItemReader | 다음 처리 항목 읽기 | snapshot의 상품 ID |
| ItemProcessor | 항목의 계산·변환 | 상품의 주간 수량 합 |
| ItemWriter | 결과 저장 | snapshot·상품별 절대 점수 |
| JobRepository | 실행·checkpoint 기록 | 실패·재시작 metadata |

**Tasklet**은 한 단계의 작업을 직접 표현할 때, **chunk-oriented step**은 항목을 반복해서 읽고 묶어 commit할 때 사용할 수 있습니다. 모든 Step에 Reader·Processor·Writer를 억지로 만들 필요는 없습니다.

입력 고정이나 작은 결과 공개는 tasklet, 상품별 계산은 chunk step으로 나눌 수 있습니다. 업무 데이터 변경에는 실제 DB 트랜잭션 관리자를 연결해야 합니다. 저장소 없는 demo의 실행 경계를 그대로 복사하지 않습니다.

## 🪪 같은 업무 작업과 새 실행 시도

> **JobInstance**는 job 이름과 식별 JobParameters로 구분하는 논리적 작업입니다. **JobExecution**은 그 작업을 실행한 한 번의 시도입니다.
> 

| 값 | 식별하는 것 | 예시 |
| --- | --- | --- |
| job name | 처리 흐름 | weeklyRanking |
| periodStart | 업무 기간 | 2026-08-17 |
| snapshotId | 고정 입력·계산 결과 묶음 | weekly-20260817-r1 |
| JobInstance ID | 위 식별값이 같은 작업 | instance 31 |
| JobExecution ID | 각 실행 시도 | 첫 실행 101, 재시작 102 |

```
weeklyRanking + periodStart + snapshotId
  └─ 같은 JobInstance
       ├─ 실행 101: FAILED
       └─ 실행 102: COMPLETED
```

실패할 때마다 `run.id`나 현재 시각을 식별 parameter에 추가하면 새 JobInstance가 됩니다. 성공 로그는 얻을 수 있어도 이전 checkpoint에서 이어 갔다는 증거가 아닙니다.

반대로 늦은 구매를 포함한 새 계산은 새 snapshot과 새 instance로 구분할 이유가 있습니다. **재시작은 입력 유지, 재계산은 입력 변경 가능성의 명시**라는 차이입니다.

## 🧊 시간 범위만 같으면 입력도 같을까

업무 구간이 `월요일 00:00, 다음 월요일 00:00)`로 같아도 실행 도중 늦은 이벤트가 도착하면 DB의 활동 행이 늘어날 수 있습니다.

| 시각 | 기록 |
| --- | --- |
| 실제 주문 확정 | confirmedAt |
| consumer의 활동 저장 | 도착·반영 시각 |
| 배치 입력 고정 | snapshot 생성 시점 |

지난주에 확정된 주문이 오늘 재전달돼 저장될 수 있습니다. 주간 소속은 confirmedAt으로 판단하고, 현재 snapshot에 들어갈 수 있는지는 **입력을 고정할 때 관찰한 DB 사실**로 판단합니다.

이번 방식은 일관된 DB 읽기에서 대상 활동을 snapshot 입력으로 복사하고, 입력 행·기간·건수·checksum을 함께 고정합니다. 실행 중 새 활동이 들어와도 이미 고정한 snapshot에 추가하지 않습니다.

단순히 `max(id)`를 저장했다고 commit 순서가 고정되는 것도 아닙니다. 번호를 먼저 할당받은 TX가 늦게 commit할 수 있기 때문입니다. 어떤 DB 읽기 시점을 고정했는지 설명해야 합니다.

### 🕰 늦은 사실은 어디로 가는가

지난주 구매를 이번 주 점수에 무조건 더하면 업무 기간이 바뀝니다. 원래 주의 새 revision snapshot을 만들어 다시 계산할 수 있습니다. 이때 기존 공개본은 새 완성본을 공개하기 전까지 유지합니다.

입력 고정은 “지난주 모든 사건이 이미 도착했다”는 증명이 아닙니다. 공개한 결과의 기준 시점과 늦은 데이터 처리 정책을 함께 밝혀야 합니다.

## 🧱 Chunk와 Checkpoint

> **Chunk**는 일정 수의 항목을 읽고 처리한 뒤 결과를 함께 commit하는 단위입니다. **Checkpoint**는 마지막으로 안전하게 저장한 처리 위치입니다.
> 

상품 ID 7·8·9를 읽고 chunk size를 2로 두면:

```
첫 chunk: 상품 7, 8 계산 → 점수 5, 1 저장 + 위치 2 commit
다음 chunk: 상품 9 처리 전에 실패 → 마지막 commit은 위치 2
같은 instance 재시작: 상품 9부터 처리 → 점수 5 저장
```

**ExecutionContext**는 reader 위치 같은 재시작 상태를 담는 저장 영역입니다. 실제 key 이름과 값은 reader 구현에 따라 다릅니다. 메모리 변수만 업데이트한 것을 영속 checkpoint로 보지 않습니다.

결과 writer와 Batch metadata가 서로 다른 TX에 있으면 “위치는 앞섰는데 결과는 없다”거나 그 반대가 생길 수 있습니다. 같은 DB를 사용하더라도 **실제 transaction manager·connection 참여**를 확인해야 합니다.

첫 chunk의 commit 전 실패라면 그 항목들을 다시 처리할 수 있습니다. reader 위치를 저장한다는 이유로 writer의 재실행 안전성을 생략하지 않습니다.

## ♻️ 안전하게 다시 쓰는 Writer

| 쓰기 방식 | 같은 항목 재처리 시 | 필요한 조건 |
| --- | --- | --- |
| `score += delta` | 두 번 증가할 수 있음 | 활동별 적용 중복 방지 |
| 고정 입력의 합을 `score = total`로 저장 | 같은 값으로 수렴 | 입력·계산식 불변, 상품별 유일 키 |
| 새 결과 묶음에 저장 후 공개 | 부분 결과를 숨길 수 있음 | 완성·공개 경계와 보존 |

이번에는 snapshot의 한 상품에 대한 **전체 구매 수량을 계산해 절대값으로 upsert**하는 방식을 사용할 수 있습니다. 이미 W6에서 구매 활동 중복을 제거했으므로 원시 Kafka eventId 처리 원장을 배치에 다시 만들 필요는 없습니다.

예를 들어 상품 7의 결과를 두 번 써도 `5 → 5`여야지 `5 → 10`이 되면 안 됩니다. `(snapshotId, productId)` 유일성으로 서로 다른 실행의 결과도 분리합니다.

잘못된 수량을 조용히 skip해도 배치는 종료될 수 있습니다. 이번 입력은 검증된 구매 사실이므로 잘못된 계약을 발견하면 실패를 드러내고 원인을 고칩니다. skip 개수를 늘려 정상으로 만드는 것이 복구는 아닙니다.

## 📖 Reader의 정렬과 재시작 상태

DB의 기본 반환 순서는 계약이 아닙니다. 페이지 경계가 바뀌면 어떤 상품을 이미 읽었는지 달라질 수 있습니다.

- snapshot 안의 상품 ID를 **유일하고 안정된 순서**로 읽습니다.
- paging reader를 쓰면 고유한 sort key를 설정합니다.
- step scope로 이번 snapshot parameter와 reader 상태를 연결합니다.
- 같은 이름의 영속 reader state를 사용하고 재시작 때 초기화하지 않습니다.
- 입력 freeze 단계가 끝난 뒤 실패한 계산 step을 이어 가며 입력을 다시 생성하지 않습니다.

메모리에 전체 입력을 읽는 방식은 작은 실험에는 단순하지만 큰 데이터의 메모리 비용을 만듭니다. 페이지 읽기를 선택하면 메모리 사용을 줄일 수 있는 대신 정렬·query·checkpoint를 검증해야 합니다.

## 🚪 부분 결과와 공개 결과를 분리하기

| 저장 영역 | 읽는 주체 | 목적 |
| --- | --- | --- |
| 고정 입력 | 배치 | 같은 계산 재현 |
| 점수 stage | 배치 | chunk별 중간 결과 |
| 완성 ranking snapshot | 공개 단계·API | 순위까지 계산된 결과 |
| 기간별 published pointer | API | 현재 보여 줄 snapshot 선택 |

배치가 stage 두 행을 저장했다고 API가 그 두 행만 보여 주면 중간 실패가 사용자 결과를 바꿉니다. 새 snapshot의 모든 점수·순위가 검증된 뒤 **완성 표시와 공개 pointer를 원자적으로 바꾸는 경계**가 필요합니다.

공개 TX가 rollback되면 이전 pointer가 유지돼야 합니다. 기존 공개 결과를 먼저 비우고 나중에 insert하는 두 TX로 나누지 않습니다.

### 🧭 업무 공개 완료와 Batch 상태

공개 TX가 commit된 직후 Batch의 최종 실행 상태 저장이 실패할 수도 있습니다. 이미 완성본이 공개됐는데 metadata만 보고 무조건 되돌리면 안 됩니다.

공개 단계는 같은 snapshot에 대해 재실행해도 같은 pointer로 수렴하게 합니다. “완성본의 공개가 commit됐는가”와 “JobExecution이 COMPLETED로 기록됐는가”를 함께 확인합니다. 다른 revision이 이미 공개됐다면 오래된 실행으로 덮어쓰지 않습니다.

## 🛍 고객에게 보여 주는 주간 랭킹

API는 공개 pointer가 가리키는 완성본만 읽고 기간·snapshotId·점수·순위를 함께 제공합니다. 아직 공개본이 없는 상태와 구매가 0건인 완성본을 구분합니다.

삭제·비노출 상품을 랭킹이 다시 노출해서는 안 됩니다. 현재 상품 공개 조건을 조회에 적용하고, 내부 계산 사실은 보존합니다. 점수·계산 당시 순위를 보존할지 고객용 표시 순위를 다시 붙일지는 API 계약에 명시합니다.

빈 주간 결과도 정상 계산일 수 있습니다. 이때 최신 상품이나 이전 주 결과를 말없이 같은 주 랭킹이라고 보여 주지 않습니다.

## 🔬 같은 결과라는 증거

| 비교 | 확인할 것 |
| --- | --- |
| 정상 실행 vs 실패·재시작 | 같은 고정 입력과 같은 상품별 점수·순위 |
| instance vs execution | 같은 instance, 다른 실행 ID |
| 첫 실패 시점 | 실제 첫 chunk 결과·영속 위치·기존 공개본 |
| 재시작 시작점 | 완료한 두 상품을 건너뛰고 다음 상품 |
| 재발행·중복 쓰기 | 구매 활동·점수 추가 없음 |
| 새 revision | 입력 변경이 명시되고 기존 결과를 몰래 수정하지 않음 |

Checksum은 정해진 직렬화 결과가 같은지 확인하는 도구입니다. 같은 잘못된 계산 두 개도 checksum은 같을 수 있으므로 손으로 계산한 기대 점수·순위와 함께 비교합니다. 실행 ID나 실행 시각을 결과 checksum에 넣으면 같은 업무 결과도 매번 달라집니다.

### 💭 선택을 설명하기

- 왜 상품 ID 하나가 이번 chunk의 한 item인가요?
- 새 `run.id`를 붙인 성공과 같은 instance의 재시작은 어떻게 다르나요?
- 고정 입력의 절대값 쓰기가 유효하려면 무엇이 바뀌지 않아야 하나요?
- 늦은 이벤트를 원래 주에 반영하면서 공개 결과를 지키려면 어떻게 해야 하나요?
- 공개본은 있는데 Batch가 FAILED라면 어떤 사실부터 확인해야 하나요?

---

<aside>
📚

**References**

</aside>

- [이전 기수 · Show Me The Ranking
- Spring Batch 5.2 · Domain Language
- Spring Batch 5.2 · Chunk-oriented Processing
- Spring Batch 5.2 · Database Readers
- Spring Batch 5.2 · JobRepository