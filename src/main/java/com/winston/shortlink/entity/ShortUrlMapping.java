package com.winston.shortlink.entity;

import jakarta.persistence.*;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

import static com.winston.shortlink.constant.CommonConstants.SHORT_CODE_LENGTH;


/**
 * @description: 短链实体类--对应数据库
 * @author: Winston
 * @date: 2026/2/1 12:06
 * @version: 1.0
 */
@Data
@Entity
@Table(name = "short_url_mapping", indexes = {
        @Index(name = "idx_short_code", columnList = "shortCode", unique = true),
        @Index(name = "idx_create_time", columnList = "CreateTime")
})
public class ShortUrlMapping {

    /**
     * 短链编码
     */
    @Id
    @Column(name = "short_code", length = SHORT_CODE_LENGTH)
    @NotBlank(message = "短链编码不能为空")
    @Size(max =  SHORT_CODE_LENGTH, message = "短链编码长度不能超过" + SHORT_CODE_LENGTH)
    private String shortCode;

    /**
     * 原始Url
     */
    @Column(name = "origin_url", columnDefinition = "VARCHAR", nullable = false)
    @NotBlank(message = "原始URL不能为空")
    @Size(max = 2048, message = "原始URL长度不能超过2048")
    private String originUrl;

    /**
     * 原始Url的哈希值
     */
    @Column(name = "origin_url_hash", columnDefinition = "VARCHAR", nullable = false)
    @NotBlank(message = "原始URL哈希值不能为空")
    private String originalUrlHash;

    /**
     * 创建时间
     */
    @Column(name = "create_time", nullable = false, updatable = false) // 一旦创建之后就不会改变
    @CreationTimestamp
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    @Column(name = "update_time")
    @UpdateTimestamp
    private LocalDateTime updateTime;

    /**
     * 过期天数
     */
    @Column(name = "expire_days", nullable = false)
    private Integer expireDays = 7;

    /**
     * 访问数量
     */
    @Column(name = "access_count", nullable = false)
    private Long accessCount = 0L;

    /**
     * 状态1 --正常
     * 状态2 --禁用
     */
    @Column(name = "status", nullable = false)
    private Integer status;

    /**
     * 创建人
     */
    @Column(name = "creator", nullable = false)
    @Size(max = 30, message = "创建人长度不能超过30字符")
    private String creator;

    /**
     * 是否过期
     * @return 返回是否过期
     */
    public boolean isExpired() {
        // 如果过期天数为null或者小于0，表示永不过期
        if (expireDays == null ||  expireDays <= 0) {
            return false;
        }

        // 如果创建时间为null， 认为已过期（异常）
        if (createTime == null) {
            return true;
        }

        LocalDateTime expireTime = createTime.plusDays(expireDays);
        return LocalDateTime.now().isAfter(expireTime);
    }
}
