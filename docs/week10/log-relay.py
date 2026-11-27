"""W10 run.sh 로그 중계 (plan.md D-13 ②) — 표준 라이브러리만 쓴다.

앱(java·pg-sim)은 로그 파일을 계속 연 채로 덧붙인다. macOS Docker Desktop의 파일 공유는 그렇게 열린 파일에
덧붙인 내용을 컨테이너(Alloy)에 바로 알리지 않고, 쓰는 쪽이 파일을 닫아야 전한다
(11-25 측정: 연 채로 1초마다 쓴 15줄이 0줄로 보이다가 닫은 직후 15줄이 한꺼번에 Loki에 들어갔다).

그래서 원본을 따라 읽어 사본 폴더에 "쓸 때마다 열고 → 덧붙이고 → 닫는다". Alloy는 사본 폴더만 본다.
원본은 그대로 남아 run.sh의 grep·증거 복사가 쓴다.

사용: python3 -I log-relay.py <사본 폴더> <원본 파일>...
"""
import os
import sys
import time


def main() -> None:
    out_dir, sources = sys.argv[1], sys.argv[2:]
    offsets = {src: 0 for src in sources}
    pending = {src: b"" for src in sources}
    while True:
        moved = False
        for src in sources:
            try:
                with open(src, "rb") as f:
                    f.seek(offsets[src])
                    chunk = f.read()
            except FileNotFoundError:
                continue
            if not chunk:
                continue
            offsets[src] += len(chunk)
            lines, newline, rest = (pending[src] + chunk).rpartition(b"\n")
            pending[src] = rest  # 아직 끝나지 않은 줄은 다음 번에
            if not newline:
                continue
            with open(os.path.join(out_dir, os.path.basename(src)), "ab") as out:
                out.write(lines + b"\n")
            moved = True
        time.sleep(0.05 if moved else 0.2)


if __name__ == "__main__":
    main()
