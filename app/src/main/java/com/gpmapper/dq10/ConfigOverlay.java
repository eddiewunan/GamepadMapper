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
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
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
 *
 * ★ 巨集功能：每顆按鍵除了預設的「單擊」映射，也可以切換成「巨集」——
 * 輕點（不是拖曳）任何一顆按鍵標記，會開啟該按鍵的編輯面板，
 * 可以切換單擊/巨集模式、新增/刪除步驟、用 +/- 按鈕調整每一步之後的等待時間。
 * 巨集的每一步在畫面上是一顆橘色、可拖曳的標記（例如 Y1、Y2…），拖到要點擊的位置即可。
 * 面板是用按鈕調整數字，不需要輸入文字，因為這層懸浮視窗是 NOT_FOCUSABLE，叫不出鍵盤。
 */
public class ConfigOverlay extends FrameLayout {

    public interface OnSaveListener {
        void onSave();
    }

    private static final int MAX_STEPS = 20;          // 單一按鍵巨集的步驟數上限
    private static final int DEFAULT_DELAY_MS = 50;   // 新增步驟的預設等待時間
    private static final int MAX_DELAY_MS = 10000;    // 等待時間上限

    /** 某顆按鍵的巨集編輯中狀態（畫面上的工作副本，按「儲存位置」才會寫入 MappingStore） */
    private static class MacroState {
        boolean enabled = false;
        final List<StepEntry> steps = new ArrayList<>();
    }

    private static class StepEntry {
        TextView marker;
        int delayMs = DEFAULT_DELAY_MS;
    }

    private final Map<Integer, TextView> markers = new LinkedHashMap<>();
    private final Map<Integer, PointF> defaultPositions = new LinkedHashMap<>(); // 每顆標記的預設(左上角)座標，供「還原預設」使用
    private final Map<Integer, String> keyLabels = new LinkedHashMap<>();
    private final Map<Integer, MacroState> macros = new LinkedHashMap<>();
    private final TextView joystickMarker;      // 左搖桿中心
    private final TextView rightJoystickMarker; // 右搖桿中心
    private final PointF joystickDefaultPos;
    private final PointF rightJoystickDefaultPos;
    private OnSaveListener saveListener;

    private final int screenW;
    private final int screenH;

    // 巨集編輯面板
    private LinearLayout editorPanel;
    private TextView editorTitle;
    private TextView editorHint;
    private Button modeBtn;
    private ScrollView stepScroll;
    private LinearLayout stepListContainer;
    private Button addStepBtn;
    private int editingKeyCode = -1;

