package com.gobo.app.net

import com.gobo.app.board.Stone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PuzzlesTest {

    // --- decodePuzzleStones -------------------------------------------------

    @Test
    fun decodesPackedCoordinates() {
        // "dadbdc" = (d,a),(d,b),(d,c) = (3,0),(3,1),(3,2)
        assertEquals(listOf(3 to 0, 3 to 1, 3 to 2), decodePuzzleStones("dadbdc"))
    }

    @Test
    fun decodeSkipsPassPlaceholdersAndTrailingOddChar() {
        // OGS pads some initial states with ".." pass placeholders; a stray trailing char is ignored.
        assertEquals(listOf(6 to 2, 3 to 3), decodePuzzleStones("....gcddx"))
        assertTrue(decodePuzzleStones("").isEmpty())
    }

    // --- parsePuzzleCollections ---------------------------------------------

    @Test
    fun parsesCollectionsWithDefaults() {
        val cols = parsePuzzleCollections(
            """
            {"count":2,"results":[
              {"id":10,"name":"  Tesuji  ","puzzle_count":12,"min_rank":0,"max_rank":34,
               "rating":4.5,"rating_count":698,"view_count":48098,"solved_count":23400,
               "created":"2025-11-04T08:23:13Z","owner":{"username":"  Lee  "},
               "starting_puzzle":{"id":555,"width":19,"height":19}},
              {"id":11}
            ]}
            """.trimIndent(),
        )
        assertEquals(2, cols.size)
        assertEquals(10L, cols[0].id)
        assertEquals("Tesuji", cols[0].name) // trimmed
        assertEquals(12, cols[0].puzzleCount)
        assertEquals(555L, cols[0].startingPuzzleId)
        assertEquals(34, cols[0].maxRank)
        assertEquals(4.5, cols[0].rating, 0.0001)
        assertEquals(698, cols[0].ratingCount)
        assertEquals(48098, cols[0].viewCount)
        assertEquals(23400, cols[0].solvedCount)
        assertEquals("2025-11-04T08:23:13Z", cols[0].created)
        assertEquals("Lee", cols[0].ownerName) // trimmed
        // Sparse entry falls back to defaults.
        assertEquals("Untitled", cols[1].name)
        assertEquals(0, cols[1].puzzleCount)
        assertEquals(0L, cols[1].startingPuzzleId)
        assertEquals(19, cols[1].width)
        assertEquals(0.0, cols[1].rating, 0.0001)
        assertEquals("", cols[1].created)
        assertEquals("", cols[1].ownerName)
    }

    // --- display formatters -------------------------------------------------

    @Test
    fun formatsDifficultyAsRankOrRange() {
        assertEquals("30k", formatDifficulty(0, 0))   // single rank when both ends equal
        assertEquals("30k–5d", formatDifficulty(0, 34))
        assertEquals("5k–1d", formatDifficulty(25, 30)) // rank 30 = 1 dan
        assertEquals("25k–9k", formatDifficulty(5, 21))
    }

    @Test
    fun formatsCreatedDate() {
        assertEquals("Nov 2025", formatPuzzleDate("2025-11-04T08:23:13Z"))
        assertEquals("Jun 2015", formatPuzzleDate("2015-06-18"))
        assertEquals("", formatPuzzleDate(""))       // no date -> omitted
        assertEquals("", formatPuzzleDate("garbage"))
    }

    @Test
    fun compactsCounts() {
        assertEquals("893", formatCount(893))
        assertEquals("1.3K", formatCount(1341))
        assertEquals("48.1K", formatCount(48098))
        assertEquals("11.1M", formatCount(11125431))
        assertEquals("0", formatCount(-5))           // clamps negatives
    }

    @Test
    fun parsesCollectionsBareArrayAndSkipsEntriesWithoutId() {
        val cols = parsePuzzleCollections("""[{"name":"x"},{"id":7,"name":"ok"}]""")
        assertEquals(1, cols.size)
        assertEquals(7L, cols[0].id)
    }

    @Test
    fun collectionsEmptyOnUnexpectedShape() {
        assertTrue(parsePuzzleCollections("""{"foo":1}""").isEmpty())
        assertTrue(parsePuzzleCollections("""123""").isEmpty())
    }

    // --- parseCollectionSummary ---------------------------------------------

    @Test
    fun parsesOrderedSummary() {
        val refs = parseCollectionSummary("""[{"id":1,"name":"a"},{"id":2,"name":" b "},{"name":"no-id"}]""")
        assertEquals(2, refs.size)
        assertEquals(PuzzleRef(1L, "a"), refs[0])
        assertEquals("b", refs[1].name) // trimmed
    }

    // --- parsePuzzle --------------------------------------------------------

    private val sample = """
        {"id":19357,"name":"  Kill black  ",
         "puzzle":{
           "puzzle_type":"life_and_death","width":19,"height":19,
           "initial_state":{"white":"dadb","black":"cacb"},
           "puzzle_description":"  kill black  ",
           "initial_player":"white","puzzle_collection":2571,
           "move_tree":{"x":-1,"y":-1,"branches":[
             {"x":0,"y":1,"wrong_answer":true},
             {"x":1,"y":0,"branches":[
               {"x":2,"y":2,"branches":[
                 {"x":0,"y":0,"correct_answer":true}
               ]}
             ]}
           ]}
         }}
    """.trimIndent()

    @Test
    fun parsesPuzzleFields() {
        val p = parsePuzzle(sample)!!
        assertEquals(19357L, p.id)
        assertEquals("Kill black", p.name)         // trimmed
        assertEquals("kill black", p.description)  // trimmed
        assertEquals(Stone.WHITE, p.playerColor)   // initial_player: white
        assertEquals(2571L, p.collectionId)
    }

    @Test
    fun decodesInitialStones() {
        val p = parsePuzzle(sample)!!
        // white "dadb" = (3,0),(3,1); black "cacb" = (2,0),(2,1)
        assertEquals(listOf(3 to 0, 3 to 1), p.initialWhite)
        assertEquals(listOf(2 to 0, 2 to 1), p.initialBlack)
    }

    @Test
    fun parsesMoveTreeAndDefaultsPlayerColorToBlack() {
        val p = parsePuzzle(sample)!!
        val root = p.moveTree
        assertEquals(-1, root.x)
        assertEquals(2, root.branches.size)
        assertTrue(root.branches[0].wrong)
        // Deep correct leaf reachable via the second branch.
        assertTrue(root.branches[1].branches[0].branches[0].correct)

        // No initial_player -> defaults to Black.
        val noPlayer = parsePuzzle("""{"id":1,"puzzle":{"move_tree":{"x":-1,"y":-1,"branches":""}}}""")!!
        assertEquals(Stone.BLACK, noPlayer.playerColor)
    }

    @Test
    fun emptyBranchesStringNormalizesToNoChildren() {
        // OGS serializes an empty branch list as a string ("" / whitespace), not [].
        val p = parsePuzzle("""{"id":1,"puzzle":{"move_tree":{"x":-1,"y":-1,"branches":"  "}}}""")!!
        assertTrue(p.moveTree.branches.isEmpty())
    }

    @Test
    fun puzzleNullWhenMalformed() {
        assertNull(parsePuzzle("""{"name":"no id"}"""))
        assertNull(parsePuzzle("""{"id":5}""")) // no puzzle block
    }

    // --- puzzleStep (solver walk) -------------------------------------------

    @Test
    fun offTreeTapWhenNoBranchMatches() {
        val root = parsePuzzle(sample)!!.moveTree
        assertEquals(PuzzleStep.OffTree, puzzleStep(root, 9, 9))
    }

    @Test
    fun wrongBranchFails() {
        val root = parsePuzzle(sample)!!.moveTree
        val step = puzzleStep(root, 0, 1)
        assertTrue(step is PuzzleStep.Wrong)
        assertEquals(0 to 1, (step as PuzzleStep.Wrong).playerMove)
    }

    @Test
    fun continuationAdvancesAndPlaysOpponentReply() {
        val root = parsePuzzle(sample)!!.moveTree
        // (1,0) is a correct continuation; the opponent replies (2,2), advancing to that node.
        val step = puzzleStep(root, 1, 0)
        assertTrue(step is PuzzleStep.Continue)
        step as PuzzleStep.Continue
        assertEquals(1 to 0, step.playerMove)
        assertEquals(2 to 2, step.opponentMove)
        // From the opponent's node, the player's solving move is (0,0).
        val solving = puzzleStep(step.next, 0, 0)
        assertTrue(solving is PuzzleStep.Solved)
        assertEquals(0 to 0, (solving as PuzzleStep.Solved).playerMove)
    }

    @Test
    fun correctLeafSolvesImmediately() {
        // A direct correct branch off the root solves without an opponent reply.
        val root = parsePuzzle(
            """{"id":1,"puzzle":{"move_tree":{"x":-1,"y":-1,"branches":[{"x":3,"y":3,"correct_answer":true}]}}}""",
        )!!.moveTree
        val step = puzzleStep(root, 3, 3)
        assertTrue(step is PuzzleStep.Solved)
    }

    @Test
    fun hintsAreTheNonWrongBranches() {
        val root = parsePuzzle(sample)!!.moveTree
        // Root branches: (0,1) wrong + (1,0) continuation -> hint is just the non-wrong one.
        assertEquals(listOf(1 to 0), puzzleHints(root))

        // A node offering two correct first moves hints both; a wrong one is excluded.
        val multi = parsePuzzle(
            """{"id":1,"puzzle":{"move_tree":{"x":-1,"y":-1,"branches":[
                 {"x":2,"y":2,"correct_answer":true},
                 {"x":3,"y":3,"branches":""},
                 {"x":4,"y":4,"wrong_answer":true}
               ]}}}""",
        )!!.moveTree
        assertEquals(listOf(2 to 2, 3 to 3), puzzleHints(multi))
    }

    @Test
    fun deadEndLineWithoutReplySolves() {
        // A non-wrong, non-correct continuation that has no opponent reply is the end of the line.
        val root = parsePuzzle(
            """{"id":1,"puzzle":{"move_tree":{"x":-1,"y":-1,"branches":[{"x":4,"y":4,"branches":""}]}}}""",
        )!!.moveTree
        val step = puzzleStep(root, 4, 4)
        assertTrue(step is PuzzleStep.Solved)
    }
}
