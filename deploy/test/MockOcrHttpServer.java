import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

public final class MockOcrHttpServer {
    private MockOcrHttpServer() { }

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(8090), 8);
        server.createContext("/health", exchange -> respond(exchange, 200, "{\"status\":\"ok\"}"));
        server.createContext("/v1/inbound-documents/recognize", exchange -> {
            if (!"POST".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"detail\":\"method not allowed\"}");
                return;
            }
            exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
            String batch = "OCR-MOCK-" + System.currentTimeMillis();
            String body = "{\"lines\":[{" +
                    "\"text\":\"药品名称：联调演示商品（非真实销售）\",\"confidence\":0.99},{" +
                    "\"text\":\"批号：" + batch + "\",\"confidence\":0.98},{" +
                    "\"text\":\"生产日期：2026-11-01\",\"confidence\":0.97},{" +
                    "\"text\":\"有效期至：2027-11-01\",\"confidence\":0.96},{" +
                    "\"text\":\"数量：1\",\"confidence\":0.95}]," +
                    "\"candidate\":{\"medicine_name\":\"联调演示商品（非真实销售）\",\"batch_no\":\"" + batch + "\"," +
                    "\"production_date\":\"2026-11-01\",\"expire_date\":\"2027-11-01\",\"quantity\":1," +
                    "\"confidence\":0.97,\"missing_fields\":[],\"needs_human_review\":true}," +
                    "\"requires_manual_review\":true,\"message\":\"TEST DOUBLE: no real OCR ran\"}";
            respond(exchange, 200, body);
        });
        server.start();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
