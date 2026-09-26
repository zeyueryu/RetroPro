package com.retropro.feature.equipment

import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.retropro.data.db.RacketWithLastString
import com.retropro.di.AppGraph
import com.retropro.uikit.navBarClearance
import com.retropro.uikit.AppTextField
import com.retropro.data.model.GearItem
import com.retropro.data.model.GearTag
import com.retropro.uikit.HorizontalChipRow
import com.retropro.uikit.SelectableChip
import com.retropro.uikit.AppText
import com.retropro.uikit.NumberStepper
import com.retropro.uikit.PrimaryButton
import com.retropro.uikit.SecondaryButton
import com.retropro.uikit.SectionCard
import com.retropro.uikit.ConfirmDialog
import com.retropro.uikit.LongPressMenu
import com.retropro.uikit.MenuAction
import com.retropro.uikit.longPressable
import com.retropro.uikit.theme.AppColors
import com.retropro.uikit.theme.AppShapes
import com.retropro.uikit.theme.AppTypography
import com.retropro.util.Formatters
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** 一天毫秒数 */
private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * 装备页 —— 球拍 / 穿线历史 / 羽毛球消耗。
 *
 * ## 设计取舍（用户明确要求）
 *
 * 穿线**只记「日期 + 价格」**，外加线型号与磅数用于区分同一支拍的多次穿线。
 * **不做寿命衰减推算** —— 那需要"每次打球用了多少小时"的输入，
 * 而球场上是没人愿意记这个的；没有输入就没法推算，硬做只会得到假精确的数字。
 *
 * ## 为什么"上次穿线"要显示"多久之前"
 *
 * 换线决策其实只依赖一个感受："这根线好像打了一段时间了"。
 * 直接显示"6 个月前"比显示"2026-03-23"更能触发行动。
 * 这里只做**呈现**，不做任何"该换了"的判断 —— 判断权留给用户。
 */