    public ConfigOverlay(Context context, MappingStore store) {
        super(context);
        setBackgroundColor(Color.argb(60, 0, 0, 0)); // 半透明背景，提示目前在設定模式

        // 取得「當下」螢幕的實際寬高（橫向遊戲時就會拿到橫向的寬高），
        // 所有標記的預設位置都用比例換算，才會自動適應目前的螢幕方向。
        DisplayMetrics dm = context.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        screenW = w;
        screenH = h;

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

        // L1/R1、L2/R2：一般點擊按鍵，放在畫面左右上角
        addMarker(KeyEvent.KEYCODE_BUTTON_L1, "L1", pct(w, 0.04f), pct(h, 0.06f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_R1, "R1", pct(w, 0.92f), pct(h, 0.06f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_L2, "L2", pct(w, 0.04f), pct(h, 0.18f), Color.RED, store);
        addMarker(KeyEvent.KEYCODE_BUTTON_R2, "R2", pct(w, 0.92f), pct(h, 0.18f), Color.RED, store);

        // 左搖桿錨點標記（藍色）
        joystickMarker = new TextView(context);
        joystickMarker.setText("左搖桿中心");
        styleMarker(joystickMarker, Color.BLUE);
        addView(joystickMarker, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        joystickDefaultPos = new PointF(pct(w, 0.20f), pct(h, 0.55f));
        joystickMarker.setX(joystickDefaultPos.x);
        joystickMarker.setY(joystickDefaultPos.y);
        makeDraggable(joystickMarker, null);
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
        makeDraggable(rightJoystickMarker, null);
        PointF savedRightAnchor = store.getRightJoystickAnchor();
        if (savedRightAnchor != null) {
            centerMarkerOnceLaidOut(rightJoystickMarker, savedRightAnchor);
        }

        buildEditorPanel(context);

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
        makeDraggable(tv, () -> openEditor(keyCode)); // 輕點（不是拖曳）就開啟這顆按鍵的編輯面板
        markers.put(keyCode, tv);
        defaultPositions.put(keyCode, new PointF(defaultX, defaultY));
        keyLabels.put(keyCode, label);

        // 如果這顆按鍵之前已經存過座標，等這個 View 排版完成後，把它移到「上次存的位置」，
        // 而不是停留在剛剛設的預設位置。
        PointF saved = store.getButtonPosition(keyCode);
        if (saved != null) {
            centerMarkerOnceLaidOut(tv, saved);
        }

        // 載入這顆按鍵之前存過的巨集（若有），每一步在畫面上建立一顆橘色標記
        MacroState state = new MacroState();
        state.enabled = store.isMacroEnabled(keyCode);
        for (MappingStore.MacroStep step : store.getMacroSteps(keyCode)) {
            StepEntry entry = createStepEntry(keyCode, step.delayMs);
            centerMarkerOnceLaidOut(entry.marker, new PointF(step.x, step.y));
            state.steps.add(entry);
        }
        macros.put(keyCode, state);
        relabelSteps(keyCode);
        applyMacroVisibility(keyCode);
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

    // ---------------------------------------------------------------- 巨集編輯

    /** 建立一顆巨集步驟的橘色標記（位置由呼叫端設定），輕點同樣會開啟這顆按鍵的編輯面板 */
    private StepEntry createStepEntry(int keyCode, int delayMs) {
        TextView tv = new TextView(getContext());
        styleMarker(tv, Color.rgb(255, 140, 0));
        addView(tv, new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        makeDraggable(tv, () -> openEditor(keyCode));

        StepEntry entry = new StepEntry();
        entry.marker = tv;
        entry.delayMs = delayMs;
        return entry;
    }

    /** 步驟標記的文字依順序重新編號，例如 Y1、Y2、Y3 */
    private void relabelSteps(int keyCode) {
        MacroState state = macros.get(keyCode);
        String base = keyLabels.get(keyCode);
        for (int i = 0; i < state.steps.size(); i++) {
            state.steps.get(i).marker.setText(base + (i + 1));
        }
    }

    /**
     * 巨集模式下顯示橘色步驟標記、隱藏原本的單擊標記；單擊模式則相反。
     * 用 INVISIBLE 而不是 GONE：INVISIBLE 的 View 仍然會被量測排版，getWidth() 才會是正確的，
     * 儲存「中心點座標」時才不會因為寬度為 0 而算錯位置。
     */
    private void applyMacroVisibility(int keyCode) {
        MacroState state = macros.get(keyCode);
        markers.get(keyCode).setVisibility(state.enabled ? View.INVISIBLE : View.VISIBLE);
        for (StepEntry entry : state.steps) {
            entry.marker.setVisibility(state.enabled ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private void openEditor(int keyCode) {
        editingKeyCode = keyCode;
        rebuildEditor();
    }

    private void closeEditor() {
        editingKeyCode = -1;
        editorPanel.setVisibility(View.GONE);
    }

    private void toggleMacroMode(int keyCode) {
        MacroState state = macros.get(keyCode);
        state.enabled = !state.enabled;
        // 第一次切到巨集模式時還沒有任何步驟，自動建立第一步，起點就放在原本的單擊位置
        if (state.enabled && state.steps.isEmpty()) {
            addStep(keyCode);
        }
        applyMacroVisibility(keyCode);
        rebuildEditor();
    }

    private void addStep(int keyCode) {
        MacroState state = macros.get(keyCode);
        if (state.steps.size() >= MAX_STEPS) return;

        StepEntry entry = createStepEntry(keyCode, DEFAULT_DELAY_MS);
        float x;
        float y;
        if (state.steps.isEmpty()) {
            TextView base = markers.get(keyCode);
            x = base.getX();
            y = base.getY();
        } else {
            TextView last = state.steps.get(state.steps.size() - 1).marker;
            x = last.getX() + 70;
            y = last.getY() + 70;
        }
        // 避免新增的標記跑到畫面外
        entry.marker.setX(Math.max(0, Math.min(x, screenW - 200)));
        entry.marker.setY(Math.max(0, Math.min(y, screenH - 150)));

        state.steps.add(entry);
        relabelSteps(keyCode);
        applyMacroVisibility(keyCode);
    }

    private void deleteStep(int keyCode, int index) {
        MacroState state = macros.get(keyCode);
        StepEntry removed = state.steps.remove(index);
        removeView(removed.marker);
        if (state.steps.isEmpty()) {
            state.enabled = false; // 步驟全刪光就回到單擊模式
        }
        relabelSteps(keyCode);
        applyMacroVisibility(keyCode);
        rebuildEditor();
    }

    private void adjustDelay(int keyCode, int index, int deltaMs) {
        StepEntry entry = macros.get(keyCode).steps.get(index);
        entry.delayMs = Math.max(0, Math.min(MAX_DELAY_MS, entry.delayMs + deltaMs));
        rebuildEditor();
    }

    private void buildEditorPanel(Context context) {
        editorPanel = new LinearLayout(context);
        editorPanel.setOrientation(LinearLayout.VERTICAL);
        editorPanel.setBackgroundColor(Color.argb(235, 30, 30, 30));
        editorPanel.setPadding(24, 16, 24, 16);
        editorPanel.setVisibility(View.GONE);

        int panelWidth = Math.min((int) (screenW * 0.9f), 1300);
        LayoutParams panelLp = new LayoutParams(panelWidth, LayoutParams.WRAP_CONTENT);
        panelLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        panelLp.topMargin = 30;
        addView(editorPanel, panelLp);

        editorTitle = new TextView(context);
        editorTitle.setTextColor(Color.WHITE);
        editorTitle.setTextSize(16);
        editorPanel.addView(editorTitle);

        modeBtn = new Button(context);
        modeBtn.setOnClickListener(v -> {
            if (editingKeyCode >= 0) toggleMacroMode(editingKeyCode);
        });
        editorPanel.addView(modeBtn);

        editorHint = new TextView(context);
        editorHint.setTextColor(Color.LTGRAY);
        editorHint.setTextSize(12);
        editorHint.setText("拖曳橘色標記設定每一步要點擊的位置；每步之間實際間隔 = 點擊本身約 50ms + 下方設定的等待時間");
        editorPanel.addView(editorHint);

        stepScroll = new ScrollView(context);
        stepListContainer = new LinearLayout(context);
        stepListContainer.setOrientation(LinearLayout.VERTICAL);
        stepScroll.addView(stepListContainer);
        editorPanel.addView(stepScroll,
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (screenH * 0.35f)));

        LinearLayout bottomRow = new LinearLayout(context);
        bottomRow.setOrientation(LinearLayout.HORIZONTAL);
        editorPanel.addView(bottomRow);

        addStepBtn = new Button(context);
        addStepBtn.setText("＋新增步驟");
        addStepBtn.setOnClickListener(v -> {
            if (editingKeyCode >= 0) {
                addStep(editingKeyCode);
                rebuildEditor();
            }
        });
        bottomRow.addView(addStepBtn);

        Button closeBtn = new Button(context);
        closeBtn.setText("關閉面板");
        closeBtn.setOnClickListener(v -> closeEditor());
        LinearLayout.LayoutParams closeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        closeLp.leftMargin = 24;
        bottomRow.addView(closeBtn, closeLp);
    }

    /** 依目前編輯中的按鍵狀態，重新產生面板內容（模式按鈕文字、步驟清單） */
    private void rebuildEditor() {
        if (editingKeyCode < 0) {
            editorPanel.setVisibility(View.GONE);
            return;
        }

        int keyCode = editingKeyCode;
        MacroState state = macros.get(keyCode);
        String label = keyLabels.get(keyCode);

        editorTitle.setText("按鍵「" + label + "」設定");
        modeBtn.setText(state.enabled ? "目前模式：巨集（點此切回單擊）" : "目前模式：單擊（點此切換為巨集）");
        editorHint.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
        stepScroll.setVisibility(state.enabled ? View.VISIBLE : View.GONE);
        addStepBtn.setVisibility(state.enabled ? View.VISIBLE : View.GONE);

        stepListContainer.removeAllViews();
        if (state.enabled) {
            for (int i = 0; i < state.steps.size(); i++) {
                stepListContainer.addView(buildStepRow(keyCode, label, state, i));
            }
        }

        editorPanel.setVisibility(View.VISIBLE);
        editorPanel.bringToFront(); // 新增的橘色標記是後加入的，要確保面板仍然蓋在最上層
    }

    private View buildStepRow(int keyCode, String label, MacroState state, int index) {
        StepEntry entry = state.steps.get(index);
        boolean isLast = index == state.steps.size() - 1;

        LinearLayout row = new LinearLayout(getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView text = new TextView(getContext());
        text.setTextColor(Color.WHITE);
        text.setTextSize(14);
        text.setText(isLast
                ? label + (index + 1) + "（最後一步）"
                : label + (index + 1) + " 之後等待 " + entry.delayMs + "ms");
        row.addView(text, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        // 最後一步後面沒有下一步，等待時間不會生效，所以不顯示調整按鈕
        if (!isLast) {
            row.addView(makeSmallButton("-100", v -> adjustDelay(keyCode, index, -100)));
            row.addView(makeSmallButton("-10", v -> adjustDelay(keyCode, index, -10)));
            row.addView(makeSmallButton("+10", v -> adjustDelay(keyCode, index, 10)));
            row.addView(makeSmallButton("+100", v -> adjustDelay(keyCode, index, 100)));
        }
        row.addView(makeSmallButton("刪除", v -> deleteStep(keyCode, index)));
        return row;
    }

    private Button makeSmallButton(String text, OnClickListener listener) {
        Button b = new Button(getContext());
        b.setText(text);
        b.setTextSize(12);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPadding(20, 10, 20, 10);
        b.setOnClickListener(listener);
        return b;
    }

    // ---------------------------------------------------------------- 還原 / 儲存

    /**
     * 把所有標記（含左右搖桿中心）重新排回預設的百分比位置，並清除所有巨集（回到預設的單擊狀態）。
     * 只影響畫面顯示，要再按「儲存位置」才會真正寫入；沒儲存就離開設定模式，原本已存的設定不會被改動。
     */
    private void resetAllToDefault() {
        for (Map.Entry<Integer, TextView> entry : markers.entrySet()) {
            PointF def = defaultPositions.get(entry.getKey());
            if (def != null) {
                entry.getValue().setX(def.x);
                entry.getValue().setY(def.y);
            }
        }
        for (Map.Entry<Integer, MacroState> entry : macros.entrySet()) {
            MacroState state = entry.getValue();
            for (StepEntry step : state.steps) {
                removeView(step.marker);
            }
            state.steps.clear();
            state.enabled = false;
            applyMacroVisibility(entry.getKey());
        }
        closeEditor();

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

    /**
     * 讓標記可以用手指拖曳（設定模式下這層 view 是可觸控的）。
     * 手指抬起時如果幾乎沒有移動，視為「輕點」，會呼叫 onClick（可為 null）。
     */
    private void makeDraggable(View view, Runnable onClick) {
        final int clickThreshold = 20;
        view.setOnTouchListener(new OnTouchListener() {
            float dx, dy, downRawX, downRawY;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        dx = v.getX() - event.getRawX();
                        dy = v.getY() - event.getRawY();
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        v.setX(event.getRawX() + dx);
                        v.setY(event.getRawY() + dy);
                        return true;
                    case MotionEvent.ACTION_UP:
                        float moved = Math.abs(event.getRawX() - downRawX) + Math.abs(event.getRawY() - downRawY);
                        if (moved < clickThreshold && onClick != null) {
                            onClick.run();
                        }
                        return true;
                }
                return false;
            }
        });
    }

    public void setOnSaveListener(OnSaveListener listener) {
        this.saveListener = listener;
    }

    /** 儲存目前所有標記的位置與巨集設定到 MappingStore */
    public void saveAllPositions(MappingStore store) {
        for (Map.Entry<Integer, TextView> entry : markers.entrySet()) {
            TextView tv = entry.getValue();
            // 用標記中心點座標（左上角座標 + 自身寬高一半）來估算實際點擊起點
            float centerX = tv.getX() + tv.getWidth() / 2f;
            float centerY = tv.getY() + tv.getHeight() / 2f;
            store.saveButtonPosition(entry.getKey(), centerX, centerY);
        }

        for (Map.Entry<Integer, MacroState> entry : macros.entrySet()) {
            MacroState state = entry.getValue();
            List<MappingStore.MacroStep> steps = new ArrayList<>();
            for (StepEntry step : state.steps) {
                TextView m = step.marker;
                steps.add(new MappingStore.MacroStep(
                        m.getX() + m.getWidth() / 2f,
                        m.getY() + m.getHeight() / 2f,
                        step.delayMs));
            }
            store.saveMacro(entry.getKey(), state.enabled, steps);
        }

        float jx = joystickMarker.getX() + joystickMarker.getWidth() / 2f;
        float jy = joystickMarker.getY() + joystickMarker.getHeight() / 2f;
        store.saveJoystickAnchor(jx, jy, store.getJoystickRadius());

        float rjx = rightJoystickMarker.getX() + rightJoystickMarker.getWidth() / 2f;
        float rjy = rightJoystickMarker.getY() + rightJoystickMarker.getHeight() / 2f;
        store.saveRightJoystickAnchor(rjx, rjy);
    }
}
