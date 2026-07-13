package org.example.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * 文档分片
 */
@Setter
@Getter
public class DocumentChunk {

    /** 跨重新入库保持稳定的业务标识。 */
    private String chunkId;

    /** 规范化文档标识，当前使用文件名。 */
    private String documentId;

    /** Markdown 完整标题路径，例如“排查步骤/步骤2-查询系统日志”。 */
    private String titlePath;

    /** 规范化内容的 SHA-256 前 12 位。 */
    private String contentHash;

    // Getters and Setters
    /**
     * 分片内容
     */
    private String content;
    
    /**
     * 分片在原文档中的起始位置
     */
    private int startIndex;
    
    /**
     * 分片在原文档中的结束位置
     */
    private int endIndex;
    
    /**
     * 分片序号（从0开始）
     */
    private int chunkIndex;
    
    /**
     * 分片标题或上下文信息
     */
    private String title;

    public DocumentChunk() {
    }

    public DocumentChunk(String content, int startIndex, int endIndex, int chunkIndex) {
        this.content = content;
        this.startIndex = startIndex;
        this.endIndex = endIndex;
        this.chunkIndex = chunkIndex;
    }

    @Override
    public String toString() {
        return "DocumentChunk{" +
                "chunkIndex=" + chunkIndex +
                ", chunkId='" + chunkId + '\'' +
                ", titlePath='" + titlePath + '\'' +
                ", title='" + title + '\'' +
                ", contentLength=" + (content != null ? content.length() : 0) +
                ", startIndex=" + startIndex +
                ", endIndex=" + endIndex +
                '}';
    }
}
