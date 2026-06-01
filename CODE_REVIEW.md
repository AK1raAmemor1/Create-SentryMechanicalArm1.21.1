# 代码审查报告：Create-SentryMechanicalArm-1.21.1-neoforge

审查日期：2026-06-01（二次审查）
审查范围：所有源文件（`euphy/upo/sentrymechanicalarm/` 包）

---

## 严重等级说明
- **P0 (Critical)** — 可能导致崩溃、数据损坏或安全漏洞
- **P1 (High)** — 可能导致功能异常或性能严重下降
- **P2 (Medium)** — 潜在问题，在特定条件下可能引发故障
- **P3 (Low)** — 代码风格、可维护性问题或轻微性能影响

---

## P0 — 严重问题（二次审查确认全部未修复）

### 1. 渲染线程与逻辑线程之间的数据竞争
所有字段为 `public/private` 非 `volatile`，渲染线程（`SentryArmRenderer`）与服务器 tick 线程（`SentryArmBlockEntity.tick`）之间无任何同步。

#### 1.1 `attachedAmmoBoxes`
- **文件**: `content/SentryArmBlockEntity.java:98`
- **声明**: `public final NonNullList<ItemStack> attachedAmmoBoxes`
- **写入**: 服务器 tick（L204-216, 242-249, 302-314 等）
- **读取**: 渲染线程（`SentryArmRenderer.java:268` 直接访问 `be.attachedAmmoBoxes`）
- **风险**: `ArrayList` 结构性修改 → `ConcurrentModificationException` 或读到不一致状态
- **等级**: **P0**

#### 1.2 `heldItem`
- **文件**: `content/SentryArmBlockEntity.java:97`
- **声明**: `private ItemStack heldItem`（含 getter/setter）
- **写入**: 服务器 tick（L167-172, 260 等处）
- **读取**: 渲染线程（`SentryArmRenderer.java:76` — `be.getHeldItem()`）
- **风险**: 非 `volatile`，渲染线程可能看到过时引用
- **等级**: **P0**

#### 1.3 `lastShootTime`
- **文件**: `content/SentryArmBlockEntity.java:100`
- **声明**: `private long lastShootTime`（含 getter L174-176 / setter L362-364）
- **写入**: 服务器 tick（L1109, 1577, 362）
- **读取**: 渲染线程（`SentryArmRenderer.java:191` — `renderMuzzleFlash`, L811 — `renderMuzzleFlashStatic`）
- **风险**: 非 `volatile`，渲染线程可能无限期读不到新值（开枪后不显示枪口火焰）
- **等级**: **P0**

#### 1.4 `color`
- **文件**: `content/SentryArmBlockEntity.java:103`
- **声明**: `public Optional<DyeColor> color`
- **写入**: 逻辑 tick（L1782-1794 `applyColor`）
- **读取**: 渲染线程多处（`SentryArmRenderer.java:182, 572, 630` 等）
- **风险**: 非 `volatile`，可能读到对空 `Optional` 的引用 → NPE
- **等级**: **P0**

#### 1.5 `shouldEjectShell`
- **文件**: `content/SentryArmBlockEntity.java:91`
- **声明**: `private boolean shouldEjectShell`（含 getter L159 / setter L161）
- **写入**: 服务器 tick（L1578 `triggerShootEffects`）
- **读取**: 渲染线程（`SentryArmRenderer.java:140` — `be.shouldEjectShell()`）
- **风险**: 非 `volatile`，渲染线程可能永远看不到 `true`，子弹壳不弹出
- **等级**: **P0**

### 2. 空 catch 块吞没异常

#### 2.1 `renderInContraption()` — 完全静默
- **文件**: `content/SentryArmRenderer.java:708-709`
- **代码**: `} catch (Exception e) {}`
- **风险**: 整个 contraption 渲染被静默吞没
- **等级**: **P0**

#### 2.2 `SentryArmBlockEntity.tick()` — 完全静默
- **文件**: `content/SentryArmBlockEntity.java:317-318`
- **代码**: `} catch (Exception ignored) {}`
- **风险**: 假玩家 tick 中弹药耗尽、枪械数据损坏等关键错误被无声吞没
- **等级**: **P0**

### 3. 结构性内存泄漏 — `CONTRAPTION_FAKE_PLAYERS`
- **文件**: `util/SentryFakePlayer.java:29`
- **声明**: `static final HashMap<BlockPos, SentryFakePlayer> CONTRAPTION_FAKE_PLAYERS`
- **代码**: `removeForContraption()` 方法定义了（L105-111）但**从未被任何代码调用**
- **风险**: 结构被拆卸后假玩家永远驻留在 `HashMap` 中，每个持有一个完整的 `ServerLevel`、`PlayerList`、`Connection` → 严重泄漏
- **等级**: **P0**

