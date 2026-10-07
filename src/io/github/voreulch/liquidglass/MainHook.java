package io.github.voreulch.liquidglass;

import java.lang.reflect.Array;
import java.lang.reflect.Method;

import android.os.SystemClock;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Liquid Glass Restorer for MagicOS. v1.4
 *
 * Root cause: after the OTA, TransparentModeManager caches mBlurType = FROSTED
 * (65536) at SystemUI init; the control center then renders glassType=1
 * (flat frosted) instead of 2 (liquid refraction + highlight).
 *
 * v1.2 lesson: hooking ConfigurationEx.getTransparentMode() never fires on
 * this device because the Vector legacy bridge has no deopt — AOT-compiled
 * call sites inline the tiny getter and bypass the hook entirely.
 *
 * v1.3 strategy (inline-proof, working): hook only big methods that cannot
 * be inlined (getTransparentModeState / initBulrParams) and then rewrite the
 * *fields* the renderers read. Field loads cannot be bypassed by inlining.
 *
 * v1.4 additions:
 *   - uikit degree algorithm scaling: AbsHighGradeDegreeAlgorithm
 *     .withTransparency() -> scale BlurParam.radius + alphas of the control
 *     center widgets (liquid cards / round buttons / sliders)
 *   - live tuning props, re-read every 2s, no restart needed:
 *       persist.sys.lgr.radius  (0.3-2.0, default 1.0) blur radius scale
 *       persist.sys.lgr.mask    (0.0-1.0, default 1.0) mask alpha scale
 *
 * v1.5 addition:
 *   - panel background veil: ShotBlurView.setBlurParams() builds the
 *     HnStaticBlurParamsEx that SurfaceControlEx.screenshot() uses to blur
 *     AND tint the captured wallpaper. The tint (R.color.
 *     window_blur_view_mask_color_transparent = #9e1a1a1a, 62% alpha) is
 *     the dominant opacity lever — scale its alpha by the mask prop.
 *
 * v1.6 addition:
 *   - persist.sys.lgr.sat (0.8-2.0, default 1.0): scale the saturation
 *     param of the panel background (stock 1.2 when transparent mode on).
 *     >1 makes the glass look glossier / more vivid.
 *
 * v1.7 addition (SUPERSEDED by v1.8):
 *   - darkened the notification panel bitmap (setForPanelTwiceBlur index 0)
 *     — WRONG: every glass material reads getPanelBitmap() which prefers
 *     bitmap[0], so one notification pull darkened ALL panels incl. the
 *     control center and killed the liquid look.
 *
 * v1.8:
 *   - nmask reinterpreted as a CARD-level tint: set absolute alpha on
 *     BlurParametersConfig.controlNtfMaskColor / controlNtfverlayColor
 *     (stock alpha=0x00 in transparent mode). turnOnViewBlur() rebuilds
 *     multiControlMaskColors from these fields for every notification
 *     card, so cards get their own readable dark-gray backdrop while the
 *     shared panel bitmap stays untouched — control center stays liquid.
 *
 * v1.9 addition (Apple-style optics):
 *   - persist.sys.lgr.refract (0.5-3.0, default 1.0): scale BPC fields
 *     refraction / depth / thickness / dispersion (stock liquid values
 *     ~0.25 / 0.78 / 0.61 / 0.25) — stronger lens bending + thickness.
 *   - persist.sys.lgr.edge (0.5-3.0, default 1.0): scale the rim-light
 *     band width. Every glass surface (control-center tiles, notification
 *     cards, volume panel) builds its edge light via the same 2-arg
 *     EdgeLightParamEx(angle=250, thickness=6.0f) constructor — hooking
 *     the constructor is inline-proof and covers them all. EDGE_COLOR is
 *     already opaque white (-1), so width is the only lever needed.
 *
 * v2.0 addition (folder-style refraction band):
 *   - v1.9's single refract prop scaled refraction/depth/thickness/
 *     dispersion together; user found the result "glowing border" and
 *     wants the launcher-folder look instead: a WIDE refraction band
 *     (thickness) with a restrained chromatic fringe (dispersion) and a
 *     THIN rim line. Split into independent props:
 *       refract (0.2-3.0): refraction + depth only (lens bending)
 *       thick   (0.2-3.0): thickness only — widens the edge refraction band
 *       disp    (0.0-3.0): dispersion only — chromatic fringe
 *     refract still scales all four when thick/disp unset (back-compat);
 *     set thick/disp to override their share.
 *
 * v2.2 addition:
 *   - persist.sys.lgr.tblur (0.1-2.0, default 1.0): scale the PER-SURFACE
 *     blur radius (BlurParametersConfig.blurRadius / dynamicCardBlurRadius).
 *     Every tile / notification card re-blurs the already-blurred panel
 *     bitmap with this radius — the residual frosted look. Independent of
 *     `radius` (which scales the panel-background blur), so tiles can be
 *     made clearer without sharpening the whole background.
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
    private static final int DEFAULT_DEGREE = 85;
    private static final float DEFAULT_RADIUS = 1.0f;
    private static final float DEFAULT_MASK = 1.0f;
    private static final float DEFAULT_SAT = 1.0f;
    private static final float DEFAULT_NTF_MASK = 0.0f;
    private static final float DEFAULT_REFRACT = 1.0f;
    private static final float DEFAULT_EDGE = 1.0f;
    private static final float DEFAULT_RIM = 1.0f;
    private static final float DEFAULT_TBLUR = 1.0f;
    private static final float UNSET = -1.0f;

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
    private static float sThickScale = UNSET;
    private static float sDispScale = UNSET;
    private static float sRimScale = DEFAULT_RIM;
    private static int sRimCount = 0;
    private static boolean sFolderMode = false;
    private static int sFolderLogged = 0;
    private static float sTileBlurScale = DEFAULT_TBLUR;
    private static int sTileBlurLogged = 0;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        ClassLoader cl = lpparam.classLoader;
        log("v2.2 loaded in process: " + lpparam.packageName);

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
        sThickScale = readOptFloatProp(THICK_PROP, 0.2f, 3.0f);
        sDispScale = readOptFloatProp(DISP_PROP, 0.0f, 3.0f);
        sRimScale = readFloatProp(RIM_PROP, DEFAULT_RIM, 0.15f, 1.0f);
        sFolderMode = readFloatProp(FOLDER_PROP, 0.0f, 0.0f, 1.0f) > 0.5f;
        sTileBlurScale = readFloatProp(TBLUR_PROP, DEFAULT_TBLUR, 0.1f, 2.0f);
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
                    if (Math.abs(sRefractScale - 1.0f) > 0.001f || sThickScale > 0 || sDispScale >= 0) {
                        try {
                            float tScale = sThickScale > 0 ? sThickScale : sRefractScale;
                            float dScale = sDispScale >= 0 ? sDispScale : sRefractScale;
                            scaleFloatField(o, "refraction", sRefractScale);
                            scaleFloatField(o, "depth", sRefractScale);
                            scaleFloatField(o, "thickness", tScale);
                            scaleFloatField(o, "dispersion", dScale);
                            if (sRefractLogged < 3) {
                                sRefractLogged++;
                                log("refract: x" + sRefractScale + " thick x" + tScale
                                        + " disp x" + dScale
                                        + " (refraction=" + XposedHelpers.getFloatField(o, "refraction")
                                        + " thickness=" + XposedHelpers.getFloatField(o, "thickness")
                                        + " dispersion=" + XposedHelpers.getFloatField(o, "dispersion") + ")");
                            }
                        } catch (Throwable t) {
                            log("refract scale failed: " + t);
                        }
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
                            float tMul = sThickScale > 0 ? sThickScale : 1.0f;
                            XposedHelpers.setFloatField(o, "refraction", 0.3f);
                            XposedHelpers.setFloatField(o, "depth", 0.54f);
                            XposedHelpers.setFloatField(o, "thickness", 0.62f * tMul);
                            XposedHelpers.setFloatField(o, "dispersion", 0.2f);
                            if (sFolderLogged < 3) {
                                sFolderLogged++;
                                log("folder: liquid optics -> 0.3/0.54/"
                                        + (0.62f * tMul) + "/0.2 (case="
                                        + param.args[0] + ", thick x" + tMul + ")");
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

    /** returns UNSET (-1) when the prop is not set at all */
    private static float readOptFloatProp(String name, float min, float max) {
        try {
            Method m = Class.forName("android.os.SystemProperties")
                    .getMethod("get", String.class, String.class);
            String v = (String) m.invoke(null, name, "");
            if (v == null || v.isEmpty()) {
                return UNSET;
            }
            float f = Float.parseFloat(v);
            return Math.max(min, Math.min(max, f));
        } catch (Throwable t) {
            return UNSET;
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
                    if (Math.abs(sEdgeScale - 1.0f) <= 0.001f) {
                        return;
                    }
                    float orig = (Float) param.args[1];
                    // some callers re-construct from an already-scaled param
                    // (copy pattern) — only scale the stock constant to
                    // avoid compounding (6.0 -> 7.8 -> 10.1 observed)
                    if (Math.abs(orig - 6.0f) > 0.01f) {
                        return;
                    }
                    param.args[1] = orig * sEdgeScale;
                    if (sEdgeCount < 3) {
                        sEdgeCount++;
                        log("edge-rim: thickness " + orig + " -> " + (orig * sEdgeScale));
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
}
