package app.applelogcam

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Shader único para tudo:
 *  entrada  HLG10 / BT.2020 (10 bits) ou SDR / BT.709-601 (8 bits) -> reflectância linear BT.2020
 *  saída    Apple Log | HLG | Rec.709 (com rolloff suave nos altos)
 *  monitor  zebra, focus peaking, false color e lupa (só no preview, nunca no arquivo)
 */
class LogRenderer {

    class Input {
        var hlg = true
        var tenBit = true
        var fullRange = true
        var kr = 0.2627f
        var kb = 0.0593f
        var texelX = 1f / 3840f
        var texelY = 1f / 2160f
    }

    class Params {
        var out = OUT_APPLE_LOG
        var tools = false
        var zebra = -1f
        var peaking = false
        var falseColor = false
        var mag = 1f
    }

    companion object {
        const val TAG = "AppleLogCam"
        const val OUT_APPLE_LOG = 0
        const val OUT_HLG = 1
        const val OUT_709 = 2

        fun outFor(p: Profile): Int = when (p) {
            Profile.APPLE_LOG -> OUT_APPLE_LOG
            Profile.HLG -> OUT_HLG
            Profile.REC709 -> OUT_709
        }

        private const val VERTEX = """#version 300 es
in vec4 aPos;
in vec2 aTex;
uniform mat4 uTexMatrix;
uniform float uMag;
uniform vec2 uMagCenter;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vec2 t = uMagCenter + (aTex - uMagCenter) / uMag;
    vTex = (uTexMatrix * vec4(t, 0.0, 1.0)).xy;
}
"""

        // Variante A: YUV cru via EXT_YUV_target; a matriz (BT.2020/709/601) e a faixa são aplicadas aqui.
        private const val FRAG_HEAD_YUV = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTex;
uniform int uFullRange;
uniform float uCodeMax;
uniform float uKr;
uniform float uKb;
in vec2 vTex;
vec3 readSignal(vec2 tc) {
    vec3 c = texture(uTex, tc).xyz * uCodeMax;
    bool ten = uCodeMax > 300.0;
    float k = ten ? 4.0 : 1.0;
    float m = ten ? 1023.0 : 255.0;
    float y; float cb; float cr;
    if (uFullRange == 1) {
        y = c.x / m; cb = c.y / m - 0.5; cr = c.z / m - 0.5;
    } else {
        y = (c.x - 16.0 * k) / (219.0 * k);
        cb = (c.y - 128.0 * k) / (224.0 * k);
        cr = (c.z - 128.0 * k) / (224.0 * k);
    }
    float r = y + 2.0 * (1.0 - uKr) * cr;
    float b = y + 2.0 * (1.0 - uKb) * cb;
    float g = (y - uKr * r - uKb * b) / (1.0 - uKr - uKb);
    return vec3(r, g, b);
}
float readLuma(vec2 tc) {
    return texture(uTex, tc).x;
}
"""

        // Variante B (fallback): o driver devolve RGB, ainda no sinal de origem (HLG ou SDR).
        private const val FRAG_HEAD_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform float uKr;
uniform float uKb;
in vec2 vTex;
vec3 readSignal(vec2 tc) {
    return texture(uTex, tc).rgb;
}
float readLuma(vec2 tc) {
    return dot(texture(uTex, tc).rgb, vec3(uKr, 1.0 - uKr - uKb, uKb));
}
"""

        private const val FRAG_BODY = """
uniform int uOut;
uniform int uInputHlg;
uniform int uTools;
uniform float uZebra;
uniform int uPeaking;
uniform int uFalseColor;
uniform vec2 uTexel;
uniform float uClipStop;
out vec4 fragColor;

const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;
// Luz de cena do sinal HLG 0.75 (branco difuso, BT.2408) = reflectância 1.0
const float HLG_REF_WHITE = 0.26496256;

const float AL_R0 = -0.05641088;
const float AL_RT = 0.01;
const float AL_C = 47.28711236;
const float AL_BETA = 0.00964052;
const float AL_GAMMA = 0.08550479;
const float AL_DELTA = 0.69336945;

// mat3 é column-major
const mat3 M709_TO_2020 = mat3(
    0.6274, 0.0691, 0.0164,
    0.3293, 0.9195, 0.0880,
    0.0433, 0.0114, 0.8956);
const mat3 M2020_TO_709 = mat3(
     1.6605, -0.1246, -0.0182,
    -0.5876,  1.1329, -0.1006,
    -0.0728, -0.0083,  1.1187);

float hlgInv(float s) {
    s = clamp(s, 0.0, 1.0);
    return (s <= 0.5) ? (s * s / 3.0) : ((exp((s - HLG_C) / HLG_A) + HLG_B) / 12.0);
}
float hlgOetf(float e) {
    e = max(e, 0.0);
    return (e <= 1.0 / 12.0) ? sqrt(3.0 * e) : HLG_A * log(12.0 * e - HLG_B) + HLG_C;
}
float inv709(float v) {
    v = clamp(v, 0.0, 1.0);
    return (v < 0.081) ? v / 4.5 : pow((v + 0.099) / 1.099, 1.0 / 0.45);
}
float oetf709(float l) {
    l = clamp(l, 0.0, 1.0);
    return (l < 0.018) ? 4.5 * l : 1.099 * pow(l, 0.45) - 0.099;
}
float appleLog(float r) {
    if (r >= AL_RT) return AL_GAMMA * log2(r + AL_BETA) + AL_DELTA;
    if (r >= AL_R0) { float d = r - AL_R0; return AL_C * d * d; }
    return 0.0;
}
// Ombro suave: linear até 0.8, depois comprime os altos até 1.0 sem clipar seco.
float shoulder(float x) {
    x = max(x, 0.0);
    return (x < 0.8) ? x : 0.8 + 0.2 * (1.0 - exp(-(x - 0.8) / 0.2));
}

vec3 sceneLinear(vec3 sig) {
    if (uInputHlg == 1) return vec3(hlgInv(sig.r), hlgInv(sig.g), hlgInv(sig.b)) / HLG_REF_WHITE;
    return M709_TO_2020 * vec3(inv709(sig.r), inv709(sig.g), inv709(sig.b));
}
vec3 view709(vec3 refl) {
    vec3 l = M2020_TO_709 * refl;
    return vec3(oetf709(shoulder(l.r)), oetf709(shoulder(l.g)), oetf709(shoulder(l.b)));
}
vec3 encode(vec3 refl, int mode) {
    if (mode == 0) return vec3(appleLog(refl.r), appleLog(refl.g), appleLog(refl.b));
    if (mode == 1) {
        vec3 e = refl * HLG_REF_WHITE;
        return vec3(hlgOetf(e.r), hlgOetf(e.g), hlgOetf(e.b));
    }
    return view709(refl);
}
vec3 falseColor(vec3 refl, vec3 disp) {
    float y = dot(refl, vec3(0.2627, 0.6780, 0.0593));
    float s = log2(max(y, 0.00001) / 0.18);
    if (s >= uClipStop - 0.25) return vec3(1.0, 0.12, 0.1);
    if (s >= 2.2) return vec3(1.0, 0.85, 0.1);
    if (s >= 0.8 && s < 1.4) return vec3(1.0, 0.55, 0.7);
    if (s >= -0.3 && s < 0.3) return vec3(0.2, 0.85, 0.3);
    if (s < -5.5) return vec3(0.45, 0.1, 0.7);
    if (s < -4.0) return vec3(0.15, 0.35, 1.0);
    float g = dot(disp, vec3(0.2126, 0.7152, 0.0722));
    return vec3(g);
}

void main() {
    vec3 refl = sceneLinear(readSignal(vTex));
    vec3 col = encode(refl, uOut);
    if (uTools == 1) {
        vec3 v = view709(refl);
        if (uFalseColor == 1) col = falseColor(refl, v);
        if (uZebra > 0.0 && max(v.r, max(v.g, v.b)) >= uZebra) {
            float stripe = mod(gl_FragCoord.x + gl_FragCoord.y, 18.0);
            if (stripe < 7.0) col = mix(col, vec3(1.0), 0.75);
            else if (stripe < 9.0) col = mix(col, vec3(0.0), 0.5);
        }
        if (uPeaking == 1) {
            float lx = readLuma(vTex + vec2(uTexel.x, 0.0)) - readLuma(vTex - vec2(uTexel.x, 0.0));
            float ly = readLuma(vTex + vec2(0.0, uTexel.y)) - readLuma(vTex - vec2(0.0, uTexel.y));
            float g = abs(lx) + abs(ly);
            if (g > 0.09) col = mix(col, vec3(1.0, 0.27, 0.12), clamp((g - 0.09) * 12.0, 0.0, 0.9));
        }
    }
    fragColor = vec4(col, 1.0);
}
"""

        private val UNIFORMS = listOf(
            "uTexMatrix", "uTex", "uMag", "uMagCenter", "uFullRange", "uCodeMax", "uKr", "uKb",
            "uOut", "uInputHlg", "uTools", "uZebra", "uPeaking", "uFalseColor", "uTexel", "uClipStop"
        )
    }

