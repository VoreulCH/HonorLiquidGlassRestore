package io.github.voreulch.liquidglass;

import java.lang.ref.WeakReference;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;


import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Liquid Glass Restorer for MagicOS (LSPosed module).
 *
 * Root cause: after the OTA, TransparentModeManager caches mBlurType = FROSTED
 * at SystemUI init, the machine type fell to COMPAT, and the liquid material
 * bit was dropped from the style config — the control center renders glassType=1
 * (flat frosted) instead of 2 (liquid refraction + highlight).
 *
 * Strategy: AOT inlines small getters and bypasses method hooks, so we only
 * hook big methods that cannot be inlined (getTransparentModeState /
 * initBulrParams / constructors) and rewrite the FIELDS the renderers read.
 *
 * All levers are live system properties (2s TTL cache) — setprop takes effect
 * without restart and OVERRIDES the built-in default; clear a prop (setprop X
 * "") to return to the baked value. The baked defaults ARE the final
 * calibrated recipe (2026-10-09): radius .7 / mask .7 / sat 1.2 / nmask .4 /
 * folder on / tblur .6 / thick 1.4 / edge 1.4 / disp .25, keyguard chain on
 * with kbblur 4 / kveil .7 / kbbright .22 / kedge 1.4 — a fresh install
 * reproduces the approved look with zero setprop. kdiag stays default-off
 * (pure logging). Full history & pipeline analysis: lg/HANDOFF.md.
 *
 * Panel / global look (persist.sys.lgr.*):
 *   radius  panel-background blur radius scale
 *   mask    mask alpha scale (panel veil + card masks)
 *   sat     panel bitmap saturation
 *   nmask   notification-card readability tint (absolute alpha)
 *   refract / thick / disp / rim  glass optics (lens bending, band width,
 *                                  chromatic fringe, rim-light alpha)
 *   edge    edge-light band width (all surfaces)
 *   tblur   per-surface re-blur (tiles / notification cards)
 *   folder  launcher-folder-style refraction constants
 *   degree  uikit degree algorithm transparency
 *
 * Keyguard (lock screen) — default OFF, enable with setprop 1:
 *   kbg      retain/serve the keyguard blur bitmap (getCurrentBlurBitmapForBg)
 *   kbblur   >0: serve a light bitmap synthesized from the SHARP wallpaper
 *            with this quarter-res blur radius (the approved look; 0 = stock
 *            bokeh)
 *   kveil    keyguard-only multiplier on the baked #1A1A1A veil
 *   kbbright keyguard card face brightness (stock shade value +0.08)
 *   kedge    keyguard-only edge-light width multiplier
 *   keng     push captured bitmaps through the engine onWallpaperChanged
 *   kicon    toolbox buttons: redirect to the ViewEx blurIcon route
 *   kdiag    pure-observation logging of the keyguard glass chain
 *
 * Safety rules (learned the hard way):
 *   - No Class.forName(initialize=true) in handleLoadPackage — it ran the
 *     <clinit> of a system class during SystemUI incubation and boot-looped
 *     the phone (v2.3 incident).
 *   - No hardcoded resource IDs — an unverified ID flipped an unrelated
 *     boolean and randomly broke SystemUI.
 *   - Unverified hooks default OFF; enable only after reaching the desktop.
 *   - Every hook body is individually try/caught so one failure never
 *     crashes the host process.
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String TAG = "LGRestorer";

    // SystemUI apk classes (present only in com.android.systemui process)
    private static final String TMM_CLASS =
            "com.hihonor.systemui.magic.transparentmode.TransparentModeManager";
    private static final String BPC_CLASS =
            "com.hihonor.systemui.magic.blur.BlurParametersConfig";
    private static final String SHOTBLUR_CLASS =
            "com.android.magic.blur.ShotBlurView";
    // uikit transparency framework (SystemUI + launcher)
    private static final String CFG_CLASS =
            "com.hihonor.uikit.transparency.configuration.TransparencyFeatureConfig";
    private static final String MACHINE_CLASS =
            "com.hihonor.uikit.transparency.configuration.MachineConfig";
    private static final String MACHINE_TYPE_CLASS =
            "com.hihonor.uikit.transparency.configuration.MachineType";
    private static final String STYLE_TYPE_CLASS =
            "com.hihonor.uikit.transparency.style.StyleType";
    private static final String UIMODE_WRAPPER_CLASS =
            "com.hihonor.uikit.hnmagicsystem.app.UiModeManagerEx";
    private static final String DEGREE_ALGO_CLASS =
            "com.hihonor.uikit.transparency.data.algorithm.degree.AbsHighGradeDegreeAlgorithm";
    private static final String COLOR_HELPER_CLASS =
            "com.hihonor.uikit.transparency.utils.ColorHelper";
    // framework classes (boot classpath)
    private static final String UIMODE_FW_CLASS =
            "com.hihonor.android.app.UiModeManagerEx";
    private static final String CONFIG_EX_CLASS =
            "com.hihonor.android.content.res.ConfigurationEx";

    private static final int BLUR_TYPE_MASK = 0xF0000;
    private static final int BLUR_TYPE_FROSTED = 0x10000;
    private static final int BLUR_TYPE_LIQUID = 0x20000;

    private static final String DEGREE_PROP = "persist.sys.lgr.degree";
    private static final String RADIUS_PROP = "persist.sys.lgr.radius";
    private static final String MASK_PROP = "persist.sys.lgr.mask";
    private static final String SAT_PROP = "persist.sys.lgr.sat";
    private static final String NTF_MASK_PROP = "persist.sys.lgr.nmask";
    private static final String REFRACT_PROP = "persist.sys.lgr.refract";
    private static final String EDGE_PROP = "persist.sys.lgr.edge";
    private static final String THICK_PROP = "persist.sys.lgr.thick";
    private static final String DISP_PROP = "persist.sys.lgr.disp";
    private static final String RIM_PROP = "persist.sys.lgr.rim";
    private static final String FOLDER_PROP = "persist.sys.lgr.folder";
    private static final String TBLUR_PROP = "persist.sys.lgr.tblur";
    private static final String KDIAG_PROP = "persist.sys.lgr.kdiag";
    private static final String KBG_PROP = "persist.sys.lgr.kbg";
    private static final String KENG_PROP = "persist.sys.lgr.keng";
    private static final String KICON_PROP = "persist.sys.lgr.kicon";
    private static final String KBBLUR_PROP = "persist.sys.lgr.kbblur";
    private static final String KVEIL_PROP = "persist.sys.lgr.kveil";
    private static final String KBBRIGHT_PROP = "persist.sys.lgr.kbbright";
    private static final String KEDGE_PROP = "persist.sys.lgr.kedge";
    private static final String NBVM_CLASS =
            "com.android.magic.blur.NotificationBackgroundViewModel";
    // Baked-in defaults = the final calibrated recipe (2026-10-09, user
    // approved). System properties OVERRIDE these per device; clear a prop
    // (setprop X "") to fall back to the baked value.
    private static final int DEFAULT_DEGREE = 85;
    private static final float DEFAULT_RADIUS = 0.7f;
    private static final float DEFAULT_MASK = 0.7f;
    private static final float DEFAULT_SAT = 1.2f;
    private static final float DEFAULT_NTF_MASK = 0.4f;
    private static final float DEFAULT_REFRACT = 1.0f;
    private static final float DEFAULT_EDGE = 1.4f;
    private static final float DEFAULT_RIM = 1.0f;
    private static final float DEFAULT_TBLUR = 0.6f;
    private static final float DEFAULT_THICK = 1.4f;
    private static final float DEFAULT_DISP = 0.25f;
    private static final float DEFAULT_FOLDER = 1.0f;
    private static final float DEFAULT_KBG = 1.0f;
    private static final float DEFAULT_KENG = 1.0f;
    private static final float DEFAULT_KICON = 1.0f;
    private static final float DEFAULT_KBBLUR = 4.0f;
    private static final float DEFAULT_KVEIL = 0.7f;
    private static final float DEFAULT_KBBRIGHT = 0.22f;
    private static final float DEFAULT_KEDGE = 1.4f;

    private static boolean sFlipLogged = false;
    private static boolean sTextureLogged = false;
    private static boolean sDegreeLogged = false;
    private static boolean sConfigLogged = false;
    private static int sConfigDiagCount = 0;
    private static int sTextureDiagCount = 0;
    private static int sStateCount = 0;
    private static int sRadiusCount = 0;
    private static int sMaskCount = 0;
    private static int sUikitCount = 0;
    private static int sPanelCount = 0;

    // live-tunable prop cache (2s TTL so setprop applies without restart)
    private static long sPropCacheAt = Long.MIN_VALUE;
    private static float sRadiusScale = DEFAULT_RADIUS;
    private static float sMaskScale = DEFAULT_MASK;
    private static float sSatScale = DEFAULT_SAT;
    private static float sNtfMask = DEFAULT_NTF_MASK;
    private static int sNtfCount = 0;
    private static float sRefractScale = DEFAULT_REFRACT;
    private static float sEdgeScale = DEFAULT_EDGE;
    private static int sEdgeCount = 0;
    private static int sRefractLogged = 0;
    private static float sThickScale = DEFAULT_THICK;
    private static float sDispScale = DEFAULT_DISP;
    private static float sRimScale = DEFAULT_RIM;
    private static int sRimCount = 0;
    private static boolean sFolderMode = false;
    private static int sFolderLogged = 0;
    private static float sTileBlurScale = DEFAULT_TBLUR;
    private static boolean sKeyguardDiag = false;
    private static boolean sKeyguardBg = false;
    private static boolean sKgEngineNotify = false;
    private static boolean sKgIconFallback = false;
    private static float sKgBlurRadius = 0.0f;
    private static float sKgVeilScale = 1.0f;
    private static float sKgBrightness = 0.12f;
    private static float sKgEdgeScale = 1.0f;
    private static int sKgShadeLogged = 0;
    // keyguard-card edge-light gate: initBulrParams(z=true, case=2) registers
    // the config here; turnOnViewBlur(view, config) then raises sKgCardNow for
    // the duration of the call so the EdgeLightParamEx constructor (built
    // synchronously inside turnOnViewBlur) can pick the keyguard-only scale.
    private static boolean sKgCardNow = false;
    private static final Set<Object> sKgConfigs =
            Collections.newSetFromMap(new WeakHashMap<Object, Boolean>());
    private static Bitmap sSynthBmp;
    private static WeakReference<Drawable> sSynthSrcRef;
    private static WeakReference<Bitmap> sStockBmpRef;
    private static int sSynthW;
    private static int sSynthH;
    private static float sSynthRadius = -1.0f;
    private static int sSynthVeilA = -1;
    private static int sSynthSatPct = -1;
    private static int sSynthGen;
    private static int sKgBgAppliedGen = -1;
    private static int sKgBgLogged = 0;
    private static int sKgBgServed = 0;
    private static int sDiagPwc = 0;
    private static final java.util.HashMap<String, Integer> sMissCounts =
            new java.util.HashMap<String, Integer>();
    private static int sKgIconLogged = 0;
    private static volatile Handler sKgHandler;
    private static final java.util.ArrayList<IconTarget> sIconTargets =
            new java.util.ArrayList<IconTarget>();
    private static int sTileBlurLogged = 0;
    private static int sDiagIcon = 0;
    private static int sDiagIconBg = 0;
    private static int sDiagBmp = 0;
    private static int sDiagReinit = 0;
    private static int sDiagPstate = 0;
    private static int sDiagStp = 0;
    private static int sDiagGlass = 0;
    private static int sDiagMat = 0;
    private static int sDiagTbv = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        log("v2.5.18 loaded in process: " + lpparam.packageName);

        hookTransparentMode(cl);
        hookBlurParams(cl);
        hookPanelVeil(cl);
        hookEdgeRim(cl);
        hookDegreeAlgorithm(cl);
        hookConfigEx(cl);
        hookFwDataSource(cl);
        hookMaterialBits(cl);
        hookMachineConfig(cl);
        hookStyleType(cl);
        if ("com.android.systemui".equals(lpparam.packageName)) {
            hookKeyguardBgBitmap(cl);
            hookKeyguardIconFallback(cl);
            hookKeyguardDiagnostics(cl);
            hookNtfCardGate(cl);
        }
    }

    /**
     * v2.5.16: raise sKgCardNow around turnOnViewBlur(view, config) when the
     * config was built by the keyguard branch (registered in sKgConfigs by
     * the initBulrParams hook). The EdgeLightParamEx constructor hook reads
     * the flag to apply the keyguard-only kedge width. before-hook re-sets
     * the flag every call, so an exception in one card cannot leave the
     * flag stuck for unrelated surfaces.
     */
    private static void hookNtfCardGate(ClassLoader cl) {
        try {
            Class<?> nbvm = XposedHelpers.findClass(NBVM_CLASS, cl);
            XposedBridge.hookAllMethods(nbvm, "turnOnViewBlur", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    sKgCardNow = param.args.length >= 2
                            && sKgConfigs.contains(param.args[1]);
                }

                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    sKgCardNow = false;
                }
            });
            log("ok: NotificationBackgroundViewModel kg-edge gate installed");
        } catch (Throwable t) {
            log("skip: NotificationBackgroundViewModel missing ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static void refreshProps() {
        long now = SystemClock.elapsedRealtime();
        if (now - sPropCacheAt >= 0 && now - sPropCacheAt < 2000L) {
            return;
        }
        sPropCacheAt = now;
        sRadiusScale = readFloatProp(RADIUS_PROP, DEFAULT_RADIUS, 0.3f, 2.0f);
        sMaskScale = readFloatProp(MASK_PROP, DEFAULT_MASK, 0.0f, 1.0f);
        sSatScale = readFloatProp(SAT_PROP, DEFAULT_SAT, 0.8f, 2.0f);
        sNtfMask = readFloatProp(NTF_MASK_PROP, DEFAULT_NTF_MASK, 0.0f, 1.0f);
        sRefractScale = readFloatProp(REFRACT_PROP, DEFAULT_REFRACT, 0.2f, 3.0f);
        sEdgeScale = readFloatProp(EDGE_PROP, DEFAULT_EDGE, 0.5f, 3.0f);
        sThickScale = readFloatProp(THICK_PROP, DEFAULT_THICK, 0.2f, 3.0f);
        sDispScale = readFloatProp(DISP_PROP, DEFAULT_DISP, 0.0f, 3.0f);
        sRimScale = readFloatProp(RIM_PROP, DEFAULT_RIM, 0.15f, 1.0f);
        sFolderMode = readFloatProp(FOLDER_PROP, DEFAULT_FOLDER, 0.0f, 1.0f) > 0.5f;
        sTileBlurScale = readFloatProp(TBLUR_PROP, DEFAULT_TBLUR, 0.1f, 2.0f);
        sKeyguardDiag = readFloatProp(KDIAG_PROP, 0.0f, 0.0f, 1.0f) > 0.5f;
        sKeyguardBg = readFloatProp(KBG_PROP, DEFAULT_KBG, 0.0f, 1.0f) > 0.5f;
        sKgEngineNotify = readFloatProp(KENG_PROP, DEFAULT_KENG, 0.0f, 1.0f) > 0.5f;
        sKgIconFallback = readFloatProp(KICON_PROP, DEFAULT_KICON, 0.0f, 1.0f) > 0.5f;
        sKgBlurRadius = readFloatProp(KBBLUR_PROP, DEFAULT_KBBLUR, 0.0f, 40.0f);
        sKgVeilScale = readFloatProp(KVEIL_PROP, DEFAULT_KVEIL, 0.0f, 2.0f);
        sKgBrightness = readFloatProp(KBBRIGHT_PROP, DEFAULT_KBBRIGHT, 0.0f, 0.5f);
        sKgEdgeScale = readFloatProp(KEDGE_PROP, DEFAULT_KEDGE, 0.5f, 3.0f);
    }

    /**
     * Core fix. getTransparentModeState() is a big method (string building,
     * multiple calls) so AOT never inlines it — every caller goes through
     * its entry point and our hook fires. After it caches mBlurType we
     * overwrite the field to LIQUID; all later isLiquidBlurType() checks
     * (inlined or not) read the field and see liquid.
     */
    private static void hookTransparentMode(ClassLoader cl) {
        try {
            Class<?> tmm = XposedHelpers.findClass(TMM_CLASS, cl);

            XposedBridge.hookAllMethods(tmm, "getTransparentModeState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object reason = param.args.length > 1 ? param.args[1] : "?";
                    try {
                        int oldType = XposedHelpers.getIntField(param.thisObject, "mBlurType");
                        XposedHelpers.setIntField(param.thisObject, "mBlurType", BLUR_TYPE_LIQUID);
                        if (sStateCount < 6) {
                            sStateCount++;
                            log("state: reason=" + reason + " on=" + param.getResult()
                                    + " mBlurType=" + oldType + " -> " + BLUR_TYPE_LIQUID);
                        }
                    } catch (Throwable t) {
                        log("state field fix failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(tmm, "isLiquidBlurType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        boolean on = XposedHelpers.getBooleanField(param.thisObject, "mIsTransparentModeOn");
                        if (on && !((Boolean) param.getResult())) {
                            param.setResult(true);
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            XposedBridge.hookAllMethods(tmm, "isFrostedBlurType",
                    XC_MethodReplacement.returnConstant(false));

            XposedBridge.hookAllMethods(tmm, "getTransparentBlurRadius", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (!(r instanceof Float)) {
                        return;
                    }
                    refreshProps();
                    if (sRadiusScale >= 0.999f) {
                        return;
                    }
                    float orig = (Float) r;
                    float scaled = Math.max(1.0f, orig * sRadiusScale);
                    if (sRadiusCount < 6) {
                        sRadiusCount++;
                        log("radius: case=" + (param.args.length > 0 ? param.args[0] : "?")
                                + " " + orig + " -> " + scaled);
                    }
                    if (scaled != orig) {
                        param.setResult(scaled);
                    }
                }
            });

            log("ok: TransparentModeManager LIQUID state fix installed");
        } catch (Throwable t) {
            log("skip: TransparentModeManager missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Control-center widgets (liquid cards / round buttons / sliders) build
     * their glass params here. withTransparency() is a big method, so the
     * hook always fires; we then rewrite the BlurParam fields the renderers
     * read. currentTransparency is reset so the setters re-apply absolute
     * values on the next call (prevents scale compounding).
     */
    private static void hookDegreeAlgorithm(ClassLoader cl) {
        try {
            Class<?> algo = XposedHelpers.findClass(DEGREE_ALGO_CLASS, cl);
            final Class<?> colorHelper = XposedHelpers.findClass(COLOR_HELPER_CLASS, cl);

            XposedBridge.hookAllMethods(algo, "withTransparency", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    refreshProps();
                    if (sRadiusScale >= 0.999f && sMaskScale >= 0.999f) {
                        return;
                    }
                    try {
                        XposedHelpers.setIntField(param.thisObject, "currentTransparency", -1);
                        Object repo = XposedHelpers.getObjectField(param.thisObject, "mHighGradeParamsRepo");
                        if (repo == null) {
                            return;
                        }
                        scaleBlurParam(repo, "lightBlurParam", colorHelper);
                        scaleBlurParam(repo, "darkBlurParam", colorHelper);
                        if (sUikitCount < 6) {
                            sUikitCount++;
                            log("uikit: repo=" + repo.getClass().getSimpleName()
                                    + " radius x" + sRadiusScale + " mask x" + sMaskScale);
                        }
                    } catch (Throwable t) {
                        log("uikit scale failed: " + t);
                    }
                }
            });

            log("ok: uikit degree algorithm scaling installed");
        } catch (Throwable t) {
            log("skip: uikit degree algorithm missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void scaleBlurParam(Object repo, String field, Class<?> colorHelper) {
        try {
            Object p = XposedHelpers.getObjectField(repo, field);
            if (p == null) {
                return;
            }
            if (sRadiusScale < 0.999f) {
                float r = XposedHelpers.getFloatField(p, "radius");
                XposedHelpers.setFloatField(p, "radius", Math.max(0.5f, r * sRadiusScale));
            }
            if (sMaskScale < 0.999f) {
                int[] alphas = (int[]) XposedHelpers.getObjectField(p, "alphas");
                int[] rgbs = (int[]) XposedHelpers.getObjectField(p, "RGBs");
                if (alphas == null || rgbs == null || alphas.length != rgbs.length) {
                    return;
                }
                int[] na = new int[alphas.length];
                for (int i = 0; i < alphas.length; i++) {
                    na[i] = Math.max(0, Math.min(255, Math.round(alphas[i] * sMaskScale)));
                }
                XposedHelpers.setObjectField(p, "alphas", na);
                Object mask = XposedHelpers.callStaticMethod(colorHelper, "composeColors", rgbs, na);
                XposedHelpers.setObjectField(p, "maskColors", mask);
            }
        } catch (Throwable ignored) {
        }
    }

    /** Scale the alpha of every mask/overlay color the glass renderer uses. */
    private static void hookBlurParams(ClassLoader cl) {
        try {
            Class<?> bpc = XposedHelpers.findClass(BPC_CLASS, cl);

            XposedBridge.hookAllMethods(bpc, "initBulrParams", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    refreshProps();
                    Object o = param.thisObject;
                    if (sKgBlurRadius > 0.01f && param.args.length >= 3
                            && Boolean.TRUE.equals(param.args[2])
                            && param.args[0] instanceof Integer && (Integer) param.args[0] == 2) {
                        // register this config as a keyguard-card recipe so
                        // turnOnViewBlur can raise the kg edge-light gate
                        sKgConfigs.add(o);
                        // v2.5.13 kbshade: with the light synthesis bitmap
                        // active (kbblur>0), the keyguard card switches from
                        // the z=true thick_1 recipe to the SHADE recipe
                        // (z=false branch of the same stock code): light ntf
                        // colors + brightness — the exact params the
                        // pull-down shade cards render with (stock 0.08;
                        // v2.5.14 raises to 0.12 because the keyguard card
                        // floats over SHARP wallpaper while shade cards float
                        // over the already-dimmed panel sheet, so the same
                        // veil reads darker here). Paired with the
                        // panel-recipe bitmap (blur + sat + veil) this
                        // replicates the shade look on the lock screen. Only
                        // when kbblur>0: on the stock bokeh this recipe has
                        // no structure to refract and reads flat (v2.5.10
                        // lesson). Runs BEFORE the mask scaling so the light
                        // colors get the same x mask treatment as the shade.
                        try {
                            int kMask = resolveStockColor(o, "ntf_background_color_transparent", 0x00000000);
                            int kOverlay = resolveStockColor(o, "card_background_overlay_color_transparent", 0x00000000);
                            // v2.5.15: kbbright knob (default 0.12). Pixel A/B
                            // showed the keyguard card face sits 28-42 levels
                            // BELOW its sharp-wallpaper backdrop while shade
                            // cards sit +20 ABOVE their dimmed panel — the
                            // "sunk decal" vs "floating glass" difference. The
                            // veil alone can't close that gap (0.12 already
                            // near the readable ceiling), so expose the
                            // brightness directly.
                            XposedHelpers.setFloatField(o, "liauidGlassNtfbrightness", sKgBrightness);
                            XposedHelpers.setIntField(o, "liauidGlassMaskColor", kMask);
                            XposedHelpers.setIntField(o, "liauidGlassNtfOverlayColor", kOverlay);
                            XposedHelpers.setObjectField(o, "multiMaskColors", new int[]{kMask, kOverlay});
                            if (sKgShadeLogged < 4) {
                                sKgShadeLogged++;
                                log("kbshade: shade recipe on keyguard card (mask=0x"
                                        + Integer.toHexString(kMask) + " overlay=0x"
                                        + Integer.toHexString(kOverlay) + " brightness=" + sKgBrightness + ")");
                            }
                        } catch (Throwable t) {
                            log("kbshade failed: " + t);
                        }
                    }
                    if (sMaskScale < 0.999f) {
                        try {
                            scaleAlphaField(o, "liauidGlassMaskColor", sMaskScale);
                            scaleAlphaField(o, "liauidGlassNtfOverlayColor", sMaskScale);
                            scaleAlphaField(o, "controlMaskColor", sMaskScale);
                            scaleAlphaField(o, "controlOverlayColor", sMaskScale);
                            scaleAlphaField(o, "controlNtfMaskColor", sMaskScale);
                            scaleAlphaField(o, "controlNtfverlayColor", sMaskScale);
                            scaleAlphaField(o, "sliderMaskColor", sMaskScale);
                            scaleAlphaField(o, "sliderOverlayColor", sMaskScale);
                            scaleAlphaField(o, "buttonMaskColor", sMaskScale);
                            scaleAlphaField(o, "buttonOverlayColor", sMaskScale);
                            scaleAlphaArray(o, "multiMaskColors", sMaskScale);
                            scaleAlphaArray(o, "multiControlMaskColors", sMaskScale);
                            scaleAlphaArray(o, "multiSliderMaskColors", sMaskScale);
                            scaleAlphaArray(o, "multiButtonMaskColors", sMaskScale);
                            scaleAlphaArray(o, "dynamicCardGradientMaskColor", sMaskScale);
                            scaleAlphaArray(o, "dynamicCardGradientMaskColorOther", sMaskScale);
                            if (sMaskCount < 3) {
                                sMaskCount++;
                                log("mask: initBulrParams case="
                                        + (param.args.length > 0 ? param.args[0] : "?")
                                        + " alpha x" + sMaskScale);
                            }
                        } catch (Throwable t) {
                            log("mask scale failed: " + t);
                        }
                    }
                    if (sNtfMask > 0.001f) {
                        // absolute override AFTER the generic scale so the
                        // card tint is exactly nmask, independent of mask prop
                        try {
                            int tint = ((int) (sNtfMask * 255.0f + 0.5f)) << 24 | 0x2E2E2E;
                            int overlay = ((int) (sNtfMask * 0.5f * 255.0f + 0.5f)) << 24 | 0x2E2E2E;
                            XposedHelpers.setIntField(o, "controlNtfMaskColor", tint);
                            XposedHelpers.setIntField(o, "controlNtfverlayColor", overlay);
                            if (sNtfCount < 3) {
                                sNtfCount++;
                                log("ntf-tint: card mask=0x" + Integer.toHexString(tint)
                                        + " overlay=0x" + Integer.toHexString(overlay)
                                        + " (case=" + (param.args.length > 0 ? param.args[0] : "?") + ")");
                            }
                        } catch (Throwable t) {
                            log("ntf tint failed: " + t);
                        }
                    }
                    // v2.5.12/13: keyguard cards (case=2, z=true) stay on the
                    // STOCK recipe (thick_1 colors x mask, brightness -0.15,
                    // saturation 1.2) when kbblur==0 — the v2.3 look. When
                    // kbblur>0 the kbshade block above already switched them
                    // to the shade recipe; nothing more to do here.
                    try {
                        scaleFloatField(o, "refraction", sRefractScale);
                        scaleFloatField(o, "depth", sRefractScale);
                        scaleFloatField(o, "thickness", sThickScale);
                        scaleFloatField(o, "dispersion", sDispScale);
                        if (sRefractLogged < 3) {
                            sRefractLogged++;
                            log("refract: x" + sRefractScale + " thick x" + sThickScale
                                    + " disp x" + sDispScale
                                    + " (refraction=" + XposedHelpers.getFloatField(o, "refraction")
                                    + " thickness=" + XposedHelpers.getFloatField(o, "thickness")
                                    + " dispersion=" + XposedHelpers.getFloatField(o, "dispersion") + ")");
                        }
                    } catch (Throwable t) {
                        log("refract scale failed: " + t);
                    }
                    if (sFolderMode && param.args.length > 0 && param.args[0] instanceof Integer
                            && (Integer) param.args[0] >= 2) {
                        // launcher folder recipe (LiquidLargeOnHighGeneral):
                        // refraction 0.3 / depth 0.54 / thickness 0.62 /
                        // dispersion 0.2 — wide soft refraction band with a
                        // restrained chromatic fringe, no glow. Absolute
                        // values (after any scaling) so tiles render exactly
                        // like desktop folders. Cases >=2 are the liquid
                        // branches (2 = tiles, 3 = ntf banner).
                        // thickness honors the thick prop as a multiplier on
                        // the folder base (0.62) — widens the edge refraction
                        // band without touching refraction/dispersion.
                        try {
                            XposedHelpers.setFloatField(o, "refraction", 0.3f);
                            XposedHelpers.setFloatField(o, "depth", 0.54f);
                            XposedHelpers.setFloatField(o, "thickness", 0.62f * sThickScale);
                            XposedHelpers.setFloatField(o, "dispersion", 0.2f);
                            if (sFolderLogged < 3) {
                                sFolderLogged++;
                                log("folder: liquid optics -> 0.3/0.54/"
                                        + (0.62f * sThickScale) + "/0.2 (case="
                                        + param.args[0] + ", thick x" + sThickScale + ")");
                            }
                        } catch (Throwable t) {
                            log("folder optics failed: " + t);
                        }
                    }
                    if (Math.abs(sTileBlurScale - 1.0f) > 0.001f) {
                        // second blur pass: every tile / card re-blurs the
                        // panel bitmap with blurRadius — the residual frosted
                        // look. Independent of the background radius prop.
                        try {
                            float br = XposedHelpers.getFloatField(o, "blurRadius");
                            float nbr = Math.max(1.0f, br * sTileBlurScale);
                            XposedHelpers.setFloatField(o, "blurRadius", nbr);
                            float dbr = XposedHelpers.getFloatField(o, "dynamicCardBlurRadius");
                            if (dbr > 0.01f) {
                                XposedHelpers.setFloatField(o, "dynamicCardBlurRadius",
                                        Math.max(1.0f, dbr * sTileBlurScale));
                            }
                            if (sTileBlurLogged < 3) {
                                sTileBlurLogged++;
                                log("tblur: case=" + (param.args.length > 0 ? param.args[0] : "?")
                                        + " blurRadius " + br + " -> " + nbr
                                        + " dynamicCard " + dbr + " x" + sTileBlurScale);
                            }
                        } catch (Throwable t) {
                            log("tblur scale failed: " + t);
                        }
                    }
                }
            });

            log("ok: BlurParametersConfig mask-scale installed");
        } catch (Throwable t) {
            log("skip: BlurParametersConfig missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void scaleAlphaField(Object obj, String field, float scale) throws Throwable {
        int c = XposedHelpers.getIntField(obj, field);
        XposedHelpers.setIntField(obj, field, scaleAlpha(c, scale));
    }

    private static void scaleFloatField(Object obj, String field, float scale) throws Throwable {
        float v = XposedHelpers.getFloatField(obj, field);
        XposedHelpers.setFloatField(obj, field, v * scale);
    }

    private static void scaleAlphaArray(Object obj, String field, float scale) throws Throwable {
        Object arr = XposedHelpers.getObjectField(obj, field);
        if (arr == null) {
            return;
        }
        int n = Array.getLength(arr);
        for (int i = 0; i < n; i++) {
            Object v = Array.get(arr, i);
            if (v instanceof Integer) {
                Array.set(arr, i, scaleAlpha((Integer) v, scale));
            }
        }
    }

    private static int scaleAlpha(int color, float scale) {
        int a = (color >>> 24) & 0xFF;
        int na = Math.round(a * scale);
        return (na << 24) | (color & 0x00FFFFFF);
    }

    /** Resolve a stock SystemUI color resource by name; fallback if missing. */
    private static int resolveStockColor(Object cfg, String name, int fallback) {
        try {
            Object ctx = XposedHelpers.getObjectField(cfg, "mContext");
            Object res = XposedHelpers.callMethod(ctx, "getResources");
            String pkg = (String) XposedHelpers.callMethod(ctx, "getPackageName");
            int id = (Integer) XposedHelpers.callMethod(res, "getIdentifier", name, "color", pkg);
            if (id == 0) {
                return fallback;
            }
            return (Integer) XposedHelpers.callMethod(ctx, "getColor", id);
        } catch (Throwable t) {
            return fallback;
        }
    }

    /** Swap FROSTED blur-type bit to LIQUID in the extended Configuration. */
    private static void hookConfigEx(ClassLoader cl) {
        try {
            Class<?> cex = XposedHelpers.findClass(CONFIG_EX_CLASS, cl);
            XposedBridge.hookAllMethods(cex, "getTransparentMode", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object r = param.getResult();
                    if (!(r instanceof Integer)) {
                        return;
                    }
                    int orig = (Integer) r;
                    if (sConfigDiagCount < 5) {
                        sConfigDiagCount++;
                        log("diag: getTransparentMode raw=0x" + Integer.toHexString(orig));
                    }
                    if ((orig & BLUR_TYPE_MASK) == BLUR_TYPE_FROSTED) {
                        param.setResult((orig & ~BLUR_TYPE_MASK) | BLUR_TYPE_LIQUID);
                        if (!sConfigLogged) {
                            sConfigLogged = true;
                            log("ok: config transparentMode 0x"
                                    + Integer.toHexString(orig) + " -> 0x"
                                    + Integer.toHexString((orig & ~BLUR_TYPE_MASK) | BLUR_TYPE_LIQUID)
                                    + " (FROSTED->LIQUID bit)");
                        }
                    }
                }
            });
            log("ok: ConfigurationEx.getTransparentMode FROSTED->LIQUID installed");
        } catch (Throwable t) {
            log("skip: ConfigurationEx missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    /** Hook the framework-side getters everything reads from (incl. via reflection). */
    private static void hookFwDataSource(ClassLoader cl) {
        try {
            Class<?> fw = XposedHelpers.findClass(UIMODE_FW_CLASS, cl);
            final int minDegree = readDegreeProp();

            XposedBridge.hookAllMethods(fw, "getTransparentTexture", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int orig = (Integer) param.getResult();
                    if (sTextureDiagCount < 5) {
                        sTextureDiagCount++;
                        log("diag: getTransparentTexture raw=" + orig);
                    }
                    if (orig != 2) {
                        param.setResult(2);
                        if (!sTextureLogged) {
                            sTextureLogged = true;
                            log("ok: framework texture " + orig + " -> 2 (LIQUID)");
                        }
                    }
                }
            });

            XposedBridge.hookAllMethods(fw, "getTransparentDegree", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    int orig = (Integer) param.getResult();
                    if (orig < minDegree) {
                        param.setResult(minDegree);
                        if (!sDegreeLogged) {
                            sDegreeLogged = true;
                            log("ok: framework degree " + orig + " -> " + minDegree);
                        }
                    }
                }
            });

            log("ok: framework data-source hooks installed (minDegree=" + minDegree + ")");
        } catch (Throwable t) {
            log("skip: framework UiModeManagerEx missing ("
                    + t.getClass().getSimpleName() + ")");
        }
    }

    private static int readDegreeProp() {
        try {
            Method m = Class.forName("android.os.SystemProperties")
                    .getMethod("getInt", String.class, int.class);
            int v = (Integer) m.invoke(null, DEGREE_PROP, DEFAULT_DEGREE);
            if (v < 60) {
                v = 60;
            } else if (v > 100) {
                v = 100;
            }
            return v;
        } catch (Throwable t) {
            return DEFAULT_DEGREE;
        }
    }

    private static float readFloatProp(String name, float def, float min, float max) {
        try {
            Method m = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class);
            String v = (String) m.invoke(null, name, String.valueOf(def));
            float f = Float.parseFloat(v);
            return Math.max(min, Math.min(max, f));
        } catch (Throwable t) {
            return def;
        }
    }

    private static void hookMaterialBits(ClassLoader cl) {
        try {
            Class<?> cfg = XposedHelpers.findClass(CFG_CLASS, cl);
            XposedBridge.hookAllMethods(cfg, "getConfigTransparentModeMaterial",
                    XC_MethodReplacement.returnConstant(3));
            log("ok: getConfigTransparentModeMaterial -> 3 (LIQUID effect bit restored)");
        } catch (Throwable t) {
            log("skip: TransparencyFeatureConfig missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookMachineConfig(ClassLoader cl) {
        try {
            Class<?> mc = XposedHelpers.findClass(MACHINE_CLASS, cl);

            XposedBridge.hookAllMethods(mc, "getInstance", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object inst = param.getResult();
                    if (inst == null) {
                        return;
                    }
                    try {
                        XposedHelpers.setIntField(inst, "transparencyMaterialLevel", 0);
                        XposedHelpers.setBooleanField(inst, "isSupportTransConfig", true);
                        XposedHelpers.setObjectField(inst, "machineType", null);
                    } catch (Throwable t) {
                        log("machine field fix failed: " + t);
                    }
                }
            });

            XposedBridge.hookAllMethods(mc, "isHighMachine",
                    XC_MethodReplacement.returnConstant(true));
            XposedBridge.hookAllMethods(mc, "isCompatMachine",
                    XC_MethodReplacement.returnConstant(false));

            try {
                final Class<?> mt = XposedHelpers.findClass(MACHINE_TYPE_CLASS, cl);
                final Object high = XposedHelpers.getStaticObjectField(mt, "HIGH");
                XposedBridge.hookAllMethods(mc, "getMachineType", new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (param.getResult() != high) {
                            param.setResult(high);
                        }
                    }
                });
            } catch (Throwable t) {
                log("getMachineType hook skipped: " + t);
            }

            log("ok: MachineConfig forced to HIGH tier");
        } catch (Throwable t) {
            log("skip: MachineConfig missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void hookStyleType(ClassLoader cl) {
        try {
            final Class<?> styleType = XposedHelpers.findClass(STYLE_TYPE_CLASS, cl);
            final Class<?> wrapper = XposedHelpers.findClass(UIMODE_WRAPPER_CLASS, cl);
            final Object liquid = XposedHelpers.getStaticObjectField(styleType, "LIQUID_GLASS_TRANSPARENT");
            final Object frosted = XposedHelpers.getStaticObjectField(styleType, "FROSTED_GLASS_TRANSPARENCY");

            XposedBridge.hookAllMethods(styleType, "getMaterialType", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.getResult() != frosted) {
                        return;
                    }
                    try {
                        Object mode = XposedHelpers.callStaticMethod(wrapper, "getTransparentMode");
                        if (mode instanceof Integer && ((Integer) mode).intValue() == 1) {
                            param.setResult(liquid);
                            if (!sFlipLogged) {
                                sFlipLogged = true;
                                log("ok: material type flipped FROSTED -> LIQUID (transparency on)");
                            }
                        }
                    } catch (Throwable ignored) {
                    }
                }
            });
            log("ok: StyleType.getMaterialType FROSTED->LIQUID guard installed");
        } catch (Throwable t) {
            log("skip: StyleType missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    private static void log(String msg) {
        XposedBridge.log("[" + TAG + "] " + msg);
    }

    /**
     * The panel background = wallpaper screenshot blurred by
     * SurfaceControlEx.screenshot() with HnStaticBlurParamsEx(radius, mask).
     * The mask (window_blur_view_mask_color_transparent, #9e1a1a1a) is the
     * dark veil covering 62% of the blurred wallpaper — the single biggest
     * opacity lever for the overall panel. Scale its alpha before the params
     * object is built. initBlurPara() re-invokes this before every capture,
     * so live tuning applies on the next pull-down.
     */
    private static void hookPanelVeil(ClassLoader cl) {
        try {
            Class<?> sbv = XposedHelpers.findClass(SHOTBLUR_CLASS, cl);

            XposedBridge.hookAllMethods(sbv, "setBlurParams", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 2 || !(param.args[1] instanceof Integer)) {
                        return;
                    }
                    refreshProps();
                    if (param.args.length >= 1 && param.args[0] instanceof Float
                            && Math.abs(sSatScale - 1.0f) > 0.001f) {
                        param.args[0] = (Float) param.args[0] * sSatScale;
                    }
                    int orig = (Integer) param.args[1];
                    if (sPanelCount < 4) {
                        sPanelCount++;
                        log("panel: setBlurParams sat=" + param.args[0]
                                + " mask=0x" + Integer.toHexString(orig)
                                + " x" + sMaskScale);
                    }
                    if (sMaskScale >= 0.999f) {
                        return;
                    }
                    param.args[1] = scaleAlpha(orig, sMaskScale);
                }
            });

            log("ok: ShotBlurView panel-veil mask-scale installed");
        } catch (Throwable t) {
            log("skip: ShotBlurView missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * Apple-style bright rim: every glass surface (control-center tiles in
     * ControlCenterViewModel, volume panel in HnViewBlurControllerImpl,
     * notification cards) builds its edge light through the same 2-arg
     * constructor EdgeLightParamEx(angle=250, thickness=6.0f). Constructors
     * are never inlined away, so scaling the thickness argument here covers
     * all of them. Color is already opaque white, width is the only lever.
     */
    private static void hookEdgeRim(ClassLoader cl) {
        try {
            Class<?> el = XposedHelpers.findClass(
                    "com.hihonor.android.graphics.material.atomiceffect.EdgeLightParamEx", cl);

            XposedBridge.hookAllConstructors(el, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 2 || !(param.args[1] instanceof Float)) {
                        return;
                    }
                    refreshProps();
                    // v2.5.16: kedge (keyguard-only multiplier). The wide
                    // refraction band the lock screen needs (x1.4) washed out
                    // the shade cards when applied globally — the user reads
                    // the shade as "a bit too bright". sKgCardNow is raised by
                    // the turnOnViewBlur gate around keyguard-card recipes.
                    float scale = sKgCardNow ? sKgEdgeScale : sEdgeScale;
                    if (Math.abs(scale - 1.0f) <= 0.001f) {
                        return;
                    }
                    float orig = (Float) param.args[1];
                    // some callers re-construct from an already-scaled param
                    // (copy pattern) — only scale the stock constant to
                    // avoid compounding (6.0 -> 7.8 -> 10.1 observed)
                    if (Math.abs(orig - 6.0f) > 0.01f) {
                        return;
                    }
                    param.args[1] = orig * scale;
                    if (sEdgeCount < 3) {
                        sEdgeCount++;
                        log("edge-rim: thickness " + orig + " -> " + (orig * scale)
                                + (sKgCardNow ? " (keyguard)" : ""));
                    }
                }
            });

            // folder-style rim: launcher folders use white@40-90% alpha while
            // SystemUI uses OPAQUE white (EDGE_COLOR=-1) — the source of the
            // "glowing border" complaint. Scale the alpha of setColor(int).
            // NOTE: LegacyBridge ignores hookMethod(single Method) — must
            // use hookAllMethods (verified via edge-rim logs).
            XposedBridge.hookAllMethods(el, "setColor", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length < 1 || !(param.args[0] instanceof Integer)) {
                        return;
                    }
                    refreshProps();
                    int c = (Integer) param.args[0];
                    int alpha = (c >>> 24) & 0xFF;
                    if (sRimCount < 4) {
                        sRimCount++;
                        log("rim: setColor arg=0x" + Integer.toHexString(c)
                                + " alpha=" + alpha + " scale=" + sRimScale);
                    }
                    if (sRimScale >= 0.999f || alpha == 0) {
                        return;
                    }
                    param.args[0] = (Math.round(alpha * sRimScale) << 24) | (c & 0xFFFFFF);
                }
            });

            log("ok: EdgeLightParamEx rim-width installed");
        } catch (Throwable t) {
            log("skip: EdgeLightParamEx missing (" + t.getClass().getSimpleName() + ")");
        }
    }

    /**
     * v2.5 lockscreen fix (THE one). v2.4.2 diagnostics proved the whole
     * chain is alive on a static-photo wallpaper: glassType=2, liquid=true,
     * HnLiquidGlassMaterial applied to toolbox icons / cards / button
     * frames — but KeyguardWallpaper.getCurrentBlurBitmapForBg() returns
     * NULL every single call, so the material has no background to sample
     * and renders flat. Stock only produces that bitmap when the wallpaper
     * drawable is a BokehDrawable (magazine / dynamic); a plain photo
     * starves every consumer:
     *   - ViewBlur.blurIcon -> HnMaterialOpt.setBitmap(bmp, .25f, .25f)
     *   - KeyguardWallpaper -> blurWallpaperBitmap(ctx, bmp) (cards/panel)
     *   - setBluredTextBackground paths (footer text readability)
     *
     * Fix: AFTER-hook getCurrentBlurBitmapForBg; when the stock result is
     * null, synthesize it with the exact stock recipe (BokehDrawable.
     * initBlurTextBitmap): wallpaper drawable -> center-crop to screen ->
     * ViewBlur.blurBitmap(bmp, 1.0f, transparentRadius/4, maskColor).
     * Cached against the drawable identity + screen size; regenerated only
     * when the wallpaper actually changes.
     *
     * Safety: default OFF via persist.sys.lgr.kbg. Pure addition — when
     * stock returns a bitmap (dynamic wallpaper) this hook does nothing.
     * No early init: fires only while the keyguard is already laid out.
     */
    private static void hookKeyguardBgBitmap(ClassLoader cl) {
        final Class<?> kgw;
        try {
            kgw = XposedHelpers.findClass("com.hihonor.keyguard.wallpaper.KeyguardWallpaper", cl);
        } catch (Throwable t) {
            log("skip: kg-bg KeyguardWallpaper missing (" + t.getClass().getSimpleName() + ")");
            return;
        }

        // hook 1: on-demand. Two jobs:
        //  a) stock returned a valid bitmap (BokehDrawable ready): copy it once
        //     into a hard reference so it survives AOD screen-off recycling,
        //     and fire the re-apply so views built during the null window
        //     (first ~2s after SystemUI start) get refreshed.
        //  b) stock returned null (early boot / post-recycle wake): serve the
        //     retained copy; if none yet, fall back to src-based synthesis.
        XposedBridge.hookAllMethods(kgw, "getCurrentBlurBitmapForBg", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                refreshProps();
                Bitmap stock = (Bitmap) param.getResult();
                if (sKeyguardBg && sKgBlurRadius > 0.01f) {
                    // v2.5.7 kbblur: the stock bokeh is baked far too blurry
                    // (plus a dark mask) for liquid cards — serve a light,
                    // tunable synthesis from the sharp wallpaper instead.
                    // Music keyguard keeps its stock look.
                    boolean music = false;
                    try {
                        music = XposedHelpers.getBooleanField(param.thisObject, "mInMusicWallpaper");
                    } catch (Throwable ignored) {
                    }
                    if (!music) {
                        try {
                            Bitmap light = ensureLightBitmap(param.thisObject, cl);
                            if (light != null && !light.isRecycled()) {
                                param.setResult(light);
                                return;
                            }
                        } catch (Throwable t) {
                            log("kg-bg light failed: " + t);
                        }
                    }
                    // synthesis unavailable -> fall through to stock behavior
                }
                if (stock != null && !stock.isRecycled()) {
                    if (sKeyguardBg) {
                        Bitmap serve = captureStockBitmap(stock, param.thisObject, cl);
                        if (serve != null && !serve.isRecycled()) {
                            param.setResult(serve);
                        }
                    }
                    return;
                }
                if (!sKeyguardBg) {
                    return;
                }
                try {
                    // music wallpaper intentionally returns null (isUseBlurNull)
                    if (XposedHelpers.getBooleanField(param.thisObject, "mInMusicWallpaper")) {
                        return;
                    }
                } catch (Throwable ignored) {
                }
                try {
                    if (sSynthBmp != null && !sSynthBmp.isRecycled()) {
                        param.setResult(sSynthBmp);
                        if (sKgBgServed < 10) {
                            sKgBgServed++;
                            log("kg-bg: cache-served (gen=" + sSynthGen + ")");
                        }
                    }
                } catch (Throwable t) {
                    log("kg-bg failed: " + t);
                }
            }
        });

        // hook 2: wallpaper-ready fallback. If the stock BokehDrawable path
        // never produces a bitmap, build one via src-based synthesis when
        // processWallpaperChange finishes assigning the wallpaper, then push
        // it through the stock re-apply sequence. Also pure-observation
        // logging (kdiag) so we can see when pwc actually fires.
        XposedBridge.hookAllMethods(kgw, "processWallpaperChange", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                refreshProps();
                if (sKeyguardDiag && sDiagPwc < 10) {
                    sDiagPwc++;
                    Object src = null;
                    try {
                        src = XposedHelpers.getObjectField(
                                param.thisObject, "mCurrentBackgroundWallpaper");
                    } catch (Throwable ignored) {
                    }
                    log("diag pwc fired: src=" + (src == null
                            ? "null" : src.getClass().getSimpleName()));
                }
                if (!sKeyguardBg) {
                    return;
                }
                try {
                    // light recipe when kbblur>0 (ensureLightBitmap itself
                    // fires the re-apply on re-synthesis; fireKgReapply
                    // dedupes by gen), otherwise re-apply the retained
                    // stock capture if we have one
                    Bitmap bmp = sKgBlurRadius > 0.01f
                            ? ensureLightBitmap(param.thisObject, cl)
                            : sSynthBmp;
                    if (bmp != null && !bmp.isRecycled()) {
                        fireKgReapply(bmp, param.thisObject, cl);
                    }
                } catch (Throwable t) {
                    log("kg-bg reapply failed: " + t);
                }
            }
        });
        log("ok: keyguard bg-bitmap synthesis installed (default off)");
    }

    /**
     * v2.5.4: switch toolbox icons (flashlight/camera) from the dead
     * HnMaterial route to the working ViewEx route.
     *
     * ToolBoxView calls the 3-arg ViewBlur.blurIcon(view, i, z), which builds
     * an HnLiquidGlassMaterial + GlassParamEx + edge light and hands the
     * captured blur bitmap to HnMaterialOpt.setBitmap. v2.5.3 diagnostics
     * proved all of that lands on the view — yet the framework Material
     * renderer never draws the glass body (only the thin ring survives).
     * The ViewEx system (setBluredTextBackground + BlurMode.BitmapIconBlur)
     * renders the same bitmap correctly — that is exactly what made the
     * v2.5.2 notification cards glassy.
     *
     * The stock itself keeps a ViewEx fallback for blurIcon in its catch
     * block: blurIcon(view, i, null, false, z). We take that overload but
     * pass z=true so updateBlurParams applies the stock readability recipe
     * (radius ~39px by transparency_value, saturation 3.0, brightness +0.4
     * for light backgrounds). Pure stock code paths; if the reflective call
     * throws we leave the original 3-arg invocation untouched.
     *
     * Safety: default OFF via persist.sys.lgr.kicon. No field writes, no
     * extra class init (ViewBlur is only touched when the keyguard is
     * already laying out icons).
     */
    private static void hookKeyguardIconFallback(ClassLoader cl) {
        final Class<?> vb;
        try {
            vb = XposedHelpers.findClass("com.hihonor.keyguard.support.ViewBlur", cl);
        } catch (Throwable t) {
            log("skip: kg-icon ViewBlur missing (" + t.getClass().getSimpleName() + ")");
            return;
        }
        XposedBridge.hookAllMethods(vb, "blurIcon", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (param.args.length != 3 || !(param.args[0] instanceof View)) {
                    return;
                }
                refreshProps();
                if (!sKgIconFallback) {
                    return;
                }
                View v = (View) param.args[0];
                int i = (Integer) param.args[1];
                boolean z = (Boolean) param.args[2];
                trackIconTarget(v, i, z);
                try {
                    XposedHelpers.callStaticMethod(vb, "blurIcon",
                            new Class[]{View.class, int.class, Object.class, boolean.class, boolean.class},
                            v, Integer.valueOf(i), null, Boolean.TRUE, Boolean.valueOf(z));
                    if (sKgIconLogged < 10) {
                        sKgIconLogged++;
                        log("kg-icon: redirected to ViewEx route (z=" + z + ")");
                    }
                    param.setResult(null);
                } catch (Throwable t) {
                    log("kg-icon redirect failed: " + t.getClass().getSimpleName());
                }
            }
        });
        log("ok: keyguard icon ViewEx fallback installed (default off)");
    }

    private static final class IconTarget {
        final WeakReference<View> ref;
        final int blurVal;
        final boolean liquid;

        IconTarget(View v, int i, boolean z) {
            ref = new WeakReference<View>(v);
            blurVal = i;
            liquid = z;
        }
    }

    private static void trackIconTarget(View v, int i, boolean z) {
        for (int k = sIconTargets.size() - 1; k >= 0; k--) {
            View t = sIconTargets.get(k).ref.get();
            if (t == v) {
                return;
            }
            if (t == null) {
                sIconTargets.remove(k);
            }
        }
        if (sIconTargets.size() < 16) {
            sIconTargets.add(new IconTarget(v, i, z));
        }
    }

    /**
     * v2.5.5: startup race fix. ToolBoxView calls blurIcon ~1-2s after
     * SystemUI start, but the stock blur bitmap only appears ~1.4-2.4s in,
     * and stock never calls blurIcon again afterwards — icons blurred during
     * the null window stay flat forever (v2.5.4 run2: both waves at +1.3s/
     * +2.0s, capture at +2.4s, screenshot flat; run1: second wave landed
     * after capture, screenshot glassy). Re-run the ViewEx route on every
     * tracked icon view once a bitmap generation becomes available.
     */
    private static void reapplyIconTargets(ClassLoader cl) {
        if (!sKgIconFallback || sIconTargets.isEmpty()) {
            return;
        }
        int n = 0;
        try {
            Class<?> vb = XposedHelpers.findClass("com.hihonor.keyguard.support.ViewBlur", cl);
            for (int k = sIconTargets.size() - 1; k >= 0; k--) {
                IconTarget t = sIconTargets.get(k);
                View v = t.ref.get();
                if (v == null) {
                    sIconTargets.remove(k);
                    continue;
                }
                try {
                    XposedHelpers.callStaticMethod(vb, "blurIcon",
                            new Class[]{View.class, int.class, Object.class, boolean.class, boolean.class},
                            v, Integer.valueOf(t.blurVal), null, Boolean.TRUE, Boolean.valueOf(t.liquid));
                    n++;
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            log("kg-icon re-apply failed (" + t.getClass().getSimpleName() + ")");
            return;
        }
        if (n > 0) {
            log("kg-icon: re-applied to " + n + " view(s) (gen=" + sSynthGen + ")");
        }
    }

    /**
     * v2.5.12: returns the hard-ref copy to SERVE (replacing the stock result)
     * so the bitmap survives AOD screen-off recycling, and every consumer
     * (cards via blurWallpaperBitmap, buttons via the engine) sees the same
     * pixels. The copy is pixel-identical to the stock bokeh — NO veil, NO
     * re-tint: the v2.3 look is the stock bokeh under the stock keyguard card
     * recipe (thick_1 colors x mask prop), with folder refraction / tblur /
     * saturation applied by the generic initBulrParams hooks. Any darkening
     * must come from the card's own mask color, never from baking a flat veil
     * into the shared bitmap — a flat veil leaves the refraction band nothing
     * to bend and reads as plain fog. Cache key: stock instance.
     */
    private static Bitmap captureStockBitmap(Bitmap stock, Object kgwInst, ClassLoader cl) {
        try {
            if (sStockBmpRef != null && sStockBmpRef.get() == stock
                    && sSynthBmp != null && !sSynthBmp.isRecycled()) {
                return sSynthBmp;
            }
            Bitmap copy = stock.copy(Bitmap.Config.ARGB_8888, false);
            if (copy == null) {
                logMiss("stock copy failed");
                return null;
            }
            sSynthBmp = copy;
            sStockBmpRef = new WeakReference<Bitmap>(stock);
            sSynthSrcRef = null;
            sSynthGen++;
            if (sKgBgLogged < 6) {
                sKgBgLogged++;
                log("kg-bg: captured stock bitmap " + stock.getWidth() + "x" + stock.getHeight()
                        + " gen=" + sSynthGen);
            }
            fireKgReapply(copy, kgwInst, cl);
            return copy;
        } catch (Throwable t) {
            log("kg-bg capture failed: " + t);
            return null;
        }
    }

    private static void fireKgReapply(final Bitmap bmp, Object kgwInst, final ClassLoader cl) {
        if (bmp == null || sSynthGen == sKgBgAppliedGen) {
            return;
        }
        sKgBgAppliedGen = sSynthGen;
        Context ctx = null;
        try {
            ctx = (Context) XposedHelpers.getObjectField(kgwInst, "mContext");
        } catch (Throwable ignored) {
        }
        final Context fCtx = ctx;
        if (sKgHandler == null) {
            sKgHandler = new Handler(Looper.getMainLooper());
        }
        sKgHandler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Class<?> kum = XposedHelpers.findClass(
                            "com.android.keyguard.KeyguardUpdateMonitor", cl);
                    XposedHelpers.callMethod(
                            XposedHelpers.callStaticMethod(kum, "getInstance"),
                            "blurBitmapChanged");
                } catch (Throwable t) {
                    log("kg-bg: blurBitmapChanged failed (" + t.getClass().getSimpleName() + ")");
                }
                try {
                    if (fCtx != null) {
                        Class<?> dep = XposedHelpers.findClass(
                                "com.hihonor.systemui.magic.HwDependency", cl);
                        Class<?> ctrlIf = XposedHelpers.findClass(
                                "com.hihonor.systemui.magic.blur.HnViewBlurController", cl);
                        Object ctrl = XposedHelpers.callStaticMethod(dep, "get", ctrlIf);
                        if (ctrl != null) {
                            XposedHelpers.callMethod(ctrl, "blurWallpaperBitmap", fCtx, bmp);
                        }
                    }
                } catch (Throwable t) {
                    log("kg-bg: blurWallpaperBitmap failed (" + t.getClass().getSimpleName() + ")");
                }
                // keyguardengine elements (toolbox buttons etc.) hold their own
                // state; the ONLY stock trigger that pushes a new blur bitmap
                // into them is a real wallpaper change. Late-ready bitmaps never
                // reach them, so buttons stay flat. Forward through the stock
                // onWallpaperChanged path: sets state + notifyStateChanged(16)
                // -> ViewRenderManager re-runs BlurManager on every registered
                // provider -> GlassEffectStrategy re-fetches the bitmap (through
                // our hooked getCurrentBlurBitmapForBg) and re-applies material.
                try {
                    if (sKgEngineNotify) {
                        Class<?> eng = XposedHelpers.findClass(
                                "com.hihonor.keyguard.colorpick.KeyguardBusinessDataProvider", cl);
                        Object inst = XposedHelpers.callStaticMethod(eng, "getInstance");
                        if (inst != null) {
                            XposedHelpers.callMethod(inst, "onWallpaperChanged", bmp);
                            log("kg-bg: engine onWallpaperChanged ok (gen=" + sSynthGen + ")");
                        }
                    }
                } catch (Throwable t) {
                    log("kg-bg: engine notify failed (" + t.getClass().getSimpleName() + ")");
                }
                reapplyIconTargets(cl);
                log("kg-bg: re-apply fired (gen=" + sSynthGen + ")");
            }
        });
    }

    /**
     * v2.5.7: light-synthesis bitmap served when kbblur>0. Built from the
     * SHARP wallpaper drawable (not the stock bokeh): quarter-res, gaussian
     * radius = kbblur in quarter-res space, no baked veil (v2.5.12 removed
     * the v2.5.9 veil — darkening belongs to the card's own mask color, a
     * baked veil flattens the glass). Cached against (src, w, h, radius);
     * any change re-synthesizes and re-applies.
     */
    private static Bitmap ensureLightBitmap(Object kgwInst, ClassLoader cl) {
        // getCurrentBlurBitmapForBg() itself reads mCurrentColorPickAndBlurWallpaper
        // (the BokehDrawable); mCurrentBackgroundWallpaper is null for most
        // user-set static wallpapers — reading it starved every synthesis.
        Drawable src = null;
        try {
            src = (Drawable) XposedHelpers.getObjectField(kgwInst, "mCurrentColorPickAndBlurWallpaper");
        } catch (Throwable ignored) {
        }
        if (src == null) {
            try {
                src = (Drawable) XposedHelpers.getObjectField(kgwInst, "mCurrentBackgroundWallpaper");
            } catch (Throwable ignored) {
            }
        }
        if (src == null) {
            logMiss("light src=null");
            return null;
        }
        Context ctx = null;
        try {
            ctx = (Context) XposedHelpers.getObjectField(kgwInst, "mContext");
        } catch (Throwable ignored) {
        }
        if (ctx == null) {
            logMiss("light ctx=null");
            return null;
        }
        android.util.DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
        int w = dm.widthPixels;
        int h = dm.heightPixels;
        if (w <= 0 || h <= 0) {
            logMiss("light metrics " + w + "x" + h);
            return null;
        }
        // v2.5.13: replicate the shade panel bitmap recipe — saturation =
        // sat prop, veil = #1A1A1A at alpha 158 x mask prop (43% at 0.7),
        // the exact formula the panel's setBlurParams(1.2, #9e1a1a1a x
        // mask) bakes into its screenshot. The veil only works here because
        // the bitmap underneath is lightly blurred and structured — the
        // refraction band still has content to bend (unlike the stock
        // bokeh, where any veil reads as dead fog).
        final int veilA = Math.max(0, Math.min(255,
                Math.round(158.0f * sMaskScale * sKgVeilScale)));
        final int veilColor = (veilA << 24) | 0x001A1A1A;
        final int satPct = Math.round(sSatScale * 100.0f);
        if (sSynthBmp != null && !sSynthBmp.isRecycled()
                && sSynthSrcRef != null && sSynthSrcRef.get() == src
                && sSynthW == w && sSynthH == h
                && sSynthRadius == sKgBlurRadius
                && sSynthVeilA == veilA && sSynthSatPct == satPct) {
            return sSynthBmp;
        }
        Bitmap srcBmp = bokehGetBitmap(src);
        if (srcBmp == null) {
            srcBmp = drawableToBitmap(src);
        }
        if (srcBmp == null || srcBmp.isRecycled() || srcBmp.getWidth() <= 0) {
            logMiss("light src not drawable->bitmap (" + src.getClass().getName() + ")");
            return null;
        }
        // v2.5.14: crop with the DRAWN wallpaper geometry. The lock screen
        // draws this bitmap at getBokehScaleValueForLockScreen() zoom (often
        // 1.12x) around its center; a plain center-crop misregisters the
        // card content by that zoom and the glass reads as a pasted decal
        // instead of refracting what is behind it. Use the drawable's own
        // crop methods — the same ones the stock initBlurTextBitmap uses —
        // so the served bitmap is pixel-registered with the on-screen
        // wallpaper. Both return quarter-res output directly.
        Bitmap quarter = null;
        String cropPath = "fallback-crop";
        try {
            float kgScale = (Float) XposedHelpers.callMethod(src, "getBokehScaleValueForLockScreen");
            if (kgScale > 1.001f) {
                Bitmap q = (Bitmap) XposedHelpers.callMethod(
                        src, "scaleCropAndMatchScreenOnce", srcBmp, kgScale);
                if (q != null && !q.isRecycled() && q.getWidth() > 0) {
                    quarter = q;
                    cropPath = "scaleCrop x" + kgScale;
                }
            } else {
                Context dctx = (Context) XposedHelpers.getObjectField(src, "mContext");
                Bitmap q = dctx != null
                        ? (Bitmap) XposedHelpers.callMethod(src, "centerCropMatchScreen", dctx, srcBmp)
                        : null;
                if (q != null && !q.isRecycled() && q.getWidth() > 0) {
                    quarter = q;
                    cropPath = "centerCrop (scale=" + kgScale + ")";
                }
            }
        } catch (Throwable t) {
            logMiss("stock crop threw " + t.getClass().getSimpleName());
        }
        if (quarter == null) {
            Bitmap cropped = centerCropToScreen(srcBmp, w, h);
            if (cropped == null) {
                logMiss("light crop failed");
                return null;
            }
            quarter = Bitmap.createScaledBitmap(cropped,
                    Math.max(1, w / 4), Math.max(1, h / 4), true);
            if (cropped != quarter) {
                cropped.recycle();
            }
        }
        Bitmap blurred = null;
        try {
            int radius = Math.max(1, (int) (sKgBlurRadius + 0.5f));
            try {
                Class<?> vb = XposedHelpers.findClass(
                        "com.hihonor.keyguard.support.ViewBlur", cl);
                // stock blurBitmap(bitmap, saturation, radius, maskColor) —
                // same HnStaticBlurParamsEx machinery as the panel blur, so
                // sat + veil land exactly like the panel's own bitmap
                Bitmap out = (Bitmap) XposedHelpers.callStaticMethod(vb, "blurBitmap",
                        quarter, sSatScale, radius, veilColor);
                // blurBitmap returns the INPUT on engine failure — only
                // accept a genuinely different blurred output
                if (out != null && out != quarter && !out.isRecycled()) {
                    blurred = out;
                }
            } catch (Throwable t) {
                logMiss("light stock blur threw " + t.getClass().getSimpleName());
            }
            if (blurred == null) {
                blurred = fastBlurFallback(quarter, radius);
                if (blurred != null && (veilA > 0 || Math.abs(sSatScale - 1.0f) > 0.001f)) {
                    try {
                        Bitmap m = blurred.copy(Bitmap.Config.ARGB_8888, true);
                        if (m != null) {
                            Canvas cv = new Canvas(m);
                            if (Math.abs(sSatScale - 1.0f) > 0.001f) {
                                Paint pt = new Paint();
                                ColorMatrix cm = new ColorMatrix();
                                cm.setSaturation(sSatScale);
                                pt.setColorFilter(new ColorMatrixColorFilter(cm));
                                cv.drawBitmap(blurred, 0, 0, pt);
                            }
                            if (veilA > 0) {
                                cv.drawColor(veilColor);
                            }
                            blurred.recycle();
                            blurred = m;
                        }
                    } catch (Throwable ignored) {
                    }
                }
            }
        } finally {
            if (quarter != null && quarter != blurred) {
                quarter.recycle();
            }
        }
        if (blurred == null || blurred.isRecycled()) {
            logMiss("light blur failed");
            return null;
        }
        sSynthBmp = blurred;
        sStockBmpRef = null;
        sSynthSrcRef = new WeakReference<Drawable>(src);
        sSynthW = w;
        sSynthH = h;
        sSynthRadius = sKgBlurRadius;
        sSynthVeilA = veilA;
        sSynthSatPct = satPct;
        sSynthGen++;
        if (sKgBgLogged < 6) {
            sKgBgLogged++;
            log("kg-bg: light synthesized " + blurred.getWidth() + "x" + blurred.getHeight()
                    + " r=" + (int) sKgBlurRadius + " sat=" + sSatScale
                    + " veil=" + veilA + " gen=" + sSynthGen
                    + " [" + cropPath + ", src " + srcBmp.getWidth() + "x" + srcBmp.getHeight() + "]");
        }
        fireKgReapply(blurred, kgwInst, cl);
        return blurred;
    }

    private static void logMiss(String why) {
        Integer c = sMissCounts.get(why);
        int n = c == null ? 0 : c.intValue();
        if (n < 30) {
            sMissCounts.put(why, n + 1);
            log("kg-bg miss[" + n + "]: " + why);
        }
    }

    private static Bitmap bokehGetBitmap(Drawable d) {
        try {
            Object b = XposedHelpers.callMethod(d, "getBitmap");
            if (b instanceof Bitmap && !((Bitmap) b).isRecycled()) {
                return (Bitmap) b;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static Bitmap drawableToBitmap(Drawable d) {
        if (d instanceof android.graphics.drawable.BitmapDrawable) {
            Bitmap b = ((android.graphics.drawable.BitmapDrawable) d).getBitmap();
            if (b != null && !b.isRecycled()) {
                return b;
            }
        }
        int dw = d.getIntrinsicWidth();
        int dh = d.getIntrinsicHeight();
        if (dw <= 0 || dh <= 0) {
            return null;
        }
        try {
            Bitmap out = Bitmap.createBitmap(dw, dh, Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(out);
            d.setBounds(0, 0, dw, dh);
            d.draw(c);
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap centerCropToScreen(Bitmap src, int w, int h) {
        try {
            float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
            int sw = Math.round(w / scale);
            int sh = Math.round(h / scale);
            int x = (src.getWidth() - sw) / 2;
            int y = (src.getHeight() - sh) / 2;
            Bitmap region = Bitmap.createBitmap(src, x, y, sw, sh);
            if (region == src) {
                region = Bitmap.createBitmap(src);
            }
            return Bitmap.createScaledBitmap(region, w, h, true);
        } catch (Throwable t) {
            return null;
        }
    }

    private static Bitmap fastBlurFallback(Bitmap src, int radius) {
        try {
            // down/up-scale re-sampling approximates a gaussian for the
            // sample-bitmap purpose (glass reads it at 0.25 scale anyway)
            int ratio = Math.max(2, radius / 6);
            Bitmap small = Bitmap.createScaledBitmap(src,
                    Math.max(1, src.getWidth() / ratio), Math.max(1, src.getHeight() / ratio), true);
            return Bitmap.createScaledBitmap(small, src.getWidth(), src.getHeight(), true);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * v2.4.2: pure-observation hooks for the lockscreen glass chain. ZERO
     * behavior change — every hook only logs, and only while
     * persist.sys.lgr.kdiag=1 (2s TTL, default off). SystemUI process only.
     *
     * One lock/unlock cycle with kdiag=1 answers:
     *   - blurIcon: are the toolbox shortcut buttons blurred, with z
     *     (transparent-mode flag) true, and what do isLiquidGlassType /
     *     isTransparentModeOpen return LIVE (settings say material=2 /
     *     switch=1 — verify at runtime, not just via adb settings);
     *   - blurIconBackground: same for the circle buttons that take the
     *     8-arg path (z3 && z gates the material branch);
     *   - getCurrentBlurBitmapForBg: is the sampling bitmap alive — NULL
     *     means the color-pick wallpaper drawable is NOT a BokehDrawable
     *     (static system wallpaper) and every glass surface has nothing to
     *     refract, which would explain flat buttons AND flat cards at once;
     *   - reInitBlurTextBitmap: static re-init vs realtime-engine feed;
     *   - processState / shouldTakeProvider: what glassType / isWindowBlur /
     *     blurBitmap the keyguard card provider actually produces;
     *   - GlassParamEx ctor + HnMaterialManager.setHnMaterial: which views
     *     actually end up carrying a liquid material.
     */
    private static void hookKeyguardDiagnostics(ClassLoader cl) {
        final Class<?> kbu;
        try {
            kbu = XposedHelpers.findClass("com.hihonor.keyguard.util.KeyguardBlurUtils", cl);
        } catch (Throwable t) {
            log("skip: diag KeyguardBlurUtils missing (" + t.getClass().getSimpleName() + ")");
            return;
        }

        try {
            Class<?> vb = XposedHelpers.findClass("com.hihonor.keyguard.support.ViewBlur", cl);
            XposedBridge.hookAllMethods(vb, "blurIcon", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 3 || !(param.args[0] instanceof View)) {
                        return;
                    }
                    refreshProps();
                    if (!sKeyguardDiag || sDiagIcon >= 80) {
                        return;
                    }
                    sDiagIcon++;
                    try {
                        View v = (View) param.args[0];
                        boolean liquid = false;
                        boolean open = false;
                        try {
                            liquid = (Boolean) XposedHelpers.callStaticMethod(kbu, "isLiquidGlassType",
                                    new Class[]{Context.class}, v.getContext());
                        } catch (Throwable ignored) {
                        }
                        try {
                            open = (Boolean) XposedHelpers.callStaticMethod(kbu, "isTransparentModeOpen");
                        } catch (Throwable ignored) {
                        }
                        log("diag blurIcon: " + v.getClass().getSimpleName()
                                + " z=" + param.args[2]
                                + " liquid=" + liquid + " open=" + open);
                    } catch (Throwable t) {
                        log("diag blurIcon failed: " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(vb, "blurIconBackground", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    if (param.args.length != 8 || !(param.args[1] instanceof View)) {
                        return;
                    }
                    refreshProps();
                    if (!sKeyguardDiag || sDiagIconBg >= 40) {
                        return;
                    }
                    sDiagIconBg++;
                    log("diag blurIconBg: " + ((View) param.args[1]).getClass().getSimpleName()
                            + " bmp=" + (param.args[0] == null ? "null" : "set")
                            + " z=" + param.args[4] + " z2=" + param.args[5] + " z3=" + param.args[6]);
                }
            });
            log("ok: diag ViewBlur installed");
        } catch (Throwable t) {
            log("skip: diag ViewBlur missing (" + t.getClass().getSimpleName() + ")");
        }

        try {
            Class<?> kgw = XposedHelpers.findClass("com.hihonor.keyguard.wallpaper.KeyguardWallpaper", cl);
            XposedBridge.hookAllMethods(kgw, "getCurrentBlurBitmapForBg", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    refreshProps();
                    if (!sKeyguardDiag || sDiagBmp >= 400) {
                        return;
                    }
                    sDiagBmp++;
                    Object r = param.getResult();
                    if (r instanceof Bitmap) {
                        Bitmap b = (Bitmap) r;
                        log("diag blurBmpForBg: " + b.getWidth() + "x" + b.getHeight()
                                + (b.isRecycled() ? " RECYCLED" : ""));
                    } else {
                        log("diag blurBmpForBg: NULL");
                    }
                }
            });
            log("ok: diag KeyguardWallpaper installed");
        } catch (Throwable t) {
            log("skip: diag KeyguardWallpaper missing (" + t.getClass().getSimpleName() + ")");
        }

        try {
            Class<?> bokeh = XposedHelpers.findClass(
                    "com.hihonor.keyguard.view.effect.bokeh.BokehDrawable", cl);
            XposedBridge.hookAllMethods(bokeh, "reInitBlurTextBitmap", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    refreshProps();
                    if (!sKeyguardDiag || sDiagReinit >= 6) {
                        return;
                    }
                    sDiagReinit++;
                    log("diag reInitBlurText: arg="
                            + (param.args.length > 0 && param.args[0] == null
                            ? "null(static-path)" : "bitmap(realtime)"));
                }
            });
            log("ok: diag BokehDrawable installed");
        } catch (Throwable t) {
            log("skip: diag BokehDrawable missing (" + t.getClass().getSimpleName() + ")");
        }

        try {
            Class<?> kgp = XposedHelpers.findClass(
                    "com.android.magic.blur.KeyguardBusinessDataProvider", cl);
            XposedBridge.hookAllMethods(kgp, "processState", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    refreshProps();
                    if (!sKeyguardDiag || sDiagPstate >= 80) {
                        return;
                    }
                    sDiagPstate++;
                    try {
                        Object cfg = param.args[0];
                        Object bmp = XposedHelpers.getObjectField(cfg, "blurBitmap");
                        boolean tmmOn = false;
                        boolean tmmLiquid = false;
                        try {
                            Class<?> tmm = XposedHelpers.findClass(TMM_CLASS, cl);
                            Object inst = XposedHelpers.callStaticMethod(tmm, "getInstance");
                            tmmOn = (Boolean) XposedHelpers.callMethod(inst, "isTransparentModeOn");
                            tmmLiquid = (Boolean) XposedHelpers.callMethod(inst, "isLiquidBlurType");
                        } catch (Throwable ignored) {
                        }
                        log("diag kg-processState: glassType=" + XposedHelpers.getIntField(cfg, "glassType")
                                + " windowBlur=" + XposedHelpers.getBooleanField(cfg, "isWindowBlur")
                                + " noProvider=" + XposedHelpers.getBooleanField(cfg, "isNotSelectProvider")
                                + " ccExpand=" + XposedHelpers.getBooleanField(cfg, "isKerguardContrlCenterExpand")
                                + " blurBmp=" + (bmp == null ? "null" : "set")
                                + " tmmOn=" + tmmOn + " tmmLiquid=" + tmmLiquid);
                    } catch (Throwable t) {
                        log("diag processState failed: " + t);
                    }
                }
            });
            XposedBridge.hookAllMethods(kgp, "shouldTakeProvider", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    refreshProps();
                    if (!sKeyguardDiag || sDiagStp >= 80) {
                        return;
                    }
                    sDiagStp++;
                    log("diag kg-shouldTakeProvider -> " + param.getResult());
                }
            });
            log("ok: diag KeyguardBusinessDataProvider installed");
        } catch (Throwable t) {
            log("skip: diag KeyguardBusinessDataProvider missing (" + t.getClass().getSimpleName() + ")");
        }

        try {
            Class<?> tbv = XposedHelpers.findClass(
                    "com.hihonor.keyguard.view.widget.ToolBoxView", cl);
            XposedBridge.hookAllMethods(tbv, "improveVisibility", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    refreshProps();
                    if (!sKeyguardDiag || sDiagTbv >= 12) {
                        return;
                    }
                    sDiagTbv++;
                    log("diag toolbox improveVisibility");
                }
            });
            log("ok: diag ToolBoxView installed");
        } catch (Throwable t) {
            log("skip: diag ToolBoxView missing (" + t.getClass().getSimpleName() + ")");
        }

        try {
            Class<?> glass = XposedHelpers.findClass(
                    "com.hihonor.android.graphics.material.atomiceffect.GlassParamEx", cl);
            XposedBridge.hookAllConstructors(glass, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    refreshProps();
                    if (!sKeyguardDiag || sDiagGlass >= 200) {
                        return;
                    }
                    sDiagGlass++;
                    StringBuilder sb = new StringBuilder("diag GlassParamEx(");
                    for (int i = 0; i < param.args.length; i++) {
                        if (i > 0) {
                            sb.append(",");
                        }
                        sb.append(param.args[i]);
                    }
                    log(sb.append(")").toString());
                }
            });
            log("ok: diag GlassParamEx installed");
        } catch (Throwable t) {
            log("skip: diag GlassParamEx missing (" + t.getClass().getSimpleName() + ")");
        }

        try {
            Class<?> hmm = XposedHelpers.findClass(
                    "com.hihonor.android.graphics.material.HnMaterialManager", cl);
            XposedBridge.hookAllMethods(hmm, "setHnMaterial", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    refreshProps();
                    if (!sKeyguardDiag || sDiagMat >= 400) {
                        return;
                    }
                    Object target = param.args.length > 0 ? param.args[0] : null;
                    Object mat = param.args.length > 1 ? param.args[1] : null;
                    if (target instanceof View) {
                        sDiagMat++;
                        log("diag setHnMaterial(view=" + ((View) target).getClass().getSimpleName()
                                + " mat=" + (mat == null ? "null" : mat.getClass().getSimpleName()) + ")");
                    } else if (target != null
                            && "android.view.ViewRootImpl".equals(target.getClass().getName())) {
                        sDiagMat++;
                        log("diag setHnMaterial(ViewRootImpl mat="
                                + (mat == null ? "null" : mat.getClass().getSimpleName()) + ")");
                    }
                }
            });
            log("ok: diag HnMaterialManager installed");
        } catch (Throwable t) {
            log("skip: diag HnMaterialManager missing (" + t.getClass().getSimpleName() + ")");
        }
    }
}
