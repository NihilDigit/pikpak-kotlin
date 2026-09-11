# Design notes, 2026-09-10

Why the acceleration layer looks the way it does, written down so it can be
refactored without rediscovering the measurements.

Every item is marked:

- **Fact** — a measured property of PikPak. Changing the code around it is
  fine; contradicting it is not.
- **Shape** — a decision taken to fit Animeko, the first consumer. Load-bearing
  for that consumer, arbitrary for the library. These are the ones to revisit.

The layer was lifted out of Animeko, where it had been through one rewrite and
about a week of live playback. That is why it works and also why it is
overfitted: the API is the shape Animeko's `TorrentFileEntry` needed, not the
shape a cloud-drive SDK would arrive at on its own.

---

## 1. Connection limits

**Fact.** One signed URL accepts 8 concurrent connections. The ninth is
answered 503. Measured 2026-09-02, and again on 2026-09-10.

**Fact.** A second, wider limit applies per account, not per host. Measured
2026-09-10 against a 54-file pack spread over 12 edge hosts
(`dl-z01a-0014` … `0050`): 16 concurrent connections were admitted with no
refusal; 32, 64 and 96 requested all settled at exactly **20** admitted. The
excess was refused two ways — 503, and dropping the TLS handshake before any
status line. `DEFAULT_ACCOUNT_CONNECTION_BUDGET = 16` is the largest round
number under that ceiling.

Caveat on the 20: an earlier run without cooldown between rungs showed 16
connections being refused, because the CDN keeps counting a connection for a
while after the client drops it. With 45 s between rungs, 16 passed cleanly.
The 20 was measured in the no-cooldown run, so the true ceiling may be higher.
It was not worth another measurement once 16 was known to be safe.

**Fact.** Per-connection throughput is bounded by the round trip, not by a
server-side rate limit. Evidence: an Amsterdam host that itself reaches
100 Mbps delivers 200 KB/s per connection to a client in China, while a
Hong Kong line saturates on one connection. Same account, same files. Two
independent measurements land near a 64 KB window — 320 KB/s × 200 ms and
200 KB/s × 250 ms — which is the classic no-window-scaling ceiling.

**Fact.** Measured 2026-09-11 on a deliberately throttled link, which is the
case this library exists for and the one a dev machine never sees. Reading the
same file, alternating one connection against eight, three rounds: x4.7, x4.7,
x5.7, mean 0.27 MB/s against 1.34 MB/s. Downloading a 2 MiB prefix: 7077 ms
against 2228 ms, x3.2 — lower than the read figure because a 2 MiB transfer is
too short to amortise the one `getFile` round trip and the TLS handshake.
Playback on the same link: cold open to first byte 4.18 s, steady state
2.35 MB/s, seek to mid-file 2.79 s.

Alternating matters. Single-connection samples on that link ranged 0.18 to
0.95 MB/s within one run, so measuring all of A and then all of B attributes
the drift to the difference between them. `WeakLinkSmokeTest` does the
alternating and asserts nothing about the ratio, because on a short route the
correct answer is "no gain".

Consequences worth keeping:

- More connections is the only lever. Larger range requests do not help; the
  window is the limit, not the request size. This is why the block size is
  chosen for latency and memory, not for throughput.
- 8 is deliberately **not** adaptive. It could be derived from RTT, and an
  earlier draft did. It was dropped because the estimate has to come from the
  first read's time-to-first-byte, which is the least reliable sample there is
  (TLS handshake, cold edge), and estimating low on a bad link is exactly the
  failure the whole layer exists to prevent. Always opening 8 costs a few extra
  requests and, on a seek, up to 8 discarded in-flight blocks instead of 1.

**Invariant.** The per-file gate belongs to the *file*, not to the reader.
`PikPakFileHandle` owns one and passes it into every `RangeReader` it builds,
because closing a reader does not stop the reads already running on it: a
replacement with its own gate lets one signed URL carry up to twice the budget
until the old reads drain, which is the 503 the budget exists to avoid. This is
easy to undo by accident, since a `RangeReader` built any other way correctly
makes its own.

**Shape.** The two gates are acquired per-file first, then account-wide
(`RangeReader.withSlot`). That ordering bounds a reader to `connectionBudget`
account slots, so one busy file cannot starve another. The reverse order
deadlocks nothing but lets a reader hold account slots for reads that are still
queued behind its own gate. If the two-level structure is ever collapsed into
one, this is the property to preserve.

---

## 2. `RangeSource`

**Shape**, but for a reason that will outlive Animeko.

