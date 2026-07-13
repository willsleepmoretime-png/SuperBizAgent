package org.example.service;

import org.example.config.DocumentChunkConfig;
import org.example.dto.DocumentChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 文档分片服务
 * 负责将长文档切分为多个有语义完整性的小片段
 */
@Service
public class DocumentChunkService {

    private static final Logger logger = LoggerFactory.getLogger(DocumentChunkService.class);

    private final DocumentChunkConfig chunkConfig;

    public DocumentChunkService(DocumentChunkConfig chunkConfig) {
        this.chunkConfig = chunkConfig;
    }

    /**
     * 智能分片文档
     * 优先按照标题、段落边界进行分割，保持语义完整性
     * 
     * @param content 文档内容
     * @param filePath 文件路径（用于日志）
     * @return 文档分片列表
     */
    public List<DocumentChunk> chunkDocument(String content, String filePath) {
        List<DocumentChunk> chunks = new ArrayList<>();

        //不为空 ，或是不全是空格
        if (content == null || content.trim().isEmpty()) {
            logger.warn("文档内容为空: {}", filePath);
            return chunks;
        }

        // 1. 首先尝试按标题分割（Markdown格式）
        List<Section> sections = splitByHeadings(content);
        
        // 2. 对每个章节进行进一步分片
        int globalChunkIndex = 0;
        for (Section section : sections) {
            List<DocumentChunk> sectionChunks = chunkSection(section, globalChunkIndex);
            chunks.addAll(sectionChunks);
            globalChunkIndex += sectionChunks.size();
        }

        String documentId = resolveDocumentId(filePath);
        for (DocumentChunk chunk : chunks) {
            populateStableIdentity(chunk, documentId);
        }

        logger.info("文档分片完成: {} -> {} 个分片", filePath, chunks.size());
        return chunks;
    }

    /**
     * 按照 Markdown 标题分割文档
     */
    private List<Section> splitByHeadings(String content) {
        List<Section> sections = new ArrayList<>();
        Pattern headingPattern = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
        List<String> headingStack = new ArrayList<>();
        String currentTitle = null;
        String currentTitlePath = "";
        StringBuilder sectionContent = new StringBuilder();
        int sectionStart = 0;
        int offset = 0;
        boolean inFence = false;

        for (String lineWithEnding : content.split("(?<=\\n)", -1)) {
            String line = lineWithEnding.replaceFirst("[\\r\\n]+$", "");
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                inFence = !inFence;
                sectionContent.append(lineWithEnding);
                offset += lineWithEnding.length();
                continue;
            }

            Matcher heading = headingPattern.matcher(line);
            if (!inFence && heading.matches()) {
                addSectionIfPresent(sections, currentTitle, currentTitlePath, sectionContent, sectionStart);
                int level = heading.group(1).length();
                while (headingStack.size() >= level) {
                    headingStack.remove(headingStack.size() - 1);
                }
                currentTitle = heading.group(2).trim();
                headingStack.add(currentTitle);
                currentTitlePath = String.join("/", headingStack);
                sectionContent = new StringBuilder();
                sectionStart = offset + lineWithEnding.length();
            } else {
                sectionContent.append(lineWithEnding);
            }
            offset += lineWithEnding.length();
        }
        addSectionIfPresent(sections, currentTitle, currentTitlePath, sectionContent, sectionStart);

        // 如果没有找到任何标题，将整个文档作为一个章节
        if (sections.isEmpty()) {
            sections.add(new Section(null, "", content, 0));
        }

