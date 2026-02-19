package com.winston.shortlink.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 处理静态资源请求的控制器
 */
@RestController
public class StaticResourceController {

    /**
     * 处理favicon.ico请求
     */
    @GetMapping("/favicon.ico")
    public ResponseEntity<Void> favicon() {
        return ResponseEntity.noContent().build();
    }

    /**
     * 处理robots.txt请求（可选）
     */
    @GetMapping("/robots.txt")
    public ResponseEntity<String> robots() {
        return ResponseEntity.ok("User-agent: *\nDisallow:");
    }
}