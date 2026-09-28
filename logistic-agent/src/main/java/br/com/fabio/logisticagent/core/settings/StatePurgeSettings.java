package br.com.fabio.logisticagent.core.settings;

import java.time.Duration;

public record StatePurgeSettings(Duration chatMemoryTtl) {
}
