package com.retropro

import android.app.Application
import com.retropro.di.AppGraph
import com.retropro.glass.GlassRuntime

/**
 * Application 入口。
 *
 *  1. 做一次液态玻璃能力检测并写入 [GlassRuntime]，
 *     后续所有玻璃渲染都读取这个结果，避免每帧重复探测。
 *  2. 初始化 [AppGraph]（只存 applicationContext，数据库懒加载，
 *     不拖慢冷启动）。
 */
class RetroProApp : Application() {

    override fun onCreate() {
        super.onCreate()
        GlassRuntime.init(this)
        AppGraph.init(this)
    }
}
