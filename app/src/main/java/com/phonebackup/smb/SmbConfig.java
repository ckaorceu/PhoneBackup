package com.phonebackup.smb;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * SMB 连接配置的本地持久化。
 * 注意：密码以明文存于应用私有 SharedPreferences，仅适合个人设备。
 * 如需更高安全性可改用 Android Keystore 加密。
 */
public class SmbConfig {
    private static final String PREF = "smb_config";
    private static final String K_HOST = "host";
    private static final String K_SHARE = "share";
    private static final String K_FOLDER = "folder";
    private static final String K_DOMAIN = "domain";
    private static final String K_USER = "user";
    private static final String K_PASS = "pass";

    public String host = "";
    public String share = "";
    public String folder = "";
    public String domain = "";
    public String user = "";
    public String pass = "";

    public boolean isConfigured() {
        return !host.trim().isEmpty() && !share.trim().isEmpty();
    }

    public static SmbConfig load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE);
        SmbConfig c = new SmbConfig();
        c.host = p.getString(K_HOST, "");
        c.share = p.getString(K_SHARE, "");
        c.folder = p.getString(K_FOLDER, "");
        c.domain = p.getString(K_DOMAIN, "");
        c.user = p.getString(K_USER, "");
        c.pass = p.getString(K_PASS, "");
        return c;
    }

    public void save(Context ctx) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
                .putString(K_HOST, host.trim())
                .putString(K_SHARE, share.trim())
                .putString(K_FOLDER, folder.trim())
                .putString(K_DOMAIN, domain.trim())
                .putString(K_USER, user.trim())
                .putString(K_PASS, pass)
                .apply();
    }
}