### 4. 反射滥用导致模组脆弱
共发现 **8 处**反射调用，其中 6 处使用 `setAccessible(true)`。

#### 4.1 `DynamicRecipeManager.java` — 访问 `RecipeManager.byType`
- **行号**: 210, 325-326, 346-347
- **代码**: `RecipeManager.class.getDeclaredField("byType")` + `setAccessible(true)`
- **风险**: 3 处相同反射，MC 版本更新或 JDK 模块系统限制会破坏
- **等级**: **P0**

#### 4.2 `SawBlockEntityMixin.java` — 访问 `RecipeManager.byType`
- **行号**: 79-80
- **代码**: `RecipeManager.class.getDeclaredField("byType")` + `setAccessible(true)`（与 4.1 相同模式）
- **风险**: 第 4 处访问 `RecipeManager.byType` 的反射，横跨 2 个文件
- **等级**: **P0**

#### 4.3 `ArmSoundHelper.java` — 访问 TaCZ `AnimationController.prototypes`
- **行号**: 48-49
- **代码**: `AnimationController.class.getDeclaredField("prototypes")` + `setAccessible(true)`
- **风险**: 依赖 TaCZ 内部实现名，更新时极易断裂
- **等级**: **P0**

#### 4.4 `DeployerBlockEntityMixin.java` — 访问 `DeployerBlockEntity$Mode` 和 `DeployerHandler.activate`
- **行号**: 51, 61-67
- **代码**: `Class.forName("...DeployerBlockEntity$Mode")` 枚举常量遍历 + `activateMethod.setAccessible(true)`
- **风险**: 反射访问私有内部枚举和方法。`modeUse` 静态缓存字段非 `volatile`
- **等级**: **P0**

#### 4.5 `NetworkHandler.java` — 反射派发客户端处理器
- **行号**: 88-95
- **代码**: `Class.forName("...ClientPacketHandler")` + `getMethod(methodName, ...)` + `invoke(null, ...)`
- **风险**: 若 `ClientPacketHandler` 被重命名或移动，静默失败（被 catch 吞没为 error 日志）
- **等级**: **P2**（非 `setAccessible`，但有更好替代方案如 Map 派发）

### 5. `TargetPool` 无锁静态 HashMap 多线程访问
- **文件**: `util/TargetPool.java:10-11`
- **声明**: `static HashMap<Integer, String>` — 非线程安全静态可变状态
- **访问**: 服务器 `tick()`（服务器线程）+ `SentryMovementBehaviour.tick()`（客户端/网络线程）同时无锁访问
- **风险**: `put`/`remove` 多线程操作 → 死循环（CPU 100%）或 NPE
- **等级**: **P0**

---

## P1 — 高危问题（二次审查确认全部未修复）

### 6. 渲染路径频繁分配对象

#### 6.1 `renderSafe()` — 每次渲染新建 `PoseStack`
- **文件**: `content/SentryArmRenderer.java:92`
- **风险**: 每个哨戒臂每帧创建一个新的 `PoseStack`
- **等级**: **P1**

#### 6.2 `tryManualEject()` — 大量分配
- **文件**: `content/SentryArmRenderer.java:461-545`
- **代码**: 每帧分配 `ArrayList`、`PoseStack`（3个）、`Vector4f`（3个）、`Matrix4f`、`Vector3f`（2个）、`Vec3`
- **等级**: **P1**

#### 6.3 `renderHeldItem` — 多处分配
- **文件**: `content/SentryArmRenderer.java:729-774`
- **代码**: `PoseStack`（L729, 760）、`Vector4f`（L754, 685）、`Quaternionf`（L762, 694）
- **等级**: **P1**

#### 6.4 `renderInContraption` — 多处分配
- **文件**: `content/SentryArmRenderer.java:657, 684, 685, 692-697`
- **代码**: `Matrix4f`（L657, 684）、`Vector4f`（L685）、`PoseStack`（L692）、`Quaternionf`（L694）
- **等级**: **P1**

### 7. `ClientPacketHandler` 类型混淆
- **文件**: `network/ClientPacketHandler.java:97`
- **代码**: `if (context.temporaryData instanceof SentryArmBlockEntity sentry)`
- **问题**: `temporaryData` 实际运行时类型为 `VirtualSentryArmBlockEntity`（继承自 `SentryArmBlockEntity`）。`instanceof` 返回 true，目前调用的 `setLastShootTime` / `triggerShootEffects` 均为继承方法，工作正常。但若未来添加 `Virtual` 专属方法则存在风险，且阅读代码时语义混淆
- **注意**: `renderInContraption` L551 正确使用了 `instanceof VirtualSentryArmBlockEntity`
- **等级**: **P1**

