# 從 1.0 升級至 2.0

本手冊適用於 v1.0.0 的 H2 檔案資料庫，採題目指定的停機切換方式。升級前先在獨立環境演練，再停止舊版、備份資料庫，讓新版使用同一個資料庫啟動。上線驗收完成後再開放記帳。

啟動環境請參閱 [README](README.md)，App 2.0 串接請參閱 [API.md](API.md)。

## 1. 升級前準備

1. 記錄目前執行的 commit、Java 版本、服務埠號、完整啟動參數，以及資料庫的實際絕對路徑。
2. 記錄既有資料庫帳號、環境變數和部署設定，透過原本的私有管道保存。升級時沿用原設定。
3. 準備 v1.0.0 舊版與本次預定上線版本的 JAR，在停機前完成建置與測試。
4. 用測試資料庫演練第 4～7 節，確認新版啟動、兩版 API、備份及回復流程都可用。
5. 安排停止記帳的時間，停機切換與驗收期間先暫停手機和網頁操作，記錄可接受的停機時間。

本專案預設資料庫 URL 為 jdbc:h2:file:./data/holdings，實體檔案為啟動工作目錄下的 data/holdings.mv.db。Eclipse 的 Run Configuration 工作目錄會影響這個位置。切換版本時，請改用已確認的絕對路徑，並加上 IFEXISTS=TRUE。路徑錯誤時會直接失敗，避免誤建空資料庫。

兩版 pom 的專案版本皆為 0.0.1-SNAPSHOT，因此輸出 JAR 同名。請將兩版建置放在不同目錄，記錄各自 commit 與 JAR SHA-256，避免取錯檔案。

### Windows PowerShell 準備兩版程式

以下路徑是部署範例。JAVA_HOME 依 README 設定，JDK 需為 17 或以上。deployRoot 必須使用新的目錄，若已經有 release-v1、release-v2，請改名並保留舊檔。

~~~powershell
$deployRoot = 'D:\ApXpert_Deploy'
New-Item -ItemType Directory -Path $deployRoot -Force | Out-Null
Set-Location -LiteralPath $deployRoot

git clone --branch v1.0.0 --single-branch https://github.com/WynnStar/ApXpert_Test.git release-v1
git clone https://github.com/WynnStar/ApXpert_Test.git release-v2

Set-Location -LiteralPath "$deployRoot\release-v1"
git rev-parse HEAD
.\mvnw.cmd -B package

Set-Location -LiteralPath "$deployRoot\release-v2"
git log -1 --oneline
.\mvnw.cmd -B package

$oldJar = "$deployRoot\release-v1\target\holdings-0.0.1-SNAPSHOT.jar"
$newJar = "$deployRoot\release-v2\target\holdings-0.0.1-SNAPSHOT.jar"
Get-FileHash -LiteralPath $oldJar, $newJar -Algorithm SHA256
~~~

release-v2 預設取得遠端預設分支。請核對內容包含預定上線變更。正式建立 v2.0.0 後，之後重建應先在 release-v2 執行 git fetch --tags 與 git checkout --detach v2.0.0，再建置。每個建置指令都必須成功才繼續。

### macOS／Linux 準備兩版程式

~~~bash
DEPLOY_ROOT="$PWD/holdings-deploy"
mkdir -p "$DEPLOY_ROOT"
cd "$DEPLOY_ROOT"

git clone --branch v1.0.0 --single-branch https://github.com/WynnStar/ApXpert_Test.git release-v1
git clone https://github.com/WynnStar/ApXpert_Test.git release-v2

cd "$DEPLOY_ROOT/release-v1"
git rev-parse HEAD
sh ./mvnw -B package

cd "$DEPLOY_ROOT/release-v2"
git log -1 --oneline
sh ./mvnw -B package

OLD_JAR="$DEPLOY_ROOT/release-v1/target/holdings-0.0.1-SNAPSHOT.jar"
NEW_JAR="$DEPLOY_ROOT/release-v2/target/holdings-0.0.1-SNAPSHOT.jar"
~~~

Linux 可用 sha256sum "$OLD_JAR" "$NEW_JAR"，macOS 可用 shasum -a 256 "$OLD_JAR" "$NEW_JAR"。保留建置日誌、版本和雜湊值供部署比對。

