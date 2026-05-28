package com.landgate.infrastructure.dao.po;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class PlatformDailyStatsPO {

    private LocalDate date;
    private Long inputTokens;
    private Long outputTokens;
    private Long cacheReadTokens;
    private Long cacheCreationTokens;
    private Long callCount;
}
