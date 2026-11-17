# 持股記帳

## 環境

- JDK 17 或以上，確保終端機能執行 `java -version`。
- 首次執行需要網路下載 Maven 和相依套件。

## 啟動

在專案根目錄執行一條指令。

### Windows PowerShell

先確認 `JAVA_HOME` 指向已安裝的 JDK 17 或以上版本的**根目錄**，
例如 `C:\Program Files\Java\jdk-17`。請按電腦上的實際安裝路徑修改，
路徑最後不用加 `\bin`。即使 `java -version` 可執行，
Windows 的 Maven Wrapper 仍需要這個變數。

先用 Set-Location 進入含有 pom.xml 的專案根目錄。以下專案及 JDK 路徑均為範例，請依實際位置調整。若 JAVA_HOME 已正確設定，可跳過設定該變數的一行。

```powershell
Set-Location -LiteralPath 'D:\ApXpert_Test\project'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
echo $env:JAVA_HOME
& "$env:JAVA_HOME\bin\java.exe" -version
& "$env:JAVA_HOME\bin\javac.exe" -version
.\mvnw.cmd -v
```

確認 java、javac 和 Maven 顯示的 Java 版本都為 17 或以上後，在同一個視窗的專案根目錄啟動。

```powershell
.\mvnw.cmd spring-boot:run
```

上述 `$env:JAVA_HOME` 設定只對目前的 PowerShell 工作階段有效。
若顯示 `JAVA_HOME not found` 或 `JAVA_HOME is set to an invalid directory`，
請核對變數是否有值，且該目錄下有 `bin\java.exe` 和 `bin\javac.exe`。

### Windows Git Bash

以下專案及 JDK 路徑請依實際位置調整。在 Git Bash 內設定的變數只影響目前視窗。

~~~bash
cd /d/ApXpert_Test/project
export JAVA_HOME='/c/Program Files/Java/jdk-17'
export PATH="$JAVA_HOME/bin:$PATH"
"$JAVA_HOME/bin/java.exe" -version
"$JAVA_HOME/bin/javac.exe" -version
sh ./mvnw -v
sh ./mvnw spring-boot:run
~~~

### macOS／Linux

```sh
sh ./mvnw spring-boot:run
```

在瀏覽器開啟 <http://localhost:8080/>，即可看到「持股記帳」與目前台北時間。
重新整理頁面會更新時間。按 Ctrl+C 停止服務。

### 如果 8080 已被占用

先停止先前啟動的服務，或改用另一個空閒的埠，例如 8081。

