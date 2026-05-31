package io.github.libxposed.api;

import androidx.annotation.NonNull;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

public abstract class XposedModule implements XposedInterface, XposedModuleInterface {
    public XposedModule(@NonNull XposedInterface base, @NonNull ModuleLoadedParam param) {
    }

    @NonNull
    @Override
    public MethodUnhooker<Method> hook(
            @NonNull Method origin,
            @NonNull Class<? extends Hooker> hooker
    ) {
        throw new UnsupportedOperationException("compile-only stub");
    }

    @NonNull
    @Override
    public MethodUnhooker<Method> hook(
            @NonNull Method origin,
            int priority,
            @NonNull Class<? extends Hooker> hooker
    ) {
        throw new UnsupportedOperationException("compile-only stub");
    }

    @NonNull
    @Override
    public <T> MethodUnhooker<Constructor<T>> hook(
            @NonNull Constructor<T> origin,
            @NonNull Class<? extends Hooker> hooker
    ) {
        throw new UnsupportedOperationException("compile-only stub");
    }

    @NonNull
    @Override
    public <T> MethodUnhooker<Constructor<T>> hook(
            @NonNull Constructor<T> origin,
            int priority,
            @NonNull Class<? extends Hooker> hooker
    ) {
        throw new UnsupportedOperationException("compile-only stub");
    }

    @Override
    public void log(@NonNull String message) {
        throw new UnsupportedOperationException("compile-only stub");
    }

    @Override
    public void log(@NonNull String message, @NonNull Throwable throwable) {
        throw new UnsupportedOperationException("compile-only stub");
    }
}
