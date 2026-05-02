package org.slackerdb.common.utils;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * AES加密解密工具类。
 * 提供AES对称加密算法的封装，支持加密和解密操作。
 *
 * <p><b>安全警告：</b>当前实现使用硬编码密钥，存在安全风险。建议从安全配置源获取密钥。</p>
 *
 * @see javax.crypto.Cipher
 * @see javax.crypto.SecretKey
 */
public class AESUtil {
    /** AES算法名称 */
    private static final String AES = "AES";

    /**
     * 解密使用AES加密的Base64编码数据。
     *
     * @param encryptedData Base64编码的加密数据
     * @param key 用于解密的AES密钥
     * @return 解密后的原始字符串
     * @throws Exception 如果解密失败或密钥无效
     */
    public static String decrypt(String encryptedData, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.DECRYPT_MODE, key);
        byte[] original = cipher.doFinal(Base64.getDecoder().decode(encryptedData));
        return new String(original);
    }

    /**
     * 使用AES算法加密数据，返回Base64编码的加密结果。
     *
     * @param data 要加密的原始字符串数据
     * @param key 用于加密的AES密钥
     * @return Base64编码的加密数据
     * @throws Exception 如果加密失败或密钥无效
     */
    public static String encrypt(String data, SecretKey key) throws Exception {
        Cipher cipher = Cipher.getInstance("AES");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        byte[] encrypted = cipher.doFinal(data.getBytes());
        return Base64.getEncoder().encodeToString(encrypted);
    }

    /**
     * 从字符串生成AES密钥。
     *
     * @param key 密钥字符串
     * @return 生成的SecretKey对象
     */
    private static SecretKey getKey(String key) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        return new SecretKeySpec(keyBytes, AES);
    }
}
