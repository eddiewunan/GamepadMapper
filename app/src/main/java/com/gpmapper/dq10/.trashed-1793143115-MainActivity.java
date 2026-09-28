package com.gpmapper.dq10;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        findViewById(R.id.btnAccessibility).setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        findViewById(R.id.btnOverlay).setOnClickListener(v -> {
            Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        });

        // 設定模式與測試模式已經改成常駐的懸浮小按鈕（🎮/⚙/🔍），
        // 不需要再透過這個 App 的畫面觸發，兩個權限開啟後直接切到遊戲，
        // 用懸浮按鈕操作即可，不用再切換回這個 App。
    }
}
