package com.landgate.domain.checkin.model.entity;

/**
 * 签到结果实体 —— 领域层返回签到是否为重复签到以及对应签到记录。
 */
public record CheckinResultEntity(
        /** 是否为今日已完成签到的幂等返回 */
        Boolean alreadySigned,
        /** 签到记录 */
        UserCheckinEntity record
) {}
