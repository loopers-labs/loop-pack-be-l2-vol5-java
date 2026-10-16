import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.config.redis.RedisNodeInfo;
import com.loopers.config.redis.RedisProperties;
import com.loopers.domain.product.PricePage;
import com.loopers.infrastructure.product.ProductCacheRedisConfig;
import com.loopers.infrastructure.product.RedisPricePageCache;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Standalone component probe; timings do not include HTTP or database work. */
public class PriceCacheReadCost {
    public static void main(String[] args) throws Exception {
        Path input = Path.of(args[0]);
        Path output = Path.of(args[1]);
        String prefix = args[2];
        int warmup = Integer.parseInt(args[3]);
        int samples = Integer.parseInt(args[4]);
        String key = prefix + ":brand:7:sort:price_asc:page:0:size:20";
        try (var context = new AnnotationConfigApplicationContext()) {
            context.register(JacksonAutoConfiguration.class,
                    Class.forName("com.loopers.config.jackson.JacksonConfig"));
            context.refresh();
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            var config = new ProductCacheRedisConfig();
            var properties = new RedisProperties(0, new RedisNodeInfo("localhost", 6379), List.of());
            var connection = config.connection(properties);
            connection.afterPropertiesSet();
            try {
                var redis = config.template(connection);
                var cache = new RedisPricePageCache(redis, mapper, prefix, 300);
                PricePage expected = mapper.readValue(Files.readString(input), PricePage.class);
                cache.put(7L, 0, 20, expected);
                String prepared = redis.opsForValue().get(key);
                if (prepared == null || !expected.equals(mapper.readValue(prepared, PricePage.class))) {
                    throw new AssertionError("Prepared cache value differs from source page");
                }
                double[] getMs = new double[samples];
                double[] decodeMs = new double[samples];
                double[] combinedMs = new double[samples];
                try {
                    for (int i = -warmup; i < samples; i++) {
                        long start = System.nanoTime();
                        String value = redis.opsForValue().get(key);
                        long received = System.nanoTime();
                        PricePage page = mapper.readValue(value, PricePage.class);
                        long decoded = System.nanoTime();
                        // Validation and output stay outside the measured intervals.
                        if (!prepared.equals(value) || !expected.equals(page)) {
                            throw new AssertionError("Redis GET or decoded page differs from expected data");
                        }
                        if (i >= 0) {
                            getMs[i] = (received - start) / 1_000_000.0;
                            decodeMs[i] = (decoded - received) / 1_000_000.0;
                            combinedMs[i] = (decoded - start) / 1_000_000.0;
                        }
                    }
                    if (!expected.equals(cache.get(7L, 0, 20).orElseThrow())) {
                        throw new AssertionError("Application cache adapter result differs");
                    }
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("redis_get", stats(getMs));
                    result.put("json_decode", stats(decodeMs));
                    result.put("get_and_decode", stats(combinedMs));
                    result.put("payload_bytes", prepared.getBytes(StandardCharsets.UTF_8).length);
                    result.put("payload_sha256", java.util.HexFormat.of().formatHex(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(prepared.getBytes(StandardCharsets.UTF_8))));
                    result.put("payload", mapper.readTree(prepared));
                    result.put("ttl_seconds_observed", redis.getExpire(key));
                    result.put("validated_measured_reads", samples);
                    result.put("application_adapter_result_matches", true);
                    result.put("registered_jackson_modules", mapper.getRegisteredModuleIds());
                    result.put("writes_dates_as_timestamps", mapper.isEnabled(
                            com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS));
                    Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(result));
                } finally {
                    redis.delete(key);
                }
                if (Boolean.TRUE.equals(redis.hasKey(key))) {
                    throw new AssertionError("Owned probe key was not removed");
                }
            } finally {
                connection.destroy();
            }
        }
    }

    private static Map<String, Object> stats(double[] values) {
        double[] ordered = values.clone();
        Arrays.sort(ordered);
        int n = ordered.length;
        double median = n % 2 == 0 ? (ordered[n / 2 - 1] + ordered[n / 2]) / 2 : ordered[n / 2];
        return Map.of("median_ms", median, "p95_ms", ordered[(int) Math.ceil(n * .95) - 1],
                "samples_ms", values);
    }
}
