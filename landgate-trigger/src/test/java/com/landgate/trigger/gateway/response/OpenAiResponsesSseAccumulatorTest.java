package com.landgate.trigger.gateway.response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.landgate.domain.billing.model.valobj.UsageTokens;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("OpenAI Responses SSE accumulator tests")
class OpenAiResponsesSseAccumulatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("Aggregates text deltas into non-streaming Responses JSON")
    void aggregatesTextDeltas() throws Exception {
        OpenAiResponsesSseAccumulator accumulator = new OpenAiResponsesSseAccumulator("gpt-5.5");

        assertFalse(accumulator.process(event("""
                {"type":"response.created","response":{"id":"resp_123","model":"gpt-5.5"}}
                """)));
        assertFalse(accumulator.process(event("""
                {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"Hello "}
                """)));
        assertFalse(accumulator.process(event("""
                {"type":"response.output_text.delta","output_index":0,"content_index":0,"delta":"world"}
                """)));
        assertTrue(accumulator.process(event("""
                {"type":"response.completed","response":{"id":"resp_123","model":"gpt-5.5","status":"completed"}}
                """)));

        JsonNode body = JSON.readTree(accumulator.buildResponsesJson(usage(7, 2, 3)));

        assertTrue(accumulator.terminalSeen());
        assertTrue(accumulator.finalResponseSeen());
        assertTrue(accumulator.terminalResponseSeen());
        assertEquals("resp_123", body.path("id").asText());
        assertEquals("completed", body.path("status").asText());
        assertEquals("Hello world", body.path("output").get(0).path("content").get(0).path("text").asText());
        assertEquals(10, body.path("usage").path("input_tokens").asInt());
        assertEquals(3, body.path("usage").path("input_tokens_details").path("cached_tokens").asInt());
    }

    @Test
    @DisplayName("Aggregates function call arguments and reasoning summary")
    void aggregatesFunctionCallAndReasoning() throws Exception {
        OpenAiResponsesSseAccumulator accumulator = new OpenAiResponsesSseAccumulator("gpt-5.5");

        accumulator.process(event("""
                {"type":"response.output_item.added","output_index":0,
                 "item":{"type":"function_call","call_id":"call_1","name":"lookup","arguments":""}}
                """));
        accumulator.process(event("""
                {"type":"response.function_call_arguments.delta","output_index":0,"delta":"{\\"q\\":"}
                """));
        accumulator.process(event("""
                {"type":"response.function_call_arguments.done","output_index":0,"arguments":"{\\"q\\":\\"weather\\"}"}
                """));
        accumulator.process(event("""
                {"type":"response.output_item.added","output_index":1,
                 "item":{"type":"reasoning","summary":[]}}
                """));
        accumulator.process(event("""
                {"type":"response.reasoning_summary_text.delta","output_index":1,"delta":"plan"}
                """));
        assertTrue(accumulator.process(event("""
                {"type":"response.done","response":{"status":"completed"}}
                """)));

        JsonNode output = JSON.readTree(accumulator.buildResponsesJson(usage(1, 1, 0))).path("output");

        assertEquals("reasoning", output.get(0).path("type").asText());
        assertEquals("summary_text", output.get(0).path("summary").get(0).path("type").asText());
        assertEquals("plan", output.get(0).path("summary").get(0).path("text").asText());
        assertEquals("function_call", output.get(1).path("type").asText());
        assertEquals("call_1", output.get(1).path("call_id").asText());
        assertEquals("{\"q\":\"weather\"}", output.get(1).path("arguments").asText());
    }

    @Test
    @DisplayName("Empty terminal output is supplemented from buffered deltas in Sub2API order")
    void emptyTerminalOutputIsSupplementedFromBufferedDeltas() throws Exception {
        OpenAiResponsesSseAccumulator accumulator = new OpenAiResponsesSseAccumulator("gpt-5.5");

        accumulator.process(event("""
                {"type":"response.reasoning_summary_text.delta","delta":"I thought about it."}
                """));
        accumulator.process(event("""
                {"type":"response.output_text.delta","delta":"The answer is 42."}
                """));
        accumulator.process(event("""
                {"type":"response.output_item.added","output_index":2,
                 "item":{"type":"function_call","call_id":"call_1","name":"verify"}}
                """));
        accumulator.process(event("""
                {"type":"response.function_call_arguments.delta","output_index":2,"delta":"{}"}
                """));
        assertTrue(accumulator.process(event("""
                {"type":"response.completed","response":{"id":"resp_empty","status":"completed","output":[]}}
                """)));

        JsonNode output = JSON.readTree(accumulator.buildResponsesJson(usage(3, 2, 0))).path("output");

        assertEquals(3, output.size());
        assertEquals("reasoning", output.get(0).path("type").asText());
        assertEquals("I thought about it.", output.get(0).path("summary").get(0).path("text").asText());
        assertEquals("message", output.get(1).path("type").asText());
        assertEquals("The answer is 42.", output.get(1).path("content").get(0).path("text").asText());
        assertEquals("function_call", output.get(2).path("type").asText());
        assertEquals("verify", output.get(2).path("name").asText());
        assertEquals("{}", output.get(2).path("arguments").asText());
    }

    @Test
    @DisplayName("Terminal response output is not overwritten by buffered deltas")
    void terminalOutputIsNotOverwrittenByBufferedDeltas() throws Exception {
        OpenAiResponsesSseAccumulator accumulator = new OpenAiResponsesSseAccumulator("gpt-5.5");

        accumulator.process(event("""
                {"type":"response.output_text.delta","delta":"from deltas"}
                """));
        assertTrue(accumulator.process(event("""
                {"type":"response.completed",
                 "response":{"id":"resp_terminal","status":"completed",
                   "output":[{"type":"message","role":"assistant","status":"completed",
                     "content":[{"type":"output_text","text":"from terminal event"}]}]}}
                """)));

        JsonNode output = JSON.readTree(accumulator.buildResponsesJson(usage(1, 1, 0))).path("output");

        assertEquals(1, output.size());
        assertEquals("from terminal event", output.get(0).path("content").get(0).path("text").asText());
    }

    @Test
    @DisplayName("response.done can carry incomplete status and reason")
    void responseDoneCanCarryIncompleteStatus() throws Exception {
        OpenAiResponsesSseAccumulator accumulator = new OpenAiResponsesSseAccumulator("gpt-5.5");

        assertTrue(accumulator.process(event("""
                {"type":"response.done",
                 "response":{"status":"incomplete",
                   "incomplete_details":{"reason":"max_output_tokens"},
                   "output":[{"type":"message","role":"assistant","status":"completed",
                     "content":[{"type":"output_text","text":"partial"}]}]}}
                """)));

        JsonNode body = JSON.readTree(accumulator.buildResponsesJson(usage(4, 2, 0)));

        assertEquals("incomplete", body.path("status").asText());
        assertEquals("max_output_tokens", body.path("incomplete_details").path("reason").asText());
        assertEquals("partial", body.path("output").get(0).path("content").get(0).path("text").asText());
    }

    @Test
    @DisplayName("response.canceled is treated as a terminal failed response")
    void responseCanceledIsTerminal() throws Exception {
        OpenAiResponsesSseAccumulator accumulator = new OpenAiResponsesSseAccumulator("gpt-5.5");

        assertTrue(accumulator.process(event("""
                {"type":"response.canceled",
                 "response":{"id":"resp_cancel","status":"canceled",
                   "error":{"code":"canceled","message":"Request canceled"}}}
                """)));

        JsonNode body = JSON.readTree(accumulator.buildResponsesJson(usage(4, 0, 0)));

        assertTrue(accumulator.terminalSeen());
        assertTrue(accumulator.terminalResponseSeen());
        assertEquals("failed", body.path("status").asText());
        assertEquals("canceled", body.path("error").path("code").asText());
        assertEquals("Request canceled", body.path("error").path("message").asText());
    }

    private static JsonNode event(String json) throws Exception {
        return JSON.readTree(json);
    }

    private static UsageTokens usage(int input, int output, int cacheRead) {
        return UsageTokens.builder()
                .inputTokens(input)
                .outputTokens(output)
                .cacheReadTokens(cacheRead)
                .build();
    }
}
