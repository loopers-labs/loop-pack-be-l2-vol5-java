# 상품 목록 인덱스 전후 실행 계획

[진행 결과](../result.md) · [before 수집 기록](explain-before/capture.json) · [after 수집 기록](explain-after/capture.json) · [적용 SQL](add-product-list-indexes.sql) · [사후 검증](verification-after.json)

2026-10-16 같은 100만 상품 DB에서 기존 보조 인덱스 4개와 추가 후 6개를 비교했다.
세 요청의 실제 목록·COUNT SQL 6개를 같은 순서로 사용했다. 쿼리마다 준비 실행 1회,
`EXPLAIN` 1회, `EXPLAIN ANALYZE` 1회를 순차 수집했고 별도 `ANALYZE TABLE`은 실행하지 않았다.
실제 SQL·JOIN·삭제 조건·조회 열·`page=0,size=20`·동점 `id DESC`는 유지했다.
문서용 ANALYZE 원문은 출력 끝의 빈 줄만 정리했다. 정리 전 출력은 로컬 `build/benchmark/index-raw/`에 보존했다.

## 적용·환경 보존

[DDL 원문](index-application.txt)의 단일 ALTER는 두 인덱스를 `ALGORITHM=INPLACE, LOCK=NONE`으로 추가했다.
MySQL 출력의 **Query 시간은 8.26초**, [실행 기록](index-application.json)의 **벽시계 경과는 8.62초**다.
종료 코드는 0, 경고는 0개이며 다른 알고리즘·잠금 방식으로 우회하지 않았다.
이 시간은 인덱스 생성 시간으로, 상품 SELECT나 HTTP 응답 시간이 아니다.

- 상품 1,000,000개·활성 950,400개, 좋아요 관계/집계 합계 2,597,980개, 가격 합계 50,950,000,000을 전후 유지했다.
- 기존 4개 정의를 보존하고 `idx_products_deleted_created`, `idx_products_deleted_price`만 추가했다.
- 버퍼 풀 134,217,728 bytes(128MiB)와 주요 전역 설정 11개가 동일하다. [전](explain-before/snapshot.json)·[후](explain-after/snapshot.json)
- API PID **34084**, `commerce-api-98da9d2.jar`와 JAR SHA-256을 유지했다. API 재시작·seed 재실행은 하지 않았다.
- 사용자 GUI 파일 SHA-256도 `F51DC3363E79FB6EEB15846ACA55C9028CB58F207C8F89CC973E2CE3B3FC89C0`으로 동일하다.
- 적용 후 세 HTTP 응답은 전체 기대값과 일치했다. [응답 확인](http-after/README.md)

## 6개 SQL의 실제 비용

아래 시간의 단위는 모두 **밀리초(ms)** 이며, `EXPLAIN ANALYZE` 최상위 노드의 `actual time=a..b` 중 **b**를 사용했다.
예상 rows는 일반 EXPLAIN의 상품 접근 행, 실제 rows·loops는 상품을 읽는 노드의 값이다.
COUNT의 최상위 출력은 1행이지만 그 아래에서 많은 상품을 읽는다.

| SQL·원문 | 상품 예상 rows 전→후 | 상품 실제 rows 전→후 | 상품 loops 전→후 | 최상위 종료 시간 ms 전→후 |
|---|---:|---:|---:|---:|
| latest 목록 [전](explain-before/latest-list-analyze.txt)·[후](explain-after/latest-list-analyze.txt) | 496,785 → 496,785 | 950,400 → 20 | 1 → 1 | 13,275 → 0.0976 |
| latest COUNT [전](explain-before/latest-count-analyze.txt)·[후](explain-after/latest-count-analyze.txt) | 496,785 → 496,785 | 950,400 → 950,400 | 1 → 1 | 213 → 155 |
| price 목록 [전](explain-before/price-list-analyze.txt)·[후](explain-after/price-list-analyze.txt) | 496,785 → 496,785 | 950,400 → 20 | 1 → 1 | 11,241 → 0.232 |
| price COUNT [전](explain-before/price-count-analyze.txt)·[후](explain-after/price-count-analyze.txt) | 496,785 → 496,785 | 950,400 → 950,400 | 1 → 1 | 221 → 178 |
| brand7 목록 [전](explain-before/brand7-list-analyze.txt)·[후](explain-after/brand7-list-analyze.txt) | 960 → 960 | 20 → 20 | 1 → 1 | 0.424 → 0.105 |
| brand7 COUNT [전](explain-before/brand7-count-analyze.txt)·[후](explain-after/brand7-count-analyze.txt) | 960 → 960 | 960 → 960 | 1 → 1 | 0.399 → 0.217 |

