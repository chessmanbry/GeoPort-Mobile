# GeoPort Mobile 開發與安裝

解壓縮後，用 VS Code 開啟 `GeoPort.code-workspace`。選擇「終端機 → 執行工作」即可建置 Android 測試 APK，或在 Mac 上建置未簽署 iOS IPA。使用 VS Code 編輯，使用 Android Studio 或 Xcode 管理 SDK、執行裝置及簽署。

| 資料夾 | 用途 |
| --- | --- |
| `android-app/` | Android 原生 App，最低 Android 8.0（API 26） |
| `mobile-app/` | iOS SwiftUI 原型，部署目標 iOS 18，實體裝置 arm64 |
| `src/`、`tests/` | 原有桌面 USB 版原始碼及測試 |

## Android

1. 安裝 Android Studio，透過 SDK Manager 安裝 Android SDK 36 和 Platform-Tools。命令列建置使用 JDK 17 或 21。
2. 可在 Android Studio 開啟 `android-app/` 後同步 Gradle，或把 `ANDROID_HOME` 設為本機 Android SDK 路徑後，在 VS Code 內執行 Android 建置工作。第一次建置需要連線下載 Gradle 和建置套件。
3. 將 `android-app/app/build/outputs/apk/debug/app-debug.apk` 安裝到測試手機。也可在 Android Studio 選擇裝置後按 Run。
4. 開啟手機的「開發人員選項」，在「選取模擬位置應用程式」選擇 **GeoPort Mobile**，並開啟系統定位。
5. 地圖點選、搜尋或輸入經緯度後，按「開始模擬」。換點後按「更新模擬位置」。按 App 內或通知上的「停止並恢復」，即可清除 App 設定的測試定位來源。

也可直接在 `android-app/` 終端機建置：

```sh
./gradlew :app:assembleDebug :app:lintDebug
```

Windows 使用 `gradlew.bat :app:assembleDebug :app:lintDebug`。

- 允許通知可從通知欄停止模擬。系統若強制結束 App，重新開啟後依提示按「停止並恢復」。若已取消模擬位置授權，請重新選擇 GeoPort Mobile 再按停止。停止後，其他 App 可能會顯示快取座標，直到系統取得新的定位。
- 地圖使用 OpenStreetMap 線上圖磚。地名搜尋使用裝置提供的 Android Geocoder，結果依裝置與網路而異。沒有網路時仍可直接輸入經緯度。收藏只儲存在裝置內。 [OpenStreetMap 圖磚使用政策](https://operations.osmfoundation.org/policies/tiles/)
- 系統會把測試座標標示為模擬位置，定位 App 是否採用這類座標取決於它使用的服務和自身設定。 [Android 開發人員選項](https://developer.android.com/studio/debug/dev-options) · [Android LocationManager](https://developer.android.com/reference/android/location/LocationManager#setTestProviderLocation(java.lang.String,%20android.location.Location))

## iOS

1. 在 Mac 安裝並開啟完整的 Xcode，完成初始設定。此版本以 Xcode 27.0 和 iOS 27 SDK 建置。
2. 開啟 `mobile-app/Locus.xcodeproj`，選擇 `Locus` target。到 **Signing & Capabilities** 選擇自己的 Apple 開發團隊。Bundle ID 衝突時，改為你的唯一 ID。
3. 接上已啟用「開發者模式」的 iPhone，選實機並按 Run。專案附的定位通訊庫是 arm64 iOS 實機版。
4. 初次使用依 [iOS 配對說明](mobile-app/SETUP.md) 設定開發者配對與 LocalDevVPN。iOS 18–26 的既有流程需要自行產生及匯入 RPPairing 檔，iOS 27 的手機配對入口沿用上游實作。
5. 先在 Wi-Fi 測試開始、換點及停止，觀察 Apple 地圖的目前位置。手機獨立運作、轉行動網路及純行動網路冷啟動，需依實機結果確認。

用 VS Code 建置會產出 `mobile-app/outputs/GeoPort-Mobile-unsigned.ipa`，安裝到 iPhone 前需要簽署。Xcode 可在完成簽署設定後直接安裝實機。 [Apple 實機建置說明](https://developer.apple.com/documentation/xcode/running-your-app-on-simulated-or-physical-devices)

iOS 原型保留原有的 MapKit 地圖、全球搜尋、座標、收藏與最近選點。這次手機版聚焦定點模擬與停止恢復，桌面版的 GPX 路線播放未移植到兩端的主要操作介面。iOS 授權、來源及附帶的預編譯通訊庫資訊見 [iOS 原型紀錄](mobile-app/MOBILE-README.md)；其中包含較早的建置歷史。

## 驗證狀態

- Android 16 模擬器已驗證從系統設定授權、地圖選點、設定及更新模擬座標、切換到其他 App 後接收定位、從通知停止、收到正常定位，以及強制關閉後重新開啟並清除。Android 靜態檢查通過。
- iOS 已完成 arm64 Release 未簽署建置。Apple 帳號簽署、真機配對和定位測試仍需在實體 iPhone 上進行。
- 原有桌面 USB 版的六項自動測試通過。

## 開發方式

目前用同一個 VS Code 工作區維護兩份原生專案。先用各平台的開發工具完成實機驗證，這樣能直接除錯定位、配對及簽署。若後續兩邊經常要同步修改地圖、搜尋、收藏或路線介面，可以用 Flutter 共用這些畫面及資料格式，再透過平台介面呼叫 Swift 和 Android 的原生定位引擎。 [Flutter 平台介面](https://docs.flutter.dev/platform-integration/platform-channels)

如需啟動原有桌面 USB 版，可另建 Python 虛擬環境，安裝 `requirements-ios26.txt`，再執行 `python src/main.py --no-browser --port 54321`。詳細操作見 [桌面 USB 說明](IOS26-TESTING.md)。
