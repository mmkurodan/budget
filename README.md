# budget — SQLite 家計簿（OCR 一括登録つき）

SQLite を利用した Android 家計簿アプリ。**日付・カテゴリ・費目・金額**のレコードを
テーブル上で直接編集し、期間指定のカテゴリ別レポートを出せる。加えて、画像や、他アプリ上に
出せる**フロートボタン**の連続取得から画面を **OCR（Tesseract）** で読み取り、
後続処理でレコード化して一括登録する。

## 主な機能と要件対応

| 要件 | 実装 |
| --- | --- |
| 日付/カテゴリ/費目/金額のレコード、直接 追加/編集/削除 | `MainActivity` + `RecordAdapter`。1レコードを **3行（日付＋カテゴリ / 費目 / 金額）** のカードで表示し編集しやすくした。FAB で追加、✕で削除 |
| レポート（期間指定・カテゴリ別収支＋合計） | `ReportActivity` |
| 任意のカテゴリ定義・ブランク許可・変更可・初期値4件 | `CategoriesActivity`。初期値 **固定費・変動費・収入・食品** |
| 画面取得ボタンを分離（直接起動） | メイン画面右下に **フロートON/OFF** と **対象アプリ取得** を並置（＋追加ボタン）。メニューからは分離 |
| 取得対象アプリを個別指定して取り込み | 右下の**対象アプリ取得**ボタン → **単一アプリ／全画面を選択**（Android 14+ の `createConfigForUserChoice`）→ 1枚取得 → OCR → 後続処理 → プレビュー登録 |
| 画像から取り込み（保存済み） | メニュー「画像から取り込み」→ 画像選択 → OCR → 後続処理 |
| フロートボタンを他アプリにオーバーレイ | `FloatingButtonService`（ドラッグ移動可）。右下の**フロートON/OFF**ボタンで表示 |
| フロートは**全画面固定**で取得・**連続取得** | `createConfigForDefaultDisplay` で全画面固定。タップごとに1枚 **OCR まで**実行して蓄積（`ScreenCaptureService` はセッションを維持し都度の同意を回避） |
| アプリに戻ると無効化し、ためた OCR を後続処理へ | `MainActivity#onResume` が `OcrInbox` を回収 → プレビューへ |
| OCR 読み込み DPI 指定（既定 400） | 設定の「OCR 読み込み DPI」→ Tesseract `user_defined_dpi` |
| 取得時にセーフエリアを任意で除外 | 設定のチェックで、取得画像から上下のシステムバー等（`WindowInsets`／バー高さ）を切り落とす |
| OCR 結果を要素別に整えてレコード化（LLM 保留） | `OcrRecordParser` |

### 後続処理（`OcrRecordParser`）のルール
- OCR 結果を空白区切りの**要素ごと**に、**日付・費目・金額を登場順**に認識。
- 日付は `2026-08-01` / `26.08.01`（2桁年）/ `08/01`（年なし）/ `2026年8月1日` などに対応
  （区切りは `-` `/` `.`、全角数字・全角記号も可。2桁年は 2000 年代に補完）。
- 数字でも文字でも **1 文字だけの要素は無視**（OCR ノイズ除け）。
- **「残高」を含む要素と、その直後に来る数字は無視**（残高の誤登録を防ぐ）。
- **金額で 7 桁を越える数字はスキップ**（口座番号などの誤検出除け）。
- **OCR の登場順を維持**し、日付／費目／金額をそれぞれ順に集めて**同じ順番同士で1レコードに対応付け**る
  （列レイアウトで各項目が塊で出ても費目が1つに結合されない／数字が日付より前でも対応）。費目の区切りは
  金額と行末。金額の数だけレコードを作り、日付が足りなければ直近の日付を流用。全体に日付が無ければレコード無し。
- 金額の符号: `-` `△` `▲` や括弧は負、それ以外は表記どおり正。カテゴリは付与しない（空）。
- プレビューでは OCR 結果を**要素別に縦へ並べて**表示する。

