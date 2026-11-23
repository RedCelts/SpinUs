package com.oniel.music.data

import java.io.File
import java.time.Instant
import java.time.ZoneId

/**
 * JVM unit tests for the pure data-layer logic (Scoring, Lyrics,
 * PlayLog). No Android classes, no network. Run with kotlinc + java:
 *
 *   kotlinc <sources> DataLayerTest.kt -d /tmp/data-test
 *   java -Duser.timezone=UTC -cp /tmp/data-test:... com.oniel.music.data.DataLayerTestKt
 *
 * A tiny assert harness is used instead of JUnit so the tests run with
 * the plain Kotlin compiler and nothing else.
 */

private var passed = 0
private var failed = 0
private val failures = mutableListOf<String>()

private fun check(name: String, condition: Boolean) {
    if (condition) {
        passed++
    } else {
        failed++
        failures.add(name)
        println("FAIL: $name")
    }
}

private fun checkEq(name: String, expected: Any?, actual: Any?) {
    check("$name (expected=$expected, actual=$actual)", expected == actual)
}

private fun checkNear(name: String, expected: Double, actual: Double, eps: Double = 1e-9) {
    check("$name (expected=$expected, actual=$actual)", kotlin.math.abs(expected - actual) < eps)
}

private fun track(
    id: String,
    albumId: String?,
    album: String,
    playCount: Int = 0,
    lastPlayedDate: String? = null,
    isFavorite: Boolean = false
) = Track(
    id = id, title = "Track $id", artists = listOf("Artist"), album = album,
    albumId = albumId, albumArtist = "Artist", runTimeTicks = 200_000_000L,
    imageTag = null, playCount = playCount, lastPlayedDate = lastPlayedDate,
    isFavorite = isFavorite
)

private fun album(
    id: String,
    lastPlayedDate: String? = null,
    playCount: Int = 0,
    premiereDate: String? = null
) = Album(
    id = id, name = "Album $id", artist = "Artist", year = 2026,
    premiereDate = premiereDate, dateCreated = null, imageTag = null,
    childCount = 3, lastPlayedDate = lastPlayedDate, playCount = playCount
)

// "Now" for the tests: Sunday 2026-11-22 12:00 UTC.
private val NOW_MS: Long = Instant.parse("2026-11-22T12:00:00Z").toEpochMilli()
private val UTC: ZoneId = ZoneId.of("UTC")

fun main() {
    testScoringBasics()
    testScoreTiles()
    testRediscover()
    testNewReleases()
    testLyricsClassify()
    testPlayLogWeeks()
    testPlayLogPersistence()
    testCrossfade()
    testAuthHeaderValue()
    println("----")
    println("passed=$passed failed=$failed")
    if (failures.isNotEmpty()) {
        println("failures:")
        failures.forEach { println(" - $it") }
    }
    if (failed > 0) kotlin.system.exitProcess(1)
}

