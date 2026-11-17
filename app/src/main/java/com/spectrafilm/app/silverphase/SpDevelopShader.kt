package com.spectrafilm.app.silverphase

import com.spectrafilm.app.editor.*

// Silverphase "develop" reveal, ported line for line from
//   sources/silverphase-v1.2.5/ios/Silverphase/Shaders/MobileView.metal (mobileDevelopFragment)
//   sources/silverphase-v1.2.5/modern_UI/Spektrafilm/Spektrafilm/Canvas/Shaders.metal
//   (developPaper, developmentRise, developmentPulse, developmentSigmoid, cctf mode 1 = ROMM).
// Durations: paper 2.2 s, scan 0.35 s, linear progress 0→1 (MobileDevelopment.swift:29-31).
// Difference from iOS (documented, presentation only, never exported): iOS feeds ROMM-encoded
// textures; Android preview bitmaps are sRGB, so the shader converts sRGB → linear ROMM (D50,
// inverse of the Bradford matrix in lockedCameraDevelopFragment) → ROMM-encoded before
// developPaper and back afterwards. Requires API 33 (RuntimeShader); below that and under
// "remove animations" the caller shows the final print directly, as iOS does under Reduce Motion.

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.provider.Settings
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.platform.LocalContext

object SpDevelop {
    const val PAPER_SECONDS = 2.2
    const val SCAN_SECONDS = 0.35

    const val AGSL = """
uniform shader printed;
uniform shader negative;
uniform float2 size;
uniform float progress;
uniform float scanStyle;

float sdec(float v) { return v <= 0.04045 ? v / 12.92 : pow((v + 0.055) / 1.055, 2.4); }
float senc(float v) { return v <= 0.0031308 ? 12.92 * v : 1.055 * pow(max(v, 0.0), 1.0 / 2.4) - 0.055; }
float rdec(float v) { return v < 16.0 / 512.0 ? v / 16.0 : pow(max(v, 0.0), 1.8); }
float renc(float v) { return v < 1.0 / 512.0 ? v * 16.0 : pow(max(v, 0.0), 1.0 / 1.8); }

half3 srgbToRomm(half3 c) {
    float3 l = float3(sdec(c.r), sdec(c.g), sdec(c.b));
    float3 r = float3(dot(l, float3(0.529346, 0.330073, 0.140582)),
                      dot(l, float3(0.098375, 0.873461, 0.028165)),
                      dot(l, float3(0.016883, 0.117673, 0.865444)));
    return half3(renc(r.r), renc(r.g), renc(r.b));
}
half3 rommToSrgb(float3 c) {
    float3 l = float3(rdec(c.r), rdec(c.g), rdec(c.b));
    float3 s = float3(dot(l, float3(2.034076, -0.727334, -0.306743)),
                      dot(l, float3(-0.228814, 1.231731, -0.002917)),
                      dot(l, float3(-0.008570, -0.153287, 1.161857)));
    return half3(senc(s.r), senc(s.g), senc(s.b));
}

float developmentSigmoid(float t, float center, float slope) {
    float start = 1.0 / (1.0 + exp(center * slope));
    float end = 1.0 / (1.0 + exp(-(1.0 - center) * slope));
    float value = 1.0 / (1.0 + exp(-(t - center) * slope));
    return saturate((value - start) / (end - start));
}
float developmentRise(float t, float center, float slope, float tail) {
    float rise = developmentSigmoid(t, center, slope);
    return rise + (1.0 - rise) * pow(t, tail);
}
float developmentPulse(float t, float on, float onSlope, float off, float offSlope, float decay) {
    return developmentSigmoid(t, on, onSlope) * (1.0 - developmentSigmoid(t, off, offSlope)) * pow(1.0 - t, decay);
}

float3 developPaper(float3 printed, float t) {
    float3 linear = float3(rdec(printed.r), rdec(printed.g), rdec(printed.b));
    float3 density = clamp(-log(max(linear, float3(0.0001))), 0.0, 9.21034);
    float neutral = min(density.r, min(density.g, density.b));
    float3 chroma = density - neutral;
    float3 latent = saturate(density / 6.0);
    float3 chromaLatent = saturate(chroma / 6.0);
    float shadow = saturate(dot(density, float3(0.34, 0.39, 0.27)) / 1.75);
    float highlight = 1.0 - shadow;
    float strength = max(chromaLatent.r, max(chromaLatent.g, chromaLatent.b));
    float warmth = saturate((chroma.g + 1.25 * chroma.b - 0.85 * chroma.r)
                            / (chroma.r + chroma.g + chroma.b + 0.0001));
    float body = developmentRise(t, 0.19, 7.4, 3.8);
    float3 result = neutral * body * developmentRise(t, 0.12, 8.0, 2.8) * float3(1.0);
    result += chroma * body * float3(developmentRise(t, 0.52, 9.4, 5.4),
                                     developmentRise(t, 0.31, 10.2, 3.4),
                                     developmentRise(t, 0.22, 11.0, 3.0));
    float3 receiver = (0.30 + 0.70 * highlight) * float3(0.04, 0.08, 0.15);
    float silverMap = saturate(0.24 + 0.46 * shadow + 0.30 * saturate(neutral / 6.0));
    float3 silver = clamp(silverMap * float3(0.18, 0.19, 0.20)
                       + chromaLatent.gbr * float3(0.03, 0.02, 0.04), 0.0, 0.28);
    float3 anti = (highlight * float3(0.10, 0.06, 0.02)
                + (1.0 - latent.brg) * float3(0.13, 0.07, 0.18)
                + chromaLatent.gbr * float3(0.04, 0.02, 0.06))
                * (0.40 + 0.60 * highlight);
    float3 warm = (0.24 + 0.42 * highlight + 0.34 * max(warmth, strength))
                 * float3(0.02, 0.08, 0.19);
    result += receiver * 1.08 * pow(1.0 - t, 0.46);
    result += silver * developmentPulse(t, 0.05, 32.0, 0.44, 8.0, 0.24);
    result += anti * 1.22 * developmentPulse(t, 0.10, 54.0, 0.28, 28.0, 0.18);
    result += warm * 0.96 * developmentPulse(t, 0.12, 20.0, 0.80, 6.5, 0.34);
    result += highlight * float3(0.03, 0.06, 0.11) * 0.68
            * developmentPulse(t, 0.03, 24.0, 0.98, 4.0, 0.08);
    float3 developed = exp(-result);
    float3 encoded = float3(renc(developed.r), renc(developed.g), renc(developed.b));
    return mix(encoded, printed, smoothstep(0.84, 1.0, t));
}

half4 main(float2 xy) {
    half3 film = negative.eval(xy).rgb;
    half3 paper = printed.eval(xy).rgb;
    if (progress <= 0.0) return half4(film, 1.0);
    if (progress >= 1.0) return half4(paper, 1.0);
    if (scanStyle > 0.5) return half4(mix(film, paper, half(smoothstep(0.0, 1.0, progress))), 1.0);
    float t = saturate((progress - 0.10) / 0.90);
    half3 dev = rommToSrgb(developPaper(float3(srgbToRomm(paper)), t));
    return half4(mix(film, dev, half(smoothstep(0.0, 0.10, progress))), 1.0);
}
"""
}

