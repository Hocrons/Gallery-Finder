package com.galeriaia.app.ui.files

import android.Manifest
import android.app.Application
import android.content.IntentSender
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galeriaia.app.data.EmbeddingIndex
import com.galeriaia.app.data.EmbeddingStore
import com.galeriaia.app.data.VideoRepository
import com.galeriaia.app.indexing.IndexWorker
import com.galeriaia.app.ml.ClipModel
import com.galeriaia.app.ml.ModelFiles
import com.galeriaia.app.ml.PtEnTranslator
import com.galeriaia.app.ml.QueryText
import com.galeriaia.app.search.QueryParser
import com.galeriaia.app.storage.AppTrash
import com.galeriaia.app.storage.Cleanup
import com.galeriaia.app.storage.CleanupGroup
import com.galeriaia.app.storage.DuplicateFiles
import com.galeriaia.app.storage.FileCategory
import com.galeriaia.app.storage.FileEntry
import com.galeriaia.app.storage.FileScanner
import com.galeriaia.app.storage.ScanResult
import com.galeriaia.app.storage.TrashItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.ZoneId

enum class FilesScreen { HOME, LIST, DUPLICATES, TRASH, VIDEOS }

enum class AgeFilter(val label: String, val days: Int) {
    ANY("Qualquer data", 0),
    M6("Mais de 6 meses", 180),
    Y1("Mais de 1 ano", 365),
}

/** Uma lista de arquivos para revisar e mandar para a lixeira. */
data class FileListUi(
    val key: String,
    val title: String,
    val description: String,
    val items: List<FileEntry>,
    val selected: Set<String>,
    /** Só para "pastas vazias". */
    val dirs: List<String> = emptyList(),
) {
    val selectedSize: Long get() = items.filter { it.path in selected }.sumOf { it.size }
}

data class VideoHit(val id: Long, val uri: Uri, val score: Float, val dateTaken: Long, val durationMs: Long, val size: Long)

data class VideoResult(val query: String, val english: String, val dateLabel: String?, val hits: List<VideoHit>)

data class FilesUi(
    val hasAccess: Boolean = false,
    val hasVideoPermission: Boolean = false,
    val modelsReady: Boolean = false,
    val deviceTotal: Long = 0,
    val deviceFree: Long = 0,
    val scanning: Boolean = false,
    val scanCount: Int = 0,
    val scanned: Boolean = false,
    val scannedBytes: Long = 0,
    val byCategory: List<Triple<FileCategory, Long, Int>> = emptyList(),
    val largeTotal: Long = 0,
    val largeCount: Int = 0,
    val cleanup: List<CleanupGroup> = emptyList(),
    val dupComputing: Boolean = false,
    val dupProgress: Float = 0f,
    val dupGroups: List<List<FileEntry>>? = null,
    val dupSelected: Set<String> = emptySet(),
    val trash: List<TrashItem> = emptyList(),
    val trashSelected: Set<String> = emptySet(),
    val screen: FilesScreen = FilesScreen.HOME,
    val list: FileListUi? = null,
    val largeCategory: FileCategory? = null,
    val largeAge: AgeFilter = AgeFilter.ANY,
    val videoTotal: Int = 0,
    val videoIndexed: Int = 0,
    val videoQuery: String = "",
    val videoSearching: Boolean = false,
    val videoResult: VideoResult? = null,
    val videoThreshold: Float = 0.24f,
    val videoExcluded: Set<Long> = emptySet(),
    val pendingVideoTrash: IntentSender? = null,
    val busy: String? = null,
    val message: String? = null,
) {
    val trashSize: Long get() = trash.sumOf { it.size }
    val dupWaste: Long get() = dupGroups.orEmpty().sumOf { g -> g.drop(1).sumOf { it.size } }
    val dupSelectedSize: Long get() = dupGroups.orEmpty().flatten().filter { it.path in dupSelected }.sumOf { it.size }
    val visibleVideos: List<VideoHit> get() = videoResult?.hits.orEmpty().filter { it.score >= videoThreshold }
    val selectedVideos: List<VideoHit> get() = visibleVideos.filter { it.id !in videoExcluded }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.2f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1e3)
    else -> "$bytes B"
}

