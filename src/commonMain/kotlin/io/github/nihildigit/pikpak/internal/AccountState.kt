package io.github.nihildigit.pikpak.internal

import io.github.nihildigit.pikpak.AccountSnapshot
import io.github.nihildigit.pikpak.ApiRequestPriority
import io.github.nihildigit.pikpak.DriveTask
import io.github.nihildigit.pikpak.FileDetail
import io.github.nihildigit.pikpak.FileKind
import io.github.nihildigit.pikpak.FileStat
import io.github.nihildigit.pikpak.LeaseBudget
import io.github.nihildigit.pikpak.PikPakClient
import io.github.nihildigit.pikpak.QuotaResponse
import io.github.nihildigit.pikpak.TaskPhase
import io.github.nihildigit.pikpak.TransferAllowance
import io.github.nihildigit.pikpak.TransferQuota
import io.github.nihildigit.pikpak.UserProfile
import io.github.nihildigit.pikpak.getQuota
import io.github.nihildigit.pikpak.getTransferQuota
import io.github.nihildigit.pikpak.withApiPriority
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class AccountState(
    private val client: PikPakClient,
) {
    private data class QuotaMutation(
        val epoch: Long,
        val usageDeltaBytes: Long = 0L,
        val trashDeltaBytes: Long = 0L,
        val trashResetToZero: Boolean = false,
        val cloudDownloadDelta: Int = 0,
        val at: TimeMark,
    )

    private data class QuotaLedger(
        val base: QuotaResponse? = null,
        val mutations: List<QuotaMutation> = emptyList(),
        val epoch: Long = 0L,
        val lastFetchedAt: TimeMark? = null,
        val dirty: Boolean = false,
        val overrideUsageBytes: Long? = null,
    ) {
        val projected: QuotaResponse?
            get() {
                val b = base ?: return null
                if (mutations.isEmpty() && overrideUsageBytes == null) return b
                var usage = overrideUsageBytes ?: b.quota.usageBytes
                var trash = b.quota.usageInTrash.toLongOrNull() ?: 0L
                var cloudDelta = 0
                for (m in mutations) {
                    if (overrideUsageBytes == null) {
                        usage = (usage + m.usageDeltaBytes).coerceAtLeast(0L)
                    }
                    trash = if (m.trashResetToZero) 0L else (trash + m.trashDeltaBytes).coerceAtLeast(0L)
                    cloudDelta += m.cloudDownloadDelta
                }
                val nextCloud = if (cloudDelta != 0 && !b.quotas.cloudDownload.isUnlimited) {
                    val baseCount = b.quotas.cloudDownload.usage.toIntOrNull()
                    if (baseCount != null) {
                        b.quotas.cloudDownload.copy(usage = (baseCount + cloudDelta).coerceAtLeast(0).toString())
                    } else b.quotas.cloudDownload
                } else b.quotas.cloudDownload
                return b.copy(
                    quota = b.quota.copy(
                        usage = usage.coerceAtLeast(0L).toString(),
                        usageInTrash = trash.coerceAtLeast(0L).toString(),
                    ),
                    quotas = b.quotas.copy(cloudDownload = nextCloud),
                )
            }
    }

    private data class TransferMutation(
        val epoch: Long,
        val offlineDeltaBytes: Long = 0L,
        val downloadDeltaBytes: Long = 0L,
        val uploadDeltaBytes: Long = 0L,
        val at: TimeMark,
    )

    private data class TransferLedger(
        val base: TransferQuota? = null,
        val mutations: List<TransferMutation> = emptyList(),
        val unflushedDownloadBytes: Long = 0L,
        val lastDownloadFlushAt: TimeMark? = null,
        val reconciledDownloadBytes: Long = 0L,
        val epoch: Long = 0L,
        val lastFetchedAt: TimeMark? = null,
        val dirty: Boolean = false,
    ) {
        val projected: TransferQuota?
            get() {
                val b = base ?: return null
                if (mutations.isEmpty() && unflushedDownloadBytes == 0L) return b
                var offlineDelta = 0L
                var downloadDelta = unflushedDownloadBytes
                var uploadDelta = 0L
                for (m in mutations) {
                    offlineDelta += m.offlineDeltaBytes
                    downloadDelta += m.downloadDeltaBytes
                    uploadDelta += m.uploadDeltaBytes
                }
                if (offlineDelta == 0L && downloadDelta == 0L && uploadDelta == 0L) return b
                fun TransferAllowance.withDelta(delta: Long): TransferAllowance =
                    if (delta == 0L) this else copy(usedBytes = (usedBytes + delta).coerceAtLeast(0L))
                val acc = b.account
                return b.copy(
                    account = acc.copy(
                        offline = acc.offline.withDelta(offlineDelta),
                        download = acc.download.withDelta(downloadDelta),
                        upload = acc.upload.withDelta(uploadDelta),
                        downloadDaily = if (acc.downloadDaily.limitBytes > 0L) {
                            acc.downloadDaily.withDelta(downloadDelta)
                        } else acc.downloadDaily,
                    ),
                )
            }
    }

    internal data class KnownEntry(
        val sizeBytes: Long,
        val trashed: Boolean,
        val deleted: Boolean = false,
        val pendingUpload: Boolean = false,
        val isFolder: Boolean = false,
    )

    private data class InFlight<T>(val epoch: Long, val deferred: Deferred<T>)

    private val quotaLedger = MutableStateFlow(QuotaLedger())
    private val transferLedger = MutableStateFlow(TransferLedger())
    private val _quotaFlow = MutableStateFlow<QuotaResponse?>(null)
    val quotaFlow: StateFlow<QuotaResponse?> = _quotaFlow.asStateFlow()

    private val _transferQuotaFlow = MutableStateFlow<TransferQuota?>(null)
    val transferQuotaFlow: StateFlow<TransferQuota?> = _transferQuotaFlow.asStateFlow()

    private val _userProfileFlow = MutableStateFlow<UserProfile?>(null)
    val userProfileFlow: StateFlow<UserProfile?> = _userProfileFlow.asStateFlow()
    private val profileFetchedAt = MutableStateFlow<TimeMark?>(null)

    private val _isFreeAccountFlow = MutableStateFlow<Boolean?>(null)
    val isFreeAccountFlow: StateFlow<Boolean?> = _isFreeAccountFlow.asStateFlow()

    private val knownEntries = MutableStateFlow<Map<String, KnownEntry>>(emptyMap())
    private val observedTaskPhases = MutableStateFlow<Map<String, String>>(emptyMap())

    private val quotaInFlight = MutableStateFlow<InFlight<QuotaResponse>?>(null)
    private val transferInFlight = MutableStateFlow<InFlight<TransferQuota>?>(null)
    private val profileInFlight = MutableStateFlow<Deferred<UserProfile>?>(null)

    private val snapshotLoadMutex = Mutex()
    private val snapshotLoaded = MutableStateFlow(false)
    private val reconcileMutex = Mutex()
    private var quotaReconcileJob: Job? = null
    private var transferReconcileJob: Job? = null
    private var snapshotSaveJob: Job? = null

    init {
        client.background.launch {
            runCatching { ensureSnapshotLoaded() }
        }
    }

    internal suspend fun ensureSnapshotLoaded() {
        if (snapshotLoaded.value) return
        snapshotLoadMutex.withLock {
            if (snapshotLoaded.value) return
            val snapshot = runCatching { client.sessionStore.loadAccountSnapshot(client.account) }.getOrNull()
            if (snapshot != null) {
                seed(
                    quota = snapshot.quota,
                    transferQuota = snapshot.transferQuota,
                    userProfile = snapshot.userProfile,
                    persist = false,
                )
            }
            snapshotLoaded.value = true
        }
    }

    fun seed(
        quota: QuotaResponse? = null,
        transferQuota: TransferQuota? = null,
        userProfile: UserProfile? = null,
        persist: Boolean = false,
    ) {
        var changed = false
        if (quota != null) {
            quotaLedger.update { cur ->
                if (cur.base != null) cur
                else {
                    changed = true
                    cur.copy(base = quota, dirty = true)
                }
            }
            publishQuota()
        }
        if (transferQuota != null) {
            transferLedger.update { cur ->
                if (cur.base != null) cur
                else {
                    changed = true
                    cur.copy(base = transferQuota, dirty = true)
                }
            }
            publishTransferQuota()
        }
        if (userProfile != null && _userProfileFlow.value == null) {
            if (_userProfileFlow.compareAndSet(null, userProfile)) {
                changed = true
            }
        }
        if (changed && persist) {
            scheduleSnapshotSave()
        }
    }

    fun clearOnLogout() {
        quotaInFlight.update { old -> old?.deferred?.cancel(); null }
        transferInFlight.update { old -> old?.deferred?.cancel(); null }
        profileInFlight.update { old -> old?.cancel(); null }
        quotaReconcileJob?.cancel()
        transferReconcileJob?.cancel()
        snapshotSaveJob?.cancel()
        quotaLedger.value = QuotaLedger()
        transferLedger.value = TransferLedger()
        _quotaFlow.value = null
        _transferQuotaFlow.value = null
        _userProfileFlow.value = null
        profileFetchedAt.value = null
        _isFreeAccountFlow.value = null
        knownEntries.value = emptyMap()
        observedTaskPhases.value = emptyMap()
    }

    suspend fun getQuota(maxAge: Duration, fetchLive: suspend () -> QuotaResponse): QuotaResponse {
        if (maxAge > Duration.ZERO) {
            ensureSnapshotLoaded()
            val cur = quotaLedger.value
            val projected = cur.projected
            val fetchedAt = cur.lastFetchedAt
            if (projected != null && !cur.dirty && fetchedAt != null && fetchedAt.elapsedNow() < maxAge) {
                return projected
            }
        }
        val targetEpoch = quotaLedger.value.epoch
        val existing = quotaInFlight.value?.takeIf {
            it.deferred.isActive && (it.epoch == targetEpoch || maxAge > Duration.ZERO)
        }?.deferred
        if (existing != null) return existing.await()

        val created = client.background.async(start = CoroutineStart.LAZY) {
            val reqEpoch = quotaLedger.value.epoch
            val fetched = fetchLive()
            onServerQuotaFetched(fetched, reqEpoch)
        }
        var winner = created
        quotaInFlight.update { cur ->
            if (cur != null && cur.deferred.isActive && (cur.epoch == targetEpoch || maxAge > Duration.ZERO)) {
                winner = cur.deferred
                cur
            } else {
                InFlight(targetEpoch, created)
            }
        }
        if (winner !== created) {
            created.cancel()
            return winner.await()
        }
        created.invokeOnCompletion {
            quotaInFlight.update { cur -> if (cur?.deferred === created) null else cur }
        }
        created.start()
        return created.await()
    }

    private fun onServerQuotaFetched(fetched: QuotaResponse, requestEpoch: Long): QuotaResponse {
        val now = client.timeSource.markNow()
        var needsLagFollowUp = false
        quotaLedger.update { cur ->
            val preRequestMutations = cur.mutations.filter { it.epoch <= requestEpoch }
            val postRequestMutations = cur.mutations.filter { it.epoch > requestEpoch }
            val preUsageDelta = preRequestMutations.sumOf { it.usageDeltaBytes }
            val preTrashDelta = preRequestMutations.sumOf { it.trashDeltaBytes }
            val preResetTrash = preRequestMutations.any { it.trashResetToZero }
            val prevBase = cur.base
            val serverUnchangedSinceBase = prevBase != null &&
                fetched.quota.usageBytes == prevBase.quota.usageBytes &&
                fetched.quota.usageInTrash == prevBase.quota.usageInTrash
            val recentPreMutation = preRequestMutations.lastOrNull()?.at?.let { it.elapsedNow() < WRITE_LAG_WINDOW } == true
            val serverLagged = serverUnchangedSinceBase &&
                recentPreMutation &&
                (preUsageDelta != 0L || preTrashDelta != 0L || preResetTrash)
            val retainedMutations = if (serverLagged) {
                needsLagFollowUp = true
                cur.mutations
            } else {
                postRequestMutations
            }
            cur.copy(
                base = fetched,
                mutations = retainedMutations,
                lastFetchedAt = now,
                dirty = retainedMutations.isNotEmpty(),
                overrideUsageBytes = if (retainedMutations.isEmpty()) null else cur.overrideUsageBytes,
            )
        }
        val projected = publishQuota() ?: fetched
        if (client.autoLeaseBudget && client.leaseBudget == null && projected.quota.limitBytes > 0L) {
            client.leaseBudget = LeaseBudget(projected.quota.remainingBytes.coerceAtLeast(0L) * 9L / 10L)
        }
        scheduleSnapshotSave()
        if (needsLagFollowUp) {
            scheduleQuotaReconciliation(delayDuration = WRITE_LAG_RETRY_DELAY)
        }
        return projected
    }

    suspend fun getTransferQuota(maxAge: Duration, fetchLive: suspend () -> TransferQuota): TransferQuota {
        flushDownstreamBytes(reconcile = false)
        if (maxAge > Duration.ZERO) {
            ensureSnapshotLoaded()
            val cur = transferLedger.value
            val projected = cur.projected
            val fetchedAt = cur.lastFetchedAt
            if (projected != null && !cur.dirty && fetchedAt != null && fetchedAt.elapsedNow() < maxAge) {
                return projected
            }
        }
        val targetEpoch = transferLedger.value.epoch
        val existing = transferInFlight.value?.takeIf {
            it.deferred.isActive && (it.epoch == targetEpoch || maxAge > Duration.ZERO)
        }?.deferred
        if (existing != null) return existing.await()

        val created = client.background.async(start = CoroutineStart.LAZY) {
            flushDownstreamBytes(reconcile = false)
            val reqEpoch = transferLedger.value.epoch
            val fetched = fetchLive()
            onServerTransferQuotaFetched(fetched, reqEpoch)
        }
        var winner = created
        transferInFlight.update { cur ->
            if (cur != null && cur.deferred.isActive && (cur.epoch == targetEpoch || maxAge > Duration.ZERO)) {
                winner = cur.deferred
                cur
            } else {
                InFlight(targetEpoch, created)
            }
        }
        if (winner !== created) {
            created.cancel()
            return winner.await()
        }
        created.invokeOnCompletion {
            transferInFlight.update { cur -> if (cur?.deferred === created) null else cur }
        }
        created.start()
        return created.await()
    }

    private fun onServerTransferQuotaFetched(fetched: TransferQuota, requestEpoch: Long): TransferQuota {
        val now = client.timeSource.markNow()
        var needsLagFollowUp = false
        transferLedger.update { cur ->
            val preRequestMutations = cur.mutations.filter { it.epoch <= requestEpoch }
            val postRequestMutations = cur.mutations.filter { it.epoch > requestEpoch }
            val preOfflineUploadDelta = preRequestMutations.sumOf { it.offlineDeltaBytes + it.uploadDeltaBytes }
            val prevBase = cur.base
            val serverUnchanged = prevBase != null &&
                fetched.account.offline.usedBytes == prevBase.account.offline.usedBytes &&
                fetched.account.upload.usedBytes == prevBase.account.upload.usedBytes
            val recentPreMutation = preRequestMutations.lastOrNull()?.at?.let { it.elapsedNow() < WRITE_LAG_WINDOW } == true
            val serverLagged = serverUnchanged && recentPreMutation && preOfflineUploadDelta != 0L
            val retainedMutations = if (serverLagged) {
                needsLagFollowUp = true
                preRequestMutations.map { it.copy(downloadDeltaBytes = 0L) }
                    .filter { it.offlineDeltaBytes != 0L || it.uploadDeltaBytes != 0L } + postRequestMutations
            } else {
                postRequestMutations
            }
            cur.copy(
                base = fetched,
                mutations = retainedMutations,
                reconciledDownloadBytes = 0L,
                lastFetchedAt = now,
                dirty = retainedMutations.isNotEmpty() || cur.unflushedDownloadBytes > 0L,
            )
        }
        val projected = publishTransferQuota() ?: fetched
        scheduleSnapshotSave()
        if (needsLagFollowUp) {
            scheduleTransferQuotaReconciliation(delayDuration = WRITE_LAG_RETRY_DELAY)
        }
        return projected
    }

    suspend fun getUserProfile(maxAge: Duration, fetchLive: suspend () -> UserProfile): UserProfile {
        if (maxAge > Duration.ZERO) {
            ensureSnapshotLoaded()
            val cached = _userProfileFlow.value
            val fetchedAt = profileFetchedAt.value
            if (cached != null && fetchedAt != null && fetchedAt.elapsedNow() < maxAge) {
                return cached
            }
        }
        val existing = profileInFlight.value?.takeIf { it.isActive }
        if (existing != null) return existing.await()

        val created = client.background.async(start = CoroutineStart.LAZY) {
            val fetched = fetchLive()
            _userProfileFlow.value = fetched
            profileFetchedAt.value = client.timeSource.markNow()
            scheduleSnapshotSave()
            fetched
        }
        var winner = created
        profileInFlight.update { cur ->
            if (cur != null && cur.isActive) {
                winner = cur
                cur
            } else {
                created
            }
        }
        if (winner !== created) {
            created.cancel()
            return winner.await()
        }
        created.invokeOnCompletion {
            profileInFlight.update { cur -> if (cur === created) null else cur }
        }
        created.start()
        return created.await()
    }

    fun observeFiles(files: Iterable<FileStat>) {
        val additions = HashMap<String, KnownEntry>()
        for (file in files) {
            if (file.id.isBlank() || file.isFolder) continue
            additions[file.id] = KnownEntry(
                sizeBytes = file.sizeBytes.coerceAtLeast(0L),
                trashed = file.trashed,
                deleted = false,
                pendingUpload = file.phase == TaskPhase.PENDING,
                isFolder = false,
            )
        }
        if (additions.isEmpty()) return
        upsertKnownEntries(additions)
    }

    fun observeFile(detail: FileDetail) {
        if (detail.id.isBlank() || detail.kind == FileKind.FOLDER) return
        upsertKnownEntries(
            mapOf(
                detail.id to KnownEntry(
                    sizeBytes = detail.sizeBytes.coerceAtLeast(0L),
                    trashed = detail.trashed,
                    deleted = false,
                    pendingUpload = detail.phase == TaskPhase.PENDING,
                    isFolder = false,
                ),
            ),
        )
    }

    fun observeFolderSubtree(folderId: String, physicalBytes: Long, trashed: Boolean = false) {
        if (folderId.isBlank() || physicalBytes < 0L) return
        upsertKnownEntries(
            mapOf(
                folderId to KnownEntry(
                    sizeBytes = physicalBytes,
                    trashed = trashed,
                    deleted = false,
                    pendingUpload = false,
                    isFolder = true,
                ),
            ),
        )
    }

    private fun upsertKnownEntries(additions: Map<String, KnownEntry>) {
        knownEntries.update { current ->
            val next = LinkedHashMap<String, KnownEntry>(current.size + additions.size)
            next.putAll(current)
            for ((id, entry) in additions) {
                next.remove(id)
                next[id] = entry
            }
            while (next.size > MAX_KNOWN_ENTRIES) {
                val eldest = next.keys.firstOrNull() ?: break
                next.remove(eldest)
            }
            next
        }
    }

    fun onFilesTrashed(ids: Collection<String>, reconcile: Boolean = true) {
        val targetIds = ids.filter { it.isNotBlank() }.distinct()
        if (targetIds.isEmpty()) return
        var usageDelta = 0L
        var trashDelta = 0L
        knownEntries.update { current ->
            var modified: MutableMap<String, KnownEntry>? = null
            for (id in targetIds) {
                val entry = current[id] ?: continue
                if (!entry.trashed && !entry.deleted && !entry.pendingUpload) {
                    val map = modified ?: LinkedHashMap(current).also { modified = it }
                    map[id] = entry.copy(trashed = true)
                    usageDelta -= entry.sizeBytes
                    trashDelta += entry.sizeBytes
                }
            }
            modified ?: current
        }
        recordQuotaMutation(
            usageDeltaBytes = usageDelta,
            trashDeltaBytes = trashDelta,
            reconcile = reconcile,
        )
    }

    fun onFilesUntrashed(ids: Collection<String>, reconcile: Boolean = true) {
        val targetIds = ids.filter { it.isNotBlank() }.distinct()
        if (targetIds.isEmpty()) return
        var usageDelta = 0L
        var trashDelta = 0L
        knownEntries.update { current ->
            var modified: MutableMap<String, KnownEntry>? = null
            for (id in targetIds) {
                val entry = current[id] ?: continue
                if ((entry.trashed || entry.deleted) && !entry.pendingUpload) {
                    val map = modified ?: LinkedHashMap(current).also { modified = it }
                    map[id] = entry.copy(trashed = false, deleted = false)
                    usageDelta += entry.sizeBytes
                    if (entry.trashed) trashDelta -= entry.sizeBytes
                }
            }
            modified ?: current
        }
        recordQuotaMutation(
            usageDeltaBytes = usageDelta,
            trashDeltaBytes = trashDelta,
            reconcile = reconcile,
        )
    }

    fun onFilesDeleted(ids: Collection<String>, reconcile: Boolean = true) {
        val targetIds = ids.filter { it.isNotBlank() }.distinct()
        if (targetIds.isEmpty()) return
        var usageDelta = 0L
        var trashDelta = 0L
        knownEntries.update { current ->
            var modified: MutableMap<String, KnownEntry>? = null
            for (id in targetIds) {
                val entry = current[id] ?: continue
                if (!entry.deleted) {
                    val map = modified ?: LinkedHashMap(current).also { modified = it }
                    map[id] = entry.copy(deleted = true)
                    if (!entry.pendingUpload) {
                        if (entry.trashed) {
                            trashDelta -= entry.sizeBytes
                        } else {
                            usageDelta -= entry.sizeBytes
                        }
                    }
                }
            }
            modified ?: current
        }
        recordQuotaMutation(
            usageDeltaBytes = usageDelta,
            trashDeltaBytes = trashDelta,
            reconcile = reconcile,
        )
    }

    fun revertFiles(snapshots: Iterable<FileStat>) {
        var usageDelta = 0L
        var trashDelta = 0L
        knownEntries.update { current ->
            var modified: MutableMap<String, KnownEntry>? = null
            for (stat in snapshots) {
                if (stat.id.isBlank()) continue
                val cur = current[stat.id] ?: continue
                val size = if (cur.sizeBytes > 0L) cur.sizeBytes else stat.sizeBytes.coerceAtLeast(0L)
                if (cur.deleted) {
                    val map = modified ?: LinkedHashMap(current).also { modified = it }
                    map[stat.id] = cur.copy(sizeBytes = size, trashed = stat.trashed, deleted = false)
                    if (!cur.pendingUpload) {
                        if (stat.trashed) trashDelta += size else usageDelta += size
                    }
                } else if (cur.trashed != stat.trashed) {
                    val map = modified ?: LinkedHashMap(current).also { modified = it }
                    map[stat.id] = cur.copy(sizeBytes = size, trashed = stat.trashed)
                    if (!cur.pendingUpload) {
                        if (stat.trashed) {
                            usageDelta -= size
                            trashDelta += size
                        } else {
                            usageDelta += size
                            trashDelta -= size
                        }
                    }
                }
            }
            modified ?: current
        }
        if (usageDelta != 0L || trashDelta != 0L) {
            recordQuotaMutation(usageDeltaBytes = usageDelta, trashDeltaBytes = trashDelta, reconcile = false)
        }
    }

    fun onTrashEmptied(reconcile: Boolean = true) {
        knownEntries.update { current ->
            var modified: MutableMap<String, KnownEntry>? = null
            for ((id, entry) in current) {
                if (entry.trashed && !entry.deleted) {
                    val map = modified ?: LinkedHashMap(current).also { modified = it }
                    map[id] = entry.copy(deleted = true)
                }
            }
            modified ?: current
        }
        recordQuotaMutation(
            usageDeltaBytes = 0L,
            trashDeltaBytes = 0L,
            trashResetToZero = true,
            reconcile = reconcile,
        )
    }

    fun onInstantCreated(fileId: String, sizeBytes: Long, reconcile: Boolean = true) {
        val cleanSize = sizeBytes.coerceAtLeast(0L)
        var storageDelta = 0L
        if (fileId.isNotBlank()) {
            knownEntries.update { current ->
                val existing = current[fileId]
                if (existing != null && !existing.deleted && !existing.trashed && !existing.pendingUpload) {
                    current
                } else {
                    storageDelta = cleanSize
                    val next = LinkedHashMap(current)
                    next[fileId] = KnownEntry(sizeBytes = cleanSize, trashed = false, deleted = false)
                    next
                }
            }
        } else {
            storageDelta = cleanSize
        }
        val uploadAllowanceCharge = (cleanSize * 15L) / 100L
        recordQuotaMutation(usageDeltaBytes = storageDelta, reconcile = reconcile)
        recordTransferMutation(uploadDeltaBytes = uploadAllowanceCharge, reconcile = reconcile)
    }

    fun onUploadPending(fileId: String, sizeBytes: Long) {
        if (fileId.isBlank()) return
        upsertKnownEntries(
            mapOf(
                fileId to KnownEntry(
                    sizeBytes = sizeBytes.coerceAtLeast(0L),
                    trashed = false,
                    deleted = false,
                    pendingUpload = true,
                ),
            ),
        )
    }

    fun onUploadBytesSent(bytes: Long) {
        if (bytes <= 0L) return
        recordTransferMutation(uploadDeltaBytes = bytes, reconcile = false)
    }

    fun onUploadCompleted(fileId: String, sizeBytes: Long, reconcile: Boolean = true) {
        val cleanSize = sizeBytes.coerceAtLeast(0L)
        var storageDelta = 0L
        if (fileId.isNotBlank()) {
            knownEntries.update { current ->
                val existing = current[fileId]
                if (existing != null && !existing.pendingUpload && !existing.deleted && !existing.trashed) {
                    current
                } else {
                    storageDelta = cleanSize
                    val next = LinkedHashMap(current)
                    next[fileId] = KnownEntry(
                        sizeBytes = cleanSize,
                        trashed = false,
                        deleted = false,
                        pendingUpload = false,
                    )
                    next
                }
            }
        } else {
            storageDelta = cleanSize
        }
        recordQuotaMutation(usageDeltaBytes = storageDelta, reconcile = reconcile)
        recordTransferMutation(reconcile = reconcile)
    }

    fun onOfflineTaskQueued(task: DriveTask, reconcile: Boolean = true) {
        if (task.id.isNotBlank()) {
            observedTaskPhases.update { it + (task.id to task.phase) }
        }
        val completedBytes = if (task.phase == TaskPhase.COMPLETE) {
            task.fileSize.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
        } else 0L
        recordQuotaMutation(
            usageDeltaBytes = completedBytes,
            cloudDownloadDelta = 1,
            reconcile = reconcile,
        )
        recordTransferMutation(
            offlineDeltaBytes = completedBytes,
            reconcile = reconcile,
        )
    }

    fun onOfflineInstantCompleted(file: FileStat?, reconcile: Boolean = true) {
        val sizeBytes = file?.sizeBytes?.coerceAtLeast(0L) ?: 0L
        if (file != null && file.id.isNotBlank()) {
            observeFiles(listOf(file))
        }
        recordQuotaMutation(usageDeltaBytes = sizeBytes, reconcile = reconcile)
        recordTransferMutation(offlineDeltaBytes = sizeBytes, reconcile = reconcile)
    }

    fun observeTasks(tasks: Iterable<DriveTask>, baseline: Boolean = false) {
        var sawCompleted = false
        var sawCompletedOffline = false
        var newlyCompletedBytes = 0L
        observedTaskPhases.update { current ->
            var modified: MutableMap<String, String>? = null
            for (task in tasks) {
                if (task.id.isBlank()) continue
                val previousPhase = current[task.id]
                if (previousPhase != task.phase) {
                    val map = modified ?: LinkedHashMap(current).also { modified = it }
                    map.remove(task.id)
                    map[task.id] = task.phase
                    while (map.size > MAX_TRACKED_TASKS) {
                        val eldest = map.keys.firstOrNull() ?: break
                        map.remove(eldest)
                    }
                    val newlyComplete = task.phase == TaskPhase.COMPLETE &&
                        (previousPhase != null || !baseline)
                    if (newlyComplete) {
                        sawCompleted = true
                        if (task.type == "offline" || task.type.isEmpty()) {
                            sawCompletedOffline = true
                            val taskBytes = task.fileSize.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                            if (taskBytes > 0L && (task.fileId.isBlank() || knownEntries.value[task.fileId] == null)) {
                                newlyCompletedBytes += taskBytes
                            }
                        }
                    }
                }
            }
            modified ?: current
        }
        if (sawCompleted) {
            recordQuotaMutation(usageDeltaBytes = newlyCompletedBytes, reconcile = true)
        }
        if (sawCompletedOffline) {
            recordTransferMutation(offlineDeltaBytes = newlyCompletedBytes, reconcile = true)
        }
    }

    fun onStorageMutated(reconcile: Boolean = true) {
        recordQuotaMutation(reconcile = reconcile)
    }

    fun noteStorageUsage(usageBytes: Long, reconcile: Boolean = false) {
        val clean = usageBytes.coerceAtLeast(0L)
        quotaLedger.update { cur ->
            cur.copy(
                overrideUsageBytes = clean,
                epoch = cur.epoch + 1L,
                dirty = true,
            )
        }
        publishQuota()
        if (reconcile) scheduleQuotaReconciliation()
    }

    fun adjustStorageUsage(usageDeltaBytes: Long, trashDeltaBytes: Long = 0L, reconcile: Boolean = true) {
        recordQuotaMutation(
            usageDeltaBytes = usageDeltaBytes,
            trashDeltaBytes = trashDeltaBytes,
            reconcile = reconcile,
        )
    }

    fun onDownstreamBytesRead(bytes: Long, flush: Boolean = false) {
        if (bytes <= 0L && !flush) return
        val now = client.timeSource.markNow()
        var shouldPublish = false
        var shouldReconcile = false
        transferLedger.update { cur ->
            val nextUnflushed = cur.unflushedDownloadBytes + bytes.coerceAtLeast(0L)
            val elapsedSinceFlush = cur.lastDownloadFlushAt?.elapsedNow() ?: Duration.INFINITE
            if (flush || nextUnflushed >= DOWNSTREAM_FLUSH_BYTES || elapsedSinceFlush >= DOWNSTREAM_FLUSH_INTERVAL) {
                shouldPublish = true
                val nextEpoch = if (nextUnflushed > 0L) cur.epoch + 1L else cur.epoch
                val nextMutations = if (nextUnflushed > 0L) {
                    (cur.mutations + TransferMutation(epoch = nextEpoch, downloadDeltaBytes = nextUnflushed, at = now))
                        .takeLast(MAX_LEDGER_MUTATIONS)
                } else cur.mutations
                val nextReconciled = cur.reconciledDownloadBytes + nextUnflushed
                if (nextReconciled >= DOWNSTREAM_RECONCILE_BYTES) {
                    shouldReconcile = true
                }
                cur.copy(
                    mutations = nextMutations,
                    unflushedDownloadBytes = 0L,
                    lastDownloadFlushAt = now,
                    reconciledDownloadBytes = if (shouldReconcile) 0L else nextReconciled,
                    epoch = nextEpoch,
                    dirty = cur.dirty || nextUnflushed > 0L,
                )
            } else {
                cur.copy(
                    unflushedDownloadBytes = nextUnflushed,
                    dirty = true,
                )
            }
        }
        if (shouldPublish) {
            publishTransferQuota()
        }
        if (shouldReconcile || (flush && transferLedger.value.reconciledDownloadBytes > 0L)) {
            scheduleTransferQuotaReconciliation(delayDuration = DOWNSTREAM_RECONCILE_DEBOUNCE)
        }
    }

    fun flushDownstreamBytes(reconcile: Boolean = true) {
        onDownstreamBytesRead(0L, flush = true)
        if (!reconcile) return
        if (transferLedger.value.reconciledDownloadBytes > 0L) {
            scheduleTransferQuotaReconciliation(delayDuration = DOWNSTREAM_RECONCILE_DEBOUNCE)
        }
    }

    private fun recordQuotaMutation(
        usageDeltaBytes: Long = 0L,
        trashDeltaBytes: Long = 0L,
        trashResetToZero: Boolean = false,
        cloudDownloadDelta: Int = 0,
        reconcile: Boolean = true,
    ) {
        val now = client.timeSource.markNow()
        val hasDelta = usageDeltaBytes != 0L || trashDeltaBytes != 0L || trashResetToZero || cloudDownloadDelta != 0
        quotaLedger.update { cur ->
            val nextEpoch = cur.epoch + 1L
            val nextMutations = if (hasDelta) {
                (cur.mutations + QuotaMutation(
                    epoch = nextEpoch,
                    usageDeltaBytes = usageDeltaBytes,
                    trashDeltaBytes = trashDeltaBytes,
                    trashResetToZero = trashResetToZero,
                    cloudDownloadDelta = cloudDownloadDelta,
                    at = now,
                )).takeLast(MAX_LEDGER_MUTATIONS)
            } else cur.mutations
            cur.copy(
                mutations = nextMutations,
                epoch = nextEpoch,
                dirty = true,
                overrideUsageBytes = if (hasDelta) null else cur.overrideUsageBytes,
            )
        }
        if (hasDelta) {
            publishQuota()
            if (reconcile) {
                scheduleSnapshotSave()
            }
        }
        if (reconcile) {
            scheduleQuotaReconciliation()
        }
    }

    private fun recordTransferMutation(
        offlineDeltaBytes: Long = 0L,
        downloadDeltaBytes: Long = 0L,
        uploadDeltaBytes: Long = 0L,
        reconcile: Boolean = true,
    ) {
        val now = client.timeSource.markNow()
        val hasDelta = offlineDeltaBytes != 0L || downloadDeltaBytes != 0L || uploadDeltaBytes != 0L
        transferLedger.update { cur ->
            val nextEpoch = cur.epoch + 1L
            val nextMutations = if (hasDelta) {
                (cur.mutations + TransferMutation(
                    epoch = nextEpoch,
                    offlineDeltaBytes = offlineDeltaBytes,
                    downloadDeltaBytes = downloadDeltaBytes,
                    uploadDeltaBytes = uploadDeltaBytes,
                    at = now,
                )).takeLast(MAX_LEDGER_MUTATIONS)
            } else cur.mutations
            cur.copy(
                mutations = nextMutations,
                epoch = nextEpoch,
                dirty = true,
            )
        }
        if (hasDelta) {
            publishTransferQuota()
            scheduleSnapshotSave()
        }
        if (reconcile) {
            scheduleTransferQuotaReconciliation()
        }
    }

    private fun publishQuota(): QuotaResponse? {
        val projected = quotaLedger.value.projected
        _quotaFlow.value = projected
        if (client.autoLeaseBudget && client.leaseBudget == null && projected != null && projected.quota.limitBytes > 0L) {
            client.leaseBudget = LeaseBudget(projected.quota.remainingBytes.coerceAtLeast(0L) * 9L / 10L)
        }
        updateIsFreeAccount()
        return projected
    }

    private fun publishTransferQuota(): TransferQuota? {
        val projected = transferLedger.value.projected
        _transferQuotaFlow.value = projected
        updateIsFreeAccount()
        return projected
    }

    private fun updateIsFreeAccount() {
        val tq = _transferQuotaFlow.value
        val q = _quotaFlow.value
        val free = when {
            tq != null && tq.account.vipStatus.isNotEmpty() -> !tq.account.isPremium
            q != null && q.userType == 1 -> false
            q != null && q.userType == 2 -> true
            else -> null
        }
        if (free != null || (tq == null && q == null)) {
            _isFreeAccountFlow.value = free
        }
    }

    private fun scheduleQuotaReconciliation(delayDuration: Duration = RECONCILE_DEBOUNCE) {
        if (!client.reconcileAccountStateInBackground || _quotaFlow.value == null) return
        client.background.launch {
            reconcileMutex.withLock {
                quotaReconcileJob?.cancel()
                quotaReconcileJob = client.background.launch {
                    delay(delayDuration)
                    runCatching {
                        withApiPriority(ApiRequestPriority.BACKGROUND) {
                            client.getQuota(Duration.ZERO)
                        }
                    }
                }
            }
        }
    }

    private fun scheduleTransferQuotaReconciliation(delayDuration: Duration = RECONCILE_DEBOUNCE) {
        if (!client.reconcileAccountStateInBackground || _transferQuotaFlow.value == null) return
        client.background.launch {
            reconcileMutex.withLock {
                transferReconcileJob?.cancel()
                transferReconcileJob = client.background.launch {
                    delay(delayDuration)
                    runCatching {
                        withApiPriority(ApiRequestPriority.BACKGROUND) {
                            client.getTransferQuota(Duration.ZERO)
                        }
                    }
                }
            }
        }
    }

    private fun scheduleSnapshotSave() {
        client.background.launch {
            reconcileMutex.withLock {
                snapshotSaveJob?.cancel()
                snapshotSaveJob = client.background.launch {
                    delay(SNAPSHOT_SAVE_DEBOUNCE)
                    val q = _quotaFlow.value
                    val tq = _transferQuotaFlow.value
                    val p = _userProfileFlow.value
                    if (q == null && tq == null && p == null) return@launch
                    runCatching {
                        client.sessionStore.saveAccountSnapshot(
                            client.account,
                            AccountSnapshot(
                                quota = q,
                                transferQuota = tq,
                                userProfile = p,
                                updatedAtEpochMs = Clock.System.now().toEpochMilliseconds(),
                            ),
                        )
                    }
                }
            }
        }
    }

    companion object {
        private const val MAX_KNOWN_ENTRIES = 16_384
        private const val MAX_TRACKED_TASKS = 2_048
        private const val MAX_LEDGER_MUTATIONS = 256
        private const val DOWNSTREAM_FLUSH_BYTES = 256L * 1024L
        private const val DOWNSTREAM_RECONCILE_BYTES = 16L * 1024L * 1024L
        private val DOWNSTREAM_FLUSH_INTERVAL = 200.milliseconds
        private val DOWNSTREAM_RECONCILE_DEBOUNCE = 2.seconds
        private val RECONCILE_DEBOUNCE = 1200.milliseconds
        private val WRITE_LAG_WINDOW = 8.seconds
        private val WRITE_LAG_RETRY_DELAY = 2.seconds
        private val SNAPSHOT_SAVE_DEBOUNCE = 200.milliseconds
    }
}
