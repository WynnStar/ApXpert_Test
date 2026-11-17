# App 2.0 API 串接指南

本文件描述目前伺服器已實作的 API，供 App 2.0 團隊依序完成零股、多次買進與部分賣出串接。啟動方式請先閱讀 [README](README.md)。

## 1. 連線與共通規則

| 呼叫端 | 本機測試的 Base URL |
| --- | --- |
| 電腦上的 curl、Postman | http://localhost:8080 |
| Android Studio 標準模擬器 | http://10.0.2.2:8080 |
| 同區網實體手機 | http://電腦的區網IP:8080 |

依實際埠號替換 8080。實體手機與電腦須能互通，防火牆須允許指定埠號。伺服器目前提供 HTTP，沒有登入、Token 或使用者資料隔離，呼叫端會存取同一份持股資料。

- API 前綴為 /api/v2，回應為 JSON，中文使用 UTF-8。
- POST 加上 Content-Type: application/json。可加 Accept: application/json。
- 成功直接回物件或陣列。錯誤格式為 {"error":{"code":"…","message":"…"}}。
- 欄位採 snake_case。股票代號為字串，保留 0050 的前導零。
- 股數為整數，價格和金額為 JSON number，日期為 YYYY-MM-DD。
- 新增交易日期依 Asia/Taipei 判斷，不得晚於今天，最早為今天往前五年的同一天，包含邊界。已知首次上市櫃日時，也不得早於該日。
- 停止買賣、下市櫃及最後交易日的買進限制，以伺服器已同步的官方狀態判斷。
- 網頁 JavaScript 跨來源呼叫時，須依部署處理同源、proxy 或 CORS。Android 原生 HTTP 呼叫不受瀏覽器 CORS 限制。

以下流程會新增記帳資料。可用以下指令啟動獨立記憶體測試庫，使用 8081 埠。停止這個測試服務後資料會消失。若 8081 已使用，請另選空閒埠號。

Windows PowerShell，先進入專案根目錄，再設定目前視窗的 JAVA_HOME。以下路徑是範例，請按實際專案位置與已安裝的 JDK 根目錄調整。

~~~powershell
Set-Location -LiteralPath 'D:\ApXpert_Test\project'
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-17'
echo $env:JAVA_HOME
& "$env:JAVA_HOME\bin\java.exe" -version
& "$env:JAVA_HOME\bin\javac.exe" -version
.\mvnw.cmd -v
~~~

確認 java、javac 與 Maven 使用的 Java 都是 17 或以上版本，在同一個 PowerShell 視窗啟動測試服務。

~~~powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.arguments=--server.port=8081 --spring.datasource.url=jdbc:h2:mem:api-guide"
~~~

等待日誌顯示服務啟動，瀏覽 http://localhost:8081/ 確認首頁可開啟。停止服務時按 Ctrl+C。JAVA_HOME 設定只對目前視窗有效，新開視窗時需重新設定或使用已配置的系統環境變數。若顯示 JAVA_HOME not found 或 invalid directory，請核對 JDK 根目錄與 bin/java.exe 是否存在。

Git Bash 使用者先依 README 的「Windows Git Bash」設定 JDK。Git Bash、macOS 或 Linux 在專案根目錄執行。

~~~bash
sh ./mvnw spring-boot:run '-Dspring-boot.run.arguments=--server.port=8081 --spring.datasource.url=jdbc:h2:mem:api-guide'
~~~

後續 Base URL 範例使用 8081。請確認測試庫內沒有準備新增的代號。

## 2. 從零完成一次買進、加碼和部分賣出

### 2.1 設定測試變數

以下 curl 指令使用 Bash，可在 Git Bash、macOS 或 Linux 執行。先依第 1 節啟動 8081 的測試服務，保持服務視窗開啟，再開另一個 Bash 終端機執行請求。

~~~bash
BASE_URL='http://localhost:8081'
TRADE_DATE=$(TZ=CST-8 date +%F)
printf '%s\n' "$TRADE_DATE"
~~~

此處的 CST-8 使用 POSIX 時區寫法，表示 UTC+8，與台北目前日期一致。部分 Windows Git Bash 無法解析 Asia/Taipei 時區資料，會退回 UTC，因此範例使用明確的時差。確認輸出的日期與台北日期一致後再送交易。Bash 的變數與換行語法須在 Git Bash 執行，PowerShell 使用者可改用 Postman 或第 3 節 fetch。

