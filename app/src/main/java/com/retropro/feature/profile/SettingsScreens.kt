package com.retropro.feature.profile

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.feature.m0.MaterialLabSections
import com.retropro.uikit.AppText
import com.retropro.uikit.AppTextField
import com.retropro.uikit.HorizontalChipRow
import com.retropro.uikit.NumberStepper
import com.retropro.uikit.PrimaryButton
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.MiuixCard
import com.retropro.uikit.SelectableChip
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import com.retropro.util.AppPrefs
import com.retropro.util.DailyQuotes
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * 「我的」的三个二级设置页（外观 / 提醒 / 备份）。
 *
 * 一级列表只放 List Item（见 [ProfileScreen]），具体内容全部下沉到这里 ——
 * 布局三页统一：大标题 + 说明 + 内容卡 + 返回按钮。
 */

/**
 * 二级 / 三级页共享骨架：状态栏净空 + 标题 + 返回。
 *
 * `internal` 而非 `private`：[VersionScreen] 是独立文件，也要用这套骨架
 * （滚动 / 安全区 / 内边距只有这一份实现）。
 */
@Composable
internal fun SettingsScaffold(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    /**
     * 页面实底。默认铺 [AppColors.Background]，阻断背后光晕/条纹（「我的」线严格 MIUIX）。
     *
     * 传 `null` 表示**不遮挡背景层** —— 供需要折射对象的验证类页面使用：
     * 折射源（条纹/圆环/色球）与页面内容是 `GlassScene` 里的**兄弟节点且在下方**，
     * 实底会把它整块盖住，而 `GlassPanel` 走 `drawBackdrop` 读的是录制图层、
     * **不感知遮挡关系** → 玻璃面板只剩纯色可折射，渲染成一块偏黑的半透明板。
     */
    backgroundColor: Color? = AppColors.Background,
    /**
     * 轻量顶栏模式。默认 `false` = 现有版式（32sp 大标题 + 底部「返回」按钮），
     * 既有五个页面**不传即零变化**。
     *
     * `true` 时改为「返回箭头 + 小号页名」顶栏，并**不渲染大标题、副标题与底部返回按钮** ——
     * 版式为「版本与更新」页设计：那里要让大字版本号独占视觉焦点，
     * 32sp 大标题会跟它抢注意力；返回由顶栏箭头与系统返回键承担，不需要两个返回入口。
     */
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            // 「我的」线严格 MIUIX：二级页也铺实底，阻断背后光晕/条纹（没有折射对象就没有液态效果）。
            // 验证类页面传 null 保留折射源，见参数说明。
            .then(
                if (backgroundColor != null) Modifier.background(backgroundColor) else Modifier,
            )
            .verticalScroll(rememberScrollState())
            .statusBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = navBarClearance()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (compact) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // 图标用项目既有写法（见 uikit/nav/LiquidNavBar.kt 的 Image + ColorFilter.tint）。
                // 40dp 外框保证触摸目标够大，24dp 是图标本体的视觉尺寸。
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(AppShapes.Chip)
                        .clickable(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = "返回",
                        modifier = Modifier.size(24.dp),
                        colorFilter = ColorFilter.tint(AppColors.TextPrimary),
                    )
                }
                AppText(title, AppTypography.CardTitle, AppColors.TextPrimary)
            }
        } else {
            Column {
                AppText(title, AppTypography.Display, AppColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                AppText(subtitle, AppTypography.Caption, AppColors.TextTertiary)
            }
        }
        content()
        if (!compact) {
            SecondaryButton(text = "返回", onClick = onBack, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * List Item 长条：标题 + 说明 + 右侧箭头。点击进入下级设置页（二级或三级）。
 *
 * ## 展开动画
 *
 * 点击时长条**横向拉伸**（scaleX 1.06，Y 不变——"往两边展开"的手感，
 * 配合路由的右滑入，像长条把下级页"推开"）+ 弹性回弹；
 * 同时箭头右移 6dp，给出明确的进入暗示。全部在 draw 阶段读动画值，零重组。
 *
 * 原先私有在 [ProfileScreen] 内（一级列表专用）；外观页要做三级入口后搬到此处共用，
 * 保证"带箭头的入口长条"在全 App 只有一份实现。
 */
@Composable
internal fun ListItem(title: String, detail: String, onClick: () -> Unit) {
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()

    // 横向展开：按下时 X 放大 1.06，spring 带微弹
    val stretchX by animateFloatAsState(
        targetValue = if (pressed) 1.06f else 1f,
        animationSpec = androidx.compose.animation.core.spring(
            dampingRatio = 0.55f,
            stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
        ),
        label = "listItemStretch",
    )
    // 箭头右移：进入暗示
    val arrowShift by animateFloatAsState(
        targetValue = if (pressed) 6f else 0f,
        animationSpec = androidx.compose.animation.core.tween(150),
        label = "listItemArrow",
    )

    Box(
        Modifier
            // 缩放放链首：整卡（背景+圆角+内容）一起横向拉伸，圆角始终贴合
            .graphicsLayer { scaleX = stretchX }
            .fillMaxWidth()
            .clip(AppShapes.Card)
            // 严格 MIUIX：不透明实色 + 发丝描边，不用半透明叠背景（那是淡玻璃的观感）
            .background(AppColors.SurfaceFallback)
            .border(1.dp, AppColors.GlassLine, AppShapes.Card)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                AppText(title, AppTypography.CardTitle, AppColors.TextPrimary)
                Spacer(Modifier.height(2.dp))
                AppText(detail, AppTypography.Caption, AppColors.TextTertiary)
            }
            // 箭头：graphicsLayer 平移，不触发布局
            AppText(
                "›",
                AppTypography.Title,
                AppColors.TextTertiary,
                modifier = Modifier.graphicsLayer { translationX = arrowShift },
            )
        }
    }
}

// ============================================================ 外观

@Composable
fun AppearanceScreen(
    onBack: () -> Unit,
    onOpenMaterialLab: () -> Unit = {},
) {
    val context = LocalContext.current
    val systemDark = isSystemInDarkTheme()

    // 自定义背景图：Photo Picker 免存储权限；拷到 filesDir，避免 uri 授权过期
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    AppPrefs.backgroundImageFile(context).outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                AppPrefs.setBackgroundStyle(context, AppPrefs.BackgroundStyle.IMAGE)
                AppPrefs.Live.backgroundStyle = AppPrefs.BackgroundStyle.IMAGE
            }.onFailure { android.util.Log.e("AppearanceScreen", "导入背景图失败", it) }
        }
    }

    SettingsScaffold(
        title = "外观",
        subtitle = "深色与纯黑都是 OLED 友好；跟随主题自动切换",
        onBack = onBack,
    ) {
        MiuixCard(title = "主题模式", subtitle = "跟随系统 / 浅色 / 深色") {
            val mode = remember { mutableStateOf(AppPrefs.themeMode(context)) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(
                    AppPrefs.ThemeMode.SYSTEM to "跟随系统",
                    AppPrefs.ThemeMode.LIGHT to "浅色",
                    AppPrefs.ThemeMode.DARK to "深色",
                ).forEach { (m, label) ->
                    SelectableChip(
                        text = label,
                        selected = mode.value == m,
                        onClick = {
                            mode.value = m
                            AppPrefs.setThemeMode(context, m)
                            when (m) {
                                AppPrefs.ThemeMode.SYSTEM ->
                                    AppPrefs.applyThemeAtStartup(context, systemDark)
                                AppPrefs.ThemeMode.LIGHT ->
                                    com.retropro.uikit.theme.AppColors.isDarkIntent = false
                                AppPrefs.ThemeMode.DARK ->
                                    com.retropro.uikit.theme.AppColors.isDarkIntent = true
                            }
                        },
                    )
                }
            }
        }

        MiuixCard(title = "计分板 AMOLED 纯黑", subtitle = "横屏计分用纯黑 #000，省电且对比最强") {
            val amoled = AppPrefs.Live.scoreboardAmoled
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppText("开启后进入计分板自动切纯黑", AppTypography.Body, AppColors.TextSecondary)
                SelectableChip(
                    text = if (amoled) "已开启" else "已关闭",
                    selected = amoled,
                    onClick = {
                        val next = !amoled
                        AppPrefs.setScoreboardAmoled(context, next)
                        AppPrefs.Live.scoreboardAmoled = next
                    },
                )
            }
        }

        MiuixCard(title = "语音识别按钮", subtitle = "快速录入面板的录音按钮底色") {
            val btnIdx = AppPrefs.Live.recordButtonIndex
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                AppPrefs.RecordButtonColors.forEachIndexed { index, (_, color) ->
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(color)
                            .clickable {
                                AppPrefs.setRecordButtonIndex(context, index)
                                AppPrefs.Live.recordButtonIndex = index
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (index == btnIdx) {
                            Box(
                                Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(Color.White),
                            )
                        }
                    }
                }
            }
        }

        MiuixCard(title = "背景", subtitle = "自定义图片会成为玻璃的折射对象，选对比强的图效果最好") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val bgStyle = AppPrefs.Live.backgroundStyle
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        AppPrefs.BackgroundStyle.DEFAULT to "默认",
                        AppPrefs.BackgroundStyle.MINIMAL to "简约",
                        AppPrefs.BackgroundStyle.SOLID to "纯色",
                        AppPrefs.BackgroundStyle.IMAGE to "自定义图片",
                    ).forEach { (style, label) ->
                        SelectableChip(
                            text = label,
                            selected = bgStyle == style,
                            onClick = {
                                if (style == AppPrefs.BackgroundStyle.IMAGE) {
                                    photoPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                    )
                                } else {
                                    AppPrefs.setBackgroundStyle(context, style)
                                    AppPrefs.Live.backgroundStyle = style
                                }
                            },
                        )
                    }
                }
                AppText(
                    text = "选「自定义图片」会打开相册挑选，图片保存在应用内，不会失效",
                    AppTypography.Caption,
                    AppColors.TextTertiary,
                )

                // ---- 遮罩浓度：选了图片才显示；白纱/黑纱太闷就往低调
                val mask = AppPrefs.Live.bgMaskOpacity
                if (bgStyle == AppPrefs.BackgroundStyle.IMAGE) {
                    Column {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                        ) {
                            AppText("图片遮罩浓度", AppTypography.Body, AppColors.TextPrimary)
                            AppText("%.0f%%".format(mask * 100), AppTypography.Body, AppColors.MeSoft)
                        }
                        top.yukonga.miuix.kmp.basic.Slider(
                            value = mask,
                            onValueChange = {
                                AppPrefs.Live.bgMaskOpacity = it
                                AppPrefs.setBgMaskOpacity(context, it)
                            },
                            valueRange = 0f..1f,
                        )
                        AppText(
                            text = "往低调更清晰，往高调文字更稳；当前盖的是" +
                                if (AppColors.isDark) "黑纱" else "白纱",
                            AppTypography.Caption,
                            AppColors.TextTertiary,
                        )
                    }
                }
            }
        }

        TextColorCard()

        // 三级入口：材质验证（原 M0 页）。放最后 —— 它是验证工具，不是外观设置本身
        ListItem(
            title = "材质验证",
            detail = "液态玻璃折射 · 帧率 · 熔断 · 本地语音识别调试",
            onClick = onOpenMaterialLab,
        )
    }
}