## 2. 資料庫會發生什麼變動

v1.0.0 包含 V1～V3，持股表、行情快取表及 Flyway 歷史表共三張。新版啟動時，Flyway 會自動執行 V4～V10。Hibernate 使用 ddl-auto=validate，SQL 初始化為 never，由 migration 負責結構變更。

| Migration | 變動 | 對資料的影響 |
| --- | --- | --- |
| V4 | 新增 holding_trades、外鍵、欄位約束與交易順序索引 | 提供逐筆交易紀錄 |
| V5 | holdings 新增 v2_managed；lots、buy_price 可為 null；交易方向加入 OPENING，期初交易可無日期；新增版本資料約束 | 將每筆既有持股補成一筆 OPENING，股數為 lots × 1000；既有持股保持 v2_managed=false |
| V6 | quote_cache 新增嘗試時間、下次重試時間、失敗次數、收盤後重試次數與查詢索引 | 保留舊行情，補入刷新與退避資訊 |
| V7 | holding_trades 新增 sort_order，既有值用交易 id 回填，調整順序索引 | 保留既有同日順序 |
| V8 | 新增 market_event、market_identity、market_final_close、market_sync_day | 記錄市場事件、首次上市櫃日、最後收盤價及每日同步進度 |
| V9 | 新增 tpex_delisting_archive_year | 記錄已載入的櫃買歷史下櫃年度 |
| V10 | 新增 tpex_listing_transfer_year | 記錄已載入的上櫃轉上市年度 |

完成 V10 後，應有九張應用程式表，加上 flyway_schema_history 共十張。索引或 migration 檔案數量不等於資料表數。

舊持股 id、code、lots、buy_price 保留。例如 id=12、0050、2 張、150 元，升級後仍能由 App 1.0 取得相同 id 與張數。2.0 則從 OPENING 算出 2,000 股，含買進手續費的成本為 300,427 元。原始資料沒有買進日期，期初交易日期為 null，不會捏造歷史日期。

成功轉由 2.0 管理時，v2_managed 變成 true，holdings.lots 和 holdings.buy_price 清成 null，逐筆交易成為成本與股數的計算依據。股數及損益由帳本計算，不能用手動回填張數的方式把持股改回 1.0。

## 3. 演練與正式資料的使用方式

先用獨立資料庫與未占用的埠號演練。正式資料副本應在舊程式已停止、資料庫已關閉後複製。若需要不停機取得副本，依 H2 的線上備份機制另行安排。

演練至少準備一筆 1.0 持股，保存 id、代號、張數、買進價與 API 回應。用新版指向同一份測試資料庫後，確認資料、API 與期初交易，再進行第 7 節的回復演練。

每次正式切換仍要在停機後重新備份。較早的演練副本可能缺少近期交易，不能直接當成本次切換的回復點。

## 4. 停止舊版並建立回復點

先暫停使用者記帳，再在舊服務的終端機按 Ctrl+C，或由 Eclipse 按停止，確認 Java 程序結束且日誌已關閉資料庫。若有服務管理器，使用原本的停止服務方式。

同一份 H2 檔案一次只供一個應用程式開啟。備份前若仍有執行中的服務或 H2 Console 使用它，先正常關閉對應連線。

### Windows PowerShell 冷備份

沿用第 1 節變數。在部署用的 PowerShell 視窗設定實際資料庫路徑，下面假設原資料位於 D:\ApXpert_Test\project\data。

~~~powershell
$dbBase = 'D:/ApXpert_Test/project/data/holdings'
$dbFile = "$dbBase.mv.db"
if (!(Test-Path -LiteralPath $dbFile -PathType Leaf)) {
    throw '找不到既有資料庫，請核對實際路徑'
}

