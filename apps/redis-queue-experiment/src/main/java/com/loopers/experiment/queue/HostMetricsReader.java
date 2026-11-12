package com.loopers.experiment.queue;

import org.springframework.stereotype.Component;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

@Component
public class HostMetricsReader {

    private final com.sun.management.OperatingSystemMXBean operatingSystem;
    private final File scratchDirectory;

    public HostMetricsReader(ExperimentProperties properties) {
        this.operatingSystem = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        this.scratchDirectory = new File(properties.scratchDirectory());
    }

    public HostMetrics read() {
        Runtime runtime = Runtime.getRuntime();
        long heapUsed = runtime.totalMemory() - runtime.freeMemory();
        return new HostMetrics(
            percent(operatingSystem.getCpuLoad()),
            percent(operatingSystem.getProcessCpuLoad()),
            operatingSystem.getFreeMemorySize(),
            operatingSystem.getTotalMemorySize(),
            heapUsed,
            runtime.maxMemory(),
            scratchDirectory.getUsableSpace(),
            scratchDirectory.getTotalSpace(),
            processIoBytes("read_bytes"),
            processIoBytes("write_bytes")
        );
    }

    private long processIoBytes(String metric) {
        try {
            Map<String, Long> io = new HashMap<>();
            for (String line : Files.readAllLines(Path.of("/proc/self/io"))) {
                String[] parts = line.split(":\\s*");
                if (parts.length == 2) {
                    io.put(parts[0], Long.parseLong(parts[1]));
                }
            }
            return io.getOrDefault(metric, -1L);
        } catch (IOException | NumberFormatException exception) {
            return -1L;
        }
    }

    private double percent(double load) {
        return load < 0 ? -1 : Math.round(load * 1000.0) / 10.0;
    }
}
