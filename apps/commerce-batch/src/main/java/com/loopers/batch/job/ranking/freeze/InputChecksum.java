package com.loopers.batch.job.ranking.freeze;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

/**
 * 입력 checksum. orderId · productId 순 정렬, 필드 사이 TAB · 행 끝 LF, UTC 시각을 마이크로초까지 고정 형식으로 쓴 뒤 SHA-256 (9주차 D-178).
 * 실행 ID · 실행 시각은 넣지 않는다. 입력 전체를 메모리에 두지 않도록 정렬된 행을 하나씩 받아 누적한다
 */
public final class InputChecksum {

    private static final DateTimeFormatter UTC_MICROS = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");
    private static final Comparator<PurchaseInput> ORDER = Comparator.comparingLong(PurchaseInput::orderId)
        .thenComparingLong(PurchaseInput::productId);

    private final MessageDigest digest;
    private PurchaseInput last;

    public InputChecksum() {
        try {
            this.digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String of(List<PurchaseInput> inputs) {
        InputChecksum checksum = new InputChecksum();
        inputs.stream().sorted(ORDER).forEach(checksum::add);
        return checksum.hex();
    }

    /** 정렬 순서가 어긋나면 같은 입력이 다른 checksum 이 되므로 조용히 받지 않는다 */
    public void add(PurchaseInput input) {
        if (last != null && ORDER.compare(last, input) >= 0) {
            throw new IllegalStateException("checksum 입력이 (orderId, productId) 오름차순이 아님. last=" + last + " input=" + input);
        }
        String line = input.orderId() + "\t" + input.productId() + "\t" + input.quantity() + "\t"
            + input.confirmedAtUtc().format(UTC_MICROS) + "\n";
        digest.update(line.getBytes(StandardCharsets.UTF_8));
        last = input;
    }

    public String hex() {
        return HexFormat.of().formatHex(digest.digest());
    }
}
