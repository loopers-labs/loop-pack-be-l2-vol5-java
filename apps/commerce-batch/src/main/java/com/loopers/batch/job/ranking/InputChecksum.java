package com.loopers.batch.job.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;

// snapshot 입력의 checksum 규칙(docs/week9/commerce-ranking.md 3절). 행을 order_id, product_id 오름차순으로 놓고
// "orderId\tproductId\tquantity\tconfirmedAt\n"을 이어 붙인 UTF-8 바이트의 SHA-256 소문자 hex다. 빈 입력은 빈 문자열의 SHA-256이다.
public final class InputChecksum {
    private static final DateTimeFormatter CONFIRMED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
        .withZone(ZoneOffset.UTC);

    private InputChecksum() {
    }

    public static String of(List<SourcePurchase> rows) {
        StringBuilder canonical = new StringBuilder();
        rows.stream()
            .sorted(Comparator.comparingLong(SourcePurchase::orderId).thenComparingLong(SourcePurchase::productId))
            .forEach(row -> canonical.append(row.orderId()).append('\t').append(row.productId()).append('\t')
                .append(row.quantity()).append('\t').append(CONFIRMED_AT.format(row.confirmedAt())).append('\n'));
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256을 쓸 수 없습니다.", e);
        }
    }
}
