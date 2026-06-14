package com.landgate.types.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("OpenAI tool continuation policy tests")
class OpenAiToolContinuationPolicyTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    @DisplayName("No function_call_output means no tool continuation validation")
    void ignoresRequestsWithoutFunctionCallOutput() throws Exception {
        var validation = OpenAiToolContinuationPolicy.validateFunctionCallOutputContext(JSON.readTree("""
                {"input":[{"type":"message","role":"user","content":"Hi"}]}"""));

        assertFalse(validation.hasFunctionCallOutput());
        assertFalse(validation.hasToolCallContext());
        assertFalse(validation.hasFunctionCallOutputMissingCallId());
        assertFalse(validation.hasItemReferenceForAllCallIds());
    }

    @Test
    @DisplayName("Detects function_call_output missing call_id")
    void detectsMissingCallId() throws Exception {
        var validation = OpenAiToolContinuationPolicy.validateFunctionCallOutputContext(JSON.readTree("""
                {"input":[{"type":"function_call_output","output":"{}"}]}"""));

        assertTrue(validation.hasFunctionCallOutput());
        assertTrue(validation.hasFunctionCallOutputMissingCallId());
        assertFalse(validation.hasToolCallContext());
        assertFalse(validation.hasItemReferenceForAllCallIds());
    }

    @Test
    @DisplayName("Detects same-input tool call context")
    void detectsToolCallContext() throws Exception {
        var validation = OpenAiToolContinuationPolicy.validateFunctionCallOutputContext(JSON.readTree("""
                {"input":[
                  {"type":"function_call","call_id":"call_1","name":"fn","arguments":"{}"},
                  {"type":"function_call_output","call_id":"call_1","output":"ok"}
                ]}"""));

        assertTrue(validation.hasFunctionCallOutput());
        assertTrue(validation.hasToolCallContext());
        assertFalse(validation.hasFunctionCallOutputMissingCallId());
    }

    @Test
    @DisplayName("Requires item_reference ids to cover every function_call_output call_id")
    void detectsItemReferenceCoverage() throws Exception {
        var covered = OpenAiToolContinuationPolicy.validateFunctionCallOutputContext(JSON.readTree("""
                {"input":[
                  {"type":"item_reference","id":"call_1"},
                  {"type":"item_reference","id":"call_2"},
                  {"type":"function_call_output","call_id":"call_1","output":"one"},
                  {"type":"function_call_output","call_id":"call_2","output":"two"}
                ]}"""));
        var partial = OpenAiToolContinuationPolicy.validateFunctionCallOutputContext(JSON.readTree("""
                {"input":[
                  {"type":"item_reference","id":"call_1"},
                  {"type":"function_call_output","call_id":"call_1","output":"one"},
                  {"type":"function_call_output","call_id":"call_2","output":"two"}
                ]}"""));

        assertTrue(covered.hasItemReferenceForAllCallIds());
        assertFalse(partial.hasItemReferenceForAllCallIds());
    }

    @Test
    @DisplayName("Detects Sub2API-compatible Codex continuation signals")
    void detectsCodexContinuationSignals() throws Exception {
        assertFalse(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"input":[{"type":"message","role":"user","content":"Hi"}]}""")));

        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"previous_response_id":"resp_1","input":[{"type":"message","role":"user","content":"Hi"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"tools":[{"type":"function","name":"fn"}],"input":[{"type":"message","role":"user","content":"Hi"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"tool_choice":"auto","input":[{"type":"message","role":"user","content":"Hi"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"tool_choice":{"type":"function","name":"fn"},"input":[{"type":"message","role":"user","content":"Hi"}]}""")));
        assertFalse(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"tool_choice":"","input":[{"type":"message","role":"user","content":"Hi"}]}""")));
        assertFalse(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"tool_choice":{},"input":[{"type":"message","role":"user","content":"Hi"}]}""")));
        assertFalse(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"tool_choice":[],"input":[{"type":"message","role":"user","content":"Hi"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"input":[{"type":"item_reference","id":"call_1"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"input":[{"type":"tool_search_output","call_id":"call_1","output":"ok"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"input":[{"type":"custom_tool_call_output","call_id":"call_1","output":"ok"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"input":[{"type":"mcp_tool_call_output","call_id":"call_1","output":"ok"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"input":[{"type":"local_shell_call","call_id":"call_1"}]}""")));
        assertTrue(OpenAiToolContinuationPolicy.needsToolContinuation(JSON.readTree("""
                {"input":[{"role":"tool","tool_call_id":"call_1","content":"ok"}]}""")));
    }
}
