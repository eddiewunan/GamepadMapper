package com.gpmapper.dq10;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 負責讀取/寫入按鍵座標與搖桿設定，使用 SharedPreferences 儲存。
 * 每個按鍵用 keyCode 當作 key 前綴，分別存 x/y 兩個 float。
 */
public class MappingStore {

    private static final String PREFS_NAME = "gp_mapping";
    private static final String KEY_JOY_ANCHOR_X = "joy_anchor_x";
    private static final String KEY_JOY_ANCHOR_Y = "joy_anchor_y";
    private static final String KEY_JOY_RADIUS = "joy_radius";
    private static final String KEY_JOY_R_ANCHOR_X = "joy_anchor_right_x";
    private static final String KEY_JOY_R_ANCHOR_Y = "joy_anchor_right_y";
    private static final float DEFAULT_JOY_RADIUS = 150f; // 預設搖桿可拖曳半徑（像素）

    private final SharedPreferences prefs;

    public MappingStore(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public void saveButtonPosition(int keyCode, float x, float y) {
        prefs.edit()
                .putFloat("btn_" + keyCode + "_x", x)
                .putFloat("btn_" + keyCode + "_y", y)
                .apply();
    }

    /** 若尚未設定過，回傳 null；呼叫端要自行處理「這顆按鍵還沒設定」的情況 */
    public PointF getButtonPosition(int keyCode) {
        if (!prefs.contains("btn_" + keyCode + "_x")) return null;
        float x = prefs.getFloat("btn_" + keyCode + "_x", 0);
        float y = prefs.getFloat("btn_" + keyCode + "_y", 0);
        return new PointF(x, y);
    }

    public void saveJoystickAnchor(float x, float y, float radius) {
        prefs.edit()
                .putFloat(KEY_JOY_ANCHOR_X, x)
                .putFloat(KEY_JOY_ANCHOR_Y, y)
                .putFloat(KEY_JOY_RADIUS, radius)
                .apply();
    }

    public PointF getJoystickAnchor() {
        if (!prefs.contains(KEY_JOY_ANCHOR_X)) return null;
        return new PointF(prefs.getFloat(KEY_JOY_ANCHOR_X, 0), prefs.getFloat(KEY_JOY_ANCHOR_Y, 0));
    }

    public float getJoystickRadius() {
        return prefs.getFloat(KEY_JOY_RADIUS, DEFAULT_JOY_RADIUS);
    }

    /** 右搖桿的中心點，跟左搖桿共用同一個可拖曳半徑（joy_radius） */
    public void saveRightJoystickAnchor(float x, float y) {
        prefs.edit()
                .putFloat(KEY_JOY_R_ANCHOR_X, x)
                .putFloat(KEY_JOY_R_ANCHOR_Y, y)
                .apply();
    }

    public PointF getRightJoystickAnchor() {
        if (!prefs.contains(KEY_JOY_R_ANCHOR_X)) return null;
        return new PointF(prefs.getFloat(KEY_JOY_R_ANCHOR_X, 0), prefs.getFloat(KEY_JOY_R_ANCHOR_Y, 0));
    }

    /** 載入所有已設定的按鍵座標，回傳 keyCode -> PointF 的對照表 */
    public Map<Integer, PointF> loadAllButtons(int[] supportedKeyCodes) {
        Map<Integer, PointF> map = new HashMap<>();
        for (int code : supportedKeyCodes) {
            PointF p = getButtonPosition(code);
            if (p != null) map.put(code, p);
        }
        return map;
    }

    /**
     * 巨集的其中一步：在 (x, y) 點擊一下，然後等待 delayMs 毫秒再執行下一步。
     * 最後一步的 delayMs 不會生效（後面沒有下一步了）。
     */
    public static class MacroStep {
        public final float x;
        public final float y;
        public final int delayMs;

        public MacroStep(float x, float y, int delayMs) {
            this.x = x;
            this.y = y;
            this.delayMs = delayMs;
        }
    }

    /**
     * 儲存某顆按鍵的巨集設定。enabled 為 false 時，步驟資料仍然會保留，
     * 只是按下按鍵時走原本的單擊映射，方便使用者暫時切回單擊又不會弄丟巨集內容。
     * 步驟用文字編碼成 "x,y,delay;x,y,delay;..." 存進 SharedPreferences。
     */
    public void saveMacro(int keyCode, boolean enabled, List<MacroStep> steps) {
        StringBuilder sb = new StringBuilder();
        for (MacroStep step : steps) {
            if (sb.length() > 0) sb.append(';');
            sb.append(step.x).append(',').append(step.y).append(',').append(step.delayMs);
        }
        prefs.edit()
                .putBoolean("macro_enabled_" + keyCode, enabled)
                .putString("macro_steps_" + keyCode, sb.toString())
                .apply();
    }

    public boolean isMacroEnabled(int keyCode) {
        return prefs.getBoolean("macro_enabled_" + keyCode, false);
    }

    /** 讀取某顆按鍵已存的巨集步驟，沒有設定過就回傳空清單（不會是 null） */
    public List<MacroStep> getMacroSteps(int keyCode) {
        List<MacroStep> result = new ArrayList<>();
        String raw = prefs.getString("macro_steps_" + keyCode, "");
        if (raw == null || raw.isEmpty()) return result;

        for (String part : raw.split(";")) {
            String[] fields = part.split(",");
            if (fields.length != 3) continue;
            try {
                result.add(new MacroStep(
                        Float.parseFloat(fields[0]),
                        Float.parseFloat(fields[1]),
                        Integer.parseInt(fields[2])));
            } catch (NumberFormatException ignored) {
                // 資料格式壞掉的那一步直接略過，不影響其他步驟
            }
        }
        return result;
    }

    /** 載入所有「已啟用巨集且至少有一步」的按鍵，回傳 keyCode -> 步驟清單 */
    public Map<Integer, List<MacroStep>> loadEnabledMacros(int[] supportedKeyCodes) {
        Map<Integer, List<MacroStep>> map = new HashMap<>();
        for (int code : supportedKeyCodes) {
            if (!isMacroEnabled(code)) continue;
            List<MacroStep> steps = getMacroSteps(code);
            if (!steps.isEmpty()) map.put(code, steps);
        }
        return map;
    }
}
