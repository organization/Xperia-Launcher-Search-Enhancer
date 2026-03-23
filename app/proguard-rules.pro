-adaptresourcefilecontents META-INF/xposed/java_init.list

-keep,allowobfuscation,allowoptimization public class * extends io.github.libxposed.api.XposedModule {
    public void onModuleLoaded(...);
    public void onPackageLoaded(...);
    public void onPackageReady(...);
    public void onSystemServerStarting(...);
}
-keep,allowshrinking,allowoptimization,allowobfuscation class ** implements io.github.libxposed.api.XposedInterface$Hooker
-keepclassmembers,allowoptimization class ** implements io.github.libxposed.api.XposedInterface$Hooker {
    java.lang.Object intercept(io.github.libxposed.api.XposedInterface$Chain);
}

-repackageclasses
-allowaccessmodification