// ============================================================ 外观 → 材质验证（三级）

/**
 * 外观的三级界面：材质验证。
 *
 * 内容全部来自原 M0 页（8 个分区），只在这里套上共享骨架。
 *
 * **必须传 `backgroundColor = null`**：见 [SettingsScaffold] 的参数说明 ——
 * 铺实底会把 `GlassScene` 的折射源整块盖住，而 `GlassPanel` 读图层时不感知遮挡，
 * 结果 210dp 折射演示面板会退化成一块偏黑的半透明板，"玻璃真的在工作"就无从判断了。
 */
@Composable
fun MaterialLabScreen(onBack: () -> Unit) {
    SettingsScaffold(
        title = "材质验证",
        subtitle = "MIUIX 0.9.4 · Haze 2.0 · Backdrop 2.0.1 · Compose 1.12.1",
        onBack = onBack,
        backgroundColor = null, // ★ 不铺实底，保留条纹/圆环/色球作为折射对象
    ) {
        MaterialLabSections()
    }
}

// ============================================================ 字体颜色

/**
 * 字体颜色自定义卡。
 *
 * ## 交互设计：预设 + 色相滑条，而不是全自由调色
 *
 * 手机上调色控件有个固有矛盾：**自由度高 = 操作繁琐**（HSV 三条滑条在小屏上很难捏准），
 * 而**只给预设 = 找不到想要的那个色**。这里取中间：预设圆点解决"大概想要这类颜色"，
 * 色相滑条解决"预设里差一点点"。
 *
 * 滑条**只调色相**，饱和度和明度锁在固定值（S=0.62 / L=0.42）。理由：
 *  - 文字色的可读性主要由**明度**决定。放开明度滑条，用户很容易调出白底白字 / 黑底黑字，
 *    然后就得到"字看不见了"的报障 —— 与其事后解释，不如一开始就不给这个坑。
 *  - S/L 固定后，滑条的效果是"同样深浅、换个色系"，这正是用户想要的那种微调。
 *
 * ## 三档的语义
 *
 * [AppColors.TextPrimary] 标题与主数值 / [AppColors.TextSecondary] 说明文字 /
 * [AppColors.TextTertiary] 最弱的一层（序号、单位、占位）。三档的**相对明度关系**是页面层次感的来源，
 * 所以每档都单独调 —— 但也正因如此，改完要自己保证三档还能区分开（卡里给了实时预览行）。
 */
