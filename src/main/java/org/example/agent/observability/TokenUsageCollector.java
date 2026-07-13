package org.example.agent.observability;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

public class TokenUsageCollector {
    private final List<ModelCallUsage> calls = new CopyOnWriteArrayList<>();
    private final Consumer<ModelCallUsage> listener;

    public TokenUsageCollector() { this(usage -> {}); }
    public TokenUsageCollector(Consumer<ModelCallUsage> listener) { this.listener = listener == null ? usage -> {} : listener; }

    public void record(ModelCallUsage usage) {
        calls.add(usage);
        listener.accept(usage);
    }

    public TokenUsageSummary summary() {
        return new TokenUsageSummary(
                calls.stream().mapToLong(ModelCallUsage::inputTokens).sum(),
                calls.stream().mapToLong(ModelCallUsage::outputTokens).sum(),
                calls.stream().mapToLong(ModelCallUsage::totalTokens).sum(),
                calls.stream().mapToLong(ModelCallUsage::durationMs).sum(),
                List.copyOf(calls));
    }
}
