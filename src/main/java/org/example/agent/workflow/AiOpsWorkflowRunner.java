package org.example.agent.workflow;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.graph.exception.GraphRunnerException;
import org.example.dto.agent.AiOpsCommand;
import org.example.dto.agent.AiOpsResult;
import org.springframework.ai.tool.ToolCallback;

public interface AiOpsWorkflowRunner {
    AiOpsResult run(AiOpsCommand command, DashScopeChatModel model, ToolCallback[] callbacks)
            throws GraphRunnerException;

    default AiOpsResult run(AiOpsCommand command, DashScopeChatModel model, ToolCallback[] callbacks,
                            WorkflowEventListener listener) throws GraphRunnerException {
        return run(command, model, callbacks);
    }
}
