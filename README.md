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
