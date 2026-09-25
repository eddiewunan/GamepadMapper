# GamepadMapper（DQ10 手把映射，非 root）

## 為什麼給原始碼而不是 APK
這是完整可編譯的 Android Studio 專案，不是已編譯簽名的 APK。我這邊的環境沒有 Android SDK / Gradle 工具鏈也無法連到 Google Maven，無法在這裡編出可安裝的 APK。你用 Android Studio 開啟後可以直接 Run 到手機上安裝測試，或是 Build > Build APK(s) 匯出 apk 檔給別人裝。

## 建置步驟
1. 用 Android Studio 開啟 `GamepadMapper` 這個資料夾（File > Open）。
2. 等 Gradle sync 完成（需要網路抓 AGP 8.5.0 與相關依賴）。
3. 用 USB 接上 vivo X200 Pro（需先在手機的「關於本機」連點版本號開啟開發者模式，並開啟 USB 偵錯），按 Run 直接裝機。

## 使用步驟
1. 打開 App，點「1. 開啟無障礙服務」，在系統設定裡找到「手把映射 for DQ10」並開啟。
   - Origin OS 可能會提示「無障礙服務有安全風險」，這是正常的系統警告，確認開啟即可。
2. 點「2. 開啟懸浮窗權限」，允許此 App 顯示在其他應用程式上層。
3. 打開 DQ10，切到遊戲畫面。
4. 切回本 App，點「3. 進入按鍵位置設定模式」——這時 App 會退到背景，遊戲畫面上會出現紅色（按鍵）與藍色（搖桿中心）的可拖曳標記。
5. 把每個標記拖到遊戲對應的按鈕位置（例如把「A」標記拖到遊戲畫面上「確認」鍵的位置；把「搖桿中心」拖到遊戲內建虛擬搖桿的圓心）。
6. 按畫面下方「儲存位置」，設定就會生效，之後接上手把即可用實體按鍵/搖桿操作遊戲。

## 已知限制（務必先實測，這是純類比映射方案的既有取捨）
- **搖桿類比訊號需要懸浮視窗拿 window focus**，這會讓 DQ10 遊戲視窗失去 focus。多數手機遊戲對此不敏感，但少數用 Unity 且沒開 `runInBackground` 的遊戲可能出現卡頓或提示。這點需要你實際測試 DQ10 的反應，如果有問題，可以再討論用 Shizuku 方案繞開（見之前的討論）。
- 搖桿移動是用「延續手勢（continued gesture）」模擬手指拖曳，對應到 DQ10 內建的觸控虛擬搖桿；如果 DQ10 沒有內建觸控虛擬搖桿（例如角色移動是靠點擊地圖），這個映射邏輯不適用，需要另外設計。
- 目前沒有處理「搖桿拖曳」與「按鍵點擊」同時發生時的多點觸控合併，兩者同時操作可能其中一個動作會被打斷，這對回合制/場景移動的 DQ10 影響應該不大，但仍建議實測。
- 按鍵座標是絕對座標（螢幕像素位置），如果 DQ10 在不同解析度/分割畫面下 UI 位置改變，映射座標需要重新設定。
- 目前只支援 DPAD 上下左右、A/B/X/Y、START/SELECT 共 10 個按鍵 + 1 個搖桿，之後要加其他按鍵（例如 L1/R1）可以在 `GamepadMapperService.SUPPORTED_KEYCODES` 與 `ConfigOverlay` 裡新增對應的 `addMarker` 呼叫。

## 檔案說明
- `MainActivity.java`：權限引導 + 進入設定模式的入口
- `GamepadMapperService.java`：核心邏輯，攔截手把按鍵/搖桿，轉成觸控手勢
- `ConfigOverlay.java`：設定模式下可拖曳的標記 UI
- `MappingStore.java`：座標設定的 SharedPreferences 讀寫