### 2.2 查詢清單

~~~bash
curl -i "$BASE_URL/api/v2/holdings"
~~~

空測試庫應回 HTTP 200 和 []。已有資料時回傳持股陣列，包含尚未轉版的 1.0 持股。

### 2.3 建立持股並記錄 300 股買進

本節用 0050、300 股、每股 150 元作為第一筆資料。買賣價格是記帳輸入，官方來源用來驗證股票身分及取得收盤價。

~~~bash
curl -i -X POST "$BASE_URL/api/v2/holdings" \
  -H 'Content-Type: application/json' \
  --data "{\"code\":\"0050\",\"side\":\"BUY\",\"trade_date\":\"$TRADE_DATE\",\"shares\":300,\"unit_price\":150}"
~~~

成功回 HTTP 201 和一個持股物件。保存回應的 id，後續使用同一個值。只有實際回應 id 為 12 時，才使用下面的 12。

~~~bash
HOLDING_ID=12
~~~

買進金額為 45,000 元，手續費為 64 元，remaining_cost 為 45064.00，average_cost 為 150.2133，realized_pnl 為 0.00。

行情欄位由官方資料及快取決定。第一次讀取可能尚未取得收盤價，App 應能呈現 null。

### 2.4 同一檔再買進 300 股

~~~bash
curl -i -X POST "$BASE_URL/api/v2/holdings/$HOLDING_ID/trades" \
  -H 'Content-Type: application/json' \
  --data "{\"side\":\"BUY\",\"trade_date\":\"$TRADE_DATE\",\"shares\":300,\"unit_price\":160}"
~~~

成功回 HTTP 201 和更新後持股。shares 為 600，remaining_cost 為 93132.00，average_cost 為 155.2200。兩筆買進各自計算手續費，第二筆為 68 元。

再次 POST /api/v2/holdings 建立相同代號會回 HTTP 409 DUPLICATE_CODE。同檔加碼請向既有 id 追加交易。

### 2.5 賣出 100 股

~~~bash
curl -i -X POST "$BASE_URL/api/v2/holdings/$HOLDING_ID/trades" \
  -H 'Content-Type: application/json' \
  --data "{\"side\":\"SELL\",\"trade_date\":\"$TRADE_DATE\",\"shares\":100,\"unit_price\":170}"
~~~

成功回 HTTP 201。賣出金額 17,000 元，手續費 24 元、ETF 證交稅 17 元，分攤成本為 15,522 元。

| 欄位 | 預期值 |
| --- | --- |
| shares | 500 |
| remaining_cost | 77610.00 |
| average_cost | 155.2200 |
| realized_pnl | 1437.00 |

current_price、price_date 和 unrealized_pnl 隨官方行情及快取更新。若測試行情固定為每股 170 元，未實現損益為 7184.00。正式執行應以實際收盤價核算。

### 2.6 查詢單檔與交易紀錄

~~~bash
curl -i "$BASE_URL/api/v2/holdings/$HOLDING_ID"
curl -i "$BASE_URL/api/v2/holdings/$HOLDING_ID/trades"
~~~

成功均為 HTTP 200。交易紀錄應包含 BUY 300 股、BUY 300 股、SELL 100 股。

交易依日期排序，期初持股優先，同日依目前保存的順序排列。新增同日交易接在該日現有紀錄後面，補登過往日期會重新計算後續持股。

### 2.7 驗證超賣會拒絕儲存

~~~bash
curl -i -X POST "$BASE_URL/api/v2/holdings/$HOLDING_ID/trades" \
  -H 'Content-Type: application/json' \
  --data "{\"side\":\"SELL\",\"trade_date\":\"$TRADE_DATE\",\"shares\":501,\"unit_price\":170}"
~~~

應回 HTTP 409。

~~~json
{
  "error": {
    "code": "INSUFFICIENT_SHARES",
    "message": "2026-11-18 賣出 501 股，當時僅持有 500 股"
  }
}
~~~

訊息日期依實際輸入改變。再次查詢，shares 仍為 500，交易仍只有三筆。歷史交易以發生當時的股數檢查，後來的買進無法抵銷更早的超賣。

## 3. 使用 fetch 串接

