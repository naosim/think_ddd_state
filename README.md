# Entityについて考える
## 動機
DDDのEntityをどう設計するべきかいろいろ考えてきたけど、その考えをそろそろアウトプットしたほうがいいかなと思ったので、思いついたままに書き記す。

## 前提
あるサービスがあり、そのサービスのユーザドメインには、申込中から契約終了までの一連の状態背にがある。
このサービスのユースケースは以下の通り。

### ユースケース
- サービスに申込むとユーザが作られ、ユーザIDが裁判され、状態が申込中になる
- 申込中に申込キャンセルをすると、申込キャンセルになる
- 申込中にセットアップ処理が完了すると、契約中になる
- 契約中に、契約終了申込がくると、契約終了になる

### ユーザの状態
- なし(申込すらされてない状態)
- 申込中
- 申込キャンセル
- 契約中
- 契約終了

### その他補足
- 各状態遷移時にはその日付が記録される
  - 例えば申込がされたら申込日が記録される
- コードはscalaで書きたいけど、現状に合わせてjava8 + lombok + javaslangの想定です。


# DDDとレイヤーアーキテクチャ
言葉を合わせるためにDDDの言葉とレイヤーアーキテクチャの関係を記載しておきます。

レイヤーアーキテクチャのルール
- 同じレイヤーは依存して良い
- 違うレイヤーは、外側から内側への依存はOK

一般的にはinfra, application, domainの3層ですが、我々はdomainの中にさらに暗黙的な層があるのが特徴です。

infra層: HTTPリクエスト(API)やDBなどを処理する。Stringやintなどのプリミティブ型を使ってもOK。

application層:
Repositoryからデータを取得して、Serviceにわたし、結果をRepositoryに保存する。ここから内側はString等のプリミティブ型の使用はNG

domain層
- 業務ロジックを書く
- フレームワークへの依存を極力排除する
  - DI禁止

### レイヤーアーキテクチャは何がやりたいのか
- ドメイン層の依存を減らし、業務ロジックに集中させる

## とりあえず1つのEntityにするパタン

```java
class UserEntity {
  UserId
  State
  OrderDate
  Option＜ContractStartDate＞
  Option＜ContractEndDate＞
  Option＜OrderCancelDate＞

  onFinishedSetup(): Validate<Error, UserEntity>
  onOrderCancel(): Validate<Error, UserEntity>
  onContractEnd(): Validate<Error, UserEntity>
}
```

ユースケースをそのままEntityにするとこうなります。
メソッドの戻り値が正常値は状態遷移した新しいEntityです。
戻り値の異常値はエラーです。ユースケースにないパタンはとりあえずエラーに落とします。例えば契約中に申込キャンセルがきたときとか。

うちのチームの古くからあるコードはこれです。

## 考察
仕様を知った状態でクラスをみると普通ですが、単純にクラスだけをみるとマズさがわかります。
たとえばContractStartDateてフィールドはいつdefinedになるの？onContractEnd()はいつ正常を返して、いつ異常を返すの？クラスを見ても何もわかりません。
実装を見れば多分わかるでしょうね。onContractEnd()なら多分こんな感じ。
```
Validate<Error, UserEntity> onContractEnd() {
  if(state.equals(state.contracted)) {
    return valid(...);
  }
  return invald(...);
}
```

とにかくこのやり方のマズいところはクラスから何もよみとけないこと。プログラミングで言うなら型で表現されていないため堅牢でない。動作は実装を読むとか、処理をテストするとかしないとわからない。バグを産むこと間違いなしです。
ここで説明した、すべてのユースケースを1つのEntityで表しているものを以降はSingleEntityパタンと呼ぶことにします。
ここから先はこのSingleEntityパタンをもとに「じゃあどうすんの？」てところをいろいろ模索したいと思います。

## 補足：その他のクラス
ちなみにそのほかのクラスの実装はこうなる

### リポジトリ
```java
interface UserRepository {
  UserEntity find(UserId);
  void update(UserEntity);
}
```
find()でヒットしなかった場合は例外なげます。もしくは戻り値をOptionにします。


### サービス
```java
class OrderService {
  Validate<Error, UserEntity> cancelOrder(UserEntity entity) {
    return entity.onOrderCancel();
  }
}
```
業務の流れを書きます。今回はUserEntityしかないのであんまり意味のないクラスです。


### APサービス
```java
class OrderApService {
  private final UserRepository userRepository;
  void cancelOrder(UserId userId) {
    // entityの取得
    UserEntity entity = userRepository.find(userId);

    // 処理
    Validate<Error, UserEntity> result = OrderService.cancelOrder(entity);

    if(result.invalid()) {
      throw RuntimeException(result.error());
    } else {
      // 保存
      userRepository.update(result.get());
    }

  }
}
```

