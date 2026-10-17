# Keep JNI entry points stable for libantidigitalsmod.so.
-keep class com.digitalsmod.digitalliquid.NativeIntegrity { *; }
-keepclasseswithmembernames class * { native <methods>; }

# Android instantiates these components from the manifest.
-keep class com.digitalsmod.digitalliquid.IslandService { *; }
-keep class com.digitalsmod.digitalliquid.HyperIslandVisibilityService { *; }
-keep class com.digitalsmod.digitalliquid.IslandNotificationListener { *; }
-keep class com.digitalsmod.digitalliquid.DigitalLiquidVoiceInteractionService { *; }
-keep class com.digitalsmod.digitalliquid.DigitalLiquidVoiceInteractionSessionService { *; }
-keep class com.digitalsmod.digitalliquid.DigitalLiquidBootReceiver { *; }
-keep class com.digitalsmod.digitalliquid.DigitalLiquidAssistantCommandReceiver { *; }
-keep class com.digitalsmod.digitalliquid.AssistantFallbackActivity { *; }
