# R8 规则：只压缩（移除未使用的代码和资源），不混淆。
# 开启混淆后点击扫码会崩溃，所以关闭混淆，并完整保留 ML Kit 扫码相关的类。

# 不混淆：类名、方法名、字段名都保持原样，崩溃堆栈也可以直接阅读
-dontobfuscate

# ML Kit 条码识别：原生库通过 JNI 按名字访问字段，内部 protobuf 通过反射按字段名解析，
# 不能被重命名或被当作未使用而删除
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.libraries.barhopper.** { *; }
-keep class com.google.barhopper.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
