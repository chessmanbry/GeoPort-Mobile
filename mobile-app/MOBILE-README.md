# GeoPort Mobile — 手機獨立版原始碼原型

狀態：2026-10-19 已使用 Xcode 27.0 / iOS 27 SDK 完成 Release arm64 建置及 IPA 打包。
仍未簽署、未在手機驗證；未簽署 IPA 不能直接安裝。
桌面版的 iPhone 16e / 26.6.2 / 23G90 測試結果不能套用到本手機版。

## 架構與來源

SwiftUI App → idevice FFI → LocalDevVPN 提供的本機通道 → iOS DVT 定位服務。
執行時目標是不需要 Mac 或 Windows；第一次安裝／配對仍可能需要電腦。
此原型是 Locus 的 MIT 授權衍生版本，不是把 GeoPort Python 直接移植進手機。

來源：https://github.com/odometer112/locus
固定 revision：83c8fb324983728e8f44759cfd834dc637ee38b5
上游來源：https://github.com/ChrisMack32/Locus
通訊套件：https://github.com/jkcoxson/idevice

保留 LICENSE 與作者歸屬。Vendor/idevice/libidevice_ffi.a 是來源專案隨附的
預編譯靜態庫；尚未自行從 Rust 原始碼重建，也未驗證它對應的精確 upstream revision。
本原型不是完整的可重現建置交付；正式發布前須固定並重建這個依賴。

本次變更：
- 首頁精簡成地圖選點、經緯度輸入、設定及停止／恢復。
- 顯示名稱 GeoPort Mobile，bundle ID org.geoport.mobile.preview。
- 清除指令失敗時不立即銷毀定位控制 handle，保留重試機會。
- 原有配對導覽與設定保留；尚未完整中文化。

## 第一次安裝：iOS 26.6.2

1. 使用者開啟 Xcode，自行閱讀並接受 Apple 授權、安裝 iOS SDK 等初始元件。
2. 用 Xcode 開啟 Locus.xcodeproj。選 Locus target，在 Signing & Capabilities
   選自己的開發團隊；若 bundle ID 衝突，改成自己的唯一 ID。
3. 接上已啟用開發者模式的 iPhone，選實機為執行目標，Build / Run。
   簽署與帳號由使用者操作；不要將 Apple 密碼或配對私鑰貼進聊天。
4. 依上游 SETUP.md，使用 idevice_pair 產生 **RPPairing** 檔。
   不是 SideStore lockdown .mobiledevicepairing；目前不會自動轉換桌面配對資料。
5. 將檔案透過 AirDrop 或本機檔案方式交給手機，在 App 配對設定匯入。
6. 從官方 App Store 安裝 LocalDevVPN，由使用者允許 VPN 設定並啟用。
   https://apps.apple.com/us/app/localdevvpn/id6755608044
7. 先連 Wi-Fi，依序設定 A、B 兩點，再按停止，使用 Apple 地圖藍點驗證。

如果選擇 IPA 側載：完成 Xcode 設定後可執行 `bash build-unsigned.sh`，
產出 outputs/GeoPort-Mobile-unsigned.ipa。它未簽署，不能直接點擊安裝；
仍需適用的簽署／側載工具。可參考 SideStore 官方安裝說明：
https://docs.sidestore.io/docs/installation/install
簽署有效期及更新方式依使用者帳號與工具而定。

## 必須驗證，才能稱為「隨時隨地」

- Wi-Fi 建立連線，設定、換點、清除，各三輪。
- 拔掉電腦後重新操作，證明不依賴桌面後端。
- Wi-Fi 轉行動網路後換點、清除。
- 僅行動網路時，完全關閉 App 再開，重新建立定位服務。
- 鎖屏／切換 App 5、15 分鐘後回來操作。
- 停用 VPN、斷線、重啟 App／手機後的恢復流程。

上游明確建議從 Wi-Fi 啟動，再轉行動網路。因此不能保證行動網路冷啟動；
若這一步不通，仍未達成使用者的完整目標，不能用「電腦版已成功」代替。
iOS 27 路徑保留自上游，但本專案尚未驗證。

## 尚未完成

- Xcode 編譯及連結檢查；簽署與真機安裝。
- idevice 靜態庫的原始碼重建與供應鏈驗證。
- 同步 FFI 呼叫可能阻塞 UI 的效能檢查。
- 背景維持、配對與清除失敗後的恢復實機測試。

## 2026-10-19：全球選點更新（尚未編譯驗證）

- 使用 Apple MapKit / MKLocalSearch 搜尋城市、地址及地標，沒有設定台北區域限制。
- 較新的搜尋取代舊搜尋，取消舊請求並忽略過期結果。
- 搜尋結果列出名稱與經緯度；選擇後移動相機，不會自動設定手機位置。
- 標準／衛星地圖切換；分開顯示選定點與已送出的模擬點。
- 直接輸入全球經緯度、座標預覽、收藏命名、收藏與最近使用入口。
- UI 與底層皆檢查有限數值與全球有效經緯度範圍。
- 搜尋失敗時提供訊息，仍可直接輸入座標。

地圖／搜尋會使用 Apple 服務；不是離線搜尋，也沒有整合 Google Maps。
地圖能找到某地點，不等於 iOS 定位服務已成功套用該點。

新增驗收：搜尋東京車站、London Bridge、紐約中央公園與台北大學；
確認相機跳轉到正確城市，選擇結果不會自動送出位置。測試無網路搜尋、
快速連續搜尋與收藏重新開啟。座標測試包含南緯、西經、零經緯度、
±90/±180 邊界以及 NaN/Infinity/超範圍輸入。

本次 xcodebuild -showsdks 與 Swift parse 仍因 Apple 授權未接受而停止；
僅 xcodebuild -version 可回傳版本，不代表已能編譯。請先由使用者開啟
Xcode 完成授權與初始設定，再進行真實 build / 簽署 / 安裝。

## 最新建置結果：2026-10-19

先前授權阻礙已解除；本節取代上方歷史紀錄中「尚未編譯」狀態。
完整 Release 建置與連結成功。修正 PairOnDeviceService 背景執行緒呼叫
MainActor 隔離方法的警告，將不操作 UI 的阻塞配對入口標為 nonisolated。
唯一剩餘提示是未使用 AppIntents，因此跳過該 metadata 擷取。

IPA：outputs/GeoPort-Mobile-unsigned.ipa（arm64，最低部署 iOS 18）。
最低部署版本只是建置設定，不是所有 iOS 18～27 皆相容的保證。
已檢查 ZIP 完整性、Payload 結構、Info.plist、bundle ID 與可執行檔架構。
尚未簽署、安裝或驗證手機端定位。

### 建議下一步：Xcode 直接安裝

1. 開啟 Locus.xcodeproj，選 Locus target。
2. 在 Xcode Settings → Accounts 登入你的 Apple 帳號（自行操作）。
3. Signing & Capabilities 選擇自己的 Team，保持自動簽署；必要時改唯一 bundle ID。
4. USB 連接、解鎖並信任 iPhone，確認 Developer Mode，選它作為執行目標。
5. 按 Run；完成後依前文匯入 RPPairing 並啟用 LocalDevVPN。
6. 先做 Wi-Fi 設定／清除驗收，再拔除 USB 驗證獨立運作。

不要將配對檔、帳號密碼或簽署私鑰放進原始碼包。
