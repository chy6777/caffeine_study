# Caffeine 缓存项目完整实操指南

---

## 前置准备

### 环境要求
```
JDK 17+
Maven 3.6+
Docker（第三阶段需要）
```

### 启动项目
```bash
# 编译
mvn clean package -DskipTests

# 运行（第一、二阶段无需Redis）
java -jar target/caffeine-demo-1.0.0.jar
```

启动成功后控制台会输出：
```
Caffeine 分级学习 Demo 启动成功！
[基础篇]  http://localhost:8080/api/users/1
[监控]    http://localhost:8080/api/cache/stats
[H2控制台] http://localhost:8080/h2-console
```

### 初始数据说明
项目启动时 `DataInitializer` 自动插入20条用户数据：
- **id 1~5**：热点用户（`hot=true`），名字分别是 Alice、Bob、Charlie、Diana、Eve
- **id 6~20**：普通用户
- 数据库使用H2内存数据库，无额外安装

---

## 第一阶段：基础篇

### 学习目标
理解 Caffeine 的基本缓存机制：读取缓存、缓存命中、过期策略、淘汰算法

---

### 实操1：观察缓存穿透过程

**目的**：理解缓存未命中时查询数据库的行为

**第一步：查询用户（缓存未命中）**
```
GET http://localhost:8080/api/users/1
```

**预期日志**：
```
[DB QUERY] 查询用户: id=1
```

**预期响应**：
```json
{
  "id": 1,
  "name": "Alice",
  "email": "alice@example.com",
  "age": 20,
  "hot": true,
  "createdAt": "2024-xx-xxTxx:xx:xxZ"
}
```

**第二步：再次查询同一用户（缓存命中）**
```
GET http://localhost:8080/api/users/1
```

**预期**：日志中**不再出现** `[DB QUERY]`，说明直接从缓存返回

**学习要点**：第一次查询走数据库并写入缓存，后续查询直接从缓存读取

---

### 实操2：对比两种过期策略

**目的**：理解 `expireAfterWrite`（写入后固定过期）与 `expireAfterAccess`（访问后重置过期）的区别

**第一步：分别用两种缓存查询**
```
# 走 expireAfterWrite 缓存（默认）
GET http://localhost:8080/api/users/2?cacheType=write

# 走 expireAfterAccess 缓存
GET http://localhost:8080/api/users/2?cacheType=access
```

**第二步：查看时间线**
```
GET http://localhost:8080/api/cache/timeline/2
```

**预期响应**：
```json
{
  "writeCache": {
    "cacheType": "expireAfterWrite",
    "cached": true,
    "expireTime": "2024-xx-xxTxx:xx:xxZ",
    "remainingTTL": "4.8min",
    "note": "expireAfterWrite: 过期时间从写入计算，访问不会延长"
  },
  "accessCache": {
    "cacheType": "expireAfterAccess",
    "cached": true,
    "expireTime": "2024-xx-xxTxx:xx:xxZ",
    "remainingTTL": "5.0min",
    "note": "expireAfterAccess: 每次访问重置过期时间！"
  }
}
```

**第三步：等30秒后再次访问 access 缓存**
```
GET http://localhost:8080/api/users/2?cacheType=access
```

**第四步：再次查看时间线**
```
GET http://localhost:8080/api/cache/timeline/2
```

**预期**：
- `writeCache` 的 `remainingTTL` 继续递减（约4.5min）
- `accessCache` 的 `remainingTTL` 重置回接近5min

**学习要点**：
- `expireAfterWrite`：无论访问多少次，到期就过期，适合 session、验证码
- `expireAfterAccess`：常访问的数据一直存活，适合热点数据、配置项

---

### 实操3：Debug模式查看缓存细节

**目的**：查看缓存是否命中、查询耗时、穿透风险

```
GET http://localhost:8080/api/users/1/debug
```

**预期响应**：
```json
{
  "data": { "id": 1, "name": "Alice", ... },
  "from": "CACHE",
  "cacheHit": true,
  "penetrationRisk": false,
  "queryTime": "0.012ms"
}
```

**清空缓存后再试**：
```
DELETE http://localhost:8080/api/cache/clear
```
```
GET http://localhost:8080/api/users/1/debug
```

