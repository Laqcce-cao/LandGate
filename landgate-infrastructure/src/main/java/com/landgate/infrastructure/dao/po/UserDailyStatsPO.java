package com.landgate.infrastructure.dao.po;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

@Getter
@Setter
public class UserDailyStatsPO {

    private Long userId;
    private LocalDate date;
    private Long totalTokens;
}
