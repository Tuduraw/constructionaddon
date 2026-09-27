# Construction Machinery Addon(重機アドオン)

Tudur's Vehicle Mod(`tudursvehiclemod`:https://github.com/Tuduraw/tudursvehiclemod)のアドオンMODです。

> このMODのコードおよびドキュメントの生成にはClaude(Anthropic)を使用しています。

実際に**作業ができる**重機・工事車両を追加します。見た目の機械を増やすだけでなく、掘削・吊り上げ・整地・杭打ち・運搬・積み込み/積み下ろし・コンクリート打設を、ワールドのブロックやエンティティに対して実際に行います。

前提MODのクラスへのmixinは使っていません。前提MODのアドオン向けAPI(`registerAddonVehicleType`・`VehicleConverterTarget`・`TieredVehicleSpawnerItem`・`tudursvehiclemod$getCustomPartTransforms()`・`tudursvehiclemod$getBodyFrameOffset()`・runway)と、オーバーライド可能なメソッドで実装しています。例外として、旋回する運転席の乗員の体の向きを描画するため、バニラのクラスにクライアント専用のmixinを2つ使っています。

---

## 1. 収録車両

| Tier | 車両 | 機能(`construction.machine`) |
|---|---|---|
| 1 | ミニバックホウ | `excavator`(同じモデルを`scale: 0.6`で使用) |
| 2 | バックホウ | `excavator` |
| 2 | ブルドーザー | `bulldozer` |
| 2 | ダンプカー | `dump_truck` |
| 3 | ラフタークレーン | `crane` |
| 3 | 杭打ち機(モンケン/打撃式) | `pile_driver`(`mode: hammer`) |
| 3 | ミキサー車 | `mixer_truck` |
| 3 | トラクターヘッド | `tractor` |
| 3 | 低床トレーラー | `trailer` |
| 4 | 杭打ち機(回転圧入式) | `pile_driver`(`mode: rotary`) |
| 5 | 大型杭打ち機(回転圧入式) | `pile_driver`(`mode: rotary`、クローラー拡幅・リーダー中折れ) |

全車両は**1つのエンティティ型**(`constructionaddon:construction_machine`、`CarEntity`を継承)です。車両JSONの`construction.machine`で機能モジュールを選ぶ構成のため、スポーンアイテムは「重機」カテゴリ×ティア1〜5の5個だけで、変換ブロックにも1ページとして追加されます。アドオンパック側でも、JSONとOBJモデルだけで新しい重機を作れます。

同梱のモデルは箱形状による仮モデルです(`tools/generate_models.py`で生成)。部品名(OBJグループ名)と回転中心を合わせれば、本格的なモデルへそのまま差し替えられます。

---

## 2. 操作

**WASDは常に走行専用です。** 重機の操作キーがWASDを奪うことはなく、走行しながら同時に機械を操作できます。視点・Kメニュー・Hキー等は前提MODのままです。

本アドオンの操作キーは、**操作の種類ごとに独立したキーバインド**になっています。既定値が同じキー(例:バックホウのブームとクレーンの起伏はどちらも↑↓)でも、操作設定画面で片方だけを変更できます。前提MODの「高度上昇/降下」(↑↓)とも別のキーバインドです。同じキーを共有するキーバインドは操作設定画面で赤く表示されますが、それぞれ対象の機体でしか働かないため問題ありません。

| 操作 | 既定キー | 使用する機体 |
|---|---|---|
| 旋回 左/右 | ← / → | バックホウ・クレーン・杭打ち機(上部旋回)、ミキサー車(シュート) |
| 汎用 上/下 | ↑ / ↓ | ブルドーザー(排土板)、杭打ち機(目標深度)、ミキサー車(シュート上下) |
| ブーム 上げ/下げ | ↑ / ↓ | バックホウ |
| アーム 内側/外側 | , / . | バックホウ |
| 起伏 上げ/下げ | ↑ / ↓ | クレーン |
| 伸縮 伸長/縮小 | , / . | クレーン |
| 巻上げ/巻下げ | ; / / | クレーン |
| 作業操作1 | Z | 各機体の主操作 |
| 作業操作2 | X | 各機体の副操作 |

Xはバイクアドオンのウィリーと既定キーが重なりますが、重機の運転席にいる間だけ作動するため実害はありません。

### 作業モード(M長押し)

