# Windows 11 USB 預覽版

這是原始碼測試包，不是已驗證的 Windows 成品或獨立 EXE。
macOS + iPhone 16e + iOS 26.6.2 (23G90) 的設定、換點、清除已由使用者確認。
Windows 11 仍需實機測試；本版僅接受 iOS 26，尚不開放 iOS 27。

## 第一次使用

1. 將 ZIP 完整解壓縮到可寫入的資料夾，例如使用者的 Downloads。不要在 ZIP 裡直接執行。
2. 安裝 Python 3.13 的 Windows 64-bit 版本，保留 Python Launcher（`py`）。
   官方下載：https://www.python.org/downloads/windows/
3. 確認 Windows 已安裝 Apple 的裝置支援，且 Apple Devices 或 iTunes 能識別手機。
   若無法識別，先修復 Apple 驅動／USB 連線，再啟動本程式。
4. 雙擊 INSTALL-WINDOWS.bat。需要網路；會建立專用 .venv-win 並安裝固定版本依賴。
5. 將 iPhone 以 USB 連接，解鎖、信任此電腦，手動啟用開發者模式。
6. 關閉其他定位工具，雙擊 START-WINDOWS.bat。
7. 瀏覽器開啟 http://127.0.0.1:54321 。不需要讓其他電腦存取這個連接埠。
8. 搜尋手機、連接、選點並設定位置，在手機 Apple 地圖確認定位藍點。
9. 按「停止定位」確認恢復真實位置；離開前按「清除並中斷連線」。

關閉瀏覽器不會自動清除定位。強制關閉視窗或拔線可能來不及清除。
若遇到這種情況，重新連接後按停止定位，並在手機確認。

## 驗證與回報

先測兩個不同座標及恢復位置，再重複三次。測試拔線、重新連線。
若有錯誤，保留啟動視窗完整輸出，連同 Windows 架構、手機型號與 iOS 組建回報。
目前 Windows on ARM 未驗證。

開啟終端機，在解壓資料夾可執行：

```
.venv-win\Scripts\python.exe -m unittest discover -s tests -v
.venv-win\Scripts\python.exe -m pymobiledevice3 usbmux list
```

第一個指令只測軟體邏輯，第二個只用於識別診斷，不代表定位測試通過。
配對紀錄應由 Windows 自行產生，不要複製 Mac 的配對紀錄或虛擬環境。

## 包內內容

- src/main.py：新版入口；只監聽本機。
- src/device_controller.py：持續 USB 通道與非同步定位服務。
- src/templates/usb.html：精簡地圖介面。
- src/legacy_main.py、map.html、map2.html：原始程式，僅供參考，非新版入口。
- requirements-ios26.txt：pymobiledevice3 11.30.0 / Flask 3.1.3。
- LICENSE：原專案授權。

不包含 Python、Apple 驅動或依賴套件；首次安裝及地圖需要網路。