    val texId: Int
    var usingYuvTarget = false
        private set

    private var program = 0
    private var locPos = 0
    private var locTex = 0
    private val loc = HashMap<String, Int>()

    private val posBuf: FloatBuffer = floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f))
    private val texBuf: FloatBuffer = floatBuffer(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))

    init {
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        texId = ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS) ?: ""
        var built = false
        if (extensions.contains("GL_EXT_YUV_target")) {
            try {
                build(FRAG_HEAD_YUV)
                usingYuvTarget = true
                built = true
            } catch (t: Throwable) {
                Log.w(TAG, "Shader EXT_YUV_target falhou, usando samplerExternalOES: ${t.message}")
            }
        }
        if (!built) build(FRAG_HEAD_OES)
        Log.i(TAG, "Renderer: ${if (usingYuvTarget) "EXT_YUV_target" else "samplerExternalOES"}")
    }

    private fun build(fragHead: String) {
        val vs = compile(GLES20.GL_VERTEX_SHADER, VERTEX)
        val fs = compile(GLES20.GL_FRAGMENT_SHADER, fragHead + FRAG_BODY)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, vs)
        GLES20.glAttachShader(p, fs)
        GLES20.glLinkProgram(p)
        val status = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(p)
            GLES20.glDeleteProgram(p)
            throw RuntimeException("Link do shader falhou: $log")
        }
        GLES20.glDeleteShader(vs)
        GLES20.glDeleteShader(fs)
        if (program != 0) GLES20.glDeleteProgram(program)
        program = p
        locPos = GLES20.glGetAttribLocation(p, "aPos")
        locTex = GLES20.glGetAttribLocation(p, "aTex")
        loc.clear()
        for (name in UNIFORMS) loc[name] = GLES20.glGetUniformLocation(p, name)
    }

    private fun compile(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val status = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(s)
            GLES20.glDeleteShader(s)
            throw RuntimeException("Compilação do shader falhou: $log")
        }
        return s
    }

    private fun u(name: String) = loc[name] ?: -1

    fun draw(texMatrix: FloatArray, input: Input, p: Params, vx: Int, vy: Int, vw: Int, vh: Int) {
        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(u("uTex"), 0)
        GLES20.glUniformMatrix4fv(u("uTexMatrix"), 1, false, texMatrix, 0)
        GLES20.glUniform1f(u("uMag"), p.mag)
        GLES20.glUniform2f(u("uMagCenter"), 0.5f, 0.5f)
        GLES20.glUniform1i(u("uFullRange"), if (input.fullRange) 1 else 0)
        GLES20.glUniform1f(u("uCodeMax"), if (input.tenBit) 1023.984f else 255f)
        GLES20.glUniform1f(u("uKr"), input.kr)
        GLES20.glUniform1f(u("uKb"), input.kb)
        GLES20.glUniform1i(u("uOut"), p.out)
        GLES20.glUniform1i(u("uInputHlg"), if (input.hlg) 1 else 0)
        GLES20.glUniform1i(u("uTools"), if (p.tools) 1 else 0)
        GLES20.glUniform1f(u("uZebra"), p.zebra)
        GLES20.glUniform1i(u("uPeaking"), if (p.peaking) 1 else 0)
        GLES20.glUniform1i(u("uFalseColor"), if (p.falseColor) 1 else 0)
        GLES20.glUniform2f(u("uTexel"), input.texelX, input.texelY)
        // Stops acima do cinza 18% onde o sinal clipa: HLG10 chega a R ≈ 3.77, SDR a 1.0
        GLES20.glUniform1f(u("uClipStop"), if (input.hlg) 4.39f else 2.47f)

        GLES20.glEnableVertexAttribArray(locPos)
        GLES20.glVertexAttribPointer(locPos, 2, GLES20.GL_FLOAT, false, 8, posBuf)
        GLES20.glEnableVertexAttribArray(locTex)
        GLES20.glVertexAttribPointer(locTex, 2, GLES20.GL_FLOAT, false, 8, texBuf)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(locPos)
        GLES20.glDisableVertexAttribArray(locTex)
    }

    fun release() {
        if (program != 0) GLES20.glDeleteProgram(program)
        GLES20.glDeleteTextures(1, intArrayOf(texId), 0)
        program = 0
    }

    private fun floatBuffer(a: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
            put(a); position(0)
        }
}
