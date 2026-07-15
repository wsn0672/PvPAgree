# PvPAgree

PvPAgreeは、**プレイヤー双方の同意があるときだけPvPを許可する**Paperプラグインです。

チャットやGUIからのPvP申請に加えて、相手を見ながら腕を振るジェスチャー操作、個別の永久拒否、双方同意による常時PvP許可、Discord Webhookなどを利用できます。

## 動作環境

- Paper 1.21.11
- Java 21
- サーバー側のPvP設定が有効であること

`server.properties` は次のように設定してください。

```properties
pvp=true
```

## 主な機能

### 双方同意型のPvP

- 同意されていないプレイヤーへの攻撃を無効化
- `/pvp <MCID>` でオンラインプレイヤーへ申請
- チャットのクリックボタンまたはGUIから承諾・拒否
- 申請はデフォルトで30秒後に失効
- 申請中にどちらかが退出した場合は即座にキャンセル
- 承諾後、デフォルトで20秒以内に最初の攻撃がなければ自動キャンセル
- 初撃後は20秒間PvPが有効になり、攻撃が成立するたびに残り時間を更新
- 残り時間をActionBarまたはBossBarに表示可能

### ジェスチャー操作

コマンドを入力しなくても、プレイヤーを見ながらPvP申請へ操作できます。

- メインハンドを空にする
- 相手の体へ視線を合わせる
- しゃがみながら腕を5回振る
- 2回目以降は残り回数をActionBarへ表示
- 送信成功時に「申請を送りました！」と表示
- 申請を受け取った側が同じ操作をすると承諾
- 申請中にしゃがみながら首を縦へ4回振ると承諾
- 申請中にしゃがみながら首を横へ4回振ると拒否
- 壁越しでは検知せず、デフォルトの最大距離は128ブロック
- 申請済みまたは承諾済みの相手には連打操作が反応しない
- 各プレイヤーが設定GUIからジェスチャー操作を無効化可能

回数、受付時間、距離、視線判定の太さ、首振り角度などはすべて`config.yml`で変更できます。

### PvP常時許可リスト

双方が専用申請へ同意すると、その2人は通常のPvP申請なしでいつでも戦えるようになります。

- 常時許可申請は相手がオンラインのときだけ送信可能
- チャットまたはGUIから承諾・拒否
- 双方の同意後に相互リストへ登録
- 死亡や通常の戦闘タイマー経過後も常時許可を維持
- どちらかが削除すると双方の登録を解除
- 解除後は通常の同意制PvPへ戻る
- 常時許可と永久拒否は同時に登録されない
- 申請中にどちらかが退出した場合は即座にキャンセル

### プレイヤーごとの受信設定

未処理の申請がない状態で`/pvp`を実行すると、設定GUIが開きます。

- 申請を自動的に受け入れる
- 申請を表示する
- 申請を常に拒否する
- ジェスチャー操作のON/OFF
- 永久拒否リストの確認・解除
- PvP常時許可リストの申請・確認・解除

### 通知と管理機能

- 申請受信音、承諾音、音量、ピッチを設定可能
- PvP開始時にMinecraft内で全体ブロードキャスト
- PvP開始時にDiscord Webhookへ通知
- 戦闘中ログアウトを相手とDiscordへ通知可能
- 戦闘中ログアウト時に任意のコンソールコマンドを実行可能
- 申請クールダウン、同時申請数、1分あたりの申請数を制限
- ほぼすべてのメッセージ、GUI素材、GUIスロットを設定可能
- プレイヤーデータを遅延・非同期保存

## インストール

1. [Releases](../../releases)から最新のjarファイルを取得します。
2. jarファイルをPaperサーバーの`plugins`フォルダへ入れます。
3. `server.properties`の`pvp`を`true`にします。
4. サーバーを再起動します。
5. 必要に応じて`plugins/PvpAgree/config.yml`を編集します。

設定変更後はサーバーを再起動するか、次のコマンドで再読み込みできます。

```text
/pvp reload
```

## 基本的な使い方

### 通常のPvP申請

申請を送る側が実行します。

```text
/pvp Steve
```

相手には承諾・拒否ボタン付きのメッセージが届きます。相手は`/pvp`を実行してGUIから応答することもできます。

承諾されると初撃待ち状態になります。デフォルトでは20秒以内にどちらかが攻撃するとPvPが開始され、最後に攻撃が成立してから20秒間PvPが継続します。

