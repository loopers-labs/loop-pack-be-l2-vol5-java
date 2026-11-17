package com.loopers.interfaces.api.coupon;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.IntFunction;

/** Bounded coupon senders and independently scheduled, matched HTTP reads to the same local app. */
final class CouponLoadDriver {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long READ_INTERVAL = Duration.ofMillis(50).toNanos();
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String SOLD_OUT = "409 COUPON_CAMPAIGN_SOLD_OUT";

    record Coupons(int requests, int concurrency, double durationMillis,
                   Map<String, Long> outcomes, Map<Integer, Long> acceptedReservations) { }
    record Reads(int samples, int errors, double p50Ms, double p95Ms, double p99Ms,
                 double maxMs, double p95DispatchLagMs, Map<String, Long> outcomes) { }
    record Phase(Reads product, Reads order) { }
    record Segment(int pairs, Double baselineProductP95Ms, Double loadedProductP95Ms,
                   Double baselineOrderP95Ms, Double loadedOrderP95Ms) { }
    record ReceiptBreakdown(double lastSuccessfulReceiptMs, Segment beforeReceipt, Segment afterReceipt, int straddlingPairs) { }
    record Result(Coupons coupons, Phase baseline, Phase loaded, double overlapMillis,
                  int plannedProbePairs, int matchedProbePairs, ReceiptBreakdown byLastReceipt) { }

