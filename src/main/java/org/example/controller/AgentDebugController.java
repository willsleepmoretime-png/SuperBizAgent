package org.example.controller;

import org.example.service.ChatService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AGENT_OPTIMIZATION: Agent 调试接口，用于查看工具 Router 对某个问题的路由结果。
 */
@RestController
@RequestMapping("/api/agent/debug")
public class AgentDebugController {

    @Autowired
    private ChatService chatService;

    @GetMapping("/route")
    public ResponseEntity<AgentRouteDebugResponse> route(@RequestParam String query) {
        Object[] methodTools = chatService.buildRoutedMethodToolsArray(query);
        return ResponseEntity.ok(new AgentRouteDebugResponse(
                query,
                chatService.getToolRouteSummary(query),
                methodTools.length
        ));
    }

    public record AgentRouteDebugResponse(
            String query,
            String routeSummary,
            int methodToolCount
    ) {
    }
}