Both readers take a source rather than a `RangeReader`, because
`PikPakFileHandle` retires readers — on signature expiry and when it has to
rebuild the file object — and anything holding an instance it was handed once
keeps reading through a dead one. This was a real bug during integration: the
ported stream reader took a `RangeReader` in its public constructor and would
have silently ignored every swap.

It was called `PikPakByteSource` at first, with a KDoc explaining itself as an
implementation detail of the handle. That was backwards: random access to one
remote file is what the library actually promises, and this is the type that
says so. The code had already noticed — there was an
`internal typealias RangeSource = PikPakByteSource` in the stream reader.

`readBytes` has a default implementation so an implementor only writes `read`.
That exists for the test doubles; `RangeReader` overrides it because it can
fill a caller's array without the intermediate channel.

---

## 3. The gcid path

Measured 2026-09-11. This is the section that changed the library's shape, so
the evidence is written out rather than summarised.

**Fact.** `POST /drive/v1/resource/list`, body
`{"urls": magnet, "page_size": 500, "thumbnail_type": "FROM_HASH"}`, parses a
magnet into a file tree **without creating an offline task and without touching
the drive**, and every leaf carries `meta.hash` — the gcid. Subdirectories are
inlined in `dir.resources`, no cursor between levels. Timed at 150–314 ms.

**Fact.** A gcid creates a file object in one request and zero bytes: the init
request of `upload()` with `hash` and `size` supplied by the caller. Response
`file.phase == PHASE_TYPE_COMPLETE` and no `resumable` node means it worked.
Timed at ~200 ms. This is `new sha` in pikpakcli and the `gcid` early-return in
rclone's `upload()`; it is a normal part of the ecosystem, not an edge.

**Fact.** The three hashes agree. The `meta.hash` from resolving a magnet, the
`hash` on the file an offline download of that same magnet produced, and the
`hash` on a file instant-created from the first — all
`A3C62CB153B61CF265298986AD97EA07A85112DF` for the Arch ISO. Offline products
carry a gcid in 96 of 96 files checked; none empty, all 40 hex.

**Fact.** End to end — magnet in, first CDN byte out — 1.23 s for a 48-file BD
pack, 1.02 s for an 11-episode season pack, 1.03 s for a single episode. An
offline download of content PikPak already holds takes 5–10 s, and content it
does not hold takes minutes or fails.

**Fact.** Content the index has never seen comes back as one placeholder entry:
the info hash as `name`, `file_size` `"0"`, empty `meta.hash`, no `meta.status`.
146 ms to learn this. Third-party code claims a `meta.error` of
`406:E_BT_FILE_NOT_EXIST` also marks it; that field was absent from every miss
observed here, so the empty gcids are the signal and the error field is not
consulted.

**Fact.** An instant upload moved neither `quota.usage` nor `quota.usageInTrash`
across four readings (before, after create, after trash, after permanent
delete). Treat this as "not observed to cost quota" rather than "free":
rclone maps `file_space_not_enough` on this very request to a fatal error, so
the check exists server-side and `about` may simply lag.

**Fact.** A signed link outlives the file object it came from — readable after
trashing and after permanent deletion. Only tested immediately; how long it
survives is unknown.

**Fact.** Transcodes belong to the content, not to the file object. An instant
upload of an already-transcoded gcid arrives with all four variants at t=0;
one with no prior transcode still had only the original at +15 s, +30 s, +60 s
and +120 s, including after its bytes were read. **Nothing here schedules a
transcode**, so a caller that finds only the original must read it or give up —
waiting does not help.

**Shape.** No fallback to an offline download when the index misses. Animeko
has a real BitTorrent engine to fall back to, and a caller without one is
better served by `createUrlFile` directly than by a wait hidden inside a
function that usually returns in a second.

## 3a. `PikPakFileHandle`

**Fact.** PikPak deduplicates by content hash, so an offset already read stays
valid across a rebuild: the replacement is the same bytes.

**Fact.** A signed link carries an expiry, and the CDN also refuses links ahead
of it. Both are handled, and they are not the same event.

The handle holds the **gcid** and treats the file id as a cache of it. The
ladder: expiry re-reads the detail (one request, ~180 ms); a rejection or a 404
on the detail creates a replacement from the gcid and continues (~380 ms).

This is what removed `FileRelocator`. An earlier revision made the caller
supply "find this content again", because only Animeko knew how — it held the
magnet and the path — and `onRelocated` wrote the new id back to its
`meta.json`. With the gcid in hand the SDK rebuilds without asking, and both
the interface and the callback are gone. `CachingVariantLinkSource` went with
them: it cached links keyed by file id, and the thing worth caching turned out
to be the id itself, which the caller can persist as one string next to the
gcid.

