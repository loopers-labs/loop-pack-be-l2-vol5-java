// 8주차 4단계 측정(D136) 부하 도구. JDK 21 단일 파일 프로그램이라 빌드 없이 실행한다.
//   java scripts/perf/week8/AdmissionLoad.java --base http://localhost:8080 --campaign 9001 --users 1000 --spread 2s --product-rps 50
// 쿠폰 발급 요청(사용자 1..N, X-USER-ID)을 spread 안에 고르게 시작하고, 그동안 상품 목록 조회를 초당 R건 보낸다.
// 상품 조회는 발급 요청이 모두 끝날 때까지 계속한다. 1회 측정용이라 절대값보다 방향을 본다.
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class AdmissionLoad {
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    // spread 뒤 마지막 요청이 응답하기까지의 여유. 요청 timeout보다 길게 둔다.
    private static final Duration DRAIN = Duration.ofSeconds(30);

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String base = options.getOrDefault("base", "http://localhost:8080");
        long campaign = Long.parseLong(required(options, "campaign"));
        int users = Integer.parseInt(options.getOrDefault("users", "1000"));
        Duration spread = duration(options.getOrDefault("spread", "2s"));
        int productRps = Integer.parseInt(options.getOrDefault("product-rps", "50"));
        String productPath = options.getOrDefault("product-path", "/api/v1/products?page=0&size=20");

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        HttpClient client = HttpClient.newBuilder().executor(executor).connectTimeout(Duration.ofSeconds(5)).build();
        Map<String, AtomicInteger> couponCodes = new ConcurrentHashMap<>();
        ConcurrentLinkedQueue<Long> couponMillis = new ConcurrentLinkedQueue<>();
        ConcurrentLinkedQueue<Long> productMillis = new ConcurrentLinkedQueue<>();
        AtomicInteger productErrors = new AtomicInteger();
        Map<String, AtomicInteger> productErrorCodes = new ConcurrentHashMap<>();
        AtomicInteger productRequests = new AtomicInteger();
        AtomicBoolean couponsDone = new AtomicBoolean();
        CountDownLatch couponLatch = new CountDownLatch(users);

        long startNanos = System.nanoTime();
        try {
            for (int index = 0; index < users; index++) {
                long userId = index + 1;
                long offsetNanos = users == 1 ? 0 : spread.toNanos() * index / users;
                executor.submit(() -> {
                    try {
                        sleepUntil(startNanos + offsetNanos);
                        HttpRequest request = HttpRequest.newBuilder(URI.create(base + "/api/v1/coupon-campaigns/" + campaign + "/issues"))
                            .timeout(REQUEST_TIMEOUT).header("X-USER-ID", String.valueOf(userId))
                            .POST(HttpRequest.BodyPublishers.noBody()).build();
                        long sent = System.nanoTime();
                        try {
                            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                            couponMillis.add(millisSince(sent));
                            count(couponCodes, couponBucket(response.statusCode()));
                        } catch (HttpTimeoutException e) {
                            count(couponCodes, "timeout");
                        } catch (Exception e) {
                            count(couponCodes, "연결 오류");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        count(couponCodes, "중단");
                    } finally {
                        couponLatch.countDown();
                    }
                    return null;
                });
            }
            executor.submit(() -> {
                long intervalNanos = TimeUnit.SECONDS.toNanos(1) / Math.max(productRps, 1);
                long next = startNanos;
                while (!couponsDone.get() && productRps > 0) {
                    sleepUntil(next);
                    next += intervalNanos;
                    productRequests.incrementAndGet();
                    long requestUser = (productRequests.get() % users) + 1;
                    executor.submit(() -> {
                        HttpRequest request = HttpRequest.newBuilder(URI.create(base + productPath)).timeout(REQUEST_TIMEOUT)
                            .header("X-USER-ID", String.valueOf(requestUser)).GET().build();
                        long sent = System.nanoTime();
                        try {
                            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                            productMillis.add(millisSince(sent));
                            if (response.statusCode() != 200) {
                                productErrors.incrementAndGet();
                                count(productErrorCodes, String.valueOf(response.statusCode()));
                            }
                        } catch (HttpTimeoutException e) {
                            productErrors.incrementAndGet();
                            count(productErrorCodes, "timeout");
                        } catch (Exception e) {
                            productErrors.incrementAndGet();
                            count(productErrorCodes, "연결 오류(" + e.getClass().getSimpleName() + ")");
                        }
                        return null;
                    });
                }
                return null;
            });

            boolean finished = couponLatch.await(spread.plus(DRAIN).toMillis(), TimeUnit.MILLISECONDS);
            couponsDone.set(true);
            if (!finished) {
                count(couponCodes, "대기 상한 초과", (int) couponLatch.getCount());
            }
        } finally {
            couponsDone.set(true);
            executor.shutdown();
            if (!executor.awaitTermination(REQUEST_TIMEOUT.plusSeconds(5).toMillis(), TimeUnit.MILLISECONDS)) {
                executor.shutdownNow();
            }
        }
        long totalMillis = millisSince(startNanos);

        Map<String, Integer> codes = new TreeMap<>();
        couponCodes.forEach((key, value) -> codes.put(key, value.get()));
        List<Long> coupon = sorted(couponMillis);
        List<Long> product = sorted(productMillis);
        System.out.println("== 쿠폰 발급 (" + users + "명, spread " + spread.toMillis() + "ms, campaign " + campaign + ")");
        codes.forEach((key, value) -> System.out.println("  " + key + ": " + value));
        System.out.println("  지연 p50 " + percentile(coupon, 50) + "ms, p99 " + percentile(coupon, 99) + "ms (응답 받은 " + coupon.size() + "건)");
        System.out.println("== 상품 목록 (초당 " + productRps + "건 목표, " + productPath + ")");
        Map<String, Integer> productCodes = new TreeMap<>();
        productErrorCodes.forEach((key, value) -> productCodes.put(key, value.get()));
        System.out.println("  요청 " + productRequests.get() + "건, 오류 " + productErrors.get() + "건 " + productCodes);
        System.out.println("  지연 p50 " + percentile(product, 50) + "ms, p99 " + percentile(product, 99) + "ms");
        System.out.println("== 총 소요 " + totalMillis + "ms");
        System.out.println("SUMMARY coupon=" + codes + " coupon_p50=" + percentile(coupon, 50) + " coupon_p99=" + percentile(coupon, 99)
            + " product_requests=" + productRequests.get() + " product_errors=" + productErrors.get() + " product_error_codes="
            + productCodes + " product_p50="
            + percentile(product, 50) + " product_p99=" + percentile(product, 99) + " total_ms=" + totalMillis);
    }

    private static String couponBucket(int status) {
        if (status == 200 || status == 201 || status == 409 || status == 429 || status == 503) {
            return String.valueOf(status);
        }
        if (status >= 500) {
            return "5xx(" + status + ")";
        }
        return "기타(" + status + ")";
    }

    private static void count(Map<String, AtomicInteger> counts, String key) {
        count(counts, key, 1);
    }

    private static void count(Map<String, AtomicInteger> counts, String key, int amount) {
        counts.computeIfAbsent(key, ignored -> new AtomicInteger()).addAndGet(amount);
    }

    private static void sleepUntil(long targetNanos) throws InterruptedException {
        long waitNanos = targetNanos - System.nanoTime();
        if (waitNanos > 0) {
            TimeUnit.NANOSECONDS.sleep(waitNanos);
        }
    }

    private static long millisSince(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static List<Long> sorted(ConcurrentLinkedQueue<Long> values) {
        List<Long> list = new ArrayList<>(values);
        Collections.sort(list);
        return list;
    }

    // 가장 가까운 순위(nearest-rank) 백분위. 값이 없으면 -1.
    private static long percentile(List<Long> sortedValues, int percent) {
        if (sortedValues.isEmpty()) {
            return -1;
        }
        int rank = (int) Math.ceil(percent / 100.0 * sortedValues.size());
        return sortedValues.get(Math.max(rank, 1) - 1);
    }

    private static Duration duration(String value) {
        if (value.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(value.substring(0, value.length() - 2)));
        }
        if (value.endsWith("s")) {
            return Duration.ofMillis((long) (Double.parseDouble(value.substring(0, value.length() - 1)) * 1000));
        }
        return Duration.ofMillis(Long.parseLong(value));
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new TreeMap<>();
        for (int index = 0; index < args.length; index++) {
            if (!args[index].startsWith("--")) {
                throw new IllegalArgumentException("알 수 없는 인자: " + args[index]);
            }
            String key = args[index].substring(2);
            if (index + 1 >= args.length || args[index + 1].startsWith("--")) {
                throw new IllegalArgumentException("값이 없는 인자: " + args[index]);
            }
            options.put(key, args[++index]);
        }
        return options;
    }

    private static String required(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null) {
            throw new IllegalArgumentException("--" + key + "가 필요합니다");
        }
        return value;
    }
}
