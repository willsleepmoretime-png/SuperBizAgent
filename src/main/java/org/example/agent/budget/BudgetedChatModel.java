package org.example.agent.budget;

import org.example.agent.observability.AgentStage;
import org.example.agent.observability.ModelCallUsage;
import org.example.agent.observability.TokenUsageCollector;
import org.example.agent.observability.UsageSource;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

public class BudgetedChatModel implements ChatModel {
    private final ChatModel delegate;
    private final AgentBudget budget;
    private final TokenUsageCollector collector;
    private final AgentStage stage;
    private final String requestId;
    private final String taskId;
    private final Supplier<Integer> roundSupplier;

    public BudgetedChatModel(ChatModel delegate, AgentBudget budget) {
        this(delegate, budget, new TokenUsageCollector(), AgentStage.SINGLE_AGENT, null, null, () -> null);
    }

    public BudgetedChatModel(ChatModel delegate, AgentBudget budget, TokenUsageCollector collector,
                             AgentStage stage, String requestId, String taskId, Supplier<Integer> roundSupplier) {
        this.delegate = delegate; this.budget = budget; this.collector = collector; this.stage = stage;
        this.requestId = requestId; this.taskId = taskId; this.roundSupplier = roundSupplier;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        budget.beforeModelCall();
        long start = System.nanoTime();
        ChatResponse response = delegate.call(prompt);
        UsageValues values = usage(response, prompt.getContents(), responseText(response));
        record(response, values, start);
        budget.addTokens(values.total());
        return response;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        return Flux.defer(() -> {
            budget.beforeModelCall();
            long start = System.nanoTime();
            AtomicLong providerInput = new AtomicLong();
            AtomicLong providerOutput = new AtomicLong();
            AtomicLong providerTotal = new AtomicLong();
            AtomicLong outputChars = new AtomicLong();
            AtomicBoolean providerSeen = new AtomicBoolean();
            String[] model = {null};
            return delegate.stream(prompt)
                    .doOnNext(response -> {
                        if (response != null && response.getMetadata() != null) {
                            model[0] = response.getMetadata().getModel();
                            Usage usage = response.getMetadata().getUsage();
                            if (usage != null && value(usage.getTotalTokens()) > 0) {
                                providerSeen.set(true);
                                providerInput.set(max(providerInput.get(), value(usage.getPromptTokens())));
                                providerOutput.set(max(providerOutput.get(), value(usage.getCompletionTokens())));
                                providerTotal.set(max(providerTotal.get(), value(usage.getTotalTokens())));
                            }
                        }
                        outputChars.addAndGet(responseText(response).length());
                    })
                    .doOnComplete(() -> {
                        UsageValues values = providerSeen.get()
                                ? new UsageValues(providerInput.get(), providerOutput.get(), providerTotal.get(), UsageSource.PROVIDER)
                                : estimated(prompt.getContents(), outputChars.get());
                        collector.record(new ModelCallUsage(UUID.randomUUID().toString(), requestId, taskId, stage,
                                roundSupplier.get(), model[0], values.input(), values.output(), values.total(),
                                values.source(), elapsed(start)));
                        budget.addTokens(values.total());
                    });
        });
    }

    @Override public ChatOptions getDefaultOptions() { return delegate.getDefaultOptions(); }

    private UsageValues usage(ChatResponse response, String input, String output) {
        if (response != null && response.getMetadata() != null) {
            Usage usage = response.getMetadata().getUsage();
            if (usage != null && value(usage.getTotalTokens()) > 0) {
                return new UsageValues(value(usage.getPromptTokens()), value(usage.getCompletionTokens()),
                        value(usage.getTotalTokens()), UsageSource.PROVIDER);
            }
        }
        return estimated(input, output == null ? 0 : output.length());
    }

    private UsageValues estimated(String input, long outputChars) {
        long in = estimate(input == null ? 0 : input.length());
        long out = estimate(outputChars);
        return new UsageValues(in, out, in + out, UsageSource.ESTIMATED);
    }

    private void record(ChatResponse response, UsageValues values, long start) {
        String model = response == null || response.getMetadata() == null ? null : response.getMetadata().getModel();
        collector.record(new ModelCallUsage(UUID.randomUUID().toString(), requestId, taskId, stage,
                roundSupplier.get(), model, values.input(), values.output(), values.total(), values.source(), elapsed(start)));
    }

    private String responseText(ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput().getText() == null
                ? "" : response.getResult().getOutput().getText();
    }
    private long estimate(long chars) { return chars == 0 ? 0 : Math.max(1, chars / 4); }
    private long value(Integer number) { return number == null ? 0 : number.longValue(); }
    private long max(long a, long b) { return Math.max(a, b); }
    private long elapsed(long start) { return (System.nanoTime() - start) / 1_000_000; }
    private record UsageValues(long input, long output, long total, UsageSource source) {}
}
