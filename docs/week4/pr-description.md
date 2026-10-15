## 📌 Summary

4주차 — 상품 목록 조회의 병목을 측정으로 확인하고 줄였으며, 목록 한 경로에 Redis cache-aside를 붙여 변경·rollback·Redis 장애 뒤에도 같은 업무 결과를 돌려주게 했습니다.

- **병목**: N+1은 없었습니다(요청당 SQL 3개 고정). 비용은 집계·정렬에 있었습니다 — price_asc는 브랜드 상품 전체의 좋아요를 센 뒤 임시 테이블에서 정렬, likes_desc는 상품 1만·좋아요 9만 행을 모두 읽습니다(DB 30 ms).
- **인덱스·쿼리**: `(brand_id, deleted_at, price, id DESC)` + latest·price_asc는 상품 페이지를 먼저 고르고 그 상품의 좋아요만 셉니다. 상품 인덱스 읽기 100 → 20행, 좋아요 948 → 185행, 정렬·임시 테이블 제거, 응답 동일. likes_desc는 전체 정렬 의미를 지키려고 쿼리를 유지했습니다.
- **캐시**: 고객 목록 cache-aside (TTL 60초, 세대 키). likes_desc hit에서 p50 약 24 → 5 ms, hit 요청의 SQL은 PK 확인 1개.
- **진행 방식**: 순진한 구현 → 실패 장면 6건을 테스트로 깨뜨림 → 4번에 나눠 고침. 커밋 이력이 그 순서입니다.

## 💬 Review Points

1. **인덱스를 맞췄는데 정렬이 남았던 이유** — 실습 인덱스 `(brand_id, deleted_at, price, id)`를 그대로 넣자 `Using temporary; Using filesort`가 남았습니다. JOIN+GROUP BY의 임시 테이블과, 동률 정렬 `id DESC`(P-09)와 인덱스 방향 불일치 때문이었습니다 (`docs/week4/tech-writing.md`).
2. **무효화는 commit 뒤** — commit 전에 지우면 그 사이 조회가 옛 값을 다시 채웁니다. 쓰기는 사실만 이벤트로 발행하고 `@TransactionalEventListener(AFTER_COMMIT)`가 무효화합니다(브랜드·좋아요가 상품 캐시를 모르게). 무효화가 실패해도 이미 commit된 요청을 실패로 만들지 않고, TTL을 상한으로 둡니다.
3. **늦은 채움은 세대 키로** — 조회는 DB를 읽기 전에 세대를 읽고, 쓰기는 commit 뒤 세대를 올립니다. 늦게 저장된 옛 결과는 옛 세대 키에만 들어갑니다. 삭제(DEL) 방식으로는 막지 못하는 것을 테스트로 확인했습니다.
4. **비노출은 신선도로 허용하지 않음** — 가격·이름·좋아요 수는 무효화 실패 시 TTL 동안 옛 값을 허용하지만, 삭제된 상품은 hit여도 PK 조회로 확인합니다. hit에도 DB 조회 1개가 남는 비용을 받아들였습니다.
5. **Redis 장애** — 이 저장소는 `LettuceConnectionFactory`를 직접 만들어 Boot의 `spring.data.redis.timeout`이 적용되지 않습니다. `RedisConfig`에서 명령 200 ms·연결 200 ms·끊긴 동안 즉시 거절로 정했습니다. Redis 오류는 DB로 우회하고, DB 오류는 감추지 않습니다. 메모리 상한은 `maxmemory 128mb` + `volatile-lru`로 정했습니다 — 기본값(noeviction)이면 메모리가 찼을 때 무효화(INCR)까지 실패합니다. 버릴 TTL 키가 없으면 volatile-lru도 쓰기를 거절하는 것을 실험으로 확인했습니다.
6. **인덱스가 다른 쿼리의 잠금 순서를 바꾼 회귀** — AI 리뷰에서 새 인덱스 때문에 W3 브랜드 일괄 삭제의 `FOR UPDATE`가 가격순으로 잠그게 된 것을 찾았습니다(InnoDB는 읽는 순서대로 잠금). 인덱스로는 보장되지 않아(실측), 대상 id를 고른 뒤 PK 범위로 잠그게 고쳤습니다.
7. 봐 주셨으면 하는 것: 세대 키가 사라졌을 때 현재 시각(ms)으로 다시 시작하는 판단, 모든 쓰기(좋아요 포함)가 목록 전체를 무효화하는 비용.

## ✅ Checklist

**🔎 조회·인덱스**
- [x] 기존 모델·필터·정렬·페이지·삭제 조건을 유지한 실제 API를 개선했다
- [x] 같은 데이터에서 SQL 수·실행 계획·결과·지연을 전후 비교했다 (`docs/week4/plan.md` 2장)
- [x] 좋아요 수 조회와 likes_desc의 전체 정렬 의미를 확인했다

**🚄 캐시·변경**
- [x] 한 공통 조회의 miss/hit 결과가 맞고 비싼 원본 호출이 줄었다
- [x] 키·TTL·사용자별 정보와 모든 영향 쓰기 경로를 정리했다 (5장 표)
- [x] 정상 관리자 변경·삭제·rollback의 기존 계약을 지키고, 무효화 실패·늦은 채움에서 선택한 표시 신선도·복구 정책과 필수 보호 조건을 검증했다

**🧪 장애·회귀**
- [x] 실제 Redis 장애에서 DB 우회와 복구 후 재채움을 확인하고 실험 자원을 정리했다
- [x] 관련 회귀·lint·ArchUnit을 실행하고 설계의 비용과 한계를 기록했다 — `./gradlew :apps:commerce-api:test` 259건 실패 0, `./gradlew check -x test` 통과

**측정 환경·표본의 한계**: 로컬 단일 머신, MySQL 8.0.46, 상품 1만 개, curl 순차 요청(준비 30회 + 200회 × 2). 같은 측정이 실행마다 2~3배 흔들려 ms 차이는 결론에 쓰지 않았고, 읽은 행 수로 비교했습니다. 10만·100만 규모, 동시 요청, replica 복제 지연은 재지 못했습니다.

- TODO - 좋아요 수 비정규화 (정합성 조건과 W3 상품 행 잠금과의 경합을 먼저 확인)
- TODO - 같은 키의 동시 miss 합치기(stampede), TTL jitter

## 📎 References

- 설계 문서: `docs/week4/plan.md` (ADR-W4-01~05)
- 기술 글: `docs/week4/tech-writing.md` — 인덱스를 맞췄는데도 정렬이 남았던 이유
- 인덱스 SQL: `docs/week4/sql/product-indexes.sql`
- 측정 seed: `com.loopers.seed.CatalogSeeder` (`--spring.profiles.active=local,seed`)