따라서 전체 최신순 목록은 **13.275초 → 0.0976ms**, 전체 가격순 목록은 **11.241초 → 0.232ms**다.
후자의 0.232를 초나 232ms로 읽으면 안 된다. latest COUNT와 price COUNT는 같은 SQL이지만
실제 수집 시점이 다른 두 실행이므로 표에서 분리했다.

## 선택 key·정렬·JOIN 해석

- 전체 최신순: `idx_products_deleted_brand_created`의 `deleted` 접두부로 읽는 `ref`에서,
  새 `idx_products_deleted_created`를 쓰는 `range`로 바뀌었다.
  [EXPLAIN 전](explain-before/latest-list-explain.txt)·[후](explain-after/latest-list-explain.txt)
- 전체 가격순: 같은 기존 `idx_products_deleted_brand_created(ref)`에서
  새 `idx_products_deleted_price(ref)`로 바뀌었다.
  [EXPLAIN 전](explain-before/price-list-explain.txt)·[후](explain-after/price-list-explain.txt)
- 두 전체 목록의 before에는 `Using filesort`와 명시적 Sort 노드가 있고 after에는 없다.
  before Sort의 출력 rows=20은 원본 20행만 읽었다는 뜻이 아니다. 아래 상품 노드가 실제 950,400행을 읽었다.
  after 상품 노드는 이번 첫 페이지에서 20행만 읽었다. 브랜드 PK 조회는 전후 모두 rows=1, loops=20이다.
- 무필터 COUNT 두 실행은 전후 모두 `idx_products_deleted_brand_created(ref)`의 covering 접근이다.
  `Using index`여도 실제 950,400행을 읽는 비용이 남는다. 이번 설정은 COUNT TTL=0s이므로 요청마다 이 SQL이 필요하다.
- brand7 목록은 전후 모두 `idx_products_deleted_brand_price(ref)`를 쓰며 filesort가 없다.
  COUNT도 기존 `idx_products_deleted_brand_created(ref)`에서 960행을 읽는다.
  새 인덱스에 따른 접근 경로 변경이 없으므로 단발 시간 차이를 새 인덱스의 효과로 단정하지 않는다.

brand7의 일반 EXPLAIN에는 브랜드가 PRIMARY의 `const` 1행으로 표시되지만,
ANALYZE 트리에는 반복 JOIN 노드 없이 상품 접근만 보인다. 실제 SQL에서 JOIN을 삭제한 것은 아니다.
`brand_id=7`과 PK 동등 조건으로 브랜드가 상수 테이블로 처리된 결과로 해석할 수 있으나,
정확한 옵티마이저 변환 과정까지 추적한 것은 아니다.

## 결론의 범위

이번 단발 계획에서는 전체 최신순·가격순의 대량 읽기와 추가 정렬이 사라진 것을 확인했다.
그러나 COUNT의 950,400행 읽기는 그대로이고, 목록 시간만으로 전체 HTTP가 0.1ms 수준이라고 주장할 수 없다.
각 노드 시간을 더해 전체 실행 시간으로 계산하지 않으며 수집 기록의 CLI 벽시계와도 구분한다.
준비 실행·고정 순서·인덱스 생성 자체의 캐시/통계 영향이 있고 지연 분포는 측정하지 않았다.

[이전 CLI의 최신순 10명 timeout 33.33%](jmeter-before-first/README.md)는 변경 전 부분 기준선으로 유지한다.
이번 적용 뒤 새 JMeter 부하는 실행하지 않았으므로 timeout 해결·HTTP 개선 배수·처리량 향상은 아직 검증하지 않았다.
사용자의 후속 JMeter 결과는 이 단발 SQL 계획과 별도 자료로 비교한다.