下列程式可放進自己的前端。BASE_URL 使用呼叫端可連到的網址，同源部署可設為空字串。錯誤物件保留 status 與 code，App 可據此決定顯示確認視窗或欄位訊息。

~~~javascript
const BASE_URL = 'http://localhost:8081';

async function requestApi(path, { method = 'GET', body } = {}) {
  const headers = { Accept: 'application/json' };
  const options = { method, headers };
  if (body !== undefined) {
    headers['Content-Type'] = 'application/json';
    options.body = JSON.stringify(body);
  }

  const response = await fetch(BASE_URL + path, options);
  if (response.status === 204) {
    return null;
  }
  const payload = await response.json();
  if (!response.ok) {
    const error = new Error(payload.error?.message ?? '無法完成請求');
    error.status = response.status;
    error.code = payload.error?.code;
    throw error;
  }
  return payload;
}

function taipeiToday() {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Taipei', year: 'numeric', month: '2-digit', day: '2-digit'
  }).formatToParts(new Date());
  const value = type => parts.find(part => part.type === type).value;
  return value('year') + '-' + value('month') + '-' + value('day');
}

async function runExample() {
  const tradeDate = taipeiToday();
  const created = await requestApi('/api/v2/holdings', {
    method: 'POST',
    body: { code: '0050', side: 'BUY', trade_date: tradeDate, shares: 300, unit_price: 150 }
  });
  const path = '/api/v2/holdings/' + created.id;

  await requestApi(path + '/trades', {
    method: 'POST',
    body: { side: 'BUY', trade_date: tradeDate, shares: 300, unit_price: 160 }
  });
  const position = await requestApi(path + '/trades', {
    method: 'POST',
    body: { side: 'SELL', trade_date: tradeDate, shares: 100, unit_price: 170 }
  });
  const trades = await requestApi(path + '/trades');
  return { position, trades };
}
~~~

在乾淨測試庫執行 runExample()。App 正式畫面應將每筆買賣拆成使用者明確確認的操作。requestApi() 沒有自動重送，網路連線失敗由 fetch 拋出例外。

## 4. API 參考

### 4.1 路由與成功回應

| 方法 | 路徑 | HTTP | 回傳 |
| --- | --- | --- | --- |
| GET | /api/v2/holdings | 200 | 持股物件陣列，空清單為 [] |
| GET | /api/v2/holdings/{id} | 200 | 一個持股物件 |
| GET | /api/v2/holdings/{id}/trades | 200 | 交易物件陣列 |
| POST | /api/v2/holdings | 201 | 建立持股及第一筆買進後的持股物件 |
| POST | /api/v2/holdings/{id}/trades | 201 | 新增交易後的持股物件 |
| GET | /api/v2/summary | 200 | 全部持股的總計物件 |
| PUT | /api/v2/holdings/{id}/trades/{tradeId} | 200 | 修改交易後的持股物件 |
| DELETE | /api/v2/holdings/{id} | 204 | 空回應，刪除整檔持股及其全部交易 |

持股 id 與交易 id 都是伺服器產生的 64 位元整數。App 2.0 已提供持股新增、清單、單檔查詢、交易修改、整檔刪除與總計，涵蓋 1.0 的操作能力。2.0 以修改單筆交易調整股數與成本，修改路徑需同時帶持股 id 和該檔的交易 id。單筆交易刪除、排序目前透過 /v2/holdings 網頁操作，API 尚未提供。清單與交易紀錄目前沒有分頁。

### 4.2 POST 欄位

| 欄位 | 型別 | 建立持股 | 追加交易 |
| --- | --- | --- | --- |
| code | 字串 | 必填，4 至 6 碼，前 4 碼數字，其餘限數字或大寫英文字母 | 以 URL 的持股 id 決定股票，請省略 |
| side | 字串 | 使用 BUY；省略或 null 目前也視為 BUY | 必填，BUY 或 SELL |
| trade_date | 字串 | 必填，YYYY-MM-DD | 同左 |
| shares | 整數 | 必填，大於 0 | 同左 |
| unit_price | 數字 | 必填，大於 0，最多兩位小數及 36 位整數 | 同左 |
| confirm_v2 | 布林 | 新持股直接由 2.0 管理，可省略 | 舊持股須經使用者確認後填 true |

