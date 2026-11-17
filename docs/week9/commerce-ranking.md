# Week 9 — 주간 판매 랭킹 (2단계: 입력 고정과 점수 계산)

이 문서는 9주차 주간 랭킹 배치의 2단계 결정만 적는다. 완성 랭킹·공개 pointer·공개 Step·API·런처(revision 자동 계산)는 3·4단계에서 더한다.
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
  - 공개됨 = pointer(3단계).

| 테이블 | 열 | 키 |
| --- | --- | --- |
| `ranking_snapshot`(snapshot 정보) | snapshot_id, period_start(DATE), period_end(DATE, 제외), revision, frozen_at(UTC), input_count, input_checksum(CHAR 64) | PK snapshot_id, UNIQUE(period_start, revision) |
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
- batch 운영 코드는 streamer에 의존하지 않고 JDBC로 읽는다. 8주차 설계 27절 경계 메모(D75, batch가 대사를 위해 다른 앱의 테이블을 JDBC로 읽음)의 범위를 주간 랭킹의 `product_purchase_activity` 읽기까지 넓힌다.

## 6. 범위 밖과 다음 단계

- 완성 랭킹 테이블·공개 pointer·공개 Step·API·런처는 3·4단계다. 완성 랭킹·pointer는 api도 읽으므로 그때 스키마 한 벌 문제를 다시 정한다.
