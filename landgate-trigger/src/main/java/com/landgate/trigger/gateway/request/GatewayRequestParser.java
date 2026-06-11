package com.landgate.trigger.gateway.request;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;

/**
 * Parses gateway request properties that are independent from the selected upstream account.
 */
@Service
public class GatewayRequestParser {

    public static final String ATTR_GATEWAY_MODEL = "gateway_model";
    public static final String ATTR_GATEWAY_UPSTREAM_PATH = "gateway_upstream_path";

    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    public GatewayRequestInfo parse(String body, HttpServletRequest request, String requestFormat) {
        String model = (String) request.getAttribute(ATTR_GATEWAY_MODEL);
        if (model == null) {
            model = extractModel(body);
        }
        String upstreamPath = (String) request.getAttribute(ATTR_GATEWAY_UPSTREAM_PATH);
        boolean clientStream = shouldClientRequestStreaming(requestFormat, body);
        return new GatewayRequestInfo(model, upstreamPath, clientStream);
    }

    /** 从请求 body JSON 中提取 model 字段 */
    public static String extractModel(String body) {
        try {
            JsonNode root = JSON_MAPPER.readTree(body);
            if (root.has("model")) return root.get("model").asText();
        } catch (Exception e) {
            // ignore
        }
        return "unknown";
    }

    /** 从请求 body JSON 中提取 stream 字段 */
    public static boolean isStreamRequest(String body) {
        try {
            JsonNode root = JSON_MAPPER.readTree(body);
            if (root.has("stream")) return root.get("stream").asBoolean();
        } catch (Exception e) {
            // ignore
        }
        return false;
    }

    /** 判断客户端请求本身是否表达了流式响应意图。 */
    public static boolean shouldClientRequestStreaming(String requestFormat, String body) {
        return isStreamRequest(body);
    }
}
