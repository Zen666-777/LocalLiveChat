package jp.mel.chat

import jp.mel.chat.data.SearchText
import org.junit.Assert.*
import org.junit.Test

class MemoryRecallTest {
    @Test fun japaneseDoesNotRequireSpacesAndNormalizesFullWidthLatin() {
        val old = SearchText.terms("紺色の傘を京都駅で渡した。ＡＬＩＣＥもいた").map { it.encoded }.toSet()
        assertTrue(SearchText.terms("傘は何色？ alice？").count { it.encoded in old } >= 3)
    }
    @Test fun rawFtsOperatorsAreNeverInterpolated() {
        assertTrue(SearchText.index("\" OR NEAR * : (傘)🙂").split(" ").all { it.matches(Regex("t[0-9a-f]+")) })
    }
    @Test fun excerptKeepsAnOldDetailAtTheEndOfALongMessage() {
        val text = "静かな町。".repeat(200) + "紺色の傘を渡した🙂" + "それから歩いた。".repeat(50)
        val excerpt = SearchText.excerpt(text, listOf("紺色", "傘"))
        assertTrue(excerpt.contains("紺色の傘"))
        assertTrue(excerpt.length <= 362)
        assertFalse(excerpt.contains('\uFFFD'))
    }
    @Test fun stopWordsAndEmptyQueriesDoNotProduceGenericMatches() {
        assertTrue(SearchText.terms("").isEmpty())
        assertTrue(SearchText.terms("です ます して").isEmpty())
    }
}
