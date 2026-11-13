# プロトコル仕様（v1）

`schema` は JSON over WebSocket。受信側（Windows / Go）と送信側（Android / Kotlin）で共有する。

- エンドポイント: `wss://<PC>:8765/ws`（同一LANのみ。インターネットへ公開しない）
- 文字コード: UTF-8 の JSON テキストフレーム
- 1メッセージ 64 KiB まで
- 認証は接続直後の最初のフレームのみ。認証前は何も実行しない

## 接続と認証

```
client → server  {"type":"auth","protocol":1,"deviceId":"…","deviceName":"Pixel","token":"…"}
server → client  {"type":"ready","protocol":1,"serverName":"PC","serverId":"…","serverTime":1700000000000,"deviceId":"…","commandPrefix":"コマンド"}
```

- `token` はペアリング時に発行される32バイトのランダム値。**ペアリングコードは接続には使わない**
- 認証失敗時は `{"type":"error","code":"auth_failed"}` の後に切断する
- 以降 25 秒ごとに server が ping、client は pong を返す。75 秒無音で切断

## ペアリング（HTTPS）

### 6桁コードでペアリング（従来）

```
POST https://<PC>:8765/pair
{"code":"123456","deviceId":"…","deviceName":"Pixel"}

200 OK
{"token":"…","serverId":"…","serverName":"PC","commandPrefix":"コマンド"}
```

### QRコードでペアリング

PC側で `p` を押すと、6桁コードと同時に使い捨てのQRペイロードが表示される。

```
instantmic://pair?v=1&h=192.168.1.20&p=8765&f=<証明書SHA-256>&s=<serverId>&n=<PC名>&t=<QRシークレット>&e=<期限ms>

client → server
POST https://<PC>:8765/pair
{"qrSecret":"<QRシークレット>","deviceId":"…","deviceName":"Pixel"}
```

- `f` はTLS証明書の SHA-256。アプリはこの指紋を**ハンドシェイク中に照合**してからコードを送る
- `t` は使い捨てのQRシークレット（16バイト）。長期トークンはQRに含めない
- QRシークレットは6桁コードと同じペアリング枠を消費する。**どちらか一方を使うと両方が失効**する
- 期限切れ・使用済みは `410 Gone`、不一致は `403 Forbidden`
- 既存の6桁コード入力はそのまま利用できる（QRを読めない場合の代替）
- `instantmic://` は Android のアプリリンクとして登録しており、**他社のQRアプリから開いてもペアリング画面に到達できる**。リンクからの場合は自動接続せず、確認後にペアリングする

ペアリング全般の前提:

- ペアリングは PC 側で明示的に開始した時だけ有効（既定10分、1回限り）
- コードは6桁。5回失敗で1分ロック（QRの不一致も同じ枠を消費）
- サーバーは自己署名証明書を使う。アプリは初回に証明書の SHA-256 指紋を記録し、以後はその指紋だけを信頼する（TOFU + pinning）
- PC 画面の指紋を入力すると、ペアリング前に照合する

## 自動検出（mDNS / DNS-SD）

受信アプリは `_instantmic._tcp`（local.）を広告する。TXTレコードは次のとおり。

| キー | 内容 |
| --- | --- |
| `id` | serverId（ペアリング済みPCの識別子） |
| `name` | PC名 |
| `fp` | TLS証明書の SHA-256 |
| `tls` | `1` / `0` |

- 検出情報に秘密は含まない。認証は従来どおりトークンで行うため、**未ペアリング端末が検出できても操作はできない**
- Android は `NsdManager` で解決し、保存済み `serverId` と `fp` が一致したときだけ保存アドレスを更新する
- `serverId` が違うPC、または `fp` が変わったPCは自動接続せず、再ペアリングを促す
- 検出できないネットワークでも、保存済みアドレスで従来どおり接続する
- 無効化: `instant-mic.exe -no-discovery`（設定は `disableDiscovery` として保存）

## 操作メッセージ

すべて `id` を持つ。受信側は直近10分の `id` を記憶し、同じ `id` の再送は実行せずに成功応答を返す（at-most-once）。

### テキスト

```
client → server {"type":"text","id":"op-…","text":"こんにちは"}
server → client {"type":"ack","id":"op-…","ok":true,"applied":"text","elapsedMs":4}
```

- 受信側でもコマンド解釈をやり直す。`コマンド、送信` はテキストとして入力されず Enter になる
- 最大 4000 文字。空文字は拒否

### キー

