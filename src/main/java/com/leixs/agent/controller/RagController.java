package com.leixs.agent.controller;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.leixs.agent.service.RagService;
import com.leixs.agent.service.VectorSearchService;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 知识库检索 Controller
 * 提供纯 RAG 流式问答：只检索知识库 + 生成回答，不经过 Agent，不调用任何工具。
 * 复用 RagService（此前未被接线的 RAG 实现）。
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private static final Logger logger = LoggerFactory.getLogger(RagController.class);

    @Autowired
    private RagService ragService;

    private final ExecutorService executor = Executors.newFixedThreadPool(4);

    /**
     * 知识库检索（SSE 流式）
     * 流程：检索 topK 文档 → 组装提示词 → 模型增量生成 → 逐块推送给前端
     */
    @PostMapping(value = "/rag_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter ragStream(@RequestBody RagRequest request) {
        SseEmitter emitter = new SseEmitter(300000L); // 5 分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message")
                        .data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        // 丢进线程池执行（queryStream 内部 blockingForEach 是阻塞的）
        executor.execute(() -> {
            try {
                logger.info("收到知识库检索请求 - Question: {}", request.getQuestion());

                ragService.queryStream(request.getQuestion(), new RagService.StreamCallBack() {
                    @Override
                    public void onSearchResult(List<VectorSearchService.SearchResult> results) {
                        // 检索完成，先告知用户检索状态
                        try {
                            String prefix = results.isEmpty()
                                    ? "⚠️ 知识库中未检索到相关内容，请先上传文档。\n\n"
                                    : "📚 已检索到 " + results.size() + " 篇相关知识库资料：\n\n";
                            emitter.send(SseEmitter.event().name("message")
                                    .data(SseMessage.content(prefix), MediaType.APPLICATION_JSON));
                        } catch (IOException e) {
                            logger.error("发送检索状态消息失败", e);
                        }
                    }

                    @Override
                    public void onReasoningChunk(String chunk) {
                        // 预留：深度思考模型的推理分片，暂不推送
                    }

                    @Override
                    public void onContentChunk(String chunk) {
                        // 模型每生成一小块，立即推送给前端
                        try {
                            emitter.send(SseEmitter.event().name("message")
                                    .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                        } catch (IOException e) {
                            logger.error("发送流式内容失败", e);
                        }
                    }

                    @Override
                    public void onComplete(String fullContent, String fullReasoning) {
                        try {
                            emitter.send(SseEmitter.event().name("message")
                                    .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                            emitter.complete();
                        } catch (IOException e) {
                            logger.error("发送完成消息失败", e);
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onError(Exception e) {
                        logger.error("知识库检索失败", e);
                        try {
                            emitter.send(SseEmitter.event().name("message")
                                    .data(SseMessage.error("知识库检索失败：" + e.getMessage()), MediaType.APPLICATION_JSON));
                        } catch (IOException ex) {
                            logger.error("发送错误消息失败", ex);
                        }
                        emitter.complete();
                    }
                });
            } catch (Exception e) {
                logger.error("知识库检索初始化失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("知识库检索服务暂时不可用，请稍后重试"), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

    /**
     * 请求体：与 ChatController.ChatRequest 保持一致（前端可复用同一格式）
     */
    @Getter
    @Setter
    public static class RagRequest {

        @JsonProperty(value = "Id")
        @JsonAlias({"id", "ID"})
        private String Id;

        @JsonProperty(value = "Question")
        @JsonAlias({"question", "QUESTION"})
        private String Question;
    }

    /**
     * SSE 消息体：与 ChatController.SseMessage 保持一致（前端可复用同一解析逻辑）
     */
    @Getter
    @Setter
    public static class SseMessage {
        private String type;
        private String data;

        public static SseMessage content(String data) {
            SseMessage message = new SseMessage();
            message.setType("content");
            message.setData(data);
            return message;
        }

        public static SseMessage error(String errorMessage) {
            SseMessage message = new SseMessage();
            message.setType("error");
            message.setData(errorMessage);
            return message;
        }

        public static SseMessage done() {
            SseMessage message = new SseMessage();
            message.setType("done");
            message.setData(null);
            return message;
        }
    }
}
