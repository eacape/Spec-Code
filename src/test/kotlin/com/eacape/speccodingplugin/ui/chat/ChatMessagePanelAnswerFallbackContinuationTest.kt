package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.stream.ChatTraceKind
import com.eacape.speccodingplugin.stream.ChatTraceStatus
import com.intellij.testFramework.runInEdtAndWait
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ChatMessagePanelAnswerFallbackContinuationTest {

    @Test
    fun `final answer fallback should continue after standalone source path line`() {
        val panel = ChatMessagePanel(role = ChatMessagePanel.MessageRole.ASSISTANT)

        runInEdtAndWait {
            panel.appendStreamEvent(
                ChatStreamEvent(
                    kind = ChatTraceKind.OUTPUT,
                    detail = """
                        这个项目当前更准确地说，是一个基础后端工程骨架。
                        1. 项目目标与核心功能
                        已从代码输入、运行时命名、配置和调度任务看出它面向 NBA standings / ESPN 数据采集。
                        backend/config/runtime.yml#L1
                        真正已经落地的业务接口目前仍以健康检查为主，所以现在还不是一个可交付的排名平台。
                        2. 技术栈与版本线索
                        后端主栈是 FastAPI、Celery、Redis、SQLAlchemy 这一组。
                    """.trimIndent(),
                    status = ChatTraceStatus.DONE,
                ),
            )
            panel.finishMessage()
        }

        val displayed = invokeAssistantDisplayedAnswerResolver(panel, panel.getContent())
        assertTrue(displayed.contains("这个项目当前更准确地说，是一个基础后端工程骨架。"), displayed)
        assertTrue(displayed.contains("已从代码输入、运行时命名、配置和调度任务看出"), displayed)
        assertTrue(displayed.contains("真正已经落地的业务接口目前仍以健康检查为主"), displayed)
        assertTrue(displayed.contains("2. 技术栈与版本线索"), displayed)
        assertTrue(displayed.contains("后端主栈是 FastAPI、Celery、Redis、SQLAlchemy"), displayed)
        assertFalse(displayed.contains("backend/config/runtime.yml#L1"), displayed)
    }

    private fun invokeAssistantDisplayedAnswerResolver(panel: ChatMessagePanel, content: String): String {
        val snapshotMethod = ChatMessagePanel::class.java.getDeclaredMethod(
            "resolveTraceSnapshot",
            String::class.java,
        )
        snapshotMethod.isAccessible = true
        val snapshot = snapshotMethod.invoke(panel, content)

        val method = ChatMessagePanel::class.java.getDeclaredMethod(
            "resolveAssistantDisplayedAnswerContent",
            String::class.java,
            snapshot.javaClass,
        )
        method.isAccessible = true
        return method.invoke(panel, content, snapshot) as String
    }
}
