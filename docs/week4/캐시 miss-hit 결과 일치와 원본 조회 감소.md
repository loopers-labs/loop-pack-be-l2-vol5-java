# 상품 목록 캐시: miss/hit 결과 일치와 원본 조회 감소

**같은 상품 목록 요청에서 DB-only·miss·hit의 전체 응답 JSON이 일치했고, 원본 목록 조회는 miss 1회에서 hit 0회로 줄었다.**

과제 체크리스트의 **“한 공통 조회의 miss/hit 결과가 맞고 비싼 원본 호출이 줄었다”**를 확인한 기록이다.

## 무엇을 비교했나

실제 애플리케이션·MySQL·Redis Testcontainers에서 상품 목록 API를 호출했다. 같은 데이터와 요청으로 아래 세 경로를 순서대로 실행하고, 조회 사이에는 데이터를 변경하지 않았다.

```http
GET /api/v1/products?sort={sort}&page={page}&size=2&brandId={brandId}
```

정렬은 `latest`·`price_asc`·`likes_desc`, 브랜드 필터는 지정·미지정으로 총 6개 조건이다. 각 조건에서 `page=0/1/2`를 조회해 첫 페이지·다음 페이지·빈 페이지를 확인했다. 가격·좋아요 동점, 좋아요 0개, 품절·삭제 상품을 포함했다.

## 결과가 같은가

| 경로 | 실행 조건 | 확인 결과 |
| --- | --- | --- |
| DB-only | 캐시 비활성 분기로 DB 조회 | 기준 응답 확보, 목록 캐시 미생성 |
| miss | 캐시 활성화 후 목록 키가 없는 상태에서 조회 | DB-only와 전체 JSON 일치, Redis 저장·TTL 확인 |
| hit | 같은 요청 재조회 | DB-only·miss와 전체 JSON 일치 |

응답 전체 비교에 더해 `page`·`size`·`last`를 검사하고, 페이지를 모았을 때 상품 ID가 중복·누락되지 않고 삭제 상품이 제외되는지 확인했다.

## 비싼 원본 호출이 줄었나

| 경로 | 원본 목록 조회 횟수 |
| --- | ---: |
| DB-only | 1회 |
| miss | 1회 |
| hit | **0회** |

즉, hit에서는 같은 결과를 반환하면서 목록을 다시 읽고 정렬·집계하는 원본 조회를 생략했다. 별도 통합 hit 테스트에서도 응답 일치와 상품 조회 저장소 호출 0회, revision 조회 2회를 확인했다.



## 참고

- [ReadCacheHttpTest.java](../../apps/commerce-api/src/test/java/com/loopers/config/cache/ReadCacheHttpTest.java)의 `databaseOnlyMissAndHitReturnSamePages`: DB-only·miss·hit의 응답과 원본 목록 조회 횟수 비교.
