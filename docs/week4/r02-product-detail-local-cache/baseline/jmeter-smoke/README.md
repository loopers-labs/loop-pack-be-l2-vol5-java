# JMeter 전체 응답 검사

2026-10-16 15:44:48~15:46:12 KST에 동일 `commerce-api-5003d2c.jar`를 OFF/ON으로 새로 실행했다.
각 모드에서 고정 활성 상품 100개를 1명·100회로 조회해 총 200건 모두 성공했다.
`full` assertion으로 전체 응답과 독립 oracle을 대조했다. 부하 측정 결과와 구분한다.

- [선별 요약과 원본 해시](summary.json)
- [고정 상품 ID](../../jmeter/product-ids.csv), [독립 기대 응답](../../jmeter/expected-responses.json)
- [원본 JTL·로그·메타데이터](../../../../../build/benchmark/r02/smoke-20261016-154446-1859652-cf5cb39a/)

각 모드의 API는 검증 후 종료했다. 전후 데이터·인덱스·DB 설정과 기존 18080/8081 소유자는 유지됐다.
미존재·400·삭제·쓰기·롤백의 정확성은 별도 실제 MySQL/HTTP 테스트로 확인했다.