> **LLM 連携は一旦保留。** 現在の後続処理はルールベース。将来の再有効化に備えて
> `llm/LlmClient` と `llm/BudgetGrammar`（GBNF 生成）は残しているが、パイプラインからは未使用。

金額は 1 列で収支を表せるよう**符号付き**（支出=負, 収入=正）で保持する。

メイン画面右下には **フロートON/OFF** / **対象アプリ取得** / **追加** の3ボタンを並置。

## 使い方（一括登録）
- **対象アプリを指定して（単発）**: 右下の**対象アプリ取得**ボタン → 全画面か**単一アプリ**を選択（Android 14+）
  → 1枚取得 → 候補を確認・編集 →「登録」。
- **フロートボタンから（全画面・連続）**: 右下の**フロートON/OFF**ボタン →（初回のみ）**画面取得を1回だけ許可**
  （全画面固定。以降タップ時はアプリ切替も選択ダイアログも出ない）→ 銀行/カードアプリを開いてボタンをタップ
  （**画面が変わるたびに連続タップ**して蓄積）→ アプリに戻ると自動でプレビュー →「登録」。
- **画像から（保存済み）**: メニュー →「画像から取り込み」でスクショ/画像を選択。

## 動作に必要な準備
1. **OCR モデル**: 設定の「OCR モデルをダウンロード」で `jpn` / `eng` を取得（`tessdata_fast`,
   初回のみ通信）。`app/src/main/assets/tessdata/*.traineddata` に同梱しても良い。
2. **権限**: フロートボタンは「他アプリの上に表示」（`SYSTEM_ALERT_WINDOW`）と画面キャプチャの
   同意（`MediaProjection`）が必要。Android 13+ では通知権限も要求する。

## アーキテクチャ
```
ui/         MainActivity(3行カードの表) / Report / Categories / Settings / ImportPreview + Adapters
data/       BudgetDbHelper(SQLite) / BudgetRepository(CRUD・集計) / Record / CategorySum
ocr/        OcrModelManager(tessdata 管理) / TesseractOcr(DPI 指定) / OcrRecordParser / OcrInbox
llm/        ImportManager(OCR→後続処理) ／ LlmClient・BudgetGrammar(保留・未使用)
overlay/    FloatingButtonService(オーバーレイ常駐, 連続取得トリガ)
capture/    CaptureActivity(同意・取得範囲切替) / ScreenCaptureService(全画面連続 or 単一アプリ単発＋OCR)
util/       AppExecutors / Prefs / DateUtil
```

- 言語: **Java**。UI は Material3 + RecyclerView。DB は `SQLiteOpenHelper` を直接利用。
- OCR は `tesseract4android`。通信系（保留中の LLM）は `HttpURLConnection` + `org.json`。追加依存は最小。

## ビルド
CI（`.github/workflows/android.yml`）と同じく **Gradle 8.11.1 / AGP 8.9.1 / JDK 17**、
compileSdk 36 / minSdk 24 / targetSdk 36。

```bash
./gradlew assembleDebug                 # デバッグ APK
./gradlew assembleRelease bundleRelease # リリース（署名は app/keystore.jks と環境変数）
./test-before-aapt2.sh app --full --apk-debug
```

> `tesseract4android` は JitPack 配布のため、`settings.gradle` の
> `dependencyResolutionManagement.repositories` に該当グループ限定で `https://jitpack.io` を追加。

## 既知の制限
- 画面キャプチャは MediaProjection のセッション開始時に**1回だけ**同意ダイアログが出る（連続取得中は再表示なし）。
- 「対象アプリを個別指定」は Android 14+ の単一アプリ取得（`createConfigForUserChoice`）を利用。
  単一アプリの描画は端末により全画面へスケール／余白が入ることがある。14 未満は全画面のみ。
- 取得の瞬間はフロートボタンを一時的に隠して写り込みを避ける。
- 後続処理はルールベースのため、明細レイアウトによっては費目/符号の推定を手動修正することがある
  （3行カードで編集しやすくしている）。残高列がある明細では収支の符号を確認のこと。
