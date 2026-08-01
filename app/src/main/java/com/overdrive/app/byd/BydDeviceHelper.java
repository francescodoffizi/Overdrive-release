package com.overdrive.app.byd;

import android.content.Context;

import com.overdrive.app.logging.DaemonLogger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Reflection utilities for safely accessing BYD SDK devices.
 * Every method is null-safe and exception-safe — never crashes.
 */
public final class BydDeviceHelper {

    private static final DaemonLogger logger = DaemonLogger.getInstance("BydDeviceHelper");

    /**
     * Get a BYD device singleton via reflection.
     * Returns null if the device class doesn't exist or getInstance fails.
     */
    public static Object getDevice(String className, Context context) {
        try {
            Class<?> cls = Class.forName(className);
            Method getInstance = cls.getMethod("getInstance", Context.class);
            Object device = getInstance.invoke(null, context);
            if (device != null) {
                logger.info("Device OK: " + cls.getSimpleName());
            } else {
                logger.info("Device NULL: " + cls.getSimpleName());
            }
            return device;
        } catch (ClassNotFoundException e) {
            logger.debug("Device class not found: " + className);
        } catch (Exception e) {
            logger.debug("Device init failed: " + className + " — " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a no-arg getter method on a device. Returns null on failure.
     *
     * Uses a per-(Class, methodName) Method cache keyed on the device's
     * runtime Class — proxy classes (e.g. BYDAuto*Device$Stub$Proxy) are
     * process-stable, so a cached Method survives binder service restarts
     * that swap the underlying instance.
     */
    public static Object callGetter(Object device, String methodName) {
        if (device == null) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicNoArgMethodCache, NO_PARAMS);
        if (m == null) {
            // getMethod missed — try the declared-method walk (private/package
            // visibility on the proxy or its supertypes).
            return callGetterDeclared(device, methodName);
        }
        try {
            return m.invoke(device);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug("Getter " + methodName + " threw: " + (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug("Getter " + methodName + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a getter with one int parameter.
     */
    public static Object callGetter(Object device, String methodName, int param) {
        if (device == null) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicIntMethodCache, INT_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, param);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug("Getter " + methodName + "(" + param + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug("Getter " + methodName + "(" + param + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a method with one int parameter.
     * Used for SDK methods like voiceCtlMoonRoof(int), voiceCtlSunshadePanel(int).
     */
    public static Object callMethod(Object device, String methodName, int param1) {
        if (device == null) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicIntMethodCache, INT_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, param1);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + param1 + ") threw: " +
                    (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + param1 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a method with two int parameters.
     * Used for SDK methods like setAcWindLevel(int, int), setAcWindMode(int, int),
     * setSeatHeatingState(int, int), setSeatVentilatingState(int, int).
     */
    public static Object callMethod(Object device, String methodName, int param1, int param2) {
        if (device == null) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicIntIntMethodCache, INT_INT_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, param1, param2);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + param1 + ", " + param2 + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + param1 + ", " + param2 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call a method with four int parameters.
     * Used for SDK methods like setAcTemperature(int zone, int temp, int, int),
     * setAllWindowState(int lf, int rf, int lr, int rr).
     */
    public static Object callMethod(Object device, String methodName, int p1, int p2, int p3) {
        if (device == null) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicInt3MethodCache, INT3_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, p1, p2, p3);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }
    public static Object callMethod(Object device, String methodName, int p1, int p2, int p3, int p4) {
        if (device == null) return null;
        Method m = lookupPublicMethodCached(device.getClass(), methodName,
                publicInt4MethodCache, INT4_PARAMS);
        if (m == null) return null;
        try {
            return m.invoke(device, p1, p2, p3, p4);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ", " + p4 + ") threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug(methodName + "(" + p1 + ", " + p2 + ", " + p3 + ", " + p4 + ") failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    private static Object callGetterDeclared(Object device, String methodName) {
        Class<?> cls = device.getClass();
        Method m = lookupDeclaredNoArgCached(cls, methodName);
        if (m == null) return null;
        try {
            return m.invoke(device);
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            logger.debug("DeclaredGetter " + methodName + " threw: " +
                (cause != null ? cause.getClass().getSimpleName() + ": " + cause.getMessage() : "unknown"));
        } catch (Exception e) {
            logger.debug("DeclaredGetter " + methodName + " failed: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return null;
    }

    /**
     * Call the generic get(int[], Class) method on a BYD device.
     * This is the correct SDK signature for reading feature ID values.
     * Falls back to get(int, int) if the array signature isn't found.
     *
     * <p><b>Primitive vs wrapper Class.</b> The HAL dispatches on the EXACT {@code Class}
     * object passed as the second argument, and it recognises the PRIMITIVE ones
     * ({@code Double.TYPE}) — not the wrappers ({@code Double.class}), which are a different
     * {@code Class} instance entirely. The OEM reference app proves this: its feature reader
     * probes {@code {Double.TYPE, Float.TYPE, Integer.TYPE, Long.TYPE}} in a loop and catches
     * {@code IllegalArgumentException} to advance to the next — you only write that if the
     * HAL rejects a type it doesn't match. Callers here have historically passed the wrapper,
     * so reads that should have worked returned null (the "PHEV charging power never reads"
     * bug). {@link #normalizePrimitive} maps a wrapper to its primitive so every existing
     * call site is fixed without touching it, and {@link #callGetProbing} tries the OEM's
     * full ladder when one type isn't enough.
     */
    public static Object callGet(Object device, int featureId, Class<?> returnType) {
        if (device == null) return null;
        try {
            Method m = findGetMethod(device);
            if (m != null) {
                Class<?>[] params = m.getParameterTypes();
                if (params.length == 2 && params[0] == int[].class) {
                    return m.invoke(device, new int[]{featureId}, normalizePrimitive(returnType));
                } else if (params.length == 2 && params[0] == int.class) {
                    return m.invoke(device, featureId, 0);
                }
            }
        } catch (Exception e) {
            logger.debug("callGet failed for id=" + featureId + " — " + e.getMessage());
        }
        return null;
    }

    /**
     * Read a feature ID through the {@code get(int deviceType, int featureId)} overload with
     * an EXPLICIT device type, rather than letting {@link #callGet} pick a signature.
     *
     * <p>Needed because {@link #callGet} prefers the {@code get(int[], Class)} form and only
     * falls back to {@code (int,int)} when the array form is absent entirely — so on a device
     * exposing both, the {@code (int,int)} pairing is never attempted. The OEM app reads its
     * battery-health index exactly this way (device type 1014 for the STATISTIC family) as its
     * second-tier attempt, so some trims answer here and nowhere else.
     *
     * @return the raw returned object (usually a boxed number), or null when unavailable.
     */
    public static Object callGetWithDeviceType(Object device, int deviceType, int featureId) {
        if (device == null) return null;
        try {
            Method m = lookupPublicMethodCached(device.getClass(), "get",
                    publicIntIntMethodCache, INT_INT_PARAMS);
            if (m == null) return null;
            return m.invoke(device, deviceType, featureId);
        } catch (Exception e) {
            logger.debug("callGetWithDeviceType(" + deviceType + ", 0x"
                    + Integer.toHexString(featureId) + ") failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * Map a boxed wrapper Class to its primitive {@code TYPE}, leaving anything else
     * untouched. The BYD HAL's {@code get(int[], Class)} matches primitives only (see
     * {@link #callGet}), so this makes a wrapper argument work instead of silently reading
     * nothing.
     */
    private static Class<?> normalizePrimitive(Class<?> t) {
        if (t == Double.class)  return Double.TYPE;
        if (t == Integer.class) return Integer.TYPE;
        if (t == Float.class)   return Float.TYPE;
        if (t == Long.class)    return Long.TYPE;
        if (t == Short.class)   return Short.TYPE;
        if (t == Boolean.class) return Boolean.TYPE;
        return t;
    }

    /**
     * Read a feature ID trying each primitive type in turn until one yields a usable value —
     * the OEM reference app's own strategy ({@code {Double.TYPE, Float.TYPE, Integer.TYPE,
     * Long.TYPE}} with an {@code IllegalArgumentException} catch per attempt).
     *
     * <p>Use this for values whose HAL-side width is not known for the trim in hand: the same
     * feature ID is a double on one firmware and an int on another, and guessing wrong reads
     * nothing. Returns the first non-null result (which may be a {@code BYDAutoEventValue} —
     * pass it through {@link #getDoubleValue} / {@link #getIntValue} as usual), or null when
     * no type worked.
     */
    public static Object callGetProbing(Object device, int featureId) {
        return callGetProbing(device, featureId, false);
    }

    /**
     * Integer-first variant of {@link #callGetProbing(Object, int)}, for callers that extract
     * the result with {@link #getIntValue}.
     *
     * <p><b>Why the order is not cosmetic.</b> {@code BYDAutoEventValue} carries INDEPENDENT
     * {@code intValue} / {@code doubleValue} fields, and the HAL fills only the one matching the
     * requested type. {@code getIntValue} always reads {@code intValue}. So if a Double-first
     * ladder wins on a feature the caller then reads as an int, {@code intValue} is still at its
     * default 0 — and 0 is IN BAND for some scales (a raw cell-temp code of 0 means -40 °C),
     * so it is not rejected and a plausible-looking wrong number is published. Asking for the
     * width you intend to read keeps the two in agreement.
     *
     * @param intFirst true → try {@code Integer.TYPE} before the wider types.
     */
    public static Object callGetProbing(Object device, int featureId, boolean intFirst) {
        if (device == null) return null;
        Method m = findGetMethod(device);
        if (m == null) return null;
        Class<?>[] params = m.getParameterTypes();
        // Only the (int[], Class) form takes a type argument; the (int,int) form has nothing
        // to probe, so a single call is all there is.
        if (!(params.length == 2 && params[0] == int[].class)) {
            try {
                if (params.length == 2 && params[0] == int.class) return m.invoke(device, featureId, 0);
            } catch (Exception e) {
                logger.debug("callGetProbing(int,int) failed id=0x" + Integer.toHexString(featureId)
                        + " — " + e.getMessage());
            }
            return null;
        }
        Class<?>[] ladder = intFirst
                ? new Class<?>[]{ Integer.TYPE, Long.TYPE, Double.TYPE, Float.TYPE }
                : new Class<?>[]{ Double.TYPE, Float.TYPE, Integer.TYPE, Long.TYPE };
        for (Class<?> t : ladder) {
            try {
                Object r = m.invoke(device, new int[]{featureId}, t);
                if (r != null) return r;
            } catch (IllegalArgumentException iae) {
                // HAL refused this width — try the next, exactly as the OEM reader does.
            } catch (Exception e) {
                logger.debug("callGetProbing id=0x" + Integer.toHexString(featureId)
                        + " type=" + t.getSimpleName() + " failed: " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * Extract intValue from a BYDAutoEventValue object.
     */
    public static int getIntValue(Object eventValue) {
        if (eventValue == null) return Integer.MIN_VALUE;
        try {
            // Direct field access — BYDAutoEventValue.intValue is public
            Field f = eventValue.getClass().getField("intValue");
            return f.getInt(eventValue);
        } catch (Exception e) {
            // Try as Integer directly (some get() calls return boxed primitives)
            if (eventValue instanceof Integer) return (Integer) eventValue;
            if (eventValue instanceof Number) return ((Number) eventValue).intValue();
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Extract doubleValue from a BYDAutoEventValue object.
     */
    public static double getDoubleValue(Object eventValue) {
        if (eventValue == null) return Double.NaN;
        try {
            Field f = eventValue.getClass().getField("doubleValue");
            return f.getDouble(eventValue);
        } catch (Exception e) {
            if (eventValue instanceof Double) return (Double) eventValue;
            if (eventValue instanceof Number) return ((Number) eventValue).doubleValue();
        }
        return Double.NaN;
    }

    /**
     * Extract stringValue from a BYDAutoEventValue object.
     */
    public static String getStringValue(Object eventValue) {
        if (eventValue == null) return null;
        try {
            Field f = eventValue.getClass().getField("stringValue");
            return (String) f.get(eventValue);
        } catch (Exception e) {
            if (eventValue instanceof String) return (String) eventValue;
        }
        return null;
    }

    /**
     * Resolve the IBYDAutoListener-derived interface for a given device class
     * by inspecting registerListener parameter types. Some devices (e.g. ADAS)
     * declare a derived interface (IBYDAutoADASListener) instead of the base
     * IBYDAutoListener — Class.forName on the base name would still find a
     * class, but the proxy must implement the device-specific subtype or
     * registerListener.invoke fails with IllegalArgumentException.
     */
    private static Class<?> getListenerInterface(Class<?> cls, String listenerInterfaceName) {
        if (cls != null) {
            Method[] methods = cls.getDeclaredMethods();
            for (Method method : methods) {
                if (method.getName().equals("registerListener")) {
                    Class<?>[] parameterTypes = method.getParameterTypes();
                    if (parameterTypes.length == 1) {
                        Class<?>[] interfaces = parameterTypes[0].getInterfaces();
                        if (interfaces.length == 1 && interfaces[0].getName().equals(listenerInterfaceName)) {
                            return interfaces[0];
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Register a listener on a device using IBYDAutoListener interface.
     * Creates a dynamic proxy that forwards all calls to the callback.
     */
    public static boolean registerListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            String listenerInterfaceName = "android.hardware.IBYDAutoListener";
            Class<?> iListener = getListenerInterface(device.getClass(), listenerInterfaceName);
            if (iListener == null) {
                iListener = Class.forName(listenerInterfaceName);
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                iListener.getClassLoader(),
                new Class<?>[]{iListener},
                (p, method, args) -> {
                    String name = method.getName();
                    if ("hashCode".equals(name)) return System.identityHashCode(p);
                    if ("equals".equals(name)) return p == args[0];
                    if ("toString".equals(name)) return "BydListener";
                    try {
                        callback.onCallback(name, args);
                    } catch (Exception e) {
                        logger.debug("Listener callback error: " + name + " — " + e.getMessage());
                    }
                    return null;
                }
            );

            // Try registerListener(IBYDAutoListener)
            Method register = findRegisterMethod(device.getClass(), iListener);
            if (register != null) {
                register.invoke(device, proxy);
                return true;
            }
        } catch (Exception e) {
            logger.debug("registerListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a listener with specific feature IDs.
     */
    public static boolean registerListener(Object device, int[] featureIds, ListenerCallback callback) {
        if (device == null) return false;
        try {
            String listenerInterfaceName = "android.hardware.IBYDAutoListener";
            Class<?> iListener = getListenerInterface(device.getClass(), listenerInterfaceName);
            if (iListener == null) {
                iListener = Class.forName(listenerInterfaceName);
            }
            Object proxy = java.lang.reflect.Proxy.newProxyInstance(
                iListener.getClassLoader(),
                new Class<?>[]{iListener},
                (p, method, args) -> {
                    String name = method.getName();
                    if ("hashCode".equals(name)) return System.identityHashCode(p);
                    if ("equals".equals(name)) return p == args[0];
                    if ("toString".equals(name)) return "BydListener";
                    try {
                        callback.onCallback(name, args);
                    } catch (Exception e) {
                        logger.debug("Listener callback error: " + name + " — " + e.getMessage());
                    }
                    return null;
                }
            );

            // Try registerListener(IBYDAutoListener, int[])
            Method register = findRegisterMethodWithIds(device.getClass(), iListener);
            if (register != null) {
                register.invoke(device, proxy, featureIds);
                return true;
            }
            // Fallback to no-filter registration
            return registerListener(device, callback);
        } catch (Exception e) {
            logger.debug("registerListener(ids) failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed (device-specific) listener using a hand-rolled concrete
     * subclass of an abstract listener class. The BYD framework provides the
     * actual abstract class at runtime via the system classloader, and our
     * subclass extends it transparently.
     *
     * Why not Proxy.newProxyInstance: java.lang.reflect.Proxy only works with
     * interfaces, but AbsBYDAutoBodyworkListener / AbsBYDAutoDoorLockListener
     * are abstract classes — Proxy throws "is not an interface" at runtime.
     *
     * Two specialized helpers below cover the two known typed-listener cases.
     * Returns true on successful registration.
     */

    /**
     * Register a typed bodywork listener. Captures door state, window state,
     * and window-open percent callbacks.
     */
    public static boolean registerBodyworkListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener listener =
                new android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener() {
                    @Override
                    public void onDoorStateChanged(int area, int state) {
                        invokeCallback(callback, "onDoorStateChanged", new Object[]{area, state});
                    }
                    @Override
                    public void onWindowStateChanged(int area, int state) {
                        invokeCallback(callback, "onWindowStateChanged", new Object[]{area, state});
                    }
                    @Override
                    public void onWindowOpenPercentChanged(int area, int percent) {
                        invokeCallback(callback, "onWindowOpenPercentChanged", new Object[]{area, percent});
                    }
                    @Override
                    public void onPowerLevelChanged(int level) {
                        invokeCallback(callback, "onPowerLevelChanged", new Object[]{level});
                    }
                };
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.bodywork.AbsBYDAutoBodyworkListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            logger.debug("registerBodyworkListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerBodyworkListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerBodyworkListener failed: " + e.getMessage());
        }
        return false;
    }

    public static boolean registerTyreListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.tyre.AbsBYDAutoTyreListener listener =
                new android.hardware.bydauto.tyre.AbsBYDAutoTyreListener() {
                    @Override
                    public void onTyrePressureValueChanged(int wheel, int value) {
                        invokeCallback(callback, "onTyrePressureValueChanged", new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyrePressureStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyrePressureStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreBatteryValueChanged(int wheel, double value) {
                        invokeCallback(callback, "onTyreBatteryValueChanged", new Object[]{wheel, value});
                    }
                    @Override
                    public void onTyreBatteryStateChanged(int state) {
                        invokeCallback(callback, "onTyreBatteryStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onTyreTemperatureStateChanged(int state) {
                        invokeCallback(callback, "onTyreTemperatureStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onTyreAirLeakStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyreAirLeakStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreSignalStateChanged(int wheel, int state) {
                        invokeCallback(callback, "onTyreSignalStateChanged", new Object[]{wheel, state});
                    }
                    @Override
                    public void onTyreSystemStateChanged(int state) {
                        invokeCallback(callback, "onTyreSystemStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onIndirectTyreSystemStateChanged(int state) {
                        invokeCallback(callback, "onIndirectTyreSystemStateChanged", new Object[]{state});
                    }
                    // Generic feature-ID event channel. When the listener is
                    // registered via the 2-arg overload with an int[] filter,
                    // the HAL fires this for each subscribed feature ID
                    // instead of (or alongside) the typed callbacks above.
                    // The Tyre device delegates per-wheel temperature reads
                    // through Instrument-class feature IDs (LF/RF/LB/RB).
                    public void onDataEventChanged(int eventId, android.hardware.bydauto.BYDAutoEventValue value) {
                        invokeCallback(callback, "onDataEventChanged", new Object[]{eventId, value});
                    }
                };

            // Log available registerListener overloads for diagnostics
            Method[] allMethods = device.getClass().getMethods();
            StringBuilder overloads = new StringBuilder();
            for (Method m : allMethods) {
                if ("registerListener".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    overloads.append("  registerListener(");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) overloads.append(", ");
                        overloads.append(params[i].getSimpleName());
                    }
                    overloads.append(")\n");
                }
            }
            if (overloads.length() > 0) {
                logger.info("TyreDevice registerListener overloads:\n" + overloads);
            }

            // Strategy 1: 2-arg registration with the per-wheel temperature
            // feature IDs. BYDAutoFeatureIds.Instrument exposes the LF/RF/LB/
            // RB tyre temperature property IDs — those are the ones the Tyre
            // device's underlying property tree actually keys on, regardless
            // of which device class hosts the registerListener overload.
            // Filtering on this exact set is what wakes up the temperature
            // event channel on firmwares where the bare typed callbacks
            // (onTyreBatteryValueChanged) stay dormant.
            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.tyre.AbsBYDAutoTyreListener.class);
            boolean twoArgRegistered = false;
            if (registerWithIds != null) {
                try {
                    int[] tyreFeatureIds = com.overdrive.app.byd.BydFeatureIds.INSTRUMENT_TYRE_TEMP_IDS;
                    registerWithIds.invoke(device, listener, tyreFeatureIds);
                    logger.info("Tyre listener registered via 2-arg overload with LF/RF/LB/RB feature IDs");
                    twoArgRegistered = true;
                } catch (Exception e) {
                    logger.info("Tyre 2-arg registration with LF/RF/LB/RB IDs failed: " + e.getMessage());
                }
                // Fallback: empty int[]. Some HAL implementations interpret
                // this as "subscribe to all features"; others reject it.
                // We only attempt this if the typed-ID registration above
                // failed outright (e.g. method threw on invoke).
                if (!twoArgRegistered) {
                    try {
                        registerWithIds.invoke(device, listener, new int[0]);
                        logger.info("Tyre listener registered via 2-arg overload with empty int[] (subscribe-all fallback)");
                        twoArgRegistered = true;
                    } catch (Exception e) {
                        logger.info("Tyre 2-arg registration with empty int[] failed: " + e.getMessage());
                    }
                }
            }

            // Strategy 2: Also register via single-arg (ensures pressure/leak/signal
            // events still arrive even if the two-arg only subscribes to temp events).
            // If two-arg already succeeded, this is additive — BYD HAL allows multiple
            // registrations. If two-arg wasn't available, this is the only path.
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.tyre.AbsBYDAutoTyreListener.class);
            if (register != null) {
                register.invoke(device, listener);
                if (twoArgRegistered) {
                    logger.info("Tyre listener also registered via 1-arg overload (pressure/state events)");
                } else {
                    logger.info("Tyre listener registered via 1-arg overload only");
                }
                return true;
            }
            // If single-arg failed but two-arg succeeded, still report success
            if (twoArgRegistered) return true;
            logger.debug("registerTyreListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerTyreListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerTyreListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed engine listener so onEngineCoolantLevelChanged and
     * onOilLevelChanged actually dispatch (the bare 1-arg
     * registerListener(IBYDAutoListener) registration succeeds but the HAL
     * never invokes the device-specific callbacks on AbsBYDAutoEngineListener
     * subclasses on most firmware).
     *
     * Mirrors the tyre approach: try the 2-arg overload first (HAL only fires
     * onDataEventChanged with feature-IDs when registered with int[] filter),
     * then 1-arg as a baseline. Both succeed additively where supported.
     */
    public static boolean registerEngineListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.engine.AbsBYDAutoEngineListener listener =
                new android.hardware.bydauto.engine.AbsBYDAutoEngineListener() {
                    @Override
                    public void onEngineSpeedChanged(int value) {
                        invokeCallback(callback, "onEngineSpeedChanged", new Object[]{value});
                    }
                    @Override
                    public void onEngineCoolantLevelChanged(int state) {
                        invokeCallback(callback, "onEngineCoolantLevelChanged", new Object[]{state});
                    }
                    @Override
                    public void onOilLevelChanged(int value) {
                        invokeCallback(callback, "onOilLevelChanged", new Object[]{value});
                    }
                    // Generic feature-ID event channel, same pattern as the
                    // tyre listener. Engine extras (coolant temp, oil temp on
                    // PHEV firmware) tend to land here keyed on feature IDs
                    // we may not have in BYDAutoFeatureIds.Engine.
                    public void onDataEventChanged(int eventId, android.hardware.bydauto.BYDAutoEventValue value) {
                        invokeCallback(callback, "onDataEventChanged", new Object[]{eventId, value});
                    }
                };

            // Diagnostic: dump all registerListener overloads so we know what
            // shapes the HAL exposes on this firmware.
            Method[] allMethods = device.getClass().getMethods();
            StringBuilder overloads = new StringBuilder();
            for (Method m : allMethods) {
                if ("registerListener".equals(m.getName())) {
                    Class<?>[] params = m.getParameterTypes();
                    overloads.append("  registerListener(");
                    for (int i = 0; i < params.length; i++) {
                        if (i > 0) overloads.append(", ");
                        overloads.append(params[i].getSimpleName());
                    }
                    overloads.append(")\n");
                }
            }
            if (overloads.length() > 0) {
                logger.info("EngineDevice registerListener overloads:\n" + overloads);
            }

            // Strategy 1: 2-arg with empty int[]. We don't have engine fluid
            // feature IDs in BYDAutoFeatureIds.Engine, so empty-array
            // (subscribe-all) is the only option for the filtered overload.
            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.engine.AbsBYDAutoEngineListener.class);
            boolean twoArgRegistered = false;
            if (registerWithIds != null) {
                try {
                    registerWithIds.invoke(device, listener, new int[0]);
                    logger.info("Engine listener registered via 2-arg overload with empty int[]");
                    twoArgRegistered = true;
                } catch (Exception e) {
                    logger.info("Engine 2-arg registration failed: " + e.getMessage());
                }
            }

            // Strategy 2: 1-arg typed. Even when the HAL never fires the
            // typed callbacks, this one is harmless and gives us the
            // baseline that several other devices rely on.
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.engine.AbsBYDAutoEngineListener.class);
            if (register != null) {
                register.invoke(device, listener);
                logger.info(twoArgRegistered
                        ? "Engine listener also registered via 1-arg overload"
                        : "Engine listener registered via 1-arg overload only");
                return true;
            }
            if (twoArgRegistered) return true;
            logger.debug("registerEngineListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerEngineListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerEngineListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed door-lock listener. Captures the canonical
     * onDoorLockStatusChanged(area, state) event the BMS emits when the gun is
     * connected and the lock state transitions.
     */
    public static boolean registerDoorLockListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener listener =
                new android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener() {
                    @Override
                    public void onDoorLockStatusChanged(int area, int state) {
                        invokeCallback(callback, "onDoorLockStatusChanged", new Object[]{area, state});
                    }
                };
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.doorlock.AbsBYDAutoDoorLockListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            logger.debug("registerDoorLockListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerDoorLockListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerDoorLockListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed charging listener. The bare 1-arg
     * registerListener(IBYDAutoListener) registration succeeds on most
     * firmware but the HAL never invokes the device-specific callbacks
     * on AbsBYDAutoChargingListener subclasses through that path —
     * onBatteryManagementDeviceStateChanged in particular has been
     * observed to silently drop on PHEV builds, which is the root cause
     * of charging-detection lag during AC charging start.
     *
     * Registers both the 2-arg (with empty int[] for subscribe-all) and
     * 1-arg overloads where present. Both succeed additively where supported.
     */
    public static boolean registerChargingListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.charging.AbsBYDAutoChargingListener listener =
                new android.hardware.bydauto.charging.AbsBYDAutoChargingListener() {
                    @Override
                    public void onBatteryManagementDeviceStateChanged(int state) {
                        invokeCallback(callback, "onBatteryManagementDeviceStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onChargerStateChanged(int state) {
                        invokeCallback(callback, "onChargerStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onChargingGunStateChanged(int state) {
                        invokeCallback(callback, "onChargingGunStateChanged", new Object[]{state});
                    }
                    @Override
                    public void onChargingPowerChanged(double power) {
                        invokeCallback(callback, "onChargingPowerChanged", new Object[]{power});
                    }
                    @Override
                    public void onChargingCapacityChanged(double capacity) {
                        invokeCallback(callback, "onChargingCapacityChanged", new Object[]{capacity});
                    }
                };

            // Strategy 1: 2-arg with empty int[] (subscribe-all). Some firmware
            // only delivers events through the filtered overload.
            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.charging.AbsBYDAutoChargingListener.class);
            boolean twoArgRegistered = false;
            if (registerWithIds != null) {
                try {
                    registerWithIds.invoke(device, listener, new int[0]);
                    twoArgRegistered = true;
                } catch (Exception e) {
                    logger.debug("Charging 2-arg registration failed: " + e.getMessage());
                }
            }

            // Strategy 2: 1-arg typed.
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.charging.AbsBYDAutoChargingListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            if (twoArgRegistered) return true;
            logger.debug("registerChargingListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerChargingListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerChargingListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed instrument listener. The instrument device's real
     * signals — most importantly {@code onExternalChargingPowerChanged(double)}
     * (live AC/DC charging power in kW) — are CONCRETE methods on the
     * {@code AbsBYDAutoInstrumentListener} abstract class, NOT on the
     * {@code IBYDAutoListener} base interface (which is an empty marker). The
     * generic {@link #registerListener(Object, ListenerCallback)} path builds a
     * dynamic {@code Proxy} of {@code IBYDAutoListener}; a Proxy can only
     * implement interface methods, so it can NEVER receive
     * {@code onExternalChargingPowerChanged} — the HAL keeps a separate typed
     * {@code List<AbsBYDAutoInstrumentListener>} and dispatches that callback
     * only to it. Result: charging power never arrives via the listener and the
     * UI falls back to a nominal estimate. Mirrors {@link #registerChargingListener}.
     *
     * Registers both the 2-arg (empty int[] = subscribe-all) and 1-arg typed
     * overloads where present; both succeed additively where supported.
     */
    public static boolean registerInstrumentListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener listener =
                new android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener() {
                    @Override
                    public void onExternalChargingPowerChanged(double power) {
                        invokeCallback(callback, "onExternalChargingPowerChanged", new Object[]{power});
                    }
                    @Override
                    public void onSafetyBeltStatusChanged(int seat, int state) {
                        invokeCallback(callback, "onSafetyBeltStatusChanged", new Object[]{seat, state});
                    }
                };

            // Strategy 1: 2-arg with empty int[] (subscribe-all). Some firmware
            // only delivers events through the filtered overload.
            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener.class);
            boolean twoArgRegistered = false;
            if (registerWithIds != null) {
                try {
                    registerWithIds.invoke(device, listener, new int[0]);
                    twoArgRegistered = true;
                } catch (Exception e) {
                    logger.debug("Instrument 2-arg registration failed: " + e.getMessage());
                }
            }

            // Strategy 2: 1-arg typed.
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.instrument.AbsBYDAutoInstrumentListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            if (twoArgRegistered) return true;
            logger.debug("registerInstrumentListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerInstrumentListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerInstrumentListener failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Register a typed statistic listener. onElecPercentageChanged(double) — the
     * DECIMAL display SoC — is a concrete method on AbsBYDAutoStatisticListener, NOT
     * on the bare IBYDAutoListener marker interface, so the generic Proxy path can
     * never receive it (the HAL dispatches it only to typed subscribers) — exactly
     * like onExternalChargingPowerChanged on the instrument device. Via the generic
     * path SoC only ever came from the slow getElecPercentageValue() poll (integer on
     * this trim); the typed path delivers sub-integer SoC the moment it changes.
     * Forwards the same methods the generic hub (onGenericCallback) already handles so
     * there is zero behavioural regression, only added granularity. Typed first with a
     * generic fallback kept at the call site for firmware exposing only the bare
     * registerListener.
     */
    public static boolean registerStatisticListener(Object device, ListenerCallback callback) {
        if (device == null) return false;
        try {
            android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener listener =
                new android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener() {
                    @Override
                    public void onElecPercentageChanged(double percentage) {
                        invokeCallback(callback, "onElecPercentageChanged", new Object[]{percentage});
                    }
                    @Override
                    public void onFuelPercentageChanged(int percentage) {
                        invokeCallback(callback, "onFuelPercentageChanged", new Object[]{percentage});
                    }
                };

            // Strategy 1: 2-arg with empty int[] (subscribe-all) — some firmware
            // only delivers through the filtered overload.
            Method registerWithIds = findRegisterMethodWithIds(device.getClass(),
                android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener.class);
            boolean twoArgRegistered = false;
            if (registerWithIds != null) {
                try {
                    registerWithIds.invoke(device, listener, new int[0]);
                    twoArgRegistered = true;
                } catch (Exception e) {
                    logger.debug("Statistic 2-arg registration failed: " + e.getMessage());
                }
            }

            // Strategy 2: 1-arg typed.
            Method register = findRegisterMethod(device.getClass(),
                android.hardware.bydauto.statistic.AbsBYDAutoStatisticListener.class);
            if (register != null) {
                register.invoke(device, listener);
                return true;
            }
            if (twoArgRegistered) return true;
            logger.debug("registerStatisticListener: no registerListener method on "
                + device.getClass().getName());
        } catch (NoClassDefFoundError e) {
            logger.debug("registerStatisticListener: class not available on this firmware");
        } catch (Exception e) {
            logger.debug("registerStatisticListener failed: " + e.getMessage());
        }
        return false;
    }

    private static void invokeCallback(ListenerCallback callback, String method, Object[] args) {
        try {
            callback.onCallback(method, args);
        } catch (Exception e) {
            logger.debug("Typed listener callback error: " + method + " — " + e.getMessage());
        }
    }

    // ==================== EXTENDED GETTER METHODS ====================

    /**
     * Call get(int deviceType, int featureId) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callGetSingle(Object device, int featureId) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "get", getSingleMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callGetSingle permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callGetSingle failed for id=" + featureId + " — " + e.getMessage());
        }
        return -1;
    }

    /**
     * Call getDouble(int deviceType, int featureId) on a BYD device.
     * Returns Double.NaN on any failure.
     */
    public static double callGetDouble(Object device, int featureId) {
        if (device == null) return Double.NaN;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return Double.NaN;
            Method m = findMethodCached(device, "getDouble", getDoubleMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof Number) return ((Number) result).doubleValue();
            }
        } catch (Exception e) {
            logger.debug("callGetDouble failed for id=" + featureId + " — " + e.getMessage());
        }
        return Double.NaN;
    }

    /**
     * Call getIntArray(int deviceType, int[] featureIds) on a BYD device.
     * Returns null on any failure.
     */
    public static int[] callGetIntArray(Object device, int[] featureIds) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getIntArray", getIntArrayMethodCache,
                    int.class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds);
                if (result instanceof int[]) return (int[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetIntArray failed — " + e.getMessage());
        }
        return null;
    }

    /**
     * Call getDoubleArray(int deviceType, int[] featureIds) on a BYD device.
     * The underlying SDK returns float[], so this method returns float[].
     * Returns null on any failure.
     */
    public static float[] callGetDoubleArray(Object device, int[] featureIds) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getDoubleArray", getDoubleArrayMethodCache,
                    int.class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds);
                if (result instanceof float[]) return (float[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetDoubleArray failed — " + e.getMessage());
        }
        return null;
    }

    /**
     * Call getBuffer(int deviceType, int featureId) on a BYD device.
     * Returns null on any failure.
     */
    public static byte[] callGetBuffer(Object device, int featureId) {
        if (device == null) return null;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return null;
            Method m = findMethodCached(device, "getBuffer", getBufferMethodCache,
                    int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId);
                if (result instanceof byte[]) return (byte[]) result;
            }
        } catch (Exception e) {
            logger.debug("callGetBuffer failed for id=" + featureId + " — " + e.getMessage());
        }
        return null;
    }

    // ==================== SETTER METHODS ====================

    /**
     * Send a set command using the BYDAutoEventValue pattern.
     * Creates a BYDAutoEventValue, sets intValue, calls device.set(int[], BYDAutoEventValue).
     * Falls back to callSetSingle if BYDAutoEventValue is not available.
     */
    public static boolean sendSetCommand(Object device, int featureId, int value) {
        int code = sendSetCommandRaw(device, featureId, value);
        return code >= 0;
    }

    /**
     * Same as {@link #sendSetCommand} but returns the RAW SDK result code
     * instead of a boolean, so callers can distinguish e.g.
     * BYDAUTO_COMMAND_RESULT_FAILED (-2147482648) from other negatives.
     * Returns {@link Integer#MIN_VALUE} only when the call threw before
     * producing a code (so it's distinguishable from the real -2147482648).
     * A Boolean SDK result maps to 0 (true) / -1 (false).
     */
    public static int sendSetCommandRaw(Object device, int featureId, int value) {
        if (device == null) return Integer.MIN_VALUE;
        try {
            Class<?> eventValueClass = Class.forName("android.hardware.bydauto.BYDAutoEventValue");
            Object eventValue = eventValueClass.getConstructor(new Class[0]).newInstance(new Object[0]);
            eventValueClass.getField("intValue").setInt(eventValue, value);
            Method setMethod = device.getClass().getMethod("set", int[].class, eventValueClass);
            Object result = setMethod.invoke(device, new int[]{featureId}, eventValue);
            if (result instanceof Integer) {
                return ((Integer) result).intValue();
            } else if (result instanceof Boolean) {
                return ((Boolean) result).booleanValue() ? 0 : -1;
            }
            return 0; // non-null result, assume success
        } catch (ClassNotFoundException e) {
            // BYDAutoEventValue not available, fall back to base class set()
            logger.debug("BYDAutoEventValue not found, falling back to callSetSingle");
            return callSetSingle(device, featureId, value);
        } catch (Exception e) {
            logger.debug("sendSetCommandRaw failed for featureId=0x" + Integer.toHexString(featureId) + ": " + e.getMessage());
            return Integer.MIN_VALUE;
        }
    }

    /**
     * Call set(int deviceType, int featureId, int value) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callSetSingle(Object device, int featureId, int value) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setSingleMethodCache,
                    int.class, int.class, int.class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId, value);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetSingle permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetSingle failed for id=" + featureId + ", value=" + value + " — " + e.getMessage());
        }
        return -1;
    }

    /**
     * Call set(int deviceType, int[] featureIds, int[] values) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callSetBatch(Object device, int[] featureIds, int[] values) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setBatchMethodCache,
                    int.class, int[].class, int[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureIds, values);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetBatch permission denied — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetBatch failed — " + e.getMessage());
        }
        return -1;
    }

    /**
     * Call set(int deviceType, int featureId, byte[] buffer) on a BYD device.
     * Returns the SDK result code, or -1 on any failure.
     */
    public static int callSetBuffer(Object device, int featureId, byte[] buffer) {
        if (device == null) return -1;
        try {
            int deviceType = resolveDeviceType(device);
            if (deviceType == Integer.MIN_VALUE) return -1;
            Method m = findMethodCached(device, "set", setBufferMethodCache,
                    int.class, int.class, byte[].class);
            if (m != null) {
                Object result = m.invoke(device, deviceType, featureId, buffer);
                if (result instanceof Number) return ((Number) result).intValue();
            }
        } catch (SecurityException e) {
            logger.debug("callSetBuffer permission denied for id=" + featureId + " — " + e.getMessage());
        } catch (Exception e) {
            logger.debug("callSetBuffer failed for id=" + featureId + " — " + e.getMessage());
        }
        return -1;
    }

    // ==================== INTERNAL HELPERS ====================

    private static final java.util.Map<Class<?>, Method> getMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getSingleMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getDoubleMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getIntArrayMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getDoubleArrayMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> getBufferMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> setSingleMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> setBatchMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Class<?>, Method> setBufferMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    /**
     * className → HAL device type. {@code ConcurrentHashMap}, not {@code HashMap}: this is written
     * from the collector poll thread, the HTTP {@code collectAllFull} thread and HAL/init worker
     * threads with no external lock, and a plain HashMap under concurrent {@code put} can corrupt
     * its table (classically, an endless loop inside {@code get}).
     */
    private static final java.util.Map<Class<?>, Integer> deviceTypeCache =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Classes whose type-probe recently FAILED, and the wall-clock ms after which to retry.
     * Separate from {@link #deviceTypeCache} so a failure can never be mistaken for a resolved
     * type, and so {@code ConcurrentHashMap}'s no-null-values rule is respected.
     */
    private static final java.util.Map<Class<?>, Long> deviceTypeMissUntil =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * How long a failed device-type probe is remembered before being retried. Sized near the
     * 5 s ACC-on poll cadence rather than a minute: a binder that recovers in 2 s should not blank
     * every manager-level read for 12 further cycles.
     *
     * <p>Measured with {@link android.os.SystemClock#elapsedRealtime()}, NOT wall clock. These head
     * units boot with an unset RTC and then jump the clock when NTP/GPS lands — a BACKWARD jump
     * would hold a wall-clock deadline in the future for hours, re-creating the process-lifetime
     * outage this TTL exists to prevent, and doing it precisely while handles are being acquired.
     */
    private static final long TYPE_MISS_TTL_MS = 10_000L;

    // ----- per-name reflection caches for callGetter / callMethod -----
    //
    // The existing per-Class caches above are each dedicated to a single
    // (methodName, paramTypes) tuple. callGetter / callMethod take the
    // methodName as a parameter, so they need a (Class, methodName) key.
    // One cache per paramType signature lets us drop methodName-on-key only
    // (paramTypes are implicit per cache).
    //
    // ConcurrentHashMap because these are hit from many threads at high
    // frequency (BydDataCollector polls every 5s while ACC ON, plus
    // setter calls from web/Telegram threads). ConcurrentHashMap forbids
    // null values, so we use NEGATIVE_CACHE_SENTINEL for "method not found".
    private static final Method NEGATIVE_CACHE_SENTINEL;
    static {
        Method sentinel = null;
        try {
            // Any well-known no-arg Method works as an identity sentinel.
            sentinel = Object.class.getDeclaredMethod("hashCode");
        } catch (NoSuchMethodException ignored) {
            // Object.hashCode is guaranteed to exist; this branch is unreachable.
        }
        NEGATIVE_CACHE_SENTINEL = sentinel;
    }

    private static final Class<?>[] NO_PARAMS = new Class<?>[0];
    private static final Class<?>[] INT_PARAMS = new Class<?>[]{int.class};
    private static final Class<?>[] INT_INT_PARAMS = new Class<?>[]{int.class, int.class};
    private static final Class<?>[] INT3_PARAMS = new Class<?>[]{int.class, int.class, int.class};
    private static final Class<?>[] INT4_PARAMS = new Class<?>[]{int.class, int.class, int.class, int.class};

    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicNoArgMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicIntMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicIntIntMethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicInt3MethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            publicInt4MethodCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>>
            declaredNoArgMethodCache = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Cached Class.getMethod(name, paramTypes) lookup.
     * Returns null when the method doesn't exist (cached negatively via sentinel).
     */
    private static Method lookupPublicMethodCached(Class<?> cls, String methodName,
            java.util.concurrent.ConcurrentMap<Class<?>, java.util.concurrent.ConcurrentMap<String, Method>> cache,
            Class<?>[] paramTypes) {
        java.util.concurrent.ConcurrentMap<String, Method> perClass = cache.get(cls);
        if (perClass == null) {
            perClass = cache.computeIfAbsent(cls, k -> new java.util.concurrent.ConcurrentHashMap<>());
        }
        Method cached = perClass.get(methodName);
        if (cached != null) {
            return cached == NEGATIVE_CACHE_SENTINEL ? null : cached;
        }
        try {
            Method m = cls.getMethod(methodName, paramTypes);
            perClass.put(methodName, m);
            return m;
        } catch (NoSuchMethodException e) {
            perClass.put(methodName, NEGATIVE_CACHE_SENTINEL);
            return null;
        } catch (Exception e) {
            // SecurityException etc. — don't poison the cache; just return null.
            return null;
        }
    }

    /**
     * Cached Class.getDeclaredMethod(name) walk up the class hierarchy.
     * Returns null when no class in the hierarchy declares the method
     * (cached negatively via sentinel).
     */
    private static Method lookupDeclaredNoArgCached(Class<?> cls, String methodName) {
        java.util.concurrent.ConcurrentMap<String, Method> perClass = declaredNoArgMethodCache.get(cls);
        if (perClass == null) {
            perClass = declaredNoArgMethodCache.computeIfAbsent(cls, k -> new java.util.concurrent.ConcurrentHashMap<>());
        }
        Method cached = perClass.get(methodName);
        if (cached != null) {
            return cached == NEGATIVE_CACHE_SENTINEL ? null : cached;
        }
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod(methodName);
                m.setAccessible(true);
                perClass.put(methodName, m);
                return m;
            } catch (NoSuchMethodException ignored) {
                walk = walk.getSuperclass();
            } catch (Exception e) {
                // SecurityException — don't poison the cache.
                return null;
            }
        }
        perClass.put(methodName, NEGATIVE_CACHE_SENTINEL);
        return null;
    }

    private static Method findGetMethod(Object device) {
        Class<?> cls = device.getClass();
        Method ghit = getMethodCache.get(cls);
        if (ghit != null) return ghit == NEGATIVE_CACHE_SENTINEL ? null : ghit;

        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("get", int[].class, Class.class);
                m.setAccessible(true);
                getMethodCache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            try {
                Method m = walk.getDeclaredMethod("get", int.class, int.class);
                m.setAccessible(true);
                getMethodCache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        // Sentinel, not null: these caches are ConcurrentHashMap now (they are hit from the poll
        // thread, the HTTP collectAllFull thread and web/Telegram setter threads, and a plain
        // HashMap can corrupt its table under concurrent put) — and CHM forbids null VALUES.
        getMethodCache.put(cls, NEGATIVE_CACHE_SENTINEL);
        return null;
    }

    /**
     * Public accessor for a device's numeric HAL type, or {@code Integer.MIN_VALUE} when it cannot
     * be resolved. Exposed for {@link BydManagerChannel}, whose manager-level reads are keyed by
     * {@code (deviceType, featureId)} — it must not duplicate this lookup, because the result is
     * cached per class here and a second cache could disagree.
     */
    public static int deviceTypeOf(Object device) {
        if (device == null) return Integer.MIN_VALUE;
        return resolveDeviceType(device);
    }

    private static int resolveDeviceType(Object device) {
        Class<?> cls = device.getClass();
        Integer hit = deviceTypeCache.get(cls);
        if (hit != null) return hit;   // get()-once, not containsKey+get: no unboxing NPE window
        // NEGATIVE CACHE WITH A TTL. Not caching failures at all (the previous revision) meant a
        // device that can never report its type re-ran two failing reflective probes on EVERY call —
        // up to ~34k probe pairs/day at the 5 s ACC-on cadence. Caching them forever (the revision
        // before that) turned one transient failure into a process-lifetime outage that no re-init
        // could clear. A short TTL has both properties: steady-state cost is one probe pair per
        // class per window, and a genuine recovery is picked up within one window.
        Long until = deviceTypeMissUntil.get(cls);
        if (until != null) {
            if (android.os.SystemClock.elapsedRealtime() < until) return Integer.MIN_VALUE;
            deviceTypeMissUntil.remove(cls);   // window elapsed → probe again
        }

        // Try getDevicetype() first (AbsBYDAutoDevice). The METHOD LOOKUP is cached permanently
        // (a Method is Class-bound, so its absence never changes) while only the VALUE is under the
        // TTL — otherwise every TTL window re-threw NoSuchMethodException for both accessors, with
        // full stack-trace fill-in, on the 5 s poll thread and once per unresolvable class.
        try {
            Method m = lookupPublicMethodCached(cls, "getDevicetype",
                    publicNoArgMethodCache, NO_PARAMS);
            if (m == null) throw new NoSuchMethodException("getDevicetype");
            Object result = m.invoke(device);
            if (result instanceof Number) {
                int type = ((Number) result).intValue();
                deviceTypeCache.put(cls, type);
                return type;
            }
        } catch (Exception ignored) {}

        // Fallback to getType() — same cached-lookup treatment.
        try {
            Method m = lookupPublicMethodCached(cls, "getType",
                    publicNoArgMethodCache, NO_PARAMS);
            if (m == null) throw new NoSuchMethodException("getType");
            Object result = m.invoke(device);
            if (result instanceof Number) {
                int type = ((Number) result).intValue();
                deviceTypeCache.put(cls, type);
                return type;
            }
        } catch (Exception ignored) {}

        // Failure is remembered only for TYPE_MISS_TTL_MS (see the negative-cache note above), so a
        // transient cause — a dead binder mid-restart, or a handle obtained under the broken
        // synthetic Context before ACC-ON replaces it — cannot become a permanent outage, while a
        // genuinely type-less device is not re-probed on every poll. Logged at DEBUG because with a
        // TTL this repeats once per window; the INFO-level version was itself a slow log leak.
        deviceTypeMissUntil.put(cls, android.os.SystemClock.elapsedRealtime() + TYPE_MISS_TTL_MS);
        logger.debug("Could not resolve deviceType for " + cls.getSimpleName()
                + " — retrying in " + (TYPE_MISS_TTL_MS / 1000) + "s");
        return Integer.MIN_VALUE;
    }

    /**
     * Drop cached device types so the next call re-probes. Intended for
     * {@link BydManagerChannel#invalidate()} — a dead binder can make {@code getDevicetype()} throw,
     * and although failures are no longer cached, a type resolved from a stale handle should not
     * outlive it.
     */
    static void invalidateDeviceTypeCache() {
        deviceTypeCache.clear();
        deviceTypeMissUntil.clear();   // a new handle deserves an immediate retry, not a stale TTL
    }

    /**
     * Find a method by name and parameter types on a device, walking up the class hierarchy.
     * Caches the result per device class in the provided cache map.
     */
    private static Method findMethodCached(Object device, String methodName,
            java.util.Map<Class<?>, Method> cache, Class<?>... paramTypes) {
        Class<?> cls = device.getClass();
        Method hit = cache.get(cls);
        if (hit != null) return hit == NEGATIVE_CACHE_SENTINEL ? null : hit;

        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod(methodName, paramTypes);
                m.setAccessible(true);
                cache.put(cls, m);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        cache.put(cls, NEGATIVE_CACHE_SENTINEL);   // never null — see findGetMethod
        return null;
    }

    private static Method findRegisterMethod(Class<?> cls, Class<?> listenerInterface) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("registerListener", listenerInterface);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        return null;
    }

    private static Method findRegisterMethodWithIds(Class<?> cls, Class<?> listenerInterface) {
        Class<?> walk = cls;
        while (walk != null && walk != Object.class) {
            try {
                Method m = walk.getDeclaredMethod("registerListener", listenerInterface, int[].class);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
            walk = walk.getSuperclass();
        }
        return null;
    }

    /** Callback interface for listener proxies */
    public interface ListenerCallback {
        void onCallback(String methodName, Object[] args);
    }

    private BydDeviceHelper() {}
}
