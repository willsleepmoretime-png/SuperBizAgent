package org.example.dto.agent;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

/** Unified request for single-agent chat endpoints. */
public class AgentRequest {
    @JsonProperty("Id")
    @JsonAlias({"id", "ID", "requestId", "sessionId"})
    private String id;

    @JsonProperty("Question")
    @JsonAlias({"question", "QUESTION", "userRequest", "message"})
    private String question;

    private String mode;
    private String userId;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getQuestion() { return question; }
    public void setQuestion(String question) { this.question = question; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
}
