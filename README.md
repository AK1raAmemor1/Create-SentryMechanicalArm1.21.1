# Create: Sentry Mechanical Arm（非官方移植）

一个 Minecraft NeoForge 模组，让 Create 动力臂能够装备 [TaCZ](https://github.com/tacz-dev/tacz)（Timeless and Classics: Zero）枪械，作为自动化防御炮塔使用。

## 功能特性

- **自动索敌** — 哨戒臂在范围内扫描敌对实体，锁定目标并以平滑关节运动追踪。
- **完整 TaCZ 兼容** — 支持所有 TaCZ 枪械：半自动、连射、全自动、栓动、充能武器。
- **向量枪口计算** — 利用手臂关节角度 + 枪械模型骨骼数据精确计算子弹射出位置，远距离瞄准无偏差。
- **实体子弹武器适配** — 自动识别 RPG/火箭/榴弹等有实体模型子弹的武器，跳过冗余 tracer，只保留 TACZ 原生弹道。
- **火控系统** — 连接烈焰火控台，可跨多个哨戒臂统一管理目标白名单/黑名单。
- **聚焦瞄准** — 使用哨戒瞄准镜指定优先目标，所有已连接哨戒集中攻击。
- **弹药管理** — 为哨戒臂附加弹药箱实现自动补给，弹药可用时即时装填。
- **物理结构兼容** — 完整支持 **Sable**（Create: Aeronautics）飞船座舱姿态、子世界坐标变换。
- **静态 + Contraption 双状态** — 支持普通方块放置和 Create 动态结构（传送带/矿车/飞船）安装。
- **TACZ 弹药配方兼容** — 机械臂单次序列组装消耗一个物品，通过多次组装平衡，工作台用子弹和铜板合成待加工子弹作为过滤模板。

## 前置模组

| 模组 | 要求 |
|-----------|----------|
| [NeoForge](https://neoforged.net) 21.1.93+ | ✅ 必需 |
| [Create](https://modrinth.com/mod/create) 6.0.10+ | ✅ 必需 |
| [TaCZ](https://modrinth.com/mod/tacz) 1.1.8+ | ✅ 必需 |
| [Sable Companion](https://github.com/ryanhcode/sable-companion) 1.6.0+ | 仅运行时（Sable 飞船需要） |

## 操作键位

| 操作 | 输入 |
|--------|-------|
| 放入/取出枪械 | 右键哨戒臂 |
| 附加弹药箱 | 手持弹药箱右键 |
| 聚焦瞄准目标 | 瞄准镜 + 左键目标 |
| 配置火控 | 手持剪贴板右键火控台 |
| 调整射程 | 空手在哨戒臂上滚轮 |

## 坐标系说明（Sable 飞船）

| 操作 | 坐标空间 |
|-----------|-----------------|
| 实体扫描 AABB | **世界**（从子世界投影） |
| 角度计算 | **世界**（枪口和目标都在世界空间） |
| 视线检测 clip | **子世界**（转换后执行 level.clip） |
| 子弹生成 | **世界**（FakePlayer 放置在投影后位置） |

## 技术架构

### 子弹位置计算

```
服务端 fireGun():
  普通枪械 → getMuzzlePosition(armLen)  → FP 移到枪口 - 眼高
  实体子弹 → 不移动 FP，保持 sync() 位置
  → operator.shoot() → EntityKineticBullet 构造函数
  → y = getY() + (getY() - yOld)/2 + getEyeHeight()

客户端 tracer:
  → calculateExactMuzzle() 从 TACZ BedrockGunModel.muzzleFlashPosPath
  → 完整变换链（关节 → 枪械 → 握持 → 缩放 → 枪口）
  → hasEntityBullet() 判断是否跳过
```

### 检测分类（`hasEntityBullet`）

| 类别 | 示例 | 行为 |
|------|------|------|
| 动能枪械 | 步枪/手枪/SMG/霰弹/机枪/狙击/迷你枪 | 向量枪口 + tracer |
| 实体子弹 | RPG/火箭/榴弹/发射器/非动能武器 | 固定位置，跳过 tracer |

## 开发

```bash
./gradlew build          # 编译打包
./gradlew runClient      # 启动游戏客户端
./gradlew runServer      # 启动专用服务器
```

## 版本历史

- **v1.3.4** — 向量枪口计算、Contraption 适配、hasEntityBullet 检测、渲染稳定性修复、Mixin 隔离 TACZ 动画状态机、无网络同步简化架构。
- **v1.0.0** — 正式版：Sable 兼容，坐标空间修复，全物品配方
- **v0.5.0** — Sable（Aeronautics 子世界）兼容，坐标空间修复
- **v0.4.0** — 初始 NeoForge 1.21.1 移植

## 许可

MIT © Euphy
