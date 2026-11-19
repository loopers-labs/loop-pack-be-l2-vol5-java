# 주간 랭킹 조회 설계

> 상태: 설계를 마쳤고 구현 전이다. 응답 계약과 규칙은 [주간 구매 랭킹](../../../docs/system-design/use-cases/weekly-ranking.md#조회-계약)을 따르고, 이 문서는 그 계약을 `commerce-api`에서 구현하는 방법을 다룬다. 검증은 [검증 기록](../../../docs/system-design/use-cases/weekly-ranking-verification.md#commerce-api-테스트)을 따른다.

`commerce-api`는 `commerce-batch`가 완성한 주간 랭킹을 읽기만 한다. 랭킹 테이블에 쓰지 않는다.

## 계층

| 위치 | 책임 |
|---|---|
| `interfaces.api.ranking` | `GET /api/v1/rankings/weekly`의 요청 모양 검사(누락, `yyyy-MM-dd` 형식)와 응답 DTO 변환 |
| `application.ranking` | `limit` 범위를 확인하고, 공개 회차 조회와 순위 조회를 차례로 조율해 응답에 쓸 값으로 묶는다 |
| `domain.ranking` | 조회 계약(포트)과 기간 값. `periodStart`가 월요일인지와 `periodEnd` 계산을 맡는다 |
| `infrastructure.ranking` | 조회 계약을 아래 두 쿼리 모양으로 구현한다. 랭킹 테이블은 읽기 전용 엔티티로 매핑하고 JPA로 읽는다. 계산 중 회차의 결과 checksum과 계산 순위는 비어 있으므로 매핑도 비울 수 있게 둔다. test 스키마를 이 매핑으로 만들기 때문이다 |

application은 infrastructure를 직접 참조하지 않는 기존 계층 규칙을 따른다. `ArchitectureTest`의 일부 규칙은 대상 패키지를 하나씩 나열하므로 `domain.ranking`, `application.ranking`을 대상에 추가한다.

## 입력 검증

| 입력 | 규칙 | 어기면 |
|---|---|---|
| `periodStart` | 필수, `yyyy-MM-dd`, 월요일 | `400 Bad Request` |
| `limit` | 선택, 기본 10, 1~100 | `400 Bad Request` |

누락과 형식 오류는 기존 `ApiControllerAdvice`가 `400`으로 바꾼다. `periodStart`는 `@DateTimeFormat(pattern = "yyyy-MM-dd")`로 엄격하게 받는다. `ISO.DATE`는 날짜 뒤의 시간대 표기(`2026-08-17Z`, `2026-08-17+09:00`)를 받고 버리기 때문이다. 월요일과 범위 검사는 `CoreException(BAD_REQUEST)`로 거절한다. 아직 오지 않은 주의 월요일은 오류가 아니라 `NOT_PUBLISHED`다.

## 조회

두 번 나눠 읽는다.

```sql
-- 1. 공개 회차: 그 기간에서 가장 최근에 완성된 회차. 행이 없으면 NOT_PUBLISHED
SELECT revision, calculated_at
FROM weekly_ranking_revisions
WHERE period_start = ? AND status = 'COMPLETED'
ORDER BY revision DESC
LIMIT 1;

-- 2. 그 회차의 순위. 행이 없어도 1의 회차 정보로 PUBLISHED와 빈 목록을 준다
SELECT s.rank_no, s.score, pr.id, pr.name, pr.price, b.id, b.name
FROM weekly_ranking_scores s
JOIN products pr ON pr.id = s.product_id AND pr.deleted_at IS NULL
JOIN brands b ON b.id = pr.brand_id
WHERE s.period_start = ? AND s.revision = ?
ORDER BY s.rank_no
LIMIT ?;
```

- 2번은 1번에서 찾은 `(기간, 회차)`로만 읽는다. 완성된 회차의 점수와 순위 행은 바뀌지도 지워지지도 않으므로, 두 조회 사이에 새 회차가 완성돼도 이 요청은 처음 찾은 회차를 끝까지 온전하게 보여 준다.
- 삭제된 상품을 거른 뒤 `limit`을 적용한다. 상품의 공개 조건(`deleted_at IS NULL`)과 표시 정보는 기존 고객 상품 조회와 같다.
- 계산 중(`CALCULATING`)이거나 닫힌(`CANCELLED`) 회차, 다른 기간의 결과를 읽지 않는다. 계산 중 회차의 순위는 비어 있거나 일부만 있다.
- 두 조회를 한 트랜잭션으로 묶지 않으므로 각 조회는 결과를 목록으로 다 받아 온다. 트랜잭션 밖의 공유 EntityManager는 쿼리 호출이 끝나면 닫혀, 스트림으로 받으면 나중에 읽을 수 없다.

## 응답 만들기

| 응답 값 | 어디서 |
|---|---|
| `status` | 1번 조회에 행이 있으면 `PUBLISHED`, 없으면 `NOT_PUBLISHED` |
| `periodStart`, `periodEnd` | 요청한 `periodStart`와 그 7일 뒤 |
| `revision` | 1번 조회. `NOT_PUBLISHED`면 `null` |
| `calculatedAt` | 1번 조회의 `calculated_at`. DB에 UTC로 저장된 값이므로 UTC `Instant`로 바꿔 준다. `NOT_PUBLISHED`면 `null` |
| `items` | 2번 조회. `NOT_PUBLISHED`면 빈 목록 |

랭킹 테이블의 시각은 배치가 UTC 값으로 쓴다. API는 JPA로 읽으므로 기존 `jdbc.time_zone: UTC` 설정대로 UTC로 해석된다. `JdbcTemplate`으로 읽게 바꾸면 이 설정이 적용되지 않으므로, 배치처럼 UTC `LocalDateTime`으로 읽어야 한다.
