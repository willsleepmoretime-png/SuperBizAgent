package org.example.agent.routing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.example.agent.budget.AgentBudget;
import org.example.agent.budget.BudgetedChatModel;
import org.example.agent.budget.BudgetExceededException;
import org.example.agent.model.AgentModelFactory;
import org.example.agent.observability.AgentStage;
import org.example.agent.observability.TokenUsageCollector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

@Component
public class LlmIntentClassifier {
    private static final Logger logger = LoggerFactory.getLogger(LlmIntentClassifier.class);
    private final AgentModelFactory modelFactory;
    private final ObjectMapper objectMapper;

    public LlmIntentClassifier(AgentModelFactory modelFactory, ObjectMapper objectMapper) {
        this.modelFactory = modelFactory;
        this.objectMapper = objectMapper;
    }

    public RouteDecision classify(String question, AgentBudget budget) {
        return classify(question, budget, new TokenUsageCollector(), null);
    }

    public RouteDecision classify(String question, AgentBudget budget, TokenUsageCollector collector, String requestId) {
        String prompt = """
                你是意图分类器，只输出一个 JSON 对象，不要输出 Markdown。
                executionMode 只能是 SINGLE_AGENT 或 AIOPS_WORKFLOW。
                intent 只能是 GENERAL_CHAT、DATE_TIME、DOCUMENT_QA、OBSERVABILITY_QUERY、INCIDENT_INVESTIGATION。
                只有需要多轮规划、组合多个证据源并输出根因报告时才选择 AIOPS_WORKFLOW；普通聊天、单次文档/日志/指标查询选择 SINGLE_AGENT。
                capabilities 是 DATE_TIME、INTERNAL_DOCS、METRICS、LOGS 的数组。
                score 是分类器自评得分，仅用于观察；requiresClarification 表示是否必须澄清。
                格式：{"executionMode":"SINGLE_AGENT","intent":"GENERAL_CHAT","capabilities":[],"score":0.8,"requiresClarification":false,"reason":"..."}
                用户请求：
                """ + question;
        try {
            ChatResponse response = new BudgetedChatModel(modelFactory.classifierModel(), budget, collector,
                    AgentStage.ROUTER, requestId, null, () -> null)
                    .call(new Prompt(prompt));
            String text = response.getResult().getOutput().getText();
            return parse(text);
        } catch (BudgetExceededException e) {
            throw e;
        } catch (Exception e) {
            logger.warn("LLM intent classification failed; falling back to safe chat route: {}", e.getMessage());
            return new RouteDecision(ExecutionMode.SINGLE_AGENT, IntentType.GENERAL_CHAT, Set.of(),
                    RouteSource.FALLBACK, null, false, "LLM classifier unavailable; safe fallback");
        }
    }

    RouteDecision parse(String raw) throws Exception {
        String json = raw == null ? "" : raw.trim();
        if (json.startsWith("```")) {
            json = json.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        }
        JsonNode root = objectMapper.readTree(json);
        ExecutionMode execution = ExecutionMode.valueOf(root.path("executionMode").asText("SINGLE_AGENT").toUpperCase(Locale.ROOT));
        IntentType intent = IntentType.valueOf(root.path("intent").asText("GENERAL_CHAT").toUpperCase(Locale.ROOT));
        Set<ToolCapability> capabilities = EnumSet.noneOf(ToolCapability.class);
        for (JsonNode item : root.path("capabilities")) {
            capabilities.add(ToolCapability.valueOf(item.asText().toUpperCase(Locale.ROOT)));
        }
        double score = Math.max(0.0, Math.min(1.0, root.path("score").asDouble(0.5)));
        return new RouteDecision(execution, intent, capabilities, RouteSource.LLM, score,
                root.path("requiresClarification").asBoolean(false), root.path("reason").asText("Classified by LLM"));
    }
}