class FilesViewModel(app: Application) : AndroidViewModel(app) {

    private val root: File = Environment.getExternalStorageDirectory()
    private val trash = AppTrash(root) { paths ->
        MediaScannerConnection.scanFile(app, paths.toTypedArray(), null, null)
    }
    private val videoRepo = VideoRepository(app)
    private val videoStore = EmbeddingStore.videos(app)
    private val translator = PtEnTranslator()
    private val clip by lazy { ClipModel(ModelFiles.dir(app.filesDir)) }

    private val _state = MutableStateFlow(FilesUi())
    val state: StateFlow<FilesUi> = _state

    private var scan: ScanResult? = null
    private var dupJob: Job? = null
    private var videoIndex: EmbeddingIndex? = null
    private var videoMeta: Map<Long, Pair<Long, Long>> = emptyMap() // id -> (duração, tamanho)

    init {
        refreshAccess()
    }

    // ---------- Permissões e visão geral ----------

    fun refreshAccess() {
        val ctx = getApplication<Application>()
        val access = Environment.isExternalStorageManager()
        val video = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
        val stat = runCatching { StatFs(root.path) }.getOrNull()
        _state.update {
            it.copy(
                hasAccess = access,
                hasVideoPermission = video || access,
                modelsReady = ModelFiles.allPresent(ctx.filesDir),
                deviceTotal = stat?.totalBytes ?: 0,
                deviceFree = stat?.availableBytes ?: 0,
            )
        }
        if (access) viewModelScope.launch(Dispatchers.IO) {
            trash.purgeExpired()
            val items = trash.list()
            _state.update { it.copy(trash = items) }
        }
    }

