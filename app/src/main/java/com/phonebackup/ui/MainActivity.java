package com.phonebackup.ui;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.phonebackup.R;
import com.phonebackup.backup.BackupResult;
import com.phonebackup.backup.BackupService;
import com.phonebackup.databinding.ActivityMainBinding;
import com.phonebackup.smb.SmbConfig;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_PERMS = 100;
    private static final String PREF = "main_pref";
    private static final String K_SAF_URI = "saf_tree_uri";

    private ActivityMainBinding b;
    private boolean running = false;
    private Uri safTreeUri;

    private final ActivityResultLauncher<Uri> pickFolder =
            registerForActivityResult(new ActivityResultContracts.OpenDocumentTree(), uri -> {
                if (uri == null) return;
                // 持久化授权，重启后仍可写入
                int flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
                getContentResolver().takePersistableUriPermission(uri, flags);
                safTreeUri = uri;
                prefs().edit().putString(K_SAF_URI, uri.toString()).apply();
                updateUsbHint();
            });

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context ctx, Intent i) {
            String action = i.getAction();
            if (BackupService.ACTION_PROGRESS.equals(action)) {
                int done = i.getIntExtra("done", 0);
                int total = i.getIntExtra("total", 0);
                long doneBytes = i.getLongExtra("doneBytes", 0);
                long totalBytes = i.getLongExtra("totalBytes", 0);
                String file = i.getStringExtra("file");
                int pct = totalBytes > 0 ? (int) (doneBytes * 100 / totalBytes) : 0;
                b.progressBar.setProgress(pct);
                b.tvStatus.setText(done + "/" + total + "  "
                        + BackupResult.formatSize(doneBytes) + "/"
                        + BackupResult.formatSize(totalBytes)
                        + (file != null ? "\n" + file : ""));
            } else if ("com.phonebackup.FINISH".equals(action)) {
                running = false;
                setRunningUi(false);
                b.progressBar.setProgress(100);
                b.tvStatus.setText(i.getStringExtra("summary"));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        b = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(b.getRoot());

        String saved = prefs().getString(K_SAF_URI, null);
        if (saved != null) safTreeUri = Uri.parse(saved);

        setupTargetToggle();
        b.btnStart.setOnClickListener(v -> onStartClicked());
        b.btnCancel.setOnClickListener(v -> cancelBackup());
        b.btnSmbSettings.setOnClickListener(v ->
                startActivity(new Intent(this, SmbSettingsActivity.class)));
        b.btnPickFolder.setOnClickListener(v -> pickFolder.launch(null));

        LocalBroadcastManager lbm = LocalBroadcastManager.getInstance(this);
        lbm.registerReceiver(receiver, new IntentFilter(BackupService.ACTION_PROGRESS));
        lbm.registerReceiver(receiver, new IntentFilter("com.phonebackup.FINISH"));

        updateUsbHint();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSmbInfo();
    }

    @Override
    protected void onDestroy() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver);
        super.onDestroy();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void setupTargetToggle() {
        b.rgTarget.setOnCheckedChangeListener((group, checkedId) -> {
            boolean usb = checkedId == R.id.rbUsb;
            b.panelUsb.setVisibility(usb ? View.VISIBLE : View.GONE);
            b.panelSmb.setVisibility(usb ? View.GONE : View.VISIBLE);
        });
    }

    private void updateUsbHint() {
        if (safTreeUri == null) {
            b.tvUsbHint.setText("请先连接移动硬盘，再点上方按钮选择备份目录（选硬盘本身或新建一个文件夹均可）。");
        } else {
            DocumentFile df = DocumentFile.fromTreeUri(this, safTreeUri);
            String name = df != null ? df.getName() : safTreeUri.toString();
            b.tvUsbHint.setText("已选择目录：" + name + "\n备份将写入该目录下，保留原有文件夹结构。");
        }
    }

    private void refreshSmbInfo() {
        SmbConfig c = SmbConfig.load(this);
        if (c.isConfigured()) {
            String info = "smb://" + c.host + "/" + c.share;
            if (!c.folder.isEmpty()) info += "/" + c.folder;
            if (!c.user.isEmpty()) info += "\n用户：" + c.user;
            b.tvSmbInfo.setText(info);
        } else {
            b.tvSmbInfo.setText("尚未配置网络共享，点击下方按钮设置。");
        }
    }

    private void onStartClicked() {
        if (running) return;

        if (!hasMediaPermission()) {
            requestMediaPermission();
            return;
        }
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
        }

        if (!b.cbImages.isChecked() && !b.cbVideos.isChecked()
                && !b.cbAudio.isChecked() && !b.cbOther.isChecked()) {
            toast("请至少选择一种备份内容");
            return;
        }

        boolean usb = b.rgTarget.getCheckedRadioButtonId() == R.id.rbUsb;
        Intent intent = new Intent(this, BackupService.class);

        if (usb) {
            if (safTreeUri == null) {
                toast("请先选择移动硬盘上的备份目录");
                return;
            }
            intent.putExtra(BackupService.EXTRA_TARGET_TYPE, "saf");
            intent.putExtra(BackupService.EXTRA_SAF_URI, safTreeUri.toString());
        } else {
            SmbConfig c = SmbConfig.load(this);
            if (!c.isConfigured()) {
                toast("请先配置网络共享");
                return;
            }
            intent.putExtra(BackupService.EXTRA_TARGET_TYPE, "smb");
            intent.putExtra(BackupService.EXTRA_SMB_HOST, c.host);
            intent.putExtra(BackupService.EXTRA_SMB_SHARE, c.share);
            intent.putExtra(BackupService.EXTRA_SMB_FOLDER, c.folder);
            intent.putExtra(BackupService.EXTRA_SMB_DOMAIN, c.domain);
            intent.putExtra(BackupService.EXTRA_SMB_USER, c.user);
            intent.putExtra(BackupService.EXTRA_SMB_PASS, c.pass);
        }
        intent.putExtra(BackupService.EXTRA_INC_IMAGES, b.cbImages.isChecked());
        intent.putExtra(BackupService.EXTRA_INC_VIDEOS, b.cbVideos.isChecked());
        intent.putExtra(BackupService.EXTRA_INC_AUDIO, b.cbAudio.isChecked());
        intent.putExtra(BackupService.EXTRA_INC_OTHER, b.cbOther.isChecked());

        running = true;
        setRunningUi(true);
        b.progressBar.setProgress(0);
        b.tvStatus.setText("正在扫描文件…");
        BackupService.start(this, intent);
    }

    private void cancelBackup() {
        Intent cancel = new Intent(this, BackupService.class);
        cancel.setAction(BackupService.ACTION_CANCEL);
        startService(cancel);
        b.tvStatus.setText("正在取消…");
    }

    private void setRunningUi(boolean isRunning) {
        b.btnStart.setEnabled(!isRunning);
        b.btnCancel.setVisibility(isRunning ? View.VISIBLE : View.GONE);
        b.progressBar.setVisibility(isRunning ? View.VISIBLE : View.GONE);
    }

    // ---------- 权限 ----------

    private boolean hasMediaPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                    == PackageManager.PERMISSION_GRANTED
                    && ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMediaPermission() {
        String[] perms;
        if (Build.VERSION.SDK_INT >= 33) {
            perms = new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO
            };
        } else {
            perms = new String[]{Manifest.permission.READ_EXTERNAL_STORAGE};
        }
        ActivityCompat.requestPermissions(this, perms, REQ_PERMS);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_PERMS) {
            boolean allGranted = grantResults.length > 0;
            for (int r : grantResults) {
                if (r != PackageManager.PERMISSION_GRANTED) allGranted = false;
            }
            if (allGranted) {
                toast("权限已授予，请再次点击开始备份");
            } else {
                toast("需要存储权限才能读取照片和视频");
            }
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
