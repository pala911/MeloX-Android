# MeloX 应用图标调整需求

## 目标效果

应用图标中的**白色音符图形等比缩小**，**红色背景区域相应扩大**（视觉占比变大）。

## 关键约束（必须全部满足）

1. **整体图标尺寸不变**：不是把整个图标缩小，只是白色音符在图标内的占比变小
2. **背景保留原图的质感红**：原图标的红色背景来自 `ic_launcher_adaptive_foreground.png` 内的圆盘（带浮雕/渐变质感），**不是**纯色 `#E7192A`。缩小音符后露出的区域必须是这个质感红，不能出现纯色补丁或色差
3. **白色音符缩小后保持居中**，等比缩放（不变形）

## 现状文件结构

```
android/app/src/main/res/
├── drawable-nodpi/
│   └── ic_launcher_adaptive_foreground.png   # 1024×1024 RGBA，透明底
│       内容 = 质感红圆盘（直径约 681px，居中）
│              + 白色音符（约 607×604px，居中，带浮雕阴影）
├── values/
│   └── ic_launcher_background.xml            # 纯色 #E7192A（被前景圆盘盖住）
└── mipmap-anydpi-v26/
    ├── ic_launcher.xml                        # adaptive icon（background + foreground）
    └── ic_launcher_round.xml                  # 同上（圆形变体）
```

## 失败方案（不要再用）

**`<inset>` 包裹 foreground 缩小**：

```xml
<foreground>
    <inset android:drawable="@drawable/ic_launcher_adaptive_foreground"
           android:inset="15%" />
</foreground>
```

问题：foreground PNG 本身包含红色圆盘（不是透明底纯音符），inset 会把**圆盘一起缩小**，四周露出 background 层的纯色 `#E7192A`，与质感红圆盘产生明显色差。

## 推荐实现方向

把 foreground PNG **拆成两层**后再缩放：

1. **背景层**：质感红圆盘（把音符区域擦除/补全后的红色圆盘，可铺满画布）
2. **前景层**：白色音符（透明底 PNG，含浮雕阴影细节）

然后只缩小前景层（PNG 重绘或 XML inset 均可，因为此时前景只剩音符）。

分层提取要点（参考）：
- 音符区域识别：低饱和度 + 高亮度像素（HSV：sat < 0.35 且 val > 0.5），需包含音符的浮雕阴影（mask 膨胀约 10px）
- 背景补全：圆盘区域可用原图大半径高斯模糊后的颜色填充被抠掉的音符位置
- 音符缩放比例：约 70%（可按视觉效果微调）

原图备份：`D:\OH-workspace\tmp\ic_launcher_adaptive_foreground.orig.png`

---

## 2026-09-23 执行记录（已落地，含实测坑）

**勘误**：背景不是"圆盘"，是 **681×681 圆角方形**红块（disc = alpha>10 共 465k px ≈ 681²）。白色音符 607×604 几乎撑满方块（音符边缘距方块边缘仅 ~37px）。

### 新踩的坑（都实测翻过车）

1. **纯 HSV 阈值抠音符不够**：`sat<0.35 & val>0.5` 只抓白色本体；音符的浮雕阴影是 **val 0.69~0.86 的"较暗红渐变"**（val<0.6 的像素是 0 个！），阈值抠不到 → 缩小后背景残留一大团阴影晕。
2. **阈值抠阴影不可行**：阴影从轮廓向外渐变融入圆盘红，没有边界，任何阈值都会留下"鬼影轮廓线"。
3. **mask 不能太大**：dilate(white, 90) 几乎吞掉整个方块 → 归一化卷积没有任何有效红源可补 → fill 输出垃圾（黑框）。
4. **归一化卷积的 keep 必须限定在前景（alpha>10）内**，否则圆盘外透明区的黑色像素会混进模糊核 → 整圈黑边。

### 成功方案（脚本 `tmp/resize_note_icon_v6.py`）

核心思路：**「音符 + 全部渐变阴影 + 一圈同色红」整环作为前景一起缩小**，绕开阴影阈值难题；背景洞用「排除音符区的归一化卷积」补全。

```
white     = (sat<0.35) & (val>0.5) & (alpha>10)          # 音符本体
note_mask = dilate(white, 65) ∩ erode(disc, 25)          # 整环 mask
GUARD=25   保证 mask 不碰方块外缘暗渐变环，也留出补全源
背景补全   = normalized conv（keep 限定 disc 内）R=220
           + 低频差校正 corr=R60（继承周围阴影衰减趋势，消接缝）
背景 mask 边缘羽化 18px（宽羽化是消鬼影关键），前景边缘 2px
前景整层等比缩 70% 居中，alpha_composite 回背景
```

参数速查：`SCALE=0.70, RING=65, GUARD=25, NC_R=220, CORR_R=60, FEATHER_BG=18, FEATHER_FG=2`

**验收**：真实显示尺寸 192/96/48px 下干净无痕；512px 放大仍有极淡的轮廓痕迹，实际尺寸不可见。白音符像素 163k → 74.5k（≈0.7²）。

## v7（最终采用）：引导 Poisson 填充 —— 彻底消除 v6 的矩形接缝和脏斑

**v6 的问题**（用户复检发现）：归一化卷积补全产生了①明显的矩形接缝框（mask 边界色差）②角落涂抹状脏斑（低频校正引入）。根因：归一化卷积无法再现圆盘的径向光泽渐变，补全区与周围红底有系统性色差。

**v7 方案**（`tmp/resize_note_icon_v7.py`，最终）：
1. 前景抠取：`mask = dilate(white,70) ∩ erode(disc,25)`（白音符+浮雕阴影整环），前景层 alpha = Gaussian(mask, σ=14) 软边。
2. 背景补全：**引导 Poisson 谐波填充**（Jacobi 700 迭代，22s）——拉普拉斯源项取自「二次曲面拟合的圆盘光泽模型」（对 clean disc 6 参数最小二乘），Dirichlet 边界取原图周边像素。补全区既继承圆盘 sheen 又与边界 C0 连续。
3. 前景音符层 Lanczos 缩 70% 居中，alpha_composite 叠回补全后的红底。

**数值验收**：mask 边界径向 24 条射线最大跳变 **1.0/255**（不可见）；未动红底偏差 **0.0**（逐位保留）；补全区梯度 p99=2.50 vs 原图自然红带 p99=2.12（同构）。

**教训**：带光泽/渐变的背景 inpaint，归一化卷积/低频校正都不够，**引导 Poisson（guide 的 Laplacian 作源项 + 原图 Dirichlet 边界）才是正解**。
