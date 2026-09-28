package com.leixs.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.leixs.agent.service.VectorSearchService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class InternalDocsTools {

    private static final Logger logger = LoggerFactory.getLogger(InternalDocsTools.class);

    public static final String TOOL_QUERY_INTERNAL_DOCS = "queryInternalDocs";

    private final VectorSearchService vectorSearchService;

    @Value("${rag.top-k:3}")
    private int topK = 3; // 默认值

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Tool(description = "Use this tool to search internal documentation and knowledge base for relevant information. " +
            "It performs RAG (Retrieval-Augmented Generation) to find similar documents and extract processing steps. " +
            "This is useful when you need to understand internal procedures, best practices, or step-by-step guides " +
            "stored in the company's documentation.")
    public String queryInternalDocs(@ToolParam(description = "Serch query describing what information you are looking for")
                                    String query) {
        try {
            List<VectorSearchService.SearchResult> searchResults = vectorSearchService.searchSimilarDocuments(query, topK);

            if (searchResults.isEmpty()) {
                return "{\"status\": \"no_results\", \"message\": \"No relevant documents found in the knowledge base.\"}";
            }

            String resultJson = objectMapper.writeValueAsString(searchResults);

            return resultJson;
        } catch (Exception e) {
            logger.error("[工具错误] queryInternalDocs 执行失败", e);
            return String.format("{\"status\": \"error\", \"message\": \"Failed to query internal docs: %s\"}",
                    e.getMessage());
        }
    }
}