**Shape.** The variant is fixed for the life of the handle. This is a
correctness rule for playback (switching variants mid-file changes the bytes
under offsets already read) and a needless restriction for anything else.

**Shape.** Two concurrent rebuilds are allowed to race and orphan one file
object. Serialising them would hold a lock across two round trips on the path
that is already the slow one, to save a row in a drive the caller is evicting
from anyway.

---

## 4. `PikPakStreamReader`

**Fact.** mpv opens a Matroska file by reading the head, seeking to the tail
for the Cues, then seeking back. This is why bytes are cached in slots keyed by
offset with an LRU, not held in a sliding window: a window discards one end on
every one of those three steps, and a small backward seek refetches.

**Fact.** A cold open needs every connection; a settled stream does not. Hence
one block claimed per fetch at the start and after a seek, two once the read
position has a contiguous run ahead of it.

**Bug, fixed.** `readAheadBytes` and `memoryCapBytes` both defaulted to 64 MiB,
and `makeRoom` exempts every slot inside the read-ahead window from eviction.
Equal values meant the window covered the whole cache, the candidate set was
empty, `makeRoom` refused, `claimNext` gave up, and read-ahead collapsed to
"the reader advances one block, the scheduler refills one block". The
documented 64 MiB of depth never existed in the steady state.

The fix is the sizing, not the exemption: every slot inside the window is a
byte this same scheduler asked for, so evicting one only makes `claimNext`
notice the hole and refetch it — an eviction that manufactures its own work.
Read-ahead is now 32 MiB against a 64 MiB cap, with an `init` check that the
cap leaves room for the window plus every slot the workers can hold
(`2 * concurrency + 1` blocks, the `2` because a wide fetch claims two).
The wide-block threshold went 32 MiB → 8 MiB in the same change: at 32 it
equalled the new window, so doubling could only begin once read-ahead was
already complete.

Worth noting how this hid: the existing test asserted `cachedBytes <= cap`,
which is just as true of a stalled window as of a working one. The replacement
asserts the depth actually reached.

**Fixed.** The surface is suspending now — `read`, `seekTo` and the internals
alike — and `runBlocking` is gone from `commonMain`. It used to bridge in two
places, and cost a constraint that could only be stated in prose: callers were
told not to call `read`/`seekTo` from the same dispatcher as
`parentCoroutineContext`, on pain of deadlock. The compiler could not see it
and the tests only avoided it by passing `Dispatchers.Default`. The internal
scheduling did not change; it had always been suspending.

`close` deliberately stayed non-suspending. A player releases its input from a
lifecycle callback with no coroutine, and aborting a parked read from another
thread is a supported way to stop playback. Making it suspend would have cost
`AutoCloseable` as well. It works out because each `Fetch.done` is parented to
the reader's job, so cancelling the scope completes every parked `join()` for
free — without that parent, close would have to walk `inFlight` under the mutex
the workers are still draining under, and that is what would have forced it to
suspend.

**Shape.** Priorities are bare integers, 100 for the block under the read
position and 10 for read-ahead, matching `RangeReader`'s `Int`. Left
hardcoded: their only meaning is the ordering, it has to hold against other
files sharing the account gate too, and a caller given two free integers can
invert it. If this ever needs exposing, the right shape is one per-reader
priority base, which is a multi-file concern and belongs above this class.

**Shape.** The four tuning numbers stay off the public constructor. They are no
longer independent — the `init` check couples cap, read-ahead, block size and
concurrency — so four free parameters would hand a caller an
`IllegalArgumentException` for a relationship nobody told them about. If tuning
is ever needed, expose `memoryCapBytes` alone and derive the rest.

---

## 5. `PikPakFileDownloader` and `RangeSource.downloadTo`

**Fact.** kotlinx-io 0.9.0 has no seekable file handle: `FileSystem` offers
`source`, `sink(append)`, `metadataOrNull`, `delete`, `atomicMove`, and no
truncate. Verified against the 0.9.0 sources, not from memory.

This turned out not to constrain anything. Writing at N offsets would leave
holes, so the file's length would stop being its progress and a bitmap would be
needed again. Fetching `concurrency` blocks and appending them in order keeps
length == progress, which is what makes resume free and what
the deleted `parallelDownloadFromUrl` (temp parts, concatenated at the end) could not offer.

