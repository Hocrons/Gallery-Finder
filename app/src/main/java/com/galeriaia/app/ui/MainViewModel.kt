package com.galeriaia.app.ui

import android.app.Application
import android.content.IntentSender
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.galeriaia.app.data.Album
import com.galeriaia.app.data.EmbeddingIndex
import com.galeriaia.app.data.EmbeddingStore
import com.galeriaia.app.data.MediaImage
import com.galeriaia.app.data.MediaRepository
import com.galeriaia.app.data.Prefs
import com.galeriaia.app.indexing.IndexWorker
import com.galeriaia.app.ml.ClipModel
import com.galeriaia.app.ml.ModelDownloader
import com.galeriaia.app.ml.ModelFiles
import com.galeriaia.app.ml.PtEnTranslator
import com.galeriaia.app.search.DateFilter
import com.galeriaia.app.search.DuplicateFinder
import com.galeriaia.app.search.QueryParser
import com.galeriaia.app.update.Updater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.sqrt

enum class Screen { HOME, RESULTS, DUPLICATES }

data class Hit(val id: Long, val uri: Uri, val score: Float, val dateTaken: Long)

enum class SearchMode { TEXT, IMAGE, DATE }

data class SearchResult(
    val originalQuery: String,
    /** Texto que foi para a IA (já em inglês). */
    val englishText: String,
    val dateLabel: String?,
    val mode: SearchMode,
    val hits: List<Hit>,
    val suggestedThreshold: Float,
    /** Quanto da galeria a busca cobriu; null = tudo analisado. */
    val coverage: String? = null,
    /** Fotos do período buscado que a IA ainda não analisou. */
    val pendingInRange: Int = 0,
    val pendingFilter: DateFilter? = null,
    val exampleUri: Uri? = null,
) {
    val semantic: Boolean get() = mode != SearchMode.DATE
    /** Foto x foto dá notas bem mais altas que texto x foto, então a régua muda. */
    val sliderRange: ClosedFloatingPointRange<Float>
        get() = if (mode == SearchMode.IMAGE) 0.55f..0.98f else 0.15f..0.35f
}

data class DownloadState(val done: Long, val total: Long, val file: String, val error: String? = null)

data class IndexProgress(
    val done: Int,
    val total: Int,
    val startedAt: Long,
    val coolingTemp: Float? = null,
    val priorityLabel: String? = null,
)

data class UpdateUi(
    val release: Updater.Release,
    val progress: Float? = null,
    val readyFile: File? = null,
    val error: String? = null,
)

data class DupUi(
    val computing: Boolean = true,
    /** Cada grupo já vem ordenado: a primeira foto é a sugerida para manter. */
    val groups: List<List<Hit>> = emptyList(),
    val threshold: Float = DEFAULT_DUP_THRESHOLD,
    val selected: Set<Long> = emptySet(),
    val keep: Set<Long> = emptySet(),
    val partialIndex: Boolean = false,
    val coverage: String? = null,
) {
    val selectedHits: List<Hit> get() = groups.flatten().filter { it.id in selected }
}

