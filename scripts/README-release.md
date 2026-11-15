# Android 正式版發布

使用 JDK 17、Android SDK 35 與 Gradle Wrapper。先執行 `make.bat check`，再以 `gradlew.bat :app:assembleDebug` 建立測試版。Linux 對應指令為 `make check` 與 `sh gradlew :app:assembleDebug`。

正式 APK 的 keystore 由發布者在專案外建立並離線備份。設定 `ROADGUARD_KEYSTORE`（絕對路徑）、`ROADGUARD_KEY_ALIAS`、`ROADGUARD_STORE_PASSWORD`、`ROADGUARD_KEY_PASSWORD` 與 `ROADGUARD_PRO_PUBLIC_KEY`。最後一項為 Pro 驗章公鑰的 Base64 SPKI。正式 APK 使用獨立的簽章金鑰。

```sh
keytool -genkeypair -keystore /secure/roadguard-release.jks -alias roadguard -keyalg RSA -keysize 3072 -validity 10000
```

在本機用 `gradlew.bat :app:assembleRelease` 建置，以 Android SDK 的 `apksigner verify --print-certs` 比對正式憑證指紋，並在測試機驗證安裝。換簽首次升級採移除後重裝，自訂點與設定可能遺失。原 Pro 用戶應把新設備碼提供給原授權窗口，以原權益補發授權。

`python scripts/publish_release.py --dry-run` 顯示流程。收到明確發版指令後才執行實際發布。GitHub Release 的版本固定下載網址與 SHA-256 會在 APK 上傳成功後寫入版本檔。若最後推送因遠端圖資更新遭拒，先同步遠端後再推送既有發布提交。
