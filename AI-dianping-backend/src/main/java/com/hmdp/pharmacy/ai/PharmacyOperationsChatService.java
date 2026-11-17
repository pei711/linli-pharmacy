package com.hmdp.pharmacy.ai;

import com.hmdp.dto.UserDTO;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class PharmacyOperationsChatService {
    private final PharmacyOperationsService operations;
    private final PharmacyOperationsAgent agent;
    private final String apiKey;

    public PharmacyOperationsChatService(PharmacyOperationsService operations,
                                         PharmacyOperationsAgent agent,
                                         @Value("${AI_API_KEY:demo-key}") String apiKey) {
        this.operations = operations;
        this.agent = agent;
        this.apiKey = apiKey;
    }

    public ChatReply chat(String message) {
        return chat(message, null);
    }

    public ChatReply chat(String message, String conversationId) {
        UserDTO user = UserHolder.getUser();
        if (user == null || user.getId() == null) throw new SecurityException("请先登录");
        if (message == null || message.isBlank() || message.length() > 2000) {
            throw new IllegalArgumentException("问题不能为空且不能超过 2,000 个字符");
        }
        if (conversationId != null && !conversationId.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalArgumentException("conversationId 只能包含字母、数字、下划线或连字符，且不超过 64 个字符");
        }
        long storeId = operations.resolveStoreForUser(user.getId());
        if (apiKey == null || apiKey.isBlank() || "demo-key".equalsIgnoreCase(apiKey.trim())) {
            return new ChatReply(operations.deterministicSummary(storeId), "RULES_FALLBACK", java.util.List.of(),
                    java.util.List.of());
        }
        try (PharmacyStoreContext.Scope ignored = PharmacyStoreContext.bind(storeId)) {
            String memoryId = "neighbor-pharmacy:ops-chat:" + storeId + ":" + user.getId()
                    + (conversationId == null ? "" : ":" + conversationId);
            String answer = agent.chat(memoryId, message);
            return new ChatReply(answer, "LANGCHAIN4J_AGENT", PharmacyStoreContext.currentToolCalls(),
                    PharmacyStoreContext.currentToolCallDetails());
        } catch (RuntimeException failure) {
            log.warn("药房经营 Agent 调用失败，回退到规则摘要 storeId={} cause={}",
                    storeId, failure.getClass().getSimpleName());
            return new ChatReply(operations.deterministicSummary(storeId), "RULES_FALLBACK", java.util.List.of(),
                    java.util.List.of());
        }
    }

    public record ChatReply(String answer, String source, java.util.List<String> toolCalls,
                            java.util.List<PharmacyStoreContext.ToolCall> toolCallDetails) { }
}
