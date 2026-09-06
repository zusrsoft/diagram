package com.hrm.diagram.parser.plantuml

import com.hrm.diagram.core.ir.ActivityBlock
import com.hrm.diagram.core.ir.ActivityIR
import com.hrm.diagram.core.ir.RichLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PlantUmlActivityParserTest {
    private fun parse(src: String, chunkSize: Int? = null): PlantUmlActivityParser {
        val parser = PlantUmlActivityParser()
        if (chunkSize == null) {
            src.lines().forEach { parser.acceptLine(it) }
        } else {
            var pending = ""
            var index = 0
            while (index < src.length) {
                val end = (index + chunkSize).coerceAtMost(src.length)
                val merged = pending + src.substring(index, end)
                var start = 0
                for (i in merged.indices) {
                    if (merged[i] == '\n') {
                        parser.acceptLine(merged.substring(start, i))
                        start = i + 1
                    }
                }
                pending = if (start < merged.length) merged.substring(start) else ""
                index = end
            }
            if (pending.isNotEmpty()) parser.acceptLine(pending)
        }
        parser.finish(blockClosed = true)
        return parser
    }

    private fun visibleBlocks(ir: ActivityIR): List<ActivityBlock> = visibleBlocks(ir.blocks)

    private fun visibleBlocks(blocks: List<ActivityBlock>): List<ActivityBlock> =
        blocks.mapNotNull(::visibleBlock)

    private fun visibleBlock(block: ActivityBlock): ActivityBlock? = when (block) {
        is ActivityBlock.Action -> block
        is ActivityBlock.Note -> {
            val text = (block.text as? RichLabel.Plain)?.text.orEmpty()
            if (text.startsWith(PlantUmlActivityParser.SWIMLANE_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.ACTION_STYLE_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.EDGE_LABEL_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.SYNC_BAR_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.NODE_REF_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.EDGE_SOURCE_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.EDGE_TARGET_PREFIX) ||
                text.startsWith(PlantUmlActivityParser.EDGE_STOP_PREFIX)
            ) {
                null
            } else {
                block
            }
        }
        is ActivityBlock.IfElse -> block.copy(
            thenBranch = visibleBlocks(block.thenBranch),
            elseBranch = visibleBlocks(block.elseBranch),
        )
        is ActivityBlock.While -> block.copy(body = visibleBlocks(block.body))
        is ActivityBlock.ForkJoin -> block.copy(branches = block.branches.map(::visibleBlocks))
    }

    @Test
    fun start_action_stop_are_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                start
                :Load data;
                stop
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val visible = visibleBlocks(ir)
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_START_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_STOP_KEY])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Load data")), visible.single())
    }

    @Test
    fun if_else_block_is_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                if (ok?) then (yes)
                  :Done;
                else (no)
                  :Retry;
                endif
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val block = assertIs<ActivityBlock.IfElse>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain("ok?"), block.cond)
        assertEquals(1, block.thenBranch.size)
        assertEquals(1, block.elseBranch.size)
    }

    @Test
    fun while_block_is_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                while (pending?)
                  :Work;
                endwhile
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val block = assertIs<ActivityBlock.While>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain("pending?"), block.cond)
        assertEquals(1, block.body.size)
    }

    @Test
    fun elseif_chain_is_parsed_as_nested_ifelse() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                if (a?) then (yes)
                  :A;
                elseif (b?) then (yes)
                  :B;
                else (no)
                  :C;
                endif
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val root = assertIs<ActivityBlock.IfElse>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain("a?"), root.cond)
        val nested = assertIs<ActivityBlock.IfElse>(root.elseBranch.single())
        assertEquals(RichLabel.Plain("b?"), nested.cond)
        assertEquals(1, nested.elseBranch.size)
    }

    @Test
    fun repeat_block_is_encoded_as_repeat_condition() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                repeat
                  :Work;
                repeat while (more?)
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val block = assertIs<ActivityBlock.While>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain("${PlantUmlActivityParser.REPEAT_PREFIX}more?"), block.cond)
        assertEquals(1, block.body.size)
    }

    @Test
    fun fork_join_is_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                fork
                  :A;
                fork again
                  :B;
                end fork
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val block = assertIs<ActivityBlock.ForkJoin>(visibleBlocks(ir).single())
        assertEquals(2, block.branches.size)
        assertEquals(1, block.branches[0].size)
        assertEquals(1, block.branches[1].size)
    }

    @Test
    fun swimlane_markers_are_preserved() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                |Customer|
                :Order;
                |System|
                :Validate;
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals(ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SWIMLANE_PREFIX}Customer")), ir.blocks[0])
        assertTrue(ir.blocks.any { it == ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SWIMLANE_PREFIX}System")) })
    }

    @Test
    fun note_is_parsed() {
        val ir = parse("note right: explanation\n").snapshot()
        assertTrue(ir.blocks.single() is ActivityBlock.Note)
    }

    @Test
    fun multiline_note_and_partition_color_are_preserved() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                partition Ops #LightSkyBlue {
                note right
                  first line
                  second line
                end note
                :Deploy;
                }
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val visible = visibleBlocks(ir)
        assertEquals(
            ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SWIMLANE_PREFIX}Ops|||#LightSkyBlue")),
            ir.blocks[0],
        )
        assertEquals(ActivityBlock.Note(RichLabel.Plain("first line\nsecond line")), visible[0])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Deploy")), visible[1])
    }

    @Test
    fun legacy_arrow_and_legacy_if_are_parsed() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                (*) --> "First Action"
                -->[ok] if "Ready?" then
                  -right-> "Ship"
                else
                  --> "Wait"
                endif
                --> (*)
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val visible = visibleBlocks(ir)
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_START_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.HAS_STOP_KEY])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("First Action")), visible[0])
        assertTrue(
            ir.blocks.any { it == ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.EDGE_LABEL_PREFIX}ok")) },
        )
        val branch = assertIs<ActivityBlock.IfElse>(visible[1])
        assertEquals(RichLabel.Plain("Ready?"), branch.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Ship")), branch.thenBranch.single())
    }

    @Test
    fun styled_action_marker_is_preserved() {
        val ir = parse("#palegreen:Done;\n").snapshot()
        assertEquals(ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.ACTION_STYLE_PREFIX}#palegreen")), ir.blocks[0])
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Done")), visibleBlocks(ir).single())
    }

    @Test
    fun sync_bar_marker_is_preserved() {
        val ir = parse("=== phase 1 ===\n").snapshot()
        assertTrue(ir.blocks.any { it == ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SYNC_BAR_PREFIX}phase 1")) })
    }

    @Test
    fun legacy_alias_and_sync_references_are_preserved() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                (*) --> "First Action" as A1
                --> ===B1===
                ===B1=== --> "Second Action"
                A1 --> (*)
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        val texts = ir.blocks.mapNotNull { (it as? ActivityBlock.Note)?.text as? RichLabel.Plain }.map { it.text }
        assertTrue(texts.any { it == "${PlantUmlActivityParser.NODE_REF_PREFIX}name:A1" })
        assertTrue(texts.any { it == "${PlantUmlActivityParser.EDGE_SOURCE_PREFIX}sync:B1" })
        assertTrue(texts.any { it == "${PlantUmlActivityParser.EDGE_STOP_PREFIX}name:A1" })
    }

    @Test
    fun activity_skinparam_block_and_direct_keys_are_stored_in_style_hints() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                skinparam activity {
                  StartColor red
                  StartLineThickness 2
                  StartShadowing true
                  BarColor SaddleBrown
                  BarFontColor Yellow
                  BarFontSize 15
                  BarFontName monospace
                  BarLineThickness 2.25
                  BarShadowing yes
                  BackgroundColor Peru
                  BorderColor Peru
                  FontColor Ivory
                  FontSize 17
                  FontName serif
                  LineThickness 2.5
                  Shadowing true
                  DiamondBackgroundColor PaleGreen
                  DiamondBorderColor Green
                  DiamondFontColor Navy
                  DiamondFontSize 16
                  DiamondFontName fantasy
                  DiamondLineThickness 2
                  DiamondShadowing on
                  EndColor Silver
                  EndLineThickness 3
                  EndShadowing 1
                  NoteBackgroundColor Ivory
                  NoteBorderColor Orange
                  NoteFontColor Navy
                  NoteFontSize 13
                  NoteFontName cursive
                  NoteLineThickness 1.75
                  NoteShadowing true
                }
                skinparam ArrowColor Navy
                :Work;
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("red", ir.styleHints.extras[PlantUmlActivityParser.STYLE_START_FILL_KEY])
        assertEquals("2", ir.styleHints.extras[PlantUmlActivityParser.STYLE_START_LINE_THICKNESS_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.STYLE_START_SHADOWING_KEY])
        assertEquals("SaddleBrown", ir.styleHints.extras[PlantUmlActivityParser.STYLE_BAR_FILL_KEY])
        assertEquals("Yellow", ir.styleHints.extras[PlantUmlActivityParser.STYLE_BAR_TEXT_KEY])
        assertEquals("15", ir.styleHints.extras[PlantUmlActivityParser.STYLE_BAR_FONT_SIZE_KEY])
        assertEquals("monospace", ir.styleHints.extras[PlantUmlActivityParser.STYLE_BAR_FONT_NAME_KEY])
        assertEquals("2.25", ir.styleHints.extras[PlantUmlActivityParser.STYLE_BAR_LINE_THICKNESS_KEY])
        assertEquals("yes", ir.styleHints.extras[PlantUmlActivityParser.STYLE_BAR_SHADOWING_KEY])
        assertEquals("Peru", ir.styleHints.extras[PlantUmlActivityParser.STYLE_ACTION_FILL_KEY])
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlActivityParser.STYLE_ACTION_TEXT_KEY])
        assertEquals("17", ir.styleHints.extras[PlantUmlActivityParser.STYLE_ACTION_FONT_SIZE_KEY])
        assertEquals("serif", ir.styleHints.extras[PlantUmlActivityParser.STYLE_ACTION_FONT_NAME_KEY])
        assertEquals("2.5", ir.styleHints.extras[PlantUmlActivityParser.STYLE_ACTION_LINE_THICKNESS_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.STYLE_ACTION_SHADOWING_KEY])
        assertEquals("Green", ir.styleHints.extras[PlantUmlActivityParser.STYLE_DECISION_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlActivityParser.STYLE_DECISION_TEXT_KEY])
        assertEquals("16", ir.styleHints.extras[PlantUmlActivityParser.STYLE_DECISION_FONT_SIZE_KEY])
        assertEquals("fantasy", ir.styleHints.extras[PlantUmlActivityParser.STYLE_DECISION_FONT_NAME_KEY])
        assertEquals("2", ir.styleHints.extras[PlantUmlActivityParser.STYLE_DECISION_LINE_THICKNESS_KEY])
        assertEquals("on", ir.styleHints.extras[PlantUmlActivityParser.STYLE_DECISION_SHADOWING_KEY])
        assertEquals("Silver", ir.styleHints.extras[PlantUmlActivityParser.STYLE_STOP_STROKE_KEY])
        assertEquals("3", ir.styleHints.extras[PlantUmlActivityParser.STYLE_STOP_LINE_THICKNESS_KEY])
        assertEquals("1", ir.styleHints.extras[PlantUmlActivityParser.STYLE_STOP_SHADOWING_KEY])
        assertEquals("Ivory", ir.styleHints.extras[PlantUmlActivityParser.STYLE_NOTE_FILL_KEY])
        assertEquals("Orange", ir.styleHints.extras[PlantUmlActivityParser.STYLE_NOTE_STROKE_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlActivityParser.STYLE_NOTE_TEXT_KEY])
        assertEquals("13", ir.styleHints.extras[PlantUmlActivityParser.STYLE_NOTE_FONT_SIZE_KEY])
        assertEquals("cursive", ir.styleHints.extras[PlantUmlActivityParser.STYLE_NOTE_FONT_NAME_KEY])
        assertEquals("1.75", ir.styleHints.extras[PlantUmlActivityParser.STYLE_NOTE_LINE_THICKNESS_KEY])
        assertEquals("true", ir.styleHints.extras[PlantUmlActivityParser.STYLE_NOTE_SHADOWING_KEY])
        assertEquals("Navy", ir.styleHints.extras[PlantUmlActivityParser.STYLE_EDGE_COLOR_KEY])
    }

    @Test
    fun streaming_equivalence() {
        val src =
            """
            start
            :Step 1;
            if (ok?) then (yes)
              :Done;
            else
              :Retry;
            endif
            stop
            """.trimIndent() + "\n"
        assertEquals(parse(src).snapshot(), parse(src, chunkSize = 1).snapshot())
    }

    @Test
    fun unmatched_endif_does_not_corrupt_root_frame() {
        val parser = parse(
            """
            start
            endif
            :Load data;
            stop
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" })
        val ir = assertIs<ActivityIR>(parser.snapshot())
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Load data")), visibleBlocks(ir).single())
    }

    @Test
    fun unmatched_endif_does_not_drop_enclosing_while_frame() {
        val parser = parse(
            """
            while (condition)
              :Load;
            endif
              :Next;
            endwhile
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" })
        val ir = assertIs<ActivityIR>(parser.snapshot())
        val block = assertIs<ActivityBlock.While>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain("condition"), block.cond)
        assertEquals(2, block.body.size)
    }

    @Test
    fun invalid_repeat_while_does_not_drop_repeat_frame() {
        val parser = parse(
            """
            repeat
              :Load;
            repeat while invalid
              :Next;
            repeat while (condition)
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" })
        val ir = assertIs<ActivityIR>(parser.snapshot())
        val block = assertIs<ActivityBlock.While>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain(PlantUmlActivityParser.REPEAT_PREFIX + "condition"), block.cond)
        assertEquals(2, block.body.size)
    }

    @Test
    fun unmatched_closes_do_not_drop_root_frame() {
        val closers = listOf("endif", "endwhile", "repeat while (condition)", "end fork")
        for (closer in closers) {
            val parser = PlantUmlActivityParser()
            parser.acceptLine(closer)
            parser.acceptLine(":After;")
            parser.finish(blockClosed = true)
            assertTrue(
                parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" },
                "missing diagnostic for '$closer'",
            )
            val ir = assertIs<ActivityIR>(parser.snapshot())
            assertEquals(ActivityBlock.Action(RichLabel.Plain("After")), visibleBlocks(ir).single())
        }
    }

    @Test
    fun unmatched_branch_switches_do_not_drop_root_frame() {
        val switchers = listOf("else", "elseif (x)", "fork again")
        for (switcher in switchers) {
            val parser = PlantUmlActivityParser()
            parser.acceptLine(switcher)
            parser.acceptLine(":After;")
            parser.finish(blockClosed = true)
            assertTrue(
                parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" },
                "missing diagnostic for '$switcher'",
            )
            val ir = assertIs<ActivityIR>(parser.snapshot())
            assertEquals(ActivityBlock.Action(RichLabel.Plain("After")), visibleBlocks(ir).single())
        }
    }

    @Test
    fun uppercase_keywords_are_parsed_case_insensitively() {
        val parser = parse(
            """
            IF (ready)
              :Load;
            ELSEIF (retry)
              :Wait;
            ELSE
              :Skip;
            ENDIF
            REPEAT
              :Step;
            REPEAT WHILE (more)
            WHILE (loop)
              :Body;
            ENDWHILE
            PARTITION Ops {
              :Deploy;
            }
            NOTE remark
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val ir = assertIs<ActivityIR>(parser.snapshot())
        assertTrue(ir.blocks.any { it == ActivityBlock.Note(RichLabel.Plain("${PlantUmlActivityParser.SWIMLANE_PREFIX}Ops")) })

        val visible = visibleBlocks(ir)
        assertEquals(5, visible.size)

        val branch = assertIs<ActivityBlock.IfElse>(visible[0])
        assertEquals(RichLabel.Plain("ready"), branch.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Load")), branch.thenBranch.single())
        val nested = assertIs<ActivityBlock.IfElse>(branch.elseBranch.single())
        assertEquals(RichLabel.Plain("retry"), nested.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Wait")), nested.thenBranch.single())
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Skip")), nested.elseBranch.single())

        val repeatLoop = assertIs<ActivityBlock.While>(visible[1])
        assertEquals(RichLabel.Plain("${PlantUmlActivityParser.REPEAT_PREFIX}more"), repeatLoop.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Step")), repeatLoop.body.single())

        val whileLoop = assertIs<ActivityBlock.While>(visible[2])
        assertEquals(RichLabel.Plain("loop"), whileLoop.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Body")), whileLoop.body.single())

        assertEquals(ActivityBlock.Action(RichLabel.Plain("Deploy")), visible[3])
        assertEquals(ActivityBlock.Note(RichLabel.Plain("remark")), visible[4])
    }

    @Test
    fun uppercase_skinparam_activity_prefix_is_accepted() {
        val ir = assertIs<ActivityIR>(
            parse(
                """
                skinparam ACTIVITY StartColor red
                :Work;
                """.trimIndent() + "\n",
            ).snapshot(),
        )
        assertEquals("red", ir.styleHints.extras[PlantUmlActivityParser.STYLE_START_FILL_KEY])
    }

    @Test
    fun compact_if_and_uppercase_note_placement_are_parsed() {
        val parser = parse(
            """
            IF(x)
              :Load;
            ENDIF
            NOTE right: text
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val visible = visibleBlocks(assertIs<ActivityIR>(parser.snapshot()))
        val branch = assertIs<ActivityBlock.IfElse>(visible[0])
        assertEquals(RichLabel.Plain("x"), branch.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Load")), branch.thenBranch.single())
        assertEquals(ActivityBlock.Note(RichLabel.Plain("text")), visible[1])
    }

    @Test
    fun uppercase_alias_keyword_is_recognized() {
        val parser = parse(
            """
            (*) --> "First Action" AS A1
            A1 --> (*)
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val ir = assertIs<ActivityIR>(parser.snapshot())
        val texts = ir.blocks.mapNotNull { (it as? ActivityBlock.Note)?.text as? RichLabel.Plain }.map { it.text }
        assertTrue(texts.any { it == "${PlantUmlActivityParser.NODE_REF_PREFIX}name:A1" })
        assertTrue(texts.any { it == "${PlantUmlActivityParser.EDGE_STOP_PREFIX}name:A1" })
        val actions = ir.blocks.filterIsInstance<ActivityBlock.Action>()
        assertEquals(listOf(ActivityBlock.Action(RichLabel.Plain("First Action"))), actions)
    }

    @Test
    fun else_prefixed_word_is_not_silently_treated_as_else_branch() {
        val parser = parse(
            """
            if (ok?) then (yes)
              :Done;
            elsewhere
              :Fallback;
            endif
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" })
        val ir = assertIs<ActivityIR>(parser.snapshot())
        val block = assertIs<ActivityBlock.IfElse>(visibleBlocks(ir).single())
        assertEquals(2, block.thenBranch.size)
        assertEquals(emptyList(), block.elseBranch)
    }

    @Test
    fun else_with_tab_separator_is_accepted() {
        val parser = parse(
            """
            if (ok?) then (yes)
              :Done;
            else${'\t'}(no)
              :Retry;
            endif
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val block = assertIs<ActivityBlock.IfElse>(visibleBlocks(assertIs<ActivityIR>(parser.snapshot())).single())
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Done")), block.thenBranch.single())
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Retry")), block.elseBranch.single())
    }

    @Test
    fun alias_keyword_matching_last_occurrence_wins_over_embedded_label_text() {
        val parser = parse(
            """
            (*) --> "A as B" as C
            C --> (*)
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val ir = assertIs<ActivityIR>(parser.snapshot())
        val texts = ir.blocks.mapNotNull { (it as? ActivityBlock.Note)?.text as? RichLabel.Plain }.map { it.text }
        assertTrue(texts.any { it == "${PlantUmlActivityParser.NODE_REF_PREFIX}name:C" })
        val actions = ir.blocks.filterIsInstance<ActivityBlock.Action>()
        assertEquals(listOf(ActivityBlock.Action(RichLabel.Plain("A as B"))), actions)
    }

    @Test
    fun legacy_if_with_expanding_lowercase_label_is_parsed_without_throwing() {
        val parser = parse("if \"İİİİİİ\" then\n  :Done;\nendif\n")
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val block = assertIs<ActivityBlock.IfElse>(visibleBlocks(assertIs<ActivityIR>(parser.snapshot())).single())
        assertEquals(RichLabel.Plain("İİİİİİ"), block.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("Done")), block.thenBranch.single())
    }

    @Test
    fun multi_statement_line_reports_residual_diagnostic() {
        val parser = parse("(*) --> \"First Action\" \"First Action\" --> (*)\n")
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" }, parser.diagnosticsSnapshot().toString())
        val actions = assertIs<ActivityIR>(parser.snapshot()).blocks.filterIsInstance<ActivityBlock.Action>()
        assertEquals(listOf(ActivityBlock.Action(RichLabel.Plain("First Action"))), actions)
    }

    @Test
    fun double_arrow_line_reports_residual_diagnostic() {
        val parser = parse("(*) --> \"First\" --> (*)\n")
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" }, parser.diagnosticsSnapshot().toString())
        val actions = assertIs<ActivityIR>(parser.snapshot()).blocks.filterIsInstance<ActivityBlock.Action>()
        assertEquals(listOf(ActivityBlock.Action(RichLabel.Plain("First"))), actions)
    }

    @Test
    fun quoted_label_with_embedded_arrow_is_not_split() {
        val parser = parse("\"Play -> Pause\" --> \"Next\"\n")
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val actions = assertIs<ActivityIR>(parser.snapshot()).blocks.filterIsInstance<ActivityBlock.Action>()
        val labels = actions.map { (it.label as RichLabel.Plain).text }
        assertTrue(labels.contains("Next"), "expected target action 'Next', got: $labels")
        assertTrue(labels.none { it.contains("->") }, "no action label should contain a split arrow: $labels")
    }

    @Test
    fun if_condition_with_nested_parens_keeps_full_text() {
        val parser = parse(
            """
            if (a && (b || c))
              :Do;
            endif
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val block = assertIs<ActivityBlock.IfElse>(visibleBlocks(assertIs<ActivityIR>(parser.snapshot())).single())
        assertEquals(RichLabel.Plain("a && (b || c)"), block.cond)
    }

    @Test
    fun while_condition_with_nested_parens_keeps_full_text() {
        val parser = parse(
            """
            while (a && (b))
              :Do;
            endwhile
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val block = assertIs<ActivityBlock.While>(visibleBlocks(assertIs<ActivityIR>(parser.snapshot())).single())
        assertEquals(RichLabel.Plain("a && (b)"), block.cond)
    }

    @Test
    fun elseif_condition_with_nested_parens_keeps_full_text() {
        val parser = parse(
            """
            if (x)
              :A;
            elseif (y && (z))
              :B;
            endif
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val root = assertIs<ActivityBlock.IfElse>(visibleBlocks(assertIs<ActivityIR>(parser.snapshot())).single())
        val nested = assertIs<ActivityBlock.IfElse>(root.elseBranch.single())
        assertEquals(RichLabel.Plain("y && (z)"), nested.cond)
    }

    @Test
    fun else_if_space_form_opens_nested_branch() {
        val parser = parse(
            """
            if (x)
              :A;
            else if "y" then
              :B;
            else
              :C;
            endif
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val root = assertIs<ActivityBlock.IfElse>(visibleBlocks(assertIs<ActivityIR>(parser.snapshot())).single())
        assertEquals(RichLabel.Plain("x"), root.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("A")), root.thenBranch.single())
        val nested = assertIs<ActivityBlock.IfElse>(root.elseBranch.single())
        assertEquals(RichLabel.Plain("y"), nested.cond)
        assertEquals(ActivityBlock.Action(RichLabel.Plain("B")), nested.thenBranch.single())
        assertEquals(ActivityBlock.Action(RichLabel.Plain("C")), nested.elseBranch.single())
    }

    @Test
    fun else_if_without_matching_if_reports_diagnostic() {
        val parser = parse("else if \"y\" then\n:After;\n")
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" })
        val ir = assertIs<ActivityIR>(parser.snapshot())
        assertEquals(ActivityBlock.Action(RichLabel.Plain("After")), visibleBlocks(ir).single())
    }

    @Test
    fun legacy_if_with_empty_quoted_condition_is_parsed() {
        val parser = parse(
            """
            (*) --> if "" then
              --> "ok"
            endif
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val ir = assertIs<ActivityIR>(parser.snapshot())
        val block = assertIs<ActivityBlock.IfElse>(visibleBlocks(ir).single())
        assertEquals(RichLabel.Plain(""), block.cond)
    }

    @Test
    fun colon_action_as_arrow_target_is_unwrapped() {
        val parser = parse("(*) --> :step;\n")
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val actions = assertIs<ActivityIR>(parser.snapshot()).blocks.filterIsInstance<ActivityBlock.Action>()
        assertEquals(listOf(ActivityBlock.Action(RichLabel.Plain("step"))), actions)
    }

    @Test
    fun colon_action_arrow_chain_is_not_swallowed_as_single_action() {
        val parser = parse(
            """
            :prep;
            :prep; --> :next;
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val ir = assertIs<ActivityIR>(parser.snapshot())
        val actions = ir.blocks.filterIsInstance<ActivityBlock.Action>().map { (it.label as RichLabel.Plain).text }
        assertTrue(actions.contains("prep"), "actions: $actions")
        assertTrue(actions.contains("next"), "actions: $actions")
        assertTrue(actions.none { it.contains("->") }, "no action label should contain an arrow: $actions")
        val texts = ir.blocks.mapNotNull { (it as? ActivityBlock.Note)?.text as? RichLabel.Plain }.map { it.text }
        assertTrue(texts.any { it == "${PlantUmlActivityParser.EDGE_STOP_PREFIX}name:prep" } || texts.any { it == "${PlantUmlActivityParser.EDGE_SOURCE_PREFIX}name:prep" })
    }

    @Test
    fun finish_without_block_closure_reports_e001() {
        val parser = PlantUmlActivityParser()
        parser.acceptLine("start")
        parser.acceptLine(":Work;")
        parser.finish(blockClosed = false)
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E001" }, parser.diagnosticsSnapshot().toString())
    }

    @Test
    fun uppercase_fork_variants_are_accepted() {
        val parser = parse(
            """
            FORK
              :A;
            FORK AGAIN
              :B;
            END FORK
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val block = assertIs<ActivityBlock.ForkJoin>(visibleBlocks(assertIs<ActivityIR>(parser.snapshot())).single())
        assertEquals(2, block.branches.size)
    }

    @Test
    fun fork_with_three_branches_is_parsed() {
        val parser = parse(
            """
            fork
              :A;
            fork again
              :B;
            fork again
              :C;
            end fork
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().isEmpty(), parser.diagnosticsSnapshot().toString())
        val block = assertIs<ActivityBlock.ForkJoin>(visibleBlocks(assertIs<ActivityIR>(parser.snapshot())).single())
        assertEquals(3, block.branches.size)
    }

    @Test
    fun unclosed_partition_reports_e007_on_finish() {
        val parser = parse(
            """
            partition P {
              :Work;
            """.trimIndent() + "\n",
        )
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" })
    }

    @Test
    fun stray_end_note_reports_diagnostic() {
        val parser = parse("end note\n:After;\n")
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-E007" })
        val ir = assertIs<ActivityIR>(parser.snapshot())
        assertEquals(ActivityBlock.Action(RichLabel.Plain("After")), visibleBlocks(ir).single())
    }

    @Test
    fun unsupported_skinparam_reports_w001() {
        val parser = parse("skinparam nonsenseKey value\n:Work;\n")
        assertTrue(parser.diagnosticsSnapshot().any { it.code == "PLANTUML-W001" })
    }

    @Test
    fun error_diagnostics_are_streaming_equivalent() {
        val src = "if (x)\n  :A;\n  elseif\n  end note\n  :B;\nendif\n"
        assertEquals(parse(src).diagnosticsSnapshot(), parse(src, chunkSize = 1).diagnosticsSnapshot())
    }
}
