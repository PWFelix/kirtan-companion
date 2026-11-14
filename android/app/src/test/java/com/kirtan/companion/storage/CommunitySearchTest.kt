package com.kirtan.companion.storage

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The community search filter wiring and the browse function's wire ids.
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
    fun `sort and scope wire ids match browse_published in schema sql`() {
        // The function compares these strings; a renamed enum would silently
        // fall through to "newest first".
        assertEquals(listOf("top", "new", "copies"), CommunityClient.BrowseSort.entries.map { it.wireId })
        assertEquals(
            listOf("everything", "names", "authors"),
            CommunityClient.BrowseScope.entries.map { it.wireId },
        )
    }
}