shares 使用 Java long，單次最多 9223372036854775807 股，累計仍不得溢位。App 應設定合理輸入上限。JavaScript 安全整數上限為 9007199254740991，更大的 id 或股數須使用能精確處理 JSON 整數的工具。

代號格式通過後，伺服器仍須由官方或既有市場狀態確認身分。目前支援上市 TWSE、上櫃 TPEX，興櫃不在串接範圍。OPENING 僅供讀取期初持股，呼叫端建立買賣應使用 BUY 或 SELL。

### 4.3 持股物件

以下為第 2 節操作後，固定收盤價 170 元時的完整欄位範例。id、價格日期應依實際回應為準。

~~~json
{
  "id": 12,
  "code": "0050",
  "name": "元大台灣50",
  "market": "TWSE",
  "v2_managed": true,
  "shares": 500,
  "remaining_cost": 77610.00,
  "average_cost": 155.2200,
  "realized_pnl": 1437.00,
  "unrealized_pnl": 7184.00,
  "realized_return_pct": 9.26,
  "unrealized_return_pct": 9.26,
  "current_price": 170.00,
  "price_date": "2026-11-18"
}
~~~

| 欄位 | 型別 | 意義 |
| --- | --- | --- |
| id | 整數 | 持股識別值 |
| code | 字串 | 股票代號 |
| name | 字串或 null | 股票名稱，舊資料尚未補齊身分時可能為 null |
| market | 字串或 null | TWSE 或 TPEX，舊資料尚未補齊身分時可能為 null |
| v2_managed | 布林 | true 表示已由 2.0 管理 |
| shares | 整數 | 剩餘股數 |
| remaining_cost | 數字 | 含買進手續費的剩餘成本，保留兩位小數 |
| average_cost | 數字或 null | remaining_cost ÷ shares，四捨五入到四位小數；零股數時為 null |
| realized_pnl | 數字 | 已實現損益加總，保留兩位小數，尚未賣出為 0 |
| unrealized_pnl | 數字或 null | 剩餘持股依收盤價一次全賣的預估淨損益；缺行情為 null，零股數為 0 |
| realized_return_pct | 數字或 null | 已實現損益 ÷ 累計分攤成本 × 100，四捨五入到兩位；尚未賣出為 null |
| unrealized_return_pct | 數字或 null | 未實現損益 ÷ 剩餘成本 × 100，四捨五入到兩位；缺價或剩餘成本為 0 時為 null |
| current_price | 數字或 null | 快取中的最近已知官方收盤價 |
| price_date | 字串或 null | 收盤價實際交易日；缺行情時為 null |

average_cost 的四位小數是目前 API 精度，網頁另顯示到兩位。請直接使用 remaining_cost 核算成本。

App 直接使用伺服器回傳的兩種損益率。realized_cost 及下市狀態目前沒有輸出為個股 JSON 欄位；總計另提供 delisted_cost，供顯示已下市櫃且尚未售出的成本。

### 4.4 交易物件

~~~json
[
  { "id": 1, "side": "BUY", "trade_date": "2026-11-18", "shares": 300, "unit_price": 150.00 },
  { "id": 2, "side": "BUY", "trade_date": "2026-11-18", "shares": 300, "unit_price": 160.00 },
  { "id": 3, "side": "SELL", "trade_date": "2026-11-18", "shares": 100, "unit_price": 170.00 }
]
~~~

| 欄位 | 型別 | 意義 |
| --- | --- | --- |
| id | 整數 | 該筆交易的識別值，供修改 API 使用 |
| side | 字串 | OPENING、BUY 或 SELL |
| trade_date | 字串或 null | 交易日期，OPENING 為 null |
| shares | 整數 | 該筆股數 |
| unit_price | 數字 | 該筆每股價格，最多兩位小數 |

App 保留回傳順序，同日可能有內容相同的多筆真實交易。修改時使用各筆 id；id 範例值需換成實際回傳值。網頁上下移按鈕的狀態沒有輸出。

1.0 的 2 張、每股 150 元帶入後，期初紀錄如下。既有資料沒有買進日期，因此日期為 null。

~~~json
[
  { "id": 4, "side": "OPENING", "trade_date": null, "shares": 2000, "unit_price": 150.00 }
]
~~~

### 4.5 查詢總計

~~~bash
curl -i "$BASE_URL/api/v2/summary"
~~~

