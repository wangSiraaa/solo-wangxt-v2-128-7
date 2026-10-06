# 可回放投资核算链（投资教学平台）

回答“**某个交易日结束时，这批股票为什么还剩这些数量和成本**”，而不是保存一个不断被覆盖的持仓余额。

- **事件账本是唯一事实源**：成交、拆股、现金分红、配股都是只追加（append-only）的不可变业务事件，带交易日、结算日、除权日、登记日、支付日、到账日与来源键。
- **投影是派生缓存**：FIFO 成本批次、现金账、权益资格、检查点全部由事件按规范顺序重放得到，可整体清空重建；每效应一事务 + 游标，崩溃后从一致检查点续放。
- **快照只用于加速**：日终复核通过后发布的正式视图，永远不能反向补造历史。
- 数量、价格、现金/金额使用**各自独立精度的 BigDecimal**；零碎股单列。
- 不连接真实券商；外部对账单仅用于账实核对。

## 目录

```
backend/   Spring Boot 3 + Spring Batch + JDBC + PostgreSQL（全部 BigDecimal）
frontend/  Angular 18：事件时间轴 / 批次来源 / 现金权益 / 账实差异与日终发布
samples/   五个验收场景的样例 CSV
```

## 核算链如何回放

```
CSV(并发/重复文件)
  └─ Spring Batch 逐行规范化
       ├─ 文件级 sha256 去重
       ├─ 来源键 (source_system, source_key) 唯一
       └─ 幂等凭证 = SHA256(来源系统|规范化业务指纹)
            └─ business_event（仅追加，触发器禁止 UPDATE/DELETE）   ← 唯一事实源
                 └─ EffectPlanner：交易日→结算、登记日→资格、支付日→入账、除权日→拆股
                      └─ FoldEngine（纯函数、FIFO、独立精度、零碎股）
                           └─ 每效应一个 REQUIRES_NEW 事务：
                                写批次/现金/权益 + 检查点(指纹) + 推进游标（原子）
                                     └─ 日终：三方守恒 + 账实核对
                                          ├─ 不平 → BLOCKED，指出最早失配事件
                                          └─ 复核确认 → 绑定事件水位发布快照
                                               └─ 发布期间迟到成交 → 进入下一草稿
```

### 四个业务日的职责

| 日期 | 参与环节 |
|---|---|
| 交易日 | 成交成立；在途成交遇拆股按 (交易日, 结算日] 比例连乘积折算结算数量与复权价 |
| 结算日 | 股份与现金同日交割，**不可提前入账** |
| 除权日 | 拆股在此日改批次（整股/零碎股拆批并溯源）；分红以此切分含权/除权交易 |
| 登记日 | 按“登记日登记在册”（须在登记日含当日结算交割）计算权益资格 |
| 支付日 | 现金分红真正入账；配股扣款 |
| 到账日 | 配股新增独立成本批次 |

### 精度

`application.yml` 中可配：股数 8 位、价格 6 位、现金 2 位、批次单位成本 6 位，全部独立舍入；
成本结转以现金精度（分）为事实值，部分卖出批次的舍入尾差留在本批次，不跨批泄漏。

## 验收场景（逐笔）

1. **跨登记日买卖** `samples/scenario1-cross-record.csv`
   09-23 买 100，09-24 卖 40（09-25 结算，早于 09-26 登记日）→ 资格 60 股，
   09-30 支付 0.50/股 = 30.00；剩余批次成本 603.00。
2. **拆股后部分卖出** `samples/scenario2-split-partial-sell.csv`
   100 股一拆三为 300，卖出 150.5 → 剩 149 整股 + 0.5 零碎股（零碎批单列），成本 498.33。
3. **部分认购** `samples/scenario3-partial-rights.csv`
   100 股 10 配 2（资格 20）只认购 7 → 扣款 21.00、到账 7 股新批次，权益状态 PARTIALLY_SUBSCRIBED。
4. **重复文件并发导入**：同字节文件 sha256 直接判 DUPLICATE；换文件名/微调空白后业务事实相同，
   行级来源键唯一约束使每一行只登记一次（见 `SpringBatchImportIntegrationTest`）。
5. **投影中断重启**：模拟“权益已计算、现金尚未入账”崩溃，删除支付检查点并回退游标，
   再重放——股份批次不变、分红现金仅一条（见 `ProjectionAndEodIntegrationTest`）。

任一账实不平，发布返回 `409 PUBLISH_BLOCKED` 并带 `earliestMismatchEventId`。

## 运行

```bash
docker compose up --build          # postgres + 后端 http://localhost:8080
cd frontend && npm install && npm start   # http://localhost:4200
```

或本地：

```bash
# 需要 JDK21、Maven、PostgreSQL16
createdb investclass   # 按 application.yml 配置账号
cd backend && mvn spring-boot:run
```

界面操作：选账户（样例账户 SC1/SC2/SC3）→ 上传 `samples/*.csv` → “崩溃恢复重放”
→ 日终页选业务日 → 准备草稿 → 运行核对 → （可选录入对账单制造账实差异）→ 确认发布。

## 测试

```bash
cd backend && mvn test
```

17 个测试：8 个纯函数核算（FoldEngine）、3 个三方守恒/账实（Reconciliator）、
6 个真实嵌入式 PostgreSQL 端到端（不可变账本、并发幂等导入、崩溃续放、日终阻断、迟到成交）。
