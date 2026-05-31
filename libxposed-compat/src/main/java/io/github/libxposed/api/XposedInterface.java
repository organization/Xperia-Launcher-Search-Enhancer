package io.github.libxposed.api;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Member;
import java.lang.reflect.Method;

public interface XposedInterface {
    int API = 100;
    int PRIORITY_DEFAULT = 50;
    int PRIORITY_LOWEST = -10000;
    int PRIORITY_HIGHEST = 10000;

    interface BeforeHookCallback {
        @NonNull
        Member getMember();

        @Nullable
        Object getThisObject();

        @NonNull
        Object[] getArgs();

        void returnAndSkip(@Nullable Object result);

        void throwAndSkip(@Nullable Throwable throwable);
    }

    interface AfterHookCallback {
        @NonNull
        Member getMember();

        @Nullable
        Object getThisObject();

        @NonNull
        Object[] getArgs();

        @Nullable
        Object getResult();

        @Nullable
        Throwable getThrowable();

        boolean isSkipped();

        void setResult(@Nullable Object result);

        void setThrowable(@Nullable Throwable throwable);
    }

    interface Hooker {
    }

    interface MethodUnhooker<T> {
        @NonNull
        T getOrigin();

        void unhook();
    }

    @NonNull
    MethodUnhooker<Method> hook(@NonNull Method origin, @NonNull Class<? extends Hooker> hooker);

    @NonNull
    MethodUnhooker<Method> hook(
            @NonNull Method origin,
            int priority,
            @NonNull Class<? extends Hooker> hooker
    );

    @NonNull
    <T> MethodUnhooker<Constructor<T>> hook(
            @NonNull Constructor<T> origin,
            @NonNull Class<? extends Hooker> hooker
    );

    @NonNull
    <T> MethodUnhooker<Constructor<T>> hook(
            @NonNull Constructor<T> origin,
            int priority,
            @NonNull Class<? extends Hooker> hooker
    );

    void log(@NonNull String message);

    void log(@NonNull String message, @NonNull Throwable throwable);
}
