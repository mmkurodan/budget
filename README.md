# budget — SQLite 家計簿（OCR + LLM 一括登録つき）

SQLite を利用した Android 家計簿アプリ。**日付・カテゴリ・費目・金額**のレコードを
テーブル上で直接編集し、期間指定のカテゴリ別レポートを出せる。加えて、他アプリ上に
出せる**フロートボタン**から画面を取り込み、**OCR（Tesseract）→ LLM（GBNF 拘束）**で
銀行口座やクレジットカードの明細をレコード化して一括登録する。

## 主な機能と要件対応

| 要件 | 実装 |
| --- | --- |
| 日付/カテゴリ/費目/金額のテーブル、直接 追加/編集/削除 | `MainActivity` + `RecordAdapter`（行内で 日付ピッカー・カテゴリ選択・費目・金額を編集、FAB で追加、✕ で削除） |
| レポート（期間指定・カテゴリ別収支＋合計） | `ReportActivity`（from/to 指定、カテゴリ別収支バー、収入/支出/収支の合計） |
| 任意のカテゴリ定義・ブランク許可・変更可 | `CategoriesActivity`（追加/改名/削除）。初期値 **固定費・変動費・収入・食品**。レコードのカテゴリは空欄可・変更可 |
| OCR API + LLM API による一括登録 | `ImportManager`（画像→OCR→LLM→候補）＋ `ImportPreviewActivity`（確認・編集して登録） |
| フロートボタンを他アプリにオーバーレイ | `FloatingButtonService`（`TYPE_APPLICATION_OVERLAY`, ドラッグ移動可） |
| ボタン押下で画面スクショ取り込み | `CaptureActivity`（MediaProjection 同意）＋ `ScreenCaptureService`（1 フレーム取得） |
| Tesseract で画面上の履歴を OCR | `TesseractOcr` + `OcrModelManager`（`jpn+eng`、端末内 DL / assets 同梱対応） |
| LLM に SQLite レコード形式へ整形指示 | `ImportManager#buildPrompt`（支出=負・収入=正、カテゴリは既知集合＋空文字） |
| **GBNF で形式を徹底** | `BudgetGrammar`（レコード配列の JSON を厳密拘束。カテゴリは既知集合＋空文字のみ許可） |
| JSON に基づき SQLite へ登録 | `ImportManager#parseRecords` → `BudgetRepository#insertRecord` |

金額は 1 列で収支を表せるよう**符号付き**（支出=負, 収入=正）で保持する。

## 動作に必要な準備

1. **LLM サーバ**: 既定は端末内の llama サーバ（`/root/llama` アプリ, `http://127.0.0.1:11434`,
   Ollama 互換）。設定画面で URL / API 種別（Ollama・OpenAI 互換）/ モデル名 / API キーを変更でき、
   「接続確認」「モデル一覧」で確認できる。GBNF は `/api/chat` の `grammar` フィールドで渡す。
2. **OCR モデル**: 設定画面の「OCR モデルをダウンロード」で `jpn` / `eng` を取得（`tessdata_fast`,
   初回のみ通信）。`app/src/main/assets/tessdata/*.traineddata` に同梱しても良い。
3. **権限**: フロートボタンは「他アプリの上に表示」（`SYSTEM_ALERT_WINDOW`）と画面キャプチャの
   同意（`MediaProjection`）が必要。Android 13+ では通知権限も要求する。

## 使い方（一括登録）

- **画像から**: メニュー →「画像から一括登録」→ 画像を選択 → 候補を確認・編集 →「登録」。
- **フロートボタンから**: メニュー →「フロートボタン ON/OFF」で表示 → 銀行/カードアプリを開いて
  ボタンをタップ → 画面キャプチャ → 候補プレビュー → 登録。

## アーキテクチャ

```
ui/         MainActivity(表, 直接編集) / Report / Categories / Settings / ImportPreview + Adapters
data/       BudgetDbHelper(SQLite) / BudgetRepository(CRUD・集計) / Record / CategorySum
ocr/        OcrModelManager(tessdata 管理) / TesseractOcr
llm/        LlmClient(Ollama・OpenAI 互換 HTTP) / BudgetGrammar(GBNF) / ImportManager(パイプライン)
overlay/    FloatingButtonService(オーバーレイ常駐)
capture/    CaptureActivity(同意) / ScreenCaptureService(1 フレーム取得)
util/       AppExecutors / Prefs / DateUtil
```

- 言語: **Java**（スキャフォールドに合わせる）。UI は Material3 + RecyclerView。
- DB は `SQLiteOpenHelper` を直接利用（追加依存なし）。
- LLM/OCR 通信は `HttpURLConnection` + `org.json`（追加依存なし）。OCR は `tesseract4android`。

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

- 画面キャプチャは Android のセッションごとに毎回同意ダイアログが出る（OS 仕様）。
- フロートボタン自体もスクリーンショットに写り得る（小さいため OCR には実害小）。
- OCR は横書き明細を想定。LLM 出力は GBNF で形式を保証するが、値の正しさは明細内容に依存する。
