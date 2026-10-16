# JMeter 동작 확인 원문

이 자료는 **사용자 본 측정 이전의 도구·응답 확인**이다. 세 시나리오를 각각 1명·1회, 총 3요청 실행했다.
20초 워밍업·60초 측정·2회 반복의 부하 결과가 아니다. 별도 mock 테스트도 실행하지 않았다.

| 시나리오 | 표본 | 실패 | 검증 | 원문 |
|---|---:|---:|---|---|
| latest | 1 | 0 | full | [JTL](latest-samples.jtl) |
| price | 1 | 0 | full | [JTL](price-samples.jtl) |
| brand7 | 1 | 0 | full | [JTL](brand7-samples.jtl) |

[실행 요약·DB 상태](summary.json)에 각 실행의 성공과 HTML 생성 확인, 계획·기대 응답·저장 속성의 해시를 보존했다.
전체 HTML·로그·명령·manifest는 로컬 build/benchmark/jmeter/before-smoke-20261016-133643-3153974-70f76a9e/에 있다.
같은 [사용자 실행 스크립트](../../jmeter/run-jmeter.ps1)의 Smoke 모드로 확인했다.

원문 JTL의 elapsed·latency·throughput 또는 1개 표본의 분위수를 본 측정 수치로 보고하지 않는다.
실행 전후 상품 100만 개·좋아요 2,597,980개·기존 상품 보조 인덱스 4개·128MiB 버퍼 풀과 주요 설정 11개가 유지됐다.
시간 기반 루프와 전체 12회 실행은 코드를 검토했으며, 사용자의 본 측정 전에 실제 실행했다고 주장하지 않는다.