Windows PowerShell

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081"
```

macOS／Linux

```sh
sh ./mvnw spring-boot:run '-Dspring-boot.run.arguments=--server.port=8081'
```

改埠後請用 <http://localhost:8081/>，手機或模擬器的 App 伺服器設定也要更換埠號。

## 2.0 持股操作與驗收

從首頁選「開啟持股記帳 2.0」，或直接開啟 <http://localhost:8080/v2/holdings>。
若已換埠，請把下列網址的 8080 替換成實際埠號。

1. 在「建立新版持股」輸入股票代號 `0050`、今天日期、`300` 股及每股價格 `150.00`，按「記錄買進」。股數以股為單位。
2. 在個股交易明細選「賣出」，使用同一天或之後的日期，輸入 `100` 股、每股 `160.00`，按「儲存交易」。
3. 確認還有 `200` 股、已實現損益為 `940.67` 元，交易紀錄有一筆買進和一筆賣出。再試著賣出 `201` 股，應顯示持股不足且交易筆數不變。
4. 回到清單查看總計。未實現損益使用最近交易日的官方收盤價，實際值會隨行情變動。

範例請使用乾淨的測試資料庫。同一股票已在清單時，從該筆「交易紀錄」追加買進或賣出。

## App 1.0 相容清單

從首頁選「App 1.0 相容清單」，或直接開啟 <http://localhost:8080/holdings>。
這裡以張為單位，可新增持股、修改張數和每股買進價、刪除及檢查重複代號。
2.0 新建或已轉換的持股會出現在新版清單。

## 本機資料庫

首次啟動時，Flyway 會在專案的 H2 檔案資料庫建立資料表。
從可寫入的專案根目錄啟動，資料會存在 `data/`，重啟後仍可讀取。
同一份 H2 檔案請一次只由一個應用程式使用。如果顯示資料庫檔案正在使用，
先關掉其他執行中的實例。這個目錄不會納入 Git。
若從 Eclipse 啟動，請在 Run Configuration 將工作目錄設為專案根目錄。

## 官方行情

新增時請輸入股票代號，系統會查詢臺灣證券交易所及證券櫃檯買賣中心，
辨識股票名稱和市場。兩個市場都查無資料時會拒絕新增。
清單顯示最近交易日的收盤價與日期，來源失敗時仍可讀取已儲存的持股，
收盤價顯示「未取得」。

行情快取存放在本機 H2。持股清單與總計先使用最近一筆已知收盤價與交易日期，
缺行情時顯示「未取得」。後端背景查到行情後，下次載入即可看到。股票代號
驗證仍由後端確認，兩個市場都確認查無的結果會快取到當天結束。

日常收盤行情優先使用 TWSE 的 STOCK_DAY_ALL 與 TPEx 的
tpex_mainboard_daily_close_quotes，一次取得該市場的資料並以同一筆資料庫交易
寫入現有 quote_cache。不同股票、不同使用者共用同一個市場的取得工作，
背景排程只針對實際持股觸發，每個市場合併成一項工作。

批次檔可能較晚更新，價格日期以官方回應為準，較舊的批次不會覆蓋較新的
已知價格。批次缺少某個代號不等於股票不存在；新增持股時仍可使用既有
個股月資料介面確認身分。下市前的歷史查價也保留個股介面。
兩個批次網址可用 quotes.twse-batch-url、quotes.tpex-batch-url 覆寫。

批次價格與實際交易日期保存在 H2，重新啟動仍可讀取；各市場共用的
請求合併與短期重試狀態存在目前伺服器程序內。行情 HTTP 介面沿用來源限流。
批次失敗時保留舊快取，清單可繼續載入。以下刷新時間與退避設定仍適用。

以台北時間為準，週一至週五從 13:30 起可刷新當日尚未更新的收盤價，
單檔每天最多四次盤後嘗試，間隔至少五分鐘。拿到當日收盤價即停止。
週六、週日沿用最近有效行情，新增從未驗證過的代號仍可查詢。
一般連線失敗按 30 秒、1 分鐘、2 分鐘、5 分鐘逐步退避。
同一代號並行查詢合併，每個官方來源至少隔兩秒送下一筆。若收到 429，
先遵守 Retry-After，未提供有效時間時暫停該來源五分鐘。
部署環境需要允許對 TWSE 和 TPEx 的 HTTPS 連線。

### 下市與下櫃

每天台北時間 08:00 批次同步證交所與櫃買中心的公司終止上市櫃、停復牌和掛牌資料。
若當天 08:00 後才啟動，會補跑當天批次。櫃買中心較舊的終止上櫃年報
會在背景逐年匯入，已匯入的歷史年度不再每天查詢。櫃買中心標為「轉上市」的紀錄
會依轉板日切換到證交所查價。官方來源失敗時保留既有狀態。
下市生效後停止例行行情查詢，背景只在需要時回查歷史價格。2.0 清單顯示最後交易日
和收盤價供查閱，剩餘成本另列，歷史價不計入市值及未實現損益。App 1.0 保留原欄位，
行情和未實現盈虧回傳空值。

買進日期依已取得的官方期間驗證。已比對出相同公司識別資料及股票代號時，可以依
正式掛牌日恢復查價。暫停期間的涵蓋範圍以已接入的官方報表為準，公司名單不包含
個別 ETF 終止公告。

## 損益計算與測試

持股清單顯示依最近交易日收盤價賣出後的淨損益。買賣手續費分別以
成交金額的 0.1425% 計算，捨去至整元，各筆最低 20 元。
證交稅僅在賣出時計算，代號以 00 開頭用 0.1%，其餘用 0.3%，
同樣捨去至整元。台股慣例為獲利紅色、虧損綠色。

總收益率為總盈虧除以含買進手續費的總成本，乘以 100 後四捨五入至兩位。
缺收盤價的持股仍計入總成本，市值和盈虧排除並標示缺行情筆數。
空清單的總金額為零，總收益率顯示 0.00%。

Windows 執行 `.\mvnw.cmd test`，macOS／Linux 執行 `sh ./mvnw test`。
損益測試使用固定價格，不會連到官方行情。

## Android App 1.0 API

後端啟動後，App 模擬器的伺服器網址填 `http://10.0.2.2:8080`，
API 前綴由 App 自動加上。在電腦上可用 `http://localhost:8080` 測試。

| 方法 | 路徑 | 成功狀態 |
| --- | --- | --- |
| GET | `/api/v1/holdings` | 200 |
| GET | `/api/v1/holdings/{id}` | 200 |
| POST | `/api/v1/holdings` | 201 |
| PUT | `/api/v1/holdings/{id}` | 200 |
| DELETE | `/api/v1/holdings/{id}` | 204 |
| GET | `/api/v1/summary` | 200 |

例如，先以 `curl.exe http://localhost:8080/api/v1/holdings` 查詢。
在 Postman 用 JSON 送出新增範例 `{"code":"0050","lots":2,"buy_price":150.00}`。
修改時以回傳的 `id` 呼叫 PUT，送 `{"lots":3,"buy_price":151.50}`。
欄位名稱採小寫底線，股票代號為字串。行情缺少時，
`current_price`、`price_date`、`pnl`、`return_pct` 會是 `null`。

查無代號回 `404 STOCK_NOT_FOUND`。如果官方連線失敗而無法驗證新代號，
或舊有資料缺少 App 必須的股票名稱及市場且當下無法補齊，
會回 `503 QUOTE_UNAVAILABLE`。API 測試使用固定行情。
框架錯誤也回 `{"error":{"code":"…","message":"…"}}`，
錯誤路徑保留 404、錯誤方法保留 405、不支援的內容類型保留 415，
伺服器例外回 500，詳細例外記錄在伺服器日誌。

## Eclipse

選擇 File → Import → Maven → Existing Maven Projects，匯入本目錄的 `pom.xml`。
將專案執行環境設為 Java 17 或以上，也可以從 Eclipse 執行 `HoldingsApplication`。
