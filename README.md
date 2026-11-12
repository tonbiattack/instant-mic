# instant-mic

Android スマホを、離れた Windows PC の「音声入力キーボード」にする LAN 専用アプリ。

- スマホ: ワンタップで日本語音声認識 → 確定結果だけを PC へ送信
- PC: 認証済みメッセージだけを受け取り、フォーカス中の入力欄へ Unicode 文字と定型キーを送る
- 音声コマンド: 「コマンド、送信」→ Enter、「コマンド、改行」→ Shift+Enter
- Bluetooth イヤホンは Windows の音声出力のまま使える（仮想マイク不要）

仕様: [docs/spec.md](docs/spec.md) / プロトコル: [docs/protocol.md](docs/protocol.md)

## 構成

```
cmd/instant-mic/        Windows 受信アプリ（Go）
internal/speech/        コマンド解釈（Go 側。Android と同一ルール）
internal/server/        WebSocket・ペアリング・重複防止
internal/input/         SendInput による文字/キー入力（Windows）
internal/config/        ペアリング情報と設定の保存
android/                Android アプリ（Kotlin + Compose）
```

## Windows 受信アプリ

```powershell
go build -o instant-mic.exe ./cmd/instant-mic
.\instant-mic.exe
```

起動すると待ち受け先と証明書の指紋が表示される。

```
instant-mic receiver
  サーバー名 : MyPC
  待ち受け   : [::]:8765
  暗号化     : TLS (自己署名証明書)
  証明書指紋 : 9f13…（64桁）
  接続先候補 :
    wss://192.168.1.20:8765
```

| 操作 | 内容 |
| --- | --- |
| `p` | ペアリング開始（6桁コードを10分間表示） |
| `c` | ペアリング中止 |
| `d` | ペアリング済み端末の一覧 |
| `q` | 終了 |

主なオプション: `-addr :8765`、`-pair`（起動と同時にペアリング開始）、`-devices`、`-unpair <id|name>`、`-command-prefix <語>`、`-no-tls`（デバッグ専用）。

設定は `%AppData%\instant-mic\config.json` に保存される。トークンは SHA-256 ハッシュのみ保存し、平文では残さない。

## Android アプリ

```powershell
cd android
.\gradlew.bat assembleDebug
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

1. PC で受信アプリを起動し `p` を押す
2. スマホのアプリに PC の IP・ポート・6桁コードを入力（指紋は任意）
3. 「タップして話す」を押して発話 → 確定後に PC の入力欄へ反映

### 音声認識のプライバシー

Android 13 以降で端末内認識が利用できる場合はオフライン認識を優先する。利用できない端末では Google などのクラウド認識になり、音声が送信される。アプリは音声を保存しない（録音ファイルも履歴も持たない）。

## 動作の要点

- **確定結果のみ送信**: 認識途中の partial result は破棄するので二重確定しない
- **ACK ベース**: PC からの ACK が返るまで「送信しました」と表示しない
- **重複実行防止**: 各操作に一意な ID を付け、PC 側で実行済み ID を記憶する
- **コマンドの完全一致**: 接頭語 + 区切り + コマンド語のみ実行。文中の「送信」では発火しない。PC 側でも再解釈する
- **フォーカス/権限**: 入力先がない、または管理者権限のウィンドウの場合は安全に失敗し、スマホに理由を返す（UAC 回避はしない）
- **認証**: 未ペアリング端末は WebSocket も `/pair` も通さない。ブラウザからの接続（Origin 付き）は拒否する

## 制限

- 同一 LAN 専用。インターネット越しの接続は対象外
- Android のバックグラウンドでの常時マイク待機は対象外（画面を開いて使う）
- 管理者権限で動作するアプリや一部ゲームには入力できない
- Enter の意味はアプリごとに異なる（送信/改行）。将来キー割り当てをカスタマイズ予定

## 開発

```powershell
go test ./...
go vet ./...
cd android && .\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

`scripts/e2e-target.ps1` は SendInput の手動確認用に、入力内容を記録するテキストボックスを開く。`cmd/e2eclient` はペアリング→送信を CLI で再現する補助ツール。