@Composable
fun EquipmentScreen() {
    val repo = AppGraph.equipment
    val scope = rememberCoroutineScope()

    // Popup Card：点击分类 chip 不跳转、不切换主内容，而是弹出该分类的管理卡片
    var popupTab by remember { mutableStateOf<EquipTab?>(null) }
    var lastTab by remember { mutableStateOf(EquipTab.RACKET) }  // 退出动画期间保持内容不空
    val tags by repo.observeGearTags().collectAsStateWithLifecycle(initialValue = emptyList())

    val rackets by remember { repo.observeRackets() }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val shuttles by remember { repo.observeShuttles() }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    // 球拍 id → 距上次穿线天数
    var daysSince by remember { mutableStateOf<Map<Long, Long>>(emptyMap()) }
    LaunchedEffect(rackets) {
        daysSince = rackets.mapNotNull { r ->
            repo.daysSinceLastString(r.id)?.let { r.id to it }
        }.toMap()
    }

    // 长按菜单：记录被长按的对象与长按点（贴手指弹出）
    var menuAnchor by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    var racketMenu by remember { mutableStateOf<RacketWithLastString?>(null) }
    var gearMenu by remember { mutableStateOf<GearCat?>(null) }
    var shuttleMenu by remember { mutableStateOf<com.retropro.data.model.Shuttle?>(null) }
    // 菜单选了「记一次穿线」→ 该球拍的 expandRequest 自增 → 表单展开
    var expandReq by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    // 删除二次确认。菜单此刻已关，所以只能留「标题 / 正文 / 待删对象」三样东西
    var pendingDelete by remember { mutableStateOf<PendingDelete?>(null) }

    Box(Modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize().wrapContentWidth(Alignment.CenterHorizontally).widthIn(max = 720.dp),
        contentPadding = PaddingValues(
        start = 20.dp,
        end = 20.dp,
        // 顶部留白放在滚动内容里（不是视口避让）：静止时标题在时钟下方，滚动时从状态栏下穿过
        top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 18.dp,
        bottom = navBarClearance(),
        ),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        item {
            Column {
                AppText("装备", AppTypography.Display, AppColors.TextPrimary)
                Spacer(Modifier.height(6.dp))
                AppText(
                    text = "球拍与穿线只记日期和价格，不做寿命推算",
                    style = AppTypography.Caption,
                    color = AppColors.TextTertiary,
                )
            }
        }

        // ---- 分类入口（六项）：点击弹出 Popup Card（不切换主内容，默认页始终展示全部装备）
        // 横向滚动胶囊：6 个分类换行会塌成两三排，破坏"一行 = 一个维度"的扫读
        item {
            HorizontalChipRow(fadeColor = AppColors.Background) {
                EquipTab.entries.forEach { tab2 ->
                    SelectableChip(
                        text = tab2.label,
                        selected = popupTab == tab2,
                        onClick = {
                            lastTab = tab2
                            popupTab = tab2
                        },
                    )
                }
            }
        }

        if (rackets.isEmpty()) {
            item {
                SectionCard(
                    title = "还没有球拍",
                    subtitle = "先加一支，之后每次穿线都挂在它下面",
                ) {
                    AppText(
                        text = "穿线历史按球拍分层，同一支拍的多次穿线会按日期倒序排列。",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary,
                    )
                }
            }
        }

        items(rackets, key = { it.id }) { racket ->
            RacketCard(
                racket = racket,
                daysSince = daysSince[racket.id],
                onAddStringJob = { date, line, tension, price ->
                    scope.launch {
                        repo.addStringJob(racket.id, date, line, tension, price)
                        daysSince = daysSince + (racket.id to (System.currentTimeMillis() - date) / DAY_MS)
                    }
                },
                onLongPress = { at ->
                    menuAnchor = at
                    racketMenu = racket
                },
                expandRequest = expandReq?.takeIf { it.first == racket.id }?.second ?: 0,
            )
        }

        item {
            AddRacketCard(repo = repo) { brand, model ->
                scope.launch { repo.addRacket(brand, model) }
            }
        }

        item {
            AppText("羽毛球", AppTypography.CardTitle, AppColors.TextPrimary)
        }

        if (shuttles.isEmpty()) {
            item {
                SectionCard(title = "还没有球", subtitle = "加上常用的型号，之后按场次记录消耗") {
                    AppText(
                        text = "用球消耗会算进单场成本，统计页能看出「这场球花了多少」。",
                        style = AppTypography.Caption,
                        color = AppColors.TextSecondary,
                    )
                }
            }
        }

        items(shuttles, key = { it.id }) { shuttle ->
            SectionCard(
                title = "${shuttle.brand} ${shuttle.model}".trim(),
                subtitle = shuttle.note?.takeIf { it.isNotBlank() },
                modifier = Modifier.longPressable(
                    key = "shuttle.${shuttle.id}",
                    onTap = {},
                    onLongPressAt = { at ->
                        menuAnchor = at
                        shuttleMenu = shuttle
                    },
                ),
            ) {
                AppText("按型号记录用球数量（支持 0.5 筒）", AppTypography.Caption, AppColors.TextTertiary)
            }
        }

        item {
            AddShuttleCard { brand, model ->
                scope.launch { repo.addShuttle(brand, model) }
            }
        }

        // ---- 其他装备（球鞋/球衣/手胶）：默认全部平铺展示
        GearCat.entries.forEach { cat ->
            item(key = "gear_${cat.code}") {
                GearSummaryCard(
                    category = cat,
                    repo = repo,
                    onOpen = {
                        lastTab = tabOf(cat)
                        popupTab = tabOf(cat)
                    },
                    onLongPress = { at ->
                        menuAnchor = at
                        gearMenu = cat
                    },
                )
            }
        }

        item {
            SectionCard(
                title = "标签 · 品牌",
                subtitle = "预设 16 个羽毛球品牌，可自定义；点上方分类标签弹出管理卡",
            ) {
                FlowChips(tags = tags, onRemove = null)
            }
        }
    }

    // ---- Popup Card：spring 弹入 + 淡出（不跳转，浮层管理卡）
    val displayTab = popupTab ?: lastTab
    androidx.compose.animation.AnimatedVisibility(
        visible = popupTab != null,
        enter = androidx.compose.animation.scaleIn(
            initialScale = 0.82f,
            animationSpec = androidx.compose.animation.core.spring(
                dampingRatio = 0.55f,
                stiffness = androidx.compose.animation.core.Spring.StiffnessMedium,
            ),
        ) + androidx.compose.animation.fadeIn(),
        exit = androidx.compose.animation.scaleOut(targetScale = 0.92f) +
            androidx.compose.animation.fadeOut(),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(AppColors.TextPrimary.copy(alpha = 0.45f))
                .clickable(interactionSource = null, indication = null, onClick = { popupTab = null }),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                when (displayTab) {
                    EquipTab.RACKET -> RacketManageCard(repo = repo, rackets = rackets, daysSince = daysSince)
                    EquipTab.LINE -> LineManageCard(repo = repo)
                    EquipTab.BALL -> BallManageCard(
                        repo = repo,
                        onAdd = { brand, model -> scope.launch { repo.addShuttle(brand, model) } },
                    )
                    EquipTab.SHOES -> GearListSection(
                        category = GearCat.SHOES,
                        repo = repo,
                        onRequestDelete = { pendingDelete = it },
                    )
                    EquipTab.JERSEY -> GearListSection(
                        category = GearCat.JERSEY,
                        repo = repo,
                        onRequestDelete = { pendingDelete = it },
                    )
                    EquipTab.GRIP -> GearListSection(
                        category = GearCat.GRIP,
                        repo = repo,
                        onRequestDelete = { pendingDelete = it },
                    )
                }
            }
        }
    }
    // ---- 长按菜单：球拍
    racketMenu?.let { racket ->
        val name = "${racket.brand} ${racket.model}".trim().ifBlank { "未命名球拍" }
        LongPressMenu(
            items = listOf(
                MenuAction(
                    label = "记一次穿线",
                    onClick = {
                        // 展开请求用自增序号下发，卡片侧靠 LaunchedEffect 监听变化展开表单
                        expandReq = racket.id to ((expandReq?.takeIf { it.first == racket.id }?.second ?: 0) + 1)
                    },
                ),
                MenuAction(
                    label = if (racket.isActive) "标记为退役" else "恢复使用",
                    onClick = {
                        scope.launch {
                            // 列表项是查询投影（不含 note 等字段），写回前必须取完整实体，
                            // 否则 withLastString 到不了的那几列会被默认值覆盖
                            repo.racketById(racket.id)?.let { full ->
                                if (full.isActive) {
                                    repo.retireRacket(full)
                                } else {
                                    repo.updateRacket(full.copy(isActive = true))
                                }
                            }
                        }
                    },
                ),
                MenuAction(
                    label = "穿线记录管理",
                    onClick = {
                        lastTab = EquipTab.LINE
                        popupTab = EquipTab.LINE
                    },
                ),
                MenuAction(
                    label = "删除「$name」",
                    destructive = true,
                    onClick = {
                        pendingDelete = PendingDelete.Racket(
                            id = racket.id,
                            name = name,
                            stringJobCount = racket.stringJobCount,
                        )
                    },
                ),
            ),
            anchor = menuAnchor,
            onDismiss = { racketMenu = null },
        )
    }

    // ---- 长按菜单：其他装备（球鞋/球衣/手胶）
    gearMenu?.let { cat ->
        LongPressMenu(
            items = listOf(
                MenuAction(
                    label = "管理${cat.label}",
                    onClick = {
                        lastTab = tabOf(cat)
                        popupTab = tabOf(cat)
                    },
                ),
                MenuAction(
                    label = "清空「${cat.label}」记录",
                    destructive = true,
                    onClick = { pendingDelete = PendingDelete.GearCategory(cat) },
                ),
            ),
            anchor = menuAnchor,
            onDismiss = { gearMenu = null },
        )
    }

    // ---- 长按菜单：羽毛球型号
    shuttleMenu?.let { shuttle ->
        val name = "${shuttle.brand} ${shuttle.model}".trim().ifBlank { "未命名球" }
        LongPressMenu(
            items = listOf(
                MenuAction(
                    label = "球型号管理",
                    onClick = {
                        lastTab = EquipTab.BALL
                        popupTab = EquipTab.BALL
                    },
                ),
                MenuAction(
                    label = "删除「$name」",
                    destructive = true,
                    onClick = { pendingDelete = PendingDelete.Shuttle(shuttle.id, name) },
                ),
            ),
            anchor = menuAnchor,
            onDismiss = { shuttleMenu = null },
        )
    }

    // ---- 删除二次确认：三类删除的连带影响各不相同，文案必须分别说清。
    // 统一约定（见 ConfirmDialog KDoc）：标题固定 `动作「对象」？`，message 只写连带影响，
    // "不可撤销"一律交给 reversible 参数统一生成，调用点不要自己拼这句。
    pendingDelete?.let { pending ->
        val title: String
        val message: String
        val confirmText: String
        when (pending) {
            is PendingDelete.Racket -> {
                title = "删除「${pending.name}」？"
                confirmText = "删除"
                message = if (pending.stringJobCount > 0) {
                    "已记录的 ${pending.stringJobCount} 条穿线历史会一起删掉，统计里的穿线花费也会相应减少。" +
                        "如果只是暂时不用了，选「标记为退役」更合适，历史会保留。"
                } else {
                    "这支拍还没有穿线记录。" +
                        "如果只是暂时不用了，选「标记为退役」更合适，历史会保留。"
                }
            }
            is PendingDelete.Shuttle -> {
                title = "删除「${pending.name}」？"
                confirmText = "删除"
                message = "各场次里这个型号的用球明细会一起删掉，统计里的用球花费也会相应减少。" +
                    "已经打过的球不会因此消失，只是不再计入花费。"
            }
            is PendingDelete.GearCategory -> {
                // 对象名加书名号，与其余两类保持同一格式（原来漏了书名号）
                title = "清空「${pending.category.label}」记录？"
                confirmText = "清空"
                message = "这个分类下已记录的全部条目都会被删除，统计里的装备花费会相应减少。"
            }
            is PendingDelete.GearItem -> {
                title = "删除「${pending.name}」？"
                confirmText = "删除"
                message = "这条装备记录会被移除，统计里的装备花费会相应减少。" +
                    "如果只是暂时不用了，选「在用」旁边的状态切换更合适，记录会保留。"
            }
            is PendingDelete.GearTagItem -> {
                title = "删除标签「${pending.name}」？"
                confirmText = "删除"
                // 标签删除的连带影响与装备不同：**不会**动已有装备的品牌字段
                // （装备的 brand 是存下来的字符串，不是外键），所以必须说清楚"只影响词库"
                message = "只会把这个词从品牌词库里移除，已经记录过的装备不受影响。"
            }
        }
        ConfirmDialog(
            title = title,
            message = message,
            confirmText = confirmText,
            onConfirm = {
                // 都走 repo 的删除方法，连带删除由外键 `CASCADE` 负责。
                // 球拍/球取完整实体再删：列表项是查询投影，不保证与实体同构。
                scope.launch {
                    when (pending) {
                        is PendingDelete.Racket ->
                            repo.racketById(pending.id)?.let { repo.deleteRacket(it) }
                        is PendingDelete.Shuttle ->
                            repo.shuttleById(pending.id)?.let { repo.deleteShuttle(it) }
                        is PendingDelete.GearCategory ->
                            repo.clearGear(pending.category.code)
                        is PendingDelete.GearItem ->
                            repo.gearItemById(pending.id)?.let { repo.deleteGear(it) }
                        is PendingDelete.GearTagItem ->
                            repo.gearTagById(pending.id)?.let { repo.deleteGearTag(it) }
                    }
                }
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null },
        )
    }
    } // Box 根闭合
}

