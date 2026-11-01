# 기존 기능 실행 기록

기존 절을 기능별 기준 문서로 이동했다. 절 번호·계약·승인/검증 상태는 보존하며, 이동 자체는 새 설계 승인이나 실행 검증을 뜻하지 않는다.

아래는 과거 실행 기록이다. 이번 문서 이동에서 앱 테스트·실험을 다시 수행한 결과가 아니다. 검증 기준은 [브랜드 삭제](../features/catalog.md#417-검증-계획)·[상품 캐시](../features/product-cache.md#446-검증상태관련-기록)에 있다.

## 브랜드 일괄 삭제 — 2026-10-07 실행 기록

2026-10-07 완료 근거: 정상 bulk·보존·영속성·인덱스 확인에 이어, `BrandRemovalTransactionIntegrationTest`의 전용 `BrandRepository` spy에서 현재 트랜잭션의 실제 DB 조회로 삭제 상품 총 3건을 확인한 뒤 RuntimeException을 주입했다. 준비 데이터는 기존 삭제 1건과 bulk 대상 미삭제 2건이었다. 검증용 native SELECT 직전의 AUTO flush로 Brand UPDATE도 전송됐으며, 이는 테스트 조회가 유발한 동작이다. Facade 종료 후 새 조회에서 Brand·Product의 삭제/수정 시각 등 상태와 과거 주문·Like가 작업 전과 같음을 확인했다. 삭제 후 회귀에서는 사용 제한·과거 주문 보존·DRAFT 확정 거절과 HTTP·접근 경계를 확인했다. 브랜드 인계 시점의 `cleanTest check` 결과는 326건·실패/오류/skip 0, Checkstyle·ArchUnit 통과다. 이후 주문·동시성과 정리 보호 보완을 포함한 최종 결과는 4.3.5에 둔다.

## 상품 조회·캐시 — 2026-10-15~16 실행 기록

2026-10-15 앱 구현·변경/실패/복구·회귀 검증을 완료했다. 실습 전체의 성능 비교까지 완료한 것은 아니다. 목록은 진단 seed에서 I/IGNORE 계획과 상위 ID·순서를 비교했지만, Quest 2번의 동일 자료·입력 전후 반복 지연과 ID·가격·좋아요 수·페이지 정보 전체 비교가 남아 있다. 2026-10-16 D0에서 실제 API로 변경 전 B와 현재 인덱스 구성을 각 90회 비교해 이를 채웠다. 응답 180개가 같았고 목록 SQL rows_examined는 20,020,020에서 176으로, HTTP p50은 1,053.8ms에서 18.4ms로 줄었다. 정확한 total COUNT는 변경 뒤에도 20,000항목을 읽는다(경로만 `(brand_id, deleted_at)` covering). 같은 구성에서 최신순·좋아요순도 기대 응답과 일치했다. SQL 실험은 글을 준비하는 과정에서 수행하며 이 제출 근거도 함께 보완할 수 있다. E0–E9 전체를 별도 선행 완료 단계로 강제하지 않는다. 최종 `cleanTest check`는 377건·fail/error/skip 0이며 Checkstyle·ArchUnit도 통과했다. 기존 353건에 캐시 24건이 더해졌다. 실제 앱·로컬 MySQL/Redis의 DB-only/miss/hit 결과는 같았다. hit는 완성 상세 문장 대신 확인 SQL을 실행하므로 전체 SQL 수는 같다. rows_examined도 두 SQL 모두 소량 자료에서 6행으로 같았다. 두 SQL은 같은 PK 조회·브랜드 조인·좋아요 COUNT를 수행하므로 2026-10-16 Quest의 hit 비싼 원본 호출 감소 조건은 미충족으로 판정했고, 좋아요 DB 조회·권위 확인 정책은 유지한다([캐시 검증 01 판정](../../week4/cache-verification/01-cache-performance.md#판정--quest-hit-조건-미충족-2026-10-16-현-정책-유지)). 실제 redis-master 중단 중에는 요청당 약 0.42초 대기 뒤 DB 값으로 응답했다. 재시작 뒤에는 AOF로 복원된 옛 기본정보가 실제 만료까지 보인 뒤 DB 값으로 재채움됐다. 근거는 [상품 상세 캐시 구현 검증](../../week4/cache-verification/README.md)에 있다. SQL-only 라이팅 실험은 미완료다.