**Removed.** `PikPakFileDownloader` — `start`/`stop`, `downloadedBytes` and
`error` as `StateFlow`, retry forever — was Animeko's `SequentialDownloader`
almost verbatim, and went back to Animeko. It was the one piece here that
introduced a concept PikPak does not have: a background task with a lifecycle.
It also depended on nothing internal (only on `RangeSource` and
`RangeSource.downloadTo`, both public), so moving it was a file move rather than a
rewrite.

Two things it learned that a rewrite must not lose:

Retry must live in exactly one layer. The first version had the outer loop
retrying while `RangeSource.downloadTo` also retried internally with its own backoff,
so `error` stayed null for as long as the inner loop kept trying and a caller
watching it saw a download that had merely stopped moving. Passing
`maxRoundFailures = 1` fixed the symptom by turning the inner retry into dead
code on that path — which is a smell worth revisiting rather than copying.

`start`/`stop` must not be driven by a `Job` the two functions hand between
themselves. The first version assigned `job` from inside a launched coroutine,
so a `stop()` arriving a microsecond later cancelled nothing and the download
ran on. A `MutableStateFlow<Boolean>` collected with `collectLatest` cannot
race.

**Known bug it carried out the door:** `_error` was only cleared after
`RangeSource.downloadTo` returned, and that only happens when the whole file is done.
So a download that failed, retried, made progress and failed again never showed
a null `error` — contradicting both places the KDoc described it. Fix it
wherever it lands.

**Fixed.** `RangeSource.downloadTo` used to fetch `concurrency` blocks, wait for all
of them, append the batch, and only then issue the next batch. Every round
ended with the connections idle behind its slowest block, and that costs most
on exactly the links the fan-out is for: block latency varies most on a weak
route, so the slowest block of a round sits furthest from the mean and the
fan-out degrades from N times to N over the variance.

It is a sliding window now. Writes stay ordered — that is what keeps the file a
valid prefix and its length the progress — but only the writes: the block at
the head of the window is appended as soon as it lands and its replacement is
issued immediately, so a slow block delays the write rather than the next
request. Peak memory is unchanged, since a window holds at most `concurrency`
blocks just as a round did.

One honest limit: the window counts finished-but-unwritten blocks against its
size, so a slow head still leaves the connections behind it idle until it
lands. Removing that too means buffering beyond `concurrency` blocks, which is
memory for throughput and was not worth taking unmeasured.

The regression test puts the slow block last in the first window, which is
where the two models differ: a round blocks on it, a window writes past it.
Reinstating the barrier was tried against that test, and it fails.

---

## 6. `indexMagnet`, removed

It submitted a magnet, polled the offline task to a terminal phase, then walked
the resulting folder — a `Flow<MagnetIndexProgress>` so a caller could show
`phase` and `progress` and give up, because a magnet nobody has uploaded takes
minutes or fails.

The gcid path removed the wait it was built to narrate, so the whole thing went
with it: `pollUntilTerminal`, `taskPollDelay`, `MagnetIndexProgress`,
`OfflineTaskFailedException`, `awaitIndex`, `readMagnetIndex`.

Two problems it had, recorded because they are the kind that come back:

Submission was unconditional, and the KDoc admitted it — a second submit of the
same magnet into a folder already holding its pack produced a second pack. The
suggested guard ("call `readMagnetIndex` first") was not something a caller
could act on: `readMagnetIndex` needs a folder id, and the only way to get one
is `getOrCreateDeepFolderId`, which is the first half of `indexMagnet` and
creates as a side effect. The donor file for one of the probes was named
`archlinux-2026.04.01-x86_64(6).iso` — the seventh copy.

`CreateUrlResult.InstantComplete`, the branch that was supposed to represent
"PikPak already had this", is near-dead code: probing on 2026-09-02 could not
reproduce it, and submitting an already-held magnet twice produced a fresh task
both times. The fast-warm case was really `Queued` plus an immediately terminal
first poll.

`createUrlFile`, `getTask` and `listOfflineTasks` stay as atomic endpoints. The
SDK just no longer strings them together — which restores the line the earlier
revision had deleted from `listOfflineTasks`: the SDK exposes no polling loop,
callers decide when a task is done.

---

## What is not here

`DownloadScheduler` — Animeko's "at most 2 concurrent downloads, yield to
playback" — was not ported. The two-level budget plus priority replaces it, and
does so at the right granularity: connections are the scarce resource, not
downloaders.

`TorrentFileLabel` and the episode-matching rules stay in Animeko. They match
Bangumi episodes to file names, which is not PikPak's business.