/** 待确认的删除动作。确认框弹出时菜单已关，所以要把展示名与连带数量一起带上。 */
private sealed interface PendingDelete {
    data class Racket(val id: Long, val name: String, val stringJobCount: Int) : PendingDelete
    data class Shuttle(val id: Long, val name: String) : PendingDelete
    /** 其他装备按分类整批清空（它们没有单条长按入口，只有汇总卡） */
    data class GearCategory(val category: GearCat) : PendingDelete
    /** 单条装备（球鞋/球衣/手胶）—— 行内「删除」入口用 */
    data class GearItem(val id: Long, val name: String) : PendingDelete
    /** 品牌标签 */
    data class GearTagItem(val id: Long, val name: String) : PendingDelete
}

/** 装备分类 tab（六项） */
enum class EquipTab(val label: String) {
    RACKET("球拍"), LINE("球线"), BALL("球"), SHOES("球鞋"), JERSEY("球衣"), GRIP("手胶"),
}

private fun tabOf(cat: GearCat): EquipTab = when (cat) {
    GearCat.SHOES -> EquipTab.SHOES
    GearCat.JERSEY -> EquipTab.JERSEY
    GearCat.GRIP -> EquipTab.GRIP
}

// ---------------------------------------------------------------- 球拍

@Composable
private fun RacketCard(
    racket: RacketWithLastString,
    daysSince: Long?,
    onAddStringJob: (date: Long, line: String, tension: Int, price: Double) -> Unit,
    /** 长按回报长按点（窗口坐标），由页面统一弹菜单 */
    onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    /** 长按菜单里选了「记一次穿线」时，把表单展开 */
    expandRequest: Int = 0,
) {
    var expanded by remember { mutableStateOf(false) }
    // 菜单选「记一次穿线」→ expandRequest 自增 → 展开表单
    LaunchedEffect(expandRequest) {
        if (expandRequest > 0) expanded = true
    }

    SectionCard(
        title = "${racket.brand} ${racket.model}".trim().ifBlank { "未命名球拍" },
        subtitle = if (racket.isActive) "在用" else "已退役",
        modifier = Modifier.longPressable(
            key = "racket.${racket.id}",
            onTap = {},
            onLongPressAt = onLongPress,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (racket.lastStringDate != null) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        AppText(
                            text = "上次穿线 ${racket.lastStringModel.orEmpty()}",
                            style = AppTypography.Body,
                            color = AppColors.TextPrimary,
                        )
                        AppText(
                            text = "${racket.lastStringTension ?: 0} 磅 · " +
                                Formatters.money(racket.lastStringPrice ?: 0.0),
                            style = AppTypography.Caption,
                            color = AppColors.TextSecondary,
                        )
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        // 相对时间比绝对日期更能触发"该换了"的行动
                        AppText(
                            text = daysSince?.let { Formatters.daysAgo(it) } ?: "—",
                            style = AppTypography.CardTitle,
                            color = AppColors.MeSoft,
                        )
                        AppText(
                            text = "共 ${racket.stringJobCount} 次",
                            style = AppTypography.Label,
                            color = AppColors.TextTertiary,
                        )
                    }
                }
            } else {
                AppText("还没有穿线记录 · 共 0 次", AppTypography.Caption, AppColors.TextTertiary)
            }

            if (expanded) {
                AddStringJobForm(onSubmit = { date, line, tension, price ->
                    onAddStringJob(date, line, tension, price)
                    expanded = false
                })
            } else {
                Box(Modifier.fillMaxWidth()) {
                    SecondaryButton(
                        text = "记一次穿线",
                        onClick = { expanded = true },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * 穿线记录表单。
 *
 * 日期用"几天前"的步进器而不是日期选择器：穿线记录基本都在当天或最近几天补录，
 * 为一个字段引入一整套日历组件不划算，而 −/+ 在球场场景下更快。
 */
@Composable
private fun AddStringJobForm(
    onSubmit: (date: Long, line: String, tension: Int, price: Double) -> Unit,
) {
    var daysAgo by remember { mutableStateOf(0.0) }
    var line by remember { mutableStateOf("") }
    var tension by remember { mutableStateOf(26.0) }
    var price by remember { mutableStateOf(0.0) }

    val date = remember(daysAgo) {
        val now = System.currentTimeMillis()
        val local = Instant.ofEpochMilli(now - (daysAgo * DAY_MS).toLong())
            .atZone(ZoneId.systemDefault())
        local.toInstant().toEpochMilli()
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        NumberStepper(
            label = "拉线日期",
            value = daysAgo,
            onValueChange = { daysAgo = it },
            range = 0.0..365.0,
            step = 1.0,
            format = { Formatters.sessionDate(date) },
        )
        AppTextField(value = line, onValueChange = { line = it }, label = "线型号（如 BG65）")
        NumberStepper(
            label = "磅数",
            value = tension,
            onValueChange = { tension = it },
            range = 18.0..35.0,
            step = 0.5,
            format = { "%.1f".format(it) },
        )
        NumberStepper(
            label = "价格",
            value = price,
            onValueChange = { price = it },
            range = 0.0..500.0,
            step = 5.0,
            unit = "元",
            format = { if (it <= 0.0) "—" else it.toInt().toString() },
        )
        PrimaryButton(
            text = "保存穿线记录",
            onClick = { onSubmit(date, line, tension.toInt(), price) },
        )
    }
}

@Composable
private fun AddRacketCard(
    repo: com.retropro.data.repository.EquipmentRepository,
    onAdd: (brand: String, model: String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var brand by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }
    val tags by repo.observeGearTags().collectAsStateWithLifecycle(initialValue = emptyList())

    SectionCard(title = "加一支球拍") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (expanded) {
                // 品牌从标签词库点选（与球鞋/球衣/手胶共用一套标签），也可手填
                HorizontalChipRow(fadeColor = AppColors.SurfaceFallback) {
                    tags.forEach { tag ->
                        SelectableChip(
                            text = tag.name,
                            selected = brand == tag.name,
                            onClick = { brand = if (brand == tag.name) "" else tag.name },
                        )
                    }
                }
                AppTextField(value = brand, onValueChange = { brand = it }, label = "品牌（可手填）")
                AppTextField(value = model, onValueChange = { model = it }, label = "型号（如 天斧 100ZZ）")
                PrimaryButton(
                    text = "添加",
                    onClick = {
                        onAdd(brand, model)
                        brand = ""
                        model = ""
                        expanded = false
                    },
                )
            } else {
                SecondaryButton(
                    text = "添加球拍",
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AddShuttleCard(onAdd: (brand: String, model: String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    var brand by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    SectionCard(title = "加一个羽毛球型号") {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (expanded) {
                AppTextField(value = brand, onValueChange = { brand = it }, label = "品牌")
                AppTextField(value = model, onValueChange = { model = it }, label = "型号（如 AS-05）")
                PrimaryButton(
                    text = "添加",
                    onClick = {
                        onAdd(brand, model)
                        brand = ""
                        model = ""
                        expanded = false
                    },
                )
            } else {
                SecondaryButton(
                    text = "添加球型号",
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- 其他装备（球鞋/球衣/手胶）

/** 装备分类。非球拍类共用一张 gear_items 表，category 区分 */
enum class GearCat(val code: String, val label: String) {
    SHOES("SHOES", "球鞋"),
    JERSEY("JERSEY", "球衣"),
    GRIP("GRIP", "手胶"),
}

/**
 * 单个分类的默认展示卡（无弹层时的平铺视图）。
 *
 * 列出该分类全部条目的精简信息；点击卡片整体弹出对应的管理 Popup Card
 * （添加/删除/启停都在弹层里做，主页面保持只读概览）。
 */
@Composable
private fun GearSummaryCard(
    category: GearCat,
    repo: com.retropro.data.repository.EquipmentRepository,
    onOpen: () -> Unit,
    onLongPress: (androidx.compose.ui.geometry.Offset) -> Unit = {},
) {
    val items by remember(category) { repo.observeGear(category.code) }
        .collectAsStateWithLifecycle(initialValue = emptyList())

    SectionCard(
        title = category.label,
        subtitle = "共 ${items.size} 件 · 点击卡片管理",
        // 短按弹管理卡、长按弹操作菜单，同一个识别器负责（两个会抢事件）
        modifier = Modifier.longPressable(
            key = "gear.${category.code}",
            onTap = onOpen,
            onLongPressAt = onLongPress,
        ),
    ) {
        Column(
            Modifier.padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (items.isEmpty()) {
                AppText(
                    "还没有${category.label}记录，点这里去添加",
                    AppTypography.Caption,
                    AppColors.TextTertiary,
                )
            }
            items.forEach { item ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column {
                        AppText(item.name, AppTypography.Body, AppColors.TextPrimary)
                        if (item.brand.isNotBlank()) {
                            AppText(item.brand, AppTypography.Caption, AppColors.TextTertiary)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (item.priceYuan > 0) {
                            AppText("¥${item.priceYuan.toLong()}", AppTypography.Body, AppColors.TextSecondary)
                        }
                        AppText(
                            if (item.isActive) "在用" else "退役",
                            AppTypography.Caption,
                            if (item.isActive) AppColors.SuccessSoft else AppColors.TextTertiary,
                        )
                    }
                }
            }
        }
    }
}

/**
 * 单个分类的装备列表 + 添加 + 标签管理。
 *
 * 条目数量级很小（几双鞋几件衣服），用 Column 而不是 Lazy —— 嵌在
 * 外层 LazyColumn 的 item 里不会产生嵌套滚动。
 */
@Composable
private fun GearListSection(
    category: GearCat,
    repo: com.retropro.data.repository.EquipmentRepository,
    /**
     * 请求删除（含二次确认）。
     *
     * 确认框挂在页面根 `Box`（和长按菜单同一层），所以这里**只上报意图**，
     * 不自己弹窗 —— 否则 `Dialog` 会在浮层内部再开一层窗口，
     * 且这里的 `pendingDelete` 状态根本不在本 composable 的作用域里。
     */
    onRequestDelete: (PendingDelete) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val items by remember(category) { repo.observeGear(category.code) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val tags by repo.observeGearTags().collectAsStateWithLifecycle(initialValue = emptyList())

    var showAdd by remember { mutableStateOf(false) }
    var showTags by remember { mutableStateOf(false) }

    // 16 个预设品牌在这里种下（装备页不走 ViewModel，放这最贴近使用点）
    LaunchedEffect(Unit) { repo.seedDefaultTagsIfEmpty() }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(
            title = category.label,
            subtitle = "记名称、品牌与价格；统计页会汇总各类花费",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (items.isEmpty()) {
                    AppText("还没有${category.label}记录", AppTypography.Caption, AppColors.TextTertiary)
                }
                items.forEach { item ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            AppText(item.name, AppTypography.CardTitle, AppColors.TextPrimary)
                            Spacer(Modifier.height(2.dp))
                            AppText(
                                text = buildString {
                                    if (item.brand.isNotBlank()) append(item.brand + " · ")
                                    if (item.priceYuan > 0) append("¥" + item.priceYuan.toLong() + " · ")
                                    append(if (item.isActive) "在用" else "已退役")
                                },
                                AppTypography.Caption,
                                AppColors.TextTertiary,
                            )
                        }
                        // 行内操作胶囊：名称列已 weight(1f)，这里不会再被压扁
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            SelectableChip(
                                text = if (item.isActive) "在用" else "启用",
                                selected = item.isActive,
                                onClick = { scope.launch { repo.setGearActive(item, !item.isActive) } },
                            )
                            SelectableChip(
                                text = "删除",
                                selected = false,
                                destructive = true,
                                // 与长按菜单的删除走同一条确认流程 ——
                                // 同一个 App 里"删东西要不要确认"不能有两种答案
                                onClick = {
                                    onRequestDelete(PendingDelete.GearItem(item.id, item.name))
                                },
                            )
                        }
                    }
                }
                PrimaryButton(text = "添加${category.label}", onClick = {
                    android.util.Log.i("GearUI", "add tapped")
                    showAdd = true
                })
            }
        }

        // ---- 标签（品牌词库）
        SectionCard(
            title = "标签 · 品牌",
            subtitle = "预设 16 个羽毛球品牌，可自定义增删；添加装备时从这里选",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showTags) {
                    // 展开态：每个标签带删除
                    FlowChips(
                        tags = tags,
                        onRemove = { tag -> onRequestDelete(PendingDelete.GearTagItem(tag.id, tag.name)) },
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AddTagInline(onAdd = { scope.launch { repo.addGearTag(it) } })
                        SecondaryButton(text = "收起", onClick = { showTags = false })
                    }
                } else {
                    FlowChips(tags = tags, onRemove = null)
                    SecondaryButton(text = "管理标签", onClick = { showTags = true })
                }
            }
        }
    }

    if (showAdd) {
        AddGearDialog(
            category = category,
            tags = tags,
            onDismiss = { showAdd = false },
            onConfirm = { name, brand, price ->
                scope.launch {
                    repo.addGear(
                        category = category.code,
                        name = name,
                        brand = brand,
                        priceYuan = price,
                        boughtDate = System.currentTimeMillis() / 86_400_000L,
                    )
                }
                showAdd = false
            },
        )
    }
}

/**
 * 品牌标签行 —— 横向滚动胶囊（16 个预设 + 自定义，换行会占掉半屏）。
 *
 * 删除态（[onRemove] != null）时每个胶囊右边带一个红色的 ×，
 * 整个「胶囊 + ×」作为一个可点单元，避免 × 太小点不中。
 */
@Composable
private fun FlowChips(
    tags: List<com.retropro.data.model.GearTag>,
    onRemove: ((com.retropro.data.model.GearTag) -> Unit)?,
) {
    HorizontalChipRow(fadeColor = AppColors.SurfaceFallback) {
        tags.forEach { tag ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                SelectableChip(text = tag.name, selected = false, onClick = {})
                if (onRemove != null) {
                    Box(
                        Modifier
                            .clip(AppShapes.Chip)
                            .clickable { onRemove(tag) }
                            .padding(horizontal = 6.dp, vertical = 8.dp),
                    ) {
                        AppText("×", AppTypography.Body, AppColors.OpponentSoft)
                    }
                }
            }
        }
    }
}

@Composable
private fun AddTagInline(onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.widthIn(min = 140.dp)) {
            AppTextField(value = text, onValueChange = { text = it }, label = "新标签")
        }
        PrimaryButton(
            text = "加标签",
            enabled = text.isNotBlank(),
            onClick = {
                onAdd(text)
                text = ""
            },
        )
    }
}

@Composable
private fun AddGearDialog(
    category: GearCat,
    tags: List<com.retropro.data.model.GearTag>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, brand: String, price: Double) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var brand by remember { mutableStateOf("") }
    var price by remember { mutableStateOf("") }

    // ⚠️ 这里必须用 Dialog（独立窗口）：GearListSection 嵌在 LazyColumn item 里，
    //    高度约束无界，fillMaxSize 的遮罩/居中 Box 会高度塌陷成 0 —— 对话框完全不可见。
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        SectionCard(
            title = "添加${category.label}",
            subtitle = "品牌从标签里点选，也可以直接在品牌框里手填",
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(value = name, onValueChange = { name = it }, label = "名称（如 65Z / 男款T恤）")
                // 品牌点选（横向滚动：预设 16 个品牌换行会把对话框撑得很高）
                HorizontalChipRow(fadeColor = AppColors.SurfaceFallback) {
                    tags.forEach { tag ->
                        SelectableChip(
                            text = tag.name,
                            selected = brand == tag.name,
                            onClick = { brand = if (brand == tag.name) "" else tag.name },
                        )
                    }
                }
                AppTextField(value = brand, onValueChange = { brand = it }, label = "品牌（可手填）")
                AppTextField(value = price, onValueChange = { price = it }, label = "价格（元，可空）")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(Modifier.weight(1f)) {
                        PrimaryButton(
                            text = "保存",
                            enabled = name.isNotBlank(),
                            onClick = {
                                onConfirm(name, brand, price.toDoubleOrNull() ?: 0.0)
                            },
                        )
                    }
                    Box(Modifier.weight(1f)) {
                        SecondaryButton(text = "取消", onClick = onDismiss)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------- 六分类管理卡（Popup 内容）

/** 球拍管理卡：现有 RacketCard 复用（含穿线展开），外加添加卡 */
@Composable
private fun RacketManageCard(
    repo: com.retropro.data.repository.EquipmentRepository,
    rackets: List<RacketWithLastString>,
    daysSince: Map<Long, Long>,
) {
    val scope = rememberCoroutineScope()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (rackets.isEmpty()) {
            SectionCard(title = "还没有球拍", subtitle = "先加一支，之后每次穿线都挂在它下面") {
                AppText("球线记录按球拍分层。", AppTypography.Caption, AppColors.TextSecondary)
            }
        }
        rackets.forEach { racket ->
            RacketCard(
                racket = racket,
                daysSince = daysSince[racket.id],
                onAddStringJob = { date, line, tension, price ->
                    scope.launch {
                        repo.addStringJob(racket.id, date, line, tension, price)
                    }
                },
            )
        }
        AddRacketCard(repo = repo) { brand, model ->
            scope.launch { repo.addRacket(brand, model) }
        }
    }
}

/** 球线管理卡：全部穿线记录（跨球拍）+ 添加表单（选球拍） */
@Composable
private fun LineManageCard(repo: com.retropro.data.repository.EquipmentRepository) {
    val jobs by repo.observeAllStringJobs().collectAsStateWithLifecycle(initialValue = emptyList())
    val rackets by repo.observeRackets().collectAsStateWithLifecycle(initialValue = emptyList())
    val scope = rememberCoroutineScope()

    var racketId by remember { mutableStateOf<Long?>(null) }
    var line by remember { mutableStateOf("") }
    var tension by remember { mutableStateOf(26.0) }
    var price by remember { mutableStateOf(50.0) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "添加穿线", subtitle = "先选球拍，再填线与磅数") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (rackets.isEmpty()) {
                    AppText("先在「球拍」里加一支拍", AppTypography.Caption, AppColors.TextTertiary)
                } else {
                    HorizontalChipRow(fadeColor = AppColors.SurfaceFallback) {
                        rackets.forEach { r ->
                            val name = "${r.brand} ${r.model}".trim()
                            SelectableChip(
                                text = name,
                                selected = racketId == r.id,
                                onClick = { racketId = r.id },
                            )
                        }
                    }
                    AppTextField(value = line, onValueChange = { line = it }, label = "线型号（如 BG65）")
                    NumberStepper("磅数", tension, { tension = it }, range = 18.0..35.0, step = 0.5, format = { "%.1f".format(it) })
                    NumberStepper("价格", price, { price = it }, range = 0.0..500.0, step = 5.0, unit = "元", format = { it.toInt().toString() })
                    PrimaryButton(
                        text = "记一次穿线",
                        enabled = racketId != null && line.isNotBlank(),
                        onClick = {
                            scope.launch {
                                repo.addStringJob(
                                    racketId = racketId!!,
                                    date = System.currentTimeMillis(),
                                    lineModel = line.trim(),
                                    tensionLbs = tension.toInt(),
                                    priceYuan = price,
                                )
                                line = ""
                            }
                        },
                    )
                }
            }
        }

        SectionCard(title = "全部穿线记录", subtitle = "跨球拍按日期倒序，共 ${jobs.size} 条") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (jobs.isEmpty()) {
                    AppText("还没有穿线记录", AppTypography.Caption, AppColors.TextTertiary)
                }
                jobs.forEach { job ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column {
                            AppText(job.racketName, AppTypography.Caption, AppColors.TextTertiary)
                            AppText(job.lineModel, AppTypography.Body, AppColors.TextPrimary)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            AppText("${job.tensionLbs} 磅", AppTypography.Body, AppColors.TextSecondary)
                            AppText(
                                Formatters.money(job.priceYuan),
                                AppTypography.Caption,
                                AppColors.TextTertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 球管理卡：型号列表 + 添加 */
@Composable
private fun BallManageCard(
    repo: com.retropro.data.repository.EquipmentRepository,
    onAdd: (String, String) -> Unit,
) {
    val shuttles by repo.observeShuttles().collectAsStateWithLifecycle(initialValue = emptyList())
    var brand by remember { mutableStateOf("") }
    var model by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionCard(title = "添加球型号", subtitle = "按型号记录用球消耗（支持 0.5 筒）") {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppTextField(value = brand, onValueChange = { brand = it }, label = "品牌（如 尤尼克斯）")
                AppTextField(value = model, onValueChange = { model = it }, label = "型号（如 AS-05）")
                PrimaryButton(
                    text = "添加球型号",
                    enabled = brand.isNotBlank() || model.isNotBlank(),
                    onClick = {
                        onAdd(brand, model)
                        brand = ""
                        model = ""
                    },
                )
            }
        }

        SectionCard(title = "球型号", subtitle = "共 ${shuttles.size} 种") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (shuttles.isEmpty()) {
                    AppText("还没有球型号", AppTypography.Caption, AppColors.TextTertiary)
                }
                shuttles.forEach { sh ->
                    AppText(
                        "${sh.brand} ${sh.model}".trim(),
                        AppTypography.Body,
                        AppColors.TextPrimary,
                    )
                }
            }
        }
    }
}