private fun testScoringBasics() {
    checkNear("freq 0 plays = 0", 0.0, Scoring.frequencyScore(0))
    checkNear("freq 20 plays = 1", 1.0, Scoring.frequencyScore(20))
    checkNear("freq 100 plays caps at 1", 1.0, Scoring.frequencyScore(100))
    check("freq 1 play in (0,1)", Scoring.frequencyScore(1) in 0.0..1.0)

    checkNear("recency today = 1", 1.0, Scoring.recencyScore(0))
    checkNear("recency 7 days = 0.5", 0.5, Scoring.recencyScore(7))
    check("recency 90 days < 0.1", Scoring.recencyScore(90) < 0.1)

    checkEq("daysAgo parses ISO", 2L, Scoring.daysAgo("2026-11-20T12:00:00.0000000Z", NOW_MS))
    checkEq("daysAgo null for missing", null, Scoring.daysAgo(null, NOW_MS))
    checkEq("daysAgo null for garbage", null, Scoring.daysAgo("not-a-date", NOW_MS))
    checkEq("daysAgo never negative", 0L, Scoring.daysAgo("2026-11-23T12:00:00Z", NOW_MS))

    // Hour boost: last played 2026-11-21 11:00 UTC, now 12:00 -> within 2h.
    checkNear(
        "hour boost within 2h",
        0.15,
        Scoring.hourBoost(Instant.parse("2026-11-21T11:00:00Z").toEpochMilli(), NOW_MS)
    )
    // Last played 2026-11-21 01:00 UTC, now 12:00 -> 11h apart, no boost.
    checkNear(
        "hour boost none at 11h",
        0.0,
        Scoring.hourBoost(Instant.parse("2026-11-21T01:00:00Z").toEpochMilli(), NOW_MS)
    )
    // Wrap-around: last played 23:00, now 00:30 next day -> 1.5h apart.
    checkNear(
        "hour boost wraps midnight",
        0.15,
        Scoring.hourBoost(
            Instant.parse("2026-11-21T23:00:00Z").toEpochMilli(),
            Instant.parse("2026-11-22T00:30:00Z").toEpochMilli()
        )
    )

    checkEq("trackScore never played, no favorite", 0.0, Scoring.trackScore(track("x", "a1", "A"), NOW_MS))
    check("trackScore never played favorite > 0", Scoring.trackScore(track("x", "a1", "A", isFavorite = true), NOW_MS) > 0)
    checkEq("trackScore >90 days = -1", -1.0, Scoring.trackScore(track("x", "a1", "A", 5, "2026-08-01T12:00:00Z"), NOW_MS))
    check("trackScore recent > stale", Scoring.trackScore(track("a", "a1", "A", 1, "2026-11-22T10:00:00Z"), NOW_MS) >
        Scoring.trackScore(track("b", "a2", "B", 1, "2026-10-01T12:00:00Z"), NOW_MS))
    check("trackScore frequency beats recency at same age", Scoring.trackScore(track("a", "a1", "A", 20, "2026-11-20T12:00:00Z"), NOW_MS) >
        Scoring.trackScore(track("b", "a2", "B", 1, "2026-11-20T12:00:00Z"), NOW_MS))
}

private fun testScoreTiles() {
    val tracks = listOf(
        track("t1", "a1", "Hot Album", playCount = 10, lastPlayedDate = "2026-11-22T10:00:00Z"),
        track("t2", "a1", "Hot Album", playCount = 5, lastPlayedDate = "2026-11-21T10:00:00Z"),
        track("t3", "a2", "Stale Album", playCount = 1, lastPlayedDate = "2026-08-01T12:00:00Z"),
        track("t4", "a3", "Never Album", playCount = 0, lastPlayedDate = null),
        track("t5", "a4", "Liked Album", playCount = 0, lastPlayedDate = null, isFavorite = true),
        track("t6", "a5", "Mid Album", playCount = 3, lastPlayedDate = "2026-11-18T12:00:00Z")
    )
    val tiles = Scoring.scoreTiles(tracks, NOW_MS)
    checkEq("scoreTiles excludes stale + never-played", 2, tiles.size)
    checkEq("scoreTiles hottest album first", "a1", tiles[0].albumId)
    checkEq("scoreTiles one tile per album", 1, tiles.count { it.albumId == "a1" })
    check("scoreTiles liked-but-never-played gets no pinned slot", tiles.none { it.albumId == "a4" })

    // Cap at 8 tiles.
    val many = (1..12).map { i ->
        track("m$i", "al$i", "Album $i", playCount = i, lastPlayedDate = "2026-11-22T09:00:00Z")
    }
    checkEq("scoreTiles caps at 8", 8, Scoring.scoreTiles(many, NOW_MS).size)
    checkEq("scoreTiles empty in -> empty out", 0, Scoring.scoreTiles(emptyList(), NOW_MS).size)
}

private fun testRediscover() {
    val albums = listOf(
        album("recent", lastPlayedDate = "2026-11-20T12:00:00Z", playCount = 5),
        album("old", lastPlayedDate = "2026-06-01T12:00:00Z", playCount = 3),
        album("older", lastPlayedDate = "2026-01-15T12:00:00Z", playCount = 9),
        album("never", lastPlayedDate = null, playCount = 0),
        album("edge", lastPlayedDate = "2026-08-24T12:00:00Z", playCount = 1) // exactly 90 days ago
    )
    val found = Scoring.rediscoverAlbums(albums, NOW_MS)
    checkEq("rediscover excludes recent + exactly-90-days", 3, found.size)
    checkEq("rediscover never-played first", "never", found[0].id)
    checkEq("rediscover oldest last-play next", "older", found[1].id)
    checkEq("rediscover then less old", "old", found[2].id)

    val many = (1..25).map { i -> album("n$i") }
    checkEq("rediscover caps at 20", 20, Scoring.rediscoverAlbums(many, NOW_MS).size)
}

