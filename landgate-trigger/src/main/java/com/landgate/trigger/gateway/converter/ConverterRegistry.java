package com.landgate.trigger.gateway.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Converter 注册表 —— 维护 formatId → {@link ProtocolConverter} 的映射。
 * <p>
 * 自动发现所有 {@link ProtocolConverter} Bean，Gateway 通过
 * {@code registry.get(formatId)} 获取对应格式的转换器。
 * <p>
 * 扩展新格式时只需新增一个 {@link ProtocolConverter} 实现（加上 {@code @Component}），
 * 无需修改 Gateway 流水线。
 */
@Slf4j
@Component
public class ConverterRegistry {

    /** formatId → Converter 映射 */
    private final Map<String, ProtocolConverter> converters = new HashMap<>();

    /**
     * 自动注册所有 ProtocolConverter Bean（Spring 注入）。
     */
    @Autowired
    public void register(List<ProtocolConverter> converterList) {
        for (ProtocolConverter c : converterList) {
            converters.put(c.getFormatId(), c);
            log.info("Registered ProtocolConverter: format={}, class={}",
                    c.getFormatId(), c.getClass().getSimpleName());
        }
    }

    /**
     * 按格式 ID 获取 Converter。
     *
     * @param formatId 格式标识（如 "messages"、"chat_completions"、"responses"）
     * @return 对应的 Converter，不存在时返回 null
     */
    public ProtocolConverter get(String formatId) {
        return converters.get(formatId);
    }

    /** 检查指定格式是否有对应的 Converter */
    public boolean supports(String formatId) {
        return converters.containsKey(formatId);
    }

    /** 已注册的格式数量 */
    public int size() {
        return converters.size();
    }
}