@Composable
private fun TextColorCard() {
    val context = LocalContext.current

    MiuixCard(
        title = "字体颜色",
        subtitle = "三档文字分层可各自调整；不设则跟随主题",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {

            // ---- 实时预览：三档并排，改完立刻能看到层次有没有塌
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(AppShapes.CardSmall)
                    .background(AppColors.NeutralFill)
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                AppText("预览", AppTypography.Caption, AppColors.TextTertiary)
                AppText("主文字 · 标题与比分", AppTypography.CardTitle, AppColors.TextPrimary)
                AppText("次级文字 · 说明与备注", AppTypography.Body, AppColors.TextSecondary)
                AppText("三级文字 · 序号与单位", AppTypography.Caption, AppColors.TextTertiary)
            }

            TextColorRow(
                label = "主文字",
                current = AppColors.TextPrimary,
                custom = AppColors.customTextPrimary,
                onPick = { AppPrefs.setTextPrimary(context, it) },
            )
            TextColorRow(
                label = "次级文字",
                current = AppColors.TextSecondary,
                custom = AppColors.customTextSecondary,
                onPick = { AppPrefs.setTextSecondary(context, it) },
            )
            TextColorRow(
                label = "三级文字",
                current = AppColors.TextTertiary,
                custom = AppColors.customTextTertiary,
                onPick = { AppPrefs.setTextTertiary(context, it) },
            )

            if (AppColors.hasCustomTextColor) {
                SecondaryButton(
                    text = "重置为默认",
                    onClick = { AppPrefs.resetTextColors(context) },
                )
            } else {
                AppText(
                    text = "当前全部使用主题默认色。",
                    style = AppTypography.Caption,
                    color = AppColors.TextTertiary,
                )
            }
        }
    }
}