成功為 HTTP 200，計算範圍包含未轉版的 1.0 持股及 2.0 持股。若只執行第 2 節的三筆交易，且收盤價固定為 170 元，範例如下。

~~~json
{
  "remaining_cost": 77610.00,
  "market_value": 85000.00,
  "unrealized_pnl": 7184.00,
  "unrealized_return_pct": 9.26,
  "realized_pnl": 1437.00,
  "realized_return_pct": 9.26,
  "quote_missing": 0,
  "delisted_cost": 0
}
~~~

| 欄位 | 型別 | 意義 |
| --- | --- | --- |
| remaining_cost | 數字 | 全部剩餘成本加總，含買進手續費，也包含缺行情及下市櫃持股 |
| market_value | 數字 | 有行情且未下市櫃的剩餘市值加總，不扣賣出費稅 |
| unrealized_pnl | 數字 | 有行情且未下市櫃的未實現損益加總 |
| unrealized_return_pct | 數字或 null | 總未實現損益 ÷ 全部剩餘成本 × 100，四捨五入至兩位；分母為 0 時為 null |
| realized_pnl | 數字 | 全部已實現損益加總，含已賣清持股 |
| realized_return_pct | 數字或 null | 總已實現損益 ÷ 全部已分攤成本 × 100，四捨五入至兩位；分母為 0 時為 null |
| quote_missing | 整數 | 尚有股數、未下市櫃且缺少收盤價的持股筆數 |
| delisted_cost | 數字 | 已下市櫃且尚有股數的剩餘成本加總 |

缺行情及下市櫃的剩餘成本保留在分母，市值及未實現損益排除，畫面應搭配 quote_missing 和 delisted_cost 呈現。空清單的各金額為 0，兩種損益率為 null。總收益率依總損益與總成本計算，不能將個股損益率直接平均。

### 4.6 修改一筆交易

先 GET /api/v2/holdings/{id}/trades，從回應選出要更正的交易 id。以下示範修改第一筆 BUY，TRADE_ID 請填實際值。進行本段前先完成第 2 節驗收，修改後的數字會跟著重算。

~~~bash
TRADE_ID=1
curl -i -X PUT "$BASE_URL/api/v2/holdings/$HOLDING_ID/trades/$TRADE_ID" \
  -H 'Content-Type: application/json' \
  --data "{\"trade_date\":\"$TRADE_DATE\",\"shares\":300,\"unit_price\":149.5}"
~~~

成功為 HTTP 200，回傳重新計算後的持股物件，交易 id 保留。

| 請求欄位 | 型別 | 規則 |
| --- | --- | --- |
| trade_date | 字串或 null | BUY、SELL 必填，依既有交易日期限制驗證；OPENING 省略或填 null |
| shares | 整數 | 必填，大於 0，符合 long 範圍 |
| unit_price | 數字 | 必填，大於 0，最多兩位小數及 36 位整數 |
| confirm_v2 | 布林 | 修改未轉版持股時須經使用者同意後填 true |

路徑選定持股與交易，股票代號及交易方向由原交易決定。PUT 會替換日期、股數及每股價格，重新回放後續交易。若造成歷史超賣則回 409 INSUFFICIENT_SHARES 並回滾。交易不存在或屬於另一檔持股回 404 NOT_FOUND。

舊於五年的交易可以更正股數與價格，日期必須維持原值。其他日期依目前五年、首次上市櫃日和未來日期限制驗證。OPENING 修改時日期必須為 null。未轉版的期初交易會先回 409 CONFIRM_V2_REQUIRED，確認後才轉由 2.0 管理。

~~~javascript
const updated = await requestApi(
  '/api/v2/holdings/' + holdingId + '/trades/' + tradeId,
  { method: 'PUT', body: { trade_date: selectedDate, shares: 300, unit_price: 149.5 } }
);
~~~

holdingId、tradeId、selectedDate 由 App 的持股、交易選擇與日期欄位取得。

### 4.7 刪除整檔持股

App 先顯示確認訊息「刪除整檔持股會一併移除所有交易和已實現損益紀錄」，使用者確認後才發 DELETE。

~~~bash
curl -i -X DELETE "$BASE_URL/api/v2/holdings/$HOLDING_ID"
~~~