前提MODのマニュアルモード(M長押しで切替)を、機体の作業系統の**起動レバー**として流用しています。**このモード自体はWASDに一切影響しません。** クレーン・杭打ち機ではアウトリガー(ジャッキ)の展開・格納を操作し、実際に展開している間だけ走行できなくなります(モードの切替そのものではなく、その物理的な状態が理由です)。バックホウ・ミキサー車では、単に作業軸を有効にするスイッチとして働きます。

### 機体ごとの操作

| 機体 | 作業モード | 操作 |
|---|---|---|
| バックホウ | 必要(走行への影響なし) | ←→ 旋回 / ↑↓ ブーム / **,・.** アーム(同時操作可) / **Z** 掘削(バケットを巻き込む) / **X** バケットを開く(一定角度を超えると放出) |
| クレーン | 必要(アウトリガー展開後に操作可。展開中は走行不可) | ←→ 旋回 / ↑↓ 起伏 / **,・.** 伸縮 / **;・/** 巻上げ・巻下げ(すべて同時操作可) / **Z** 吊る・離す |
| ブルドーザー | 不要 | ↑↓ 排土板の高さ / 前進で自動的に切土・盛土 / **Z** 排土板の土を前方に排出 |
| 杭打ち機 | アウトリガー展開に使用(展開中は走行不可) | **X** リーダー起立・格納(起立状態でも走行可) / ←→ 旋回 / ↑↓ 目標深度 / **Z** 杭打ち(リーダー起立+アウトリガー展開後) |
| ダンプカー | 不要 | **Z** 荷台を上げる(一定角度で排出) / **X** 荷台を下げる |
| ミキサー車 | 必要(走行への影響なし) | ←→ シュート旋回 / ↑↓ シュートの上下 / **Z** 打設 |
| トラクター | 不要 | **Z** トレーラーの連結・切り離し / **H** 連結中のトレーラーの道板開閉 |

機体の状態(積載量・深度・打撃数・回転数・貫入停止など)は、運転者のアクションバーに表示されます。キー名を含む案内(バケット満杯・排土板満杯・トレーラー連結中・リーダー格納中)は、**実際に割り当てられているキー**を表示します。

作業による旋回(足回りは回らず、上部旋回体だけが回る動作)の際に、機体ごとに警報音を鳴らせます(`swing_sound`、後述)。同梱の車両では、クレーンと杭打ち機に設定しています。

---

## 3. 各機能の詳細

### バックホウ(`excavator`)

- **操作**:↑↓でブーム、「,」「.」でアームをそれぞれ独立して動かします。両方を同時に操作できます。
- **掘削**:Zでバケットを巻き込んでいる間、刃先(`bucket_tip`)付近のブロックを掘って積みます。硬い地盤ほど時間がかかり(後述の共通処理)、硬さの上限以上は掘れません。草花・雪などは積まずに取り除きます。液体は掘りません。
- **放出**:Xでバケットを開き`dump_angle`より開くと、1ブロックずつ放出します。刃先がダンプカーの荷台の上にあれば荷台(インベントリ)へ直接積み込み、そうでなければ周囲に広がりながら山なりに積み上がります(後述「ブロックの放出先」)。
- 運転席は上部旋回体と一緒に回り、視点も追従します(`seat_parts`)。

### クレーン(`crane`)

- 作業モードでアウトリガーが張り出し、張り出し完了後に操作できます。アウトリガーが出ている間は走行できません。
- 旋回・起伏・伸縮・巻上げは、すべて同時に操作できます。
- Zでフック付近の吊れるもの(モブ・ドロップアイテム・他の乗り物)を吊り、もう一度Zで離します。プレイヤー・乗り物を吊れるかどうかはサーバー設定で切り替えます(既定はプレイヤー不可・乗り物可)。プレイヤーが運転中の乗り物は吊れません。

### ブルドーザー(`bulldozer`)

- 排土板の下端が**計画高**です。0で履帯の接地面と同じ高さ、マイナスにすると地面を削り込みながら1層ずつ下がっていきます。
- 前進中、排土板の前にある計画高以上のブロックを削って排土板に溜め、計画高のすぐ下の穴を溜めた土で埋めます。凸を削り凹を埋めるので、地面が平らに仕上がります。
- 硬い地盤ほど機体が減速し、排土板が満杯に近いほど最高速度が下がります。硬さの上限以上のブロックは削れず、通常の壁と同様に止まります。

### 杭打ち機(`pile_driver`)

インベントリ(Kメニュー)の最初のブロックアイテムを杭材として、リーダーの真下に1列ずつ地中へ打ち込みます。押しのけた土はドロップしません。クリエイティブでは、杭材がなくても`creative_pile_block`で打てます。リーダーの真下の位置が変わる(移動・旋回)と、新しい杭として扱います。

- **リーダー**:Xで起立・格納します。起立させたままでも走行できます。
- **ジャッキ(アウトリガー)**:作業モードで四隅のジャッキを展開します。展開中は走行できません。
- **杭打ち**:リーダー起立とジャッキ展開の両方がそろうと、Zで打てます(`require_outriggers: false`でジャッキ不要)。
- **作業旋回**:←→で上部旋回体をリーダー・運転席ごと旋回します。
- **打ち込み方式**
  - **モンケン(打撃式、`mode: hammer`)**:1ブロックに必要な打撃数は`blows_per_block × 抵抗係数`です。`max_blows_per_block`を超えるブロックで**貫入停止**します。
  - **回転圧入式(`mode: rotary`)**:1ブロックの所要時間は`ticks_per_block × 抵抗係数`、回転数は`rpm ÷ 抵抗係数`で、硬いほど遅く回転も落ちます。`min_rpm`を下回るブロックで**貫入停止**します。
- **大型杭打ち機**(ティア5)
  - **クローラー拡幅**(`crawler_extension: true`):リーダー起立の前にクローラーが左右へ広がり、格納時はリーダーを倒してから戻ります(見た目のみ)。転輪はクローラーと一緒に動き、走行に合わせて回ります。
  - **リーダーの中折れ**:下部は固定で、途中から上部だけが後方へ折りたたまれます。オーガは固定側の最下部にあり、折りたたみの影響を受けません。
  - 小型機との違いはJSONの関節定義だけです。

### トラクター(`tractor`)・トレーラー(`trailer`)

- **積載**:前提MODのrunway機能をそのまま使っています。トレーラーの荷台と、道板(`hatch_gated`のrunway)はトレーラーのJSONに定義しています。荷台に載った重機は、前提MODの甲板輸送処理で一緒に運ばれます。
- **牽引**:トラクターの第五輪(`hitch_*`)の近くにトレーラーのキングピン(`kingpin_*`)を寄せ、Zで連結します。
  - 連結中のトレーラーは、キングピンが第五輪に乗り、後軸(`axle_z`)が後ろへ引きずられる連結車両の幾何で毎tick位置が決まります。カーブで内輪差が出て、バックでは振れ回ります。
  - `max_articulation`で折れ曲がりの限界(ジャックナイフ防止)を設けています。
- 連結中にトラクターでHキーを押すと、トレーラーの道板(`$hatch`系パーツと`hatch_gated`のrunway)が開閉します。
- トレーラーには座席がありません。回収(スニーク+右クリック)は前提MODの通常どおりです。

### ダンプカー(`dump_truck`)

- 荷台は前提MODの車両インベントリそのものです。バックホウからの積み込みも、Kメニューから手で入れたものも同じ積荷として扱います。
- 荷台を`dump_start_angle`以上に上げると、後部(`discharge`)から周囲に広がりながら山なりに積み上がるように排出します(後述「ブロックの放出先」)。荷台が急なほど排出が速くなります。ゆっくり走りながら排出すると、帯状に敷き均せます。
- ブロックアイテム以外は排出されず、荷台に残ります。
- 荷台内の土の山は積載量に応じて表示されます。

### ミキサー車(`mixer_truck`)

- **練り混ぜ**:インベントリに原料と水を入れると、ドラムが`recipes`に従って生コンにします。
  - 既定のレシピは、各色のコンクリートパウダー+水 → 同じ色のコンクリートです。
  - レシピはデータで定義しているので、コンクリート以外の材料を敷設させることもできます。
  - ドラムに入る材料は1種類ずつです。
- **水**:インベントリの水入りバケツはタンクに移され、空のバケツが残ります。機体が水に浸かっている間も給水されます。
- **打設**:Zでシュートの出口から流し込みます。材料は液体のように、`spread_radius`の範囲で低い空きへ流れ込み、壁(型枠)で止まります。低い所から1層ずつ埋まるので、上面が平らに仕上がります。

---

## 4. 硬さに応じた処理(共通)

地盤に道具を押し込む処理は、すべて`work.GroundResistance`を共通で使います。そのため、杭・バケット・排土板の間で「硬い」の基準が揃います。各機体の`resistance`オブジェクトで調整します。

```json
"resistance": {
  "hardness_scale": 1.0,
  "refusal_hardness": 20.0,
  "overrides": { "minecraft:clay": 2.0, "minecraft:gravel": 0.3 }
}
```

| 項目 | 内容 |
|---|---|
| `hardness_scale` | 抵抗係数 = 1 + 硬さ × この値。土(0.5)は1.5倍、石(1.5)は2.5倍、深層岩(3.0)は4倍の手間 |
| `refusal_hardness` | この硬さ以上は貫入・掘削不能(貫入停止) |
| `overrides` | ブロックごとの硬さの上書き |

破壊不能ブロック(岩盤など)と、中身を持つブロック(チェスト等)は常に貫入停止です。空気・液体・草花などは抵抗なしで押しのけます。

| 機体 | 抵抗係数の使われ方 | 固有の停止条件 |
|---|---|---|
| モンケン | 必要打撃数 = `blows_per_block × 係数` | 必要打撃数 > `max_blows_per_block` |
| 回転圧入 | 所要時間 = `ticks_per_block × 係数`、回転数 = `rpm ÷ 係数` | 回転数 < `min_rpm` |
| バックホウ | 1ブロックの掘削時間 = `base_dig_ticks × 係数` | ― |
| ブルドーザー | 機体の減速量 = `cut_drag × 係数` | ― |

### ブロックの放出先(山なりに積み上がる仕組み)

バックホウの放出・ブルドーザーの排出・ダンプカーの排出は、共通の探索(`work.PourSpreader`)で落とす場所を決めます。放出点から水平方向にも探し、「高さ + 水平距離 × `dump_slope`」が最小のマスへ落とすため、周囲へ広がりながら山なりに積み上がります(`dump_slope`が大きいほど急な山)。置き場所が見つからない場合だけアイテムとしてドロップします。ミキサー車の打設も同じ探索で、傾斜0(液体)として動作します。

各機体の設定項目:

| 機体 | 半径 | 傾斜 |
|---|---|---|
| バックホウ | `excavator.dump_spread_radius`(既定4) | `excavator.dump_slope`(既定0.75) |
| ブルドーザー | `bulldozer.dump_spread_radius`(既定3) | `bulldozer.dump_slope`(既定0.75) |
| ダンプカー | `dump_truck.dump_spread_radius`(既定4) | `dump_truck.dump_slope`(既定0.75) |

---

## 5. 車両JSONの`construction`オブジェクト

前提MODの`VehicleDefinition`は知らないキーを無視するため、1台の車両を1つのJSONで記述したまま共存できます。

```json
"construction": {
  "machine": "excavator",
  "joints": [ ... ],
  "work_points": { "bucket_tip": { "part": "$bucket", "x": -0.2, "y": 0.6, "z": 4.95 } },
  "seat_parts": [ { "seat": 0, "part": "$upper" } ],
  "swing_sound": { "sound": "minecraft:block.note_block.bit", "volume": 0.6, "pitch": 1.8, "interval": 8 },
  "excavator": { ...機体固有の設定(すべて省略可)... }
}
```

### `joints`(可動部)

OBJグループを、機能モジュールが同期する名前付きの**チャンネル**の値で動かします。座標はモデル座標系(+Zが前方、**+Xが左**、+Yが上、`scale`適用前)です。

| 項目 | 既定値 | 内容 |
|---|---|---|
| `part` | (必須) | OBJグループ名 |
| `parent` | なし | 親の関節。親の変換の上に自分の変換を重ねる(深さ制限なし) |
| `pivot_x/y/z` | 0 | 回転・拡縮の中心 |
| `axis_x/y/z` | 1/0/0 | 回転軸・移動方向・拡縮方向 |
| `mode` | `rotate` | `rotate`(角度)/`slide`(移動量)/`scale`(倍率)/`spin`(毎tick加算する連続回転) |
| `channel` | なし | 駆動するチャンネル名 |
| `factor` | 1 | 値 = `offset + factor × チャンネル値` |
| `offset` | 0(`scale`は1) | 同上 |
| `min` / `max` | 無制限 | 値の範囲(`spin`は対象外) |
| `inherit_rotation` | `true` | `false`にすると親の回転を継承せず、位置の変化だけを受け継ぐ(クレーンのワイヤーやフックのように真下に垂れる部品向け) |

**機能ごとのチャンネル名**

| 機能 | チャンネル |
|---|---|
| `excavator` | `swing` `boom` `arm` `bucket` `load` |
| `crane` | `swing` `luff` `extend` `rope` `outrigger` `hook` |
| `bulldozer` | `blade` `load` |
| `pile_driver` | `mast`(0〜1) `hammer` `rpm` `feed`(0〜1) `swing` `outrigger`(0〜1) `track_width`(0〜1) `travel`(走行速度、ブロック/tick) |
| `dump_truck` | `bed` `load` |
| `mixer_truck` | `drum`(度/tick) `chute_swing` `chute_tilt` `load` |
| `tractor` | `coupled` |

`load`は0〜1の積載率です。`mode: scale`、`offset: 0`と組み合わせると、積荷の盛り上がりを表現できます。

角度の向きについて:同梱モデルのバックホウ・クレーンのブーム系は`axis_x: -1`としているため、正の値で持ち上がります。

チャンネルを指定しない関節は動かない固定関節になります。大型杭打ち機のリーダー下部のように、「自分は動かないが、子の部品(折れるリーダー上部・オーガ)をまとめてぶら下げる親」として使えます。

転輪のような回る部品は、`travel`チャンネルと`spin`モードを組み合わせ、`factor`を`360 ÷ (2π × 半径)`にすると、走行距離に合った回転になります。拡幅するクローラーの子にすれば、クローラーと一緒に移動します(前提MODの`wheel_parts`・`crawler_tracks`はJSONで固定された位置に描画されるため、拡幅とは組み合わせられません)。

### `work_points`(作業点)

機体が実際に作用する点です(刃先・ブーム先端・シュート出口・杭の中心など)。`part`を指定すると、その関節の親子連結ごと動きます。描画とサーバー処理で同じ行列を使うため、見た目と作用位置がずれません。

| 機能 | 使う作業点(名前は設定で変更可) |
|---|---|
| `excavator` | `bucket_tip` |
| `crane` | `boom_tip` |
| `pile_driver` | `pile_point` |
| `dump_truck` | `discharge` |
| `mixer_truck` | `chute_tip` |

### `seat_parts`

指定した座席を関節に乗せます(旋回するキャブ)。乗員の位置・視点・向きが関節に追従します。

### `swing_sound`(作業旋回時の警報音、省略可)

作業による旋回(上部旋回体だけが回る動作。車両自体の旋回は含みません)の間、指定した音を一定間隔で鳴らします。**省略すると何も鳴りません。**

| 項目 | 既定値 | 内容 |
|---|---|---|
| `sound` | (必須) | サウンドイベントID。バニラ・他MOD・リソースパックの`sounds.json`で定義した音のいずれも指定可 |
| `volume` / `pitch` | 1.0 / 1.0 | 音量・音程 |
| `interval` | 10 | 旋回が続く間の再生間隔(tick)。旋回を始めた瞬間に1回目が鳴ります |

### 機体固有の設定

各項目は`tools/generate_models.py`が出力するサンプルJSONに、既定値とともにすべて記載しています。主なものは以下です。

- `excavator`
  - `tip_point`
  - 速度:`swing_speed` `swing_limit`(0で無制限) `boom_speed` `arm_speed`(ブーム・アームは各専用キーで独立操作)
  - 可動範囲:`boom_min/max` `arm_min/max` `bucket_min/max` `curl_speed`
  - 作業:`capacity` `dig_radius` `base_dig_ticks` `dump_angle` `dump_interval` `dump_spread_radius` `dump_slope` `resistance`
- `crane`
  - 旋回・起伏・伸縮:`tip_point` `swing_speed` `swing_limit` `luff_speed` `luff_min/max` `extend_speed` `extend_max`
  - ワイヤー:`rope_speed` `rope_min/max`
  - アウトリガー:`outrigger_speed` `require_outriggers`
  - 吊り上げ:`grab_radius` `max_lift_width` `hook_drop`
- `bulldozer`
  - 排土板の形状:`blade_front_z` `blade_width` `blade_bottom_y` `blade_height`
  - 排土板の上下:`blade_min/max` `blade_speed`
  - 作業:`capacity` `max_cuts_per_tick` `full_load_speed` `cut_drag` `unload_interval` `min_work_speed` `dump_spread_radius` `dump_slope` `resistance`
- `pile_driver`
  - 共通:`mode` `pile_point` `max_depth` `default_depth` `mast_speed` `creative_pile_block` `surface_search`
  - 旋回・設置:`swing_speed` `swing_limit`(0で無制限) `outrigger_speed` `require_outriggers` `crawler_extension`(クローラー拡幅の有無) `track_width_speed`
  - 打撃式:`blows_per_block` `max_blows_per_block` `lift_ticks` `drop_ticks` `lift_height`
  - 回転圧入式:`ticks_per_block` `rpm` `min_rpm` `rpm_response`
  - `resistance`
- `dump_truck`
  - 荷台の受け入れ範囲:`bed_min_x/y/z` `bed_max_x/y/z`(バックホウの刃先がこの箱の中なら積み込み)
  - 荷台の動作:`bed_speed` `bed_max_angle` `dump_start_angle` `dump_interval_slow/fast`
  - その他:`capacity`(0でインベントリ容量から算出) `discharge_point` `dump_spread_radius` `dump_slope`
- `mixer_truck`
  - シュート:`chute_point` `chute_swing_speed` `chute_swing_limit` `chute_tilt_min/max` `chute_tilt_speed`
  - 打設:`pour_interval` `spread_radius`
  - ドラム・水:`capacity` `water_capacity` `water_per_bucket` `mix_interval` `drum_mix_speed` `drum_pour_speed` `drum_idle_speed`
  - `recipes`(`[{ "input": "アイテムID", "output": "ブロックID", "water": true }]`)
- `tractor`:`hitch_x/y/z` `couple_radius` `towing_speed_factor`
- `trailer`:`kingpin_x/y/z` `axle_z` `max_articulation`

---

## 6. サーバー設定(`config/constructionaddon-server.json`)

| 項目 | 既定値 | 内容 |
|---|---|---|
| `allowTerrainEditing` | `true` | ブロックを壊す・置く機能全体の主スイッチ。`false`にすると、走行・吊り上げ・牽引はできるが地形は変えない |
| `allowUnmannedTerrainEditing` | `false` | 権限を確認する操作者がいない状態での地形変更の許可(現状、全機能とも運転者の操作が前提) |
| `craneCanLiftPlayers` | `false` | クレーンでプレイヤーを吊れるか |
| `craneCanLiftVehicles` | `true` | クレーンで乗り物を吊れるか |
| `showStatusMessages` | `true` | 運転者のアクションバーに状態を表示するか |

ブロックの破壊・設置はすべて**運転者の権限**で判定します。判定は、アドベンチャーモード、スポーン保護、ワールドボーダー、およびFabricの`PlayerBlockBreakEvents.BEFORE`(土地保護MODが利用するイベント)です。手で掘れない場所は機械でも掘れません。

---

## 7. 制限事項・既知の注意点

- 車両設定は前提MODと同じくデータパック側(`data/`)から読み込みます。前提MODの外部フォルダ`tudursvehiclemod-addons/`から読み込まれた車両は、走行はできますが機能モジュールは働きません。
- 前提MODの車両定義にはクライアントへの同期処理がなく、本アドオンもこれに合わせています。専用サーバーでの動作は前提MODと同じ条件になります。
- 牽引中のトレーラーは、地形や壁との衝突判定を行いません。
- 当たり判定(ブロック衝突)の箱は前提MODの標準サイズです(`force_bounding_box`で変更可)。
- 旋回する運転席の乗員の体の向きは、前提MODの描画補正の後に補正を重ねるクライアント用mixinで回しています。前提MOD側の該当実装が変わった場合は影響を受ける可能性があります。

---

## 8. ビルド

前提MODのディレクトリで`./gradlew publishToMavenLocal`を実行してから、このプロジェクトで`./gradlew build`を実行します。Minecraft・Fabric Loader・Yarnのバージョンは前提MODと一致させてください(`gradle.properties`)。

## ライセンス

MIT
