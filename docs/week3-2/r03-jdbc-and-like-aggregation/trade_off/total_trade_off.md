# R03 트레이드오프

[요구사항](../requirement.md) · [구현 계획](../plan.md) · [전체 요구사항](../../total_requirement.md) · [주제 문서 템플릿](../../_template.md)

**굵게 = 채택** · ~~취소선 = 미채택~~ · `[검토 전]` / `[검토 중]` = 미확정 · `[보류]` = 필요할 때 재검토

모든 주제를 결정했다. 1~4·6은 구현 계획에 반영해 구현했고(1·3은 구현 후 조정, 3은 조정 후 철회), 집계(5·7~9)는 구현 계획 반영 전이다. 집계 결정으로 2번의 등록 SQL이 8번으로 대체된다.

## 선택 현황

1. [좋아요의 도메인 구조](01-like-aggregate.md): **독립 Like 애그리거트 + UseCase(`execute(LikeCommand.*)` 형식으로 조정)** / ~~User 애그리거트 + 사용자 행 잠금~~ / ~~User 애그리거트, 잠금 없음~~ / ~~DAO 유지~~
2. [좋아요 중복 등록·취소 처리](02-like-duplicate.md): **(등록 SQL은 8번으로 대체) Repository `save`의 HQL `insert … on conflict do nothing`, 취소는 JPQL delete 1회, 멱등 200 유지** / ~~사전 확인 + 경쟁 시 500~~ / ~~트랜잭션 밖에서 위반을 성공으로 변환~~ / ~~조회 후 삭제~~
3. [좋아요 등록 시 활성 상품 확인](03-like-product-check.md): **Service가 `findById` + `ensureActive`, 같은 트랜잭션, 잠금 없음(응답은 기존과 같은 404 `"Not Found"`)** / ~~`PRODUCT_NOT_FOUND`로 되돌리기(틀린 전제로 조정했다가 철회)~~ / ~~Repository 조건절~~ / ~~도메인 `LikePolicy`~~ / ~~`Like.create(userId, product)`~~ / ~~상품 공유 잠금~~
4. [조회 DAO의 QueryDSL 전환 방식](04-query-conversion.md): **`Projections.constructor` + 필요할 때만 Row, 주문 목록 2단계 유지, `QueryDsl*QueryDao`, 상품 쓰기 응답은 `findAdminProduct`로 다시 읽고 좋아요 수 DAO 제거, 기존 테스트 이름만 변경, 컨텍스트별 커밋** / ~~`@QueryProjection`~~ / ~~fetch join~~ / ~~엔티티 + batch fetch~~ / ~~Controller가 좋아요 수만 붙이기~~
5. [좋아요 수 집계 전략](05-like-count-strategy.md): **변경분만 반영, 반영 지연 허용(규모: 좋아요 수백만·인기 상품 편중)** / ~~전체 재집계 + 쿼리 개선~~ / ~~요청 시 즉시 ±1~~
6. [테스트 코드의 JdbcClient 허용](04-query-conversion.md#테스트-코드의-jdbcclient-허용-6번): **테스트의 데이터 준비·검증에서는 허용** / ~~테스트도 JPA·QueryDSL로~~ / ~~새 테스트만 금지~~
7. [좋아요 변경 추적 방식](07-like-change-tracking.md): **메모리 증감분(delta) 누적 + `AFTER_COMMIT` 이벤트, `ConcurrentHashMap` 컴포넌트** / ~~메모리 상품 id Set 재집계~~ / ~~변경 로그 테이블~~ / ~~카운트 행 dirty 플래그~~ / ~~워터마크(+ 소프트 삭제)~~ / ~~`@Cacheable` 캐싱~~ / ~~Redis 카운트~~
8. [좋아요 추가·삭제 감지](08-like-insert-detection.md): **native `INSERT IGNORE` + Repository `boolean` 반환, Service가 true일 때만 이벤트 발행** / ~~넣기 전 조회~~ / ~~`useAffectedRows=true`(추천 철회)~~ / ~~예외로 판정~~ / ~~중복 시 값 변경~~
9. [증감분 반영과 전체 재집계](09-like-count-flush.md): **5초 JDBC 배치 upsert(`GREATEST(0, …)`), 실패 시 되돌려 재시도, 정상 종료 flush 없음, 요청 받기 전 1회 전체 재집계, 유니크 키 `(product_id, user_id)`** / ~~60초 주기~~ / ~~잠금 없는 상시 전체 재집계~~ / ~~종료 직전 flush~~ / ~~`(product_id)` 인덱스 추가~~

주제를 시작할 때 템플릿을 복사해 `번호-주제.md` 형식으로 만들고 이 목록에 연결한다.

## 구현 계획으로 이어갈 것

1~3번은 [구현 계획](../plan.md)의 좋아요 등록·취소 절(커밋 1~5, 조정 커밋 6)로, 4·6번은 조회 전환 절로 구체화했다.
HQL on conflict는 MySQL에서 `insert … on duplicate key update user_id = product_likes.user_id`로 바뀌어 기대대로 동작했다(2번 남은 사항 해소).
5번의 결정에 따라 `LikeRepository.save`의 반환 형태가 바뀔 수 있다(1번 남은 사항).
