package io.github.libxposed.api;

import android.content.pm.ApplicationInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

public interface XposedModuleInterface {
    interface ModuleLoadedParam {
        boolean isSystemServer();

        @NonNull
        String getProcessName();
    }

    interface SystemServerLoadedParam {
        @NonNull
        ClassLoader getClassLoader();
    }

    interface PackageLoadedParam {
        @NonNull
        String getPackageName();

        @NonNull
        ApplicationInfo getApplicationInfo();

        @RequiresApi(Build.VERSION_CODES.Q)
        @NonNull
        ClassLoader getDefaultClassLoader();

        @NonNull
        ClassLoader getClassLoader();

        boolean isFirstPackage();
    }

    default void onPackageLoaded(@NonNull PackageLoadedParam param) {
    }

    default void onSystemServerLoaded(@NonNull SystemServerLoadedParam param) {
    }
}
