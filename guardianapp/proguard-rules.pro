# 注解与签名信息（kotlin 元数据等排错需要）
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# 守护模块：状态类与事件序列化依赖具体实现名，与家人侠 release 同一保留策略
-keep class io.github.xiaohaoz1.jiarenxia.guardian.** { *; }

# kotlinx / kotlin 运行时的常见未解析告警（引用平台不存在的类，属正常）
-dontwarn kotlin.**
-dontwarn kotlinx.**
-dontwarn org.jetbrains.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn com.google.errorprone.**
-dontwarn javax.annotation.**