/**
 * 一档文字色的调节行：预设圆点 + 色相滑条。
 *
 * [custom] 为 `null` 表示这档还没被自定义过 —— 滑条的初始色相取 [current] 算出来，
 * 这样第一次拖动不会让颜色突然跳到另一个色系。
 */
@Composable
private fun TextColorRow(
    label: String,
    current: Color,
    custom: Color?,
    onPick: (Color?) -> Unit,
) {
    // ⚠️ 这里**不能**把 custom/current 作为 remember 的 key。
    // 拖动滑条会写回 AppColors → custom 变化 → 若 key 里有 custom，
    // 每次 recomposition 都会把 hue 重新初始化，拖动会被反复打断（滑条抖回原处）。
    // 所以只在首次组合时初始化一次，别的入口（点预设色）显式同步，见下面 clickable。
    var hue by remember { mutableFloatStateOf(hueOf(custom ?: current)) }

    // 重置为默认时把滑条也拨回默认色的色相。
    // 只在 custom 变成 null 那一刻同步，拖动/点预设过程中不介入（那两种都自己设了 hue）。
    LaunchedEffect(custom == null) {
        if (custom == null) hue = hueOf(current)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AppText(label, AppTypography.Body, AppColors.TextPrimary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppText(
                    text = if (custom == null) "默认" else "#%06X".format(custom.toArgb() and 0xFFFFFF),
                    style = AppTypography.Caption,
                    color = AppColors.TextTertiary,
                )
                Spacer(Modifier.size(8.dp))
                // 当前实际生效色的色块，比十六进制好认
                Box(
                    Modifier
                        .size(22.dp)
                        .clip(CircleShape)
                        .background(current)
                        .border(1.dp, AppColors.GlassLine, CircleShape),
                )
            }
        }

        // 预设圆点：一行放不下就走横向滚动（项目约定：选项多就横向滚，不要换行）
        HorizontalChipRow(fadeColor = AppColors.SurfaceFallback) {
            AppPrefs.TextColorPresets.forEach { (_, color) ->
                val selected = custom != null && custom.toArgb() == color.toArgb()
                Box(
                    Modifier
                        .padding(end = 10.dp)
                        .size(30.dp)
                        .clip(CircleShape)
                        .background(color)
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) AppColors.MeSoft else AppColors.GlassLine,
                            shape = CircleShape,
                        )
                        .clickable {
                            // 点预设色要同步滑条位置，否则滑条还停在旧色相上，
                            // 用户再拖一下会跳回旧色系（看起来像"预设没生效"）
                            hue = hueOf(color)
                            onPick(color)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (selected) {
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(CircleShape)
                                .background(Color.White),
                        )
                    }
                }
            }
        }

        top.yukonga.miuix.kmp.basic.Slider(
            value = hue,
            onValueChange = {
                hue = it
                // 拖动即生效：颜色由色相算出，S/L 锁定（见 TextColorCard 的说明）
                onPick(colorFromHue(it))
            },
            valueRange = 0f..360f,
        )

        AppText(
            text = "拖动色相条微调色系；深浅已锁定，保证任何底色上都读得清。",
            style = AppTypography.Caption,
            color = AppColors.TextTertiary,
        )
    }
}

