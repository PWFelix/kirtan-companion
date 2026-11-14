package com.kirtan.companion.data

import com.kirtan.companion.R

/**
 * The curated tag vocabulary. Fixed for now: free-text tags drift ("keherwa",
 * "kaherva", "8 beat") and can't drive the community filters. Authors tag their
 * own beats (published with the beat); anyone can add PRIVATE tags to any beat.
 *
 * Ids are the wire format, stable forever; labels are localised. A tag id this
 * build doesn't know (added in a later version) is kept on the beat and simply
 * not shown, so nothing is lost on a round trip through an older app.
 * Mirrors ios Sources/data/TagVocabulary.swift and web src/data/tags.js.
 */
object TagVocabulary {
    data class Group(val id: String, val label: Int, val tags: List<Tag>)
    data class Tag(val id: String, val label: Int)

    val groups: List<Group> = listOf(
        Group("tala", R.string.tag_group_tala, listOf(
            Tag("keherwa", R.string.tag_keherwa),
            Tag("dadra", R.string.tag_dadra),
            Tag("jhaptal", R.string.tag_jhaptal),
            Tag("rupak", R.string.tag_rupak),
            Tag("teental", R.string.tag_teental),
        )),
        Group("feel", R.string.tag_group_feel, listOf(
            Tag("steady", R.string.tag_steady),
            Tag("double-time", R.string.tag_double_time),
            Tag("laid-back", R.string.tag_laid_back),
            Tag("build-up", R.string.tag_build_up),
        )),
        Group("use", R.string.tag_group_use, listOf(
            Tag("kirtan", R.string.tag_kirtan),
            Tag("bhajan", R.string.tag_bhajan),
            Tag("arati", R.string.tag_arati),
            Tag("prayers", R.string.tag_prayers),
        )),
        Group("part", R.string.tag_group_part, listOf(
            Tag("main", R.string.tag_main),
            Tag("fill", R.string.tag_fill),
            Tag("turnaround", R.string.tag_turnaround),
            Tag("ending", R.string.tag_ending),
        )),
        Group("level", R.string.tag_group_level, listOf(
            Tag("beginner", R.string.tag_beginner),
            Tag("intermediate", R.string.tag_intermediate),
            Tag("advanced", R.string.tag_advanced),
        )),
        Group("practice", R.string.tag_group_practice, listOf(
            Tag("exercise-beginner", R.string.tag_exercise_beginner),
            Tag("exercise-intermediate", R.string.tag_exercise_intermediate),
            Tag("exercise-advanced", R.string.tag_exercise_advanced),
        )),
    )

    private val byId: Map<String, Tag> = groups.flatMap { it.tags }.associateBy { it.id }

    /** The tag for [id], or null for one this build doesn't know. */
    fun tag(id: String): Tag? = byId[id]

    /** [ids] in vocabulary order, unknown ones dropped — for display. */
    fun known(ids: Collection<String>): List<Tag> =
        groups.flatMap { it.tags }.filter { it.id in ids }
}
