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

## とりあえず1つのEntityにするパタン

- UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
    - Option＜ContractStartDate＞
    - Option＜ContractEndDate＞
    - Option＜OrderCancelDate＞
  - メソッド
    - onFinishedSetup(): Validate<Error, UserEntity>
    - onOrderCancel(): Validate<Error, UserEntity>
    - onContractEnd(): Validate<Error, UserEntity>

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

## ステートパタン
仕様を分析すると下記のような状態遷移表や状態遷移図が書けます。そしてデザパタの勉強した人なら、「状態遷移といえばステートパタンだ！！」となるので、その方向で考えて見ます。ただ結論を言っちゃうと、これは失敗です。少なくとも私は嫌いです。なので時間がない人はこの章を飛ばしてもらってOKです。

- UserEntity＜＜interface＞＞
  - onFinishedSetup(): Validate<Error, UserEntity>
  - onOrderCancel(): Validate<Error, UserEntity>
  - onContractEnd(): Validate<Error, UserEntity>

- UserOrderedEntity implements UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
  - メソッド
    - onFinishedSetup(): Validate<Error, UserEntity>
    - onOrderCancel(): Validate<Error, UserEntity>
    - onContractEnd(): Validate<Error, UserEntity>

- UserOrderCanceledEntity implements UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
    - OrderCancelDate
  - メソッド
    - onFinishedSetup(): Validate<Error, UserEntity>
    - onOrderCancel(): Validate<Error, UserEntity>
    - onContractEnd(): Validate<Error, UserEntity>

- UserContractedEntity implements UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
    - ContractStartDate
  - メソッド
    - onFinishedSetup(): Validate<Error, UserEntity>
    - onOrderCancel(): Validate<Error, UserEntity>
    - onContractEnd(): Validate<Error, UserEntity>

- UserContractEndedEntity implements UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
    - ContractStartDate
    - ContractEndDate
  - メソッド
    - onFinishedSetup(): Validate<Error, UserEntity>
    - onOrderCancel(): Validate<Error, UserEntity>
    - onContractEnd(): Validate<Error, UserEntity>

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


- UserOrderedEntity implements UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
  - メソッド
    - onFinishedSetup(): UserContractedEntity
    - onOrderCancel(): UserOrderCanceledEntity

- UserOrderCanceledEntity implements UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
    - OrderCancelDate

- UserContractedEntity implements UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
    - ContractStartDate
  - メソッド
    - onContractEnd(): UserContractEndedEntity

- UserContractEndedEntity implements UserEntity
  - フィールド
    - UserId
    - State
    - OrderDate
    - ContractStartDate
    - ContractEndDate

## 考察
クラスを見ただけで何ができるのかがわかるようになりました。またメソッドの戻り値が状態ごとのエンティティになることで、状態遷移の仕様もクラスで表現できるようになりました。これはもう仕様書として成立するレベルですね。このパタンをStateEntityパタンと呼ぶことにします。
ただこれにはリポジトリから取得する方法に問題があります。
SingleEntityパタン(ステートパタンも同様)とStateEntityパタンのリポジトリを考えます。

SingleEntityパタン
```
UserRepository {
  Validate<Error, UserEntity> find(UserId)
}
```

StateEntityパタン
```
UserRepository {
  Validate<Error, UserOrderedEntity> findOrdered(UserId)
  Validate<Error, UserContractedEntity> findContracted(UserId)
  Validate<Error, UserOrderCanceledEntity> findCanceled(UserId)
  Validate<Error, UserContractEndedEntity> findContractEnded(UserId)
}
```

SingleEntityパタンではメソッドは1つだけです。UserIdで検索してヒットすれば正常値、ないなら異常値が返ります。ただ戻り値の型は状況によってパタンはいろいろあると思います。異常時にErrorの詳細な内容を知らなくていいならOption<UserEntity>でもいいし、
異常時は例外投げるようにして戻り値をUserEntityにしてもいい。そこはAP層の設計による部分なので適宜決めてください。

そして本題のStateEntityパタンのリポジトリについてですが、ステートごとにfindを実装する必要があります。
