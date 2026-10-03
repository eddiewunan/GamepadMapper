package com.gpmapper.dq10;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PointF;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.TextView;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 手把映射核心服務：
 * 1. 用 onKeyEvent 攔截手把離散按鍵（A/B/X/Y/L1/R1/L2/R2/MODE…），轉成螢幕上固定座標的點擊。
 *    這部分完全不需要 focus，隨時都在運作，不影響系統其他操作。
 * 2. 用一個可取得 focus 的透明懸浮視窗接收類比訊號（onGenericMotionEvent）：
 *    左右搖桿（AXIS_X/Y、AXIS_Z/RZ）用「延續中的手勢」模擬手指按住拖曳；
 *    十字鍵在這隻手把上是走 HAT_X/HAT_Y 軸（不是按鍵事件），用邊緣觸發的方式偵測方向變化，觸發一次點擊。
 *    左右搖桿若同時操作，會合併進「同一次」dispatchGesture 呼叫，
 *    因為 Android 一次手勢呼叫代表當下所有手指的狀態，分開呼叫會讓後面那次打斷前一個。
 *    ★ 這個視窗會搶走系統 focus，導致遊戲畫面可能整個變黑、系統手勢返回/長按失效，
 *    所以改成「手動開關」：預設不開啟，靠畫面上的懸浮小按鈕手動啟用/停用。
 * 3. 提供「設定模式」：顯示可拖曳標記讓使用者自訂每個按鍵對應的螢幕座標。
 * 4. 提供「測試模式」：即時顯示手把送出的 keyCode/軸值，方便確認實際訊號。
 *
 * 這三個功能（搖桿收訊、設定模式、測試模式）統一用「常駐的懸浮小按鈕」開關，
 * 不需要離開目前的遊戲、也不需要切換 App，直接在遊戲畫面上點懸浮按鈕即可切換。
 * 進入設定模式或測試模式前，都會記住搖桿收訊原本的開關狀態，離開後自動還原，而不是永遠關閉。
 *
 * 已知限制（實測前務必留意）：
 * - 開啟搖桿收訊的當下，遊戲視窗一定會失去 focus；如果遊戲對 focus 遺失敏感
 *   （黑畫面、暫停、靜音），代表這個遊戲的移動操作可能只能靠方向鍵/按鍵，搖桿不適用。
 * - 搖桿拖曳與按鍵點擊若「同時」發生（雙手同時操作），屬於多點觸控情境，
 *   按鍵點擊目前是獨立呼叫 dispatchGesture，若跟搖桿拖曳同時觸發，可能會互相打斷。
 * - L2/R2 若是手把上的類比扳機，系統可能只會送出瞬間的 DOWN+UP，收不到「持續按住」的狀態，
 *   這種情況下無法做出真正的長按效果，是 Android 輸入框架的限制，不是本 App 的 bug。
 * - 所有懸浮視窗都加了「忽略螢幕安全邊界內縮」的旗標，讓畫面座標盡量對齊實際觸控注入時
 *   系統使用的原始座標系統，避免因為瀏海/圓角/黑邊裁切造成的座標系統不一致。
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
            KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2,
            KeyEvent.KEYCODE_BUTTON_MODE
    };

    private static final float JOYSTICK_DEADZONE = 0.25f;
    private static final long JOYSTICK_UPDATE_DURATION_MS = 60L; // 每段延續手勢的持續時間
    private static final long JOYSTICK_MIN_INTERVAL_MS = JOYSTICK_UPDATE_DURATION_MS; // 兩次送出的最短間隔，避免積壓佇列
    private static final long TAP_DURATION_MS = 50L;
    private static final float HAT_TRIGGER_THRESHOLD = 0.5f; // HAT 軸判定「有壓到某個方向」的門檻

    private WindowManager windowManager;
    private View joystickCaptureView;      // 只在手動開啟時才存在：拿 focus 收搖桿/十字鍵訊號
    private boolean joystickCaptureEnabled = false;

    private ConfigOverlay configOverlay;   // 設定模式時顯示的可拖曳標記層
    private boolean inConfigMode = false;
    private boolean joystickWasEnabledBeforeConfig = false; // 進設定模式前搖桿原本的開關狀態，離開後要還原

    private TextView testLabel;            // 測試模式用的小提示框，顯示即時 keyCode/軸值
    private boolean testMode = false;
    private boolean joystickWasEnabledBeforeTest = false; // 進測試模式前搖桿原本的開關狀態，離開後要還原

    // 常駐的三顆懸浮控制鈕：搖桿收訊 / 設定模式 / 測試模式，全部 FLAG_NOT_FOCUSABLE，不影響系統操作
    private FloatingButton joystickButton;
    private FloatingButton configButton;
    private FloatingButton testButton;

    private MappingStore mappingStore;
    private Map<Integer, PointF> buttonMap = new HashMap<>();
    private Map<Integer, List<MappingStore.MacroStep>> macroMap = new HashMap<>(); // 已啟用巨集的按鍵 -> 步驟清單
    private final Handler macroHandler = new Handler(Looper.getMainLooper());
    private final Set<Integer> runningMacros = new HashSet<>(); // 正在執行中的巨集，避免同一顆按鍵連按造成重疊
    private PointF joystickAnchor;       // 左搖桿中心（AXIS_X / AXIS_Y）
    private PointF rightJoystickAnchor;  // 右搖桿中心（AXIS_Z / AXIS_RZ）
    private float joystickRadius;

    private long lastJoystickDispatchTime = 0L; // 節流用：避免同時塞太多段延續手勢造成佇列積壓

    // 十字鍵目前的 HAT 軸狀態，用來判斷「剛從中立變成按到某個方向」的那一瞬間（邊緣觸發）
    private float lastHatX = 0f;
    private float lastHatY = 0f;

    /** 每根搖桿各自的手勢延續狀態（左右各一份，才能同時拖著兩個點移動） */
    private static class JoystickState {
        GestureDescription.StrokeDescription activeStroke;
        PointF lastPoint;
    }

    private final JoystickState leftJoystick = new JoystickState();
    private final JoystickState rightJoystick = new JoystickState();

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
        macroMap = mappingStore.loadEnabledMacros(SUPPORTED_KEYCODES);
        joystickAnchor = mappingStore.getJoystickAnchor();
        rightJoystickAnchor = mappingStore.getRightJoystickAnchor();
        joystickRadius = mappingStore.getJoystickRadius();
    }

    /**
     * 幫懸浮視窗的 LayoutParams 加上「忽略螢幕安全邊界內縮」的旗標，
     * 讓視窗盡量佔滿原始物理螢幕（包含瀏海/圓角/黑邊區域），
     * 避免視窗座標系統跟 dispatchGesture 實際注入觸控時用的座標系統不一致，
     * 這是為了解決「畫面上設定的位置」跟「實際觸發位置」有固定偏移量的問題。
     */
    private void applyFullScreenFlags(WindowManager.LayoutParams params) {
        params.flags |= WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        }
    }

    /** 建立三顆常駐懸浮控制鈕，垂直排列在畫面左側，可各自拖曳到不擋視線的位置 */
    private void addControlButtons() {
        joystickButton = new FloatingButton(200, () -> setJoystickCaptureEnabled(!joystickCaptureEnabled));
        configButton = new FloatingButton(280, this::toggleConfigMode);
        testButton = new FloatingButton(360, this::toggleTestMode);
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
            applyFullScreenFlags(params);
            windowManager.addView(joystickCaptureView, params);
        } else {
            releaseAllJoysticks(); // 關閉前先把還在進行中的拖曳手勢收尾，避免手指卡在畫面上
            if (joystickCaptureView != null) {
                windowManager.removeView(joystickCaptureView);
                joystickCaptureView = null;
            }
        }
    }

    /**
     * 設定模式：直接在目前畫面（不管是不是遊戲畫面）疊上可拖曳的按鍵標記。
     * 因為是懸浮視窗，只要遊戲已經在背景／前景，切換過去後標記仍然會顯示在遊戲畫面最上層，
     * 不需要透過本 App 的 Activity 去「跳轉」到遊戲。
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
     * 開啟測試模式會順便打開搖桿收訊（因為要測到類比軸的數值），
     * 關閉時會還原成「進測試模式之前」搖桿原本的開關狀態，而不是永遠關閉。
     */
    private void toggleTestMode() {
        testMode = !testMode;
        refreshButtonLabels();

        if (testMode) {
            joystickWasEnabledBeforeTest = joystickCaptureEnabled;
            addTestLabelIfNeeded();
            testLabel.setVisibility(View.VISIBLE);
            setJoystickCaptureEnabled(true);
            showTestInfo("測試模式已開啟\n按手把按鍵，或推動搖桿/十字鍵看看數值");
        } else {
            if (testLabel != null) testLabel.setVisibility(View.GONE);
            setJoystickCaptureEnabled(joystickWasEnabledBeforeTest); // 還原成進測試模式之前的狀態
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
        applyFullScreenFlags(params);
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
        applyFullScreenFlags(params);
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

        if (testMode) {
            // 測試模式：不管來源類型，所有按鍵事件都顯示（包含 keyCode=0 的未知按鍵），
            // 這樣才分得出「手把真的沒送訊號」跟「訊號來源類型不是手把而被過濾掉」。
            // 只有手把來源的事件會被消費；其他來源（例如音量鍵、返回鍵）只顯示、不攔截，
            // 否則測試期間手機的實體按鍵會全部失效。
            showKeyTestInfo(event, isGamepadKey);
            return isGamepadKey;
        }

        if (!isGamepadKey) return false;

        int keyCode = event.getKeyCode();
        boolean hasMacro = macroMap.containsKey(keyCode);
        boolean hasTap = buttonMap.containsKey(keyCode);
        if (!hasMacro && !hasTap) return false; // 這個按鍵還沒設定過，交還給系統預設處理

        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            triggerKey(keyCode);
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

    /** 測試模式專用：顯示一個按鍵事件的完整資訊，方便辨識背鍵這類沒有標準代碼的按鍵 */
    private void showKeyTestInfo(KeyEvent event, boolean isGamepadKey) {
        String actionName = (event.getAction() == KeyEvent.ACTION_DOWN) ? "DOWN" : "UP";
        InputDevice device = event.getDevice();
        String deviceName = (device != null) ? device.getName() : "未知";
        showTestInfo("按鍵事件" + (isGamepadKey ? "" : "（非手把來源，未攔截）")
                + "\nkeyCode=" + event.getKeyCode()
                + "\n名稱=" + KeyEvent.keyCodeToString(event.getKeyCode())
                + "\nscanCode=" + event.getScanCode()
                + "\n動作=" + actionName
                + "\n來源=0x" + Integer.toHexString(event.getSource())
                + "\n裝置=" + deviceName);
    }

    /**
     * 測試模式專用：列出「上面已經顯示的標準軸以外」、目前數值明顯不是 0 的軸。
     * 有些手把的額外按鍵（例如背鍵）不是用按鍵事件，而是用 AXIS_GENERIC_1～16 這類軸送出，
     * 這樣可以直接看出來。
     */
    private String describeOtherAxes(MotionEvent event) {
        int[] shown = {
                MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
                MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
                MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS
        };
        InputDevice device = event.getDevice();
        if (device == null) return "其他軸：（無裝置資訊）";

        StringBuilder sb = new StringBuilder();
        for (InputDevice.MotionRange range : device.getMotionRanges()) {
            int axis = range.getAxis();
            boolean alreadyShown = false;
            for (int a : shown) {
                if (a == axis) {
                    alreadyShown = true;
                    break;
                }
            }
            if (alreadyShown) continue;

            float value = event.getAxisValue(axis);
            if (Math.abs(value) > 0.05f) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(MotionEvent.axisToString(axis)).append('=').append(String.format("%.2f", value));
            }
        }
        return "其他軸：" + (sb.length() == 0 ? "（無）" : sb.toString());
    }

    private void handleJoystickMotion(MotionEvent event) {
        if (testMode) {
            // 測試模式：把左搖桿、右搖桿、十字鍵、L2/R2 扳機可能用到的軸都顯示出來，
            // 方便確認手把實際送出的是哪一種訊號、對應到哪個軸代碼。
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
                    x, y, z, rz, rx, ry, hatX, hatY, lTrigger, rTrigger, brake, gas)
                    + "\n" + describeOtherAxes(event)
                    + "\n來源=0x" + Integer.toHexString(event.getSource()));
            return; // 測試模式下不觸發實際手勢
        }

        handleDpadHat(event);
        handleRealJoystickMotion(event);
    }

    /**
     * 十字鍵在這隻手把上是走 HAT_X/HAT_Y 軸（數值只有 -1/0/1），不是按鍵事件。
     * 用「邊緣觸發」的方式偵測：從中立(0)變成某個方向(±1)的那一瞬間，觸發一次點擊，
     * 行為跟原本用按鍵事件做的十字鍵一樣是單點點擊，不是持續拖曳。
     */
    private void handleDpadHat(MotionEvent event) {
        float hatX = event.getAxisValue(MotionEvent.AXIS_HAT_X);
        float hatY = event.getAxisValue(MotionEvent.AXIS_HAT_Y);

        checkHatEdge(lastHatX, hatX, -1f, KeyEvent.KEYCODE_DPAD_LEFT);
        checkHatEdge(lastHatX, hatX, 1f, KeyEvent.KEYCODE_DPAD_RIGHT);
        checkHatEdge(lastHatY, hatY, -1f, KeyEvent.KEYCODE_DPAD_UP);
        checkHatEdge(lastHatY, hatY, 1f, KeyEvent.KEYCODE_DPAD_DOWN);

        lastHatX = hatX;
        lastHatY = hatY;
    }

    private void checkHatEdge(float previousValue, float currentValue, float triggerValue, int keyCode) {
        boolean wasAtTrigger = Math.abs(previousValue - triggerValue) < HAT_TRIGGER_THRESHOLD;
        boolean isAtTrigger = Math.abs(currentValue - triggerValue) < HAT_TRIGGER_THRESHOLD;
        if (!wasAtTrigger && isAtTrigger) {
            triggerKey(keyCode); // 十字鍵也支援巨集，跟一般按鍵走同一套邏輯
        }
    }

    /**
     * 按鍵被觸發時的統一入口：如果這顆按鍵啟用了巨集就執行巨集，否則走原本的單擊映射。
     * 一般按鍵（onKeyEvent）與十字鍵（HAT 軸）都會呼叫這裡。
     */
    private void triggerKey(int keyCode) {
        List<MappingStore.MacroStep> steps = macroMap.get(keyCode);
        if (steps != null && !steps.isEmpty()) {
            runMacro(keyCode, steps);
            return;
        }
        PointF target = buttonMap.get(keyCode);
        if (target != null) dispatchTap(target);
    }

    /**
     * 依序執行巨集：點擊第 1 步位置 -> 等待 -> 點擊第 2 步位置 -> ...
     * 每一步的開始時間 = 前一步開始時間 + 點擊本身的持續時間 + 前一步設定的等待時間，
     * 這樣前一步的點擊一定已經結束才會開始下一步，不會因為「新手勢打斷前一個手勢」而漏掉動作。
     * 巨集執行期間，同一顆按鍵再按會被忽略；最後一步之後的等待時間不會生效。
     * 注意：巨集裡的每一步點擊都是獨立的 dispatchGesture 呼叫，
     * 如果同時有搖桿正在拖曳，可能會被打斷（跟一般按鍵的既有限制相同）。
     */
    private void runMacro(int keyCode, List<MappingStore.MacroStep> steps) {
        if (!runningMacros.add(keyCode)) return; // 這顆按鍵的巨集還在跑，忽略這次觸發

        long startTime = 0L;
        for (int i = 0; i < steps.size(); i++) {
            MappingStore.MacroStep step = steps.get(i);
            macroHandler.postDelayed(() -> dispatchTap(new PointF(step.x, step.y)), startTime);
            startTime += TAP_DURATION_MS;
            if (i < steps.size() - 1) startTime += step.delayMs;
        }
        // 全部步驟結束後才解除「執行中」狀態
        macroHandler.postDelayed(() -> runningMacros.remove(keyCode), startTime);
    }

    /**
     * 左右搖桿合併處理：每次收到訊號，同時檢查左右搖桿目前的狀態，
     * 把「需要更新」的那幾根手指，合併進同一次 dispatchGesture 呼叫裡送出去。
     * 這是必要的，因為 Android 一次 dispatchGesture 代表的是「當下所有手指」的狀態，
     * 分開呼叫左搖桿一次、右搖桿一次，後面那次會把前一次的手指直接打斷。
     */
    private void handleRealJoystickMotion(MotionEvent event) {
        boolean leftHasAnchor = joystickAnchor != null;
        boolean rightHasAnchor = rightJoystickAnchor != null;
        if (!leftHasAnchor && !rightHasAnchor) return; // 兩根搖桿都還沒在設定模式裡設定過中心點

        float lx = event.getAxisValue(MotionEvent.AXIS_X);
        float ly = event.getAxisValue(MotionEvent.AXIS_Y);
        float rx = event.getAxisValue(MotionEvent.AXIS_Z);
        float ry = event.getAxisValue(MotionEvent.AXIS_RZ);

        float lMag = (float) Math.sqrt(lx * lx + ly * ly);
        float rMag = (float) Math.sqrt(rx * rx + ry * ry);

        boolean leftActive = leftHasAnchor && lMag >= JOYSTICK_DEADZONE;
        boolean rightActive = rightHasAnchor && rMag >= JOYSTICK_DEADZONE;

        boolean leftWasActive = leftJoystick.activeStroke != null;
        boolean rightWasActive = rightJoystick.activeStroke != null;

        if (!leftActive && !rightActive && !leftWasActive && !rightWasActive) {
            return; // 兩邊都是中立、也沒有殘留中的手勢，完全不用處理
        }

        // 節流：只有在「持續移動中」（不是剛開始按、也不是剛放開）才做頻率限制，
        // 剛按下或剛放開的那一刻要立即處理，不能被節流卡住。
        boolean justStartedOrEnded = (leftActive != leftWasActive) || (rightActive != rightWasActive);
        long now = System.currentTimeMillis();
        if (!justStartedOrEnded && now - lastJoystickDispatchTime < JOYSTICK_MIN_INTERVAL_MS) {
            return;
        }
        lastJoystickDispatchTime = now;

        GestureDescription.Builder builder = new GestureDescription.Builder();
        boolean anyStroke = false;

        if (leftActive) {
            if (lMag > 1f) { lx /= lMag; ly /= lMag; }
            float tx = joystickAnchor.x + lx * joystickRadius;
            float ty = joystickAnchor.y + ly * joystickRadius;
            GestureDescription.StrokeDescription stroke = buildMoveStroke(leftJoystick, joystickAnchor, tx, ty);
            builder.addStroke(stroke);
            leftJoystick.activeStroke = stroke;
            leftJoystick.lastPoint = new PointF(tx, ty);
            anyStroke = true;
        } else if (leftWasActive) {
            builder.addStroke(buildEndStroke(leftJoystick));
            leftJoystick.activeStroke = null;
            leftJoystick.lastPoint = null;
            anyStroke = true;
        }

        if (rightActive) {
            if (rMag > 1f) { rx /= rMag; ry /= rMag; }
            float tx = rightJoystickAnchor.x + rx * joystickRadius;
            float ty = rightJoystickAnchor.y + ry * joystickRadius;
            GestureDescription.StrokeDescription stroke = buildMoveStroke(rightJoystick, rightJoystickAnchor, tx, ty);
            builder.addStroke(stroke);
            rightJoystick.activeStroke = stroke;
            rightJoystick.lastPoint = new PointF(tx, ty);
            anyStroke = true;
        } else if (rightWasActive) {
            builder.addStroke(buildEndStroke(rightJoystick));
            rightJoystick.activeStroke = null;
            rightJoystick.lastPoint = null;
            anyStroke = true;
        }

        if (!anyStroke) return;

        boolean dispatched = dispatchGesture(builder.build(), null, null);
        if (!dispatched) {
            // 自我修復：如果這次呼叫被系統拒絕，代表剛剛記錄的「延續中」狀態其實沒有真的生效，
            // 若不重置，下一次會拿一個系統根本不認得的 StrokeDescription 去呼叫 continueStroke，
            // 導致那根搖桿的手勢鏈永久卡死（這可能就是「左右搖桿不能同時推」的原因之一）。
            // 這裡直接整組重置，讓兩根搖桿下次都從「重新按下」開始，至少不會卡死。
            leftJoystick.activeStroke = null;
            leftJoystick.lastPoint = null;
            rightJoystick.activeStroke = null;
            rightJoystick.lastPoint = null;
            lastJoystickDispatchTime = 0L;
        }
    }

    /**
     * 建立/延續一段搖桿移動用的手勢片段，起點是這根搖桿上一次移動到的位置（沒有的話用中心點當起點）。
     * 注意：continueStroke 要求新路徑的起點必須等於前一段路徑的終點，否則會丟例外。
     */
    private GestureDescription.StrokeDescription buildMoveStroke(JoystickState state, PointF anchorFallback, float toX, float toY) {
        PointF from = (state.lastPoint != null) ? state.lastPoint : anchorFallback;
        Path path = new Path();
        path.moveTo(from.x, from.y);
        path.lineTo(toX, toY);

        if (state.activeStroke == null) {
            return new GestureDescription.StrokeDescription(path, 0, JOYSTICK_UPDATE_DURATION_MS, true);
        }
        return state.activeStroke.continueStroke(path, 0, JOYSTICK_UPDATE_DURATION_MS, true);
    }

    /** 搖桿回到中立位置時，結束這根手指的手勢（相當於放開手指），原地放開、長度為 0 */
    private GestureDescription.StrokeDescription buildEndStroke(JoystickState state) {
        PointF p = state.lastPoint;
        Path path = new Path();
        path.moveTo(p.x, p.y);
        path.lineTo(p.x, p.y);
        return state.activeStroke.continueStroke(path, 0, 1, false);
    }

    /** 關閉搖桿收訊前呼叫：把左右兩根搖桿還在進行中的手勢一起收尾，避免手指卡在畫面上 */
    private void releaseAllJoysticks() {
        boolean leftActive = leftJoystick.activeStroke != null;
        boolean rightActive = rightJoystick.activeStroke != null;
        if (!leftActive && !rightActive) return;

        GestureDescription.Builder builder = new GestureDescription.Builder();
        if (leftActive) {
            builder.addStroke(buildEndStroke(leftJoystick));
            leftJoystick.activeStroke = null;
            leftJoystick.lastPoint = null;
        }
        if (rightActive) {
            builder.addStroke(buildEndStroke(rightJoystick));
            rightJoystick.activeStroke = null;
            rightJoystick.lastPoint = null;
        }
        dispatchGesture(builder.build(), null, null);
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
        macroHandler.removeCallbacksAndMessages(null); // 取消還沒執行的巨集步驟
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
            applyFullScreenFlags(params);

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
