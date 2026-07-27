package com.phonebackup.ui;

import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.phonebackup.databinding.ActivitySmbSettingsBinding;
import com.phonebackup.smb.SmbBackupTarget;
import com.phonebackup.smb.SmbConfig;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SmbSettingsActivity extends AppCompatActivity {

    private ActivitySmbSettingsBinding b;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivitySmbSettingsBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        SmbConfig c = SmbConfig.load(this);
        b.etHost.setText(c.host);
        b.etShare.setText(c.share);
        b.etFolder.setText(c.folder);
        b.etDomain.setText(c.domain);
        b.etUser.setText(c.user);
        b.etPass.setText(c.pass);

        b.btnSave.setOnClickListener(v -> save());
        b.btnTest.setOnClickListener(v -> test());
    }

    private SmbConfig collect() {
        SmbConfig c = new SmbConfig();
        c.host = text(b.etHost);
        c.share = text(b.etShare);
        c.folder = text(b.etFolder);
        c.domain = text(b.etDomain);
        c.user = text(b.etUser);
        c.pass = b.etPass.getText() == null ? "" : b.etPass.getText().toString();
        return c;
    }

    private void save() {
        SmbConfig c = collect();
        if (!c.isConfigured()) {
            Toast.makeText(this, "请填写服务器地址和共享名", Toast.LENGTH_SHORT).show();
            return;
        }
        c.save(this);
        Toast.makeText(this, "已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void test() {
        SmbConfig c = collect();
        if (!c.isConfigured()) {
            b.tvTestResult.setText("请先填写服务器地址和共享名");
            return;
        }
        b.btnTest.setEnabled(false);
        b.tvTestResult.setText("正在连接…");

        executor.execute(() -> {
            SmbBackupTarget t = new SmbBackupTarget(
                    c.host, c.share, c.folder, c.domain, c.user, c.pass);
            final String[] msgRef = new String[1];
            try {
                t.connect();
                msgRef[0] = "连接成功，可以开始备份。";
            } catch (Exception e) {
                msgRef[0] = "连接失败：" + e.getMessage();
            } finally {
                t.close();
            }
            runOnUiThread(() -> {
                b.tvTestResult.setText(msgRef[0]);
                b.btnTest.setEnabled(true);
            });
        });
    }

    private static String text(android.widget.EditText et) {
        return et.getText() == null ? "" : et.getText().toString();
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }
}
