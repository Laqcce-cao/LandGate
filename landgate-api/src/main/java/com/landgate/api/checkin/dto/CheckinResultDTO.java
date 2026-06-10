package com.landgate.api.checkin.dto;

/**
 * 签到结果 DTO —— 用户点击签到后的响应对象。
 */
public record CheckinResultDTO(
        Boolean alreadySigned,
        CheckinRecordDTO record
) {}
