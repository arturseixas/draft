package app.applelogcam

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.InputStream

/** LUT 3D no formato .cube (Resolve/Adobe). R varia mais rápido, depois G, depois B. */
class CubeLut(
    val title: String,
    val size: Int,
    val data: FloatArray,
    val domainMin: Float,
    val domainMax: Float
) {
    companion object {
        private val WS = Regex("\\s+")

        fun parse(input: InputStream): CubeLut {
            var title = ""
            var size = 0
            var dmin = 0f
            var dmax = 1f
            var data: FloatArray? = null
            var n = 0
            input.bufferedReader().useLines { lines ->
                for (raw in lines) {
                    val line = raw.trim()
                    if (line.isEmpty() || line.startsWith("#")) continue
                    val first = line[0]
                    val parts = line.split(WS)
                    if (first.isDigit() || first == '-' || first == '.' || first == '+') {
                        val d = data ?: throw IllegalArgumentException("Valores antes de LUT_3D_SIZE")
                        if (parts.size < 3) continue
                        if (n + 3 > d.size) throw IllegalArgumentException("A LUT tem mais pontos do que declara")
                        d[n++] = parts[0].toFloat()
                        d[n++] = parts[1].toFloat()
                        d[n++] = parts[2].toFloat()
                    } else {
                        when (parts[0].uppercase()) {
                            "TITLE" -> title = line.substring(5).trim().trim('"')
                            "LUT_3D_SIZE" -> {
                                size = parts.getOrNull(1)?.toIntOrNull() ?: 0
                                if (size !in 2..129) throw IllegalArgumentException("LUT_3D_SIZE inválido: $size")
                                data = FloatArray(size * size * size * 3)
                            }
                            "LUT_1D_SIZE" -> throw IllegalArgumentException("Só LUTs 3D são aceitas (o arquivo é 1D)")
                            "DOMAIN_MIN" -> dmin = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                            "DOMAIN_MAX" -> dmax = parts.getOrNull(1)?.toFloatOrNull() ?: 1f
                            "LUT_3D_INPUT_RANGE" -> {
                                dmin = parts.getOrNull(1)?.toFloatOrNull() ?: 0f
                                dmax = parts.getOrNull(2)?.toFloatOrNull() ?: 1f
                            }
                        }
                    }
                }
            }
            val d = data ?: throw IllegalArgumentException("Arquivo sem LUT_3D_SIZE (não parece um .cube 3D)")
            if (n != d.size) throw IllegalArgumentException("A LUT tem ${n / 3} pontos, esperava ${d.size / 3}")
            if (dmax <= dmin) throw IllegalArgumentException("DOMAIN_MIN/MAX inválidos")
            return CubeLut(title, size, d, dmin, dmax)
        }
    }
}

/** LUTs importadas ficam copiadas em files/luts dentro do app. */
class LutStore(context: Context) {
    private val dir = File(context.filesDir, "luts").apply { mkdirs() }
    private val resolver = context.contentResolver

    fun list(): List<String> =
        dir.listFiles()?.filter { it.isFile && it.name.endsWith(".cube", ignoreCase = true) }
            ?.map { it.name.substringBeforeLast('.') }
            ?.sortedBy { it.lowercase() }
            .orEmpty()

    private fun file(name: String) = File(dir, "$name.cube")

    fun load(name: String): CubeLut = file(name).inputStream().use { CubeLut.parse(it) }

    fun delete(name: String) {
        file(name).delete()
    }

    /** Copia o arquivo escolhido para o app depois de validar. Devolve o nome salvo. */
    fun import(uri: Uri): String {
        val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
            ?: throw IllegalArgumentException("Não consegui ler o arquivo")
        CubeLut.parse(bytes.inputStream())
        val display = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
        val base = (display?.substringBeforeLast('.') ?: "LUT")
            .replace(Regex("[^A-Za-z0-9 _\\-.()]"), "_")
            .take(60)
            .ifBlank { "LUT" }
        var name = base
        var i = 2
        while (file(name).exists()) name = "$base (${i++})"
        file(name).writeBytes(bytes)
        return name
    }
}
