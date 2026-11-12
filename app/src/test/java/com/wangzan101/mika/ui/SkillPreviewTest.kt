package com.wangzan101.mika.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class SkillPreviewTest {
    @Test fun railSkillDisplaysItsMarkdownBodyWithoutTheFileHeader() {
        val body = "## 入口\n- 用 `phone_launch_app` 打开铁路 <参数>。\n\n## 步骤\n1. 选择**出发日期**。"
        val document = "\uFEFF---\nname: train-fare\ndisplay: 查车次\n---\n$body".replace("\n", "\r\n")
        assertEquals(body, skillPreviewBody(document))
        val blocks = MarkdownModel.parse(skillPreviewBody(document))
        assertEquals(listOf("入口", "步骤"), blocks.filterIsInstance<MdBlock.Heading>().map { it.text.text })
        assertEquals(listOf(null, 1), blocks.filterIsInstance<MdBlock.ListBlock>().map { it.start })
    }

    @Test fun plainMarkdownAndIncompleteFilesDoNotLoseText() {
        for (text in listOf("---\n普通段落\n---\n结尾", "---\nname: train-fare", "普通段落", "---\nname: train-fare\n---")) {
            assertEquals(text, skillPreviewBody(text))
        }
    }
}