**预期响应**：
```json
{
  "data": { "id": 1, "name": "Alice", ... },
  "from": "DATABASE",
  "cacheHit": false,
  "penetrationRisk": false,
  "queryTime": "2.350ms"
}
```

**查询不存在的用户**：
```
GET http://localhost:8080/api/users/99999/debug
```

**预期响应**：
```json
{
  "data": null,
  "from": "DATABASE",
  "cacheHit": false,
  "penetrationRisk": true,
  "queryTime": "1.200ms",
  "warning": "该 key 未缓存空值，反复查询会每次都打到数据库（缓存穿透）！解决方案见第三阶段。"
}
```

**学习要点**：不存在的key每次都会查库，这就是缓存穿透问题的根源

---

### 实操4：观察 W-TinyLFU 淘汰算法

**目的**：理解 Caffeine 的频率感知淘汰机制

```
GET http://localhost:8080/api/cache/evict-demo?maxSize=3
```

**预期响应**：
```json
{
  "maxSize": 3,
  "experiment1_基本淘汰": [
    "put(1) → 缓存: [1] (大小: 1)",
    "put(2) → 缓存: [1, 2] (大小: 2)",
    "put(3) → 缓存: [1, 2, 3] (大小: 3)",
    "put(4) → 缓存: [2, 3, 4] (大小: 3)",
    "put(5) → 缓存: [3, 4, 5] (大小: 3)"
  ],
  "experiment2_频率感知淘汰(W-TinyLFU)": [
    "插入 1,2,3 → 缓存: [1, 2, 3]",
    "访问 key=1 共 10 次 → 缓存: [1, 2, 3]",
    "put(4) → 缓存: [1, 3, 4] ← key=1 频率高，更不容易被淘汰",
    "put(5) → 缓存: [1, 4, 5]"
  ],
  "lesson": "Caffeine 使用 W-TinyLFU 算法：频繁访问的条目存活率更高..."
}
```

**学习要点**：对比两个实验——实验1中FIFO淘汰最早写入的；实验2中key=1因为被访问10次，频率高所以存活

---

### 实操5：查看全局缓存统计

```
GET http://localhost:8080/api/cache/stats
```

**预期响应**：
```json
{
  "writeCache": {
    "type": "expireAfterWrite 5min",
    "size": 1,
    "hitCount": 2,
    "missCount": 1,
    "hitRate": "66.67%",
    "evictionCount": 0,
    "avgLoadPenalty": "0.523ms"
  },
  "accessCache": {
    "type": "expireAfterAccess 5min",
    "size": 1,
    "hitCount": 3,
    "missCount": 1,
    "hitRate": "75.00%",
    "evictionCount": 0
  },
  "manualCache": {
    "type": "手动缓存 (expireAfterWrite 10min)",
    "size": 0,
    "hitCount": 0,
    "missCount": 0,
    "hitRate": "0.00%"
  }
}
```

**学习要点**：`recordStats()` 开启统计后，可以实时查看命中率、淘汰次数、平均加载耗时

---

### 实操6：基础CRUD与缓存同步

**新增用户**：
```
POST http://localhost:8080/api/users
Content-Type: application/json

{
  "name": "TestUser",
  "email": "test@example.com",
  "age": 25
}
```

**查看缓存大小**：
```
GET http://localhost:8080/api/cache/size
```

**更新用户（触发 @CacheEvict）**：
```
PUT http://localhost:8080/api/users
Content-Type: application/json

{
  "id": 1,
  "name": "AliceUpdated",
  "email": "alice@example.com",
  "age": 21
}
```

**验证缓存已清除**：
```
GET http://localhost:8080/api/users/1/debug
```

预期 `from: "DATABASE"`，说明更新后缓存已被清除，重新查库

**删除用户**：
```
DELETE http://localhost:8080/api/users/21
```

---

### 实操7：手动缓存方式

**目的**：对比声明式（@Cacheable）与命令式（Cache API）的区别

```
GET http://localhost:8080/api/users/3/manual
GET http://localhost:8080/api/users/3/manual
```

第二次请求日志中出现 `[CACHE HIT - MANUAL]` 而非 `[DB QUERY - MANUAL]`

---

## 第二阶段：进阶篇

### 学习目标
掌握缓存更新策略、并发保护、条件缓存、缓存预热

---

### 实操8：@CachePut vs @CacheEvict 对比

**目的**：理解两种更新策略的核心区别

