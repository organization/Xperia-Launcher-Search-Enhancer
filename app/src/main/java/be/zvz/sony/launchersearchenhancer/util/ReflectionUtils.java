package be.zvz.sony.launchersearchenhancer.util;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class ReflectionUtils {

    private ReflectionUtils() {}

    public static Method findMethod(Class<?> startClass, String name, Class<?>... params)
            throws NoSuchMethodException {
        for (Class<?> c = startClass; c != null; c = c.getSuperclass()) {
            try {
                Method m = c.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new NoSuchMethodException(name);
    }

    public static Field findField(Class<?> startClass, String name) throws NoSuchFieldException {
        for (Class<?> c = startClass; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {
            }
        }
        throw new NoSuchFieldException(name);
    }
}
