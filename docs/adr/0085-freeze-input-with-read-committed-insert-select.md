# ADR 0085: 입력 고정은 READ COMMITTED TX의 INSERT … SELECT 한 문장으로 하고, 원본에 confirmed_at 인덱스는 추가하지 않는다

Status: Accepted
Related: [9주차 설계 6절](../week9/design.md), [실험 1·1-2·1-3·1-4](../week9/experiments.md), [ADR 0081](0081-freeze-snapshot-once-in-revision-order.md)

## Context

입력 고정은 원본 `product_purchase_activity`에서 한 주의 구매 활동을 snapshot 입력으로 복사한다. 복사하는 동안에도 consumer는 원본에 구매를 저장한다. 요구는 세 가지다.

- 한 순간의 DB를 복사한다.
- consumer의 저장을 막지 않는다.
- 실패하면 반쪽 snapshot을 남기지 않는다.

실험 1·1-2·1-3·1-4에서 세 방식을 비교했다. 실험 1-3은 복사 문장이 도는 도중에 다른 연결의 INSERT를 넣어, 실험 1-4는 INSERT를 쉬지 않고 넣으면서, 실제 복사 중 consumer가 기다리는지를 쟀다.

| 방식 | 내용 |
| --- | --- |
| A | REPEATABLE READ TX에서 1,000건씩 나눠 읽고 앱에서 INSERT |
| B | REPEATABLE READ TX에서 `INSERT … SELECT` 한 문장 |
| C | READ COMMITTED TX에서 `INSERT … SELECT` 한 문장 |

구현은 C를 임시로 써 왔다.

## Decision

- 입력 고정 Step(`freezeSnapshotInput`)은 C로 확정한다.
  - 이 Step의 TX만 READ COMMITTED다.
  - 원본은 `INSERT … SELECT` 한 문장으로만 읽는다.
  - 건수·상품 수·checksum은 원본이 아니라 복사한 snapshot 입력에서 계산한다.
- 원본 테이블에 `confirmed_at` 인덱스를 추가하지 않는다. 원본은 commerce-streamer가 소유한다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| A (RR 나눠 읽기) | 한 순간(실험 1 가시성)·잠금 없음·rollback은 C와 같았다. 데이터가 앱을 오가 복사가 더 느렸다(10만 행 약 1.5~1.8초, 2차 실행 약 5초 2번 / 1만 행 약 0.25~0.38초). C를 쓸 수 없는 환경(예: 문장 단위 binlog를 강제하는 DB)에서는 대안으로 남는다 |
| B (RR 한 문장) | 원본을 읽으며 행마다 공유 잠금을 건다. consumer 대기는 실행 계획이 정했다. 전체 스캔이면 복사 도중 INSERT의 잠금 대기가 관찰되지 않았지만, `confirmed_at` 범위 스캔이면 스캔이 이미 지나간 범위의 늦은 구매가 commit까지 기다렸다(실험 1-3, 8만 행 대상에서 최대 약 0.35초). 경로는 통계에 따라 바뀌어 코드로 정할 수 없다. 또 잠그면서 읽는 읽기라 복사 도중 commit된 늦은 구매 중 스캔이 아직 닿지 않은 자리의 행이 snapshot에 들어갔다. snapshot이 어느 시점의 구매인지 한 순간으로 설명할 수 없다 |
| 원본에 `confirmed_at` 인덱스 추가 | 대상 비율이 작으면 범위 스캔으로 바뀌었다(실험 1-2). 하지만 9.2만 행에서 C 복사 시간 차이는 75 → 60ms로 작았다. 인덱스는 구매 저장마다 쓰기 비용을 더하고 소유 앱(streamer)을 바꿔야 한다. 원본이 크게 쌓여 복사 시간이 문제가 되면 다시 검토한다 |

## Consequences

- 원본이 쌓일수록 C의 `INSERT … SELECT`는 전체를 훑는다(인덱스 없음). 복사 시간이 누적량에 따라 늘 수 있다. 늘어나는 크기는 재지 않았다.
- 같은 TX에서 원본을 다시 읽으면 다른 시점을 볼 수 있다(READ COMMITTED). 그래서 원본을 다시 읽지 않는 규칙을 코드 주석과 설계에 둔다.
- 긴 복사 TX의 undo 보관 비용은 재지 않았다.
- 실험 1-4의 INSERT는 연결 두 개의 직렬 INSERT였다. 실제 consumer의 동시 연결 수와 유입량에서의 대기는 재지 않았다.
- C는 READ COMMITTED라 행 기반 binlog가 필요하다. MySQL 문서에 따르면 READ COMMITTED는 row-based binary logging만 지원하고, `binlog_format=MIXED`면 서버가 행 기반으로 기록한다. 기본값은 `ROW`다. `binlog_format`은 MySQL 8.0.34부터 deprecated이고, 행 기반 외의 형식은 앞으로 제거될 예정이다. 그래서 `STATEMENT`로 설정된 DB에서만 문제가 된다. 실험 DB(8.0.46)는 `ROW`였고, `STATEMENT`에서 실패하는지는 직접 확인하지 않았다.

## 검증

- 실험 1(10만 행 대상, 원본 12만 행)
  - C 복사 약 0.56~0.66초
  - commit 전 실패 시 snapshot 0행
- 실험 1-2(원본 9.2만 행, 대상 10.9%): C의 복사 문장 도중(문장 시작 33~62ms 뒤) commit된 지난주 늦은 구매가 3회 모두 snapshot에 들어가지 않았다.
- 실험 1-3(원본 10만 행 이하, 방식마다 3번)
  - 대상 83%(96,000행): C는 복사 문장 484~574ms 동안 원본 행 잠금·잠금 대기가 없었고 snapshot은 80,000행(도중 들어온 늦은 구매 없음)이었다. 같은 조건의 B(전체 스캔)는 PRIMARY `S` 약 4만~7만 개, 잠금 대기 관찰 안 됨, snapshot 80,005~80,006행이었다.
  - 통계 갱신 전 B(범위 스캔): 약 305ms 뒤 시작한 늦은 구매 INSERT가 309~352ms 기다려 commit 무렵 끝났다.
  - 대상 10.9%(92,000행): B는 3번 중 1번 전체 스캔, 2번 범위 스캔이었고 범위 스캔에서 늦은 구매가 최대 41ms 기다렸다. C는 3번 모두 잠금 대기가 없었고 snapshot은 10,000행이었다.
- 실험 1-4(INSERT를 쉬지 않고 넣으며 복사, `confirmed_at` 인덱스 없음, 방식마다 3번)
  - B는 복사 문장 도중 끝난 늦은 구매를 6번 모두 snapshot에 넣었고, 행 잠금 대기가 5~106회(합계 14~113ms) 생겼다.
  - C는 복사 전에 commit된 늦은 구매만 넣었고(시각 차이로 보이는 1행 제외), 행 잠금 대기는 6번 모두 0회였다.
  - 복사 중 INSERT p95는 두 방식 모두 복사 밖의 약 2배였다.
- `RankingJobRestartTest`: 입력 고정 복사 중 세션 격리 수준 `READ-COMMITTED`, 점수 계산 중 `REPEATABLE-READ`