**第一步：先查询让缓存有数据**
```
GET http://localhost:8080/api/users/1
```

**第二步：用 @CachePut 更新**
```
PUT http://localhost:8080/api/users/update-v2
Content-Type: application/json

{
  "id": 1,
  "name": "Alice-v2",
  "email": "alice@example.com",
  "age": 21
}
```

**预期响应**：
```json
{
  "user": { "id": 1, "name": "Alice-v2", ... },
  "strategy": "CachePut",
  "operation": "更新数据库 + 直接刷新缓存",
  "nextReadWillHitCache": true,
  "note": "适用于：更新后对象完整、希望下次读取立即命中"
}
```

**第三步：立即查询验证**
```
GET http://localhost:8080/api/users/1
```

预期：日志中**无** `[DB QUERY]`，说明直接命中缓存，且返回 `name: "Alice-v2"`

---

**第四步：用 @CacheEvict 更新**
```
PUT http://localhost:8080/api/users/update-v3
Content-Type: application/json

{
  "id": 1,
  "name": "Alice-v3",
  "email": "alice@example.com",
  "age": 22
}
```

**预期响应**：
```json
{
  "user": { "id": 1, "name": "Alice-v3", ... },
  "strategy": "CacheEvict",
  "operation": "更新数据库 + 删除缓存",
  "nextReadWillHitCache": false,
  "note": "下次读取会 miss 一次查库后重新缓存"
}
```

**第五步：立即查询**
```
GET http://localhost:8080/api/users/1
```

预期：日志中**有** `[DB QUERY]`，说明缓存被删除后重新查库

**学习要点**：
- `@CachePut`：更新后缓存立即有新值，适合更新后对象完整且希望下次读取立即命中的场景
- `@CacheEvict`：删除缓存，下次读取会miss一次，适合不确定更新后缓存值是否正确的场景

---

### 实操9：sync=true 并发保护（有效场景）

**目的**：验证同一key并发查询时，sync=true确保只有一个线程查库

**第一步：清空缓存，模拟缓存过期**
```
DELETE http://localhost:8080/api/cache/clear
```

**第二步：100个线程同时查询同一key**
```
GET http://localhost:8080/api/users/1/sync-test?threads=100
```

**预期响应**：
```json
{
  "scenario": "100 个线程查询同一个 key (id=1)",
  "syncProtection": "有效",
  "dbQueryCount": 1,
  "cacheHitCount": 99,
  "totalTime": "85.3ms",
  "lesson": "sync=true 确保同一 key 只有一个线程查库，其他等待结果"
}
```

**关键指标**：`dbQueryCount` 应该为 **1**，说明只有1个线程查了数据库，其他99个线程等待结果

---

### 实操10：sync=true 失效场景（不同key）

**目的**：理解sync=true只对同一key有效

```
GET http://localhost:8080/api/users/sync-test-fail?keyCount=100&threads=100
```

**预期响应**：
```json
{
  "scenario": "100 个线程查询 100 个不同的 key",
  "syncProtection": "无效",
  "dbQueryCount": 100,
  "totalTime": "120.5ms",
  "lesson": "sync=true 只对同一 key 的并发查询有效，不同 key 各自独立"
}
```

**关键指标**：`dbQueryCount` 接近 **100**，因为100个不同的key各自独立，sync=true无法保护

---

### 实操11：SpEL 条件缓存

**目的**：理解 `condition`（执行前判断）和 `unless`（执行后判断）的用法

**缓存命中场景（id < 100）**：
```
GET http://localhost:8080/api/users/5/conditional
```

**预期响应**：
```json
{
  "data": { "id": 5, "name": "Eve", ... },
  "cached": true,
  "condition": "id < 100 → 满足，执行缓存"
}
```

**不缓存场景（id >= 100）**：
```
GET http://localhost:8080/api/users/150/conditional
GET http://localhost:8080/api/users/150/conditional
```

**预期**：两次请求日志中都出现 `[DB QUERY - CONDITIONAL]`，说明每次都查库，不走缓存

```json
{
  "data": null,
  "cached": false,
  "condition": "id < 100 → 不满足，跳过缓存",
  "unless": "result == null → 满足，不缓存结果"
}
```

---

### 实操12：自定义KeyGenerator（复合条件查询）

**目的**：理解如何为多参数查询生成缓存key

```
GET http://localhost:8080/api/users/search?name=Ali&age=20
```

