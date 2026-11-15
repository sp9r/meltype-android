# Meltype Android (QWERTY + Glide Typing)

日本語と英語をモード切替なしで自動判別してライブ変換する **Meltype** の考え方をもとに、Android 向けに **QWERTY 配列** と **グライド入力（スワイプ入力）** を実装したオフライン IME です。

## 主な特徴

- **日英シームレス自動判別・ライブ変換**:
  - `kyouhagoogledekensaku` → `今日はgoogleで検索`
  - `ashitanomeetingwotsuika` → `明日のmeetingを追加`
  - `I want to go to the park` → 英語文としてそのまま保持
  - `/command`, `@mention`, `$variable`, `RTX4090` 等のコマンド・型番・URLを自動保持
- **QWERTY + 日英デュアル・グライド入力**:
  - 英単語約 39,000 語＋固有名詞・IT用語と、日本語ローマ字語彙・助詞結合形・`readings.txt` 逆変換語彙の双方を同時にスコアリングし、1ストロークのスワイプで英語も日本語も入力できます。
  - グライド直後に候補バーへ別言語・別単語の代替チップを表示し、1タップで切り替えられます。
- **組み込み OSS Mozc 変換エンジン**:
  - `libmozc.so` (`arm64-v8a` / `x86_64`) と `mozc.data` によるオフライン連文節かな漢字変換と単文節候補展開に対応しています。
- **その他**:
  - 上フリック数字・記号入力（Row 1: `1..0`, Row 2〜3: 主要記号）
  - `?123` / `=\<` 記号レイヤー
  - スペースバー左右スライドによる文節/カーソル移動
  - 片手モード（全幅 / 左寄せ / 右寄せ）、ライト / ダーク / システム連動テーマ、ユーザー辞書

## ビルド方法

大容量バイナリ（`libmozc.so`、`mozc.data`）はリポジトリに直接含めておらず、`tools/fetch_mozc_assets.py` が `AoneSenbongi/Meltype-Android` (`android-1.3.0`) から SHA-256 検証付きで展開します。

```powershell
# 初回アセット取得 + 単体テスト + Debug APK ビルド
.\build.ps1
```

または個別に実行できます:

```powershell
python tools/fetch_mozc_assets.py
.\gradlew.bat testDebugUnitTest --no-daemon
.\gradlew.bat assembleDebug --no-daemon
```

## ライセンスと謝辞

- 本プロジェクトは **GPL-3.0** (`LICENSE`) のもとで提供されます。
- 上流の Meltype (`yksr-melt/Meltype`) 辞書データおよび `AoneSenbongi/Meltype-Android` (`android-1.3.0`) の OSS Mozc Android ビルド成果物を利用しています。詳細は `THIRD_PARTY_NOTICES.txt` を参照してください。
