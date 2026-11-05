package com.kevin.phonekline;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** 手机 MCP Key 用 Android Keystore 加密；不写入日志或明文配置。 */
final class PhoneSecretStore {
    private static SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        String alias = "phone-kline-mcp-key";
        if (store.containsAlias(alias)) return (SecretKey) store.getKey(alias, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    static void save(SharedPreferences prefs, String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        if (!prefs.edit().putString("mcp_key_cipher", Base64.encodeToString(encrypted, Base64.NO_WRAP))
                .putString("mcp_key_iv", Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP)).commit())
            throw new java.io.IOException("保存 Key 失败");
    }

    static String load(SharedPreferences prefs) {
        try {
            String encrypted = prefs.getString("mcp_key_cipher", "");
            if (encrypted.isEmpty()) return "";
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128,
                    Base64.decode(prefs.getString("mcp_key_iv", ""), Base64.DEFAULT)));
            return new String(cipher.doFinal(Base64.decode(encrypted, Base64.DEFAULT)), StandardCharsets.UTF_8);
        } catch (Exception unavailable) { return ""; }
    }
}