/** 滑条固定的饱和度与明度 —— 文字色的可读性主要由明度决定，所以锁死不放给用户 */
private const val TEXT_COLOR_SAT = 0.62f
private const val TEXT_COLOR_LIGHT = 0.42f

/** 由色相生成颜色（HSL → RGB）。S/L 用固定值，见 [TEXT_COLOR_SAT]。 */
private fun colorFromHue(hue: Float): Color {
    val h = ((hue % 360f) + 360f) % 360f
    val c = (1f - kotlin.math.abs(2f * TEXT_COLOR_LIGHT - 1f)) * TEXT_COLOR_SAT
    val x = c * (1f - kotlin.math.abs((h / 60f) % 2f - 1f))
    val m = TEXT_COLOR_LIGHT - c / 2f
    val (r, g, b) = when {
        h < 60f -> Triple(c, x, 0f)
        h < 120f -> Triple(x, c, 0f)
        h < 180f -> Triple(0f, c, x)
        h < 240f -> Triple(0f, x, c)
        h < 300f -> Triple(x, 0f, c)
        else -> Triple(c, 0f, x)
    }
    return Color(
        red = (r + m).coerceIn(0f, 1f),
        green = (g + m).coerceIn(0f, 1f),
        blue = (b + m).coerceIn(0f, 1f),
    )
}