$backupDir = Join-Path $deployRoot ("backups\before-v2-" + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Path $backupDir | Out-Null
$backupFile = Join-Path $backupDir 'holdings.mv.db'
Copy-Item -LiteralPath $dbFile -Destination $backupFile

$originalHash = (Get-FileHash -LiteralPath $dbFile -Algorithm SHA256).Hash
$backupHash = (Get-FileHash -LiteralPath $backupFile -Algorithm SHA256).Hash
if ($originalHash -ne $backupHash) {
    throw '備份雜湊不一致，請先處理備份'
}
$backupDir
$backupHash
~~~

將 backupDir、雜湊、舊版 commit、JAR 雜湊與部署設定記錄在私有部署紀錄。備份可能包含使用者資料，放在 repo 外並限制存取。

### macOS／Linux 冷備份

DB_BASE 請填實際資料庫的絕對路徑，省略 .mv.db。

~~~bash
DB_BASE='/absolute/path/to/project/data/holdings'
DB_FILE="$DB_BASE.mv.db"
test -f "$DB_FILE" || { printf '%s\n' '找不到既有資料庫'; exit 1; }

BACKUP_DIR="$DEPLOY_ROOT/backups/before-v2-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$BACKUP_DIR"
BACKUP_FILE="$BACKUP_DIR/holdings.mv.db"
cp -p "$DB_FILE" "$BACKUP_FILE"
cmp "$DB_FILE" "$BACKUP_FILE" || { printf '%s\n' '備份內容不一致'; exit 1; }
printf '%s\n' "$BACKUP_DIR"
~~~

Linux 用 sha256sum，macOS 用 shasum -a 256 記錄備份雜湊。正式啟動與回復需沿用這次的路徑和備份。

## 5. 讓新版連同一份資料庫啟動

維持暫停記帳。使用已建置的新版 JAR，沿用原本埠號、資料庫帳密與其他設定。這份專案的兩版都使用 Spring Boot 內嵌 Tomcat，可直接執行 JAR。

Windows PowerShell，在部署視窗執行。

~~~powershell
java -jar $newJar "--server.port=8080" "--spring.datasource.url=jdbc:h2:file:$dbBase;IFEXISTS=TRUE"
~~~

macOS／Linux，在部署終端機執行。

~~~bash
java -jar "$NEW_JAR" --server.port=8080 "--spring.datasource.url=jdbc:h2:file:$DB_BASE;IFEXISTS=TRUE"
~~~

帳密透過既有部署設定或 SPRING_DATASOURCE_USERNAME、SPRING_DATASOURCE_PASSWORD 環境變數提供。請沿用該資料庫既有帳密，避免在版本切換時意外換帳號。機密設定以私有方式保存。

等待 Flyway 執行完成，日誌應顯示 V4～V10 成功套用，以及 Spring Boot 完成啟動。已是新版資料庫時會驗證現有 migration，重啟不應再補一次 OPENING。若路徑不存在、資料庫被鎖住、帳密不符、migration 或結構驗證失敗，先保留日誌並依第 7 節處理。

直接停機複製已關閉的 .mv.db 可建立 H2 冷備份。Migration 失敗可能留下部分結構變更，因此回復流程以完整的升級前備份為準。H2 與 Flyway 的備份、交易行為請參閱文末官方說明。

## 6. 開放使用前驗收

先核對資料與讀取功能。

- 首頁 / 顯示「持股記帳」與時間，/holdings 與 /v2/holdings 都能載入。
- GET /api/v1/holdings 仍包含未轉版持股，id、code、lots、buy_price 與升級前一致。
- GET /api/v1/summary 可正常取得總計。行情日期與盈虧可能隨最新快取改變，請以固定欄位核對舊資料是否保留。
- GET /api/v2/holdings 可看到舊持股的換算股數，v2_managed=false。
- GET /api/v2/holdings/{id}/trades 有且只有一筆對應的 OPENING，日期為 null。
- 升級日誌沒有失敗 migration 或 Hibernate schema validation 錯誤。

Windows 可在另一個 PowerShell 視窗執行以下唯讀驗收。

~~~powershell
Invoke-RestMethod 'http://localhost:8080/api/v1/holdings'
Invoke-RestMethod 'http://localhost:8080/api/v1/summary'
Invoke-RestMethod 'http://localhost:8080/api/v2/holdings'
~~~

macOS／Linux 或 Git Bash 可執行。

~~~bash
curl -i 'http://localhost:8080/api/v1/holdings'
curl -i 'http://localhost:8080/api/v1/summary'
curl -i 'http://localhost:8080/api/v2/holdings'
~~~

選取實際回傳的 id，再查交易紀錄。id=12 的例子僅適用於測試資料確實有該 id。

~~~bash
curl -i 'http://localhost:8080/api/v2/holdings/12/trades'
~~~

寫入驗收在演練資料庫完成，逐步範例見 API.md。確認 300 股買進、多次加碼、部分賣出、逐筆費用、超賣拒絕、錯誤格式及轉版確認都正常。若正式驗收需要寫入，也應使用經同意的測試紀錄，並記下操作結果。

完成驗收、確認恢復服務後，才允許使用者重新記帳。保留此次部署版本、備份位置、時間與驗收結果。

## 7. 新版啟動或驗收失敗時回復

1. 維持暫停記帳，停止新版，確認資料庫關閉。
2. 保存新版日誌與當下資料庫，供除錯及查找上線期間新增的交易。
3. 將第 4 節備份還原至原資料庫路徑，核對內容或雜湊。
4. 使用 v1.0.0 JAR、原本帳密與相同資料庫路徑啟動。
5. 核對舊 API 與持股資料，確認後再恢復操作。

Flyway 沒有在本專案提供 V10 回到 V3 的反向 migration。請使用完整備份恢復舊結構。直接切換 JAR 可能讓舊版讀到已轉版且 lots、buy_price 為 null 的資料，或遇到 Flyway 版本驗證問題。

### Windows PowerShell 回復

舊版啟動視窗與部署視窗可能不同，請先在部署視窗恢復第 1、4 節的變數。以下假設資料庫已停止。

~~~powershell
if (!(Test-Path -LiteralPath $backupFile -PathType Leaf)) {
    throw '找不到選定的升級前備份'
}
$preservedFile = "$dbBase.v2-failed-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + '.mv.db'
Move-Item -LiteralPath $dbFile -Destination $preservedFile
Copy-Item -LiteralPath $backupFile -Destination $dbFile

if ((Get-FileHash -LiteralPath $dbFile -Algorithm SHA256).Hash -ne $backupHash) {
    throw '還原後雜湊不一致'
}
java -jar $oldJar "--server.port=8080" "--spring.datasource.url=jdbc:h2:file:$dbBase;IFEXISTS=TRUE"
~~~

### macOS／Linux 回復

~~~bash
test -f "$BACKUP_FILE" || { printf '%s\n' '找不到選定備份'; exit 1; }
PRESERVED_FILE="$DB_BASE.v2-failed-$(date +%Y%m%d-%H%M%S).mv.db"
mv "$DB_FILE" "$PRESERVED_FILE"
cp -p "$BACKUP_FILE" "$DB_FILE"
cmp "$DB_FILE" "$BACKUP_FILE" || { printf '%s\n' '還原內容不一致'; exit 1; }

java -jar "$OLD_JAR" --server.port=8080 "--spring.datasource.url=jdbc:h2:file:$DB_BASE;IFEXISTS=TRUE"
~~~

還原到升級前備份後，升級後新增、修改、刪除的交易不會出現在該備份中。已開放記帳才出問題時，先保存完整新版資料，再決定修復新版或恢復舊版並安排人工核對、補登。持有不相容新版結構的檔案供舊版直接操作，會增加資料遺漏和語意不一致的風險。

## 8. 上線後注意事項

| 項目 | 要確認的行為 |
| --- | --- |
| App 1.0 共存 | 未轉版持股繼續使用原 API。單純讀取新版不會轉版 |
| 逐檔轉版 | 使用者確認並成功新增或修改交易後，該檔從 1.0 清單及總計移除；舊版單檔操作回 409 |
| 共用刪除 | 2.0 網頁刪除整檔持股會移除共用資料及交易，兩版都會反映 |
| 行情缺少 | 清單可載入，缺價顯示「未取得」，已實現損益仍保留，歷史最後價不計入目前未實現損益 |
| 行情刷新 | 週一至週五台北時間 13:35、15:05 兩時段，每市場各時段最多一次；取得當日資料即停止。晚啟動只補最近時段，冷卻或佇列可使實際查詢延後 |
| 市場狀態同步 | 台北時間每日 08:00 排程。08:00 後啟動時會提交補同步，歷史下櫃與轉板資料由背景作業載入 |
| 官方連線 | 同來源完成後隨機間隔 30～60 秒，連線或 HTTP 失敗至少冷卻 30 分鐘，Retry-After 更晚時遵守標頭。首次歷史同步可能較久，需觀察日誌 |
| H2 部署 | 檔案模式一次執行一個應用程式。資料存於固定、可寫入且持續保存的路徑 |
| 備份 | 定期做可回復的資料備份，另保留對應程式版本，資料檔、備份與機密設定放在 repo 外 |
| POST 重送 | API 沒有請求去重，逾時後先核對交易結果，再由使用者確認重送 |
| 應用範圍 | 目前沒有使用者隔離與登入。股票分割或其他公司行動未自動換算，興櫃不在行情支援範圍 |

時段嘗試時間沿用 quote_cache 既有欄位保存，這次排程調整沒有新增 migration。
HTTP 來源冷卻門閂位於程序記憶體，重啟會重設；已有快取的批次時段限制仍從資料庫恢復。
首次驗證可能受來源等待影響而先回 503，詳見 API.md。兩時段限制針對收盤後批次，
暖機、新代號驗證與歷史同步仍可能有其他請求。

目前只用週末與時段判斷收盤後刷新，沒有完整的交易所休市日曆。不要將缺少當日價格直接判定為股票下市。正式機器的時間與時區設定也應正確。

2.0 API 已提供的範圍、金額精度、缺價及舊版轉版行為以 API.md 為準。

## 9. 本次隔離演練紀錄

演練使用 v1.0.0 原始碼、目前新版程式、JDK 25.0.3 和 H2 2.3.232。兩版編譯目標為 Java 17。資料庫位於獨立測試目錄，指定同一份 H2 檔案，所有資料均為假資料。

演練以測試啟動器載入兩版實際 Spring Boot 類別，分別啟動嵌入式 Tomcat 並經 HTTP 驗收。正式交付的兩版 JAR 另已建置成功。演練以固定的本地行情快取驗證資料與 API，關閉市場狀態同步，行情網址指向本機不可用埠號，沒有依賴官方網站可用性。未模擬實體手機、正式網路切換或完整官方歷史資料同步。

2026-11-18 已完成以下演練。新版本以 4b5167f 的程式為驗證基準，舊版為 v1.0.0 對應的 6d4a010。

| 驗證項目 | 實際結果 |
| --- | --- |
| 舊版與冷備份 | 1.0 執行 V1～V3，保存 id=12、0050、2 張、每股 150 元，關閉服務後備份 SHA-256 相符 |
| 原資料庫升級 | 同一個 H2 路徑執行 V4～V10，id 不變，產生一筆 2,000 股、日期 null 的 OPENING，v1 單檔查詢仍回 200 |
| 轉版與交易 | 未確認追加回 409，確認後買入 300 股回 201，超賣 2,301 股回 409，賣出 100 股回 201，已轉版持股從 v1 查詢回 409 |
| 新版重啟 | 剩餘 2,200 股，交易維持三筆，OPENING 只有一筆，成功 migration 版本仍為十個 |
| 備份回復 | 停止新版並保存新版資料，還原 V3 備份後 SHA-256 相符，1.0 重新啟動且讀到原 id=12、2 張、150 元；新版期間交易保留在另外保存的新版檔案中 |

兩版均執行 Maven package 並成功產出 JAR。既有 TradeMigrationTest 兩項測試通過，涵蓋 V3 升級保留原持股與 V6 升級保留同日交易順序。演練只驗證上述 JDK 25 執行環境，尚未在 JDK 17 執行環境或 macOS／Linux 實機重跑完整升降版。

## 官方參考

H2 官方文件說明執行中的資料庫與關閉後檔案的備份方式。 [H2 升級、備份及回復](https://h2database.com/html/tutorial.html#backup)

Flyway migration 的失敗回滾取決於資料庫和 DDL 的交易支援，升級流程需要保留完整回復點。 [Flyway migration 交易處理](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/migration-transaction-handling)
