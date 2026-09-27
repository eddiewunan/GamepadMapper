package com.gpmapper.dq10;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.PointF;

import java.util.HashMap;
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
}
