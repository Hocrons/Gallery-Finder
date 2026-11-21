package com.galeriaia.app.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.galeriaia.app.data.Album
import com.galeriaia.app.data.EmbeddingIndex
import com.galeriaia.app.data.EmbeddingStore
import com.galeriaia.app.data.MediaImage
import com.galeriaia.app.data.MediaRepository
import com.galeriaia.app.ml.ClipModel
import com.galeriaia.app.ml.ModelDownloader
import com.galeriaia.app.ml.ModelFiles
import com.galeriaia.app.ml.PtEnTranslator
import com.galeriaia.app.search.DateFilter
import com.galeriaia.app.search.QueryParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

enum class Screen { HOME, RESULTS }

data class Hit(val id: Long, val uri: Uri, val score: Float, val dateTaken: Long)

data class SearchResult(
    val originalQuery: String,
    /** Texto que foi para a IA (já em inglês). Vazio = busca só por data. */
    val englishText: String,
    val dateLabel: String?,
    val semantic: Boolean,
    val hits: List<Hit>,
    val suggestedThreshold: Float,
    val partialIndex: Boolean,
)

data class DownloadState(val done: Long, val total: Long, val file: String, val error: String? = null)

data class IndexProgress(val done: Int, val total: Int, val startedAt: Long)

sealed interface ActionKind {
    data object Trash : ActionKind
    data class Move(val relativePath: String) : ActionKind
}

/** Pedido de confirmação do sistema que a tela precisa abrir. */
data class PendingRequest(val sender: IntentSender, val kind: ActionKind, val chunk: List<Hit>)

data class UiState(
    val permission: PermissionState = PermissionState.UNKNOWN,
    val modelsReady: Boolean? = null,
    val download: DownloadState? = null,
    val totalImages: Int = 0,
    val indexedImages: Int = 0,
    val indexProgress: IndexProgress? = null,
    val query: String = "",
    val manualRange: DateFilter? = null,
    val searching: Boolean = false,
    val screen: Screen = Screen.HOME,
    val result: SearchResult? = null,
    val threshold: Float = 0.25f,
    /** Fotos que o usuário DESMARCOU (o resto do que está visível fica selecionado). */
    val excluded: Set<Long> = emptySet(),
    val albums: List<Album> = emptyList(),
    val pending: PendingRequest? = null,
    val busy: String? = null,
    val message: String? = null,
) {
    val visibleHits: List<Hit>
        get() {
            val r = result ?: return emptyList()
            return if (r.semantic) r.hits.filter { it.score >= threshold } else r.hits
        }
    val selectedHits: List<Hit> get() = visibleHits.filter { it.id !in excluded }
}