### PvP常時許可の申請

```text
/pvp allow Steve
```

相手が承諾すると相互常時許可が成立します。成立後は通常申請なしでPvPできます。

一覧をGUIで開く場合は次を実行します。

```text
/pvp allowlist
```

常時許可を解除すると、相手側のリストからも削除されます。

```text
/pvp unallow Steve
```

## コマンド

| コマンド | 説明 |
| --- | --- |
| `/pvp` | 未処理の申請へ応答、または設定GUIを開く |
| `/pvp <MCID>` | 通常のPvP申請を送信する |
| `/pvp cancel <MCID>` | 指定した相手への通常申請を取り消す |
| `/pvp cancelall` | 送信中の通常申請をすべて取り消す |
| `/pvp blocklist` | 永久拒否リストを開く |
| `/pvp allow <MCID>` | PvP常時許可申請を送信する |
| `/pvp allowlist` | PvP常時許可リストを開く |
| `/pvp unallow <MCID>` | 相互常時許可を双方から解除する |
| `/pvp reload` | `config.yml`を再読み込みする |

チャットボタンが内部で使用する承諾・拒否用サブコマンドもありますが、通常は直接入力する必要はありません。

## 権限

| 権限 | デフォルト | 説明 |
| --- | --- | --- |
| `pvpagree.use` | 全員 | PvpAgreeのコマンドとGUIを使用する |
| `pvpagree.reload` | OP | 設定ファイルを再読み込みする |
| `pvpagree.bypasslimits` | OP | 申請クールダウンと件数制限を無視する |

## 設定

設定ファイルは`plugins/PvpAgree/config.yml`に生成されます。

| 設定項目 | 内容 |
| --- | --- |
| `request-timeout-seconds` | 通常申請の有効時間 |
| `allowlist-request-timeout-seconds` | 常時許可申請の有効時間 |
| `agreement-timeout-seconds` | 承諾から初撃までの制限時間 |
| `combat-timeout-seconds` | 最後の攻撃からPvP終了までの時間 |
| `request-limits` | 申請クールダウンと件数制限 |
| `sneak-gesture` | 視線と腕振りによる操作設定 |
| `head-gesture` | 首振りによる承諾・拒否設定 |
| `fight-end` | 死亡、ワールド移動、テレポート時の終了設定 |
| `combat-display` | 残り時間の表示方法 |
| `combat-logout` | 戦闘中ログアウト時の処理 |
| `sounds` | 通知音と承諾音 |
| `discord-webhook` | Discord通知 |
| `minecraft-broadcast` | Minecraft内のPvP開始通知 |
| `messages` | チャットメッセージ |
| `gui` | GUIの文言、素材、スロット |

### Discord Webhook

```yaml
discord-webhook:
  enabled: true
  url: "https://discord.com/api/webhooks/..."
  username: "PvpAgree"
  message: "⚔️ {player1}さんと{player2}さんがPvPを開始しました！"
```

通知は申請承諾時ではなく、最初の攻撃が実際に成立したときに送信されます。

### Minecraft内ブロードキャスト

```yaml
minecraft-broadcast:
  enabled: true
  message: "&8[&cPvP&8] &e{player1}さんと{player2}さんがPvPを開始しました！"
```

### 戦闘時間の表示

`combat-display.mode`には次のいずれかを指定できます。

```yaml
combat-display:
  mode: "ACTION_BAR" # NONE / ACTION_BAR / BOSS_BAR
```

## Configの自動更新

プラグインを更新した際は、jar内の新しいデフォルト設定と既存の`config.yml`を比較します。

- 既存項目の設定値は上書きしません。
- 新しいバージョンで追加された不足項目だけを自動追加します。
- サーバー起動時と`/pvp reload`実行時に補完します。
- 追加した項目数をサーバーコンソールへ表示します。

## 保存データ

プレイヤー設定は`plugins/PvpAgree/data.yml`へ保存されます。

- 申請の受信設定
- ジェスチャー操作のON/OFF
- 永久拒否リスト
- 相互PvP常時許可リスト

変更はまとめて非同期保存されます。

## 他プラグインとの併用

WorldGuardなど、別のプラグインがPvPダメージを無効化している場所では、PvpAgreeで承諾しても攻撃できない場合があります。

## ビルド

```shell
mvn clean package
```

生成物：

```text
target/pvpagree-1.0.0.jar
```
