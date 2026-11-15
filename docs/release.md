# リリース手順

**ふだんは何もしなくてよい。** `main` にマージすると、GitHub Actions が
バージョンを決めてタグを作り、Windows 版と Android 版をビルドして Release を公開する
（`.github/workflows/auto-release.yml` → `.github/workflows/release.yml`）。

## 自動リリースの流れ

1. PR が `main` にマージされる
2. 直前のタグからの変更を見て、次のバージョンを決める
   - `feat:` を含む → minor（`0.2.0` → `0.3.0`）
   - `fix:` などそれ以外 → patch（`0.2.0` → `0.2.1`）
   - `feat!:` / `BREAKING CHANGE:` → major（0.x の間は minor 扱い）
3. `release.yml` がテスト・ビルド・署名検証を行い、Release を公開する
4. タグ（`v0.3.0` など）はそのコミットに自動作成される

コミットメッセージの接頭辞がそのままバージョンの上げ幅になる。迷ったら `feat:` を付ける。

### 自動リリースを止めたいとき

| 方法 | 使いどころ |
| --- | --- |
| コミットメッセージに `[skip release]` を入れる | 一度だけ飛ばしたい |
| `docs/` `licenses/` `.github/` と `*.md` `*.txt` `LICENSE` だけの変更 | 自動で飛ばる（バージョン番号を無駄に増やさない） |
| `Actions → Auto release → Run workflow` で `bump` を指定 | 上げ幅を手で決めて出したい |

`bump` に `patch` / `minor` / `major` を選ぶと、コミットからの判定を上書きできる。
`force` を有効にすると、ドキュメントだけの変更でもリリースする。

### 手元でタグを打つ場合

自動リリースを使わず、従来どおり自分でバージョンを決めることもできる。

```powershell
git tag -a v0.3.0 -m "Instant Mic 0.3.0"
git push origin v0.3.0
```

`v<major>.<minor>.<patch>` 以外のタグ（`v1.2`、`v1.2.3-rc1` など）は
ビルドが失敗する。`versionCode` が壊れるのを防ぐため。

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

## ドラフトで確認したいとき

`Actions → Release → Run workflow` を実行し、`version` に `v0.3.0` などを入れる。

- ドラフト Release が作られる（公開されない）
- 成果物をダウンロードして実機で確認できる
- 問題なければ Releases で「Publish release」を押す

## 手元で確認する場合

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
  成功を `needs` で待つ。失敗したビルドが中途半端な Release を残すことはない
- **リリースは直列化する**: `concurrency: release` により、同じバージョンで
  2つの実行が競合しない
- **バージョンを検証する**: タグ・自動リリースともに `v<major>.<minor>.<patch>` 以外を弾く。
  `versionCode` は `major*10000 + minor*100 + patch` で単調増加（minor/patch は 99 以下）
- **二重リリースを防ぐ**: 同じコミットに既にタグがあれば自動リリースは動かない
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
- 自動リリースが作るタグは `GITHUB_TOKEN` によるものなので、他のワークフローを再起動しない
  （無限ループしない）

## 公開前チェックリスト

- [ ] `git grep` でトークン・鍵・ホスト名・ユーザー名が混入していないか確認した
- [ ] 全履歴を対象に確認した（過去コミットに残ると、後から消しても漏れる）
- [ ] ライセンスを決めた（`LICENSE` を追加）
- [ ] README の導入手順が実際の配布物と一致している
- [ ] 実機（Windows + Android）で Releases のファイルだけを使って導入できた
