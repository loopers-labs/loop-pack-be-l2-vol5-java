# 인덱스 적용 후 HTTP 응답 확인

[사후 검증 기록](../verification-after.json) · [실행 계획 비교](../index-review.md) · [적용 전 HTTP 자료](../http-before/README.md)

2026-10-16 14:28 KST 검증 기록이다. 인덱스 추가 뒤 기존 API PID **34084**와
`commerce-api-98da9d2.jar`를 유지한 상태에서 세 요청을 각 한 번 확인했다.
아래 값은 성능 부하 측정이 아니라 전체 응답의 정확성 확인 결과다.

| 요청 | HTTP | items | totalElements | 전체 기대 응답 일치 | 보존 응답 |
|---|---:|---:|---:|---|---|
| `sort=latest&page=0&size=20` | 200 | 20 | 950,400 | true | [latest](latest-response.json) |
| `sort=price_asc&page=0&size=20` | 200 | 20 | 950,400 | true | [price](price-response.json) |
| `brandId=7&sort=price_asc&page=0&size=20` | 200 | 20 | 960 | true | [brand7](brand7-response.json) |

모두 `GET /api/v1/products`이며 [seed 공식으로 계산한 기대 응답](../../jmeter/expected-responses.json)과
상품 항목의 모든 필드·순서·페이지·응답 포장을 비교해 일치했다.
이는 세 고정 입력의 검증이며 모든 필터·페이지·오류 시나리오를 새로 회귀한 결과는 아니다.

상품 100만 개·좋아요 2,597,980개, 기존 인덱스 4개 보존과 신규 2개 추가, 128MiB 버퍼 풀·전역 설정 11개 동일을
함께 확인했다. API 재시작이나 seed 재실행 없이 검증했고 사용자 GUI 파일 해시도 유지했다.

이번에는 SQL·바인딩 로그를 다시 켜지 않았다. 요청당 실제 2 SQL을 관측한 원문은 적용 전 HTTP 자료에 있으며,
after의 실행 계획은 별도 저장된 실제 SELECT의 EXPLAIN·ANALYZE 자료다.
새 JMeter 부하는 실행하지 않았고, 이전 timeout 33.33%의 부분 기준선과 이번 응답 확인을 섞어 개선율로 계산하지 않는다.
