# ADR 0095: chunk 크기가 바뀐 재시작은 복원하지 않고 거부한다

Status: Accepted
Extends: [ADR 0089](0089-read-product-ids-and-insert-weekly-scores.md) (저장한 `page.size`의 쓰임을 정한다)
Related: [9주차 설계 6절](../week9/design.md)

## Context

점수 Reader(`JdbcPagingItemReader`)는 재시작할 때 저장된 마지막 키(`start.after`)로 다음 페이지를 읽고, 읽은 수(`read.count`)를 페이지 크기로 나눈 나머지만큼 그 페이지 안에서 건너뛴다. 페이지 크기는 chunk 크기 설정과 같다. 같은 크기로 재시작하면 커밋 경계가 페이지 경계라 나머지가 0이다. 설정을 바꾼 채 재시작하면 나머지가 0이 아닐 수 있고, 그만큼 상품을 건너뛰어 점수가 빠진다.

Reader에는 페이지 크기가 달라졌을 때 고정 입력을 `OFFSET`으로 다시 읽어 키를 계산하는 복원 코드와, 배포된 적 없는 이전 Cursor Reader의 checkpoint를 옮기는 코드가 있었다.

## Decision

- 저장된 `page.size`가 현재 chunk 크기와 다르면 Reader `open`에서 실패한다. 메시지로 원래 chunk 크기로 다시 실행하라고 알린다.
- 키 복원과 이전 Cursor 호환 코드는 지운다.

## Alternatives

| 대안 | 판단 |
| --- | --- |
| 달라진 크기에 맞춰 키를 다시 계산 | 재시작 위치를 정하는 코드가 하나 더 생기고 `OFFSET` 조회가 필요하다. 재시작 중에 chunk 크기를 바꿀 이유가 없다 |
| 검사 없이 재시작 | 읽은 수가 새 크기의 배수가 아니면 상품이 조용히 빠진다 |

## Consequences

- chunk 크기를 바꾸려면 진행 중인 JobInstance를 원래 크기로 끝낸 뒤 바꾼다.
- 실패한 재실행은 Reader 위치를 바꾸지 않으므로, 원래 크기로 다시 실행하면 이어서 끝난다.

## 검증

`RankingJobRestartTest.rejectsRestartWithDifferentChunkSize`: chunk 2로 상품 7·8을 커밋하고 실패한 뒤 chunk 3 Reader로 재실행하자 읽기 전에 FAILED였고 stage는 7·8 그대로였다. 원래 Job으로 재실행하자 상품 9만 읽고 순위 7:5:1, 9:5:2, 8:1:3으로 끝났다.
