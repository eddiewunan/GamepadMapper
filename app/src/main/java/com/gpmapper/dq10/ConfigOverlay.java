package com.gpmapper.dq10;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 設定模式用的懸浮層：在螢幕上顯示可拖曳的按鍵標記，
 * 使用者把每個標記拖到遊戲畫面對應的按鈕位置，按下「儲存位置」後寫入 MappingStore。
 *
 * 標記的預設位置是用「螢幕寬高的百分比」計算，而不是寫死的像素值，
 * 這樣不管進入設定模式當下手機是直的還是橫的（例如橫向遊戲），
 * 標記都會依照當下實際的螢幕寬高比例分佈，不會因為方向不同而跑到畫面外或擠成一團。
 * 最終位置仍然以你實際拖曳存檔的座標為準。
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

        // 取得「當下」螢幕的實際寬高（橫向遊戲時就會拿到橫向的寬高），
        // 所有標記的預設位置都用比例換算，才會自動適應目前的螢幕方向。
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        // 方向鍵（左下區域，模擬十字鍵慣用位置）
        addMarker(KeyEvent.KEYCODE_DPAD_UP, "上", pct(w, 0.28f), pct(h, 0.65f), Color.RED);
        addMarker(KeyEvent.KEYCODE_DPAD_DOWN, "下", pct(w, 0.28f), pct(h, 0.85f), Color.RED);
        addMarker(KeyEvent.KEYCODE_DPAD_LEFT, "左", pct(w, 0.20f), pct(h, 0.75f), Color.RED);
        addMarker(KeyEvent.KEYCODE_DPAD_RIGHT, "右", pct(w, 0.36f), pct(h, 0.75f), Color.RED);

        // 功能按鍵（右下區域，模擬 A/B/X/Y 慣用位置）
        addMarker(KeyEvent.KEYCODE_BUTTON_A, "A", pct(w, 0.85f), pct(h, 0.75f), Color.RED);
        addMarker(KeyEvent.KEYCODE_BUTTON_B, "B", pct(w, 0.92f), pct(h, 0.65f), Color.RED);
        addMarker(KeyEvent.KEYCODE_BUTTON_X, "X", pct(w, 0.78f), pct(h, 0.65f), Color.RED);
        addMarker(KeyEvent.KEYCODE_BUTTON_Y, "Y", pct(w, 0.85f), pct(h, 0.55f), Color.RED);

        // START / SELECT（畫面上方中間附近）
        addMarker(KeyEvent.KEYCODE_BUTTON_START, "START", pct(w, 0.55f), pct(h, 0.08f), Color.RED);
        addMarker(KeyEvent.KEYCODE_BUTTON_SELECT, "SELECT", pct(w, 0.42f), pct(h, 0.08f), Color.RED);

        // L1/R1：一般點擊按鍵，放在畫面左右上角
        addMarker(KeyEvent.KEYCODE_BUTTON_L1, "L1", pct(w, 0.04f), pct(h, 0.06f), Color.RED);
        addMarker(KeyEvent.KEYCODE_BUTTON_R1, "R1", pct(w, 0.92f), pct(h, 0.06f), Color.RED);

        // L2/R2：固定為「拖曳手勢」的起點，L2 放開後會模擬向左滑，R2 模擬向右滑，
        // 用紫色跟一般點擊按鍵（紅色）區分，避免使用者誤以為它們是單純點擊。
        addMarker(KeyEvent.KEYCODE_BUTTON_L2, "L2(左滑)", pct(w, 0.04f), pct(h, 0.18f), Color.MAGENTA);
        addMarker(KeyEvent.KEYCODE_BUTTON_R2, "R2(右滑)", pct(w, 0.92f), pct(h, 0.18f), Color.MAGENTA);

        // 搖桿錨點標記（左搖桿的中心點，也就是遊戲畫面上虛擬搖桿的原點）
        joystickMarker = new TextView(context);
        joystickMarker.setText("搖桿中心");
        styleMarker(joystickMarker, Color.BLUE);
        addView(joystickMarker, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        joystickMarker.setX(pct(w, 0.20f));
        joystickMarker.setY(pct(h, 0.55f));
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

    private float pct(int total, float fraction) {
        return total * fraction;
    }

    private void addMarker(int keyCode, String label, float defaultX, float defaultY, int color) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        styleMarker(tv, color);
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
            // 用標記中心點座標（左上角座標 + 自身寬高一半）來估算實際點擊/滑動起點
            float centerX = tv.getX() + tv.getWidth() / 2f;
            float centerY = tv.getY() + tv.getHeight() / 2f;
            store.saveButtonPosition(entry.getKey(), centerX, centerY);
        }
        float jx = joystickMarker.getX() + joystickMarker.getWidth() / 2f;
        float jy = joystickMarker.getY() + joystickMarker.getHeight() / 2f;
        store.saveJoystickAnchor(jx, jy, store.getJoystickRadius());
    }
}
