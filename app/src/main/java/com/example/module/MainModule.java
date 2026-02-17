package com.example.module;

import android.annotation.SuppressLint;

import androidx.annotation.NonNull;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.annotations.BeforeInvocation;
import io.github.libxposed.api.annotations.XposedHooker;

/**
 * This is the entry point class for the Xposed module.
 * Customization suggestions:
 * 1. Change the package name `com.example.module` to your own.
 * 2. Add your Hook logic in `onSystemServerLoaded` or `onPackageLoaded`.
 */
@SuppressLint({"PrivateApi", "BlockedPrivateApi"})
public class MainModule extends XposedModule {

    public MainModule(XposedInterface base, ModuleLoadedParam param) {
        super(base, param);
    }

    @Override
    public void onSystemServerLoaded(@NonNull SystemServerLoadedParam param) {
        super.onSystemServerLoaded(param);
        // Add Hook logic for System Server here
        // For example:
        // try {
        //     var classLoader = param.getClassLoader();
        //     var clazz = classLoader.loadClass("com.android.server.wm.WindowManagerService");
        //     // hook(method, MyHooker.class);
        // } catch (Throwable t) {
        //     log("Hook failed", t);
        // }
    }

    @Override
    public void onPackageLoaded(@NonNull PackageLoadedParam param) {
        super.onPackageLoaded(param);
        // Add Hook logic for specific applications here
        // if (param.getPackageName().equals("com.target.package")) {
        //     // ...
        // }
    }

    /**
     * This is a simple Hooker example.
     */
    @XposedHooker
    private static class ExampleHooker implements Hooker {
        @BeforeInvocation
        public static void before(@NonNull BeforeHookCallback callback) {
            // Logic to execute before method execution
        }

        // @AfterInvocation
        // public static void after(@NonNull AfterHookCallback callback) {
        //     // Logic to execute after method execution
        // }
    }
}
