package com.landgate.types.gateway;

/**
 * Stable OpenAI Anthropic Messages compatibility body facts.
 *
 * <p>This type owns literals shared with Sub2API's OpenAI Messages bridge only.
 * It must not parse or mutate JSON, select accounts, build auth headers, or
 * translate protocols.</p>
 */
public final class OpenAiAnthropicMessagesCompatPolicy {

    public static final String TODO_GUARD_MARKER = "<sub2api-claude-code-todo-guard>";
    public static final String TODO_GUARD_TEXT = TODO_GUARD_MARKER
            + "\nWhen using Claude Code todo or task tracking tools, keep the visible task list consistent. "
            + "Do not send final or summary text while any item remains in_progress. Before finishing, asking "
            + "the user to choose, or reporting a blocker, update the todo list so completed work is completed "
            + "and deferred work is pending/open; leave an item in_progress only when active work will continue "
            + "in the same turn.\n</sub2api-claude-code-todo-guard>";

    private OpenAiAnthropicMessagesCompatPolicy() {
    }
}
