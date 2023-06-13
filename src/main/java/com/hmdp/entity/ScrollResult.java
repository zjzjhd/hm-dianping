package com.hmdp.entity;

import lombok.Data;

import java.util.List;

/**
 * @author zjzjhd
 * @version 1.0
 * @description: TODO
 * @date 2023/3/8 20:03
 */
@Data
public class ScrollResult {
    private List<?> list;
    private Long minTime;
    private Integer offset;
}