### 8. （已合并至 P0 #1.5）

### 9. `Integer` 溢出风险
- `triggerHoldTime`（L106）和 `markedPosShotCounter`（L77）等 `int` 实例字段存在长时间累加后溢出风险
- **风险**: 当前逻辑在达到阈值后会重置，但极端运行时间下（数小时不重启）仍有可能溢出
- **等级**: **P2**

### 10. 静态列表无限增长风险
共计 **3 处**静态 `ArrayList` 存在无界增长风险：

#### 10.1 `SentryShellManager.shells`
- **文件**: `util/SentryShellManager.java:31`
- **风险**: 若 `removeIf` 条件异常或 `System.currentTimeMillis()` 回跳，shell 永不移除

#### 10.2 `SentryTrailManager.tracers`
- **文件**: `util/SentryTrailManager.java:25`
- **风险**: `addTracer()`（L46-48）无容量上限，若客户端 tick 停滞则列表可无限增长

#### 10.3 `SentryShellManager.shells` / `SentryTrailManager.tracers` 共同问题
- 两个列表均使用 `static final ArrayList`，无容量上限，无溢出保护
- **等级**: **P1**

---

## P2 — 中等风险

### 11. 空 catch 块仅 `printStackTrace`
| 文件 | 行号 | 代码 |
|---|---|---|
| `content/SentryArmRenderer.java` | 794-795 | `catch (Exception e) { e.printStackTrace(); }` |
| `util/ArmSoundHelper.java` | 84-86 | `catch (Exception e) { e.printStackTrace(); }` |
| `mixin/DeployerBlockEntityMixin.java` | 77-79 | `catch (Exception e) { e.printStackTrace(); }` |
- **风险**: `printStackTrace()` 在 Modded Minecraft 日志系统中可能被重定向

### 12. 硬编码值应配置化
| 文件 | 行号 | 值 | 建议 |
|---|---|---|---|
| `content/SentryArmBlockEntity.java` | 359 | MAX_RANGE = 256.0 | 配置 |
| `content/SentryArmBlockEntity.java` | 539-549 | 转向系数 0.44f, 0.22f, 0.19f | 配置 |
| `content/SentryArmBlockEntity.java` | 392 | 目标重扫间隔 = 300 tick | 配置 |
| `content/SentryLinkHandler.java` | 121 | 连接距离 = 36.0 | 配置 |
| `content/SentryArmRenderer.java` | 138 | 枪械缩放 = 1.5f | 配置或模型参数 |
| `content/SentryArmRenderer.java` | 361, 923 | 光照值 = 15728880 | 应使用 `LightTexture.FULL_BRIGHT` |

### 13. `FireControlMovementBehaviour.getTargetAngle()` — 潜在 NPE
- **文件**: `content/FireControlMovementBehaviour.java:127`
- **代码**: `Vec3 localVector = context.contraption.entity.reverseRotation(vectorToPlayer, 1);`
- **风险**: 仅检查了 `context.position != null`（L125），未对 `context.contraption` 或 `context.contraption.entity` 做 null 检查。若 contraption 为 null 则 NPE
- **等级**: **P2**

### 14. 弃用 API 使用
- **`BlazeFireControlBlockItem.java:40-51`**: `@SuppressWarnings("removal")` + `initializeClient` — NeoForge 1.21+ 标记为移除
- **`BlazeFireControlItemRenderer.java:43`**: `RenderSystem.setShader(GameRenderer::getPositionTexShader)` — 已弃用
- **`SentryHudHandler.java:82`**: 同上

### 15. ~~`UnfinishedAmmoItem.getAmmoId()` 返回 null~~（非问题，已移除）
- `getAmmoId()` 返回 `null` 是设计行为（未完成品无弹药 ID）
- 所有 3 个调用者均做了 null 检查：`SequencedAssemblyRecipeMixin.java:37`（`if (recipeAmmoId == null)`）、`SawBlockEntityMixin.java:48`（`if (filterAmmoId == null) return`）、`PressingRecipeMixin.java:30`（`if (recipeAmmoId == null) return`）
- **非问题，移除**

### 16. `LuaDataSnapshot` 字符串构建性能
- **文件**: `content/SentryArmBlockEntity.java:1839-1862`
- **风险**: 每 tick 遍历 Lua 表构建字符串 — 产生大量临时字符串
- **等级**: **P2**

### 17. `SentryTargetSavedData` 暴露可变内部状态
- **文件**: `util/SentryTargetSavedData.java:43-44`
- **代码**: `getTargets()` 返回内部 `HashSet` 的直接引用
- **风险**: 调用方可修改内部集合。虽然 `SavedData` 通常在服务器单线程中访问，但若未来添加异步操作则产生线程安全问题
- **等级**: **P2**