private fun testNewReleases() {
    val albums = listOf(
        album("new1", premiereDate = "2026-11-01T00:00:00Z"),
        album("new2", premiereDate = "2026-10-15T00:00:00Z"),
        album("old", premiereDate = "2026-01-01T00:00:00Z"),
        album("nodate", premiereDate = null),
        album("future", premiereDate = "2027-01-01T00:00:00Z"),
        album("edge", premiereDate = "2026-08-24T00:00:00Z") // exactly 90 days before Nov 22
    )
    val releases = Scoring.newReleases(albums, NOW_MS)
    checkEq("newReleases only within 90 days", listOf("new1", "new2", "edge"), releases.map { it.id })
    checkEq("newReleases newest first", "new1", releases[0].id)
    checkEq("newReleases empty in -> empty out", 0, Scoring.newReleases(emptyList(), NOW_MS).size)
}

private fun testLyricsClassify() {
    val synced = listOf(
        RawLyricLine("First synced line", 10_000_000L),
        RawLyricLine("Second synced line", 50_000_000L),
        RawLyricLine("Third synced line", 100_000_000L),
        RawLyricLine("Fourth synced line", 150_000_000L)
    )
    val out = Lyrics.classify(synced)
    checkEq("synced keeps 4 lines", 4, out.size)
    checkEq("synced startMs converts ticks", 1000L, out[0].startMs)
    checkEq("synced last startMs", 15000L, out[3].startMs)

    // Out of order input comes back sorted.
    val shuffled = listOf(
        RawLyricLine("late", 100_000_000L),
        RawLyricLine("early", 10_000_000L)
    )
    checkEq("synced sorts by start", listOf("early", "late"), Lyrics.classify(shuffled).map { it.text })

    // Plain lyrics: no timestamps at all.
    val plain = listOf(
        RawLyricLine("First plain line", null),
        RawLyricLine("Second plain line", null),
        RawLyricLine("", null) // trailing empty line Jellyfin appends
    )
    checkEq("plain lyrics -> empty list", emptyList<LyricLine>(), Lyrics.classify(plain))

    // Only one timed line is not enough to call it synced.
    checkEq(
        "one timed line -> empty",
        emptyList<LyricLine>(),
        Lyrics.classify(listOf(RawLyricLine("only", 10_000_000L), RawLyricLine("plain", null)))
    )
    // Two lines with the SAME start time are not synced either.
    checkEq(
        "same start twice -> empty",
        emptyList<LyricLine>(),
        Lyrics.classify(listOf(RawLyricLine("a", 10_000_000L), RawLyricLine("b", 10_000_000L)))
    )
    // Blank lines are dropped before counting.
    checkEq(
        "blank lines dropped",
        2,
        Lyrics.classify(listOf(RawLyricLine("a", 10_000_000L), RawLyricLine("  ", 20_000_000L), RawLyricLine("b", 30_000_000L))).size
    )
    checkEq("empty input -> empty", emptyList<LyricLine>(), Lyrics.classify(emptyList()))
}

