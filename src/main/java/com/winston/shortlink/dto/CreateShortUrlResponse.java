package com.winston.shortlink.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @description:
 * @author: Winston
 * @date: 2026/1/28 21:36
 * @version: 1.0
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class CreateShortUrlResponse {
    private String shortCode;
    private String shortUrl;
    private String originUrl;
    private LocalDateTime createTime;
    private LocalDateTime expireTime;
    private Integer expireDays;
    private Long accessCount = 0L;

}
