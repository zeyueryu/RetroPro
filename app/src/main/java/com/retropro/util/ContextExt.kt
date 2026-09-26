package com.retropro.util

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper

/**
 * 从 Compose 的 `LocalContext` 拿到宿主 [Activity]。
 *
 * 为什么不能直接 `context as Activity`：Compose 树里的 Context 常常是
 * `ContextThemeWrapper` 之类的包装，直接强转会抛 `ClassCastException`。
 * 必须沿 [ContextWrapper.baseContext] 一路往上找。
 *
 * 用途：计分板需要临时锁定横屏、并加 `FLAG_KEEP_SCREEN_ON`。
 */
fun Context.findActivity(): Activity? {
    var ctx: Context? = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
