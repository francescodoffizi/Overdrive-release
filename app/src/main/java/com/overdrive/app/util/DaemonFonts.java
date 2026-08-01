package com.overdrive.app.util;

import android.graphics.Paint;
import android.graphics.Typeface;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.lang.reflect.Method;

/**
 * Process-wide font bootstrap + text-draw guard for the CameraDaemon process.
 *
 * <h3>Why this exists</h3>
 * CameraDaemon runs as a standalone process launched via the PermissionGranter
 * shell wrapper — it is NOT forked from Zygote, so Android's normal font
 * preload never runs and the native {@code gDefaultTypeface} can stay
 * {@code null}. On DiLink 3/4 the native font system still initializes lazily
 * on first use, so {@link Typeface#create(Typeface, int)} works. On DiLink 5
 * the BSP ships a different font configuration ({@code /system/etc/fonts.xml}
 * layout), the lazy init fails, {@code Typeface.create(...)} returns
 * {@code null}, and — because the old renderer code only set the typeface
 * {@code if (created != null)} — the paints kept a {@code null} typeface. The
 * FIRST {@code Canvas.drawText}/{@code Paint.measureText} on such a paint then
 * tripped this native check inside {@code Typeface.cpp::resolveDefault()}:
 *
 * <pre>Assertion failed: src == nullptr &amp;&amp; gDefaultTypeface == nullptr</pre>
 *
 * which is a {@code LOG_ALWAYS_FATAL_IF} — it {@code abort()}s the whole
 * process (SIGABRT, exit code 134). A Java {@code try/catch} around the paint
 * setup CANNOT catch this: the abort is in native code, below the JVM, and it
 * fires at draw time, not at typeface-creation time.
 *
 * <h3>Two independent defenses</h3>
 * <ol>
 *   <li><b>Native default backstop.</b> {@link #bootstrap()} loads a real
 *       {@link Typeface} straight from a font FILE via
 *       {@link Typeface#createFromFile(File)} (that path reads the file bytes
 *       and never consults the missing default, so it succeeds even when
 *       {@code gDefaultTypeface == null}) and — <b>only when the platform
 *       default is detected as unusable</b> — installs it as the process
 *       default through {@code Typeface.setDefault()}, the only call that sets
 *       the <i>native</i> {@code gDefaultTypeface}. Once it is non-null, NO
 *       {@code drawText} anywhere in the process can hit the assertion, even on
 *       a paint this class never touched.</li>
 *   <li><b>Explicit per-paint typeface.</b> {@link #apply(Paint, int)} sets a
 *       guaranteed-non-null typeface on each text paint. It PREFERS the system
 *       face ({@code Typeface.create(SANS_SERIF, style)}) so the full glyph
 *       fallback chain (CJK / Arabic / emoji) is preserved on healthy devices,
 *       and substitutes the file-loaded face when the system family is not
 *       native-valid (the broken-BSP case). Crucially it checks the family's
 *       native pointer FIRST (a pure field read) and only calls
 *       {@code Typeface.create()} when that pointer is non-zero — because
 *       {@code create()} on a null-native family would itself run
 *       {@code resolveDefault()} and abort, which no Java {@code try/catch}
 *       could stop.</li>
 * </ol>
 * {@link #canDrawText()} reports whether any usable face exists at all; on the
 * pathological device where even the {@code .ttf} files are unreadable, the
 * renderers skip text (icons/shapes only) rather than risk the abort.
 *
 * <h3>No device detection — runtime capability, not model</h3>
 * There is NO {@code Build.MODEL} / "if DiLink 5" branch. The identical binary
 * ships to every device; behavior diverges purely on a one-time runtime probe
 * ({@link #systemDefaultUsable()}). A healthy BSP passes the probe and takes the
 * legacy path exactly; a broken one fails it and gets the file-face fallback +
 * native backstop. This is deliberately more robust than a model allowlist,
 * which a firmware update or custom ROM could invalidate.
 *
 * <h3>Regression safety on DiLink 3/4</h3>
 * On a healthy device {@link #apply} calls {@code Typeface.create(SANS_SERIF,
 * style)} — the exact call the renderers used before — so glyphs AND the
 * non-Latin fallback chain are unchanged, and the process default is left
 * ALONE ({@code setDefault} is NOT called, so no fallback chain is stripped).
 * The only behavioral change is on a device where the old code would have
 * aborted.
 *
 * <p>Thread-safe: {@link #bootstrap()} is idempotent and synchronized; the
 * cached faces are immutable once resolved and only read afterwards.
 */
