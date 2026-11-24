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

## App 2.0 API

App 2.0 的新增、清單、單檔查詢、交易修改、整檔刪除及總計操作見 [API 串接指南](API.md)。
新增 GET /api/v2/summary、PUT /api/v2/holdings/{id}/trades/{tradeId}、
DELETE /api/v2/holdings/{id}。交易清單回傳 id，個股與總計回傳兩種損益率。
修改與刪除共用網頁已有的交易、日期及超賣驗證，整檔刪除會一併移除全部交易。

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

日常收盤行情使用證交所官網的 MI_INDEX 與櫃買中心官網的 afterTrading/otc
（上櫃股票每日收盤行情，不含定價），都是官方來源的全市場批次報表。
上市請求帶入台北日期 YYYYMMDD、type=ALLBUT0999；上櫃帶入台北日期
YYYY/MM/DD、type=EW，兩者均使用 response=json。完整解析後，以同一筆資料庫
交易寫入現有 quote_cache。不同股票、不同使用者共用同一個市場的取得工作，
背景排程只針對實際持股觸發，每個市場合併成一項工作。

批次檔可能較晚更新，價格日期以官方回應為準，較舊的批次不會覆蓋較新的
已知價格。批次缺少某個代號不等於股票不存在；新增持股時仍可使用既有
個股月資料介面確認身分。下市前的歷史查價也保留個股介面。
解析時依欄位名稱尋找行情表格，處理空白及 HTML 標記，不固定表格或欄位索引。
必須有合法成功狀態、符合請求的實際回應日期、完整行情欄位、合法代號與價格。
回應日期是今天但行情表為空，不算更新成功；整批結構或任一行情列格式錯誤時，
不寫入部分報價。--、----、null 等缺價保留為空值，零成交量不當成有效交易。
批次及個股寫入在資料庫更新時也比較價格日期，避免交錯請求把新行情覆蓋成舊行情。

批次網址可以用以下設定或對應環境變數覆寫；指定報表端點即可，程式會帶入日期、
分類與 JSON 參數。舊版 OpenAPI 陣列格式與新報表格式不同，不可只換回舊網址。

| 設定 | 環境變數 | 預設端點 |
|---|---|---|
| quotes.twse-batch-url | QUOTES_TWSE_BATCH_URL | https://www.twse.com.tw/rwd/zh/afterTrading/MI_INDEX |
| quotes.tpex-batch-url | QUOTES_TPEX_BATCH_URL | https://www.tpex.org.tw/www/zh-tw/afterTrading/otc |

測試伺服器可分別提供相同路徑及官方報表格式，再以這兩個環境變數替換主機。
個股網址 quotes.twse-url、quotes.tpex-url 與個股查詢方式維持原有設定。

批次價格、實際交易日期與刷新狀態保存在 H2，
一般重啟可讀回既有市場進度。請求合併與 HTTP 來源冷卻門閂存在目前伺服器程序內；
重啟後來源門閂會重設，資料庫已記錄的下次嘗試時間及恢復窗口仍生效。
批次失敗時保留舊快取，清單可繼續載入。

以台北時間為準，平日 13:40 加 15～30 秒隨機偏移開始收盤後觀察，
13:40～14:30 尚未取得今日有效批次時，以 2 分鐘加 0～10 秒偏移重試。
14:30 不是永久停止：改為每 10 分鐘加 0～10 秒偏移補查，直到取得有效今日批次。
排程每 5 秒檢查實際持股，不需要使用者先開清單；檢查不等於每次向官方請求。
取得當日有效批次後停止該市場當日例行查詢，舊價及空表不算今日成功。

若正常窗口曾嘗試但完全沒有可辨識官方回應，窗口後第一次取得可辨識回應時，
啟動一次 30 分鐘的較密集補查窗口。正常回覆「尚未公布」不算整段斷線。
再次失敗或重啟不重新計時；補查窗口結束後仍保留低頻更新。
刷新目標日期、首次偏移、下次嘗試、首次回應、恢復窗口及完成時間，
由 V11 的 quote_market_refresh 保存，即使尚無任何行情資料也能保留進度。

quotes.refresh-start、quotes.refresh-end、quotes.refresh-interval、
quotes.refresh-low-frequency、quotes.refresh-recovery-window 分別可設定開始、窗口結束、
正常間隔、低頻間隔與恢復窗口；預設為 13:40、14:30、PT2M、PT10M、PT30M。
quotes.refresh-check-ms 預設 5000；排程檢查、工作佇列與來源間隔可能使實際請求稍晚。
已知休市日不抓該日行情；啟動及缺價預熱依官方日曆尋找最近交易日。

13:30～14:30 期間，個股補查須等相關市場今日批次完成才允許；
14:30 後解除此限制。期間未知股票無法完成驗證時回 503，不建立持股。

