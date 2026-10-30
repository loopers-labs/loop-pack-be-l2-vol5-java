# 주문·PG 결제 설계 읽기 안내

기존 PG 구현의 계약·처리·검증 기준을 기능별로 묶었다. 이벤트 발행의 추가 초안과 기존 PG 구현의 승인/실행 상태를 구분한다.

1. 공통 [의존](../../common/architecture.md)·[모델 관계](../../common/domain-model.md)·[API 규칙](../../common/api-contract.md).
2. [상태·저장·불변조건](model.md).
3. [Facade·TX·잠금·의사코드](processing.md).
4. [PG HTTP·회로·설정](pg-client.md)과 [주문·결제 API](api-contract.md).
5. [현행 검증 기준](verification.md)과 [과거 실행 기록](../../verification/payment.md).

판단 이유는 [PG 결제 결정](../../decisions/payment.md) 및 [공통 판단의 관련 A 절](../../decisions/domain-and-api.md)을 따른다. 과거 POINT 처리·검증은 [역사 기록](../../verification/legacy-point.md)이며 신규 PG의 계약으로 사용하지 않는다. PG 앱과 이벤트 계약은 설계 진입점의 기능별 문서에서 확인한다.
