package com.gpmapper.dq10;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 設定模式用的懸浮層：在螢幕上顯示可拖曳的按鍵標記，
 * 使用者把每個標記拖到遊戲畫面對應的按鈕位置，按下「儲存位置」後寫入 MappingStore。
 */
public class ConfigOverlay extends FrameLayout {

    public interface OnSaveListener {
        void onSave();
    }

    private final Map<Integer, TextView> markers = new LinkedHashMap<>();
    private final TextView joystickMarker;
    private OnSaveListener saveListener;

    public ConfigOverlay(Context context) {
        super(context);
        setBackgroundColor(Color.argb(60, 0, 0, 0)); // 半透明背景，提示目前在設定模式

        // 手把按鍵標記：keyCode -> 顯示文字、預設位置（僅供第一次擺放參考，之後可自由拖曳）
        addMarker(KeyEvent.KEYCODE_DPAD_UP, "上", 300, 800);
        addMarker(KeyEvent.KEYCODE_DPAD_DOWN, "下", 300, 1000);
        addMarker(KeyEvent.KEYCODE_DPAD_LEFT, "左", 200, 900);
        addMarker(KeyEvent.KEYCODE_DPAD_RIGHT, "右", 400, 900);
        addMarker(KeyEvent.KEYCODE_BUTTON_A, "A", 900, 900);
        addMarker(KeyEvent.KEYCODE_BUTTON_B, "B", 1000, 800);
        addMarker(KeyEvent.KEYCODE_BUTTON_X, "X", 800, 800);
        addMarker(KeyEvent.KEYCODE_BUTTON_Y, "Y", 900, 700);
        addMarker(KeyEvent.KEYCODE_BUTTON_START, "START", 700, 200);
        addMarker(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", 500, 200);

        // 搖桿錨點標記（左搖桿的中心點，也就是遊戲畫面上虛擬搖桿的原點）
        joystickMarker = new TextView(context);
        joystickMarker.setText("搖桿中心");
        styleMarker(joystickMarker, Color.BLUE);
        addView(joystickMarker, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        joystickMarker.setX(300);
        joystickMarker.setY(1400);
        makeDraggable(joystickMarker);

        // 儲存按鈕，固定在畫面底部中間
        Button saveBtn = new Button(context);
        saveBtn.setText("儲存位置");
        LayoutParams lp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.bottomMargin = 60;
        addView(saveBtn, lp);
        saveBtn.setOnClickListener(v -> {
            if (saveListener != null) saveListener.onSave();
        });
    }

    private void addMarker(int keyCode, String label, float defaultX, float defaultY) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        styleMarker(tv, Color.RED);
        addView(tv, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        tv.setX(defaultX);
        tv.setY(defaultY);
        makeDraggable(tv);
        markers.put(keyCode, tv);
    }

    private void styleMarker(TextView tv, int color) {
        tv.setTextColor(Color.WHITE);
        tv.setBackgroundColor(color);
        tv.setPadding(24, 16, 24, 16);
        tv.setAlpha(0.85f);
    }

    /** 讓標記可以用手指拖曳（設定模式下這層 view 是可觸控的） */
    private void makeDraggable(View view) {
        view.setOnTouchListener(new OnTouchListener() {
            float dx, dy;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = v.getX() - event.getRawX();
                        dy = v.getY() - event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.setX(event.getRawX() + dx);
                        v.setY(event.getRawY() + dy);
                        return true;
                }
                return false;
            }
        });
    }

    public void setOnSaveListener(OnSaveListener listener) {
        this.saveListener = listener;
    }

    /** 儲存目前所有標記的位置到 MappingStore */
    public void saveAllPositions(MappingStore store) {
        for (Map.Entry<Integer, TextView> entry : markers.entrySet()) {
            TextView tv = entry.getValue();
            // 用標記中心點座標（左上角座標 + 自身寬高一半）來估算實際點擊點
            float centerX = tv.getX() + tv.getWidth() / 2f;
            float centerY = tv.getY() + tv.getHeight() / 2f;
            store.saveButtonPosition(entry.getKey(), centerX, centerY);
        }
        float jx = joystickMarker.getX() + joystickMarker.getWidth() / 2f;
        float jy = joystickMarker.getY() + joystickMarker.getHeight() / 2f;
        store.saveJoystickAnchor(jx, jy, store.getJoystickRadius());
    }
}