**预期响应**：
```json
{
  "data": [
    { "id": 1, "name": "Alice", "age": 20, ... }
  ],
  "generatedCacheKey": "users:name=Ali,age=20"
}
```

再次请求相同参数，日志中不再出现 `[DB QUERY - SEARCH]`，说明缓存命中

**学习要点**：自定义 `customKeyGenerator` 生成语义化key `users:name=Ali,age=20`，比默认的SimpleKey更清晰

---

### 实操13：基础预热 vs 高级预热

**基础预热（无优先级）**：
```
POST http://localhost:8080/api/cache/warmup-simple
```

**预期响应**：
```json
{
  "loaded": 20,
  "elapsedMs": 150,
  "cacheSize": 20,
  "note": "基础预热完成！第二阶段有带优先级的高级版本"
}
```

**高级预热（按优先级分批）**：
```
POST http://localhost:8080/api/cache/warmup
```

**预期响应**：
```json
{
  "message": "预热已触发（异步执行）",
  "currentStatus": "RUNNING",
  "detail": "GET /api/cache/warmup/status 查看进度"
}
```

**查看预热进度**：
```
GET http://localhost:8080/api/cache/warmup/status
```

**预期响应**：
```json
{
  "status": "COMPLETED",
  "currentPriority": "LOW",
  "priorityStats": {
    "HIGH": { "total": 5, "loaded": 5, "failed": 0, "status": "DONE" },
    "MEDIUM": { "total": 10, "loaded": 10, "failed": 0, "status": "DONE" },
    "LOW": { "total": 5, "loaded": 5, "failed": 0, "status": "DONE" }
  },
  "totalLoaded": 20,
  "totalFailed": 0,
  "elapsedMs": 120
}
```

**学习要点**：HIGH优先加载热点用户（id 1~5），保证核心请求在预热完成前就能命中缓存

---

## 第三阶段：高级篇

### 前置：启动Redis

```bash
docker run -d --name redis-demo -p 6379:6379 redis:latest
```

验证Redis运行：
```bash
docker exec redis-demo redis-cli ping
# 返回 PONG
```

---

### 学习目标
掌握多级缓存架构、缓存三大问题防护、一致性方案、降级容错

---

### 实操14：多级缓存查询流程

**目的**：理解 L1(Caffeine) → L2(Redis) → DB 的逐级查询和回填

**第一步：清空所有缓存**
```
DELETE http://localhost:8080/api/cache/clear
```

**第二步：第一次查询（L1未命中 → L2未命中 → 查DB → 回填L1+L2）**
```
GET http://localhost:8080/api/advanced/users/1
```

**预期响应**：
```json
{
  "data": { "id": 1, "name": "Alice", ... },
  "queryTime": "5.230ms",
  "flow": "L1(Caffeine) → L2(Redis) → DB"
}
```

**日志中应出现**：
```
[DB QUERY] id=1, 查询数据库
```

**第三步：第二次查询（L1命中，纳秒级返回）**
```
GET http://localhost:8080/api/advanced/users/1
```

**预期**：`queryTime` 大幅降低（如 `0.003ms`），日志中无DB查询

**第四步：查看各级命中率**
```
GET http://localhost:8080/api/advanced/cache/level-stats
```

**预期响应**：
```json
{
  "l1": {
    "name": "Caffeine (L1)",
    "hitCount": 1,
    "missCount": 1,
    "hitRate": "50.0%",
    "avgLatency": "~0.001ms (纳秒级)"
  },
  "l2": {
    "name": "Redis (L2)",
    "hitCount": 0,
    "missCount": 1,
    "hitRate": "0.0%",
    "avgLatency": "~0.5-2ms (毫秒级)"
  },
  "db": {
    "name": "Database (L3)",
    "queryCount": 1,
    "avgLatency": "~10-50ms (毫秒级)"
  },
  "summary": "L1 缓存命中率 50.0%，L2 缓存命中率 0.0%，数据库查询次数 1",
  "redisAvailable": true
}
```

**学习要点**：L1命中率提升后，L2和DB的压力会显著下降

---

### 实操15：缓存穿透演练（三种模式对比）

**目的**：对比三种穿透防护方案的效果

**模式一：无防护**
```
POST http://localhost:8080/api/drill/penetrate?count=1000&mode=none
```