private fun testPlayLogWeeks() {
    val dir = createTempDir("playlog-weeks")
    val log = PlayLog(dir)
    // Week of Mon Nov 16 – Sun Nov 22, 2026 (NOW is Sun Nov 22 12:00 UTC).
    val mon = Instant.parse("2026-11-16T10:00:00Z").toEpochMilli()
    val wed = Instant.parse("2026-11-18T20:00:00Z").toEpochMilli()
    val sun = Instant.parse("2026-11-22T09:00:00Z").toEpochMilli()
    // Previous week: Mon Nov 9 – Sun Nov 15.
    val prevTue = Instant.parse("2026-11-10T10:00:00Z").toEpochMilli()

    log.logPlay("t1", "Song A", "Artist X", "Album 1", 180_000, mon)
    log.logPlay("t1", "Song A", "Artist X", "Album 1", 180_000, wed)
    log.logPlay("t2", "Song B", "Artist Y", "Album 2", 240_000, sun)
    log.logPlay("t3", "Song C", "Artist X", "Album 3", 60_000, prevTue)

    val weeks = log.getWeeks()
    checkEq("two weeks logged", 2, weeks.size)
    val current = weeks[0]
    checkEq("current week label", "Nov 16 – Nov 22", current.label)
    checkEq("current week plays", 3, current.plays)
    checkEq("current week minutes", 10, current.minutes) // (180+180+240)s = 600s = 10 min
    checkEq("current week top song", "Song A", current.topSong)
    checkEq("current week top artist", "Artist X", current.topArtist)
    checkEq("previous week label", "Nov 9 – Nov 15", weeks[1].label)
    checkEq("previous week plays", 1, weeks[1].plays)
    checkEq("previous week minutes", 1, weeks[1].minutes)

    // Week boundaries: Sunday belongs to the week starting the previous Monday.
    checkEq(
        "Sunday maps to Monday start",
        Instant.parse("2026-11-16T00:00:00Z").toEpochMilli(),
        log.weekStartMs(Instant.parse("2026-11-22T23:59:59Z").toEpochMilli(), UTC)
    )
    checkEq(
        "Monday maps to itself",
        Instant.parse("2026-11-16T00:00:00Z").toEpochMilli(),
        log.weekStartMs(Instant.parse("2026-11-16T00:00:01Z").toEpochMilli(), UTC)
    )
    checkEq(
        "next Monday starts a new week",
        Instant.parse("2026-11-23T00:00:00Z").toEpochMilli(),
        log.weekStartMs(Instant.parse("2026-11-23T00:00:00Z").toEpochMilli(), UTC)
    )

    checkEq("empty log -> no weeks", emptyList<WeekStats>(), PlayLog(createTempDir("playlog-empty")).getWeeks())
    dir.deleteRecursively()
}

private fun testPlayLogPersistence() {
    val dir = createTempDir("playlog-persist")
    val log = PlayLog(dir)
    // Special characters must survive a write/read round-trip.
    log.logPlay("t\"1", "Song \"Quoted\" ❤️", "Artist \\ Back", "Al\nbum", 200_000, NOW_MS)
    log.logPlay("t2", "Plain", "Artist", "Album", 200_000, NOW_MS)
    val reloaded = PlayLog(dir).getEntries()
    checkEq("round-trip keeps 2 entries", 2, reloaded.size)
    checkEq("round-trip title with quotes + emoji", "Song \"Quoted\" ❤️", reloaded[0].title)
    checkEq("round-trip artist with backslash", "Artist \\ Back", reloaded[0].artist)
    checkEq("round-trip album with newline", "Al\nbum", reloaded[0].album)
    checkEq("round-trip track id", "t\"1", reloaded[0].trackId)

    // Entries older than 84 days are pruned on the next write.
    val old = NOW_MS - 100L * 86_400_000L
    log.logPlay("t-old", "Old Song", "Artist", "Album", 200_000, old)
    log.logPlay("t3", "New Song", "Artist", "Album", 200_000, NOW_MS)
    val afterPrune = PlayLog(dir).getEntries()
    check("old entry pruned", afterPrune.none { it.trackId == "t-old" })
    check("new entries kept after prune", afterPrune.any { it.trackId == "t3" })

    // Cap: a file already holding 5010 entries is trimmed to 5000 on write.
    val bigDir = createTempDir("playlog-cap")
    val many = (1..5010).joinToString(",", "[", "]") { i ->
        """{"t":$NOW_MS,"id":"c$i","n":"S$i","a":"A","al":"Al","d":60000}"""
    }
    File(bigDir, "playlog.json").writeText(many)
    PlayLog(bigDir).logPlay("fresh", "Fresh", "A", "Al", 60000, NOW_MS)
    val capped = PlayLog(bigDir).getEntries()
    checkEq("cap trims to 5000", 5000, capped.size)
    checkEq("cap keeps newest (fresh last)", "fresh", capped.last().trackId)
    check("cap drops oldest first", capped.none { it.trackId == "c1" })

    dir.deleteRecursively()
    bigDir.deleteRecursively()
}