public final class DaemonFonts {

    private static final DaemonLogger logger = DaemonLogger.getInstance("DaemonFonts");

    /**
     * Candidate font files, most-preferred first — the file face is only the
     * fallback used when the system face is unavailable. Covers the common AOSP
     * / BYD-BSP layouts across DiLink 3/4/5.
     */
    private static final String[] REGULAR_CANDIDATES = {
        "/system/fonts/Roboto-Regular.ttf",
        "/system/fonts/RobotoStatic-Regular.ttf",
        "/system/fonts/NotoSans-Regular.ttf",
        "/system/fonts/DroidSans.ttf",
        "/system/fonts/DroidSansFallback.ttf",
        "/system/fonts/NotoSansCJK-Regular.ttc",
    };
    private static final String[] BOLD_CANDIDATES = {
        "/system/fonts/Roboto-Bold.ttf",
        "/system/fonts/RobotoStatic-Bold.ttf",
        "/system/fonts/NotoSans-Bold.ttf",
        "/system/fonts/DroidSans-Bold.ttf",
    };

    private static volatile boolean bootstrapped = false;
    private static volatile boolean available = false;
    private static Typeface fileRegular;   // published before `bootstrapped` is set true
    private static Typeface fileBold;

    // Cached reflective handle to android.graphics.Typeface#native_instance —
    // the native SkTypeface pointer. Reading it is a pure field access with NO
    // native call, so unlike Typeface.create() it can never trip the abort.
    private static final java.lang.reflect.Field NATIVE_PTR_FIELD = findNativePtrField();

    private DaemonFonts() {}

    private static java.lang.reflect.Field findNativePtrField() {
        for (String name : new String[]{"native_instance", "mNativePtr"}) {
            try {
                java.lang.reflect.Field f = Typeface.class.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable ignored) {
                // try next candidate
            }
        }
        return null;
    }

