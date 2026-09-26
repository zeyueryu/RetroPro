package com.retropro.uikit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.utils.MiuixPopupUtils

/**
 * MIUIX 弹出层宿主壳 —— 只是为了让 `MiuixPopupHost` 有地方挂。
 *
 * ## 为什么需要这一层
 *
 * MIUIX 的 `OverlayListPopup` / `OverlayIconCascadingDropdownMenu` 等 overlay 系列组件
 * 官方前置条件写明：**必须用在 `Scaffold` 内部**，因为它们依赖 Scaffold 提供的
 * `MiuixPopupHost` 来渲染弹窗内容；不在 Scaffold 里的话弹窗内容**不会被渲染**
 * （表现是点按钮毫无反应，且不报错 —— 很容易误判成手势没接上）。
 *
 * 但本项目的主容器是自研的 `GlassScene`（`glass/GlassSurface.kt`），它承担
 * `layerBackdrop` 折射源的角色。**绝不能**把它换成 MIUIX `Scaffold`：
 * 那会让玻璃面板落进自己被录制的图层里 → 自引用 → RenderThread 栈溢出
 * （本项目在 MemoCard 项目上真实崩溃过，是硬规则）。
 *
 * 所以这里提供**最小侵入**的做法：一个只挂 `popupHost`、不参与视觉的 Scaffold 壳。
 *
 * ## ⚠️ 用法约束（踩过坑，务必遵守）
 *
 * **必须包在"整页"这一层**，不要只包触发弹窗的那个小按钮。原因：
 * `Scaffold` 的内容槽是 `SubcomposeLayout`，它会按 `contentWindowInsets` 布局并
 * 让内容区填充可用空间 —— 只包一个小按钮的话，那个按钮会被撑成整屏，
 * 原本靠 `Modifier.align(Alignment.TopEnd)` 做的右上角定位会失效（跑到屏幕中心）。
 *
 * 正确：在页面最外层包一层，页面内部照常用 `Box` + `align` 定位。
 *
 * 同时**不要嵌套两层** `MiuixPopupScope` —— 里层 Scaffold 同样会撑满，
 * 效果同"只包小按钮"。
 *
 * ## 为什么包一层而不是直接用 Scaffold
 *
 * 项目硬规则：MIUIX 组件必须包一层自建 Wrapper ——
 * 其官方标注 experimental，API 可能随版本变更，收口在一处便于跟进。
 * 这样以后 MIUIX 改 `Scaffold` / `MiuixPopupHost` 签名，只需改这个文件。
 *
 * ## 两个参数为什么要显式给
 *
 * - `containerColor = Color.Transparent`：不给的话 Scaffold 会画出自己的默认底色，
 *   直接盖掉页面背景（本项目背景是自绘的 `AppBackground`，会被糊掉）。
 * - 内容槽忽略 `PaddingValues`：本项目页面各自用 `statusBarsPadding()` /
 *   `navBarClearance()` 处理安全区，全局再垫一层会双重留白。
 *
 * @param modifier 作用在 Scaffold 外层。页面级使用时一般传 `Modifier.fillMaxSize()`。
 * @param content 页面内容。
 */
@Composable
fun MiuixPopupScope(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Scaffold(
        modifier = modifier,
        // 透明底：项目背景由 AppBackground 自绘，不能被 Scaffold 默认色盖住
        containerColor = Color.Transparent,
        // 这个壳只负责提供 popupHost，其余槽位全部留空
        popupHost = { MiuixPopupUtils.Companion.MiuixPopupHost() },
    ) { _: PaddingValues ->
        Box(Modifier.fillMaxSize()) { content() }
    }
}
