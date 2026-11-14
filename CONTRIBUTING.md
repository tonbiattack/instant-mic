# コントリビューション

バグ報告・機能提案・プルリクエストを歓迎します。日本語でも英語でも構いません。

## 報告の前に

- 既存の [Issues](https://github.com/tonbiattack/instant-mic/issues) に同じ内容がないか確認してください
- セキュリティ上の問題は **Issue に書かず**、[SECURITY.md](SECURITY.md) の手順に従ってください

## バグ報告に含めてほしい情報

| 項目 | 例 |
| --- | --- |
| 端末 | Pixel 8 / Android 15 |
| PC | Windows 11 23H2 |
| バージョン | v0.1.0（Releases のタグ、または設定画面） |
| 再現手順 | 1. … 2. … 3. … |
| 期待した動作 | |
| 実際の動作 | |
| ログ | `instant-mic.exe` の出力、`adb logcat -s InstantMicDiag` |

**個人情報は伏せてください。** スクリーンショットには、他のアプリの内容や通知が写り込みます。「入力先:」の欄は前面ウィンドウのタイトルを表示するため、機密のファイル名やページ名が出ることがあります。

## 開発

```powershell
go test ./... && go vet ./...
cd android && .\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

- Go 側の規則（`internal/speech`）と Kotlin 側（`domain/CommandParser.kt`）は**同じ規則を二重に持っています**。片方だけ変えると、PC 側が拒否するようになります。両方と、対応するテストを更新してください
- 整形規則（`internal/speech/shape.go` と `domain/TextShaper.kt`）も同様です
- UI のテストは Robolectric で JVM 上で動きます（実機不要）。危険な状態（確認ダイアログなど）の変更にはテストを添えてください

## プルリクエスト

- 1つの PR は1つの目的に絞ってください
- 挙動が変わる場合は `README.md` と `docs/` の該当箇所も更新してください
- CI（Go / Android）が通ることが条件です

## 行動規範

[CODE_OF_CONDUCT.md](CODE_OF_CONDUCT.md) に従ってください。
