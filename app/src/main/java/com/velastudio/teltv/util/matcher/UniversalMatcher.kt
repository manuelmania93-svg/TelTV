package com.velastudio.teltv.util.matcher

import kotlin.math.max
import kotlin.math.min

/**
 * Finds the next/previous item for chaotic library names: S/E numbering, anime with absolute
 * numbering (S02E18 although only one season exists), date-named shows and bare numbers ("722").
 * Pure functions over small strings: no index, no background work, no persistence.
 */
object UniversalMatcher {
    data class Entry(val id: String, val title: String)

    private data class Slot(val season: Int?, val episode: Int)

    /** Same show if stems are equal, or within a small edit distance for longer stems. */
    fun sameShow(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (min(a.length, b.length) < 6) return false
        val limit = max(1, min(a.length, b.length) / 8)
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        return editDistance(a, b, limit) <= limit
    }

    private fun editDistance(a: String, b: String, cap: Int): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(
                    prev[j] + 1, cur[j - 1] + 1,
                    prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                )
            }
            if (cur.min() > cap) return cap + 1
            prev = cur
        }
        return prev[b.length]
    }

    fun findNext(current: Entry, library: List<Entry>, seasonCountHint: Int? = null): Entry? =
        step(current, library, seasonCountHint, +1)

    fun findPrevious(current: Entry, library: List<Entry>, seasonCountHint: Int? = null): Entry? =
        step(current, library, seasonCountHint, -1)

    private fun step(current: Entry, library: List<Entry>, hint: Int?, dir: Int): Entry? {
        val cur = MediaIdentityParser.parse(current.title)
        return when (cur.kind) {
            MediaIdentity.Kind.DATED -> stepDated(current, cur, library, dir)
            MediaIdentity.Kind.EPISODE -> stepEpisode(current, cur, library, hint, dir)
            else -> null
        }
    }

    private fun stepDated(current: Entry, cur: MediaIdentity, library: List<Entry>, dir: Int): Entry? {
        var best: Entry? = null
        var bestKey = 0
        for (e in library) {
            if (e.id == current.id) continue
            val id = MediaIdentityParser.parse(e.title)
            if (id.kind != MediaIdentity.Kind.DATED || !sameShow(id.stem, cur.stem)) continue
            val k = id.dateKey!!
            val ok = if (dir > 0) k > cur.dateKey!! else k < cur.dateKey!!
            if (!ok) continue
            val better = best == null || (if (dir > 0) k < bestKey else k > bestKey)
            if (better) { best = e; bestKey = k }
        }
        return best
    }

    private fun stepEpisode(
        current: Entry, cur: MediaIdentity, library: List<Entry>, hint: Int?, dir: Int
    ): Entry? {
        val group = ArrayList<Pair<Entry, MediaIdentity>>()
        group.add(current to cur)
        for (e in library) {
            if (e.id == current.id) continue
            val id = MediaIdentityParser.parse(e.title)
            if (id.kind == MediaIdentity.Kind.EPISODE && sameShow(id.stem, cur.stem)) group.add(e to id)
        }
        val slots = resolveSlots(group.map { it.second }, hint)
        val curSlot = slots[0]
        val bySlot = HashMap<Slot, Entry>()
        for (i in 1 until group.size) bySlot.putIfAbsent(slots[i], group[i].first)

        if (dir > 0) {
            bySlot[Slot(curSlot.season, curSlot.episode + 1)]?.let { return it }
            if (curSlot.season != null) {
                bySlot[Slot(curSlot.season + 1, 1)]?.let { return it }
                bySlot[Slot(curSlot.season + 1, 0)]?.let { return it }
            }
        } else {
            if (curSlot.episode > 1) bySlot[Slot(curSlot.season, curSlot.episode - 1)]?.let { return it }
            if (curSlot.season != null && curSlot.season > 1) {
                var finale: Slot? = null
                for (s in bySlot.keys) {
                    if (s.season == curSlot.season - 1 && (finale == null || s.episode > finale.episode)) finale = s
                }
                finale?.let { return bySlot[it] }
            }
        }
        return null
    }

    /**
     * Decide per group how numbers are read:
     * - absolute mode (anime): all numbers are one running count, seasons are ignored. Used when
     *   [seasonCountHint] is 1, when only seasons > 1 appear (orphan "S02E18"), or when nothing
     *   carries a season.
     * - seasoned mode: S/E as written; bare numbers like 722 decode to S7E22 when that season is
     *   plausible for the group.
     */
    private fun resolveSlots(ids: List<MediaIdentity>, hint: Int?): List<Slot> {
        val seasons = ids.mapNotNull { it.season }.toSortedSet()
        // A bare "722" next to explicit S07 files is evidence the numbering is season-based.
        val compactConfirmsSeasons = ids.any {
            it.season == null && it.bareNumber && it.episode!! in 101..9999 &&
                it.episode % 100 != 0 && (it.episode / 100) in seasons
        }
        val absolute = seasons.isEmpty() || hint == 1 ||
            (seasons.first() > 1 && seasons.size == 1 && !compactConfirmsSeasons)
        if (absolute) return ids.map { Slot(null, it.episode!!) }
        val maxSeason = seasons.last()
        return ids.map { id ->
            val ep = id.episode!!
            if (id.season == null && id.bareNumber && ep in 101..9999 && ep % 100 != 0) {
                val s = ep / 100
                if (s <= maxSeason + 1) return@map Slot(s, ep % 100)
            }
            Slot(id.season, ep)
        }
    }
}
