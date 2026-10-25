# 상품 목록 조회 성능 실험

이 폴더는 상품 목록 조회 개선의 측정 보고서, K6 스크립트, 실행 계획과 결과 원본을 함께 보관한다. 캐시의 동작 계약은 [상품 목록 캐시 설계](../../system-design/use-cases/product-list-cache.md)에 있다.

## 실행

저장소 루트에서 기본 측정은 다음 명령으로 실행한다.

    k6 run docs/performance/product-listing/k6/product-listing.k6.js

스크립트는 기본적으로 http://localhost:18081의 첫 페이지를 대상으로 하며, 기준 응답 파일과 결과 출력 위치는 스크립트의 기본값을 사용한다. 동시성, 표본 수, 좋아요 쓰기 혼합 등 조정 가능한 PRODUCT_QUERY_* 설정은 스크립트 상단을 확인한다.

## 자료

- [측정 보고서](report.md): 환경, 방법, 단계별 결과와 판단
- [K6 시나리오](k6/product-listing.k6.js): 응답 검증과 부하 패턴
- [기준선 결과](results/baseline/)
- [단계별 원본](results/): baseline, step1~step5
