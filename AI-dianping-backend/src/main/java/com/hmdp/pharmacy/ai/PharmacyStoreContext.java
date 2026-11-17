package com.hmdp.pharmacy.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class PharmacyStoreContext {
    private static final ThreadLocal<Long> STORE_ID = new ThreadLocal<>();
    private static final ThreadLocal<List<String>> TOOL_CALLS = new ThreadLocal<>();
    private static final ThreadLocal<List<ToolCall>> TOOL_CALL_DETAILS = new ThreadLocal<>();

    private PharmacyStoreContext() { }

    public static Scope bind(long storeId) {
        Long previous = STORE_ID.get();
        List<String> previousCalls = TOOL_CALLS.get();
        List<ToolCall> previousDetails = TOOL_CALL_DETAILS.get();
        STORE_ID.set(storeId);
        TOOL_CALLS.set(new ArrayList<>());
        TOOL_CALL_DETAILS.set(new ArrayList<>());
        return () -> {
            if (previous == null) STORE_ID.remove();
            else STORE_ID.set(previous);
            if (previousCalls == null) TOOL_CALLS.remove();
            else TOOL_CALLS.set(previousCalls);
            if (previousDetails == null) TOOL_CALL_DETAILS.remove();
            else TOOL_CALL_DETAILS.set(previousDetails);
        };
    }

    public static long currentStoreId() {
        Long storeId = STORE_ID.get();
        if (storeId == null) throw new SecurityException("药房工具没有可信门店上下文");
        return storeId;
    }

    public static void recordToolCall(String toolName, Map<String, Object> parameters) {
        List<String> calls = TOOL_CALLS.get();
        if (calls != null) {
            calls.add(toolName);
            TOOL_CALL_DETAILS.get().add(new ToolCall(toolName, Map.copyOf(parameters)));
        }
    }

    public static List<String> currentToolCalls() {
        List<String> calls = TOOL_CALLS.get();
        return calls == null ? List.of() : List.copyOf(calls);
    }

    public static List<ToolCall> currentToolCallDetails() {
        List<ToolCall> calls = TOOL_CALL_DETAILS.get();
        return calls == null ? List.of() : List.copyOf(calls);
    }

    public record ToolCall(String name, Map<String, Object> parameters) { }

    @FunctionalInterface
    public interface Scope extends AutoCloseable {
        @Override void close();
    }
}
