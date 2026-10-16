# 서울 실험 서버 배포

작성일: 2026-10-13. [장비·배포 스냅샷](../../docs/week4/experiments/environments/ec2-seoul.md)

- 앱 `54.117.65.124`, DB `16.184.11.214`. 앱→DB는 `172.31.26.139`로 연결한다.
- 두 서버의 `~/week4`에 기존 Docker 구성을 배포했다. env는 600, 개인키는 저장소 밖에 보관한다.
- `uniform-10k-v1`의 5개 테이블 건수·전체 CSV 필드 체크섬 일치, LOAD DATA 경고 0개를 확인했다.
- 상품 목록 응답이 CSV와 일치하고 요청별 서버·SQL Trace와 Actuator 지표 수집이 확인됐다.
- 앱 이미지 `commerce-api:week4-seoul-baseline`, JAR SHA-256 `212d229e08028b7e8af9ee7f808610ecd8b3bddd14992148927e64d13d3b0ea3`.

서버의 `~/week4`에서 실행한다.

```bash
# DB 서버
sudo docker compose --env-file db.env -f db-compose.yml up -d --wait

# 앱 서버: 실험 프로필만 추가. 조회·풀 설정은 기존과 동일
sudo docker compose -p week4 --env-file app.env -f app-compose.yml -f experiment-compose.yml up -d --wait
```

재적재는 빈 실험 DB에서만 기존 seed 스크립트로 수행한다. 적재·이미지 빌드·환경 변경은 부하 측정과 겹치지 않는다. 시드니 서버는 이번 작업에서 중지·삭제하지 않았다.
