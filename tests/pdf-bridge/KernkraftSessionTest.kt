package dev.kern.pdfbridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The page-number conversion and answer reading of the session wrapper. Robolectric supplies org.json. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KernkraftSessionTest {

    @Test
    fun a_step_gives_changed_pages_from_zero() {
        val step = KernkraftSession.parseStep(
            JSONObject(
                """{"ok":true,"revision":3,"pages":5,"changed_pages":[1,4,5],"can_undo":true,"can_redo":false,"modified":true}""",
            ),
        )
        assertEquals(listOf(0, 3, 4), step.changedPages)
        assertEquals(5, step.pageCount)
        assertEquals(3L, step.revision)
        assertTrue(step.canUndo)
        assertFalse(step.canRedo)
        assertTrue(step.modified)
    }

    @Test
    fun a_step_without_changed_pages_has_none() {
        val step = KernkraftSession.parseStep(
            JSONObject("""{"ok":true,"revision":0,"pages":2,"changed_pages":[],"can_undo":false,"can_redo":false,"modified":false}"""),
        )
        assertEquals(emptyList<Int>(), step.changedPages)
    }

    @Test
    fun a_rotation_is_sent_with_pages_from_one() {
        val json = PdfOp.Rotate(listOf(0, 2), 90).toJson()
        assertEquals("rotate", json.getString("op"))
        assertEquals(90, json.getInt("degrees"))
        assertEquals("[1,3]", json.getJSONArray("pages").toString())
    }

    @Test
    fun rotating_every_page_sends_no_pages() {
        assertEquals("[]", PdfOp.Rotate(emptyList(), 180).toJson().getJSONArray("pages").toString())
    }

    @Test
    fun a_reorder_is_sent_from_one() {
        assertEquals("[3,1,2]", PdfOp.Reorder(listOf(2, 0, 1)).toJson().getJSONArray("order").toString())
    }

    @Test
    fun a_removal_is_sent_from_one() {
        val json = PdfOp.Remove(listOf(4)).toJson()
        assertEquals("remove", json.getString("op"))
        assertEquals("[5]", json.getJSONArray("pages").toString())
    }

    @Test
    fun an_error_answer_becomes_an_exception_with_its_kind() {
        val failure = runCatching {
            KernkraftPdf.answerOrThrow(JSONObject("""{"ok":false,"kind":"RebuiltNotEditable","message":"rebuilt"}"""))
        }.exceptionOrNull() as PdfEngineException
        assertEquals("RebuiltNotEditable", failure.kind)
        assertEquals("rebuilt", failure.message)
        assertTrue(failure.needsCompactedCopy)
    }

    @Test
    fun other_errors_do_not_offer_a_compacted_copy() {
        val failure = runCatching {
            KernkraftPdf.answerOrThrow(JSONObject("""{"ok":false,"kind":"WrongPassword","message":"no"}"""))
        }.exceptionOrNull() as PdfEngineException
        assertFalse(failure.needsCompactedCopy)
    }

    @Test
    fun text_lines_keep_their_boxes_and_drop_blank_lines() {
        val lines = KernkraftSession.parseLines(
            JSONObject(
                """{"ok":true,"revision":0,"tagged":false,"blocks":[],"lines":[
                    {"block":0,"text":"Hello","rect":[0.1,0.2,0.5,0.25],"glyphs":[]},
                    {"block":0,"text":"  ","rect":[0.1,0.3,0.2,0.35],"glyphs":[]},
                    {"block":1,"text":"World","rect":[0.1,0.4,0.6,0.45],"glyphs":[]}]}""",
            ),
        )
        assertEquals(listOf("Hello", "World"), lines.map { it.text })
        assertEquals(PdfBox(0.1f, 0.2f, 0.5f, 0.25f), lines[0].box)
    }

    @Test
    fun a_page_without_text_has_no_lines() {
        val lines = KernkraftSession.parseLines(JSONObject("""{"ok":true,"revision":0,"tagged":false,"blocks":[],"lines":[]}"""))
        assertEquals(emptyList<PdfTextLineBox>(), lines)
    }

    @Test
    fun a_match_spanning_two_lines_has_two_boxes() {
        val found = KernkraftSession.parseMatches(
            JSONObject("""{"ok":true,"matches":[{"rects":[[0.1,0.1,0.9,0.15],[0.1,0.16,0.3,0.21]]},{"rects":[[0.5,0.5,0.6,0.55]]}]}"""),
        )
        assertEquals(listOf(2, 1), found.map { it.boxes.size })
        assertEquals(PdfBox(0.1f, 0.16f, 0.3f, 0.21f), found[0].boxes[1])
    }

    @Test
    fun links_give_an_address_or_a_page_from_zero_and_skip_the_unresolved() {
        val links = KernkraftSession.parseLinks(
            JSONObject(
                """{"ok":true,"links":[
                    {"rects":[[0.1,0.1,0.2,0.2]],"uri":"https://example.org"},
                    {"rects":[[0.3,0.3,0.4,0.4]],"page":3,"x":0.0,"y":0.0,"zoom":null},
                    {"rects":[[0.5,0.5,0.6,0.6]]}]}""",
            ),
        )
        assertEquals(2, links.size)
        assertEquals("https://example.org", (links[0] as PdfEngineLink.Uri).uri)
        assertEquals(2, (links[1] as PdfEngineLink.Page).page)
    }

    @Test
    fun a_line_keeps_its_glyph_boxes_in_the_order_given() {
        val lines = KernkraftSession.parseLines(
            JSONObject(
                """{"ok":true,"revision":0,"tagged":false,"blocks":[],"lines":[
                    {"block":0,"text":"ab","rect":[0.1,0.2,0.3,0.25],"glyphs":[[0.2,0.2,0.3,0.25],[0.1,0.2,0.2,0.25]]}]}""",
            ),
        )
        assertEquals(listOf(PdfBox(0.2f, 0.2f, 0.3f, 0.25f), PdfBox(0.1f, 0.2f, 0.2f, 0.25f)), lines[0].glyphs)
    }

    @Test
    fun a_markup_is_sent_with_its_page_from_one_its_kind_and_its_boxes() {
        val json = PdfOp.AddMarkup(
            kind = MarkupKind.StrikeOut,
            page = 2,
            quads = listOf(PdfBox(0.1f, 0.2f, 0.5f, 0.25f)),
            colour = PdfColour(1.0, 0.5, 0.0),
            modified = "D:20261125120000+00'00'",
        ).toJson()
        assertEquals("addMarkup", json.getString("op"))
        assertEquals("strikeOut", json.getString("kind"))
        assertEquals(3, json.getInt("page"))
        assertEquals(1, json.getJSONArray("quads").length())
        assertEquals(4, json.getJSONArray("quads").getJSONArray(0).length())
        assertEquals(0.5, json.getJSONArray("quads").getJSONArray(0).getDouble(2), 1e-6)
        assertEquals("[1,0.5,0]", json.getJSONArray("colour").toString())
        assertEquals("D:20261125120000+00'00'", json.getString("modified"))
    }

    @Test
    fun every_markup_kind_has_the_name_the_engine_reads() {
        assertEquals(listOf("highlight", "underline", "strikeOut"), MarkupKind.entries.map { it.wire })
    }

    @Test
    fun an_ink_is_sent_with_each_stroke_as_pairs_and_the_width_in_points() {
        val json = PdfOp.AddInk(
            page = 0,
            strokes = listOf(listOf(PdfPoint(0.1, 0.2), PdfPoint(0.3, 0.4)), listOf(PdfPoint(0.5, 0.5))),
            widthPoints = 3.0,
            colour = PdfColour(0.0, 0.0, 0.0),
            modified = "D:20261125120000+00'00'",
        ).toJson()
        assertEquals("addInk", json.getString("op"))
        assertEquals(1, json.getInt("page"))
        assertEquals("[[[0.1,0.2],[0.3,0.4]],[[0.5,0.5]]]", json.getJSONArray("strokes").toString())
        assertEquals(3.0, json.getDouble("width"), 0.0)
    }

    @Test
    fun annotations_are_read_with_their_names_kinds_and_geometry() {
        val list = KernkraftSession.parseAnnotations(
            JSONObject(
                """{"ok":true,"annotations":[
                  {"id":{"object":12,"generation":0},"kind":"Note","subtype":null,"drawn_by_library":true,
                   "rect":[0.1,0.2,0.15,0.25],"quads":[],"strokes":[],"contents":"Check this","author":null,
                   "colour":[1.0,0.92,0.0],"opacity":1.0,"line_width":null,"flags":28},
                  {"id":{"object":13,"generation":2},"kind":"Ink","subtype":null,"drawn_by_library":true,
                   "rect":[0.1,0.1,0.5,0.3],"quads":[],"strokes":[[[0.1,0.1],[0.5,0.3]],[[0.2,0.2]]],"contents":null,
                   "author":null,"colour":[0,0,0],"opacity":1.0,"line_width":2.0,"flags":4},
                  {"id":null,"kind":"Other","subtype":"Stamp","drawn_by_library":false,
                   "rect":[0.6,0.6,0.9,0.7],"quads":[],"strokes":[],"contents":null,"author":null,
                   "colour":null,"opacity":1.0,"line_width":null,"flags":4},
                  {"id":{"object":14,"generation":0},"kind":"Highlight","subtype":null,"drawn_by_library":true,
                   "rect":[0.1,0.5,0.6,0.55],"quads":[[0.1,0.5,0.3,0.55],[0.35,0.5,0.6,0.55]],"strokes":[],"contents":null,
                   "author":null,"colour":[1.0,0.92,0.0],"opacity":1.0,"line_width":null,"flags":4}]}""",
            ),
        )
        assertEquals(4, list.size)
        val note = list[0]
        assertEquals(PdfAnnotationId(12, 0), note.id)
        assertEquals(PdfAnnotationKind.Note, note.kind)
        assertEquals("Check this", note.contents)
        assertEquals(PdfBox(0.1f, 0.2f, 0.15f, 0.25f), note.rect)
        assertEquals(PdfColour(1.0, 0.92, 0.0), note.colour)
        assertEquals(28L, note.flags)
        val ink = list[1]
        assertEquals(PdfAnnotationId(13, 2), ink.id)
        assertEquals(listOf(listOf(PdfPoint(0.1, 0.1), PdfPoint(0.5, 0.3)), listOf(PdfPoint(0.2, 0.2))), ink.strokes)
        assertEquals(null, ink.contents)
        val stamp = list[2]
        assertEquals(null, stamp.id)
        assertEquals(PdfAnnotationKind.Other("Stamp"), stamp.kind)
        assertFalse(stamp.drawnByLibrary)
        assertEquals(null, stamp.colour)
        assertEquals(2, list[3].quads.size)
    }

    @Test
    fun a_step_names_the_annotation_it_added() {
        val step = KernkraftSession.parseStep(
            JSONObject(
                """{"ok":true,"revision":1,"pages":1,"changed_pages":[1],"can_undo":true,"can_redo":false,"modified":true,
                   "annotation":{"object":21,"generation":0}}""",
            ),
        )
        assertEquals(PdfAnnotationId(21, 0), step.annotation)
        val none = KernkraftSession.parseStep(
            JSONObject("""{"ok":true,"revision":1,"pages":1,"changed_pages":[],"can_undo":true,"can_redo":false,"modified":true}"""),
        )
        assertEquals(null, none.annotation)
    }

    @Test
    fun a_note_is_sent_with_its_place_text_and_page_from_one() {
        val json = PdfOp.AddNote(1, PdfPoint(0.25, 0.5), "Check this", PdfColour(1.0, 0.92, 0.0), "D:20261126120000Z").toJson()
        assertEquals("addNote", json.getString("op"))
        assertEquals(2, json.getInt("page"))
        assertEquals("[0.25,0.5]", json.getJSONArray("at").toString())
        assertEquals("Check this", json.getString("text"))
    }

    @Test
    fun an_edit_of_an_annotation_sends_only_what_it_changes() {
        val id = PdfAnnotationId(12, 3)
        val colour = PdfOp.EditAnnotation(0, id, "D:20261126120000Z", colour = PdfColour(1.0, 0.0, 0.0)).toJson()
        assertEquals("editAnnotation", colour.getString("op"))
        assertEquals(1, colour.getInt("page"))
        assertEquals(12, colour.getJSONObject("id").getInt("object"))
        assertEquals(3, colour.getJSONObject("id").getInt("generation"))
        assertTrue(colour.has("colour"))
        assertFalse(colour.has("contents") || colour.has("translate"))
        val move = PdfOp.EditAnnotation(0, id, "D:20261126120000Z", translate = PdfPoint(-0.1, 0.2)).toJson()
        assertEquals("[-0.1,0.2]", move.getJSONArray("translate").toString())
        assertFalse(move.has("colour") || move.has("contents"))
        val text = PdfOp.EditAnnotation(0, id, "D:20261126120000Z", contents = "New").toJson()
        assertEquals("New", text.getString("contents"))
    }

    @Test
    fun a_deletion_names_the_annotation_and_the_page_from_one() {
        val json = PdfOp.DeleteAnnotation(2, PdfAnnotationId(9, 0)).toJson()
        assertEquals("deleteAnnotation", json.getString("op"))
        assertEquals(3, json.getInt("page"))
        assertEquals(9, json.getJSONObject("id").getInt("object"))
    }

    @Test
    fun blocks_are_read_with_their_lines_joined_and_the_reason_a_block_is_refused() {
        val page = KernkraftSession.parseBlocks(
            JSONObject(
                """{"ok":true,"revision":7,"tagged":false,
                   "lines":[{"block":0,"text":"First line","wrapped":false,"rect":[0.1,0.1,0.5,0.12],"glyphs":[]},
                            {"block":0,"text":"second line","wrapped":false,"rect":[0.1,0.12,0.5,0.14],"glyphs":[]},
                            {"block":1,"text":"Hidden","wrapped":false,"rect":[0.1,0.3,0.2,0.32],"glyphs":[]}],
                   "blocks":[{"id":0,"rect":[0.1,0.1,0.5,0.14],"editable":true},
                             {"id":1,"rect":[0.1,0.3,0.2,0.32],"editable":false,"reason":"NoSize"}]}""",
            ),
        )
        assertEquals(7L, page.revision)
        assertEquals(2, page.blocks.size)
        val first = page.blocks[0]
        assertEquals("First line\nsecond line", first.text)
        assertEquals(2, first.lineCount)
        assertTrue(first.editable)
        assertEquals(null, first.reason)
        assertEquals(PdfBox(0.1f, 0.1f, 0.5f, 0.14f), first.box)
        val second = page.blocks[1]
        assertFalse(second.editable)
        assertEquals("NoSize", second.reason)
        assertEquals("Hidden", second.text)
        assertEquals(1, second.lineCount)
    }

    @Test
    fun a_wrapped_line_runs_on_with_one_space_and_an_ended_line_keeps_its_line_feed() {
        val page = KernkraftSession.parseBlocks(
            JSONObject(
                """{"ok":true,"revision":1,"tagged":false,
                   "lines":[{"block":0,"text":"the quick brown","wrapped":true,"rect":[0.1,0.1,0.5,0.12],"glyphs":[]},
                            {"block":0,"text":"fox jumps ","wrapped":true,"rect":[0.1,0.12,0.5,0.14],"glyphs":[]},
                            {"block":0,"text":"over.","wrapped":false,"rect":[0.1,0.14,0.3,0.16],"glyphs":[]},
                            {"block":0,"text":"New paragraph","wrapped":false,"rect":[0.1,0.16,0.4,0.18],"glyphs":[]}],
                   "blocks":[{"id":0,"rect":[0.1,0.1,0.5,0.18],"editable":true}]}""",
            ),
        )
        val block = page.blocks.single()
        assertEquals("the quick brown fox jumps over.\nNew paragraph", block.text)
        assertEquals(4, block.lineCount)
    }

    @Test
    fun a_page_without_text_has_no_blocks_but_still_a_revision() {
        val page = KernkraftSession.parseBlocks(JSONObject("""{"ok":true,"revision":2,"tagged":false,"blocks":[],"lines":[]}"""))
        assertEquals(2L, page.revision)
        assertTrue(page.blocks.isEmpty())
    }

    @Test
    fun a_text_edit_is_sent_with_the_page_from_one_the_block_and_the_revision() {
        val json = PdfOp.EditText(page = 1, block = 4, revision = 9, text = "New words").toJson()
        assertEquals("editText", json.getString("op"))
        assertEquals(2, json.getInt("page"))
        assertEquals(4, json.getInt("block"))
        assertEquals(9L, json.getLong("revision"))
        assertEquals("New words", json.getString("text"))
        assertFalse(json.has("family") || json.has("size") || json.has("colour"))
    }

    @Test
    fun a_text_edit_sends_the_style_fields_that_are_set() {
        val json = PdfOp.EditText(0, 0, 1, "x", family = "Serif", size = 12.0, colour = PdfColour(0.0, 0.0, 1.0)).toJson()
        assertEquals("Serif", json.getString("family"))
        assertEquals(12.0, json.getDouble("size"), 0.0)
        assertEquals(1.0, json.getJSONArray("colour").getDouble(2), 0.0)
    }

    @Test
    fun new_text_is_sent_with_the_page_from_one_its_box_and_no_style_it_does_not_set() {
        val json = PdfOp.AddText(page = 2, at = PdfPoint(0.1, 0.4), width = 0.5, text = "Hello").toJson()
        assertEquals("addText", json.getString("op"))
        assertEquals(3, json.getInt("page"))
        assertEquals("[0.1,0.4]", json.getJSONArray("at").toString())
        assertEquals(0.5, json.getDouble("width"), 0.0)
        assertEquals("Hello", json.getString("text"))
        assertFalse(json.has("like") || json.has("family") || json.has("size") || json.has("colour"))
    }

    @Test
    fun new_text_that_follows_a_block_names_it_with_its_revision() {
        val json = PdfOp.AddText(0, PdfPoint(0.0, 0.0), 0.3, "x", likeBlock = 4, likeRevision = 6, size = 11.0).toJson()
        val like = json.getJSONObject("like")
        assertEquals(4, like.getInt("block"))
        assertEquals(6L, like.getLong("revision"))
        assertEquals(11.0, json.getDouble("size"), 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun a_block_to_follow_without_its_revision_is_refused() {
        PdfOp.AddText(0, PdfPoint(0.0, 0.0), 0.3, "x", likeBlock = 4)
    }

    @Test
    fun the_fonts_of_a_page_are_read_with_the_nearest_marked() {
        val fonts = KernkraftSession.parsePageFonts(
            JSONObject(
                """{"ok":true,"revision":3,"nearest":2,"fonts":[
                     {"font":"Helvetica","embedded":false,"size":11.0,"colour":[0,0,0],"blocks":[0,2],"chars":140,"nearest":true},
                     {"font":"ABCDEF+Garamond","embedded":true,"size":18.5,"colour":null,"blocks":[1],"chars":12,"nearest":false}]}""",
            ),
        )
        assertEquals(3L, fonts.revision)
        assertEquals(2, fonts.nearest)
        assertEquals(PdfPageFont("Helvetica", false, 11.0, PdfColour(0.0, 0.0, 0.0), listOf(0, 2), 140, true), fonts.fonts[0])
        assertEquals(null, fonts.fonts[1].colour)
        assertTrue(fonts.fonts[1].embedded)
        val none = KernkraftSession.parsePageFonts(JSONObject("""{"ok":true,"revision":0,"nearest":null,"fonts":[]}"""))
        assertEquals(null, none.nearest)
        assertTrue(none.fonts.isEmpty())
    }

    @Test
    fun a_step_carries_the_notes_the_engine_gave() {
        val step = KernkraftSession.parseStep(
            JSONObject(
                """{"ok":true,"revision":4,"pages":1,"changed_pages":[1],"can_undo":true,"can_redo":false,"modified":true,
                   "notes":["substitute font used for: ABCDEF+Arial (sans)"]}""",
            ),
        )
        assertEquals(listOf("substitute font used for: ABCDEF+Arial (sans)"), step.notes)
        val none = KernkraftSession.parseStep(
            JSONObject("""{"ok":true,"revision":4,"pages":1,"changed_pages":[],"can_undo":true,"can_redo":false,"modified":true}"""),
        )
        assertTrue(none.notes.isEmpty())
    }
}
