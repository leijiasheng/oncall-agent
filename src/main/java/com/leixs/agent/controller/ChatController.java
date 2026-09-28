package com.leixs.agent.controller;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.alibaba.cloud.ai.graph.NodeOutput;
import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.streaming.OutputType;
import com.alibaba.cloud.ai.graph.streaming.StreamingOutput;
import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.leixs.agent.service.AiOpsService;
import com.leixs.agent.service.ChatService;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.ReentrantLock;

@RestController
@RequestMapping("/api")
public class ChatController {

    private static final Logger logger = LoggerFactory.getLogger(ChatController.class);

    @Autowired
    private AiOpsService aiOpsService;

    @Autowired
    private ChatService chatService;

    @Autowired
    private ToolCallbackProvider tools;

    private final ExecutorService executor = Executors.newFixedThreadPool(8);

    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    private static final int MAX_WINDOW_SIZE = 6;

    @PostMapping("/chat")
    public ResponseEntity<ApiResponse<ChatResponse>> chat(@RequestBody ChatRequest request) {
        try {

            logger.info("收到对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

            if (request.getId() == null || request.getQuestion().trim().isEmpty()) {
                logger.warn("问题内容为空");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST) //状态码400
                        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "问题内容不能为空"));
            }

            SessionInfo session = getOrCreateSession(request.getId());

            List<Map<String, String>> history = session.getHistory();
            logger.info("会话历史消息对数: {}", history.size() / 2);

            // 创建 DashScope API 和 ChatModel
            DashScopeApi dashScopeApi = chatService.createDashScopeApi();
            DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

            chatService.logAvailableTools();

            logger.info("开始 ReactAgent 对话（支持自动工具调用）");

            String systemPrompt = chatService.buildSystemPrompt(history);

            ReactAgent reactAgent = chatService.createReactAgent(chatModel, systemPrompt);

            String fullAnswer = chatService.executeChat(reactAgent, request.getQuestion());

