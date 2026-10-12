# Construction Detail Pack(重機ディテールパック)

Construction Machinery Addon(`constructionaddon`)の機能を使って、実際の重機クラスに近い寸法で作り込んだ重機・車両11機を追加するアドオンパックです。
同梱のサンプル機とは別の、新規設計の機体です(寸法・関節位置・作業点・座席・車輪位置もすべて独自)。

- 前提:Tudur's Vehicle Mod 1.0.3 以降、Construction Machinery Addon(関節モード`aim`/`reach`/`belt`/`show`・`crawlers`と、打撃式・回転貫入式の杭の施工に対応した版)
- モデルは [Meshkit](https://github.com/Tuduraw/Meshkit) で作成しました(生成スクリプト:このパックのプロジェクトの `tools/`)。
- 作者:Tuduraw。Tudur's Vehicle Mod の作者 Tuduraw 氏に感謝します。モデル・テクスチャ・スクリプトの作成には Claude(Anthropic)を使用しました。
- ライセンス:MIT(リポジトリと同じ)

## 導入

フォルダ `ConstructionDetailPack` ごと、ゲームディレクトリの `tudursvehiclemod-addons/` に置きます。

```
<ゲームディレクトリ>/tudursvehiclemod-addons/ConstructionDetailPack/
  data/constructiondetail/vehicles/*.json
  assets/constructiondetail/models/obj/*.obj
  assets/constructiondetail/textures/vehicle/*.png
```

ゲーム中は `/reload` で読み込まれます。スポーンアイテムは重機カテゴリの各ティアに並びます。

## 収録機体

| 機体ID | 表示名 | ティア | 機能 | 全長×全幅×全高(m) | 三角形数 |
|---|---|---|---|---|---|
| excavator_3t | Mini Backhoe 3t | 1 | バックホウ(排土板付き) | 4.6 × 1.5 × 2.45 | 約3,400 |
| excavator_20t | Backhoe 20t | 2 | バックホウ | 10.8 × 2.8 × 3.0 | 約4,100 |
| bulldozer_20t | Bulldozer 20t | 2 | ブルドーザー(リッパー付き) | 7.1 × 3.42 × 3.2 | 約3,600 |
| dump_truck_10t | Dump Truck 10t | 2 | ダンプカー | 7.9 × 2.49 × 3.0 | 約6,800 |
| rough_terrain_crane_25t | Rough Terrain Crane 25t | 3 | ラフタークレーン | 11.7 × 2.62 × 3.4 | 約4,700 |
| pile_driver_hammer | Drop Hammer Pile Driver | 3 | 杭打ち機(打撃式) | 6.8 × 3.8 × 13.9 | 約5,600 |
| mixer_truck | Concrete Mixer Truck | 3 | ミキサー車 | 7.9 × 2.49 × 3.9 | 約7,700 |
| tractor_head | Tractor Head 6x4 | 3 | トラクターヘッド | 6.8 × 2.49 × 3.6 | 約6,400 |
| lowboy_trailer | Low-bed Trailer | 3 | 低床トレーラー | 13.0 × 3.0 × 1.8 | 約5,200 |
| pile_driver_rotary | Rotary Pile Driver | 4 | 杭打ち機(回転貫入式) | 6.8 × 3.8 × 13.9 | 約6,300 |
| pile_driver_large | Large Rotary Pile Driver | 5 | 大型杭打ち機(回転貫入式) | 9.2 × 4.3〜4.9 × 25 | 約7,500 |

寸法はモデルの休止姿勢での値です。全幅はミラーを除く車体幅、全高はキャブ(トラクターは排気管、杭打ち機はリーダー上端、トレーラーはグースネック)までの高さです。全長は、バックホウが後端からバケット先端まで(ブームを上げ、アームを下ろした姿勢)、ブルドーザーがリッパーを含む長さ、ミキサー車がシュートを除く長さです。

運転者は、キャブの内寸に頭・腕・脚が収まるように縮めて表示し(`passenger_display`)、視点を縮めた運転者の目の高さに置いています(`camera_positions`)。倍率は、トラクターヘッド 1.0、ダンプカー・ミキサー車 0.975、バックホウ 20t・大型杭打ち機 0.85、ブルドーザー 0.8、杭打ち機 0.75、ミニバックホウ 0.65、ラフタークレーン 0.6(キャブがブームの横で幅0.75mと細いため)です。

## 機体ごとの構成

- **Backhoe 20t / Mini Backhoe 3t**:ブーム・アーム・バケットはそれぞれの油圧シリンダーで動きます(シリンダーも機械の動きに合わせて回転・伸縮します)。3t級は後端旋回半径の小さい上部旋回体と排土板(固定)を持ちます。
- **Bulldozer 20t**:セミU形の排土板(幅3.42m)、リフトシリンダー、後部リッパー(固定)。
- **Rough Terrain Crane 25t**:4段の伸縮ブーム(最大21m伸長)、起伏シリンダー、片側2.1m張り出すアウトリガー、4輪の大径タイヤ。
- **Drop Hammer / Rotary Pile Driver**:高さ約12mの箱形断面リーダー(継手フランジ・前面のガイドレール・背面のはしご付き)。リーダーは下端のピンと、高さ7mほどの背面に取り付けた中央1本の太い起伏シリンダーで支え、走行時は下端を支点に後方へ倒してレストに載せます。回転貫入式は、リーダーを上下する回転装置が鋼管杭(直径0.5m、先端に羽根付き)の頭を掴み、回しながら押し込みます。建て込める杭長は2〜9m、打ち下げ用のドライブロッドは3.5m延長できます。打撃式は、リーダーを上下するキャップ(ヘルメット)が鋼管杭(直径0.5m)の頭に載り、その上のハンマーを持ち上げて落として打ち込みます。打ち下げには長さ3.5mのヤットコを使います。リーダー下端には振れ止め(後述)があります。
- **Large Rotary Pile Driver**:直径0.8mの鋼管を継いだ高さ約24mのリーダーを、下端のピンと、高さ12.5mほどに取り付けた2本の多段式バックステーで支える三点支持式。起立・格納ではリーダー全体が下端を支点に起伏し、格納時はカウンターウェイト上のレストに載ります。リーダーを上下する回転装置が鋼管杭(直径0.8m、先端に羽根付き)の頭を掴み、リーダー下端の振れ止めに抱えさせて回しながら押し込みます。建て込める杭長は4〜18m、打ち下げ用のヤットコは長さ6mです。クローラー拡幅(片側0.7m)付き。
- **杭打ち機のアウトリガー**:3機とも、上部旋回体の下部の四隅に付いたアウトリガーが斜め外側へ張り出し(小型機1.1m、大型機1.7m)、ジャッキを接地させます。上部旋回体と一緒に旋回し、どの向きでも接地点がクローラー(大型機は拡幅後のクローラー)の外側に来る長さにしています。
- **Dump Truck 10t / Concrete Mixer Truck / Tractor Head 6x4**:キャブオーバー型・右ハンドルの3軸車(前1軸操舵、後2軸はダブルタイヤ)。ダンプの荷台はホイストシリンダー付き。トラクターのカプラー高さは1.25mです。
- **Low-bed Trailer**:床高0.85m・幅3.0mの低床部と、2軸台車の上の後部床(高さ1.3m)。後端の歩み板は先端が地面に届く角度まで倒れます。

重機の性能に関わる設定(速度・容量・硬さの扱いなど)は、同じ種類のサンプル機の値を引き継いでいます。寸法に関わる設定(排土板の位置と大きさ、荷台の範囲、ブームの伸長量、杭の最大深さ、連結位置など)は、この機体の寸法に合わせて変えています。

### 油圧シリンダーの追従について

シリンダーの筒(`$〇〇_ram`)は相手側の取付点の方向へ向く`aim`関節、棒・各段(`$〇〇_ram_s1`…)は取付点間の距離の伸びを分け合う`reach`関節です。駆動する部品の現在の位置から毎回計算するため、ブーム・アーム・バケット・起伏・ホイスト、杭打ち機のバックステーとも、動作範囲のどの姿勢でも取付点から外れません。

### クローラー(履帯)

すべてのクローラー機(バックホウ2機・ブルドーザー・杭打ち機3機)の履帯は、走行・旋回に合わせて動きます。起動輪・誘導輪・転輪・上部転輪も、履帯の送りに合った速さで回ります。

- 足回りは戦車と同じ考え方で配置しています。前の誘導輪と後ろの起動輪は上側の履帯に内接し、転輪は下側の履帯の上に、誘導輪・起動輪とも互いにも重ならない間隔で並べ、上部転輪が上側の履帯を支えます。
- 動かないクローラーは、Tudur's Vehicle Mod の`crawler_tracks`/`track_roller_parts`で動かします。
- 大型杭打ち機のクローラーは拡幅で左右へ動くため、Construction Machinery Addon の`crawlers`で描画します(履帯の送りはTudur's Vehicle Mod が左右別々に計算した値を使うので、旋回時の左右差も同じに出ます)。

### 杭の施工(打撃式・回転貫入式)

杭打ち機3機とも、Construction Machinery Addon の杭の施工(建て込み杭長の設定、杭の建て込み・貫入・引き上げ、杭を離す・掴み直す、継ぎ杭の接合、ドライブロッド・ヤットコによる打ち下げ)にそのまま対応しています。打撃式と回転貫入式の違いは、杭を地中へ進める方法(ハンマーの打撃か、回転か)だけです。操作はアドオン本体のREADMEを参照してください(既定:Xでリーダー起立、↑↓で建て込み杭長、Zで建て込み・掴む・離す、右クリックで貫入、左クリックで引き上げ、Iでドライブロッド・ヤットコ)。

- 鋼管杭は軸と先端に分かれていて、建て込み杭長に合わせて軸が伸び縮みします。回転貫入式の杭は先端に羽根付き、打撃式の杭は閉じた靴の普通の鋼管杭です。
- 小型の回転貫入式(Rotary Pile Driver)のドライブロッドは常に回転装置を貫いて付いていて、打ち下げ時は3.5m下へ延びます。大型機のヤットコは、切り替えると回転装置の下に現れます。
- 打撃式(Drop Hammer Pile Driver)は、杭頭に載せるキャップ(ヘルメット)がリーダーを上下し、その上にハンマーが載っています。打撃のたびにハンマーがキャップから1.8m持ち上がって落ち、杭が少しずつ沈みます。打ち下げには長さ3.5mのヤットコを使い、切り替えるとキャップの下に現れます(ドロップハンマーでは、キャップを貫くドライブロッドよりヤットコが一般的なため)。

### 振れ止め

杭打ち機3機とも、リーダー下端に振れ止めがあります。中央(杭が通る部分)が空洞の半割リング2つで、杭を左右から抱えます。キャップ(またはドライブロッド・ヤットコの下端)が下りてきて振れ止めの1.2m上まで近づくと、ヘッド(回転装置・ハンマーのキャップ)に干渉しないよう左右に開き、上がると再び閉じます(`steady`チャンネル)。

杭材は鋼管杭なので、どの機体も鉄ブロックを消費します(`pile_block`)。

バックホウのブームは、根元から急に立ち上がり、途中で折れて先端へ向かう形(上に凸)です。

## 形と寸法の考え方(実機との比較について)

このパックは特定の実在機の再現ではありません。メーカー名・機種名・ロゴ・特徴的な意匠は使わず、形はすべてオリジナルです。
実在の重機は、一般的な構造(どんな部品で構成され、どこが動くか)と、クラスごとのおおよその寸法を比べるためだけに参照しました。比べた目安は次のとおりです(いずれも各クラスで一般的な範囲)。

| 機体 | 比較したクラスの目安 | このパック |
|---|---|---|
| Backhoe 20t | 下部走行体長さ約4.4m、全幅約2.8m(履板0.6m)、キャブ高さ約3.0m、後端旋回半径約2.7〜2.8m、ブーム約5.7m、アーム約2.9m | 走行体4.44m、全幅2.8m、高さ3.0m、後端2.75m、ブーム5.7m、アーム2.9m |
| Mini Backhoe 3t | 走行体長さ約2.1m、全幅約1.5m、高さ約2.5m、ブーム約2.6m、アーム約1.3m | 2.1m、1.5m、2.45m、2.6m、1.35m |
| Bulldozer 20t | 排土板幅約3.4m・高さ約1.3m、軌間約2.0m、履板約0.56m、高さ約3.2m | 3.42m・1.3m、1.98m、0.56m、3.2m |
| Rough Terrain Crane 25t | 全長約11m、全幅約2.6m、全高約3.5m、ホイールベース約3.6m、ブーム4段約9〜30m、アウトリガー最大張り出し約6.3m | 11.7m、2.62m、3.4m、3.7m、8.9〜29.9m、6.3m |
| Dump Truck 10t | 全長約7.6m、全幅2.49m、高さ約3.0m、ホイールベース約4.7〜5.0m、荷台内寸約5.1×2.2×0.5m | 7.9m(バンパー・灯火含む)、2.49m、3.0m、4.95m、5.2×2.1×0.6m |
| Mixer Truck | 全長約7.7m、全高約3.75m、ドラム径約2.2m | 7.9m、3.9m、2.2m |
| Tractor Head 6x4 | 全長約6.3〜6.6m、カプラー高さ約1.25m | 6.8m、1.25m |
| Low-bed Trailer | 全長約12〜13m、全幅約3.0m、床高約0.8〜0.9m | 13.0m、3.0m、0.85m |
| Pile Drivers | 小型:リーダー約12m。大型:リーダー約20〜30m、バックステーはリーダー中間部、クローラー幅約3.5→4.9m、回転貫入の鋼管杭径約0.4〜1.0m | 12m。24m、高さ約1/2、3.5→4.9m、0.5m・0.8m |

ゲーム上の都合で変えている点:

- 当たり判定(`width`)は、移動のしやすさを優先して 1.6〜3.5 にしています(実際の長さより小さい正方形)。
- 運転席は日本の車両と同じ右ハンドルです。

## 作り直し

```
git clone https://github.com/Tuduraw/Meshkit.git   # このプロジェクトと同じ階層に置く(または MESHKIT_PATH を指定)
python3 tools/build.py                   # ConstructionDetailPack/ を書き出す
python3 tools/build.py --preview         # 休止姿勢と作業姿勢のプレビュー画像も build/preview/ に書き出す
python3 tools/build.py excavator_20t     # 指定した機体だけ作り直す
```

必要なのは Python 3、numpy、Pillow です。速度・容量などの性能設定は `tools/templates/` にあります。寸法は各スクリプト(`excavator.py`・`crane.py`・`dozer.py`・`piling.py`・`trucks.py`)の先頭の定数で決めており、関節・作業点・座席・車輪の位置もそこから計算して機体JSONに書き出します。

---

# Construction Detail Pack (English)

An add-on pack with 11 newly designed construction machines and vehicles for Construction Machinery Addon, built to
the approximate dimensions of real machine classes (their dimensions, joints, work points, seats and wheels are all
their own, independent of the sample machines).

- Requires Tudur's Vehicle Mod 1.0.3+ and Construction Machinery Addon.
- Models made with [Meshkit](https://github.com/Tuduraw/Meshkit) (generator: `tools/` of this pack project).
- Thanks to Tuduraw, author of Tudur's Vehicle Mod. Claude (Anthropic) was used to create the models, textures and scripts.
- Install: copy the `ConstructionDetailPack` folder into `<game dir>/tudursvehiclemod-addons/`, then `/reload`.
- Machines: 3 t and 20 t backhoes, 20 t bulldozer, 25 t rough terrain crane, drop hammer / rotary / large rotary pile
  drivers, 10 t dump truck, mixer truck, 6x4 tractor head and low-bed trailer (see the Japanese table for sizes).
- Performance settings (speeds, capacities, hardness handling) follow the sample machine of the same type; settings
  tied to size (blade, body box, boom extension, pile depth, coupling points) follow the new dimensions.
- Hydraulic rams rotate and extend with the channel of the part they drive; the fit is linear, so the rod end can be
  off by up to 6-32 cm at the ends of the range. Pile driver backstays (multi-stage, attached at about half the
  leader height) match exactly with the leader raised and lowered (up to ~1-2.2 m off only while it moves).
- Pile drivers: box-section leaders (compact rigs) and a round-pipe leader on a three-point rig (large), each pinned
  at its foot, held by two backstays and laid back onto a rest for travel. Rotary rigs turn and press in a steel pipe
  pile with a tip wing.
- Not a replica of any real machine: no brands, model names or logos, and all shapes are original. Real machines were
  used only to compare general structure and typical class dimensions.
- Rebuild: `python3 tools/build.py` (needs Python 3, numpy, Pillow and Meshkit next to the project or
  at `$MESHKIT_PATH`).
