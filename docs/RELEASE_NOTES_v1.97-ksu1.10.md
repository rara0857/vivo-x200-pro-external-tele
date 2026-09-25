# v1.97 / KernelSU v1.10

配套版本：LSPosed／Vector APK **v1.97** + KernelSU ZIP **v1.10**。兩個附件是手機上已測試的原始發行檔；不要與舊版混裝。適用範圍與安裝順序請先看 [README](../README.md)。

- 長焦增距介面與 180° 預覽／成片方向處理。
- OIS 選項：A 固定 2.35 倍、B AF 公式（33 ms 輪詢；變化超過 500 才更新）、C 向相機送出原廠 OIS OFF 請求、關閉後恢復原廠值。
- 原生 ZEISS 邊框與相片資訊的等效焦距標示；已驗證 200、400、540、800、3200 mm。拍攝後立即開啟相片時，邊框處理有短暫的延續時間。
- 僅在 PD2405 與指定原廠函式庫雜湊相符時啟動 KernelSU 原生控制器。不改寫原廠分區。

這是實驗性移植，**未以實體 G2 增距鏡驗證**；OIS 命令／Camera2 OFF 請求也不是致動器效果的實測證明。沒有原廠外接鏡 HAL 或外接鏡專用 EIS。ZIP 內的 Frida 元件適用其自身授權，詳見 [第三方授權說明](../THIRD_PARTY_NOTICES.md)。ZIP 所附 `README.txt` 的後半部保留舊版歷史紀錄，其中 v1.94 的「C=零增益」已不適用；本版 C 的行為以此說明及專案 README 為準。

SHA-256：

```text
pd2405-exttele-v1.97-fixed-focal-labels-3200.apk
C4FB6757FF86D44B18C674E46A433D0D36CCFF9843DA4929C5CC5149C17C0DFD

pd2405-exttele-ois-watermark-ksu-v1.10.zip
D3139194FCD6747DF8DE642E94CAE3E7CCB1254ABF2EB7230F25AB21CBF66775
```