enum class PermissionState { UNKNOWN, DENIED, PARTIAL, GRANTED }

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val media = MediaRepository(app)
    private val store = EmbeddingStore(app)
    private val translator = PtEnTranslator()
    private val clip by lazy { ClipModel(ModelFiles.dir(app.filesDir)) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var images: List<MediaImage> = emptyList()
    private var index: EmbeddingIndex? = null
    private var indexJob: Job? = null
    private var downloadJob: Job? = null
    private var remainingChunks: ArrayDeque<List<Hit>> = ArrayDeque()
    private var actionDoneCount = 0

    init {
        checkModels()
    }

    // ---------- Permissão e modelos ----------

    fun onPermission(state: PermissionState) {
        _state.update { it.copy(permission = state) }
        if (state == PermissionState.GRANTED || state == PermissionState.PARTIAL) refreshGallery()
    }

    private fun checkModels() = viewModelScope.launch {
        val files = withContext(Dispatchers.IO) { ModelFiles.allPresent(getApplication<Application>().filesDir) }
        val tr = translator.isReady()
        _state.update { it.copy(modelsReady = files && tr) }
    }

    fun downloadModels() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _state.update { it.copy(download = DownloadState(0, ModelFiles.totalBytes, "")) }
            try {
                withContext(Dispatchers.IO) {
                    ModelDownloader.downloadMissing(getApplication<Application>().filesDir) { done, total, file ->
                        _state.update { it.copy(download = DownloadState(done, total, file)) }
                    }
                }
                _state.update { it.copy(download = it.download?.copy(file = "tradutor PT→EN")) }
                translator.download()
                _state.update { it.copy(download = null, modelsReady = true) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(download = it.download?.copy(error = e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    // ---------- Galeria e indexação ----------

    fun refreshGallery() = viewModelScope.launch {
        if (indexJob?.isActive == true) return@launch
        withContext(Dispatchers.IO) {
            images = media.queryImages()
            val current = images.mapTo(HashSet()) { it.id }
            val indexed = store.allIds()
            val removed = indexed.filter { it !in current }
            if (removed.isNotEmpty()) {
                store.delete(removed)
                index = null
            }
            val indexedNow = indexed.size - removed.size
            _state.update {
                it.copy(totalImages = images.size, indexedImages = indexedNow, albums = media.albums(images))
            }
        }
    }

    fun startIndexing() {
        if (indexJob?.isActive == true || _state.value.modelsReady != true) return
        indexJob = viewModelScope.launch(Dispatchers.Default) {
            val indexed = withContext(Dispatchers.IO) { store.allIds() }
            val todo = images.filter { it.id !in indexed }
            if (todo.isEmpty()) {
                _state.update { it.copy(message = "Tudo indexado ✔") }
                return@launch
            }
            val started = System.currentTimeMillis()
            _state.update { it.copy(indexProgress = IndexProgress(0, todo.size, started)) }
            var done = 0
            try {
                for (chunk in todo.chunked(16)) {
                    ensureActive()
                    // Decodifica as miniaturas em paralelo; a IA roda uma por vez.
                    val prepared = chunk.map { img ->
                        async(Dispatchers.IO) {
                            runCatching {
                                val bmp = media.loadThumbnail(img.uri)
                                try { clip.preprocess(bmp) } finally { bmp.recycle() }
                            }.getOrNull()
                        }
                    }.awaitAll()
                    val entries = chunk.mapIndexed { i, img ->
                        // Se a foto não abrir (corrompida etc.), salva vetor zerado para não tentar de novo.
                        val vec = prepared[i]?.let { runCatching { clip.embedImage(it) }.getOrNull() }
                            ?: FloatArray(ClipModel.EMBED_DIM)
                        EmbeddingStore.Entry(img.id, img.dateTaken, vec)
                    }
                    withContext(Dispatchers.IO) { store.insert(entries) }
                    done += chunk.size
                    _state.update {
                        it.copy(
                            indexProgress = IndexProgress(done, todo.size, started),
                            indexedImages = indexed.size + done,
                        )
                    }
                }
                _state.update { it.copy(message = "Indexação concluída: $done fotos") }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _state.update { it.copy(message = "Indexação pausada ($done salvas). Continue quando quiser.") }
                throw e
            } catch (e: Throwable) {
                _state.update { it.copy(message = "Erro na indexação: ${e.message}") }
            } finally {
                index = null
                _state.update { it.copy(indexProgress = null) }
            }
        }
    }

    fun cancelIndexing() {
        indexJob?.cancel()
    }

    // ---------- Busca ----------

    fun setQuery(q: String) = _state.update { it.copy(query = q) }

    fun setManualRange(start: LocalDate?, end: LocalDate?) = _state.update {
        if (start == null) it.copy(manualRange = null)
        else {
            val e = end ?: start
            val fmt = { d: LocalDate -> "%02d/%02d/%d".format(d.dayOfMonth, d.monthValue, d.year) }
            it.copy(manualRange = DateFilter(start, e, label = if (start == e) fmt(start) else "${fmt(start)} – ${fmt(e)}"))
        }
    }

    fun search() {
        val s = _state.value
        if (s.searching) return
        val parsed = QueryParser.parse(s.query)
        val dateFilter = s.manualRange ?: parsed.date
        if (parsed.text.isBlank() && dateFilter == null) {
            _state.update { it.copy(message = "Descreva a foto ou informe um período.") }
            return
        }
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            try {
                val zone = ZoneId.systemDefault()
                fun toDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

                val result = if (parsed.text.isBlank()) {
                    // Só data: não precisa de IA, usa a galeria inteira.
                    val hits = images.asSequence()
                        .filter { dateFilter!!.matches(toDate(it.dateTaken)) }
                        .sortedByDescending { it.dateTaken }
                        .map { Hit(it.id, it.uri, 1f, it.dateTaken) }
                        .toList()
                    SearchResult(s.query, "", dateFilter?.label, false, hits, 0f, false)
                } else {
                    val ptText = applyGlossary(parsed.text)
                    val english = runCatching { translator.translate(ptText) }.getOrDefault(ptText)
                    val hits = withContext(Dispatchers.Default) {
                        val idx = index ?: withContext(Dispatchers.IO) { store.loadIndex() }.also { index = it }
                        val q = textQuery(english)
                        val scores = idx.scores(q)
                        val list = ArrayList<Hit>()
                        for (i in 0 until idx.size) {
                            if (scores[i] < MIN_SCORE) continue
                            if (dateFilter != null && !dateFilter.matches(toDate(idx.dates[i]))) continue
                            list += Hit(idx.ids[i], media.uriFor(idx.ids[i]), scores[i], idx.dates[i])
                        }
                        list.sortByDescending { it.score }
                        list
                    }
                    val top = hits.firstOrNull()?.score ?: 0f
                    val suggested = (top - 0.05f).coerceIn(0.21f, 0.30f)
                    SearchResult(
                        s.query, english, dateFilter?.label, true, hits, suggested,
                        partialIndex = _state.value.indexedImages < _state.value.totalImages,
                    )
                }
                _state.update {
                    it.copy(
                        searching = false, result = result, screen = Screen.RESULTS,
                        threshold = result.suggestedThreshold, excluded = emptySet(),
                    )
                }
            } catch (e: Throwable) {
                _state.update { it.copy(searching = false, message = "Erro na busca: ${e.message}") }
            }
        }
    }

    /** Gírias brasileiras que o tradutor costuma errar. Adicione as suas aqui. */
    private fun applyGlossary(text: String): String {
        var t = text
        for ((pattern, replacement) in GLOSSARY) t = t.replace(pattern, replacement)
        return t
    }

    /** Média de duas formulações do texto: melhora a precisão do CLIP. */
    private fun textQuery(english: String): FloatArray {
        val a = clip.embedText(english)
        val b = clip.embedText("a photo of $english")
        val v = FloatArray(a.size) { a[it] + b[it] }
        val n = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        for (i in v.indices) v[i] /= n
        return v
    }

    fun setThreshold(t: Float) = _state.update { it.copy(threshold = t) }

    fun toggle(id: Long) = _state.update {
        it.copy(excluded = if (id in it.excluded) it.excluded - id else it.excluded + id)
    }

    fun selectAll() = _state.update { it.copy(excluded = emptySet()) }

    fun selectNone() = _state.update { st -> st.copy(excluded = st.visibleHits.mapTo(HashSet()) { it.id }) }

    fun back() = _state.update { it.copy(screen = Screen.HOME) }

    fun clearMessage() = _state.update { it.copy(message = null) }

    // ---------- Ações: Lixeira e mover ----------

    fun trashSelected() = startAction(ActionKind.Trash)

    fun moveSelected(relativePath: String) = startAction(ActionKind.Move(relativePath))

    private fun startAction(kind: ActionKind) {
        val sel = _state.value.selectedHits
        if (sel.isEmpty()) return
        remainingChunks = ArrayDeque(sel.chunked(CHUNK))
        actionDoneCount = 0
        nextChunk(kind)
    }

    private fun nextChunk(kind: ActionKind) {
        val chunk = remainingChunks.removeFirstOrNull()
        if (chunk == null) {
            finishAction(kind)
            return
        }
        val uris = chunk.map { it.uri }
        val sender = runCatching {
            when (kind) {
                ActionKind.Trash -> media.trashRequest(uris)
                is ActionKind.Move -> media.writeRequest(uris)
            }.intentSender
        }.getOrElse { e ->
            _state.update { it.copy(message = "Não foi possível pedir a confirmação: ${e.message}") }
            return
        }
        _state.update { it.copy(pending = PendingRequest(sender, kind, chunk)) }
    }

    /** Chamado pela tela depois que o usuário respondeu à confirmação do Android. */
    fun onSystemConfirmation(approved: Boolean) {
        val req = _state.value.pending ?: return
        _state.update { it.copy(pending = null) }
        if (!approved) {
            remainingChunks.clear()
            finishAction(req.kind, cancelled = true)
            return
        }
        viewModelScope.launch {
            when (val kind = req.kind) {
                ActionKind.Trash -> {
                    val ids = req.chunk.map { it.id }
                    withContext(Dispatchers.IO) { store.delete(ids) }
                    actionDoneCount += ids.size
                    removeFromResults(ids.toSet())
                }
                is ActionKind.Move -> {
                    _state.update { it.copy(busy = "Movendo fotos…") }
                    val ok = withContext(Dispatchers.IO) { media.moveTo(req.chunk.map { it.uri }, kind.relativePath) }
                    actionDoneCount += ok
                    _state.update { it.copy(busy = null) }
                }
            }
            nextChunk(req.kind)
        }
    }

    private fun finishAction(kind: ActionKind, cancelled: Boolean = false) {
        val msg = when {
            cancelled && actionDoneCount == 0 -> "Cancelado."
            kind is ActionKind.Trash -> "$actionDoneCount foto(s) enviadas para a Lixeira (recuperáveis por 30 dias)."
            kind is ActionKind.Move -> "$actionDoneCount foto(s) movidas para ${kind.relativePath.trimEnd('/')}."
            else -> null
        }
        _state.update { it.copy(message = msg) }
        refreshGallery()
    }

    private fun removeFromResults(ids: Set<Long>) {
        index = null
        _state.update { st ->
            val r = st.result ?: return@update st
            st.copy(result = r.copy(hits = r.hits.filter { it.id !in ids }), excluded = st.excluded - ids)
        }
    }

    override fun onCleared() {
        translator.close()
        runCatching { clip.close() }
    }

    companion object {
        private const val CHUNK = 500
        private const val MIN_SCORE = 0.15f
        private val GLOSSARY = listOf(
            Regex("(?i)\\bprints\\b") to "capturas de tela",
            Regex("(?i)\\bprint\\b") to "captura de tela",
            Regex("(?i)\\bzap\\b") to "WhatsApp",
            Regex("(?i)\\brango\\b") to "comida",
        )
    }
}
