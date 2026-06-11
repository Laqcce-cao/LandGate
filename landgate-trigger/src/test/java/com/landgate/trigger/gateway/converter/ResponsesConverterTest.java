package com.landgate.trigger.gateway.converter;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("ResponsesConverter 流式透传测试")
class ResponsesConverterTest {

    private final ResponsesConverter converter = new ResponsesConverter();

    @Test
    @DisplayName("response.completed 后保留 SSE 空行分隔符")
    void keepsBlankSeparatorAfterCompletedEvent() {
        StreamTranslator t = converter.createStreamFromIR("gpt-5.5");

        assertEquals(List.of("event: response.completed"),
                t.feed("event: response.completed"));

        List<String> dataOut = t.feed("data: {\"type\":\"response.completed\",\"response\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":4}}}");
        assertEquals(1, dataOut.size());
        assertTrue(dataOut.get(0).contains("response.completed"));
        assertTrue(t.isDone());

        assertEquals(List.of(""), t.feed(""));
        assertTrue(t.feed("data: {\"type\":\"response.output_text.delta\",\"delta\":\"late\"}").isEmpty());
    }
}
