# 持股記帳

## 環境

- JDK 17 或以上，確保終端機能執行 `java -version`。
- 首次執行需要網路下載 Maven 和相依套件。

## 啟動

在專案根目錄執行一條指令。

Windows PowerShell

```powershell
.\mvnw.cmd spring-boot:run
```

macOS／Linux

```sh
sh ./mvnw spring-boot:run
```

在瀏覽器開啟 <http://localhost:8080/>，即可看到「持股記帳」與目前台北時間。
重新整理頁面會更新時間。按 Ctrl+C 停止服務。

## Eclipse

選擇 File → Import → Maven → Existing Maven Projects，匯入本目錄的 `pom.xml`。
將專案執行環境設為 Java 17 或以上，也可以從 Eclipse 執行 `HoldingsApplication`。
