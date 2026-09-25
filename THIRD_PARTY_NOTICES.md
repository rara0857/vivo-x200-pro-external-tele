# 第三方元件

Release 中的 KernelSU ZIP 附有 `watermark_controller` 原生執行檔。該檔靜態連結 **Frida Core 17.18.0**；此部分的著作權與授權仍歸 Frida 原作者所有，不受本專案 MIT 授權取代。

- 原始碼：<https://github.com/frida/frida/tree/17.18.0>
- 授權檔：[third_party/FRIDA_COPYING](third_party/FRIDA_COPYING)
- 授權：Frida 專案所附的 wxWindows Library Licence v3.1（詳見授權檔）
- 建置所用開發套件：`frida-core-devkit-17.18.0-android-arm64.tar.xz`，來自 Frida 官方 Release。

原始碼庫不收錄 Frida 開發套件、vivo 韌體、原廠 Camera APK 或其他原廠二進位檔。使用者如自行重建 KernelSU ZIP，需取得相應的 Android NDK 與上述 Frida 開發套件，並遵守 Frida 授權。
