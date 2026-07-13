package org.example.memory.working;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "memory.working")
public class WorkingMemoryProperties {
    private String keyPrefix = "memory:conversation:v1";
    private int maxWindowPairs = 6;
    private int summaryMaxChars = 3000;
    private Duration ttl = Duration.ofDays(7);

    public String getKeyPrefix() { return keyPrefix; }
    public void setKeyPrefix(String keyPrefix) { this.keyPrefix = keyPrefix; }
    public int getMaxWindowPairs() { return maxWindowPairs; }
    public void setMaxWindowPairs(int maxWindowPairs) { this.maxWindowPairs = maxWindowPairs; }
    public int getSummaryMaxChars() { return summaryMaxChars; }
    public void setSummaryMaxChars(int summaryMaxChars) { this.summaryMaxChars = summaryMaxChars; }
    public Duration getTtl() { return ttl; }
    public void setTtl(Duration ttl) { this.ttl = ttl; }
}
