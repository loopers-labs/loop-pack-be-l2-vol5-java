package com.loopers.support;

import io.lettuce.core.RedisFuture;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisCacheMemoryProbeTest {
    private static final String PREFIX = "commerce:measurement:";

    @Test
    void deduplicatesScanResultsAndSeparatesAllocatedMemoryFromPayloadAndWholeProcessMemory() {
        var source = mock(RedisCacheMemoryProbe.ReadOnlySource.class);
        String first = PREFIX + "rev=4";
        String second = PREFIX + "rev=5";
        when(source.scan(PREFIX)).thenReturn(List.of(second, first, second));
        when(source.ttlMillis(first)).thenReturn(5000L, 4900L);
        when(source.ttlMillis(second)).thenReturn(30000L, 29900L);
        when(source.allocatedBytes(first)).thenReturn(1024L);
        when(source.allocatedBytes(second)).thenReturn(2048L);
        when(source.payloadBytes(first)).thenReturn(900L);
        when(source.payloadBytes(second)).thenReturn(1900L);
        Properties memory = new Properties();
        memory.setProperty("used_memory", "1000000");
        memory.setProperty("used_memory_dataset", "500000");
        memory.setProperty("used_memory_rss", "2000000");
        memory.setProperty("maxmemory", "0");
        Properties stats = new Properties();
        stats.setProperty("expired_keys", "7");
        stats.setProperty("evicted_keys", "0");
        Properties server = new Properties();
        server.setProperty("redis_version", "7.4.0");
        when(source.info("memory")).thenReturn(memory);
        when(source.info("stats")).thenReturn(stats);
        when(source.info("server")).thenReturn(server);

        var sample = RedisCacheMemoryProbe.measure(source, PREFIX, System.nanoTime());

        assertThat(sample.scannedKeys()).isEqualTo(2);
        assertThat(sample.duplicateScanKeys()).isEqualTo(1);
        assertThat(sample.vanishedDuringMeasurement()).isZero();
        assertThat(sample.liveKeys()).isEqualTo(2);
        assertThat(sample.allocatedBytes()).isEqualTo(3072);
        assertThat(sample.payloadBytes()).isEqualTo(2800);
        assertThat(sample.entries()).containsExactly(
            new RedisCacheMemoryProbe.Entry(first, 4L, 4900, 1024, 900),
            new RedisCacheMemoryProbe.Entry(second, 5L, 29900, 2048, 1900));
        assertThat(sample.server()).isEqualTo(
            new RedisCacheMemoryProbe.ServerMemory(1000000L, 500000L, 2000000L, 7L, 0L, 0L, "7.4.0"));
        assertThat(sample.elapsedNanos()).isGreaterThanOrEqualTo(0);
        verify(source, times(1)).allocatedBytes(second);
        assertThatThrownBy(() -> sample.entries().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void excludesAndCountsKeysThatDisappearAtAnyMeasurementBoundary() {
        var source = mock(RedisCacheMemoryProbe.ReadOnlySource.class);
        String before = PREFIX + "rev=1";
        String duringMemory = PREFIX + "rev=2";
        String afterLength = PREFIX + "rev=3";
        when(source.scan(PREFIX)).thenReturn(List.of(before, duringMemory, afterLength));
        when(source.ttlMillis(before)).thenReturn(-2L);
        when(source.ttlMillis(duringMemory)).thenReturn(1L);
        when(source.allocatedBytes(duringMemory)).thenReturn(null);
        when(source.ttlMillis(afterLength)).thenReturn(1L, -2L);
        when(source.allocatedBytes(afterLength)).thenReturn(1024L);
        when(source.payloadBytes(afterLength)).thenReturn(0L);

        var sample = RedisCacheMemoryProbe.measure(source, PREFIX, System.nanoTime());

        assertThat(sample.scannedKeys()).isEqualTo(3);
        assertThat(sample.vanishedDuringMeasurement()).isEqualTo(3);
        assertThat(sample.liveKeys()).isZero();
        assertThat(sample.allocatedBytes()).isZero();
        assertThat(sample.payloadBytes()).isZero();
        assertThat(sample.entries()).isEmpty();
        verify(source, never()).allocatedBytes(before);
        verify(source, never()).payloadBytes(duringMemory);
    }

    @Test
    void keepsPersistentAndEmptyStringKeysVisibleRatherThanMisclassifyingThemAsExpired() {
        var source = mock(RedisCacheMemoryProbe.ReadOnlySource.class);
        String key = PREFIX + "not-a-version";
        when(source.scan(PREFIX)).thenReturn(List.of(key));
        when(source.ttlMillis(key)).thenReturn(-1L);
        when(source.allocatedBytes(key)).thenReturn(72L);
        when(source.payloadBytes(key)).thenReturn(0L);

        var sample = RedisCacheMemoryProbe.measure(source, PREFIX, System.nanoTime());

        assertThat(sample.liveKeys()).isEqualTo(1);
        assertThat(sample.entries()).containsExactly(new RedisCacheMemoryProbe.Entry(key, null, -1, 72, 0));
    }

    @Test
    void missingServerFieldsRemainUnknownInsteadOfBecomingZero() {
        var sample = RedisCacheMemoryProbe.serverMemory(new Properties(), null, null);
        assertThat(sample).isEqualTo(new RedisCacheMemoryProbe.ServerMemory(null, null, null, null, null, null, null));
        Properties malformed = new Properties();
        malformed.setProperty("used_memory", "not-a-number");
        assertThatThrownBy(() -> RedisCacheMemoryProbe.serverMemory(malformed, null, null))
            .isInstanceOf(NumberFormatException.class);
    }

    @Test
    void treatsPrefixAsLiteralAndRejectsUnscopedScans() {
        assertThat(RedisCacheMemoryProbe.scanPattern("test:[a]*?\\:")).isEqualTo("test:\\[a\\]\\*\\?\\\\:*");
        assertThatThrownBy(() -> RedisCacheMemoryProbe.scanPattern(" ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RedisCacheMemoryProbe.scanPattern(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesPartialOrOutOfScopeMeasurements() {
        var source = mock(RedisCacheMemoryProbe.ReadOnlySource.class);
        when(source.scan(PREFIX)).thenReturn(List.of("other:rev=0"));
        assertThatThrownBy(() -> RedisCacheMemoryProbe.measure(source, PREFIX, System.nanoTime()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("outside");
        when(source.scan(PREFIX)).thenReturn(List.of(PREFIX + "rev=0"));
        when(source.ttlMillis(PREFIX + "rev=0")).thenReturn(null);
        assertThatThrownBy(() -> RedisCacheMemoryProbe.measure(source, PREFIX, System.nanoTime()))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("PTTL");
    }

    @Test
    @SuppressWarnings("unchecked")
    void memoryCommandTimeoutIsAnExplicitFailedMeasurementNotAZeroByteSample() throws Exception {
        RedisFuture<Long> result = mock(RedisFuture.class);
        when(result.get(2, TimeUnit.SECONDS)).thenThrow(new TimeoutException("test-only timeout"));
        assertThatThrownBy(() -> RedisCacheMemoryProbe.awaitMemoryUsage(result))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("incomplete")
            .hasCauseInstanceOf(TimeoutException.class);
        verify(result).cancel(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void interruptionCancelsThePendingMemoryCommandAndPreservesTheInterruptFlag() throws Exception {
        RedisFuture<Long> result = mock(RedisFuture.class);
        when(result.get(2, TimeUnit.SECONDS)).thenThrow(new InterruptedException("test-only interruption"));
        try {
            assertThatThrownBy(() -> RedisCacheMemoryProbe.awaitMemoryUsage(result))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("interrupted");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(result).cancel(true);
        } finally {
            Thread.interrupted();
        }
    }
}
