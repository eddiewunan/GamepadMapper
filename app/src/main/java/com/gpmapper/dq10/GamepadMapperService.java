package com.gpmapper.dq10;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/**
 * 手把映射核心服務：
 * 1. 用 onKeyEvent 攔截手把離散按鍵（DPAD/A/B/X/Y…），轉成螢幕上固定座標的點擊。
 *    這部分完全不需要 focus，隨時都在運作，不影響系統其他操作。
 * 2. 用一個可取得 focus 的透明懸浮視窗接收左搖桿的類比訊號（onGenericMotionEvent），
 *    再用「延續中的手勢（continued gesture）」模擬手指按住拖曳，達成搖桿移動效果。
 *    ★ 這個視窗會搶走系統 focus，導致遊戲畫面可能整個變黑、系統手勢返回/長按失效，
 *    所以改成「手動開關」：預設不開啟，靠畫面上一顆小小的切換鈕手動啟用/停用，
 *    只有真的要移動角色時才短暫開啟，用完記得關掉。
 * 3. 提供「設定模式」：顯示可拖曳標記讓使用者自訂每個按鍵對應的螢幕座標。
 *
 * 已知限制（實測前務必留意）：
 * - 開啟搖桿收訊的當下，遊戲視窗一定會失去 focus；如果遊戲對 focus 遺失敏感
 *   （黑畫面、暫停、靜音），代表這個遊戲的移動操作可能只能靠方向鍵/按鍵，搖桿不適用。
 * - 搖桿拖曳與按鍵點擊若「同時」發生（雙手同時操作），屬於多點觸控情境，
 *   目前實作未特別處理兩者的手勢合併，可能出現其中一個動作被中斷的狀況。
 */
public class GamepadMapperService extends AccessibilityService {

    public static final String ACTION_TOGGLE_CONFIG = "com.gpmapper.dq10.ACTION_TOGGLE_CONFIG";