### OrderServiceの必要性
OrderServiceの実装が1行しかないのに必要なのか？もしOrderServiceがなかったら、OrderApServiceに書くことになる。OrderApServiceすでにデータ取得、処理、保存をしていてコード量が多いため、そこにさらに実装を増やすべきではない。またテストの観点でもApServiceはDB等のインフラが絡むためテストしづらい。Serviceならentityを自力でつくるだけでテストできる。
以上のことから、OrderServiceは必ず作ったほうがいい。


## ステートパタン
仕様を分析すると下記のような状態遷移表や状態遷移図が書けます。そしてデザパタの勉強した人なら、「状態遷移といえばステートパタンだ！！」となるので、その方向で考えて見ます。ただ結論を言っちゃうと、これは失敗です。少なくとも私は嫌いです。なので時間がない人はこの章を飛ばしてもらってOKです。

```java
interface UserEntity {
  onFinishedSetup(): Validate<Error, UserEntity>
  onOrderCancel(): Validate<Error, UserEntity>
  onContractEnd(): Validate<Error, UserEntity>
}

UserOrderedEntity implements UserEntity {
  UserId
  State
  OrderDate

  onFinishedSetup(): Validate<Error, UserEntity>
  onOrderCancel(): Validate<Error, UserEntity>
  onContractEnd(): Validate<Error, UserEntity>
}

UserOrderCanceledEntity implements UserEntity {
  UserId
  State
  OrderCancelDate

  onFinishedSetup(): Validate<Error, UserEntity>
  onOrderCancel(): Validate<Error, UserEntity>
  onContractEnd(): Validate<Error, UserEntity>
}

UserContractedEntity implements UserEntity {
  UserId
  State
  ContractStartDate

  onFinishedSetup(): Validate<Error, UserEntity>
  onOrderCancel(): Validate<Error, UserEntity>
  onContractEnd(): Validate<Error, UserEntity>
}

UserContractEndedEntity implements UserEntity {
  UserId
  State
  ContractStartDate
  ContractEndDate

  onFinishedSetup(): Validate<Error, UserEntity>
  onOrderCancel(): Validate<Error, UserEntity>
  onContractEnd(): Validate<Error, UserEntity>
}
```

UserEntityインターフェースを状態ごとのEntityで実装しています。

## 考察
SingleEntityパタンと比べると、フィールドからOptionが消えたことがわかります。状態ごとにクラスにしているので、たとえばUserOrderCanceledEntity(申込キャンセル)には契約開始日等は不要になります。結果、フィールドについてはクラスから仕様が理解できるようになりました。ただしメソッドはどうでしょう？SingleEntityパタンとかわらないので、依然としてクラスからは何もわからないですね。ただメソッドの実装は進化しています。onContractEnd()はこんな感じ。

UserOrderedEntity
```
Validate<Error, UserEntity> onContractEnd() {
  return invalid(...);
}
```

UserContractedEntity
```
Validate<Error, UserEntity> onContractEnd() {
  return valid(...);
}
```
SingleEntityパタンではIF文で分岐してましたが、ここではIF文が消えてシンプルになりました。

## まとめ
ステートパタンはSingleEntityパタンに比べて、フィールドについてはクラスで理解できるようになったが、メソッドについてはわからないまま。

## 状態ごとに堅牢なEntityを作る
ステートパタンの悪いところは、共通インターフェースを全員が実装していること。それによってクラスごとの機能がぼやける。もちろん隠蔽を目的にするなら良いけど、クラスから仕様を把握する目的ではよくない。なので共通インターフェースを取っ払って各Entityでできること(メソッド)を忠実に実装してみます。

```java
class UserOrderedEntity {
  UserId
  State
  OrderDate

  onFinishedSetup(): UserContractedEntity
  onOrderCancel(): UserOrderCanceledEntity
}

class UserOrderCanceledEntity {
  UserId
  State
  OrderDate
  OrderCancelDate
}

class UserContractedEntity {
  UserId
  State
  OrderDate
  ContractStartDate

  onContractEnd(): UserContractEndedEntity
}

class UserContractEndedEntity {
  UserId
  State
  OrderDate
  ContractStartDate
  ContractEndDate
}
```

## 考察
クラスを見ただけで何ができるのかがわかるようになりました。またメソッドの戻り値が状態ごとのエンティティになることで、状態遷移の仕様もクラスで表現できるようになりました。これはもう仕様書として成立するレベルですね。このパタンをStateEntityパタンと呼ぶことにします。

## その他のクラス
### リポジトリ
```java
UserRepository {
  UserOrderedEntity findOrdered(UserId)
  UserContractedEntity findContracted(UserId)
  UserOrderCanceledEntity findCanceled(UserId)
  UserContractEndedEntity findContractEnded(UserId)
  
  UserReferEntity findRefer(UserId)
}
```

SingleEntityパタンでは取得メソッドは1つだけでしたが、本題のStateEntityパタンはステートごとにfindを実装する必要があります。


```java
```
