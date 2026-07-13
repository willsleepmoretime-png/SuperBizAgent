package org.example.service;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 无外部依赖的中英文基础分词：英文/数字保留术语 token，连续中文生成单字和二元组。
 */
@Component
public class BasicMixedLanguageTokenizer {

    public List<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        List<String> tokens = new ArrayList<>();
        StringBuilder latin = new StringBuilder();
        StringBuilder han = new StringBuilder();

        for (int i = 0; i < text.length(); i++) {
            char current = Character.toLowerCase(text.charAt(i));
            if (isHan(current)) {
                flushLatin(tokens, latin);
                han.append(current);
            } else if (Character.isLetterOrDigit(current) || current == '_' || current == '-' || current == '.') {
                flushHan(tokens, han);
                latin.append(current);
            } else {
                flushLatin(tokens, latin);
                flushHan(tokens, han);
            }
        }
        flushLatin(tokens, latin);
        flushHan(tokens, han);
        return tokens;
    }

    private void flushLatin(List<String> tokens, StringBuilder value) {
        if (!value.isEmpty()) {
            tokens.add(value.toString().toLowerCase(Locale.ROOT));
            value.setLength(0);
        }
    }

    private void flushHan(List<String> tokens, StringBuilder value) {
        if (value.isEmpty()) {
            return;
        }
        for (int i = 0; i < value.length(); i++) {
            tokens.add(String.valueOf(value.charAt(i)));
            if (i + 1 < value.length()) {
                tokens.add(value.substring(i, i + 2));
            }
        }
        value.setLength(0);
    }

    private boolean isHan(char value) {
        return Character.UnicodeScript.of(value) == Character.UnicodeScript.HAN;
    }
}