        return sections;
    }

    private void addSectionIfPresent(List<Section> sections, String title, String titlePath,
                                     StringBuilder rawContent, int startIndex) {
        String sectionContent = rawContent.toString().trim();
        if (!sectionContent.isEmpty()) {
            sections.add(new Section(title, titlePath, sectionContent, startIndex));
        }
    }

    /**
     * 对单个章节进行分片
     */
    private List<DocumentChunk> chunkSection(Section section, int startChunkIndex) {
        List<DocumentChunk> chunks = new ArrayList<>();
        String content = section.content;
        String title = section.title;

        // 如果章节内容小于最大尺寸，直接作为一个分片
        if (content.length() <= chunkConfig.getMaxSize()) {
            // RAG_OPTIMIZATION: 分片内容中显式带上标题上下文，提升标题类问题和章节语义的召回率。
            String chunkContent = enrichChunkContent(title, content);
            DocumentChunk chunk = new DocumentChunk(
                chunkContent,
                section.startIndex, 
                section.startIndex + content.length(), 
                startChunkIndex
            );
            chunk.setTitle(title);
            chunk.setTitlePath(section.titlePath);
            chunks.add(chunk);
            return chunks;
        }

        // 章节内容较长，需要进一步分片
        // 优先在段落边界分割
        List<String> paragraphs = splitByParagraphs(content);
        
        StringBuilder currentChunk = new StringBuilder();
        int currentStartIndex = section.startIndex;
        int chunkIndex = startChunkIndex;

        for (String paragraph : paragraphs) {
            // 如果当前分片加上新段落超过最大尺寸
            if (currentChunk.length() > 0 && 
                currentChunk.length() + paragraph.length() > chunkConfig.getMaxSize()) {
                
                // 保存当前分片
                String chunkContent = enrichChunkContent(title, currentChunk.toString().trim());
                DocumentChunk chunk = new DocumentChunk(
                    chunkContent,
                    currentStartIndex,
                    currentStartIndex + chunkContent.length(),
                    chunkIndex++
                );
                chunk.setTitle(title);
                chunk.setTitlePath(section.titlePath);
                chunks.add(chunk);

                // 开始新分片，包含重叠部分
                String overlap = getOverlapText(chunkContent);
                currentChunk = new StringBuilder(overlap);
                currentStartIndex = currentStartIndex + chunkContent.length() - overlap.length();
            }

            currentChunk.append(paragraph).append("\n\n");
        }

        // 保存最后一个分片
        if (currentChunk.length() > 0) {
            String chunkContent = enrichChunkContent(title, currentChunk.toString().trim());
            DocumentChunk chunk = new DocumentChunk(
                chunkContent,
                currentStartIndex,
                currentStartIndex + chunkContent.length(),
                chunkIndex
            );
            chunk.setTitle(title);
            chunk.setTitlePath(section.titlePath);
            chunks.add(chunk);
        }

        return chunks;
    }

    private String enrichChunkContent(String title, String content) {
        if (title == null || title.isBlank()) {
            return content;
        }
        return "标题: " + title.trim() + "\n\n" + content;
    }

    private void populateStableIdentity(DocumentChunk chunk, String documentId) {
        String normalizedContent = chunk.getContent() == null
                ? ""
                : chunk.getContent().replaceAll("\\s+", " ").trim();
        String contentHash = sha256Prefix(normalizedContent, 12);
        String titlePath = chunk.getTitlePath() == null ? "" : chunk.getTitlePath().trim();
        chunk.setDocumentId(documentId);
        chunk.setContentHash(contentHash);
        chunk.setChunkId(documentId + "::" + titlePath + "::" + contentHash);
    }

    private String resolveDocumentId(String filePath) {
        if (filePath == null || filePath.isBlank()) {
            return "unknown-document";
        }
        Path fileName = Paths.get(filePath).normalize().getFileName();
        return fileName == null ? filePath.replace('\\', '/') : fileName.toString();
    }

    private String sha256Prefix(String value, int length) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.substring(0, Math.min(length, hex.length()));
        } catch (Exception e) {
            throw new IllegalStateException("生成 chunk 内容哈希失败", e);
        }
    }

    /**
     * 按段落分割文本
     */
    private List<String> splitByParagraphs(String content) {
        List<String> paragraphs = new ArrayList<>();
        
        // 按双换行符分割段落
        String[] parts = content.split("\n\n+");
        for (String part : parts) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                paragraphs.add(trimmed);
            }
        }

        return paragraphs;
    }

    /**
     * 获取重叠文本
     * 从文本末尾提取指定长度的内容作为下一个分片的开头
     */
        private String getOverlapText(String text) {
            int overlapSize = Math.min(chunkConfig.getOverlap(), text.length());
            if (overlapSize <= 0) {
                return "";
            }

            // 从末尾提取重叠内容
            String overlap = text.substring(text.length() - overlapSize);

            // 尝试在句子边界截断（查找最后一个句号、问号、感叹号）
            int lastSentenceEnd = Math.max(
                overlap.lastIndexOf('。'),
                Math.max(overlap.lastIndexOf('？'), overlap.lastIndexOf('！'))
            );

            if (lastSentenceEnd > overlapSize / 2) {
                return overlap.substring(lastSentenceEnd + 1).trim();
            }

            return overlap.trim();
        }

    /**
     * 章节数据类
     */
    private static class Section {
        String title;
        String titlePath;
        String content;
        int startIndex;

        Section(String title, String titlePath, String content, int startIndex) {
            this.title = title;
            this.titlePath = titlePath;
            this.content = content;
            this.startIndex = startIndex;
        }
    }
}
