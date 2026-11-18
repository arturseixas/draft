package app.applelogcam

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.util.LruCache
import android.util.Size
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.TextView
import android.widget.VideoView
import java.util.Locale
import java.util.concurrent.Executors

class Clip(
    val id: Long,
    val uri: Uri,
    val name: String,
    val folder: String,
    val durationMs: Long,
    val size: Long,
    val width: Int,
    val height: Int
)

/**
 * Galeria dos clipes em Movies/AppleLogCam. Cada subpasta é uma pasta do app; "Sem pasta" é a raiz.
 * Pastas vazias ficam guardadas nas preferências (o MediaStore não lista pasta sem arquivo).
 */
class GalleryPanel(
    private val activity: Activity,
    private val settings: CamSettings,
    private val flash: (String) -> Unit,
    private val onChanged: () -> Unit
) : FrameLayout(activity) {

    companion object {
        const val BASE = "Movies/AppleLogCam/"
        const val REQ_WRITE = 41
        const val REQ_DELETE = 42
        private const val ALL = "\u0000all"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }

    private val resolver = activity.contentResolver
    private val ui = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(2)
    private val cache = LruCache<Long, Bitmap>(90)

    private var clips: List<Clip> = emptyList()
    private var shown: List<Clip> = emptyList()
    private var filter = ALL
    private val selected = LinkedHashSet<Long>()
    private var pendingAfterGrant: (() -> Unit)? = null
    private var playing: Clip? = null

    private val countLabel: TextView
    private val folderStrip: LinearLayout
    private val grid: GridView
    private val empty: TextView
    private val actionBar: LinearLayout
    private val selLabel: TextView
    private val player: FrameLayout
    private val videoView: VideoView
    private val playerTitle: TextView
    private val clipAdapter = ClipAdapter()

    private fun tv(s: String, sizeSp: Float, color: Int, tf: Typeface = Ui.COND, spacing: Float = 0.1f) =
        TextView(context).apply {
            text = s
            textSize = sizeSp
            setTextColor(color)
            typeface = tf
            letterSpacing = spacing
            includeFontPadding = false
        }

    private fun outline(radiusDp: Float, stroke: Int = Ui.FAINT, fill: Int = 0) = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = context.dp(radiusDp)
        setStroke(context.dpi(1), stroke)
    }

    private fun button(s: String, color: Int = Ui.CREAM, action: () -> Unit) =
        tv(s, 11f, color, Ui.COND, 0.16f).apply {
            gravity = Gravity.CENTER
            background = outline(6f)
            setPadding(context.dpi(12), context.dpi(8), context.dpi(12), context.dpi(8))
            setOnClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                action()
            }
        }

    init {
        setBackgroundColor(0xF70B0B0C.toInt())
        isClickable = true
        visibility = GONE
        val d = context

        val col = LinearLayout(d).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(d).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d.dpi(20), d.dpi(12), d.dpi(14), d.dpi(6))
        }
        header.addView(tv("GALERIA", 18f, Ui.CREAM, Ui.COND, 0.3f))
        countLabel = tv("", 11f, Ui.DIM, Ui.MONO, 0.04f)
        header.addView(countLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = d.dpi(14) })
        header.addView(View(d), LinearLayout.LayoutParams(0, 1, 1f))
        header.addView(tv("✕", 20f, Ui.CREAM, Ui.COND, 0f).apply {
            setPadding(d.dpi(14), d.dpi(6), d.dpi(6), d.dpi(6))
            setOnClickListener { back() }
        })
        col.addView(header)

        val hs = HorizontalScrollView(d).apply { isHorizontalScrollBarEnabled = false }
        folderStrip = LinearLayout(d).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(d.dpi(16), d.dpi(4), d.dpi(16), d.dpi(12))
        }
        hs.addView(folderStrip)
        col.addView(hs)

        val body = FrameLayout(d)
        grid = GridView(d).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = d.dpi(168)
            horizontalSpacing = d.dpi(10)
            verticalSpacing = d.dpi(16)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            setPadding(d.dpi(16), d.dpi(2), d.dpi(16), d.dpi(20))
            clipToPadding = false
            selector = ColorDrawable(0)
            isVerticalScrollBarEnabled = false
        }
        grid.adapter = clipAdapter
        grid.setOnItemClickListener { _, _, pos, _ -> onTap(shown[pos]) }
        grid.setOnItemLongClickListener { v, _, pos, _ ->
            v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            toggleSelect(shown[pos])
            true
        }
        body.addView(grid, FrameLayout.LayoutParams(MATCH, MATCH))
        empty = tv("Nenhum vídeo nesta pasta", 13f, Ui.DIM, Ui.COND_REG, 0.02f).apply { gravity = Gravity.CENTER }
        body.addView(empty, FrameLayout.LayoutParams(MATCH, MATCH))
        col.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))

        actionBar = LinearLayout(d).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF151413.toInt())
            setPadding(d.dpi(16), d.dpi(10), d.dpi(12), d.dpi(10))
            visibility = GONE
        }
        selLabel = tv("", 12f, Ui.AMBER, Ui.COND, 0.16f)
        actionBar.addView(selLabel, LinearLayout.LayoutParams(0, WRAP, 1f))
        for ((label, action) in listOf<Pair<String, () -> Unit>>(
            "MOVER" to { moveSelected() },
            "COMPARTILHAR" to { share(selectedClips().map { it.uri }) },
            "APAGAR" to { deleteSelected() },
            "CANCELAR" to { clearSelection() }
        )) {
            actionBar.addView(
                button(label, if (label == "APAGAR") Ui.RED else Ui.CREAM, action),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = d.dpi(8) }
            )
        }
        col.addView(actionBar, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(col, LayoutParams(MATCH, MATCH))

        // ---- player
        player = FrameLayout(d).apply {
            setBackgroundColor(Color.BLACK)
            visibility = GONE
            isClickable = true
        }
        videoView = VideoView(d)
        val controller = MediaController(d)
        controller.setAnchorView(videoView)
        videoView.setMediaController(controller)
        videoView.setOnPreparedListener { videoView.start() }
        videoView.setOnErrorListener { _, _, _ ->
            flash("NÃO CONSEGUI TOCAR ESSE VÍDEO AQUI")
            true
        }
        player.addView(videoView, LayoutParams(MATCH, MATCH, Gravity.CENTER))
        val ptop = LinearLayout(d).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(d.dpi(18), d.dpi(10), d.dpi(12), d.dpi(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xCC000000.toInt(), 0)
            )
        }
        playerTitle = tv("", 13f, Ui.CREAM, Ui.MONO, 0.02f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        ptop.addView(playerTitle, LinearLayout.LayoutParams(0, WRAP, 1f))
        ptop.addView(button("ABRIR FORA") { playing?.let { openExternal(it.uri) } },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = d.dpi(8) })
        ptop.addView(button("COMPARTILHAR") { playing?.let { share(listOf(it.uri)) } },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = d.dpi(8) })
        ptop.addView(tv("✕", 20f, Ui.CREAM, Ui.COND, 0f).apply {
            setPadding(d.dpi(14), d.dpi(6), d.dpi(6), d.dpi(6))
            setOnClickListener { closePlayer() }
        })
        player.addView(ptop, LayoutParams(MATCH, WRAP, Gravity.TOP))
        addView(player, LayoutParams(MATCH, MATCH))
    }

    // ================================================================ abrir / fechar

    val isOpen get() = visibility == VISIBLE

    fun open(folder: String? = null) {
        if (folder != null) filter = folder
        visibility = VISIBLE
        reload()
    }

    /** Volta um nível: player -> seleção -> fecha. */
    fun back() {
        when {
            player.visibility == VISIBLE -> closePlayer()
            selected.isNotEmpty() -> clearSelection()
            else -> {
                visibility = GONE
                onChanged()
            }
        }
    }

    fun reload() {
        io.execute {
            val list = query()
            ui.post {
                clips = list
                val ids = list.map { it.id }.toSet()
                selected.retainAll(ids)
                if (filter != ALL && filter.isNotEmpty() && filter !in allFolders()) filter = ALL
                rebuildFolders()
                applyFilter()
            }
        }
    }

    private fun query(): List<Clip> {
        val out = ArrayList<Clip>()
        val proj = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.RELATIVE_PATH,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT
        )
        try {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, proj,
                "${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?", arrayOf("$BASE%"),
                "${MediaStore.Video.Media.DATE_ADDED} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val rel = c.getString(2) ?: BASE
                    val folder = rel.removePrefix(BASE).trimEnd('/').substringBefore('/')
                    out += Clip(
                        id,
                        ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id),
                        c.getString(1) ?: "",
                        folder,
                        c.getLong(3),
                        c.getLong(4),
                        c.getInt(5),
                        c.getInt(6)
                    )
                }
            }
        } catch (t: Throwable) {
            Log.w(LogRenderer.TAG, "galeria: ${t.message}")
        }
        return out
    }

    // ================================================================ pastas

    private fun allFolders(): List<String> =
        (settings.folders + clips.map { it.folder }.filter { it.isNotEmpty() }).toSortedSet(String.CASE_INSENSITIVE_ORDER).toList()

    private fun folderLabel(f: String) = if (f.isEmpty()) "SEM PASTA" else f

    private fun rebuildFolders() {
        folderStrip.removeAllViews()
        chip("TUDO", ALL, clips.size)
        chip("SEM PASTA", "", clips.count { it.folder.isEmpty() })
        for (f in allFolders()) chip(f, f, clips.count { it.folder == f })
        val add = tv("+ PASTA", 11f, Ui.AMBER, Ui.COND, 0.16f).apply {
            gravity = Gravity.CENTER
            background = outline(16f, Ui.AMBER)
            setPadding(context.dpi(14), context.dpi(8), context.dpi(14), context.dpi(8))
            setOnClickListener { newFolderDialog(null) }
        }
        folderStrip.addView(add, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = context.dpi(6) })
    }

    private fun chip(label: String, key: String, count: Int) {
        val isRec = key != ALL && key == settings.recordFolder
        val sel = key == filter
        val t = tv(if (isRec) "● $label  $count" else "$label  $count", 11f, if (sel) Ui.AMBER else Ui.CREAM, Ui.COND, 0.12f).apply {
            gravity = Gravity.CENTER
            background = outline(16f, if (sel) Ui.AMBER else if (isRec) 0x99E5322D.toInt() else Ui.FAINT, if (sel) 0x22FFB000 else 0)
            setPadding(context.dpi(14), context.dpi(8), context.dpi(14), context.dpi(8))
            setOnClickListener {
                filter = key
                rebuildFolders()
                applyFilter()
            }
            if (key != ALL) setOnLongClickListener {
                it.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                folderMenu(key)
                true
            }
        }
        folderStrip.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = context.dpi(6) })
    }

    private fun dialog() = AlertDialog.Builder(context, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    private fun sanitize(s: String) = s.trim().replace(Regex("[\\\\/:*?\"<>|]"), "").take(40)

    private fun nameInput(prefill: String): Pair<View, EditText> {
        val input = EditText(context).apply {
            setText(prefill)
            setSelection(prefill.length)
            hint = "Nome da pasta"
            setSingleLine()
        }
        val wrap = FrameLayout(context).apply {
            setPadding(context.dpi(22), context.dpi(8), context.dpi(22), 0)
            addView(input)
        }
        return wrap to input
    }

    private fun newFolderDialog(then: ((String) -> Unit)?) {
        val (view, input) = nameInput("")
        dialog().setTitle("Nova pasta").setView(view)
            .setPositiveButton("CRIAR") { _, _ ->
                val name = sanitize(input.text.toString())
                if (name.isEmpty()) {
                    flash("NOME INVÁLIDO")
                    return@setPositiveButton
                }
                settings.folders = settings.folders + name
                settings.save()
                rebuildFolders()
                then?.invoke(name)
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun folderMenu(f: String) {
        val opts = if (f.isEmpty()) arrayOf("Gravar novos clipes aqui")
        else arrayOf("Gravar novos clipes aqui", "Renomear", "Apagar pasta")
        dialog().setTitle(folderLabel(f)).setItems(opts) { _, which ->
            when (which) {
                0 -> {
                    settings.recordFolder = f
                    settings.save()
                    rebuildFolders()
                    onChanged()
                    flash("GRAVANDO EM ${folderLabel(f)}")
                }
                1 -> renameFolder(f)
                2 -> deleteFolder(f)
            }
        }.show()
    }

    private fun renameFolder(f: String) {
        val (view, input) = nameInput(f)
        dialog().setTitle("Renomear pasta").setView(view)
            .setPositiveButton("SALVAR") { _, _ ->
                val n = sanitize(input.text.toString())
                if (n.isEmpty() || n == f) return@setPositiveButton
                settings.folders = settings.folders - f + n
                if (settings.recordFolder == f) settings.recordFolder = n
                settings.save()
                filter = n
                moveTo(clips.filter { it.folder == f }, n, quiet = true)
                onChanged()
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun deleteFolder(f: String) {
        dialog().setTitle("Apagar a pasta \"$f\"?")
            .setMessage("Os vídeos dela voltam para Sem pasta. Nenhum vídeo é apagado.")
            .setPositiveButton("APAGAR PASTA") { _, _ ->
                settings.folders = settings.folders - f
                if (settings.recordFolder == f) settings.recordFolder = ""
                settings.save()
                filter = ALL
                moveTo(clips.filter { it.folder == f }, "", quiet = true)
                onChanged()
            }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    // ================================================================ lista

    private fun applyFilter() {
        shown = if (filter == ALL) clips else clips.filter { it.folder == filter }
        clipAdapter.notifyDataSetChanged()
        empty.visibility = if (shown.isEmpty()) VISIBLE else GONE
        val total = shown.sumOf { it.size }
        countLabel.text = "${shown.size} CLIPES · ${sizeLabel(total)}"
        refreshActionBar()
    }

    private fun onTap(c: Clip) {
        if (selected.isNotEmpty()) toggleSelect(c) else play(c)
    }

    private fun toggleSelect(c: Clip) {
        if (!selected.add(c.id)) selected.remove(c.id)
        clipAdapter.notifyDataSetChanged()
        refreshActionBar()
    }

    private fun clearSelection() {
        selected.clear()
        clipAdapter.notifyDataSetChanged()
        refreshActionBar()
    }

    private fun refreshActionBar() {
        actionBar.visibility = if (selected.isEmpty()) GONE else VISIBLE
        selLabel.text = "${selected.size} SELECIONADO" + if (selected.size == 1) "" else "S"
    }

    private fun selectedClips() = clips.filter { it.id in selected }

    // ================================================================ ações

    private fun moveSelected() {
        val list = selectedClips()
        if (list.isEmpty()) return
        val targets = listOf("") + allFolders()
        val labels = targets.map { if (it.isEmpty()) "Sem pasta" else it } + "+ Nova pasta"
        dialog().setTitle("Mover ${list.size} clipe(s) para").setItems(labels.toTypedArray()) { _, which ->
            if (which == targets.size) newFolderDialog { f -> moveTo(list, f, quiet = false) }
            else moveTo(list, targets[which], quiet = false)
        }.show()
    }

    private fun moveTo(list: List<Clip>, folder: String, quiet: Boolean, retry: Boolean = true) {
        if (list.isEmpty()) {
            reload()
            return
        }
        io.execute {
            val failed = ArrayList<Clip>()
            for (c in list) {
                try {
                    val v = ContentValues().apply {
                        put(MediaStore.Video.Media.RELATIVE_PATH, BASE + if (folder.isEmpty()) "" else "$folder/")
                    }
                    resolver.update(c.uri, v, null, null)
                } catch (t: Throwable) {
                    failed += c
                }
            }
            ui.post {
                clearSelection()
                if (failed.isNotEmpty() && retry) {
                    // Clipes de uma instalação anterior do app: o Android pede confirmação para mexer neles.
                    requestWrite(failed) { moveTo(failed, folder, quiet, retry = false) }
                } else if (failed.isNotEmpty()) {
                    flash("${failed.size} CLIPE(S) NÃO PUDERAM SER MOVIDOS")
                } else if (!quiet) {
                    flash("MOVIDO PARA ${folderLabel(folder)}")
                }
                reload()
            }
        }
    }

    private fun requestWrite(list: List<Clip>, then: () -> Unit) {
        try {
            val pi = MediaStore.createWriteRequest(resolver, list.map { it.uri })
            pendingAfterGrant = then
            activity.startIntentSenderForResult(pi.intentSender, REQ_WRITE, null, 0, 0, 0)
        } catch (t: Throwable) {
            flash("SEM PERMISSÃO PARA MOVER")
        }
    }

    private fun deleteSelected() {
        val list = selectedClips()
        if (list.isEmpty()) return
        dialog().setTitle("Apagar ${list.size} clipe(s)?")
            .setMessage("Os arquivos saem do celular.")
            .setPositiveButton("APAGAR") { _, _ -> delete(list) }
            .setNegativeButton("CANCELAR", null)
            .show()
    }

    private fun delete(list: List<Clip>) {
        io.execute {
            val failed = ArrayList<Clip>()
            for (c in list) {
                try {
                    resolver.delete(c.uri, null, null)
                    cache.remove(c.id)
                } catch (t: Throwable) {
                    failed += c
                }
            }
            ui.post {
                clearSelection()
                if (failed.isNotEmpty()) {
                    try {
                        val pi = MediaStore.createDeleteRequest(resolver, failed.map { it.uri })
                        activity.startIntentSenderForResult(pi.intentSender, REQ_DELETE, null, 0, 0, 0)
                    } catch (t: Throwable) {
                        flash("SEM PERMISSÃO PARA APAGAR")
                    }
                } else {
                    flash("${list.size} CLIPE(S) APAGADO(S)")
                }
                reload()
                onChanged()
            }
        }
    }

    fun onActivityResult(requestCode: Int, resultCode: Int) {
        when (requestCode) {
            REQ_WRITE -> {
                val then = pendingAfterGrant
                pendingAfterGrant = null
                if (resultCode == Activity.RESULT_OK) then?.invoke()
            }
            REQ_DELETE -> {
                reload()
                onChanged()
            }
        }
    }

    private fun share(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        i.type = "video/mp4"
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            activity.startActivity(Intent.createChooser(i, "Compartilhar"))
        } catch (_: Throwable) {
            flash("NENHUM APP PARA COMPARTILHAR")
        }
    }

    private fun openExternal(uri: Uri) {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (_: Throwable) {
            flash("NENHUM APP PARA ABRIR O VÍDEO")
        }
    }

    // ================================================================ player

    private fun play(c: Clip) {
        playing = c
        playerTitle.text = c.name.removeSuffix(".mp4") + "   " + resLabel(c) + " · " + durLabel(c.durationMs)
        player.visibility = VISIBLE
        videoView.setVideoURI(c.uri)
    }

    private fun closePlayer() {
        try { videoView.stopPlayback() } catch (_: Throwable) {}
        player.visibility = GONE
        playing = null
    }

    // ================================================================ formatação

    private fun durLabel(ms: Long): String {
        val s = ms / 1000
        return if (s >= 3600) String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
        else String.format(Locale.US, "%d:%02d", s / 60, s % 60)
    }

    private fun sizeLabel(b: Long): String = when {
        b >= 1L shl 30 -> String.format(Locale.US, "%.1f GB", b / 1073741824.0)
        b >= 1L shl 20 -> "${b shr 20} MB"
        else -> "${b shr 10} KB"
    }

    private fun resLabel(c: Clip): String {
        val long = maxOf(c.width, c.height)
        val res = when {
            long >= 3800 -> "4K"
            long >= 1900 -> "HD"
            long > 0 -> "${minOf(c.width, c.height)}p"
            else -> "—"
        }
        return if (c.height > c.width) "$res 9:16" else res
    }

    // ================================================================ grade

    private inner class ClipAdapter : BaseAdapter() {
        override fun getCount() = shown.size
        override fun getItem(position: Int): Any = shown[position]
        override fun getItemId(position: Int) = shown[position].id
        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val cell = (convertView as? ClipCell) ?: ClipCell(context)
            val c = shown[position]
            cell.bind(c, c.id in selected)
            return cell
        }
    }

    private inner class ClipCell(ctx: Context) : LinearLayout(ctx) {
        private val image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0xFF1A1918.toInt())
        }
        private val dur = tv("", 10f, Ui.CREAM, Ui.MONO_BOLD, 0.02f).apply {
            setBackgroundColor(0xB3000000.toInt())
            setPadding(ctx.dpi(5), ctx.dpi(2), ctx.dpi(5), ctx.dpi(2))
        }
        private val check = tv("✓", 13f, Ui.BG, Ui.COND, 0f).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { setColor(Ui.AMBER); cornerRadius = ctx.dp(12f) }
        }
        private val frame = FrameLayout(ctx).apply {
            clipToOutline = true
            background = GradientDrawable().apply { setColor(0xFF1A1918.toInt()); cornerRadius = ctx.dp(8f) }
        }
        private val name = tv("", 11f, Ui.CREAM, Ui.MONO, 0.02f).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        private val meta = tv("", 9.5f, Ui.DIM, Ui.MONO, 0.02f)
        private var boundId = -1L

        init {
            orientation = VERTICAL
            frame.addView(image, FrameLayout.LayoutParams(MATCH, MATCH))
            frame.addView(dur, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, ctx.dpi(6), ctx.dpi(6))
            })
            frame.addView(check, FrameLayout.LayoutParams(ctx.dpi(24), ctx.dpi(24), Gravity.TOP or Gravity.END).apply {
                setMargins(0, ctx.dpi(6), ctx.dpi(6), 0)
            })
            addView(frame, LayoutParams(MATCH, ctx.dpi(96)))
            addView(name, LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dpi(7) })
            addView(meta, LayoutParams(MATCH, WRAP).apply { topMargin = ctx.dpi(3) })
        }

        fun bind(c: Clip, sel: Boolean) {
            boundId = c.id
            name.text = c.name.removeSuffix(".mp4")
            val where = if (filter == ALL && c.folder.isNotEmpty()) " · ${c.folder}" else ""
            meta.text = "${resLabel(c)} · ${sizeLabel(c.size)}$where"
            dur.text = durLabel(c.durationMs)
            check.visibility = if (sel) VISIBLE else GONE
            frame.foreground = if (sel) outline(8f, Ui.AMBER).apply { setStroke(context.dpi(2), Ui.AMBER) } else null
            val cached = cache.get(c.id)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                image.setImageDrawable(null)
                io.execute {
                    val b = try { resolver.loadThumbnail(c.uri, Size(384, 216), null) } catch (_: Throwable) { null }
                    if (b != null) {
                        cache.put(c.id, b)
                        ui.post { if (boundId == c.id) image.setImageBitmap(b) }
                    }
                }
            }
        }
    }
}