    // 目前支援映射的按鍵清單，要跟 ConfigOverlay 裡建立的標記一致
    private static final int[] SUPPORTED_KEYCODES = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT
    };

    private static final float JOYSTICK_DEADZONE = 0.25f;
    private static final long JOYSTICK_UPDATE_DURATION_MS = 60L; // 每段延續手勢的持續時間
    private static final long TAP_DURATION_MS = 50L;

    private WindowManager windowManager;
    private View joystickCaptureView;      // 只在使用者手動開啟時才存在：拿 focus 收搖桿訊號
    private TextView toggleButton;         // 永遠存在的小按鈕，不搶 focus，用來手動開/關搖桿收訊
    private WindowManager.LayoutParams toggleButtonParams;
    private boolean joystickCaptureEnabled = false;
    private ConfigOverlay configOverlay;   // 設定模式時顯示的可拖曳標記層
    private boolean inConfigMode = false;

    private MappingStore mappingStore;
    private Map<Integer, PointF> buttonMap = new HashMap<>();
    private PointF joystickAnchor;
    private float joystickRadius;

    // 搖桿目前正在進行中的手勢，用來延續拖曳動作；lastJoystickPoint 記錄上一段路徑的終點
    private GestureDescription.StrokeDescription activeJoystickStroke;
    private PointF lastJoystickPoint;

    private final BroadcastReceiver configReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_TOGGLE_CONFIG.equals(intent.getAction())) {
                toggleConfigMode();
            }
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mappingStore = new MappingStore(this);
        reloadMapping();

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        addToggleButton(); // 只加這顆小按鈕，不會搶 focus，系統操作完全不受影響

        IntentFilter filter = new IntentFilter(ACTION_TOGGLE_CONFIG);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(configReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(configReceiver, filter);
        }
    }

    private void reloadMapping() {
        buttonMap = mappingStore.loadAllButtons(SUPPORTED_KEYCODES);
        joystickAnchor = mappingStore.getJoystickAnchor();
        joystickRadius = mappingStore.getJoystickRadius();
    }

    /**
     * 建立永遠存在的小切換鈕：FLAG_NOT_FOCUSABLE，絕對不會搶系統 focus，
     * 也不影響手勢返回/長按等系統操作。點一下才切換搖桿收訊的開/關。
     * 按住可以拖曳到畫面上不擋視線的角落。
     */
    private void addToggleButton() {
        toggleButton = new TextView(this);
        updateToggleButtonLabel();
        toggleButton.setTextColor(Color.WHITE);
        toggleButton.setBackgroundColor(Color.argb(180, 0, 0, 0));
        toggleButton.setPadding(20, 12, 20, 12);

        toggleButtonParams = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // 關鍵：這顆按鈕永遠不搶 focus
                PixelFormat.TRANSLUCENT);
        toggleButtonParams.gravity = Gravity.TOP | Gravity.START;
        toggleButtonParams.x = 0;
        toggleButtonParams.y = 200;

        toggleButton.setOnTouchListener(new View.OnTouchListener() {
            float downRawX, downRawY, downX, downY;
            static final int CLICK_THRESHOLD = 20;

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        downX = toggleButtonParams.x;
                        downY = toggleButtonParams.y;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        toggleButtonParams.x = (int) (downX + (event.getRawX() - downRawX));
                        toggleButtonParams.y = (int) (downY + (event.getRawY() - downRawY));
                        windowManager.updateViewLayout(toggleButton, toggleButtonParams);
                        return true;
                    case MotionEvent.ACTION_UP:
                        float moved = Math.abs(event.getRawX() - downRawX) + Math.abs(event.getRawY() - downRawY);
                        if (moved < CLICK_THRESHOLD) {
                            setJoystickCaptureEnabled(!joystickCaptureEnabled);
                        }
                        return true;
                }
                return false;
            }
        });

        windowManager.addView(toggleButton, toggleButtonParams);
    }

    private void updateToggleButtonLabel() {
        toggleButton.setText(joystickCaptureEnabled ? "🎮搖桿:開" : "🎮搖桿:關");
    }

    /** 手動開關搖桿收訊。開啟時才會加入會搶 focus 的懸浮視窗，關閉時立即移除還給系統。 */
    private void setJoystickCaptureEnabled(boolean enabled) {
        if (enabled == joystickCaptureEnabled) return;
        joystickCaptureEnabled = enabled;
        updateToggleButtonLabel();

        if (enabled) {
            joystickCaptureView = new View(this) {
                @Override
                public boolean onGenericMotionEvent(MotionEvent event) {
                    handleJoystickMotion(event);
                    return true;
                }
            };
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, // 觸控完全穿透給遊戲
                    PixelFormat.TRANSLUCENT);
            // 注意：這裡刻意「不」加 FLAG_NOT_FOCUSABLE，因為需要拿到 focus 才收得到搖桿類比訊號，
            // 開啟期間遊戲視窗會失去 focus，是目前非 root 方案無法繞開的取捨，所以才做成手動開關。
            windowManager.addView(joystickCaptureView, params);
        } else {
            releaseJoystick(); // 關閉前先把還在進行中的拖曳手勢收尾，避免手指卡在畫面上
            if (joystickCaptureView != null) {
                windowManager.removeView(joystickCaptureView);
                joystickCaptureView = null;
            }
        }
    }

    private void toggleConfigMode() {
        inConfigMode = !inConfigMode;
        if (inConfigMode) {
            setJoystickCaptureEnabled(false); // 設定模式下不需要搖桿收訊，避免互相干擾
            showConfigOverlay();
        } else {
            hideConfigOverlay();
        }
    }

    private void showConfigOverlay() {
        configOverlay = new ConfigOverlay(this);
        configOverlay.setOnSaveListener(() -> {
            configOverlay.saveAllPositions(mappingStore);
            reloadMapping();
            toggleConfigMode(); // 存檔後自動退出設定模式
        });

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // 設定模式不需要攔截搖桿，讓標記可以正常觸控拖曳
                PixelFormat.TRANSLUCENT);
        windowManager.addView(configOverlay, params);
    }

    private void hideConfigOverlay() {
        if (configOverlay != null) {
            windowManager.removeView(configOverlay);
            configOverlay = null;
        }
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        if (inConfigMode) return false; // 設定模式下不做按鍵映射，避免誤觸

        int source = event.getSource();
        boolean isGamepadKey = (source & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (source & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
        if (!isGamepadKey) return false;

        PointF target = buttonMap.get(event.getKeyCode());
        if (target == null) return false; // 這個按鍵還沒設定位置，交還給系統預設處理

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            dispatchTap(target);
        }
        return true; // 消費掉這個按鍵事件
    }

    private void dispatchTap(PointF point) {
        Path path = new Path();
        path.moveTo(point.x, point.y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, TAP_DURATION_MS);
        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        dispatchGesture(gesture, null, null);
    }

    private void handleJoystickMotion(MotionEvent event) {
        if (joystickAnchor == null) return; // 尚未在設定模式裡設定搖桿中心位置

        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);
        float magnitude = (float) Math.sqrt(x * x + y * y);

        if (magnitude < JOYSTICK_DEADZONE) {
            releaseJoystick();
            return;
        }

        // 超過搖桿最大幅度時做正規化，避免超出可拖曳半徑
        if (magnitude > 1f) {
            x /= magnitude;
            y /= magnitude;
        }

        float targetX = joystickAnchor.x + x * joystickRadius;
        float targetY = joystickAnchor.y + y * joystickRadius;
        updateJoystickGesture(targetX, targetY);
    }

    /**
     * 用「延續手勢」的方式模擬手指持續按著搖桿並移動：
     * 每次收到新的搖桿數值，就從上一個點延伸一小段路徑到新的點。
     * 注意：continueStroke 要求新路徑的起點必須等於前一段路徑的終點，否則會丟例外。
     */
    private void updateJoystickGesture(float x, float y) {
        PointF from = (lastJoystickPoint != null) ? lastJoystickPoint : joystickAnchor;

        Path path = new Path();
        path.moveTo(from.x, from.y);
        path.lineTo(x, y);

        GestureDescription.StrokeDescription stroke;
        if (activeJoystickStroke == null) {
            stroke = new GestureDescription.StrokeDescription(path, 0, JOYSTICK_UPDATE_DURATION_MS, true);
        } else {
            stroke = activeJoystickStroke.continueStroke(path, 0, JOYSTICK_UPDATE_DURATION_MS, true);
        }

        GestureDescription gesture = new GestureDescription.Builder().addStroke(stroke).build();
        boolean dispatched = dispatchGesture(gesture, null, null);
        if (dispatched) {
            activeJoystickStroke = stroke;
            lastJoystickPoint = new PointF(x, y);
        }
    }

    /** 搖桿回到中立位置時，結束手勢（相當於放開手指） */
    private void releaseJoystick() {
        if (activeJoystickStroke == null || lastJoystickPoint == null) return;

        Path path = new Path();
        path.moveTo(lastJoystickPoint.x, lastJoystickPoint.y);
        path.lineTo(lastJoystickPoint.x, lastJoystickPoint.y); // 原地放開，長度為 0

        GestureDescription.StrokeDescription stroke =
                activeJoystickStroke.continueStroke(path, 0, 1, false);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);

        activeJoystickStroke = null;
        lastJoystickPoint = null;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 這個服務不需要處理無障礙事件，只用按鍵/手勢功能
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(configReceiver);
        if (windowManager != null) {
            if (toggleButton != null) windowManager.removeView(toggleButton);
            if (joystickCaptureView != null) windowManager.removeView(joystickCaptureView);
            hideConfigOverlay();
        }
    }
}
