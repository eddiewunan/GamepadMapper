package com.gpmapper.dq10;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PointF;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
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
 *
 * 建構時會傳入 MappingStore，若某個按鍵之前已經存過座標，標記會直接顯示在「上次存的位置」。
 * 畫面下方另外有一顆「還原預設」按鈕，可以把所有標記重新排回預設的百分比位置，
 * 方便設錯了想重來；要注意這個「還原」只是重排畫面上的標記，
 * 真正生效還是要再按一次「儲存位置」才會寫回 MappingStore。
 */
public class ConfigOverlay extends FrameLayout {

    public interface OnSaveListener {
        void onSave();
    }

    private final Map<Integer, TextView> markers = new LinkedHashMap<>();
    private final Map<Integer, PointF> defaultPositions = new LinkedHashMap<>(); // 每顆標記的預設(左上角)座標，供「還原預設」使用
    private final TextView joystickMarker;      // 左搖桿中心
    private final TextView rightJoystickMarker; // 右搖桿中心
    private final PointF joystickDefaultPos;
    private final PointF rightJoystickDefaultPos;
    private OnSaveListener saveListener;

    public ConfigOverlay(Context context, MappingStore store) {
        super(context);
        setBackgroundColor(Color.argb(60, 0, 0, 0)); // 半透明背景，提示目前在設定模式

        // 取得「當下」螢幕的實際寬高（橫向遊戲時就會拿到橫向的寬高），
        // 所有標記的預設位置都用比例換算，才會自動適應目前的螢幕方向。
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;

        // 方向鍵（左下區域，模擬十字鍵慣用位置）
        addMarker(KeyEvent.KEYCODE_DPAD_UP, "上", pct(w, 0.28f), pct(h, 0.65f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_DPAD_DOWN, "下", pct(w, 0.28f), pct(h, 0.85f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_DPAD_LEFT, "左", pct(w, 0.20f), pct(h, 0.75f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_DPAD_RIGHT, "右", pct(w, 0.36f), pct(h, 0.75f), Color.RED, store);

        // 功能按鍵（右下區域，模擬 A/B/X/Y 慣用位置）
        addMarker(KeyEvent.KEYCODE_BUTTON_A, "A", pct(w, 0.85f), pct(h, 0.75f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_B, "B", pct(w, 0.92f), pct(h, 0.65f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_X, "X", pct(w, 0.78f), pct(h, 0.65f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_Y, "Y", pct(w, 0.85f), pct(h, 0.55f), Color.RED, store);

        // ST / SL（START / SELECT 縮寫，避免文字太長），放在畫面上方中間附近
        addMarker(KeyEvent.KEYCODE_BUTTON_START, "ST", pct(w, 0.55f), pct(h, 0.08f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_SELECT, "SL", pct(w, 0.42f), pct(h, 0.08f), Color.RED, store);

        // MD（KEYCODE_BUTTON_MODE = 110），一般點擊按鍵
        addMarker(KeyEvent.KEYCODE_BUTTON_MODE, "MD", pct(w, 0.48f), pct(h, 0.18f), Color.RED, store);

        // L1/R1：一般點擊按鍵，放在畫面左右上角
        addMarker(KeyEvent.KEYCODE_BUTTON_L1, "L1", pct(w, 0.04f), pct(h, 0.06f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_R1, "R1", pct(w, 0.92f), pct(h, 0.06f), Color.RED, store);

        // L2/R2：固定為「拖曳手勢」的起點，L2 放開後會模擬向左滑，R2 模擬向右滑，
        // 用紫色跟一般點擊按鍵（紅色）區分，避免使用者誤以為它們是單純點擊。
        addMarker(KeyEvent.KEYCODE_BUTTON_L2, "L2(左滑)", pct(w, 0.04f), pct(h, 0.18f), Color.MAGENTA, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_R2, "R2(右滑)", pct(w, 0.92f), pct(h, 0.18f), Color.MAGENTA, store);

        // 左搖桿錨點標記（藍色）
        joystickMarker = new TextView(context);
        joystickMarker.setText("左搖桿中心");
        styleMarker(joystickMarker, Color.BLUE);
        addView(joystickMarker, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        joystickDefaultPos = new PointF(pct(w, 0.20f), pct(h, 0.55f));
        joystickMarker.setX(joystickDefaultPos.x);
        joystickMarker.setY(joystickDefaultPos.y);
        makeDraggable(joystickMarker);
        PointF savedAnchor = store.getJoystickAnchor();
        if (savedAnchor != null) {
            centerMarkerOnceLaidOut(joystickMarker, savedAnchor);
        }

        // 右搖桿錨點標記（青色，跟左搖桿的藍色區分）
        rightJoystickMarker = new TextView(context);
        rightJoystickMarker.setText("右搖桿中心");
        styleMarker(rightJoystickMarker, Color.CYAN);
        rightJoystickMarker.setTextColor(Color.BLACK); // 青色底用黑字比較清楚
        addView(rightJoystickMarker, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        rightJoystickDefaultPos = new PointF(pct(w, 0.72f), pct(h, 0.55f));
        rightJoystickMarker.setX(rightJoystickDefaultPos.x);
        rightJoystickMarker.setY(rightJoystickDefaultPos.y);
        makeDraggable(rightJoystickMarker);
        PointF savedRightAnchor = store.getRightJoystickAnchor();
        if (savedRightAnchor != null) {
            centerMarkerOnceLaidOut(rightJoystickMarker, savedRightAnchor);
        }

        // 底部按鈕列：還原預設 + 儲存位置，並排放在畫面底部中間
        LinearLayout buttonRow = new LinearLayout(context);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        LayoutParams rowLp = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
        rowLp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        rowLp.bottomMargin = 60;
        addView(buttonRow, rowLp);

        Button resetBtn = new Button(context);
        resetBtn.setText("還原預設");
        buttonRow.addView(resetBtn);
        resetBtn.setOnClickListener(v -> resetAllToDefault());

        Button saveBtn = new Button(context);
        saveBtn.setText("儲存位置");
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.leftMargin = 24;
        buttonRow.addView(saveBtn, saveLp);
        saveBtn.setOnClickListener(v -> {
            if (saveListener != null) saveListener.onSave();
        });
    }

    private float pct(int total, float fraction) {
        return total * fraction;
    }

    private void addMarker(int keyCode, String label, float defaultX, float defaultY, int color, MappingStore store) {
        TextView tv = new TextView(getContext());
        tv.setText(label);
        styleMarker(tv, color);
        addView(tv, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        tv.setX(defaultX);
        tv.setY(defaultY);
        makeDraggable(tv);
        markers.put(keyCode, tv);
        defaultPositions.put(keyCode, new PointF(defaultX, defaultY));

        // 如果這顆按鍵之前已經存過座標，等這個 View 排版完成後，把它移到「上次存的位置」，
        // 而不是停留在剛剛設的預設位置。
        PointF saved = store.getButtonPosition(keyCode);
        if (saved != null) {
            centerMarkerOnceLaidOut(tv, saved);
        }
    }

    /**
     * 儲存的座標是「標記中心點」，但 setX/setY 設的是左上角，
     * 而 View 的寬高要等排版完成（layout pass）之後 getWidth()/getHeight() 才會有正確值，
     * 所以用 post() 排到下一輪主執行緒工作，確保這時候寬高已經量測完成。
     */
    private void centerMarkerOnceLaidOut(TextView view, PointF centerPoint) {
        view.post(() -> {
            view.setX(centerPoint.x - view.getWidth() / 2f);
            view.setY(centerPoint.y - view.getHeight() / 2f);
        });
    }

    /** 把所有標記（含左右搖桿中心）重新排回預設的百分比位置，只影響畫面顯示，要再按「儲存位置」才會真正寫入 */
    private void resetAllToDefault() {
        for (Map.Entry<Integer, TextView> entry : markers.entrySet()) {
            PointF def = defaultPositions.get(entry.getKey());
            if (def != null) {
                entry.getValue().setX(def.x);
                entry.getValue().setY(def.y);
            }
        }
        joystickMarker.setX(joystickDefaultPos.x);
        joystickMarker.setY(joystickDefaultPos.y);
        rightJoystickMarker.setX(rightJoystickDefaultPos.x);
        rightJoystickMarker.setY(rightJoystickDefaultPos.y);
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

        float rjx = rightJoystickMarker.getX() + rightJoystickMarker.getWidth() / 2f;
        float rjy = rightJoystickMarker.getY() + rightJoystickMarker.getHeight() / 2f;
        store.saveRightJoystickAnchor(rjx, rjy);
    }
}
