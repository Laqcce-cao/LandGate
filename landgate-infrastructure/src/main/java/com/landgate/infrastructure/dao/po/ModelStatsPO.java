package com.landgate.infrastructure.dao.po;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
public class ModelStatsPO {

    private String model;
    private Long totalTokens;
    private BigDecimal totalCost;
    private Long callCount;
}
