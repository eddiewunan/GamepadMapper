package com.gpmapper.dq10;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
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
 * 1. 用 onKeyEvent 攔截手把離散按鍵（DPAD/A/B/X/Y/L1/R1/L2/R2…），轉成螢幕上固定座標的點擊或滑動。
 *    這部分完全不需要 focus，隨時都在運作，不影響系統其他操作。
 * 2. 用一個可取得 focus 的透明懸浮視窗接收左搖桿的類比訊號（onGenericMotionEvent），
 *    再用「延續中的手勢（continued gesture）」模擬手指按住拖曳，達成搖桿移動效果。
 *    ★ 這個視窗會搶走系統 focus，導致遊戲畫面可能整個變黑、系統手勢返回/長按失效，
 *    所以改成「手動開關」：預設不開啟，靠畫面上的懸浮小按鈕手動啟用/停用。
 * 3. 提供「設定模式」：顯示可拖曳標記讓使用者自訂每個按鍵對應的螢幕座標。
 * 4. 提供「測試模式」：即時顯示手把送出的 keyCode/軸值，方便確認實際訊號。
 *
 * 這三個功能（搖桿收訊、設定模式、測試模式）統一用「常駐的懸浮小按鈕」開關，
 * 不需要離開目前的遊戲、也不需要切換 App，直接在遊戲畫面上點懸浮按鈕即可切換，
 * 避免了「切回 GamepadMapper 這個 App 再想辦法跳回遊戲」的麻煩與不可靠。
 *
 * 已知限制（實測前務必留意）：
 * - 開啟搖桿收訊的當下，遊戲視窗一定會失去 focus；如果遊戲對 focus 遺失敏感
 *   （黑畫面、暫停、靜音），代表這個遊戲的移動操作可能只能靠方向鍵/按鍵，搖桿不適用。
 * - 搖桿拖曳與按鍵點擊若「同時」發生（雙手同時操作），屬於多點觸控情境，
 *   目前實作未特別處理兩者的手勢合併，可能出現其中一個動作被中斷的狀況。
 */
public class GamepadMapperService extends AccessibilityService {

    // 目前支援映射的按鍵清單，要跟 ConfigOverlay 裡建立的標記一致
    private static final int[] SUPPORTED_KEYCODES = {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_B,
            KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_Y,
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_BUTTON_SELECT,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1,
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2
    };

    private static final float JOYSTICK_DEADZONE = 0.25f;
    private static final long JOYSTICK_UPDATE_DURATION_MS = 60L; // 每段延續手勢的持續時間
    private static final long JOYSTICK_MIN_INTERVAL_MS = JOYSTICK_UPDATE_DURATION_MS; // 兩次送出的最短間隔，避免積壓佇列
    private static final long TAP_DURATION_MS = 50L;
    private static final float SWIPE_DISTANCE_PX = 300f;  // L2/R2 觸發的滑動距離
    private static final long SWIPE_DURATION_MS = 120L;   // L2/R2 滑動手勢的持續時間

    private WindowManager windowManager;
    private View joystickCaptureView;      // 只在手動開啟時才存在：拿 focus 收搖桿訊號
    private boolean joystickCaptureEnabled = false;

    private ConfigOverlay configOverlay;   // 設定模式時顯示的可拖曳標記層
    private boolean inConfigMode = false;
    private boolean joystickWasEnabledBeforeConfig = false; // 進設定模式前搖桿原本的開關狀態，離開後要還原

    private TextView testLabel;            // 測試模式用的小提示框，顯示即時 keyCode/軸值
    private boolean testMode = false;

    // 常駐的三顆懸浮控制鈕：搖桿收訊 / 設定模式 / 測試模式，全部 FLAG_NOT_FOCUSABLE，不影響系統操作
    private FloatingButton joystickButton;
    private FloatingButton configButton;
    private FloatingButton testButton;

