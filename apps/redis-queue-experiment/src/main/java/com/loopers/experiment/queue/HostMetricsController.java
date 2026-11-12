package com.loopers.experiment.queue;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/experiment/metrics")
public class HostMetricsController {

    private final HostMetricsReader metricsReader;

    public HostMetricsController(HostMetricsReader metricsReader) {
        this.metricsReader = metricsReader;
    }

    @GetMapping
    public HostMetrics metrics() {
        return metricsReader.read();
    }
}