/** 取颜色的色相（0–360），供滑条初始化用。灰度色的饱和度为 0，此时返回 0（红）。 */
private fun hueOf(color: Color): Float {
    val r = color.red
    val g = color.green
    val b = color.blue
    val max = maxOf(r, g, b)
    val min = minOf(r, g, b)
    val d = max - min
    if (d == 0f) return 0f
    val h = when (max) {
        r -> ((g - b) / d) % 6f
        g -> (b - r) / d + 2f
        else -> (r - g) / d + 4f
    }
    return ((h * 60f) % 360f + 360f) % 360f
}

// ============================================================ 提醒

@Composable
fun RemindersScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val reminders by vm.reminders.collectAsStateWithLifecycle()

    SettingsScaffold(
        title = "提醒",
        subtitle = "达到阈值就在这里提示，判断依据是你自己的记录",
        onBack = onBack,
    ) {
        MiuixCard(title = "提醒规则", subtitle = "每条提醒独立开关与阈值") {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                if (reminders.isEmpty()) {
                    AppText("加载中…", AppTypography.Caption, AppColors.TextTertiary)
                }
                reminders.forEach { status ->
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column {
                                AppText(
                                    text = if (status.due) "${status.label} · 该行动了" else status.label,
                                    style = AppTypography.CardTitle,
                                    color = if (status.due) AppColors.OpponentSoft else AppColors.TextPrimary,
                                )
                                Spacer(Modifier.height(2.dp))
                                AppText(
                                    text = if (status.enabled) status.detail else "已关闭",
                                    style = AppTypography.Caption,
                                    color = AppColors.TextTertiary,
                                )
                            }
                            SelectableChip(
                                text = if (status.enabled) "已开启" else "已关闭",
                                selected = status.enabled,
                                onClick = { vm.setEnabled(status.type, !status.enabled) },
                            )
                        }
                        NumberStepper(
                            label = "阈值",
                            value = status.thresholdDays.toDouble(),
                            onValueChange = { vm.setThreshold(status.type, it.toInt()) },
                            range = 1.0..365.0,
                            step = 1.0,
                            unit = "天",
                        )
                    }
                }
            }
        }
    }
}

// ============================================================ 备份与恢复

@Composable
fun BackupScreen(vm: ProfileViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val busy by vm.busy.collectAsStateWithLifecycle()
    val lastExport by vm.lastExportPath.collectAsStateWithLifecycle()
    var showImportConfirm by remember { mutableStateOf(false) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            context.contentResolver.openInputStream(uri)?.let { stream ->
                vm.importFromStream(stream) { error ->
                    if (error != null) {
                        android.util.Log.e("BackupScreen", "导入失败", error)
                    }
                }
            }
        }
    }

    SettingsScaffold(
        title = "备份与恢复",
        subtitle = "导出为 JSON，恢复会覆盖当前全部数据",
        onBack = onBack,
    ) {
        MiuixCard(title = "导出 / 导入", subtitle = "备份写在应用专属目录，系统清理时不会被删") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = if (busy) "处理中…" else "导出备份",
                    enabled = !busy,
                    onClick = { vm.export { } },
                )
                SecondaryButton(
                    text = "从备份恢复…",
                    onClick = { showImportConfirm = true },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
                lastExport?.let {
                    AppText(
                        text = "最近导出：$it",
                        AppTypography.Caption,
                        AppColors.TextTertiary,
                    )
                }
                AppText(
                    text = "要用别的设备恢复，请先用文件管理器把备份文件拷走。",
                    AppTypography.Caption,
                    AppColors.TextTertiary,
                )
            }
        }
    }

    if (showImportConfirm) {
        ImportConfirm(
            busy = busy,
            onDismiss = { showImportConfirm = false },
            onConfirmed = {
                showImportConfirm = false
                importLauncher.launch(arrayOf("application/json"))
            },
        )
    }
}

