package com.landgate.trigger.gateway.converter;

import java.util.List;

/**
 * SSE 流式翻译器 —— 逐行消费上游原始 SSE 行，返回翻译后的 SSE 行列表。
 * <p>
 * 用法：
 * <pre>
 *   StreamTranslator t = converter.createStreamToIR(model);
 *   while ((line = reader.readLine()) != null) {
 *       for (String out : t.feed(line)) {
 *           writer.write(out + "\n");
 *       }
 *       if (t.isDone()) break;
 *   }
 * </pre>
 */
public interface StreamTranslator {

    /**
     * 消费一行上游原始 SSE 行（含 "data: " 或 "event: " 前缀）。
     *
     * @param line 上游原始 SSE 行
     * @return 需要写入客户端的翻译后 SSE 行列表（可能为空）
     */
    List<String> feed(String line);

    /** 翻译是否已完成（上游流已结束或已收到终端事件） */
    boolean isDone();

    /** 从流中提取的输入 token 数 */
    int getInputTokens();

    /** 从流中提取的输出 token 数 */
    int getOutputTokens();
}