Nothing that needs the drive to stay tidy. `instantCreate` leaves a real file
object behind and the SDK never deletes it. Keeping it is the cheaper path —
refreshing an expired link becomes one request instead of two, and a second
playback of the same episode skips resolution entirely — but it does mean the
drive fills up. Eviction is the caller's, and Animeko already has slot
eviction for exactly this.

A "lease" mode that deletes the file object after taking its link was measured
and works (the signed link survives permanent deletion). It was dropped anyway:
it makes every refresh cost a rebuild, and rclone's issue tracker reports that
the server-side record of a deleted file's hash changes within the hour, which
would make the rebuild unreliable in the one situation it is needed.

## The download surface, 0.6.0

Three whole-file modes collapsed into one main path plus one named exception.

`parallelDownloadFromUrl` was deleted. Once `RangeSource.downloadTo` slid its
window, the only thing parallel still had was streaming each part straight to a
temp file instead of holding a window in memory — 4 MiB at the defaults. It paid
for that with no resume and no URL refresh, and its own KDoc admitted the
second: "a signature that expires mid-download fails the whole call", which for
a large file is the normal case rather than an edge.

The names changed hands at the same time. `download` / `downloadFromUrl` were
the single-connection pair and are now `downloadSingleConnection` /
`downloadSingleConnectionFromUrl`; `downloadFromUrl` now means the parallel one.
The rule behind it: the name a reader reaches for without reading the docs
should be the one that is right, and a single connection to this CDN is
round-trip-bound — it is the exception, so it says so in its name.

That renaming is a silent behaviour change for anyone who passed
`downloadFromUrl(url, dest)` positionally. The third parameter was renamed
`expectedSize` → `totalSize` specifically so that the named-argument form fails
to compile instead; the positional form cannot be caught this way and is called
out in the release notes.

The other thing this bought: **the account budget is now a property of the
default path**. Every default way to read bytes goes through `RangeSource`,
hence through `RangeReader`, hence through both gates. The paths that bypass the
budget are exactly the two with `SingleConnection` in the name, which a caller
has to choose deliberately. No acquire had to be pushed down into `sendRaw`,
and the question of whether OSS uploads belong in a download budget never had to
be answered.

## Open: the cold open

4.18 s to the first byte on a throttled link, against a 2.35 MB/s steady state,
and 2.79 s for a seek. Roughly: one `getFile` round trip, a TLS handshake, then
256 KiB at single-connection speed. Three things could move it, in descending
order of what they are worth:

- **`prewarm()` at the call site.** Already exists, removes the `getFile` round
  trip from the critical path — the largest of the three and the only one that
  needs no code here.
- **A smaller first block.** Not expressible as "make the first block small":
  a slot is `offset / blockSize` and the whole cache is keyed on it, so a
  variable block size breaks the addressing. The way that does work is a
  smaller `blockSize` with a larger `slotCount` in the steady state — a fetch
  already spans `slotCount` contiguous slots in one range request, so 64 KiB ×
  4 is the same request as 256 KiB × 1 while a cold 64 KiB × 1 is four times
  quicker to first byte. Costs four times the slots (1024 at the default cap),
  and `init`'s headroom check has to use the new maximum block count instead of
  its hardcoded 2.
- **Whatever the other 1.8 s of that seek is.** A seek needs no `getFile` and
  should reuse the connection, so the arithmetic above only accounts for about
  a second of it. Nobody has instrumented the gap. Do that before the block
  size — if the cost lives there, a smaller first block will not be visible.

## Closed questions

Things that were tried and do not work, so they do not get tried again:

- **Reading content without any file object.** Every link field
  (`web_content_link`, `medias[].link.url`) comes from exactly two endpoints,
  and both take a `file_id`. `resource/list`'s `list_id` and per-resource `id`
  have no follow-up endpoint — `resource/link`, `/detail`, `/play`, `/info`,
  `/file` all 404. PikPak's own landing page stops at preview too.
- **Suppressing the pack subfolder an offline task creates.** The create-task
  body is `kind` / `name` / `parent_id` / `upload_type` / `url` / `folder_type`
  and nothing else, consistent across four independent implementations. There
  is no file-selection field either. The gcid path sidesteps this by not
  creating a task.
- **Server-side search, including by hash.** `filters` accepts `phase`,
  `trashed`, `kind`, `starred`, `modified_time` and nothing else; `name` is
  rejected with 404 under every operator. The web client's own search runs in a
  Web Worker over an IndexedDB cache.
- **Querying tasks by URL or info hash.** The tasks endpoint answers 400 to an
  `id` filter; every implementation that needs this pulls the whole table and
  matches `params.url` client-side. `FileStat.params.url` makes that
  unnecessary for files, which is why the field is modelled now.