/**
 * 导入前的二次确认。**必须把"会清空现有数据"写在按钮上方**，
 * 这是备份功能里最危险的一步 —— 一旦选错文件，当前记录就没了。
 */
@Composable
private fun ImportConfirm(busy: Boolean, onDismiss: () -> Unit, onConfirmed: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(AppColors.TextPrimary.copy(alpha = 0.35f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss),
    )
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.fillMaxWidth()) {
            MiuixCard(
                title = "从备份恢复？",
                subtitle = "恢复会用备份里的数据「替换」当前全部记录，这一步不可撤销。",
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    AppText(
                        text = "建议先点「导出备份」留一份当前数据。",
                        AppTypography.Caption,
                        AppColors.TextSecondary,
                    )
                    PrimaryButton(text = "选择备份文件", onClick = onConfirmed, enabled = !busy)
                    SecondaryButton(text = "取消", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}


// ============================================================ 每日格言

/**
 * 每日格言设置。
 *
 * 两条路径：预设库按日轮换（不设自定义时自动生效）/ 自定义一句固定显示。
 * 预设库里点任意一条 = 钉住它（即设为自定义），今天轮换到的那条高亮品牌蓝。
 */
@Composable
fun QuoteSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val custom = AppPrefs.Live.dailyQuote

    var text by remember { mutableStateOf(custom ?: "") }

    SettingsScaffold(
        title = "每日格言",
        subtitle = "记录页左上角每天一句话",
        onBack = onBack,
    ) {
        // ---- 今天这句：当前生效 + 来源标注
        MiuixCard(title = "今天这句") {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                // 档位与记录页保持一致（Lead = 24sp）：这里是所见即所得预览，
                // 用别的档位会让用户按预览效果做判断，结果回到记录页发现尺寸不同。
                AppText(
                    text = DailyQuotes.forToday(custom),
                    AppTypography.Lead,
                    AppColors.TextPrimary,
                )
                AppText(
                    text = if (custom?.isNotBlank() == true) "来自你的自定义" else "预设轮换 · 每天自动换",
                    AppTypography.Caption,
                    AppColors.TextTertiary,
                )
            }
        }

        // ---- 自定义：设了固定显示，清空回到轮换
        MiuixCard(title = "自定义", subtitle = "设一句自己的，固定显示；留空保存即清除") {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                AppTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = "比如：稳住节奏，先赢回一拍",
                )
                PrimaryButton(
                    text = "保存",
                    onClick = {
                        AppPrefs.setDailyQuote(context, text.takeIf { it.isNotBlank() })
                        AppPrefs.Live.dailyQuote = text.trim().takeIf { it.isNotBlank() }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (custom?.isNotBlank() == true) {
                    SecondaryButton(
                        text = "清除，回到每日轮换",
                        onClick = {
                            AppPrefs.setDailyQuote(context, null)
                            AppPrefs.Live.dailyQuote = null
                            text = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        // ---- 预设库：点一条 = 钉住；今天轮换到的高亮
        MiuixCard(title = "预设库", subtitle = "点任意一条钉住它；高亮的是今天轮换到的") {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                val todayIdx = DailyQuotes.todayPresetIndex()
                DailyQuotes.PRESETS.forEachIndexed { i, quote ->
                    val pinned = custom == quote
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.CardSmall)
                            .clickable {
                                AppPrefs.setDailyQuote(context, quote)
                                AppPrefs.Live.dailyQuote = quote
                                text = quote
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AppText(
                            text = quote,
                            AppTypography.Body,
                            when {
                                pinned -> AppColors.MeSoft
                                i == todayIdx -> AppColors.MeSoft
                                else -> AppColors.TextSecondary
                            },
                        )
                        if (pinned) {
                            AppText("已钉住", AppTypography.Label, AppColors.MeSoft)
                        } else if (i == todayIdx) {
                            AppText("今天", AppTypography.Label, AppColors.MeSoft)
                        }
                    }
                }
            }
        }
    }
}
