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

## 持股操作

啟動後從首頁進入「持股清單」，或直接開啟 <http://localhost:8080/holdings>。
新增一筆代號 `0050`、張數 `2`、買進價 `150.00` 的持股，再試著修改張數、重複新增及刪除。
同一代號只可有一筆持股。修改時可調整張數和每股買進價。

首次啟動時，Flyway 會在專案的 H2 檔案資料庫建立資料表。
從專案根目錄啟動時，資料保存在 `data/`，重新啟動後仍可讀取。
這個目錄不會納入 Git。直接用 Eclipse 執行時，請讓 Run Configuration
使用專案根目錄作為工作目錄，才能讀取同一份本機資料。


## 官方行情

新增時請輸入股票代號，系統會查詢臺灣證券交易所及證券櫃檯買賣中心，
辨識股票名稱和市場。兩個市場都查無資料時會拒絕新增。
清單顯示最近交易日的收盤價與日期，來源失敗時仍可讀取已儲存的持股，
收盤價顯示「未取得」。

行情以台北日期與股票代號快取於本機 H2，表單預查、正式新增及清單
共用同一份結果。當日的查詢失敗也會快取，以避免重新整理時重複打官方。
若當月無有效收盤價，最多回查上個月，同一來源與月份每天最多查一次。
部署環境需要允許對 TWSE 和 TPEx 的 HTTPS 連線。

## Eclipse

選擇 File → Import → Maven → Existing Maven Projects，匯入本目錄的 `pom.xml`。
將專案執行環境設為 Java 17 或以上，也可以從 Eclipse 執行 `HoldingsApplication`。
