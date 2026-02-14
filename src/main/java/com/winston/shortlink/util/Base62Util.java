package com.winston.shortlink.util;

/**
 * @description: Base62编码工具类，将数字编码为Base62字符串，得到短链编码
 * @author: Winston
 * @date: 2026/2/4 21:39
 * @version: 1.0
 */
public class Base62Util {
    /**
     * Base62字符集：0-9, A-Z, a-z
     * 总共62个字符，避免了容易混淆的字符
     */
    private static final String BASE62_CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final int BASE = 62;


    /**
     * 将长整型整数编码为Base62字符串
     * @param num 需要编码的整数
     * @return 返回编码后的Base62字符串
     */
    public static String encode(long num) {
        if (num == 0) {
            return "0";
        }

        // 编码为字符串
        StringBuilder sb = new StringBuilder();
        // 取余找到索引
        while (num > 0) {
            sb.append(BASE62_CHARS.charAt((int) (num % BASE)));
            // 整除
            num /= BASE;
        }
        // 反转
        return sb.reverse().toString();
    }

    /**
     * 解码，将Base62还原为长整型整数
     * @param code 字符串
     * @return 长整数
     */
    public static long decode(String code) {
        // 1.非空判断
        if (code == null || code.isEmpty()) {
            throw new IllegalArgumentException("输入字符串不能为空");
        }
        long result = 0;
        long power = 1;
        // 2.从右往左开始
        for (int i = code.length() - 1; i >= 0; i--) {
            char c = code.charAt(i);
            // 获取对应索引
            int index = BASE62_CHARS.indexOf(c);
            if (index == -1) {
                throw new IllegalArgumentException("包含非法字符: " + c);
            }
            // 3.还原为长整数
            result += index * power;
            power = power * BASE;
        }
        return result;
    }

    /**
     * 生成指定的编码字符串，严格控制长度
     * @param num 需要编码的长整型整数
     * @param exactLength 严格控制的长度
     * @return 返回控制长度的编码字符串
     */
    public static String encodeWithExactLength(long num, int exactLength) {
        // 1.非空判断
        if (exactLength < 0) {
            throw new IllegalArgumentException("长度必须大于0");
        }
        // 2.编码
        String encode = encode(num);
        // 3.控制长度
        if (encode.length() >  exactLength) {
            // 超过指定的长度就裁取
            return encode.substring(0, exactLength);
        } else if (encode.length() < exactLength) {
            StringBuilder sb = new StringBuilder();
            // 小于需要的长度，需要前面补零处理
            for (int i = 0; i < exactLength - encode.length(); i++) {
                sb.append('0');
            }
            sb.append(encode);
            return sb.toString();
        } else {
            // 相等就直接返回
            return encode;
        }
    }

    /**
     * 生成至少长度的字符串
     * @param num 长整数
     * @param minLength 至少长度
     * @return 返回编码字符串
     */
    public static String encodeWithMinLength(long num, int minLength) {
        // 1.非空判断
        if (minLength < 0) {
            throw new IllegalArgumentException("长度必须大于0");
        }
        // 2.编码
        String encode = encode(num);
        // 3.如果长度大于等于最低，直接返回
        if (encode.length() >= minLength) {
            return encode;
        }
        // 4.长度小于最低限制，前面补零
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < minLength - encode.length(); i++) {
            sb.append('0');
        }
        sb.append(encode);
        return sb.toString();
    }

    /**
     * 检验是否是有效的Base62字符串
     * @param str Base62字符串
     * @return 布尔
     */
    public static boolean isValidBase62(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        for (char c : str.toCharArray()) {
            if (BASE62_CHARS.indexOf(c) == -1) {
                return false;
            }
        }
        return true;
    }

    /**
     * 获取Base62字符集
     * @return Base62字符集
     */
    public static String getBase62Chars() {
        return BASE62_CHARS;
    }

    /**
     * 获取Base62进制数
     * @return 62
     */
    public static int getBase() {
        return BASE;
    }

    /**
     * 在指定位数下，获取Base62字符串能够编码的最大数值
     * @param length 指定位数
     * @return 返回该指定位数下Base62能够编码的最大长整型数
     */
    public static long getMaxValue(int length) {
        // 1.非空判断
        if (length <= 0) {
            return 0;
        }
        // 2.计算最大
        int max = 0;
        int power = 1;
        for (int i = 0; i < length; i++) {
            max += (BASE - 1)  * power;
            power *= BASE;
        }
        // 3.返回最大数值
        return max;
    }

    /**
     * 生成随机的Base62字符串
     *
     * @param length 字符串长度
     * @return 随机的Base62字符串
     */
    public static String generateRandom(int length) {
        if (length <= 0) {
            throw new IllegalArgumentException("长度必须大于0");
        }

        StringBuilder sb = new StringBuilder();
        java.util.Random random = new java.util.Random();

        for (int i = 0; i < length; i++) {
            int index = random.nextInt(BASE);
            sb.append(BASE62_CHARS.charAt(index));
        }

        return sb.toString();
    }



}
