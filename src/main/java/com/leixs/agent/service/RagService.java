package com.leixs.agent.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.GeneralGetParam;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.InputRequiredException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.utils.Constants;
import io.reactivex.Flowable;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
public class RagService {

    private static final Logger logger = LoggerFactory.getLogger(RagService.class);

    @Autowired
    private VectorSearchService vectorSearchService;

    @Value("${dashscope.api.key}")
    private String apiKey;

    @Value("${rag.top-k}")
    private int topK;

    @Value("${rag.model}")
    private String model;

    private Generation generation;


    @PostConstruct
    public void init() {
        Constants.apiKey = apiKey;
        Constants.baseHttpApiUrl = "https://dashscope.aliyuncs.com/api/v1";

        generation = new Generation();

        logger.info("RAG 服务初始化完成，model: {}, topK: {}", model, topK);
    }


    /**
     * 流式处理用户问题（不带历史消息）
     *
     * @param question 用户问题
     * @param callBack 流式回调接口
     */
    public void queryStream(String question, StreamCallBack callBack) {
        queryStream(question, new ArrayList<>(), callBack);
    }

    /**
     * 流式处理用户问题（带历史消息）
     *
     * @param question 用户问题
     * @param history 历史消息列表，格式：[{"role": "user", "content": "..."}, {"role": "assistant", "content": "..."}]
     * @param callBack 流式回调接口
     */
    public void queryStream(String question, List<Map<String, String>> history, StreamCallBack callBack) {
        try {
            logger.info("收到 RAG 流式查询: {}", question);

            //向量相似度检索
            List<VectorSearchService.SearchResult> searchResults = vectorSearchService.searchSimilarDocuments(question, topK);

            callBack.onSearchResult(searchResults);

            if (searchResults.isEmpty()) {
                logger.warn("未找到相关文档");
                callBack.onComplete("抱歉，我在知识库中没有找到相关信息来回答您的问题。", "");
                return;
            }

            String context = buildContext(searchResults);
            String prompt = buildPrompt(question, context);

            generateAnswerStream(prompt, history, callBack);
        } catch (Exception e) {
            logger.error("RAG 流式查询失败", e);
            callBack.onError(e);
        }
    }

    private void generateAnswerStream(String prompt, List<Map<String, String>> history, StreamCallBack callBack)
        throws NoApiKeyException, ApiException, InputRequiredException {

        List<Message> messages = new ArrayList<>();

        for (Map<String, String> historyMsg : history) {
            String role = historyMsg.get("role");
            String content = historyMsg.get("content");

            if ("user".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.USER.getValue())
                        .content(content)
                        .build());
            } else if ("assistant".equals(role)) {
                messages.add(Message.builder()
                        .role(Role.ASSISTANT.getValue())
                        .content(content)
                        .build());
            }
        }

            Message userMsg = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();
        messages.add(userMsg);

        logger.debug("发送给AI模型的消息数量: {}（包含 {} 条历史消息）",
                messages.size(), history.size());

        GenerationParam param = GenerationParam.builder()
                .apiKey(apiKey)
                .model(model)
                .incrementalOutput(true)
                .resultFormat("message")
                .messages(messages)
                .build();

        logger.info("开始调用AI模型流式接口...");

        Flowable<GenerationResult> result = generation.streamCall(param);

        StringBuilder reasoningContent = new StringBuilder();
        StringBuilder finalContent = new StringBuilder();

        result.blockingForEach( message -> {
            if (message.getOutput() != null &&
                !message.getOutput().getChoices().isEmpty()) {
                String content = message.getOutput().getChoices().get(0).getMessage().getContent();

                if (content != null && !content.isEmpty()) {
                    logger.debug("收到AI模型内容块: {}", content);

                    finalContent.append(content);
                    callBack.onContentChunk(content);
                    logger.debug("已调用 onContentChunk 回调");
                } else {
                    logger.debug("收到空内容块，跳过");
                }
            }
        });

        logger.info("AI模型流式响应完成，总内容长度: {}", finalContent.length());

        callBack.onComplete(finalContent.toString(), reasoningContent.toString());
        logger.info("已调用 onComplete 回调");
    }

    private String buildPrompt(String question, String context) {
        return String.format(
                "你是一个专业的AI助手。请根据以下参考资料回答用户的问题。\n\n" +
                        "参考资料：\n%s\n" +
                        "用户问题：%s\n\n" +
                        "请基于上述参考资料给出准确、详细的回答。如果参考资料中没有相关信息，请明确说明。",
                context, question
        );
    }

    private String buildContext(List<VectorSearchService.SearchResult> searchResults) {
        StringBuilder context = new StringBuilder();

        for (int i = 0; i < searchResults.size(); i++) {
            VectorSearchService.SearchResult result = searchResults.get(i);
            context.append("【参考资料 ").append(i + 1).append("】\n");
            context.append(result.getContent()).append("\n\n");
        }
        return context.toString();
    }

    /**
     * 流式回调接口
     */
    public interface StreamCallBack {
        // 向量检索完成，返回检索到的知识库片段
        void onSearchResult(List<VectorSearchService.SearchResult> results);
        // 思考过程分片输出（本示例代码没有处理reasoning，预留接口，适配深度思考模型）
        void onReasoningChunk(String chunk);
        // 回答文本分片输出，流式一小块一小块返回
        void onContentChunk(String chunk);
        // 全部生成完成，返回完整回答、完整思考文本
        void onComplete(String fullContent, String fullReasoning);
        // 任意环节异常回调
        void onError(Exception e);
    }
}
