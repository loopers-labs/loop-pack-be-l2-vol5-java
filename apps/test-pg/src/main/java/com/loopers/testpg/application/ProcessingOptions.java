package com.loopers.testpg.application;

import java.time.Duration;

public record ProcessingOptions(ProcessingMode mode, Duration processingDelay, Duration dropResponseDelay) {}
