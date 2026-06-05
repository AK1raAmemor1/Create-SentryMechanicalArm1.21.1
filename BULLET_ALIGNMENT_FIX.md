# 子弹位置对齐修复记录

## 问题描述

哨戒臂发射的 TACZ 子弹实体和客户端 tracer 与枪械模型枪口位置不对齐，远距离索敌因枪口/瞄准坐标偏差导致命中率低。同时存在双重枪线（tracer + TACZ 子弹实体两条视觉轨迹）。

**主要症状：**
- 子弹从模型枪口上方/侧方约 1.5 格偏移发出
- 有实体模型子弹的武器（RPG/榴弹/火箭）出现双重枪线
- 远距离目标命中率异常低
- Contraption 动平台上子弹位置完全不对齐

## 根因

### 1. TACZ 子弹生成位置公式

通过反编译 `EntityKineticBullet` 构造函数，确认 TACZ 子弹最终位置计算公式：

```java
// EntityKineticBullet 构造函数末尾
x = xOld + (getX() - xOld) / 2.0
y = getY() + (getY() - yOld) / 2.0 + getEyeHeight()  // 眼高参与插值
z = zOld + (getZ() - zOld) / 2.0
```

FakePlayer 位置通过 `setPos(x, y, z)` 设置时，**必须同步更新 `xo/yo/zo/xOld/yOld/zOld`**，否则插值使子弹落在新旧位置的中间点。

### 2. 索敌计算与子弹起点不一致

`getActualMuzzlePos()` 返回 `fp.getEyePosition()`（区块中心+偏移动），而子弹实际从 `getMuzzlePosition(armLen)`（手臂末端）生成，远程角度偏差导致脱靶。

### 3. 有实体模型子弹的武器双重轨迹

TACZ 对 RPG/火箭等武器会生成一个可见的子弹实体（火箭模型），同时哨戒臂的 `SentryTrailManager.addTracer()` 添加一条粒子线，两条轨迹起点不同。

## 修复方案

### 服务端 `fireGun()`

```java
// SentryArmBlockEntity.fireGun()
boolean hasEntityBullet = SentryFakePlayer.hasEntityBullet(heldItem);

if (!hasEntityBullet) {
    double armLen = SentryFakePlayer.getGunArmLength(heldItem);
    Vec3 muzzlePos = SentryFakePlayer.getMuzzlePosition(this, targetYaw, targetPitch, armLen);
    double fpY = muzzlePos.y - fakePlayer.getEyeHeight();
    fakePlayer.setPos(muzzlePos.x, fpY, muzzlePos.z);
    // ↓ 关键：全部 6 个位置字段同步更新
    fakePlayer.xo = muzzlePos.x;
    fakePlayer.yo = fpY;
    fakePlayer.zo = muzzlePos.z;
    fakePlayer.xOld = muzzlePos.x;
    fakePlayer.yOld = fpY;
    fakePlayer.zOld = muzzlePos.z;
}

operator.shoot(() -> targetPitch, () -> targetYaw);
// TACZ 引擎根据上述 6 个字段插值后: y = fpY + 0 + 1.62 = muzzlePos.y ✓

if (!hasEntityBullet) {
    // 恢复
    fakePlayer.setPos(originalPos);
    fakePlayer.xo = originalPos.x; // 全部 6 个
}
```

### 索敌计算 `getActualMuzzlePos()`

```java
public Vec3 getActualMuzzlePos() {
    // 地面: yaw = 180 - baseAngle, pitch = -headAngle
    // 天花板: yaw = baseAngle, pitch = headAngle
    double armLen = getGunArmLength(heldItem);
    if (hasEntityBullet(heldItem)) {
        return getMuzzlePosition(this, yaw, pitch, 0).add(0, 1.62, 0);
    }
    return getMuzzlePosition(this, yaw, pitch, armLen);
}
```

`calculateTruthAngle()` → `getActualMuzzlePos()` → 瞄准向量 `targetPos - muzzlePos`，确保瞄准起点 = 子弹起点。

### `hasEntityBullet()` 检测

反向匹配，检测非动能枪械 ID 路径：

```java
public static boolean hasEntityBullet(ItemStack gunStack) {
    String path = gunId.getPath().toLowerCase();
    if (path.contains("rifle") || path.contains("pistol") || path.contains("smg")
            || path.contains("shotgun") || path.contains("carbine") || path.contains("dmr")
            || path.contains("lmg") || path.contains("machine_gun") || path.contains("marksman")
            || path.contains("sniper") || path.contains("revolver") || path.contains("handgun")
            || path.contains("mini_gun") || path.contains("minigun") || path.contains("blaster"))
        return false;
    return true; // RPG/火箭/榴弹/发射器/非动能武器
}
```

## 涉及文件

| 文件 | 变更 |
|------|------|
| `SentryArmBlockEntity.java` | `fireGun()`: 6 位置字段 + hasEntityBullet 检测<br>`getActualMuzzlePos()`: 向量计算，实体子弹回退<br>`sendShootPacket()`: 清理 exactMuzzle 引用<br>删除 `exactMuzzle` 字段 |
| `SentryFakePlayer.java` | 新增 `hasEntityBullet()` / `getContraptionLocalMuzzle()`<br>调整 armLength 常量 |
| `ClientPacketHandler.java` | `calculateExactMuzzle()` 完整变换链<br>SHOOT/Contraption: hasEntityBullet 跳过 tracer |
| `SentryMovementBehaviour.java` | `tickClientLogic/tickServerTargeting/handleClientShootPacket` 改用 `getContraptionLocalMuzzle()`<br>`fireGunInContraption`: 实体子弹不移动 FP<br>`scanForTarget` Aeronautics 分支用向量计算 |
| `SentryMuzzleSyncPacket.java` | **删除**（不再需网络同步） |
| `GunDisplayInstanceAccessor.java` | 新增 Mixin，隔离 TACZ 动画状态机 |

## 架构示意

```
服务端 fireGun():
                           +-- getGunArmLength(gun)
                           |       ↓
  SentryFakePlayer.sync()  →  hasEntityBullet?
                           |   ├─ true  → 不移动 FP
                           |   └─ false → getMuzzlePosition(yaw, pitch, armLen)
                           |               → FP.setPos(muzzle.x, muzzle.y-1.62, muzzle.z)
                           |               → xo/yo/zo/xOld/yOld/zOld = muzzle
                           ↓
                    operator.shoot()
                           ↓
              EntityKineticBullet ctor:
              y = getY() + (getY()-yOld)/2 + eyeHeight
              = (muzzle.y-1.62) + 0 + 1.62
              = muzzle.y ✓

客户端 handleSentryShoot():
  SentryShootPacket → SentryArmBlockEntity
     ↓
  calculateExactMuzzle(sentry, gunStack):
    区块世界 → 手臂关节(base→lower→upper→head→claw)
           → 枪械定位(rotate/translate/scale)
           → TACZ handOriginPath
           → TACZ displayScale
           → TACZ muzzleFlashPosPath
     ↓
  Vec3 exactMuzzle
     ↓
  hasEntityBullet?  ├─ true  → 跳过 SentryTrailManager.addTracer
                    └─ false → tracer 从 exactMuzzle 开始
```