同一代號與同市場的批次工作會合併。每個來源在前一筆 HTTP 請求完成後，
隨機等待 30 至 60 秒才送下一筆，同來源一次只執行一筆。TWSE 與 TPEx 分別限流，
公司名單、歷史價格、年度日曆與批次行情共用各自來源的門閂。
連線、DNS、TLS、逾時、非 200 HTTP 回應或無法解析的 JSON 會讓該來源冷卻至少
30 分鐘；若 Retry-After 指定更晚恢復時間，採較晚者。正常的查無資料 JSON
不觸發來源冷卻。可選日曆端點的 HTTP 400／404 僅延後日曆重試，不暫停行情來源。冷卻中直接回報暫時不可用，不延長原本的冷卻時間。
批次失敗也會保存下次嘗試時間。連線失敗仍至少暫停 30 分鐘；
有效的「尚未公布」回應按正常／低頻間隔重試，不誤當網路中斷。

quotes.min-request-interval、quotes.max-request-interval 可設定間隔範圍，
預設分別為 PT30S、PT60S。初次驗證若需要等候官方，可能超過後端單次查詢的
10 秒等待而先回 503；查詢仍可能在背景完成，稍後可重新驗證。
已有快取的清單先讀快取。更新窗口控制收盤後批次刷新；初次暖機、
新代號驗證和歷史市場資料同步仍可能產生額外請求。
部署環境需要允許對 TWSE 和 TPEx 的 HTTPS 連線。

### 首次啟動與缺價預熱

啟動完成後在背景檢查兩個市場的最近應有交易日，沒有任何持股也會補齊行情。
首次在星期六啟動的新資料庫，星期五開市時補星期五；若官方日曆確認星期五休市，則往前補最近交易日（例如星期四）。早上啟動先補前一個交易日。
若指定過往日期的合法報表尚無資料，有限次往前找，預設最多 14 個候選交易日，
可用 quotes.warmup-lookback 設為 1～31。連線或格式異常不會一路追打更早日期。
價格以實際交易日期保存，不把星期五價格標成星期六；已有最近交易日行情則不重查。
預熱與一般刷新共用市場進度及來源限流，並使用獨立背景工作池，不阻塞服務啟動。
網頁第一次可能仍顯示未取得；背景完成後重新整理。官方不可用時依保存的期限補查。
quotes.warmup-enabled=false 可關閉預熱；自動測試關閉真實背景網路，使用假來源驗證。

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


### 官方開休市日曆

兩市場分別讀取官方指定年度資料，完整解析、核對年度及必要日期後，
將該年每日開／休市狀態與來源、確認時間寫入 V12 的 quote_market_calendar。
不能直接用查不到收盤價判斷休市；「僅辦理交割、市場無交易」視為休市，
春節後開始交易日、春節前股票系統最後交易日仍視為交易日，債券專屬日程不套用股票。

| 市場 | 官方來源（2026 年範例） |
|---|---|
| TWSE | https://www.twse.com.tw/rwd/zh/holidaySchedule/holidaySchedule?date=20260101&response=json |
| TPEx | https://www.tpex.org.tw/www/zh-tw/bulletin/tradingDate?date=2026&response=json |

背景同步在啟動時執行，每小時檢查；已有最近 24 小時內成功保存的年度資料不重抓，
失敗至少隔一小時才嘗試，來源冷卻與 Retry-After 仍優先。1 月另補前一年度，以便跨年回溯。
首次日曆同步進行中先延後行情預熱；完成或失敗後解除等待。
同步失敗保留已有日曆；沒有日曆的平日標為「未知」，仍可有限回查行情，不假設該日休市。
已有最近應有交易日有效行情時，週末／國定休市不再重查；缺價時才背景補齊。

年度日曆不保證涵蓋颱風等臨時停市公告。維運人員需先查證官方公告，
再設定 `quotes.calendar-closed-dates=2026-11-20,2026-11-23`（僅示意日期），
或 `quotes.calendar-open-dates` 指定例外開市，重啟後生效；兩項不可包含相同日期。
覆寫適用兩市場，優先於年度表，日期格式為 YYYY-MM-DD、以逗號分隔。
本功能不會自動把無報價、單檔停牌或下市等同於全市場休市。

`quotes.calendar-sync-enabled=false` 可停用日曆網路同步，但仍讀取既有資料及覆寫設定。
測試主機可覆寫 `quotes.twse-calendar-url`、`quotes.tpex-calendar-url`：
網址以 `%d` 表示年度（前者預設為 `date=%d0101`，後者為 `date=%d`）；需提供同格式 JSON，
TPEx 的 data.html 也需保留年度與股票休市表格。單元測試使用存檔 fixture 或本機假伺服器，
不會連到官方；全部時間判斷由同一個台北 Clock 取得，不依賴主機預設時區。