            session.addMessage(request.getQuestion(), fullAnswer);
            logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                    request.getId(), session.getMessagePairCount());

            return ResponseEntity.ok(ApiResponse.success(ChatResponse.success(fullAnswer)));
        } catch (Exception e) {
            logger.error("对话失败", e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), "对话服务暂时不可用，请稍后重试"));
        }
    }

    /**
     * 清空会话历史
     */
    @PostMapping("/chat/clear")
    public ResponseEntity<ApiResponse<String>> clearChatHistory(@RequestBody ChatRequest request) {
        try {
            logger.info("收到清空会话历史请求 - SessionId: {}", request.getId());

            if (request.getId() == null || request.getId().isEmpty()) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error(HttpStatus.BAD_REQUEST.value(), "会话ID不能为空"));
            }

            SessionInfo session = sessions.get(request.getId());
            if (session != null) {
                session.clearHistory();
                return ResponseEntity.ok(ApiResponse.success("会话历史已清空"));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), "会话不存在"));
            }
        } catch (Exception e) {
            logger.error("清空会话历史失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "清空会话失败，请稍后重试"));
        }
    }

    //响应头设置为 SSE 协议类型，用于服务端向前端持续推送流式事件，字符集 UTF‑8
    @PostMapping(value = "/chat_stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter chatStream(@RequestBody ChatRequest request) {

        //创建 SseEmitter 长连接
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 参数校验
        if (request.getQuestion() == null || request.getQuestion().trim().isEmpty()) {
            logger.warn("问题内容为空");
            try {
                emitter.send(SseEmitter.event().name("message").data(SseMessage.error("问题内容不能为空"), MediaType.APPLICATION_JSON));
                emitter.complete();
            } catch (IOException e) {
                emitter.completeWithError(e);
            }
            return emitter;
        }

        //丢进线程池异步执行 `executor.execute()`
        executor.execute(() -> {
            try {
                logger.info("收到 ReactAgent 对话请求 - SessionId: {}, Question: {}", request.getId(), request.getQuestion());

                // 获取或创建会话
                SessionInfo session = getOrCreateSession(request.getId());

                // 获取历史消息
                List<Map<String, String>> history = session.getHistory();
                logger.info("ReactAgent 会话历史消息对数: {}", history.size() / 2);

                // 创建 DashScope API 和 ChatModel
                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = chatService.createStandardChatModel(dashScopeApi);

                // 记录可用工具
                chatService.logAvailableTools();

                logger.info("开始 ReactAgent 流式对话（支持自动工具调用）");

                // 构建系统提示词（包含历史消息）
                String systemPrompt = chatService.buildSystemPrompt(history);

                // 创建 ReactAgent
                ReactAgent agent = chatService.createReactAgent(chatModel, systemPrompt);

                // 用于累积完整答案
                StringBuilder fullAnswerBuilder = new StringBuilder();

                // 使用 agent.stream() 进行流式对话
                Flux<NodeOutput> stream = agent.stream(request.getQuestion());

                //`stream.subscribe( onNext,onError,onComplete )` 订阅数据流，三件回调
                //onNext：每收到一条分片就执行
                //onError：整个 Agent 流程抛出异常
                //onComplete：✅Agent 全部流程跑完，数据流结束（重要）
                stream.subscribe(
                        output -> {
                            try {
                                // 检查是否为 StreamingOutput 类型
                                if (output instanceof StreamingOutput streamingOutput) {
                                    OutputType type = streamingOutput.getOutputType();

                                    // 处理模型推理的流式输出
                                    if (type == OutputType.AGENT_MODEL_STREAMING) {
                                        // 流式增量内容，逐步显示
                                        String chunk = streamingOutput.message().getText();
                                        if (chunk != null && !chunk.isEmpty()) {
                                            fullAnswerBuilder.append(chunk);

                                            // 实时发送到前端
                                            emitter.send(SseEmitter.event()
                                                    .name("message")
                                                    .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));

                                            logger.info("发送流式内容: {}", chunk);
                                        }
                                    } else if (type == OutputType.AGENT_MODEL_FINISHED) {
                                        // 模型推理完成
                                        logger.info("模型输出完成");
                                    } else if (type == OutputType.AGENT_TOOL_FINISHED) {
                                        // 工具调用完成
                                        logger.info("工具调用完成: {}", output.node());
                                    } else if (type == OutputType.AGENT_HOOK_FINISHED) {
                                        // Hook 执行完成
                                        logger.debug("Hook 执行完成: {}", output.node());
                                    }
                                }
                            } catch (IOException e) {
                                logger.error("发送流式消息失败", e);
                                throw new RuntimeException(e);
                            }
                        },
                        error -> {
                            // 错误处理
                            logger.error("ReactAgent 流式对话失败", error);
                            try {
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.error("流式对话服务暂时不可用，请稍后重试"), MediaType.APPLICATION_JSON));
                            } catch (IOException ex) {
                                logger.error("发送错误消息失败", ex);
                            }
                            emitter.completeWithError(error);
                        },
                        () -> {
                            // 完成处理
                            try {
                                String fullAnswer = fullAnswerBuilder.toString();
                                logger.info("ReactAgent 流式对话完成 - SessionId: {}, 答案长度: {}",
                                        request.getId(), fullAnswer.length());

                                // 更新会话历史
                                session.addMessage(request.getQuestion(), fullAnswer);
                                logger.info("已更新会话历史 - SessionId: {}, 当前消息对数: {}",
                                        request.getId(), session.getMessagePairCount());

                                // 发送完成标记
                                emitter.send(SseEmitter.event()
                                        .name("message")
                                        .data(SseMessage.done(), MediaType.APPLICATION_JSON));
                                emitter.complete();
                            } catch (IOException e) {
                                logger.error("发送完成消息失败", e);
                                emitter.completeWithError(e);
                            }
                        }
                );

            } catch (Exception e) {
                logger.error("ReactAgent 对话初始化失败", e);
                try {
                    emitter.send(SseEmitter.event()
                            .name("message")
                            .data(SseMessage.error("流式对话服务暂时不可用，请稍后重试"), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }


    /**
     * AI 智能运维接口（SSE 流式模式）- 自动分析告警并生成运维报告
     * 无需用户输入，自动执行告警分析流程
     */
    @PostMapping(value = "/ai_ops", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter aiOps() {
        SseEmitter emitter = new SseEmitter(600000L); // 10分钟超时（告警分析可能较慢）

        executor.execute(() -> {
            try {
                logger.info("收到 AI 智能运维请求 - 启动多 Agent 协作流程");

                DashScopeApi dashScopeApi = chatService.createDashScopeApi();
                DashScopeChatModel chatModel = DashScopeChatModel.builder()
                        .dashScopeApi(dashScopeApi)
                        .defaultOptions(DashScopeChatOptions.builder()
                                .model(DashScopeChatModel.DEFAULT_MODEL_NAME)
                                .temperature(0.3)
                                .maxToken(8000)
                                .topP(0.9)
                                .build())
                        .build();

                ToolCallback[] toolCallbacks = tools.getToolCallbacks();

                emitter.send(SseEmitter.event().name("message").data(SseMessage.content("正在读取告警并排解任务...\n")));

                Optional<OverAllState> overAllStateOptional = aiOpsService.executeAiOpsAnalysis(chatModel, toolCallbacks);

                if (overAllStateOptional.isEmpty()) {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("多 Agent 编排未获取到有效结果"), MediaType.APPLICATION_JSON));
                    emitter.complete();
                    return;
                }

                OverAllState state = overAllStateOptional.get();
                logger.info("AI Ops 编排完成，开始提取最终报告...");

                Optional<String> finalReportOptional = aiOpsService.extractFinalReport(state);

                if (finalReportOptional.isPresent()) {
                    String finalReportText = finalReportOptional.get();
                    logger.info("提取到 Planner 最终报告，长度: {}", finalReportText.length());

                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("📋 **告警分析报告**\n\n"), MediaType.APPLICATION_JSON));

                    int chunkSize = 50;
                    for (int i = 0; i < finalReportText.length(); i  += chunkSize) {
                        int end = Math.min(i + chunkSize, finalReportText.length());
                        String chunk = finalReportText.substring(i, end);

                        emitter.send(SseEmitter.event().name("message")
                                .data(SseMessage.content(chunk), MediaType.APPLICATION_JSON));
                    }

                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("\n" + "=".repeat(60) + "\n\n"), MediaType.APPLICATION_JSON));

                    logger.info("最终报告已完整输出");
                } else {
                    logger.warn("未能提取到 Planner 最终报告");
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.content("⚠️ 多 Agent 流程已完成，但未能生成最终报告。"), MediaType.APPLICATION_JSON));
                }

                emitter.send(SseEmitter.event().name("message").data(SseMessage.done(), MediaType.APPLICATION_JSON));
                emitter.complete();
                logger.info("AI Ops 多 Agent 编排完成");
            } catch (Exception e) {
                logger.error("AI Ops 多 Agent 协作失败", e);
                try {
                    emitter.send(SseEmitter.event().name("message")
                            .data(SseMessage.error("AI Ops 流程暂时不可用，请稍后重试"), MediaType.APPLICATION_JSON));
                } catch (IOException ex) {
                    logger.error("发送错误消息失败", ex);
                }
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }


    @GetMapping("/chat/session/{sessionId}")
    public ResponseEntity<ApiResponse<SessionInfoResponse>> getSessionInfo(@PathVariable String sessionId) {
        try {
            logger.info("收到获取会话信息请求 - SessionId: {}", sessionId);

            SessionInfo session = sessions.get(sessionId);

            if (session != null) {
                SessionInfoResponse response = new SessionInfoResponse();
                response.setSessionId(sessionId);
                response.setMessagePairCount(session.getMessagePairCount());
                response.setCreateTime(session.createTime);

                return ResponseEntity.ok(ApiResponse.success(response));
            } else {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error(HttpStatus.NOT_FOUND.value(), "会话不存在"));
            }

        } catch (Exception e) {
            logger.error("获取会话信息失败", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ApiResponse.error(HttpStatus.INTERNAL_SERVER_ERROR.value(), "获取会话信息失败，请稍后重试"));
        }
    }


    /**
     * 会话信息响应
     */
    @Setter
    @Getter
    public static class SessionInfoResponse {
        private String sessionId;
        private int messagePairCount;
        private long createTime;
    }

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

    private SessionInfo getOrCreateSession(String sessionId) {
        if (sessionId == null || sessionId.isEmpty()) {
            sessionId = UUID.randomUUID().toString();
        }
        return sessions.computeIfAbsent(sessionId, SessionInfo::new);
    }

    /**
     * 统一聊天响应格式
     * 适用于所有普通返回模式的对话接口
     */
    @Setter
    @Getter
    public static class ChatResponse {
        private boolean success;
        private String answer;
        private String errorMessage;

        public static ChatResponse success(String answer) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(true);
            response.setAnswer(answer);
            return response;
        }

        public static ChatResponse error(String errorMessage) {
            ChatResponse response = new ChatResponse();
            response.setSuccess(false);
            response.setErrorMessage(errorMessage);
            return response;
        }
    }

    @Getter
    @Setter
    public static class ChatRequest {

        @JsonProperty(value = "Id")
        @JsonAlias({"id", "ID"})
        private String Id;

        @JsonProperty(value = "Question")
        @JsonAlias({"question", "QUESTION"})
        private String Question;
    }

    @Getter
    @Setter
    public static class ApiResponse<T> {
        private int code;
        private String message;
        private T data;

        public static <T> ApiResponse<T> success(T data) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(200);
            response.setMessage("success");
            response.setData(data);
            return response;
        }

        public static <T> ApiResponse<T> error(String message) {
            return error(HttpStatus.INTERNAL_SERVER_ERROR.value(), message);
        }

        public static <T> ApiResponse<T> error(int code, String message) {
            ApiResponse<T> response = new ApiResponse<>();
            response.setCode(code);
            response.setMessage(message);
            return response;
        }

    }


    private static class SessionInfo {
        private final String sessionId;
        private final List<Map<String, String>> messageHistory;
        private final long createTime;
        private final ReentrantLock lock;

        public SessionInfo(String sessionId) {
            this.sessionId = sessionId;
            this.messageHistory = new ArrayList<>();
            this.createTime = System.currentTimeMillis();
            this.lock = new ReentrantLock();
        }

        /**
         * 添加一对消息（用户问题 + AI回复）
         * 自动管理历史消息窗口大小
         */
        public void addMessage(String userQuestion, String aiAnswer) {
            lock.lock();
            try {
                // 添加用户消息
                Map<String, String> userMsg = new HashMap<>();
                userMsg.put("role", "user");
                userMsg.put("content", userQuestion);
                messageHistory.add(userMsg);

                // 添加AI回复
                Map<String, String> assistantMsg = new HashMap<>();
                assistantMsg.put("role", "assistant");
                assistantMsg.put("content", aiAnswer);
                messageHistory.add(assistantMsg);

                // 自动清理：保持最多 MAX_WINDOW_SIZE 对消息
                // 每对消息包含2条记录（user + assistant）
                int maxMessages = MAX_WINDOW_SIZE * 2;
                while (messageHistory.size() > maxMessages) {
                    // 成对删除最旧的消息（删除前2条）
                    messageHistory.remove(0); // 删除最旧的用户消息
                    if (!messageHistory.isEmpty()) {
                        messageHistory.remove(0); // 删除对应的AI回复
                    }
                }
                logger.debug("会话 {} 更新历史消息，当前消息对数: {}",
                        sessionId, messageHistory.size() / 2);
            } finally {
                lock.unlock();
            }
        }

        public List<Map<String, String>> getHistory() {
            lock.lock();
            try {
                return new ArrayList<>(messageHistory);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 清空历史消息
         */
        public void clearHistory() {
            lock.lock();
            try {
                messageHistory.clear();
                logger.info("会话 {} 历史消息已清空", sessionId);
            } finally {
                lock.unlock();
            }
        }

        /**
         * 获取当前消息对数
         */
        public int getMessagePairCount() {
            lock.lock();
            try {
                return messageHistory.size() / 2;
            } finally {
                lock.unlock();
            }
        }
    }
}