    /**
     * @return {@code true} iff {@code tf} is non-null AND its native SkTypeface
     *         pointer is non-zero — i.e. {@code Typeface.create(tf, style)} and
     *         drawing with it are guaranteed NOT to hit the native
     *         {@code resolveDefault} abort. When the field cannot be read we
     *         return {@code false} (we cannot PROVE validity). Callers that need
     *         "prove broken" semantics use this directly; callers that must not
     *         regress a healthy device when the field is simply unreadable use
     *         {@link #familyProbablyUsable}.
     */
    private static boolean hasValidNative(Typeface tf) {
        if (tf == null) return false;
        if (NATIVE_PTR_FIELD == null) return false;
        try {
            return NATIVE_PTR_FIELD.getLong(tf) != 0L;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * "Optimistic" variant used only by {@link #apply}. Same as
     * {@link #hasValidNative} EXCEPT that when the native-pointer field is
     * unreadable ({@code NATIVE_PTR_FIELD == null}) it returns {@code true} for
     * a non-null family. Rationale: if we can't inspect the pointer we behave
     * exactly like the pre-fix code (call {@code create()} on the real family),
     * which is correct on every healthy device and no worse than the historical
     * behavior on a broken one. Being pessimistic here would instead STRIP the
     * fallback chain on healthy devices whenever reflection is unavailable — a
     * regression we must avoid.
     */
    private static boolean familyProbablyUsable(Typeface tf) {
        if (tf == null) return false;
        if (NATIVE_PTR_FIELD == null) return true;   // can't inspect → legacy behavior
        try {
            return NATIVE_PTR_FIELD.getLong(tf) != 0L;
        } catch (Throwable t) {
            return true;   // field present but read failed → still optimistic
        }
    }

    /**
     * Resolve a file-backed fallback face and install it as the process default
     * so the native {@code gDefaultTypeface} is non-null. Idempotent — call once
     * at daemon boot (recommended) and defensively from any renderer that might
     * construct before boot ran.
     *
     * @return {@code true} if text can be drawn safely; {@code false} only when
     *         neither a system face nor any font file is usable (renderers then
     *         skip text via {@link #canDrawText()}).
     */
    public static synchronized boolean bootstrap() {
        if (bootstrapped) return available;

        // Probe the platform default ONCE. This — not the device model — is how
        // we tell a healthy BSP (DiLink 3/4) from a broken one (DiLink 5). The
        // same code ships everywhere; only this runtime result differs.
        //   sysOk         : default is present AND native-valid (healthy).
        //   knownBroken   : default is present in Java but its native pointer is
        //                   ZERO — the exact abort condition (definitively bad).
        // If we can't read the native field at all, sysOk is false but
        // knownBroken is ALSO false — we stay optimistic and do NOT touch the
        // global default (avoids a false-positive regression); per-paint apply()
        // still protects every known text path.
        boolean sysOk = systemDefaultUsable();
        boolean knownBroken = systemDefaultKnownBroken();

        // 1) Load a guaranteed-good face from disk (createFromFile reads the
        //    file bytes and never consults the possibly-null default). Needed
        //    as the per-paint fallback in apply() and, on a broken BSP, as the
        //    process default installed below.
        fileRegular = loadFirst(REGULAR_CANDIDATES);
        if (fileRegular != null) {
            // A face from createFromFile is always native-valid when non-null,
            // so a plain null check is correct here (don't use hasValidNative,
            // which is false when the pointer field is merely unreadable).
            fileBold = loadFirst(BOLD_CANDIDATES);
            if (fileBold == null) {
                // Derive a synthetic bold from the file face. create() with a
                // native-valid family never consults the (possibly-null) default.
                try {
                    fileBold = Typeface.create(fileRegular, Typeface.BOLD);
                } catch (Throwable t) {
                    fileBold = fileRegular;
                }
                if (fileBold == null) fileBold = fileRegular;
            }
        }

        // 2) Native backstop — ONLY when the platform default is DEFINITIVELY
        //    broken. Installing our single-file face as the process default
        //    would STRIP the platform default's glyph fallback chain
        //    (CJK/Arabic/emoji), so we must never do it on a healthy device or
        //    when we're merely unsure. On a broken BSP it makes gDefaultTypeface
        //    non-null so any stray null-typeface paint (one apply() didn't
        //    touch) also cannot abort.
        boolean seeded = false;
        if (knownBroken && fileRegular != null) {
            seeded = installProcessDefault(fileRegular);
        }

        // Text is safe if the platform default already works OR we loaded a file
        // face (used both as the per-paint fallback in apply() and, above, as the
        // seeded default on the definitively-broken path).
        available = sysOk || (fileRegular != null);

        bootstrapped = true;
        logger.info("Font bootstrap: sysOk=" + sysOk
                + " knownBroken=" + knownBroken
                + " fileFace=" + (fileRegular != null)
                + " seededDefault=" + seeded
                + " → text " + (available ? "ENABLED" : "DISABLED (icons/shapes only)"));
        return available;
    }

    /** @return whether text can be drawn without risking the native null-default abort. */
    public static boolean canDrawText() {
        if (!bootstrapped) bootstrap();
        return available;
    }

    /**
     * Set a guaranteed-non-null SANS_SERIF typeface matching {@code style} on
     * {@code paint}. Equivalent to {@code apply(paint, Typeface.SANS_SERIF,
     * style)}. This is what {@code Typeface.DEFAULT} / {@code DEFAULT_BOLD}
     * resolve to, so it is a drop-in for the previous sans-serif paints.
     *
     * @param style {@link Typeface#NORMAL} or {@link Typeface#BOLD} (italic
     *              variants map to the upright base — the daemon overlays never
     *              used italics).
     */
    public static void apply(Paint paint, int style) {
        apply(paint, Typeface.SANS_SERIF, style);
    }

    /**
     * Set a guaranteed-non-null typeface of the requested {@code family} +
     * {@code style} on {@code paint}. Prefers the system face
     * ({@code Typeface.create(family, style)} — identical to the pre-fix code
     * path, so glyphs AND the CJK/Arabic/emoji fallback chain are unchanged on
     * healthy devices); substitutes the file-loaded fallback only when the
     * system face is unavailable (broken BSP). No-op when neither exists —
     * callers MUST additionally gate draws on {@link #canDrawText()}.
     *
     * <p>Note on the broken-BSP fallback: the disk face is sans-serif Roboto,
     * so a {@code MONOSPACE} request degrades to sans there. That is a cosmetic
     * downgrade that only occurs on a device where the alternative is a process
     * abort — an acceptable trade. Healthy devices always get the real family.
     *
     * @param family a platform family such as {@link Typeface#SANS_SERIF} or
     *               {@link Typeface#MONOSPACE}
     * @param style  {@link Typeface#NORMAL} or {@link Typeface#BOLD}
     */
    public static void apply(Paint paint, Typeface family, int style) {
        if (paint == null) return;
        if (!bootstrapped) bootstrap();
        boolean bold = (style == Typeface.BOLD || style == Typeface.BOLD_ITALIC);

        Typeface tf = null;

        // Only call Typeface.create(family, ...) when `family` looks usable. On
        // a broken BSP the platform families (SANS_SERIF / MONOSPACE) can wrap a
        // NULL native SkTypeface; create() would then run resolveDefault(null)
        // and ABORT natively — a Java try/catch cannot catch that. familyProbably
        // Usable() pre-checks the pointer (a pure field read); when the field is
        // unreadable it stays optimistic so healthy devices keep the real family
        // + fallback chain exactly as before.
        if (familyProbablyUsable(family)) {
            try {
                tf = Typeface.create(family, bold ? Typeface.BOLD : Typeface.NORMAL);
            } catch (Throwable ignored) {
                // create() shouldn't throw; never let paint setup crash.
            }
            // If we CAN read the pointer and it's zero, discard and fall back.
            // If we can't read it, trust create()'s non-null result (legacy).
            if (NATIVE_PTR_FIELD != null && !hasValidNative(tf)) tf = null;
        }

        // Broken BSP (or family unusable): fall back to the disk face, which is
        // always native-valid (loaded via createFromFile), so the paint ends up
        // with a NON-null typeface and drawText cannot abort.
        if (tf == null) tf = bold ? fileBold : fileRegular;
        if (tf == null) tf = fileRegular;

        if (tf != null) paint.setTypeface(tf);
        // else: no face at all — leave paint untouched; canDrawText() is false
        // and the caller will skip drawText entirely.
    }

    private static Typeface loadFirst(String[] paths) {
        for (String p : paths) {
            try {
                File f = new File(p);
                if (!f.exists() || !f.canRead()) continue;
                Typeface tf = Typeface.createFromFile(f);
                if (tf != null) {
                    logger.debug("Loaded fallback font: " + p);
                    return tf;
                }
            } catch (Throwable t) {
                logger.debug("Font load skipped (" + p + "): " + t.getMessage());
            }
        }
        return null;
    }

    /**
     * @return true if the platform already has a usable default face — i.e.
     *         {@code Typeface.DEFAULT} exists AND its native pointer is
     *         non-zero. This is a pure field read via {@link #hasValidNative};
     *         it must NOT call {@code Typeface.create()}, because on the broken
     *         BSP that call would itself abort the process rather than return.
     */
    private static boolean systemDefaultUsable() {
        try {
            return hasValidNative(Typeface.DEFAULT)
                    || hasValidNative(Typeface.SANS_SERIF);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @return true only when we can PROVE the platform default is broken: the
     *         native-pointer field is readable AND both DEFAULT and SANS_SERIF
     *         report a zero native pointer. When the field is unreadable we
     *         return false (we don't know → don't touch the global default).
     *         This gates the global backstop so it never fires on a healthy or
     *         merely-uncertain device.
     */
    private static boolean systemDefaultKnownBroken() {
        if (NATIVE_PTR_FIELD == null) return false;   // can't tell → assume healthy
        try {
            return !hasValidNative(Typeface.DEFAULT)
                    && !hasValidNative(Typeface.SANS_SERIF);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Install {@code face} as the process default via {@code Typeface.setDefault},
     * which sets the <i>native</i> {@code gDefaultTypeface}. This is the crucial
     * backstop: with a non-null native default, {@code resolveDefault()} can no
     * longer abort for ANY null-typeface paint anywhere in the process.
     *
     * <p>{@code setDefault} is greylisted on newer API levels, so it is invoked
     * reflectively and every failure is swallowed — the explicit
     * {@link #apply(Paint, int)} calls remain the primary defense if reflection
     * is blocked.
     *
     * @return true if the default was successfully installed.
     */
    private static boolean installProcessDefault(Typeface face) {
        try {
            // setDefault(Typeface) is `private static` on every API level (and
            // @UnsupportedAppUsage(maxTargetSdk=P)). getMethod() returns only public
            // members, so it would always throw NoSuchMethodException here. Use
            // getDeclaredMethod + setAccessible; targetSdk=25 <= maxTargetSdk P keeps
            // this within hidden-API greylist policy on the daemon target. A genuine
            // hidden-API block still degrades gracefully via the catch below.
            Method setDefault = Typeface.class.getDeclaredMethod("setDefault", Typeface.class);
            setDefault.setAccessible(true);
            setDefault.invoke(null, face);
            logger.debug("Installed process default typeface (native gDefaultTypeface seeded)");
            return true;
        } catch (Throwable t) {
            logger.debug("Typeface.setDefault unavailable (" + t.getMessage()
                    + ") — relying on per-paint apply()");
            return false;
        }
    }
}