**预期响应**：
```json
{
  "mode": "none",
  "totalRequests": 1000,
  "dbQueryCount": 1000,
  "duration": "520.3ms",
  "description": "无防护：每次请求都打到数据库"
}
```

**模式二：空对象缓存**
```
POST http://localhost:8080/api/drill/penetrate?count=1000&mode=null-cache
```

**预期响应**：
```json
{
  "mode": "null-cache",
  "totalRequests": 1000,
  "dbQueryCount": 1000,
  "duration": "180.5ms",
  "description": "空对象缓存：只有第一次查库，后续命中缓存（短 TTL）"
}
```

注意：这里每个请求的fakeId都不同（100000, 100001, ...），所以每个id都是第一次查询，dbQueryCount仍为1000。但空对象缓存的意义在于：**同一个不存在的id反复查询时**，只有第一次查库。

**模式三：布隆过滤器**
```
POST http://localhost:8080/api/drill/penetrate?count=1000&mode=bloom-filter
```

**预期响应**：
```json
{
  "mode": "bloom-filter",
  "totalRequests": 1000,
  "dbQueryCount": 0,
  "bloomBlockedCount": 1000,
  "duration": "5.2ms",
  "description": "布隆过滤器：不存在的 key 直接拦截，0 次查库"
}
```

**对比总结**：

| 模式 | DB查询次数 | 耗时 | 原理 |
|------|-----------|------|------|
| none | 1000 | ~520ms | 每次都查库 |
| null-cache | 1000（首次），后续0 | ~180ms | 同一id后续命中空值缓存 |
| bloom-filter | 0 | ~5ms | 布隆过滤器判断不存在，直接拦截 |

**学习要点**：布隆过滤器在大量不存在key的场景下效果最好，返回false即"一定不存在"，直接拦截无需查库

---

### 实操16：缓存击穿演练（三种模式对比）

**目的**：对比三种击穿防护方案在并发场景下的表现

**模式一：无防护**
```
POST http://localhost:8080/api/drill/breakdown?threads=100&mode=none
```

**预期响应**：
```json
{
  "mode": "none",
  "threads": 100,
  "dbQueryCount": 100,
  "duration": "150.3ms",
  "description": "无防护：所有线程都查库（缓存击穿）"
}
```

**模式二：sync=true**
```
POST http://localhost:8080/api/drill/breakdown?threads=100&mode=sync
```

**预期响应**：
```json
{
  "mode": "sync",
  "threads": 100,
  "dbQueryCount": 1,
  "duration": "60.5ms",
  "description": "sync=true：只有一个线程查库，其他等待结果"
}
```

**模式三：逻辑过期**
```
POST http://localhost:8080/api/drill/breakdown?threads=100&mode=logic-expire
```

**预期响应**：
```json
{
  "mode": "logic-expire",
  "threads": 100,
  "dbQueryCount": 1,
  "returnOldValueCount": 99,
  "duration": "2.1ms",
  "description": "逻辑过期：返回旧值 + 单线程异步刷新"
}
```

**对比总结**：

| 模式 | DB查询次数 | 耗时 | 是否阻塞 |
|------|-----------|------|----------|
| none | 100 | ~150ms | 所有线程都阻塞等待查库 |
| sync | 1 | ~60ms | 其他线程阻塞等待第一个线程完成 |
| logic-expire | 1 | ~2ms | 不阻塞，立即返回旧值 |

**学习要点**：
- `sync=true` 只有1次查库，但其他线程需要等待（会阻塞）
- `逻辑过期` 立即返回旧值不阻塞，后台异步刷新，响应最快

---

### 实操17：缓存雪崩演练

**目的**：理解固定TTL导致同时过期的问题

**模式一：固定TTL**
```
POST http://localhost:8080/api/drill/avalanche?keyCount=50&mode=fixed-ttl
```

**模式二：随机TTL**
```
POST http://localhost:8080/api/drill/avalanche?keyCount=50&mode=random-ttl
```

**学习要点**：固定TTL下所有key同时过期，瞬间并发打到数据库；随机偏移将过期时间分散在时间窗口内，降低峰值并发

**防护代码示例**（来自 `User.java`）：
```java
// baseMinutes=30, maxOffsetMinutes=10 → 结果范围 30~40 分钟
int randomTTL = User.randomExpireOffset(30, 10);
```

---

