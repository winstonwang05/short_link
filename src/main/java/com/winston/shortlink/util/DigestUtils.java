package com.winston.shortlink.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import static com.winston.shortlink.constant.CommonConstants.SHORT_CODE_LENGTH;

/**
 * @description: 摘要算法工具类， 提供常用的哈希算法功能，如MD5、SHA-1、SHA-256等
 * @author: Winston
 * @date: 2026/2/4 21:24
 * @version: 1.0
 */
public class DigestUtils {

    private static final String MD5 = "MD5";
    private static final String SHA1 = "SHA-1";
    private static final String SHA256 = "SHA-256";
    private static final String SHA512 = "SHA-512";

    // 工具类，禁止实例化
    private DigestUtils() {};


    /**
     * MD5摘要
     * @param data 待摘要的数据
     * @return MD5的摘要结果（32位小写十六进制字符串）
     */
    public static String md5(String data) {
        // 鲁棒性检查
        if (data == null) {
            return null;
        }
        return md5(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * MD5摘要
     * @param data 待摘要的数据
     * @return MD5摘要的结果（32位小写十六进制字符串）
     */
    private static String md5(byte[] data) {
        return digest(data, MD5);
    }

    /**
     *  SHA-1摘要
     * @param data 待摘要的数据
     * @return SHA-1的摘要结果（40位小写十六进制字符串）
     */
    public static String sha1(String data) {
        if (data == null) {
            return null;
        }
        return sha1(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-1摘要0
     * @param bytes 待摘要的数据
     * @return SHA-1摘要结果（40位小写十六进制字符串）
     */
    private static String sha1(byte[] bytes) {
        return digest(bytes, SHA1);
    }
    /**
     * SHA-256摘要s
     *
     * @param data 待摘要的数据
     * @return SHA-256摘要结果（64位小写十六进制字符串）
     */
    public static String sha256(String data) {
        if (data == null) {
            return null;
        }
        return sha256(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-256摘要
     *
     * @param data 待摘要的字节数组
     * @return SHA-256摘要结果（64位小写十六进制字符串）
     */
    public static String sha256(byte[] data) {
        return digest(data, SHA256);
    }

    /**
     * SHA-512摘要
     *
     * @param data 待摘要的数据
     * @return SHA-512摘要结果（128位小写十六进制字符串）
     */
    public static String sha512(String data) {
        if (data == null) {
            return null;
        }
        return sha512(data.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * SHA-512摘要
     *
     * @param data 待摘要的字节数组
     * @return SHA-512摘要结果（128位小写十六进制字符串）
     */
    public static String sha512(byte[] data) {
        return digest(data, SHA512);
    }


    /**
     *  通用的摘要方法
     * @param data 待摘要的数据
     * @param algorithm 摘要算法名称
     * @return 摘要结果（小写十六进制字符串）
     */
    private static String digest(byte[] data, String algorithm) {
        if (data == null || algorithm == null) {
            return null;
        }

        try {
            // 获取摘要实例对象
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            byte[] hashBytes = digest.digest(data);
            // 将二进制转化为十六进制返回
            return HexFormat.of().formatHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("不支持的摘要算法: " + algorithm, e);
        }
    }
    /**
     * 验证数据与摘要是否匹配（MD5）
     *
     * @param data 原始数据
     * @param expectedHash 期望的摘要值
     * @return 是否匹配
     */
    public static boolean verifyMd5(String data, String expectedHash) {
        if (data == null || expectedHash == null) {
            return false;
        }
        String actualHash = md5(data);
        return expectedHash.equalsIgnoreCase(actualHash);
    }

    /**
     * 验证数据与摘要是否匹配（SHA-256）
     *
     * @param data 原始数据
     * @param expectedHash 期望的摘要值
     * @return 是否匹配
     */
    public static boolean verifySha256(String data, String expectedHash) {
        if (data == null || expectedHash == null) {
            return false;
        }
        String actualHash = sha256(data);
        return expectedHash.equalsIgnoreCase(actualHash);
    }

    /**
     * 生成带盐值的MD5摘要
     *
     * @param data 原始数据
     * @param salt 盐值
     * @return 带盐值的MD5摘要
     */
    public static String md5WithSalt(String data, String salt) {
        if (data == null || salt == null) {
            return null;
        }
        return md5(data + salt);
    }

    /**
     * 生成带盐值的SHA-256摘要
     *
     * @param data 原始数据
     * @param salt 盐值
     * @return 带盐值的SHA-256摘要
     */
    public static String sha256WithSalt(String data, String salt) {
        if (data == null || salt == null) {
            return null;
        }
        return sha256(data + salt);
    }

    /**
     * 生成简短的哈希值（取MD5的前8位）
     * 适用于需要较短哈希值的场景
     *
     * @param data 原始数据
     * @return 8位哈希值
     */
    public static String shortHash(String data) {
        String md5Hash = md5(data);
        return md5Hash != null && md5Hash.length() >= SHORT_CODE_LENGTH ? md5Hash.substring(0, SHORT_CODE_LENGTH) : md5Hash;
    }

    /**
     * 生成URL安全的哈希值
     * 使用SHA-256并转换为Base62编码
     *
     * @param data 原始数据
     * @return URL安全的哈希值
     */
    public static String urlSafeHash(String data) {
        String sha256Hash = sha256(data);
        if (sha256Hash == null) {
            return null;
        }

        // 将十六进制字符串转换为长整型，然后使用Base62编码
        try {
            // 取SHA-256的前16位（64bit）转换为long
            String hexSubstring = sha256Hash.substring(0, 16);
            long hashLong = Long.parseUnsignedLong(hexSubstring, 16);
            return Base62Util.encode(hashLong);
        } catch (Exception e) {
            // 如果转换失败，返回原始哈希值的前10位
            return sha256Hash.substring(0, Math.min(10, sha256Hash.length()));
        }
    }
}


