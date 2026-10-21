package com.googlebook.fsrbridge;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/** Tests normal and exceptional scope recovery without claiming a renderer launch. */
public final class FinalViewportScopeTest {
    public static void main(String[] args) throws Exception {
        Field active = Fsr3Bridge.class.getDeclaredField("inFinalPass");
        active.setAccessible(true);
        List<Boolean> scopes = new ArrayList<>();
        FinalViewportUniforms uniforms = () -> {
            try {
                scopes.add(active.getBoolean(null));
            } catch (IllegalAccessException exception) {
                throw new AssertionError(exception);
            }
        };
        Fsr3Bridge.beginFinalPass(uniforms);
        Fsr3Bridge.endFinalPass();
        Fsr3Bridge.resetFinalPassScope();
        if (!scopes.equals(List.of(true, false))) throw new AssertionError("normal scope restoration");
        scopes.clear();
        Fsr3Bridge.beginFinalPass(uniforms);
        Fsr3Bridge.resetFinalPassScope();
        if (!scopes.equals(List.of(true, false))) throw new AssertionError("aborted frame restoration");
        FinalViewportUniforms failing = () -> { throw new IllegalStateException("injected refresh failure"); };
        try {
            Fsr3Bridge.beginFinalPass(failing);
            throw new AssertionError("refresh failure hidden");
        } catch (IllegalStateException expected) { /* Recovery must still clear the entered scope. */ }
        try {
            Fsr3Bridge.resetFinalPassScope();
            throw new AssertionError("restoration failure hidden");
        } catch (IllegalStateException expected) { /* Callback is detached before it is retried. */ }
        Fsr3Bridge.resetFinalPassScope();
        if (active.getBoolean(null)) throw new AssertionError("failed callback left final scope active");
        System.out.println("PASS final viewport normal/aborted/failed scope contracts");
    }
}