/**
 * Plays the develop reveal once over [print] (and [negative], same size), then calls [finished].
 * Under system "remove animations" or below API 33 it calls [finished] immediately.
 */
@Composable
fun SpDevelopReveal(print: Bitmap, negative: Bitmap, scanStyle: Boolean, modifier: Modifier = Modifier, finished: () -> Unit) {
    val animationsOff by rememberSpReduceMotion()
    if (Build.VERSION.SDK_INT < 33 || animationsOff || print.isRecycled || negative.isRecycled) {
        LaunchedEffect(print) { finished() }
        return
    }
    SpDevelopReveal33(print, negative, scanStyle, modifier, finished)
}

@RequiresApi(33)
@Composable
private fun SpDevelopReveal33(print: Bitmap, negative: Bitmap, scanStyle: Boolean, modifier: Modifier, finished: () -> Unit) {
    val progress = remember(print) { Animatable(0f) }
    // A driver that rejects the AGSL (IllegalArgumentException at compile) must not crash the
    // editor: fall back to showing the print at once, as under Reduce Motion.
    val shader = remember(print, negative) {
        runCatching {
            RuntimeShader(SpDevelop.AGSL).apply {
                setInputShader("printed", BitmapShader(print, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                setInputShader("negative", BitmapShader(negative, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                setFloatUniform("scanStyle", if (scanStyle) 1f else 0f)
            }
        }.getOrNull()
    }
    if (shader == null) {
        LaunchedEffect(print) { finished() }
        return
    }
    LaunchedEffect(print) {
        val seconds = if (scanStyle) SpDevelop.SCAN_SECONDS else SpDevelop.PAPER_SECONDS
        progress.animateTo(1f, tween((seconds * 1000).toInt(), easing = LinearEasing))
        finished()
    }
    Canvas(modifier.fillMaxSize()) {
        val sx = size.width / print.width
        val sy = size.height / print.height
        shader.setFloatUniform("size", print.width.toFloat(), print.height.toFloat())
        shader.setFloatUniform("progress", progress.value)
        drawContext.canvas.save()
        drawContext.canvas.scale(sx, sy)
        drawRect(ShaderBrush(shader), size = androidx.compose.ui.geometry.Size(print.width.toFloat(), print.height.toFloat()))
        drawContext.canvas.restore()
    }
}