    fun scan() {
        if (_state.value.scanning || !_state.value.hasAccess) return
        _state.update { it.copy(scanning = true, scanCount = 0) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = FileScanner.scan(root, setOf(trash.dir.path)) { n ->
                    _state.update { it.copy(scanCount = n) }
                }
                scan = result
                recompute()
                _state.update { it.copy(scanning = false, scanned = true, dupGroups = null) }
            } catch (e: Throwable) {
                _state.update { it.copy(scanning = false, message = "Erro ao analisar: ${e.message}") }
            }
        }
    }

    /** Recalcula resumo e sugestões a partir da varredura atual. */
    private fun recompute() {
        val sc = scan ?: return
        val pm = getApplication<Application>().packageManager
        val installedCheck = { e: FileEntry ->
            runCatching {
                val pkg = pm.getPackageArchiveInfo(e.path, 0)?.packageName ?: return@runCatching false
                pm.getPackageInfo(pkg, 0)
                true
            }.getOrDefault(false)
        }
        val byCat = sc.files.groupBy { it.category }
            .map { (c, l) -> Triple(c, l.sumOf { it.size }, l.size) }
            .sortedByDescending { it.second }
        val large = sc.files.filter { it.size >= LARGE_MIN }
        val cleanup = Cleanup.suggest(sc, System.currentTimeMillis(), installedCheck)
        val stat = runCatching { StatFs(root.path) }.getOrNull()
        _state.update {
            it.copy(
                scannedBytes = sc.files.sumOf { f -> f.size },
                byCategory = byCat,
                largeTotal = large.sumOf { f -> f.size },
                largeCount = large.size,
                cleanup = cleanup,
                deviceFree = stat?.availableBytes ?: it.deviceFree,
            )
        }
    }

    // ---------- Listas (grandes / limpeza) ----------

    fun openLarge() {
        _state.update { it.copy(screen = FilesScreen.LIST) }
        rebuildLarge(emptySet())
    }

    fun setLargeCategory(c: FileCategory?) {
        _state.update { it.copy(largeCategory = c) }
        rebuildLarge(_state.value.list?.selected.orEmpty())
    }

    fun setLargeAge(a: AgeFilter) {
        _state.update { it.copy(largeAge = a) }
        rebuildLarge(_state.value.list?.selected.orEmpty())
    }

    private fun rebuildLarge(keepSelected: Set<String>) {
        val sc = scan ?: return
        val st = _state.value
        val now = System.currentTimeMillis()
        val items = sc.files.asSequence()
            .filter { it.size >= LIST_MIN }
            .filter { st.largeCategory == null || it.category == st.largeCategory }
            .filter { st.largeAge.days == 0 || now - it.modified > st.largeAge.days * DAY }
            .sortedByDescending { it.size }
            .take(1000)
            .toList()
        val paths = items.mapTo(HashSet()) { it.path }
        _state.update {
            it.copy(
                list = FileListUi(
                    "large", "Arquivos grandes",
                    "Do maior para o menor (a partir de 10 MB). Marque o que quer mandar para a lixeira.",
                    items, keepSelected.filterTo(HashSet()) { p -> p in paths },
                )
            )
        }
    }

    fun openCleanup(group: CleanupGroup) {
        val preselect = if (group.key == "apk_other") emptySet() else group.items.mapTo(HashSet()) { it.path }
        _state.update {
            it.copy(
                screen = FilesScreen.LIST,
                list = FileListUi(group.key, group.title, group.description, group.items, preselect, group.dirs),
            )
        }
    }

    fun toggle(path: String) = _state.update { st ->
        val l = st.list ?: return@update st
        st.copy(list = l.copy(selected = if (path in l.selected) l.selected - path else l.selected + path))
    }

    fun selectAll() = _state.update { st ->
        val l = st.list ?: return@update st
        st.copy(list = l.copy(selected = l.items.mapTo(HashSet()) { it.path }))
    }

    fun selectNone() = _state.update { st ->
        val l = st.list ?: return@update st
        st.copy(list = l.copy(selected = emptySet()))
    }

    fun trashSelectedFromList() {
        val l = _state.value.list ?: return
        moveToTrash(l.items.filter { it.path in l.selected })
    }

    fun deleteEmptyDirs() {
        val l = _state.value.list ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val n = trash.deleteEmptyDirs(l.dirs)
            scan = scan?.let { ScanResult(it.files, emptyList(), it.root) }
            recompute()
            _state.update { it.copy(screen = FilesScreen.HOME, list = null, message = "$n pastas vazias apagadas.") }
        }
    }

    private fun moveToTrash(files: List<FileEntry>) {
        if (files.isEmpty()) return
        _state.update { it.copy(busy = "Movendo para a lixeira do app…") }
        viewModelScope.launch(Dispatchers.IO) {
            val moved = trash.moveIn(files).toHashSet()
            val size = files.filter { it.path in moved }.sumOf { it.size }
            scan = scan?.let { sc -> ScanResult(sc.files.filter { it.path !in moved }, sc.emptyDirs, sc.root) }
            recompute()
            val items = trash.list()
            _state.update { st ->
                st.copy(
                    busy = null,
                    trash = items,
                    list = st.list?.let { l -> l.copy(items = l.items.filter { it.path !in moved }, selected = l.selected - moved) },
                    dupGroups = st.dupGroups?.map { g -> g.filter { it.path !in moved } }?.filter { it.size > 1 },
                    dupSelected = st.dupSelected - moved,
                    message = "${moved.size} arquivo(s) (${formatSize(size)}) na lixeira do app. " +
                        "Esvazie a lixeira para liberar o espaço.",
                )
            }
        }
    }

    // ---------- Duplicados ----------

    fun findDuplicates() {
        val sc = scan ?: return
        if (dupJob?.isActive == true) return
        _state.update { it.copy(screen = FilesScreen.DUPLICATES, dupComputing = true, dupProgress = 0f, dupGroups = null) }
        dupJob = viewModelScope.launch(Dispatchers.IO) {
            val groups = DuplicateFiles.find(
                sc.files,
                isCancelled = { !isActive },
                onProgress = { d, t -> _state.update { it.copy(dupProgress = if (t == 0) 1f else d.toFloat() / t) } },
            )
            _state.update {
                it.copy(
                    dupComputing = false,
                    dupGroups = groups,
                    dupSelected = groups.flatMap { g -> g.drop(1) }.mapTo(HashSet()) { f -> f.path },
                )
            }
        }
    }

    fun openDuplicates() = _state.update { it.copy(screen = FilesScreen.DUPLICATES) }

    fun toggleDup(path: String) = _state.update {
        it.copy(dupSelected = if (path in it.dupSelected) it.dupSelected - path else it.dupSelected + path)
    }

    fun trashDuplicates() {
        val st = _state.value
        moveToTrash(st.dupGroups.orEmpty().flatten().filter { it.path in st.dupSelected })
    }

    // ---------- Lixeira do app ----------

    fun openTrash() = _state.update { it.copy(screen = FilesScreen.TRASH, trashSelected = emptySet()) }

    fun toggleTrash(name: String) = _state.update {
        it.copy(trashSelected = if (name in it.trashSelected) it.trashSelected - name else it.trashSelected + name)
    }

    fun restoreSelected() {
        val sel = _state.value.trash.filter { it.trashName in _state.value.trashSelected }
        if (sel.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val n = trash.restore(sel)
            val items = trash.list()
            _state.update { it.copy(trash = items, trashSelected = emptySet(), message = "$n arquivo(s) restaurados.") }
            if (scan != null) {
                // os restaurados voltam a aparecer na próxima análise
                _state.update { it.copy(scanned = false) }
            }
        }
    }

    fun emptyTrash() {
        _state.update { it.copy(busy = "Esvaziando a lixeira…") }
        viewModelScope.launch(Dispatchers.IO) {
            val freed = trash.purge()
            val stat = runCatching { StatFs(root.path) }.getOrNull()
            _state.update {
                it.copy(
                    busy = null, trash = emptyList(), trashSelected = emptySet(),
                    deviceFree = stat?.availableBytes ?: it.deviceFree,
                    message = "${formatSize(freed)} liberados ✔",
                )
            }
        }
    }

    // ---------- Vídeos por descrição ----------

    fun openVideos() {
        _state.update { it.copy(screen = FilesScreen.VIDEOS) }
        refreshVideoCounts()
    }

    fun refreshVideoCounts() {
        viewModelScope.launch(Dispatchers.IO) {
            val vids = runCatching { videoRepo.queryVideos() }.getOrDefault(emptyList())
            videoMeta = vids.associate { it.id to (it.durationMs to it.size) }
            val ids = videoStore.allIds()
            _state.update { it.copy(videoTotal = vids.size, videoIndexed = vids.count { v -> v.id in ids }) }
            videoIndex = null
        }
    }

    fun startVideoIndexing() {
        IndexWorker.startNow(getApplication())
        _state.update { it.copy(message = "Analisando vídeos em segundo plano (acompanhe na notificação).") }
    }

    fun setVideoQuery(q: String) = _state.update { it.copy(videoQuery = q) }

    fun searchVideos() {
        val st = _state.value
        if (st.videoSearching) return
        val parsed = QueryParser.parse(st.videoQuery)
        if (parsed.text.isBlank() && parsed.date == null) {
            _state.update { it.copy(message = "Descreva o vídeo ou informe um período.") }
            return
        }
        _state.update { it.copy(videoSearching = true) }
        viewModelScope.launch {
            try {
                val idx = videoIndex ?: withContext(Dispatchers.IO) { videoStore.loadIndex() }.also { videoIndex = it }
                val zone = ZoneId.systemDefault()
                val english: String
                val scores: FloatArray
                if (parsed.text.isBlank()) {
                    english = ""
                    scores = FloatArray(idx.size) { 1f }
                } else {
                    val pt = QueryText.glossary(parsed.text)
                    if (!translator.isReady()) translator.download()
                    english = runCatching { translator.translate(pt) }.getOrDefault(pt)
                    scores = withContext(Dispatchers.Default) { idx.scores(QueryText.embed(clip, english, "a video of")) }
                }
                val hits = ArrayList<VideoHit>()
                for (i in 0 until idx.size) {
                    if (parsed.text.isNotBlank() && scores[i] < 0.15f) continue
                    val date = Instant.ofEpochMilli(idx.dates[i]).atZone(zone).toLocalDate()
                    if (parsed.date != null && !parsed.date.matches(date)) continue
                    val meta = videoMeta[idx.ids[i]] ?: continue
                    hits += VideoHit(idx.ids[i], videoRepo.uriFor(idx.ids[i]), scores[i], idx.dates[i], meta.first, meta.second)
                }
                if (parsed.text.isBlank()) hits.sortByDescending { it.dateTaken } else hits.sortByDescending { it.score }
                val top = hits.firstOrNull()?.score ?: 0f
                _state.update {
                    it.copy(
                        videoSearching = false,
                        videoResult = VideoResult(st.videoQuery, english, parsed.date?.label, hits),
                        videoThreshold = if (parsed.text.isBlank()) 0f else (top - 0.05f).coerceIn(0.20f, 0.30f),
                        videoExcluded = emptySet(),
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(videoSearching = false, message = "Erro na busca: ${e.message}") }
            }
        }
    }

    fun setVideoThreshold(t: Float) = _state.update { it.copy(videoThreshold = t) }

    fun toggleVideo(id: Long) = _state.update {
        it.copy(videoExcluded = if (id in it.videoExcluded) it.videoExcluded - id else it.videoExcluded + id)
    }

    fun selectAllVideos() = _state.update { it.copy(videoExcluded = emptySet()) }

    fun selectNoVideos() = _state.update { st -> st.copy(videoExcluded = st.visibleVideos.mapTo(HashSet()) { it.id }) }

    /** Vídeos vão para a Lixeira da galeria (sistema), como as fotos. */
    fun trashVideos() {
        val sel = _state.value.selectedVideos.take(500)
        if (sel.isEmpty()) return
        val sender = runCatching { videoRepo.trashRequest(sel.map { it.uri }).intentSender }.getOrElse { e ->
            _state.update { it.copy(message = "Não foi possível pedir a confirmação: ${e.message}") }
            return
        }
        pendingVideoIds = sel.map { it.id }
        _state.update { it.copy(pendingVideoTrash = sender) }
    }

    private var pendingVideoIds: List<Long> = emptyList()

    fun onVideoTrashResult(ok: Boolean) {
        val ids = pendingVideoIds.toSet()
        pendingVideoIds = emptyList()
        _state.update { it.copy(pendingVideoTrash = null) }
        if (!ok) return
        viewModelScope.launch(Dispatchers.IO) {
            videoStore.delete(ids)
            videoIndex = null
            val freed = _state.value.videoResult?.hits.orEmpty().filter { it.id in ids }.sumOf { it.size }
            _state.update { st ->
                st.copy(
                    videoResult = st.videoResult?.let { r -> r.copy(hits = r.hits.filter { it.id !in ids }) },
                    videoExcluded = st.videoExcluded - ids,
                    message = "${ids.size} vídeo(s) (${formatSize(freed)}) na Lixeira da galeria (30 dias).",
                )
            }
            refreshVideoCounts()
        }
    }

    // ---------- Navegação ----------

    fun back() = _state.update {
        when (it.screen) {
            FilesScreen.HOME -> it
            else -> it.copy(screen = FilesScreen.HOME)
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    override fun onCleared() {
        translator.close()
        runCatching { clip.close() }
    }

    companion object {
        private const val LARGE_MIN = 50L * 1024 * 1024
        private const val LIST_MIN = 10L * 1024 * 1024
        private const val DAY = 24L * 60 * 60 * 1000
    }
}