成功回 HTTP 204，body 為空。第 3 節 requestApi() 對 204 直接回 null，不再呼叫 response.json()。

~~~javascript
await requestApi('/api/v2/holdings/' + holdingId, { method: 'DELETE' });
const holdings = await requestApi('/api/v2/holdings');
const summary = await requestApi('/api/v2/summary');
~~~

此請求同時刪除期初持股、全部買賣與該檔已實現損益。成功後重新載入清單與總計。尚未轉版的 1.0 持股也能從此 API 刪除，兩版都會反映。持股不存在或已刪除回 404 NOT_FOUND。交易與持股刪除在同一筆資料庫交易內完成。

## 5. 費用、成本及缺行情處理

1. 成交金額 = 該筆股數 × 每股價格，使用十進位運算。
2. 每筆買進及賣出分別計算手續費。成交金額 × 0.001425，捨去至整元，每筆最低 20 元。
3. 賣出證交稅依代號，00 開頭用 0.001，其餘用 0.003，捨去至整元。
4. 每筆買進將成交金額與手續費加進剩餘成本，依交易順序推進移動加權平均。
5. 部分賣出分攤成本 = 賣出當下成本 × 賣出股數 ÷ 當時股數，四捨五入到分。全部賣清時分攤全部剩餘成本。
6. 該筆已實現損益 = 賣出金額 − 賣出手續費 − 證交稅 − 分攤成本。剩餘成本由原成本扣掉分攤成本，維持成本守恆。
7. 未實現損益假設剩餘股數依 current_price 一次賣清，再扣一筆賣出手續費、證交稅和剩餘成本。

2.0 的零股成交金額與成本可包含分，v2 金額可有兩位小數。App 1.0 的整數元規格繼續適用於 /api/v1。

缺價時 current_price、price_date 及有剩餘持股的 unrealized_pnl 為 null，App 顯示「未取得」。已有快取時保留價格及實際交易日期，背景刷新後再次 GET 才能讀到新值。全數賣清後 shares、remaining_cost、unrealized_pnl 為 0，average_cost 為 null，已實現損益仍保留。

已確認下市櫃時，歷史最後價不計入目前未實現損益，current_price 和 price_date 為 null。既有已實現損益照常回傳。App 目前無法只從 null 區分下市與暫時缺行情。股票分割和其他公司行動未自動調整股數或成本。

## 6. 與 App 1.0 共存及確認轉版

- /api/v1 的張數 CRUD 與總計繼續提供，只納入 v2_managed=false 的持股。
- 2.0 清單包含兩種持股，單純 GET 不會轉版，新建持股直接由 2.0 管理。
- 向 v2_managed=false 的持股追加交易，confirm_v2 省略或 false 時回 409 CONFIRM_V2_REQUIRED。
- App 應顯示確認視窗，說明該檔將移出 App 1.0 清單與總計，之後由 2.0 管理。使用者同意後才送同一筆交易並填 confirm_v2=true。
- 交易驗證或回放失敗會回滾，舊持股仍可從 1.0 操作。成功轉版後，後續追加可省略 confirm_v2。
- 已轉版持股從 1.0 單檔查詢、修改或刪除會回 409 HOLDING_REQUIRES_V2。在 1.0 重複建立同代號回 409 DUPLICATE_CODE，訊息提示使用新版。

HOLDING_ID 請使用 v2 清單內 v2_managed=false 的持股 id。確認後送出範例如下。

~~~bash
curl -i -X POST "$BASE_URL/api/v2/holdings/$HOLDING_ID/trades" \
  -H 'Content-Type: application/json' \
  --data "{\"side\":\"BUY\",\"trade_date\":\"$TRADE_DATE\",\"shares\":300,\"unit_price\":160,\"confirm_v2\":true}"
~~~

### 1.0 的 409 與 503 決策

沿用逐檔確認轉版的方案。409 HOLDING_REQUIRES_V2 表示該持股已不符合舊版張數模型，503 QUOTE_UNAVAILABLE 表示暫時無法確認身分。兩者的錯誤 body 都維持原有 error.code、error.message 格式。

這些 HTTP 狀態與錯誤碼超出 App 1.0 原題列出的部分清單，屬於相容設計的取捨，不能視為完全符合原清單。伺服器使用 409 避免舊版覆寫新版帳本，使用 503 區分暫時失敗與確定查無代號。原 App 會依既有錯誤呈現機制顯示訊息。未轉版持股的原 v1 操作、欄位與成功狀態繼續沿用。