private fun testCrossfade() {
    // Range + default.
    checkEq("crossfade default is 3s", 3, Crossfade.DEFAULT_SECONDS)
    checkEq("crossfade clamps below 0", 0, Crossfade.clampSeconds(-1))
    checkEq("crossfade clamps above 12", 12, Crossfade.clampSeconds(13))
    checkEq("crossfade keeps 12", 12, Crossfade.clampSeconds(12))

    // Start condition: a 200s track, 3s crossfade -> starts at 197s.
    check("crossfade starts at exactly duration-N", Crossfade.shouldStart(197_000, 200_000, 3_000, true))
    check("crossfade starts inside the window", Crossfade.shouldStart(199_500, 200_000, 3_000, true))
    check("crossfade does not start before the window", !Crossfade.shouldStart(196_999, 200_000, 3_000, true))
    check("crossfade does not start at/past the end", !Crossfade.shouldStart(200_000, 200_000, 3_000, true))
    check("crossfade 0s never starts", !Crossfade.shouldStart(199_000, 200_000, 0, true))
    check("crossfade never starts with no next track", !Crossfade.shouldStart(199_000, 200_000, 3_000, false))
    check("crossfade never starts with unknown duration", !Crossfade.shouldStart(199_000, 0, 3_000, true))
    check("crossfade never starts with negative position", !Crossfade.shouldStart(-1, 200_000, 3_000, true))
    // Short track: a 2s track with a 3s crossfade still overlaps from 0.
    check("crossfade starts at 0 on a track shorter than N", Crossfade.shouldStart(0, 2_000, 3_000, true))

    // Volume ramp over 3000ms: outgoing 1 -> 0, incoming 0 -> 1, equal-power.
    val (out0, in0) = Crossfade.volumes(0, 3_000)
    checkNear("ramp start out=1", 1.0, out0.toDouble())
    checkNear("ramp start in=0", 0.0, in0.toDouble())
    val (outMid, inMid) = Crossfade.volumes(1_500, 3_000)
    checkNear("ramp middle out=0.7071", 0.70710678, outMid.toDouble(), 1e-6)
    checkNear("ramp middle in=0.7071", 0.70710678, inMid.toDouble(), 1e-6)
    val (outEnd, inEnd) = Crossfade.volumes(3_000, 3_000)
    checkNear("ramp end out=0", 0.0, outEnd.toDouble())
    checkNear("ramp end in=1", 1.0, inEnd.toDouble())
    // Late ticks clamp: both players are at their finished volumes.
    val (outLate, inLate) = Crossfade.volumes(9_999, 3_000)
    checkNear("ramp late out stays 0", 0.0, outLate.toDouble())
    checkNear("ramp late in stays 1", 1.0, inLate.toDouble())
    // Equal power: out^2 + in^2 = 1 everywhere (no loudness dip mid-fade).
    for (ms in listOf(1L, 400L, 750L, 1_500L, 2_250L, 2_999L)) {
        val (o, i) = Crossfade.volumes(ms, 3_000)
        checkNear("ramp power is 1 at ${ms}ms", 1.0, (o * o + i * i).toDouble(), 1e-6)
    }
    // Outgoing never rises, incoming never falls.
    val steps = (0..30).map { Crossfade.volumes(it * 100L, 3_000) }
    check("ramp out is non-increasing", steps.zipWithNext().all { (a, b) -> b.first <= a.first })
    check("ramp in is non-decreasing", steps.zipWithNext().all { (a, b) -> b.second >= a.second })

    // v1.0.4: quiet-tail early start (240s track).
    check("quiet tail starts early inside last 15s",
        Crossfade.shouldStartOnQuietTail(229_000, 240_000, 900, true, true))
    check("quiet tail ignored before last 15s",
        !Crossfade.shouldStartOnQuietTail(224_000, 240_000, 5_000, true, true))
    check("quiet tail needs 800ms of quiet",
        !Crossfade.shouldStartOnQuietTail(230_000, 240_000, 799, true, true))
    check("quiet tail needs known loudness",
        !Crossfade.shouldStartOnQuietTail(230_000, 240_000, 5_000, true, false))
    check("quiet tail needs a next track",
        !Crossfade.shouldStartOnQuietTail(230_000, 240_000, 5_000, false, true))
    check("quiet tail never past the end",
        !Crossfade.shouldStartOnQuietTail(240_000, 240_000, 5_000, true, true))
    // Quiet = 20 dB under the track average, or under ~-50 dBFS.
    check("quiet: 25 dB under average", Crossfade.isQuiet(0.01f, 0.2f))
    check("not quiet: 6 dB under average", !Crossfade.isQuiet(0.1f, 0.2f))
    check("quiet: near-silence on a soft track", Crossfade.isQuiet(0.002f, 0.015f))
    check("not quiet: soft track at its normal level", !Crossfade.isQuiet(0.015f, 0.015f))
    // Fade length.
    checkEq("fade: full length when time allows", 5_000L, Crossfade.fadeLengthMs(5_000, 220_000, 240_000))
    checkEq("fade: shortened to what is left", 2_000L, Crossfade.fadeLengthMs(5_000, 238_000, 240_000))
    checkEq("fade: never under 1s", 1_000L, Crossfade.fadeLengthMs(5_000, 239_800, 240_000))
    checkEq("fade: 0 when crossfade is off", 0L, Crossfade.fadeLengthMs(0, 230_000, 240_000))
    // Same album plays straight through.
    check("same album", Crossfade.sameAlbum("alb1", "alb1"))
    check("different albums", !Crossfade.sameAlbum("alb1", "alb2"))
    check("unknown album never counts as same", !Crossfade.sameAlbum(null, null))
    check("empty album id never counts as same", !Crossfade.sameAlbum("", ""))
    // Zero-length crossfade is a no-op, never NaN.
    val (o0, i0) = Crossfade.volumes(0, 0)
    check("zero-length ramp is (1,1)", o0 == 1f && i0 == 1f)

    // v1.0.3: duration fallback for streams that report no length.
    checkEq("duration: stream length wins", 200_000L, Crossfade.effectiveDurationMs(200_000, 199_000))
    checkEq("duration: TIME_UNSET falls back to runtime", 199_000L,
        Crossfade.effectiveDurationMs(Long.MIN_VALUE + 1, 199_000))
    checkEq("duration: 0 falls back to runtime", 199_000L, Crossfade.effectiveDurationMs(0, 199_000))
    checkEq("duration: neither known is 0", 0L, Crossfade.effectiveDurationMs(Long.MIN_VALUE + 1, 0))
    check("fallback duration starts the fade in the window",
        Crossfade.shouldStart(196_500, Crossfade.effectiveDurationMs(Long.MIN_VALUE + 1, 199_000), 3_000, true))
}