### 实操18：异步刷新（逻辑过期 → 返回旧值）

**目的**：理解逻辑过期的工作机制

**第一步：先查询让缓存有数据**
```
GET http://localhost:8080/api/advanced/users/1
```

**第二步：使用异步刷新接口**
```
GET http://localhost:8080/api/advanced/users/1/async
```

**预期响应**：
```json
{
  "data": { "id": 1, "name": "Alice", ... },
  "queryTime": "0.005ms",
  "strategy": "逻辑过期 → 返回旧值 + 后台异步刷新",
  "note": "检查日志可以看到 [ASYNC REFRESH] 刷新记录"
}
```

**日志中应出现**：
```
[ASYNC] id=1, 缓存逻辑过期，返回旧值并异步刷新
[ASYNC REFRESH] id=1 缓存已刷新
```

**学习要点**：逻辑过期时间存在 `logicExpireTime` 字段（`@Transient` 不持久化），读取时检查是否过期，过期则返回旧值+异步刷新，调用方不阻塞

---

### 实操19：Pub/Sub 缓存一致性

**目的**：理解多实例部署下缓存一致性的实现

**第一步：广播缓存无效化**
```
POST http://localhost:8080/api/advanced/cache/invalidate/1
```

**预期响应**：
```json
{
  "message": "缓存无效化指令已广播",
  "userId": 1,
  "steps": [
    "1. 数据库已更新",
    "2. Redis 缓存已删除",
    "3. Pub/Sub 消息已广播（所有实例清除本地 L1）"
  ]
}
```

**日志中应出现**：
```
[CONSISTENCY] Pub/Sub 无效化完成: id=1
[CONSISTENCY] 收到无效化消息: user:1
[CONSISTENCY] 本地 L1 缓存已清除: id=1
```

**第二步：查看一致性方案对比**
```
GET http://localhost:8080/api/advanced/consistency/compare
```

**预期响应**（核心部分）：
```json
{
  "schemes": [
    {
      "name": "Pub/Sub 实时通知",
      "consistency": "准实时（毫秒级）",
      "pros": ["通知及时", "所有实例同步失效", "实现成熟"],
      "cons": ["依赖 Redis Pub/Sub 可靠性", "网络抖动可能丢消息"],
      "suitableFor": "多实例部署、对一致性要求较高"
    },
    {
      "name": "版本号比对",
      "consistency": "读时校验",
      "pros": ["实现简单", "不依赖额外中间件"],
      "cons": ["每次读取都要查 Redis 版本号"],
      "suitableFor": "实例少、读多写少的场景"
    },
    {
      "name": "TTL 兜底",
      "consistency": "最终一致（过期后一致）",
      "pros": ["零额外开发", "零额外依赖"],
      "cons": ["过期窗口内数据不一致"],
      "suitableFor": "对一致性要求不高的参考数据"
    }
  ],
  "recommendation": "生产环境建议: Pub/Sub + TTL 兜底组合使用"
}
```

---

### 实操20：Redis 宕机降级演练

**目的**：验证Redis不可用时服务不中断

**第一步：模拟Redis宕机**
```
POST http://localhost:8080/api/drill/redis-down
```

**预期响应**：
```json
{
  "status": "Redis 已模拟宕机",
  "degradationStrategy": "L1(Caffeine) + DB 兜底",
  "impact": "性能会下降（L2缓存不可用），但服务不中断",
  "nextStep": "GET /api/advanced/users/1 验证降级后仍能正常响应",
  "recoveryStep": "POST /api/drill/redis-recover 恢复 Redis"
}
```

**第二步：验证降级后服务正常**
```
GET http://localhost:8080/api/advanced/users/1
```

**预期**：仍然正常返回用户数据，只是L2（Redis）层不可用，降级为L1+DB

```
GET http://localhost:8080/api/advanced/cache/level-stats
```

**预期**：`redisAvailable: false`

**第三步：恢复Redis**
```
POST http://localhost:8080/api/drill/redis-recover
```

**预期响应**：
```json
{
  "status": "Redis 已恢复",
  "syncedKeys": 0,
  "pendingKeysBeforeRecover": 0,
  "note": "降级期间无写入操作，无需同步"
}
```

**第四步：验证数据一致性**
```
GET http://localhost:8080/api/drill/verify-consistency?id=1
```

