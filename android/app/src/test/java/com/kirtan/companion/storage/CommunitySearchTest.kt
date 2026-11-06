package com.kirtan.companion.storage

import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The community search filter wiring and the rating average.
 *
 * Both are pure, so they are tested without a network or a client. The filter
 * shape matters because PostgREST's `or=(…)` syntax is easy to get subtly wrong
 * (missing parentheses, wrong separator) and a malformed filter fails the whole
 * browse rather than degrading to "no results".
 */
class CommunitySearchTest {

    @Test
    fun `an empty query sends no filters at all`() {
        for (scope in CommunityClient.BrowseScope.entries) {
            assertEquals(emptyList<Pair<String, String>>(), browseFilters("", scope))
            assertEquals(emptyList<Pair<String, String>>(), browseFilters("   ", scope))
        }
    }

    @Test
    fun `name scope filters only the name column`() {
        assertEquals(
            listOf("name" to "ilike.%dadra%"),
            browseFilters("dadra", CommunityClient.BrowseScope.NAMES),
        )
    }

    @Test
    fun `author scope filters only the author column`() {
        assertEquals(
            listOf("author_name" to "ilike.%sitapati%"),
            browseFilters("sitapati", CommunityClient.BrowseScope.AUTHORS),
        )
    }

    @Test
    fun `everything scope is one or-filter over both columns`() {
        assertEquals(
            listOf("or" to "(name.ilike.%te%,author_name.ilike.%te%)"),
            browseFilters("te", CommunityClient.BrowseScope.EVERYTHING),
        )
    }

    @Test
    fun `the query is trimmed before it reaches the filter`() {
        assertEquals(
            browseFilters("dadra", CommunityClient.BrowseScope.NAMES),
            browseFilters("  dadra  ", CommunityClient.BrowseScope.NAMES),
        )
    }

    @Test
    fun `the average is null until somebody rates`() {
        val unrated = item(ratingSum = 0, ratingCount = 0)
        assertNull(unrated.averageStars)

        // Zero sum WITH a count is a real one-star-and-up average of zero only
        // if someone rated zero, which the check constraint forbids — so a zero
        // sum with a count can only mean the data is odd, and we still divide.
        val rated = item(ratingSum = 9, ratingCount = 2)
        assertEquals(4.5, rated.averageStars!!, 0.0)
    }

    private fun item(ratingSum: Long, ratingCount: Long) = PublishedItem(
        id = "x",
        authorId = null,
        authorName = null,
        kind = PublishedKind.BEAT,
        name = "n",
        payload = JsonObject(emptyMap()),
        copies = 0,
        ratingSum = ratingSum,
        ratingCount = ratingCount,
        createdAt = null,
    )
}
