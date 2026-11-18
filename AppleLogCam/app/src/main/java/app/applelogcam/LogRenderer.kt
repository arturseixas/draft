package app.applelogcam

import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * Lê o frame HLG10 / BT.2020 da câmera e escreve:
 *   MODE_APPLE_LOG: HLG -> luz de cena linear -> curva Apple Log (primárias Rec.2020, sem troca de gamut)
 *   MODE_MONITOR_709: visualização SDR só para a tela (nunca vai para o arquivo)
 */
class LogRenderer {

    companion object {
        const val TAG = "AppleLogCam"
        const val MODE_APPLE_LOG = 0
        const val MODE_MONITOR_709 = 1

        private const val VERTEX = """#version 300 es
in vec4 aPos;
in vec2 aTex;
uniform mat4 uTexMatrix;
out vec2 vTex;
void main() {
    gl_Position = aPos;
    vTex = (uTexMatrix * vec4(aTex, 0.0, 1.0)).xy;
}
"""

        // Variante A: lê YUV cru (EXT_YUV_target) e converte com a matriz BT.2020 aqui, sem depender do driver.
        private const val FRAG_HEAD_YUV = """#version 300 es
#extension GL_EXT_YUV_target : require
precision highp float;
uniform __samplerExternal2DY2YEXT uTex;
uniform int uFullRange;
in vec2 vTex;
vec3 readHlgBt2020() {
    // P010 guarda 10 bits nos bits altos de 16: devolve o código de 0 a 1023.
    vec3 c = texture(uTex, vTex).xyz * 1023.984;
    float y; float cb; float cr;
    if (uFullRange == 1) {
        y = c.x / 1023.0; cb = c.y / 1023.0 - 0.5; cr = c.z / 1023.0 - 0.5;
    } else {
        y = (c.x - 64.0) / 876.0; cb = (c.y - 512.0) / 896.0; cr = (c.z - 512.0) / 896.0;
    }
    return vec3(y + 1.4746 * cr,
                y - 0.164553 * cb - 0.571353 * cr,
                y + 1.8814 * cb);
}
"""

        // Variante B (fallback): o driver já devolve RGB (ainda codificado em HLG).
        private const val FRAG_HEAD_OES = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision highp float;
uniform samplerExternalOES uTex;
uniform int uFullRange;
in vec2 vTex;
vec3 readHlgBt2020() {
    return texture(uTex, vTex).rgb;
}
"""

        private const val FRAG_BODY = """
uniform int uMode;
uniform float uGain;
out vec4 fragColor;

// BT.2100 HLG: sinal -> luz de cena (0..1)
const float HLG_A = 0.17883277;
const float HLG_B = 0.28466892;
const float HLG_C = 0.55991073;
// Luz de cena do sinal HLG 0.75 (branco difuso, BT.2408). Vira reflectância 1.0 no Apple Log.
const float HLG_REF_WHITE = 0.26496256;

float hlgToScene(float s) {
    s = clamp(s, 0.0, 1.0);
    return (s <= 0.5) ? (s * s / 3.0) : ((exp((s - HLG_C) / HLG_A) + HLG_B) / 12.0);
}

// Apple Log Profile white paper: reflectância linear R -> sinal P
const float AL_R0 = -0.05641088;
const float AL_RT = 0.01;
const float AL_C = 47.28711236;
const float AL_BETA = 0.00964052;
const float AL_GAMMA = 0.08550479;
const float AL_DELTA = 0.69336945;

float appleLog(float r) {
    if (r >= AL_RT) return AL_GAMMA * log2(r + AL_BETA) + AL_DELTA;
    if (r >= AL_R0) { float d = r - AL_R0; return AL_C * d * d; }
    return 0.0;
}

float srgbOetf(float l) {
    return (l <= 0.0031308) ? 12.92 * l : 1.055 * pow(l, 1.0 / 2.4) - 0.055;
}

// BT.2020 -> BT.709 em luz linear (BT.2087). mat3 é column-major.
const mat3 M2020_TO_709 = mat3(
     1.6605, -0.1246, -0.0182,
    -0.5876,  1.1329, -0.1006,
    -0.0728, -0.0083,  1.1187);

void main() {
    vec3 sig = readHlgBt2020();
    vec3 scene = vec3(hlgToScene(sig.r), hlgToScene(sig.g), hlgToScene(sig.b));
    vec3 refl = scene / HLG_REF_WHITE * uGain;   // 1.0 = branco difuso, ~0.18 = cinza médio

    if (uMode == 0) {
        fragColor = vec4(appleLog(refl.r), appleLog(refl.g), appleLog(refl.b), 1.0);
    } else {
        vec3 lin = clamp(M2020_TO_709 * clamp(refl, 0.0, 1.0), 0.0, 1.0);
        fragColor = vec4(srgbOetf(lin.r), srgbOetf(lin.g), srgbOetf(lin.b), 1.0);
    }
}
"""
    }

    val texId: Int
    var usingYuvTarget = false
        private set

    private var program = 0
    private var locPos = 0
    private var locTex = 0
    private var locTexMatrix = 0
    private var locMode = 0
    private var locGain = 0
    private var locFullRange = 0
    private var locSampler = 0

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
        Log.i(TAG, "Renderer: ${if (usingYuvTarget) "EXT_YUV_target (YUV cru + matriz BT.2020)" else "samplerExternalOES"}")
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
        locTexMatrix = GLES20.glGetUniformLocation(p, "uTexMatrix")
        locMode = GLES20.glGetUniformLocation(p, "uMode")
        locGain = GLES20.glGetUniformLocation(p, "uGain")
        locFullRange = GLES20.glGetUniformLocation(p, "uFullRange")
        locSampler = GLES20.glGetUniformLocation(p, "uTex")
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

    fun draw(
        texMatrix: FloatArray, mode: Int, gain: Float, fullRange: Boolean,
        vx: Int, vy: Int, vw: Int, vh: Int
    ) {
        GLES20.glViewport(vx, vy, vw, vh)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glUniform1i(locSampler, 0)
        GLES20.glUniformMatrix4fv(locTexMatrix, 1, false, texMatrix, 0)
        GLES20.glUniform1i(locMode, mode)
        GLES20.glUniform1f(locGain, gain)
        GLES20.glUniform1i(locFullRange, if (fullRange) 1 else 0)

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