**预期响应**：
```json
{
  "userId": 1,
  "dbName": "Alice",
  "l1CaffeineName": "Alice",
  "consistent": true
}
```

---

### 实操21：查看演练结果汇总

```
GET http://localhost:8080/api/drill/result
```

**预期响应**：
```json
{
  "totalDrills": 10,
  "results": [
    { "drillType": "缓存穿透", "mode": "none", ... },
    { "drillType": "缓存穿透", "mode": "null-cache", ... },
    { "drillType": "缓存穿透", "mode": "bloom-filter", ... },
    { "drillType": "缓存击穿", "mode": "none", ... },
    { "drillType": "缓存击穿", "mode": "sync", ... },
    { "drillType": "缓存击穿", "mode": "logic-expire", ... },
    ...
  ]
}
```

---

## 完整接口清单（按学习顺序）

### 第一阶段（15个接口）
```
GET    /api/users/{id}                    # 查询用户（默认writeCache）
GET    /api/users/{id}?cacheType=access   # 查询用户（accessCache）
GET    /api/users/{id}/manual             # 手动缓存方式
GET    /api/users/{id}/debug              # Debug模式
POST   /api/users                         # 新增用户
PUT    /api/users                         # 更新用户（@CacheEvict）
DELETE /api/users/{id}                    # 删除用户
GET    /api/cache/stats                   # 全局命中率统计
GET    /api/cache/stats/{key}             # 按key查看状态
GET    /api/cache/size                    # 缓存大小
GET    /api/cache/timeline/{id}           # 时间线
DELETE /api/cache/clear                   # 清空所有缓存
GET    /api/cache/evict-demo?maxSize=3    # W-TinyLFU淘汰演示
POST   /api/cache/warmup-simple           # 基础预热
```

### 第二阶段（+8个接口）
```
PUT    /api/users/update-v2               # @CachePut 更新
PUT    /api/users/update-v3               # @CacheEvict 更新（对比）
GET    /api/users/{id}/sync-test          # sync=true 并发保护
GET    /api/users/sync-test-fail          # sync=true 失效演示
GET    /api/users/search?name=&age=       # 复合条件查询
GET    /api/users/{id}/conditional        # SpEL条件缓存
POST   /api/cache/warmup                  # 高级预热
GET    /api/cache/warmup/status           # 预热进度
```

### 第三阶段（+20个接口）
```
GET    /api/advanced/users/{id}           # 多级缓存查询
GET    /api/advanced/users/{id}/async     # 异步刷新
GET    /api/advanced/cache/level-stats    # 各级命中率
POST   /api/advanced/cache/invalidate/{id}  # Pub/Sub一致性删除
GET    /api/advanced/consistency/compare  # 一致性方案对比
POST   /api/drill/penetrate               # 穿透演练
POST   /api/drill/breakdown               # 击穿演练
POST   /api/drill/avalanche               # 雪崩演练
POST   /api/drill/redis-down              # 模拟Redis宕机
POST   /api/drill/redis-recover           # 模拟Redis恢复
GET    /api/drill/verify-consistency      # 验证一致性
GET    /api/drill/result                  # 演练结果汇总
GET    /api/benchmark/compare?n=1000      # 基准测试对比
```

---

## 学习检查清单

完成以下操作即表示掌握该项目核心知识点：

**基础篇**
- [ ] 能观察到缓存命中（第二次查询无DB日志）
- [ ] 能对比两种过期策略的行为差异
- [ ] 能理解Debug模式中 `penetrationRisk` 的含义
- [ ] 能看懂W-TinyLFU淘汰实验的结果

**进阶篇**
- [ ] 能对比 `@CachePut` 和 `@CacheEvict` 的行为差异
- [ ] 能理解 `sync=true` 的生效条件（同一key）和失效条件（不同key）
- [ ] 能解释 `condition` 和 `unless` 的执行时机区别
- [ ] 能理解预热的优先级设计（HIGH → MEDIUM → LOW）

**高级篇**
- [ ] 能画出 L1 → L2 → DB 的查询流程图
- [ ] 能对比穿透防护三种方案的效果数据
- [ ] 能对比击穿防护三种方案的阻塞性能差异
- [ ] 能解释逻辑过期为什么不阻塞调用方
- [ ] 能理解Pub/Sub一致性删除的完整流程
- [ ] 能验证Redis降级后服务不中断