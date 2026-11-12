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

```
POST https://<PC>:8765/pair
{"code":"123456","deviceId":"…","deviceName":"Pixel"}

200 OK
{"token":"…","serverId":"…","serverName":"PC","commandPrefix":"コマンド"}
```

- ペアリングは PC 側で明示的に開始した時だけ有効（既定10分、1回限り）
- コードは6桁。5回失敗で1分ロック
- サーバーは自己署名証明書を使う。アプリは初回に証明書の SHA-256 指紋を記録し、以後はその指紋だけを信頼する（TOFU + pinning）
- PC 画面の指紋を入力すると、ペアリング前に照合する

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
client → server {"type":"key","id":"op-…","key":"enter"}
```

`key` は `enter` / `shift+enter` / `ctrl+z` / `ctrl+a` のみ。

### ACK

```
{"type":"ack","id":"op-…","ok":true,"applied":"enter","elapsedMs":2}
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

- 接頭語（既定 `コマンド`）+ 区切り（`、` や空白）+ コマンド語 の**完全一致**のみ実行
- 対応: `送信`→Enter、`改行`→Shift+Enter、`取り消し`/`取消`→Ctrl+Z、`全選択`→Ctrl+A
- 文中に「送信」が含まれても実行しない。`コマンド一覧を教えて` のような語も通常テキスト扱い
- 部分結果（interim）は送信しない。確定結果のみ
- 未知のコマンド語は何も入力せずエラーを返す

## 切断時

- ACK が返らない操作は成功扱いにしない
- 再接続は 1s→2s→4s→8s のバックオフで自動再試行
- 同じ操作 ID で再送するため、ACK 消失時に二重入力しない