舊 App 不會主動收到通知。新版網頁刪除整檔持股會移除共用資料，1.0 和 2.0 都會反映刪除結果。其他未轉版持股仍能由 1.0 操作。

## 7. 錯誤與重試

~~~json
{
  "error": {
    "code": "VALIDATION_ERROR",
    "message": "股數必須大於 0"
  }
}
~~~

App 先依 HTTP 狀態和 error.code 決定流程，再顯示 error.message。多欄位有錯時一次回一則訊息，請勿依賴欄位錯誤出現順序。

| HTTP | error.code | 情境及處理 |
| --- | --- | --- |
| 400 | VALIDATION_ERROR | 缺欄位、非法數值、JSON 型別或日期不符，修正輸入 |
| 404 | NOT_FOUND | 持股或交易不存在、交易不屬於該持股、id 非合法整數或 API 路徑不存在 |
| 404 | STOCK_NOT_FOUND | 新建時上市、上櫃都查不到代號 |
| 409 | DUPLICATE_CODE | 同代號已存在，取得既有 id 追加交易 |
| 409 | CONFIRM_V2_REQUIRED | 舊持股尚未確認由 2.0 管理 |
| 409 | HOLDING_REQUIRES_V2 | 從 v1 操作已轉版持股 |
| 409 | INSUFFICIENT_SHARES | 任一時間點超賣，檢查順序及歷史買進 |
| 405 | METHOD_NOT_ALLOWED | 路徑不支援指定 HTTP 方法 |
| 415 | UNSUPPORTED_MEDIA_TYPE | 請以 application/json 傳送 |
| 500 | INTERNAL_ERROR | 顯示一般錯誤，詳細原因由伺服器日誌記錄 |
| 503 | QUOTE_UNAVAILABLE | 建立時無法驗證股票身分，稍後由使用者再嘗試 |

官方來源共用完成後 30～60 秒的隨機間隔，連線或 HTTP 失敗冷卻至少 30 分鐘。
首次驗證或批次缺代號而需查個股資料時，來源等待可能超過後端的 10 秒查詢期限，
先回 503 QUOTE_UNAVAILABLE。查詢可能仍在背景填入快取，App 可顯示訊息讓使用者
稍後再驗證。已回明確 503 的這次身分驗證沒有建立持股，正常 POST 逾時的去重限制
仍依本節下方處理。已有快取的清單可先呈現價格和日期。

行情缺少但持股可讀時，GET 回 200 及 null 行情欄位。部分超出表示範圍的數字或其他未預期輸入也可能回 500，呼叫端需處理一般錯誤。

目前 POST 沒有 idempotency key 或請求去重。連線逾時時交易可能已儲存，先重新 GET 持股及交易紀錄，再由使用者確認是否重送。相同日期、股數、價格可代表多筆有效交易，僅比對內容無法保證識別原請求，請避免自動無限重送。

## 8. 串接驗收清單

- [ ] 能連到伺服器，空清單正確解析成 []。
- [ ] 建立 300 股後保存 id，追加買進、賣出沿用該 id。
- [ ] 第 2 節最後為 500 股、剩餘成本 77610.00、已實現損益 1437.00。
- [ ] 缺價可顯示「未取得」，已實現損益仍呈現。
- [ ] 超賣回 409，股數與交易筆數維持原值。
- [ ] 未來日期和超過五年前的新增被拒絕。
- [ ] 可呈現 OPENING 的 null 日期與從張換算的股數。
- [ ] 取得使用者確認才送 confirm_v2=true，失敗交易沒有轉版。
- [ ] 未轉版持股仍能由 1.0 操作，已轉版持股移出舊清單與總計。
- [ ] 正確顯示個股與總計的已實現、未實現損益率及 null。
- [ ] 依交易 id 修改，原 id 保留，超賣修改回滾。
- [ ] 整檔刪除的 204 空回應可正確處理，清單與總計重新載入。
- [ ] 正確處理金額精度和實際 JSON 欄位。
- [ ] POST 逾時先確認伺服器狀態，再決定是否重送。

本文件固定收盤價範例供核算使用。正常啟動會使用官方行情，自動化測試則用固定行情驗證計算與 API。

