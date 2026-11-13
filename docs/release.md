# リリース手順

`v*` タグを push すると、GitHub Actions が Windows 版と Android 版をビルドし、
両方成功したときだけ Release を公開する（`.github/workflows/release.yml`）。

## 初回だけ必要な準備

### 1. 署名鍵を作る

```powershell
.\scripts\setup-signing.ps1
```

鍵は `%USERPROFILE%\.instant-mic-signing\release.jks` に作られる。**リポジトリには置かない**
（`.gitignore` で `*.jks` を除外済み）。

> この鍵を失うと、既存ユーザーへ更新を配布できなくなる。Android は署名が変わると
> 上書きインストールを拒否するため。安全な場所にバックアップする。

### 2. GitHub Secrets を登録する

`Settings → Secrets and variables → Actions → New repository secret`：

| Secret | 値 |
| --- | --- |
| `INSTANT_MIC_KEYSTORE_BASE64` | `setup-signing.ps1` が表示する Base64 文字列 |
| `INSTANT_MIC_KEYSTORE_PASSWORD` | キーストアのパスワード |
| `INSTANT_MIC_KEY_ALIAS` | `instant-mic`（スクリプトの既定値） |
| `INSTANT_MIC_KEY_PASSWORD` | 鍵のパスワード |

## リリースの流れ

### 1. まずドラフトで確認する

`Actions → Release → Run workflow` を実行する。`version` に `v0.1.0` などを入れる。

- ドラフト Release が作られる（公開されない）
- 成果物をダウンロードして実機で確認する

### 2. 確認できたらタグで公開する

```powershell
git tag v0.1.0
git push origin v0.1.0
```

- タグの名前がそのままバージョンになる
- `versionCode` はタグから自動計算される（`v1.2.3` → `10203`）

### 3. 手元で確認する場合

```powershell
# Windows
go build -o dist/instant-mic.exe ./cmd/instant-mic

# Android（署名なし。実機には入れられない）
cd android && .\gradlew.bat assembleRelease
```

署名済み APK をローカルで作るときは、環境変数を設定してから `assembleRelease` を実行する。

```powershell
$env:INSTANT_MIC_KEYSTORE = "$env:USERPROFILE\.instant-mic-signing\release.jks"
$env:INSTANT_MIC_KEYSTORE_PASSWORD = "..."
$env:INSTANT_MIC_KEY_ALIAS = "instant-mic"
$env:INSTANT_MIC_KEY_PASSWORD = "..."
cd android && .\gradlew.bat assembleRelease
```

## 事故を防ぐ仕組み

- **ビルドが1つでも失敗したら公開しない**: `release` ジョブは Windows と Android の両方の
  成功を `needs` で待つ
- **未署名 APK を弾く**: ファイル名に `unsigned` が含まれていたら失敗させる。
  未署名を配ると、後から更新できない端末が生まれる
- **署名を検証する**: `apksigner verify` を通してから成果物にする
- **チェックサムを添える**: 各成果物に `.sha256` を付け、Release 本文にも載せる
- **ローカルのパスを焼き込まない**: `go build -trimpath -ldflags "-s -w"`

## 秘密情報の扱い

- 署名鍵・パスワードは **Actions Secrets のみ**。ワークフローは環境変数で受け取り、
  ファイルへは `$RUNNER_TEMP`（ジョブ終了で破棄）にだけ書く
- ビルドログに値を出力しない（`ls` で存在確認するだけ）
- リポジトリに `*.jks` / `*.keystore` を置かない（`.gitignore` 済み）

## 公開前チェックリスト

- [ ] `git grep` でトークン・鍵・ホスト名・ユーザー名が混入していないか確認した
- [ ] 全履歴を対象に確認した（過去コミットに残ると、後から消しても漏れる）
- [ ] ライセンスを決めた（`LICENSE` を追加）
- [ ] README の導入手順が実際の配布物と一致している
- [ ] 実機（Windows + Android）で Releases のファイルだけを使って導入できた
