package com.gpmapper.dq10;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.Toast;

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

        findViewById(R.id.btnConfig).setOnClickListener(v -> {
            // 通知無障礙服務切換到「設定模式」，顯示可拖曳的按鍵位置標記
            Intent intent = new Intent(GamepadMapperService.ACTION_TOGGLE_CONFIG);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
            Toast.makeText(this, "已切換設定模式，請到遊戲畫面上拖曳標記並按「儲存位置」",
                    Toast.LENGTH_LONG).show();
            moveTaskToBack(true); // 退到背景，讓遊戲畫面顯示出來
        });
    }
}