private fun testAuthHeaderValue() {
    // The exact header the service attaches to ExoPlayer's audio
    // requests: the universal stream endpoint 401s without it.
    val client = JellyfinClient()
    client.restoreSession("http://127.0.0.1:8096", "tok123", "user-1", "Oniel", true)
    val header = client.authHeaderValue()
    check("auth header starts with MediaBrowser Token", header.startsWith("MediaBrowser Token=\"tok123\""))
    check("auth header names the client", header.contains("Client=\"Oniel Music\""))
    check("auth header carries the version", header.contains("Version=\"1.0.7\""))
    check("auth header has a device id", header.contains("DeviceId=\""))
    // The URL keeps api_key too (both together return 200).
    val url = client.audioUrl("track-9")
    check("audio url is the universal endpoint", url.contains("/Audio/track-9/universal"))
    check("audio url keeps api_key", url.contains("api_key=tok123"))
    // v1.0.3: non-MP3 files must direct-stream (length + seeking).
    check("audio url allows flac direct", url.contains("Container=mp3,flac,"))
    check("audio url allows aac in m4a", url.contains("m4a%7Caac"))
    check("audio url transcodes the rest to mp3", url.contains("TranscodingContainer=mp3"))
    check("audio url transcodes over plain http", url.contains("TranscodingProtocol=http"))
    checkEq("backdrop url is null without a tag", null, client.backdropUrl("art-1", null))
    check("backdrop url points at Backdrop/0",
        client.backdropUrl("art-1", "tag9")!!.contains("/Items/art-1/Images/Backdrop/0?"))
}