    private MappingStore mappingStore;
    private Map<Integer, PointF> buttonMap = new HashMap<>();
    private PointF joystickAnchor;
    private float joystickRadius;

    // 搖桿目前正在進行中的手勢，用來延續拖曳動作；lastJoystickPoint 記錄上一段路徑的終點
    private GestureDescription.StrokeDescription activeJoystickStroke;
    private PointF lastJoystickPoint;
    private long lastJoystickDispatchTime = 0L; // 節流用：避免同時塞太多段延續手勢造成佇列積壓

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        mappingStore = new MappingStore(this);
        reloadMapping();

        windowManager = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        addControlButtons();
    }

    private void reloadMapping() {
        buttonMap = mappingStore.loadAllButtons(SUPPORTED_KEYCODES);
        joystickAnchor = mappingStore.getJoystickAnchor();
        joystickRadius = mappingStore.getJoystickRadius();
    }

    /** 建立三顆常駐懸浮控制鈕，垂直排列在畫面左側，可各自拖曳到不擋視線的位置 */
    private void addControlButtons() {
        joystickButton = new FloatingButton(200, () -> setJoystickCaptureEnabled(!joystickCaptureEnabled));
        configButton = new FloatingButton(280, this::toggleConfigMode);
        testButton = new FloatingButton(360, () -> setTestModeEnabled(!testMode));
        refreshButtonLabels();
    }

    private void refreshButtonLabels() {
        if (joystickButton != null) joystickButton.setLabel(joystickCaptureEnabled ? "🎮搖桿:開" : "🎮搖桿:關");
        if (configButton != null) configButton.setLabel(inConfigMode ? "⚙設定:開" : "⚙設定:關");
        if (testButton != null) testButton.setLabel(testMode ? "🔍測試:開" : "🔍測試:關");
    }

    /** 手動開關搖桿收訊。開啟時才會加入會搶 focus 的懸浮視窗，關閉時立即移除還給系統。 */
    private void setJoystickCaptureEnabled(boolean enabled) {
        if (enabled == joystickCaptureEnabled) return;
        joystickCaptureEnabled = enabled;
        refreshButtonLabels();

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

    /**
     * 設定模式：直接在目前畫面（不管是不是遊戲畫面）疊上可拖曳的按鍵標記。
     * 因為是懸浮視窗，只要遊戲已經在背景／前景，切換過去後標記仍然會顯示在遊戲畫面最上層，
     * 不需要透過本 App 的 Activity 去「跳轉」到遊戲，直接用手機原本的切換視窗方式切過去即可。
     */
    private void toggleConfigMode() {
        inConfigMode = !inConfigMode;
        refreshButtonLabels();
        if (inConfigMode) {
            // 記住目前搖桿的開關狀態，離開設定模式後要還原回這個狀態，而不是永遠變成關閉
            joystickWasEnabledBeforeConfig = joystickCaptureEnabled;
            setJoystickCaptureEnabled(false); // 設定模式下不需要搖桿收訊，避免互相干擾
            showConfigOverlay();
        } else {
            hideConfigOverlay();
            if (joystickWasEnabledBeforeConfig) {
                setJoystickCaptureEnabled(true); // 還原成進設定模式之前的狀態
            }
        }
    }

    /**
     * 測試模式：手把按什麼鍵、搖桿/十字鍵推到哪個數值，都會即時顯示在畫面小提示框上，
     * 但不會真的觸發點擊/滑動手勢，避免測試時誤觸遊戲畫面。
     * 開啟測試模式會順便打開搖桿收訊（因為要測到類比軸的數值），關閉時一併關掉。
     */
    private void setTestModeEnabled(boolean enabled) {
        if (enabled == testMode) return;
        testMode = enabled;
        refreshButtonLabels();

        if (enabled) {
            addTestLabelIfNeeded();
            testLabel.setVisibility(View.VISIBLE);
            setJoystickCaptureEnabled(true);
            showTestInfo("測試模式已開啟\n按手把按鍵，或推動搖桿/十字鍵看看數值");
        } else {
            if (testLabel != null) testLabel.setVisibility(View.GONE);
            setJoystickCaptureEnabled(false);
        }
    }

    private void addTestLabelIfNeeded() {
        if (testLabel != null) return;
        testLabel = new TextView(this);
        testLabel.setTextColor(Color.WHITE);
        testLabel.setBackgroundColor(Color.argb(200, 0, 0, 0));
        testLabel.setPadding(24, 16, 24, 16);
        testLabel.setText("測試模式待命中…");

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        params.y = 100;
        windowManager.addView(testLabel, params);
    }

    private void showTestInfo(String text) {
        if (testLabel != null) testLabel.setText(text);
    }

    private void showConfigOverlay() {
        configOverlay = new ConfigOverlay(this, mappingStore);
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

        if (testMode) {
            // 測試模式：只顯示這顆按鍵實際送出的 keyCode，不做任何映射動作
            String actionName = (event.getAction() == KeyEvent.ACTION_DOWN) ? "DOWN" : "UP";
            showTestInfo("按鍵事件\nkeyCode=" + event.getKeyCode()
                    + "\n名稱=" + KeyEvent.keyCodeToString(event.getKeyCode())
                    + "\n動作=" + actionName);
            return true;
        }

        PointF target = buttonMap.get(event.getKeyCode());
        if (target == null) return false; // 這個按鍵還沒設定位置，交還給系統預設處理

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_L2) {
                dispatchSwipe(target, -1); // 向左滑
            } else if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_R2) {
                dispatchSwipe(target, 1);  // 向右滑
            } else {
                dispatchTap(target);
            }
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

    /** L2/R2 專用：從設定的起點做一個固定距離的橫向滑動（快速拖曳），direction 為 -1（左）或 1（右） */
    private void dispatchSwipe(PointF start, int direction) {
        Path path = new Path();
        path.moveTo(start.x, start.y);
        path.lineTo(start.x + direction * SWIPE_DISTANCE_PX, start.y);
        GestureDescription.StrokeDescription stroke =
                new GestureDescription.StrokeDescription(path, 0, SWIPE_DURATION_MS);
        dispatchGesture(new GestureDescription.Builder().addStroke(stroke).build(), null, null);
    }

    private void handleJoystickMotion(MotionEvent event) {
        if (testMode) {
            // 測試模式：把左搖桿、右搖桿、十字鍵、L2/R2 扳機可能用到的軸都顯示出來，
            // 方便確認手把實際送出的是哪一種訊號、對應到哪個軸代碼。
            // （不同手把/模式下，右搖桿可能是 AXIS_Z/AXIS_RZ 或 AXIS_RX/AXIS_RY；
            //   扳機可能是 AXIS_LTRIGGER/AXIS_RTRIGGER 或 AXIS_BRAKE/AXIS_GAS，因裝置而異）
            float x = event.getAxisValue(MotionEvent.AXIS_X);
            float y = event.getAxisValue(MotionEvent.AXIS_Y);
            float z = event.getAxisValue(MotionEvent.AXIS_Z);
            float rz = event.getAxisValue(MotionEvent.AXIS_RZ);
            float rx = event.getAxisValue(MotionEvent.AXIS_RX);
            float ry = event.getAxisValue(MotionEvent.AXIS_RY);
            float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
            float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);
            float lTrigger = event.getAxisValue(MotionEvent.AXIS_LTRIGGER);
            float rTrigger = event.getAxisValue(MotionEvent.AXIS_RTRIGGER);
            float brake = event.getAxisValue(MotionEvent.AXIS_BRAKE);
            float gas = event.getAxisValue(MotionEvent.AXIS_GAS);
            showTestInfo(String.format(
                    "左類比 X=%.2f Y=%.2f\nZ=%.2f RZ=%.2f\nRX=%.2f RY=%.2f\nHAT_X=%.2f HAT_Y=%.2f\nLT=%.2f RT=%.2f\nBRAKE=%.2f GAS=%.2f",
                    x, y, z, rz, rx, ry, hatX, hatY, lTrigger, rTrigger, brake, gas));
            return; // 測試模式下不觸發實際手勢
        }

        if (joystickAnchor == null) return; // 尚未在設定模式裡設定搖桿中心位置

        float x = event.getAxisValue(MotionEvent.AXIS_X);
        float y = event.getAxisValue(MotionEvent.AXIS_Y);
        float magnitude = (float) Math.sqrt(x * x + y * y);

        if (magnitude < JOYSTICK_DEADZONE) {
            releaseJoystick(); // 放開一定要立刻處理，不能被節流卡住，否則會延遲收尾
            return;
        }

        // 節流：搖桿的 onGenericMotionEvent 觸發頻率遠高於每段延續手勢的播放時間（60ms），
        // 如果每次收到訊號都塞一段新的手勢進去，快速畫一圈就會瞬間塞進十幾段，
        // 系統必須照順序全部播完才會輪到「放開」那一下，變成放開後畫面還在「補動作」。
        // 所以這裡限制送出頻率，跟每段手勢的播放時間對齊，避免佇列越疊越多。
        long now = System.currentTimeMillis();
        if (now - lastJoystickDispatchTime < JOYSTICK_MIN_INTERVAL_MS) {
            return;
        }
        lastJoystickDispatchTime = now;

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
        lastJoystickDispatchTime = 0L;
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
        if (windowManager != null) {
            if (joystickButton != null) joystickButton.remove();
            if (configButton != null) configButton.remove();
            if (testButton != null) testButton.remove();
            if (joystickCaptureView != null) windowManager.removeView(joystickCaptureView);
            if (testLabel != null) windowManager.removeView(testLabel);
            hideConfigOverlay();
        }
    }

    /**
     * 常駐懸浮小按鈕：FLAG_NOT_FOCUSABLE，絕對不會搶系統 focus，不影響手勢返回/長按等系統操作，
     * 也不管目前在哪個 App 都會顯示在最上層。按住可拖曳到不擋視線的位置，輕點（移動距離很小）才會觸發 onTap。
     */
    private class FloatingButton {
        private final TextView view;
        private final WindowManager.LayoutParams params;

        FloatingButton(int defaultYPx, Runnable onTap) {
            view = new TextView(GamepadMapperService.this);
            view.setTextColor(Color.WHITE);
            view.setBackgroundColor(Color.argb(180, 0, 0, 0));
            view.setPadding(20, 12, 20, 12);

            params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, // 關鍵：永遠不搶 focus
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = 0;
            params.y = defaultYPx;

            view.setOnTouchListener(new View.OnTouchListener() {
                float downRawX, downRawY, downX, downY;
                static final int CLICK_THRESHOLD = 20;

                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    switch (event.getAction()) {
                        case MotionEvent.ACTION_DOWN:
                            downRawX = event.getRawX();
                            downRawY = event.getRawY();
                            downX = params.x;
                            downY = params.y;
                            return true;
                        case MotionEvent.ACTION_MOVE:
                            params.x = (int) (downX + (event.getRawX() - downRawX));
                            params.y = (int) (downY + (event.getRawY() - downRawY));
                            windowManager.updateViewLayout(view, params);
                            return true;
                        case MotionEvent.ACTION_UP:
                            float moved = Math.abs(event.getRawX() - downRawX) + Math.abs(event.getRawY() - downRawY);
                            if (moved < CLICK_THRESHOLD) {
                                onTap.run();
                            }
                            return true;
                    }
                    return false;
                }
            });

            windowManager.addView(view, params);
        }

        void setLabel(String text) {
            view.setText(text);
        }

        void remove() {
            windowManager.removeView(view);
        }
    }
}