const val DEFAULT_DUP_THRESHOLD = 0.96f
const val MIN_DUP_THRESHOLD = 0.93f

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
    val update: UpdateUi? = null,
    val installRequest: File? = null,
    val dup: DupUi? = null,
    val fastMode: Boolean = false,
    /** Data da foto mais antiga já analisada. */
    val indexedUntil: Long? = null,
    /** Foto de exemplo escolhida para a busca. */
    val exampleUri: Uri? = null,
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
    private val workManager = WorkManager.getInstance(app)
    /** Só o modelo de texto é carregado aqui; o de imagem roda no IndexWorker. */
    private val clip by lazy { ClipModel(ModelFiles.dir(app.filesDir)) }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var images: List<MediaImage> = emptyList()
    private var index: EmbeddingIndex? = null
    private var downloadJob: Job? = null
    private var remainingChunks: ArrayDeque<List<Hit>> = ArrayDeque()
    private var actionDoneCount = 0
    private var wasIndexing = false

    // dados das duplicadas (pares calculados uma vez; reagrupados quando muda a régua)
    private var dupIndex: EmbeddingIndex? = null
    private var dupPairs: DuplicateFinder.Pairs? = null
    private var dupDetails: Map<Long, MediaRepository.Detail> = emptyMap()
    private val removedIds = HashSet<Long>()

    init {
        _state.update { it.copy(fastMode = Prefs.fastMode(app)) }
        checkModels()
        observeIndexing()
        checkUpdate(manual = false)
    }

    // ---------- Permissão e modelos ----------

    fun onPermission(state: PermissionState) {
        _state.update { it.copy(permission = state) }
        if (state == PermissionState.GRANTED || state == PermissionState.PARTIAL) {
            refreshGallery()
            if (_state.value.modelsReady == true) IndexWorker.scheduleAuto(getApplication())
        }
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
                IndexWorker.scheduleAuto(getApplication())
            } catch (e: Exception) {
                _state.update {
                    it.copy(download = it.download?.copy(error = e.message ?: e.javaClass.simpleName))
                }
            }
        }
    }

    // ---------- Galeria e indexação (o trabalho pesado roda no IndexWorker) ----------

    fun refreshGallery() = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            images = media.queryImages()
            val current = images.mapTo(HashSet()) { it.id }
            val indexed = store.allIds()
            val indexedNow = indexed.count { it in current }
            val until = store.oldestDate()
            _state.update {
                val base = it.copy(totalImages = images.size, albums = media.albums(images), indexedUntil = until)
                if (it.indexProgress == null) base.copy(indexedImages = indexedNow) else base
            }
        }
    }

    private fun observeIndexing() = viewModelScope.launch {
        workManager.getWorkInfosByTagFlow(IndexWorker.TAG).collect { infos ->
            val running = infos.firstOrNull { it.state == WorkInfo.State.RUNNING }
            val waitingManual = infos.any {
                it.state == WorkInfo.State.ENQUEUED && it.tags.contains(IndexWorker.TAG_MANUAL)
            }
            when {
                running != null -> {
                    val p = running.progress
                    val total = p.getInt(IndexWorker.KEY_TOTAL, 0)
                    val done = p.getInt(IndexWorker.KEY_DONE, 0)
                    val started = p.getLong(IndexWorker.KEY_STARTED, System.currentTimeMillis())
                    val cooling = if (p.getBoolean(IndexWorker.KEY_COOLING, false)) p.getFloat(IndexWorker.KEY_TEMP, 0f) else null
                    val priority = p.getString(IndexWorker.KEY_PRIORITY)
                    wasIndexing = true
                    index = null
                    if (done % 160 == 0) launch(Dispatchers.IO) {
                        val until = store.oldestDate()
                        _state.update { it.copy(indexedUntil = until) }
                    }
                    _state.update {
                        it.copy(
                            indexProgress = IndexProgress(done, total, started, cooling, priority),
                            indexedImages = if (total > 0) (it.totalImages - (total - done)).coerceAtLeast(0) else it.indexedImages,
                        )
                    }
                }
                waitingManual -> _state.update {
                    it.copy(indexProgress = IndexProgress(0, 0, System.currentTimeMillis()))
                }
                else -> {
                    _state.update { it.copy(indexProgress = null) }
                    if (wasIndexing) {
                        wasIndexing = false
                        index = null
                        refreshGallery()
                        _state.update { it.copy(message = "Indexação finalizada ✔") }
                    }
                }
            }
        }
    }

    fun startIndexing() {
        if (_state.value.modelsReady != true) return
        IndexWorker.startNow(getApplication())
    }

    fun setFastMode(on: Boolean) {
        Prefs.setFastMode(getApplication(), on)
        _state.update { it.copy(fastMode = on) }
        // Se estiver indexando, reinicia já no novo modo (continua de onde parou)
        if (_state.value.indexProgress != null) IndexWorker.restart(getApplication())
    }

    fun cancelIndexing() {
        IndexWorker.cancel(getApplication())
        _state.update { it.copy(message = "Indexação pausada. Continue quando quiser.") }
    }

    private suspend fun loadIndex(): EmbeddingIndex =
        index ?: withContext(Dispatchers.IO) { store.loadIndex() }.also {
            if (_state.value.indexProgress == null) index = it
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

    fun setExample(uri: Uri?) = _state.update { it.copy(exampleUri = uri) }

    fun search() {
        val s = _state.value
        if (s.searching) return
        val parsed = QueryParser.parse(s.query)
        val dateFilter = s.manualRange ?: parsed.date
        val example = s.exampleUri
        if (example == null && parsed.text.isBlank() && dateFilter == null) {
            _state.update { it.copy(message = "Descreva a foto, escolha uma foto de exemplo ou informe um período.") }
            return
        }
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            try {
                val result = when {
                    example != null -> {
                        val vec = withContext(Dispatchers.Default) {
                            val bmp = withContext(Dispatchers.IO) { media.decodeSampled(example) }
                            try { clip.embedImage(clip.preprocess(bmp)) } finally { bmp.recycle() }
                        }
                        vectorSearch(vec, SearchMode.IMAGE, dateFilter, s.query, "", example)
                    }
                    parsed.text.isBlank() -> {
                        // Só data: não precisa de IA, usa a galeria inteira.
                        val hits = images.asSequence()
                            .filter { dateFilter!!.matches(toDate(it.dateTaken)) }
                            .sortedByDescending { it.dateTaken }
                            .map { Hit(it.id, it.uri, 1f, it.dateTaken) }
                            .toList()
                        SearchResult(s.query, "", dateFilter?.label, SearchMode.DATE, hits, 0f)
                    }
                    else -> {
                        val ptText = applyGlossary(parsed.text)
                        val english = runCatching { translator.translate(ptText) }.getOrDefault(ptText)
                        val vec = withContext(Dispatchers.Default) { textQuery(english) }
                        vectorSearch(vec, SearchMode.TEXT, dateFilter, s.query, english, null)
                    }
                }
                showResult(result)
            } catch (e: Throwable) {
                _state.update { it.copy(searching = false, message = "Erro na busca: ${e.message}") }
            }
        }
    }

    /** "Parecidas com esta": usa o vetor que a foto já tem no índice (instantâneo). */
    fun searchSimilarTo(hit: Hit) {
        if (_state.value.searching) return
        _state.update { it.copy(searching = true) }
        viewModelScope.launch {
            try {
                val idx = loadIndex()
                val pos = idx.ids.indexOf(hit.id)
                val vec = if (pos >= 0) {
                    idx.vectors.copyOfRange(pos * ClipModel.EMBED_DIM, (pos + 1) * ClipModel.EMBED_DIM)
                } else withContext(Dispatchers.Default) {
                    val bmp = withContext(Dispatchers.IO) { media.loadThumbnail(hit.uri) }
                    try { clip.embedImage(clip.preprocess(bmp)) } finally { bmp.recycle() }
                }
                showResult(vectorSearch(vec, SearchMode.IMAGE, null, "Parecidas com uma foto", "", hit.uri))
            } catch (e: Throwable) {
                _state.update { it.copy(searching = false, message = "Erro na busca: ${e.message}") }
            }
        }
    }

    private fun showResult(result: SearchResult) = _state.update {
        it.copy(
            searching = false, result = result, screen = Screen.RESULTS,
            threshold = result.suggestedThreshold, excluded = emptySet(),
        )
    }

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private fun toDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

    private suspend fun vectorSearch(
        q: FloatArray,
        mode: SearchMode,
        dateFilter: DateFilter?,
        originalQuery: String,
        english: String,
        exampleUri: Uri?,
    ): SearchResult {
        val idx = loadIndex()
        val minScore = if (mode == SearchMode.IMAGE) MIN_SCORE_IMAGE else MIN_SCORE
        val hits = withContext(Dispatchers.Default) {
            val scores = idx.scores(q)
            val list = ArrayList<Hit>()
            for (i in 0 until idx.size) {
                if (scores[i] < minScore) continue
                if (dateFilter != null && !dateFilter.matches(toDate(idx.dates[i]))) continue
                list += Hit(idx.ids[i], media.uriFor(idx.ids[i]), scores[i], idx.dates[i])
            }
            list.sortByDescending { it.score }
            list
        }
        val top = hits.firstOrNull()?.score ?: 0f
        val suggested = if (mode == SearchMode.IMAGE) {
            // a própria foto (se estiver na galeria) dá ~1.0; parecidas costumam ficar acima de 0.8
            0.80f
        } else (top - 0.05f).coerceIn(0.21f, 0.30f)

        // Cobertura: o que ainda falta analisar
        val st = _state.value
        var coverage: String? = null
        var pending = 0
        if (st.indexedImages < st.totalImages) {
            coverage = coverageText(st)
            if (dateFilter != null) {
                val indexedIds = withContext(Dispatchers.IO) { store.allIds() }
                pending = images.count { it.id !in indexedIds && dateFilter.matches(toDate(it.dateTaken)) }
            }
        }
        return SearchResult(
            originalQuery, english, dateFilter?.label, mode, hits, suggested,
            coverage = coverage,
            pendingInRange = pending,
            pendingFilter = dateFilter.takeIf { pending > 0 },
            exampleUri = exampleUri,
        )
    }

    fun coverageText(st: UiState): String {
        val pct = if (st.totalImages == 0) 0 else st.indexedImages * 100 / st.totalImages
        val until = st.indexedUntil?.let { ms ->
            val d = toDate(ms)
            " (das mais recentes até ${MONTHS[d.monthValue - 1]}/${d.year})"
        } ?: ""
        return "A IA analisou $pct% da galeria$until."
    }

    /** Analisa primeiro as fotos do período buscado. */
    fun prioritize(filter: DateFilter) {
        val from = filter.start?.atStartOfDay(zone)?.toInstant()?.toEpochMilli() ?: Long.MIN_VALUE
        val to = filter.end?.plusDays(1)?.atStartOfDay(zone)?.toInstant()?.toEpochMilli()?.minus(1) ?: Long.MAX_VALUE
        IndexWorker.startPriority(getApplication(), from, to, filter.monthOfYear ?: 0, filter.label)
        _state.update {
            it.copy(
                screen = Screen.HOME,
                message = "Analisando ${filter.label} primeiro. Busque de novo quando terminar.",
            )
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

    // ---------- Duplicadas e parecidas ----------

    fun findDuplicates() {
        _state.update { it.copy(screen = Screen.DUPLICATES, dup = DupUi(computing = true)) }
        viewModelScope.launch {
            try {
                val idx = loadIndex()
                val pairs = withContext(Dispatchers.Default) {
                    DuplicateFinder(ClipModel.EMBED_DIM).findPairs(idx.vectors, idx.size, MIN_DUP_THRESHOLD)
                }
                val involved = HashSet<Long>()
                for (p in 0 until pairs.size) { involved += idx.ids[pairs.a[p]]; involved += idx.ids[pairs.b[p]] }
                dupDetails = withContext(Dispatchers.IO) { media.details(involved) }
                dupIndex = idx
                dupPairs = pairs
                removedIds.clear()
                regroup(DEFAULT_DUP_THRESHOLD)
            } catch (e: Throwable) {
                _state.update { it.copy(screen = Screen.HOME, dup = null, message = "Erro ao procurar duplicadas: ${e.message}") }
            }
        }
    }

    fun setDupThreshold(t: Float) {
        viewModelScope.launch { regroup(t) }
    }

    private suspend fun regroup(threshold: Float) {
        val idx = dupIndex ?: return
        val pairs = dupPairs ?: return
        val groups = withContext(Dispatchers.Default) {
            DuplicateFinder.groups(pairs, idx.size, threshold).mapNotNull { g ->
                val hits = g.map { i -> Hit(idx.ids[i], media.uriFor(idx.ids[i]), 1f, idx.dates[i]) }
                    .filter { it.id !in removedIds }
                    // melhor primeiro: maior resolução, depois maior arquivo, depois a mais antiga (original)
                    .sortedWith(
                        compareByDescending<Hit> { dupDetails[it.id]?.pixels ?: 0L }
                            .thenByDescending { dupDetails[it.id]?.bytes ?: 0L }
                            .thenBy { it.dateTaken }
                    )
                hits.takeIf { it.size >= 2 }
            }
        }
        _state.update {
            it.copy(
                dup = DupUi(
                    computing = false,
                    groups = groups,
                    threshold = threshold,
                    keep = groups.mapTo(HashSet()) { g -> g.first().id },
                    selected = groups.flatMap { g -> g.drop(1) }.mapTo(HashSet()) { h -> h.id },
                    partialIndex = it.indexedImages < it.totalImages,
                    coverage = if (it.indexedImages < it.totalImages) coverageText(it) else null,
                )
            )
        }
    }

    fun toggleDup(id: Long) = _state.update { st ->
        val d = st.dup ?: return@update st
        st.copy(dup = d.copy(selected = if (id in d.selected) d.selected - id else d.selected + id))
    }

    fun openDuplicatesAgain() = _state.update { it.copy(screen = Screen.DUPLICATES) }

    fun trashDuplicates() = startAction(ActionKind.Trash, _state.value.dup?.selectedHits.orEmpty())

    // ---------- Ações: Lixeira e mover ----------

    fun trashSelected() = startAction(ActionKind.Trash, _state.value.selectedHits)

    fun moveSelected(relativePath: String) = startAction(ActionKind.Move(relativePath), _state.value.selectedHits)

    private fun startAction(kind: ActionKind, hits: List<Hit>) {
        if (hits.isEmpty()) return
        remainingChunks = ArrayDeque(hits.chunked(CHUNK))
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
                    removeIds(ids.toSet())
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

    private suspend fun removeIds(ids: Set<Long>) {
        index = null
        removedIds += ids
        _state.update { st ->
            val r = st.result
            st.copy(
                result = r?.copy(hits = r.hits.filter { it.id !in ids }),
                excluded = st.excluded - ids,
            )
        }
        if (_state.value.dup != null) regroup(_state.value.dup!!.threshold)
    }

    // ---------- Atualização do app ----------

    fun checkUpdate(manual: Boolean) = viewModelScope.launch {
        val release = withContext(Dispatchers.IO) { runCatching { Updater.checkLatest() } }
        release.onSuccess { rel ->
            if (rel != null) _state.update { it.copy(update = UpdateUi(rel)) }
            else if (manual) _state.update { it.copy(message = "Você já está na versão mais recente (build ${Updater.currentBuild}).") }
        }.onFailure { e ->
            if (manual) _state.update { it.copy(message = "Não consegui verificar: ${e.message}") }
        }
    }

    fun downloadUpdate() {
        val u = _state.value.update ?: return
        u.readyFile?.let { f -> _state.update { it.copy(installRequest = f) }; return }
        if (u.progress != null) return
        _state.update { it.copy(update = u.copy(progress = 0f, error = null)) }
        viewModelScope.launch {
            try {
                val file = withContext(Dispatchers.IO) {
                    Updater.download(getApplication(), u.release) { p ->
                        _state.update { st -> st.copy(update = st.update?.copy(progress = p)) }
                    }
                }
                _state.update { it.copy(update = it.update?.copy(progress = null, readyFile = file), installRequest = file) }
            } catch (e: Throwable) {
                _state.update { it.copy(update = it.update?.copy(progress = null, error = e.message)) }
            }
        }
    }

    fun installHandled() = _state.update { it.copy(installRequest = null) }

    override fun onCleared() {
        translator.close()
        runCatching { clip.close() }
    }

    companion object {
        private const val CHUNK = 500
        private const val MIN_SCORE = 0.15f
        private const val MIN_SCORE_IMAGE = 0.55f
        private val MONTHS = listOf("jan", "fev", "mar", "abr", "mai", "jun", "jul", "ago", "set", "out", "nov", "dez")
        private val GLOSSARY = listOf(
            Regex("(?i)\\bprints\\b") to "capturas de tela",
            Regex("(?i)\\bprint\\b") to "captura de tela",
            Regex("(?i)\\bzap\\b") to "WhatsApp",
            Regex("(?i)\\brango\\b") to "comida",
        )
    }
}
