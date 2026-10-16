# 가격순 캐시: 기본부터 읽기

가격순 캐시의 최종 판단·저장소 선택·확인한 비용은 [design.md의 가격순 캐시 결론](design.md#price-cache-choice)에 모았다. 이 문서는 GET·SET·DEL과 변경 후 재조회 흐름을 읽는 안내다.

인기순 ID 캐시는 [design.md의 순위 재사용](design.md#popular-cache-choice)에서 별도로 다룬다. 이 문서의 캐시 값·변경 대상·측정은 가격순 범위다.

가격순과 인기순 목록에서 순서·이름·가격·좋아요 수·하트가 각각 얼마나 늦게 반영될 수 있는지는 [design.md의 캐시별 최신성 표](design.md#cache-freshness)에서 비교한다.

캐시는 DB 조회 결과의 복사본이다. 같은 목록을 다시 요청할 때 DB에서 상품 페이지를 고르는 일을 줄이려고 사용한다. 이 문서는 현재 Redis 학습 구현의 동작을 설명한다. 대상은 인덱스 실험과 같은 브랜드 7의 가격순 첫 20개에 좋아요 수·내 좋아요 여부를 붙인 목록이다. 먼저 [조회 범위·다섯 경우의 비교·현재 설정](price-cache-comparison.md)을 읽는다.

## 1. 조회: 있으면 쓰고, 없으면 만든다

```text
가격순 첫 페이지 요청
  → DB에서 브랜드 확인
  → Redis에 목록이 있나?
      있음(hit): DB에서 캐시 상품의 삭제 여부 확인 후 재사용
      없음(miss): DB에서 가격순 상품 20개 조회 → Redis에 저장
  → DB에서 현재 좋아요 수 조회
  → X-USER-ID가 있으면 사용자 확인·페이지 상품의 내 좋아요 여부 일괄 조회
  → 브랜드·좋아요 수·isLiked를 합쳐 응답
```

브랜드 필터·가격순·page=0·size=20만 대상이다. 저장 값은 상품 ID·이름·가격·생성 시각이다. 목록 응답은 items·page·size이며 총상품수는 세거나 반환하지 않는다. 좋아요를 따로 가져오는 이유는 좋아요가 바뀌어도 가격순은 그대로이기 때문이다. 다른 페이지·정렬·브랜드 없는 요청은 DB로 조회한다.

## 2. 변경: 완료되면 복사본을 지운다

```text
상품 가격 변경 → DB commit → 그 브랜드의 첫 페이지 캐시 DEL
다음 조회 → 캐시 없음 → 새 목록 조회·저장
```

상품 생성·이름/가격 수정·상품 삭제·브랜드 삭제가 대상이다. 현재 목록 밖에 있던 상품도 가격이 내려가면 들어올 수 있어 같은 브랜드 페이지를 지운다. 다른 브랜드와 좋아요·재고·브랜드 이름 변경은 가격순 캐시를 지우지 않는다. 브랜드 정보는 매번 DB에서 읽는다.

`PricePageChanged`는 “이 브랜드의 목록이 바뀌었다”는 앱 내부 알림이다. `PricePageInvalidator`는 DB commit 후 그 알림을 받아 DEL한다. 실패해 rollback되면 DEL하지 않는다. 별도 메시지 서버나 durable queue를 추가한 것이 아니다.

## 3. TTL: 복사본의 유효기간

저장할 때 300초 TTL을 둔다. 재사용할 때 연장하지 않는다. 삭제가 실패해도 그 항목을 계속 남겨두지 않기 위한 장치다. 300초가 최적이라는 측정은 하지 않았다.

TTL만으로 모든 요청의 변경 직후 최신성을 보장하지 않는다. 예를 들어:

```text
조회 A가 옛 가격을 DB에서 읽음
  → 가격 변경 commit·캐시 삭제
  → 조회 A가 늦게 옛 목록 저장
  → 다음 조회에서 옛 가격이 보일 수 있음
```

이것이 기본 방식의 한계다. 실패·경쟁 중 표시 이름·가격·순서가 일시적으로 오래될 수 있다. TTL은 늦게 저장한 시점부터 시작되므로 “DB 변경 후 무조건 300초 안에 모든 요청이 최신”이라고 설명하지 않는다.

## 4. 지켜야 하는 두 가지

- 삭제 상품은 보여주면 안 된다. 그래서 hit도 캐시 속 상품 ID가 현재 활성인지 DB에 확인한다. 빠진 상품이 있으면 DB에서 전체 페이지를 다시 구한다. 캐시 hit가 DB 호출 0회라는 뜻은 아니다.
- 아직 commit되지 않은 값을 Redis에 저장하면 안 된다. 진행 중인 쓰기 트랜잭션 안의 조회는 DB만 사용한다. 일반 고객의 독립 조회는 캐시를 사용할 수 있다.

Redis가 멈추면 DB로 조회한다. 복구 후 옛 키가 남아 있으면 즉시 최신이라고 보장하지 않는다. 만료 또는 성공한 관련 DEL 뒤 DB로 다시 채운다. DB도 실패했다면 정상 빈 목록으로 감추지 않는다. 주문 금액·재고·포인트 판단은 기존 DB 로직을 사용한다.

## 코드 읽는 순서

1. [PricePageReader](../../apps/commerce-api/src/main/java/com/loopers/application/product/PricePageReader.java): hit/miss 선택.
2. [RedisPricePageCache](../../apps/commerce-api/src/main/java/com/loopers/infrastructure/product/RedisPricePageCache.java): GET·SET+TTL·DEL.
3. [ProductFacade](../../apps/commerce-api/src/main/java/com/loopers/application/product/ProductFacade.java): 상품 변경 뒤 알림 발행.
4. [PricePageInvalidator](../../apps/commerce-api/src/main/java/com/loopers/application/product/PricePageInvalidator.java): commit 후 삭제.

local과 benchmark는 같은 Redis를 쓰므로 기본 prefix를 분리했다. 같은 프로필에서 다른 DB로 연결을 바꾸면 prefix도 바꾼다. 이 설정은 서로 다른 데이터의 복사본을 섞지 않기 위한 것이다.

현재 캐시 정책은 [ADR 0021](../adr/0021-price-page-cache-aside-with-after-commit-eviction.md), 목록 응답은 [ADR 0022](../adr/0022-product-list-without-total-count.md), 실행 검증은 [구현 기록](implementation-log.md#price-cache-without-count)을 따른다. 개발용 `local`·측정용 `benchmark` 프로필은 현재 Redis 캐시가 켜져 있다. `local` 프로필 이름이 로컬 캐시 선택을 뜻하지는 않는다. 이 설정은 학습 구현의 기본값이며 최종 채택 여부와 구분한다.

## 가격 변경 흐름을 예로 읽기

아래 가격과 상품 A/B/C는 동작을 이해하기 위한 설명용 예시다. 성능 측정 수치가 아니다.

```text
A: 1,000원 / B: 2,000원 / C: 3,000원
```

1. **처음 가격순 조회**: DB에서 `A → B → C`를 가져와 응답하고 같은 목록을 Redis에도 저장한다. 이것이 miss다.
2. **다시 같은 조회**: Redis에 저장된 `A → B → C`를 사용한다. 이것이 hit다. 브랜드·상품 삭제 여부·현재 좋아요 수는 DB에서 확인한다.
3. **관리자가 B를 500원으로 변경**: DB 가격 변경이 완료되면 Redis의 기존 목록을 삭제한다. B가 맨 앞으로 와야 하므로 옛 목록은 더 이상 사용하지 않는다.
4. **변경 후 다음 조회**: 캐시가 비어 있으니 DB에서 새 목록 `B → A → C`를 가져와 응답하고 Redis에 다시 저장한다.

```text
가격 변경 완료 → 옛 캐시 삭제 → 다음 조회에서 새 목록 저장
```

| 도구 | 확인하는 역할 |
| --- | --- |
| SQL | DB 가격과 기대 순서가 바뀌었나? |
| API | 실제 앱을 통해 가격을 바꾸고 조회하기 |
| Redis 명령 | 옛 목록이 지워지고 새 목록이 저장됐나? |

직접 SQL UPDATE만 실행하면 앱의 commit 후 캐시 삭제 알림을 거치지 않는다. 자동 무효화를 확인하는 변경 경로는 관리자 API다. 위 흐름은 정상 변경 후 순차 조회를 설명하며, 실패·늦은 저장의 한계는 앞의 3~4절을 따른다.

기존 자동 테스트 완료는 사용자가 직접 같은 장면을 실습했다는 뜻은 아니다. [측정에서 hit·miss를 확인한 방법](price-cache-comparison.md#redis-hitmiss를-어떻게-만들고-확인했나), [가격 변경 검증과 시간 측정의 구분](price-cache-comparison.md#가격-변경-후-데이터-확인과-getput-시간은-왜-다른가)도 함께 읽는다.

## 내 좋아요 표시는 어디서 가져오나?

여러 사용자가 같은 상품 캐시를 읽어도 하트는 각자 달라야 한다. `isLiked`는 캐시에 넣지 않고, 페이지를 고른 뒤 `사용자 ID + 그 페이지의 상품 ID`로 DB에서 한 번에 찾는다. 캐시 hit여도 이 조회는 실행한다. 좋아요 수는 `product_like_count`, 내 좋아요 여부는 `product_like`에서 가져온다.

개인별 값을 합친 최종 응답은 캐시에 저장하지 않는다. 공통 상품 페이지를 구분하는 캐시 키에도 사용자 ID를 추가하지 않는다.

좋아요 등록/취소가 완료된 뒤 목록을 새로 요청하면 하트와 좋아요 수를 다시 합친다. 이 변경은 공통 가격순 캐시를 지우지 않는다. 사용자 헤더 없이 조회하면 전부 `isLiked=false`다. [요청·오류 규칙](../adr/0023-product-list-personal-like-state.md), [검증 기록](implementation-log.md#product-list-personal-like-state)을 참고한다.

## 다음 실습: 캐시 없음·로컬·Redis 비교

같은 가격순 조회를 세 방식으로 비교하는 방법은 [가격순 캐시 비교](price-cache-comparison.md)에 정리했다. 로컬은 앱 메모리, Redis는 공유 저장소에 같은 상품 페이지를 둔다. 읽기 속도만 아니라 가격을 바꾼 뒤 다른 앱에도 반영되는지 확인한다. [실제 실행 결과](implementation-log.md#price-cache-without-count)와 미측정 범위를 구분해서 읽는다.

여기서 "앱 두 대"는 같은 백엔드를 A와 B로 두 번 실행한다는 뜻이다. DB가 같아도 로컬 캐시는 앱마다 따로 있다. A에서 가격을 바꾸고 A의 캐시를 지워도 B에는 옛 가격이 남을 수 있다. [두 앱의 구조와 가격 변경 예시](price-cache-comparison.md#앱-두-대는-무슨-뜻인가)를 함께 보면 된다.


같은 목록을 여러 명이 한꺼번에 조회하는 경우와 한 브랜드의 상품이 많은 경우는 [상품 쏠림·동시 조회 실험](price-cache-skew.md)에 설명했다. 캐시가 채워져 있을 때의 이점과, 비어 있을 때 여러 요청이 같은 목록을 DB에서 다시 읽는 문제를 따로 확인했다.
