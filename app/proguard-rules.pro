# MIUIX / Compose 相关类通过 R8 的默认规则处理。
# M0 阶段未开启混淆（isMinifyEnabled = false），此文件为后续里程碑预留。

# 保留 Compose Runtime 的合成方法
-keepclassmembers class ** {
    @androidx.compose.runtime.Composable <methods>;
}

# 液态玻璃的 RuntimeShader 相关（反射调用风险）
-keep class androidx.graphics.shapes.** { *; }
-keep class io.github.kyant0.** { *; }