### 18. `IArmAmmoStorage` 接口与实现不匹配
- **文件**: `util/IArmAmmoStorage.java:8`
- **接口**: `setAmmoBox(ItemStack)` — 仅一个参数
- **实现**: `SentryArmBlockEntity.java:242-249` — 调用 `attachedAmmoBoxes.clear(); set(0, stack); set(1, EMPTY)`
- **风险**: BE 支持 2 个弹药箱槽位，但接口只支持传入 1 个，第二个静默丢弃。`clear()` + `set(1, EMPTY)` 模式可能触发 `ConcurrentModificationException`（在 `attachedAmmoBoxes` 同步保护缺失下）
- **等级**: **P2**

---

## P3 — 低风险 / 代码风格

### 19. 缺失的文档
- `AeronauticsHelper.java` 方法参数缺少 `@Nullable` 标注
- `SentryFakePlayer.java` 各方法缺少线程安全说明

### 20. 注释代码
- `SentryDebugHelper.java` 整体被注释 — 应清理或恢复
- `ArmSoundHelper.java:78` — `soundChannel.playSound(...)` 被注释

### 21. 静态初始化顺序不明确
- `SentrySpriteShifts.java:16-31` 在静态初始化器中调用 `SpriteShifter.get()`，可能过早初始化

### 22. `for` 循环方式
- `content/SentryArmBlockEntity.java` 多处使用 `for (int i = 0; i < ...; i++)` 遍历 `NonNullList`，可改为增强型 `for-each`

### 23. `VirtualSentryArmBlockEntity.tick()` 为空
- **文件**: `content/VirtualSentryArmBlockEntity.java:38-39`
- **风险**: Contraption 中虚拟 BE 的 `tick()` 为空，所有数据依赖 NBT 快照。设计上可接受，但易造成开发者误解
- **等级**: **P3**

### 24. `SentryMovementBehaviour.scanForTarget` LOGGER 级别不当
- **文件**: `content/SentryMovementBehaviour.java:921-924, 956-958, 967-970, 982-985, 990-993`
- **风险**: 目标扫描循环中使用 `LOGGER.info` 记录每实体过滤原因。战斗中频繁触发 → 控制台大量日志输出，性能开销（字符串格式化 + 文件 I/O）
- **建议**: 改为 `LOGGER.debug` 或仅在调试模式下启用
- **等级**: **P3**

---

## 修复优先级建议

### 立即修复 (P0)
1. **数据竞争修复** — `attachedAmmoBoxes` 改用 `CopyOnWriteArrayList` 或渲染前快照；`heldItem`、`lastShootTime`、`color`、`shouldEjectShell` 添加 `volatile` / `AtomicReference`
2. **空 catch 块** — 对 L708（renderInContraption）和 L317（tick）添加 `LOGGER.error` 日志
3. **`CONTRAPTION_FAKE_PLAYERS` 泄漏** — 在 `SentryMovementBehaviour.stopMoving()` / `onBlockBroken()` 等回调中调用 `removeForContraption()`
4. **`TargetPool` 线程安全** — 改用 `ConcurrentHashMap` 或添加 `synchronized`
5. **全部 8 处反射** — 用 Mixin 或公开 API 替换 `setAccessible`（`DynamicRecipeManager` x3, `SawBlockEntityMixin` x1, `ArmSoundHelper` x1, `DeployerBlockEntityMixin` x2, `NetworkHandler` x1）

### 短期修复 (P1)
6. **渲染对象池化** — `PoseStack`、`Vector4f`、`Matrix4f`、`Quaternionf` 使用 `ThreadLocal` 回收复用
7. **类型混淆修正** — `ClientPacketHandler.java:97` 使用 `VirtualSentryArmBlockEntity` 检查或添加明确标记
8. **`shells` / `tracers` 列表上限** — 添加最大容量（如 500/1000）和溢出清理策略
9. **`Integer` 溢出防护** — 对 `triggerHoldTime`、`markedPosShotCounter` 进行范围饱和检查

### 中期修复 (P2)
10. **反射替换（P2 级）** — `NetworkHandler` 反射派发替换为显式 Map 或 if-else 链
11. **配置化** — 关键参数提取到配置文件
12. **弃用 API 替换** — 更新着色器调用
13. **`IArmAmmoStorage` 接口修复** — 改为支持多槽位或明确限制为 1 槽
14. **`FireControlMovementBehaviour.getTargetAngle()`** — 对 `context.contraption.entity` 添加 null 检查
15. **`SentryTargetSavedData.getTargets()`** — 返回不可变集合副本 `Set.copyOf(targetBlocks)`
