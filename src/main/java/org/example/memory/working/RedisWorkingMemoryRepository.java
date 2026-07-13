package org.example.memory.working;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;

@Repository
public class RedisWorkingMemoryRepository implements WorkingMemoryRepository {
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final WorkingMemoryProperties properties;

    public RedisWorkingMemoryRepository(StringRedisTemplate redisTemplate,
                                        ObjectMapper objectMapper,
                                        WorkingMemoryProperties properties) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public Optional<WorkingMemory> find(WorkingMemoryScope scope) {
        String json = redisTemplate.opsForValue().get(key(scope));
        if (json == null || json.isBlank()) return Optional.empty();
        try {
            return Optional.of(objectMapper.readValue(json, WorkingMemory.class));
        } catch (Exception e) {
            throw new IllegalStateException("无法读取 Redis 会话记忆", e);
        }
    }

    @Override
    public void save(WorkingMemoryScope scope, WorkingMemory memory) {
        try {
            redisTemplate.opsForValue().set(key(scope), objectMapper.writeValueAsString(memory), properties.getTtl());
        } catch (Exception e) {
            throw new IllegalStateException("无法保存 Redis 会话记忆", e);
        }
    }

    @Override
    public boolean delete(WorkingMemoryScope scope) {
        return Boolean.TRUE.equals(redisTemplate.delete(key(scope)));
    }

    String key(WorkingMemoryScope scope) {
        return properties.getKeyPrefix() + ":" + hash(scope.userId()) + ":" + hash(scope.sessionId());
    }

    private String hash(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("当前 JVM 不支持 SHA-256", e);
        }
    }
}