```
client → server {"type":"key","id":"op-…","key":"ctrl+backspace"}
client → server {"type":"key","id":"op-…","key":"home>shift+end>delete"}
client → server {"type":"key","id":"op-…","key":"backspace","count":3}
```

### 取り消し（言い直し）

```
client → server {"type":"erase","id":"op-…","count":5}
server → client {"type":"ack","id":"op-…","ok":true,"applied":"erase","elapsedMs":3}
```

- 直前に受信側が入力したテキストを `count` 文字ぶん Backspace で消す
- `count` は 1〜4000。範囲外は `bad_erase`
- **受信側が記録した文字数と一致したときだけ実行する**。不一致・記録なしは何も消さず `ok:false`
- 記録は「テキスト入力の成功」で更新し、キー操作の成功で破棄する（カーソル位置を測れないため）
- 取り消し成功後は記録を消すので、同じ入力は二重に消えない
- 音声の「コマンド、言い直し」は `text` メッセージとして届き、受信側が同じ処理に振り分ける

`key` は検証済みのキー列。

- 1ステップは修飾キー + 1キー: `ctrl+z`、`shift+enter`、`alt+f4`、`win+up`
- `>` で最大8ステップの連続操作: `home>shift+end>delete`
- 修飾キーは `ctrl` / `shift` / `alt` / `win`
- 通常キーは `enter` / `tab` / `escape` / `backspace` / `delete` / `insert` / `space` / `up` / `down` / `left` / `right` / `home` / `end` / `pageup` / `pagedown` / `a`〜`z` / `0`〜`9` / `f1`〜`f24`
- `count` は任意で 1〜5。同じキー列を繰り返す（省略時 1）
- 上記以外のキー、2つ以上のキーを含むステップ、修飾キーの重複、`ctrl+alt+delete` は `bad_key` として拒否する
- 受信側は許可されたキーだけを `SendInput` に渡す。Android 側だけの検証には依存しない

### ACK

```
{"type":"ack","id":"op-…","ok":true,"applied":"enter","elapsedMs":2}
{"type":"ack","id":"op-…","ok":true,"applied":"home>shift+end>delete","elapsedMs":3}
{"type":"ack","id":"op-…","ok":false,"error":"入力先のウィンドウがありません"}
{"type":"ack","id":"op-…","ok":true,"duplicate":true}
```

`duplicate:true` は「同じ ID を実行済みなので、今回は実行していない」の意味。
アプリは ACK を受け取るまで成功表示しない。

### エラー

```
{"type":"error","id":"op-…","code":"bad_key","message":"unsupported key"}
```

## コマンド解釈
- 接頭語（既定 `コマンド`）+ 区切り（`、` や空白）+ コマンド語 の**全体一致**のみ実行
- 対応: `送信`/`エンター`→Enter、`改行`→Shift+Enter、`削除`/`バックスペース`→Backspace、`前の単語削除`→Ctrl+Backspace、`行削除`→`home>shift+end>delete`、`デリート`→Delete、`上/下/左/右`（`アップ/ダウン/レフト/ライト`）→矢印、`コピー/貼り付け/切り取り`→Ctrl+C/V/X、`やり直し`→Ctrl+Y、`タブ切り替え`→Ctrl+Tab、`取り消し`/`取消`→Ctrl+Z、`全選択`→Ctrl+A
- `言い直し`/`言直し` は直前のテキスト入力の取り消し（キーではなく `erase` として処理）
- 通常テキストは送信前に句読点整形を通す（`まる`→`。`、`てん`→`、` など）。整形はコマンド判定の後に行う
- 繰り返し: `3回削除` / `削除3回` / `三回削除`（対象は削除・デリート・前の単語削除・矢印。上限5回）
- 誤変換の別名: `開業`→改行、`取消`→取り消し、`バックスペース`→削除 など、実測されたものだけを明示登録する
- 文中に「送信」が含まれても実行しない。`コマンド一覧を教えて` のような語も通常テキスト扱い
- 部分結果（interim）は送信しない。確定結果のみ
- 未知のコマンド語は何も入力せずエラーを返す

## 切断時

- ACK が返らない操作は成功扱いにしない
- 再接続は 1s→2s→4s→8s のバックオフで自動再試行
- 同じ操作 ID で再送するため、ACK 消失時に二重入力しない
- アプリ起動時は保存済みトークンで即座に接続し、同時に mDNS で同じPCを探す。アドレスが変わっていれば検出結果で更新する