    static Result run(URI app, long campaignId, long productId, long orderId, String orderOwner,
                      IntFunction<String> applicantIdentity, int requests, int concurrency,
                      Consumer<String> phaseStart, Consumer<String> phaseFinished) throws Exception {
        if (requests < 1 || concurrency < 1) {
            throw new IllegalArgumentException("Request and concurrency counts must be positive");
        }
        var product = new ReadTarget(request(app.resolve("/api/v1/products/" + productId)).GET().build(), "productId", productId);
        var order = new ReadTarget(request(app.resolve("/api/v1/orders/" + orderId)).header("X-USER-ID", orderOwner).GET().build(), "orderId", orderId);
        URI issues = app.resolve("/api/v1/coupon-campaigns/" + campaignId + "/issues");
        try (var reads = client(); var couponClient = client(); var tasks = Executors.newVirtualThreadPerTaskExecutor()) {
            var probe = new ProbeSchedule(tasks, reads, product, order);
            try {
                long warmStart = epoch();
                var warmup = new ArrayList<PendingPair>();
                for (int tick = 0; tick < 80; tick++) {
                    warmup.add(probe.submit(tick, warmStart + tick * READ_INTERVAL));
                }
                for (var pair : warmup) {
                    ProbePair response = pair.read();
                    if (!response.product().ok() || !response.order().ok()) {
                        throw new IllegalStateException("Warmup did not return both seeded detail resources: " + response);
                    }
                }

                phaseStart.accept("loaded");
                long mixedStart = epoch();
                var accepted = new ConcurrentHashMap<Integer, Long>();
                var outcomes = new ConcurrentHashMap<String, LongAdder>();
                var next = new AtomicInteger(1);
                var finish = new AtomicLong(mixedStart);
                var lastReceipt = new AtomicLong(Long.MIN_VALUE);
                int lanes = Math.min(concurrency, requests);
                var done = new CountDownLatch(lanes);
                var senders = new ArrayList<Future<?>>();
                for (int lane = 0; lane < lanes; lane++) {
                    senders.add(tasks.submit(() -> {
                        try {
                            waitUntil(mixedStart);
                            for (int id = next.getAndIncrement(); id <= requests; id = next.getAndIncrement()) {
                                if (Thread.currentThread().isInterrupted()) {
                                    throw new InterruptedException();
                                }
                                CouponReply reply = submit(couponClient, issues, campaignId, applicantIdentity.apply(id));
                                long received = System.nanoTime();
                                finish.accumulateAndGet(received, Math::max);
                                outcomes.computeIfAbsent(reply.outcome(), ignored -> new LongAdder()).increment();
                                if (reply.reservationId() != null) {
                                    lastReceipt.accumulateAndGet(received, Math::max);
                                    accepted.put(id, reply.reservationId());
                                }
                            }
                            return null;
                        } finally {
                            done.countDown();
                        }
                    }));
                }
                long deadline = mixedStart + Duration.ofMinutes(12).toNanos();
                var mixed = new TreeMap<Integer, ProbePair>();
                var loaded = new ArrayList<PendingPair>();
                for (int tick = 0; ; tick++) {
                    long due = mixedStart + tick * READ_INTERVAL;
                    if (due >= deadline) {
                        throw new IllegalStateException("Coupon load exceeded 12 minutes");
                    }
                    if (done.await(Math.max(0, due - System.nanoTime()), TimeUnit.NANOSECONDS)) {
                        break;
                    }
                    // If the load generator stalled, omit missed ticks instead of emitting a catch-up burst.
                    if (System.nanoTime() - due < READ_INTERVAL) {
                        loaded.add(probe.submit(tick, due));
                    }
                }
                for (var sender : senders) {
                    sender.get();
                }
                long completed = finish.get();
                for (var pending : loaded) {
                    ProbePair pair = pending.read();
                    if (pair.product().started() < completed && pair.order().started() < completed) {
                        mixed.put(pending.tick(), pair);
                    }
                }
                phaseFinished.accept("loaded");
                if (mixed.isEmpty()) {
                    throw new IllegalStateException("No product/order pair overlapped coupon requests");
                }

                phaseStart.accept("baseline");
                long baselineStart = epoch();
                var baseline = new ArrayList<PendingPair>();
                for (int tick : mixed.keySet()) {
                    long due = baselineStart + tick * READ_INTERVAL;
                    waitUntil(due);
                    if (System.nanoTime() - due < READ_INTERVAL) {
                        baseline.add(probe.submit(tick, due));
                    }
                }
                long duration = completed - mixedStart;
                waitUntil(baselineStart + duration);
                var baselineRecords = new ArrayList<ProbePair>();
                var loadedRecords = new ArrayList<ProbePair>();
                for (var pending : baseline) {
                    ProbePair pair = pending.read();
                    if (pair.product().started() < baselineStart + duration && pair.order().started() < baselineStart + duration) {
                        baselineRecords.add(pair);
                        loadedRecords.add(mixed.get(pending.tick()));
                    }
                }
                phaseFinished.accept("baseline");
                if (baselineRecords.isEmpty()) {
                    throw new IllegalStateException("No matched product/order observation");
                }
                var counts = new TreeMap<String, Long>();
                outcomes.forEach((key, count) -> counts.put(key, count.sum()));
                var coupons = new Coupons(requests, concurrency, millis(duration), counts, new TreeMap<>(accepted));
                var breakdown = accepted.isEmpty() ? null : breakdown(mixedStart, lastReceipt.get(), baselineRecords, loadedRecords);
                return new Result(coupons, summarize(baselineRecords), summarize(loadedRecords), millis(duration),
                    Math.toIntExact((duration - 1) / READ_INTERVAL + 1), loadedRecords.size(), breakdown);
            } finally {
                tasks.shutdownNow();
            }
        }
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3)).build();
    }

    private static HttpRequest.Builder request(URI uri) {
        return HttpRequest.newBuilder(uri).timeout(REQUEST_TIMEOUT);
    }

    private static long epoch() {
        return System.nanoTime() + Duration.ofMillis(300).toNanos();
    }

    private static void waitUntil(long target) throws InterruptedException {
        long left = target - System.nanoTime();
        if (left > 0) {
            TimeUnit.NANOSECONDS.sleep(left);
        }
    }

    private record CouponReply(String outcome, Long reservationId) { }

    private static CouponReply submit(HttpClient client, URI uri, long campaignId, String identity) throws InterruptedException {
        var post = request(uri).header("X-USER-ID", identity).POST(HttpRequest.BodyPublishers.noBody()).build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(post, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException failure) {
            return new CouponReply("TRANSPORT " + failure.getClass().getSimpleName(), null);
        }
        int code = response.statusCode();
        try {
            JsonNode envelope = JSON.readTree(response.body());
            JsonNode data = envelope.path("data");
            String result = envelope.at("/meta/result").asText();
            String error = envelope.at("/meta/errorCode").asText();
            if (code == 409 && "FAIL".equals(result) && "COUPON_CAMPAIGN_SOLD_OUT".equals(error)) {
                return new CouponReply(SOLD_OUT, null);
            }
            String state = data.path("status").asText();
            boolean validState = (code == 202 && "WAITING".equals(state)) ||
                (code == 200 && "ISSUED".equals(state) && data.path("couponId").asLong() > 0);
            long reservation = data.path("reservationId").asLong();
            if (validState && "SUCCESS".equals(result) && data.path("campaignId").asLong() == campaignId && reservation > 0) {
                return new CouponReply(code + " " + state, reservation);
            }
            return new CouponReply(code + " UNEXPECTED " + error, null);
        } catch (IOException failure) {
            return new CouponReply(code + " INVALID_JSON", null);
        }
    }

    private record ReadTarget(HttpRequest request, String idField, long expectedId) { }
    private record Sample(long started, long elapsed, long dispatchLag, String outcome) {
        boolean ok() { return "200 SUCCESS".equals(outcome); }
    }
    private record ProbePair(Sample product, Sample order) { }
    private record PendingPair(int tick, Future<Sample> product, Future<Sample> order) {
        ProbePair read() throws Exception { return new ProbePair(product.get(), order.get()); }
    }

    private record ProbeSchedule(ExecutorService tasks, HttpClient client, ReadTarget product, ReadTarget order) {
        PendingPair submit(int tick, long due) {
            return new PendingPair(tick, tasks.submit(() -> probe(client, product, due)), tasks.submit(() -> probe(client, order, due)));
        }
    }

    private static Sample probe(HttpClient client, ReadTarget target, long due) throws InterruptedException {
        waitUntil(due);
        long start = System.nanoTime();
        long elapsed;
        String result;
        try {
            var response = client.send(target.request(), HttpResponse.BodyHandlers.ofByteArray());
            elapsed = System.nanoTime() - start;
            result = readOutcome(response, target);
        } catch (IOException failure) {
            elapsed = System.nanoTime() - start;
            result = "TRANSPORT " + failure.getClass().getSimpleName();
        }
        return new Sample(start, elapsed, Math.max(0, start - due), result);
    }

    private static String readOutcome(HttpResponse<byte[]> response, ReadTarget target) {
        int status = response.statusCode();
        try {
            var envelope = JSON.readTree(response.body());
            if (status == 200 && "SUCCESS".equals(envelope.at("/meta/result").asText()) &&
                envelope.path("data").path(target.idField()).asLong() == target.expectedId()) {
                return "200 SUCCESS";
            }
            return status + " UNEXPECTED " + envelope.at("/meta/errorCode").asText();
        } catch (IOException failure) {
            return status + " INVALID_JSON";
        }
    }

    private static ReceiptBreakdown breakdown(long start, long receipt, List<ProbePair> baseline, List<ProbePair> loaded) {
        var earlyBaseline = new ArrayList<ProbePair>();
        var earlyLoaded = new ArrayList<ProbePair>();
        var laterBaseline = new ArrayList<ProbePair>();
        var laterLoaded = new ArrayList<ProbePair>();
        for (int i = 0; i < loaded.size(); i++) {
            ProbePair pair = loaded.get(i);
            if (pair.product().started() < receipt && pair.order().started() < receipt) {
                earlyBaseline.add(baseline.get(i));
                earlyLoaded.add(pair);
            } else if (pair.product().started() >= receipt && pair.order().started() >= receipt) {
                laterBaseline.add(baseline.get(i));
                laterLoaded.add(pair);
            }
        }
        return new ReceiptBreakdown(millis(receipt - start), segment(earlyBaseline, earlyLoaded),
            segment(laterBaseline, laterLoaded), loaded.size() - earlyLoaded.size() - laterLoaded.size());
    }

    private static Segment segment(List<ProbePair> baseline, List<ProbePair> loaded) {
        if (loaded.isEmpty()) {
            return new Segment(0, null, null, null, null);
        }
        Phase plain = summarize(baseline);
        Phase mixed = summarize(loaded);
        return new Segment(loaded.size(), plain.product().p95Ms(), mixed.product().p95Ms(),
            plain.order().p95Ms(), mixed.order().p95Ms());
    }

    private static Phase summarize(List<ProbePair> pairs) {
        return new Phase(stats(pairs.stream().map(ProbePair::product).toList()), stats(pairs.stream().map(ProbePair::order).toList()));
    }

    private static Reads stats(List<Sample> records) {
        long[] latencies = records.stream().mapToLong(Sample::elapsed).sorted().toArray();
        long[] lag = records.stream().mapToLong(Sample::dispatchLag).sorted().toArray();
        var outcomes = new TreeMap<String, Long>();
        records.forEach(record -> outcomes.merge(record.outcome(), 1L, Long::sum));
        return new Reads(records.size(), Math.toIntExact(records.stream().filter(record -> !record.ok()).count()),
            percentile(latencies, 50), percentile(latencies, 95), percentile(latencies, 99), percentile(latencies, 100),
            percentile(lag, 95), outcomes);
    }

    private static double percentile(long[] sorted, int percentile) {
        int index = (int) Math.ceil(sorted.length * percentile / 100.0) - 1;
        return millis(sorted[index]);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0;
    }
}
