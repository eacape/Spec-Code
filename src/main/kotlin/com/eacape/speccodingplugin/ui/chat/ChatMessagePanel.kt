package com.eacape.speccodingplugin.ui.chat

import com.eacape.speccodingplugin.SpecCodingBundle
import com.eacape.speccodingplugin.stream.ChatStreamEvent
import com.eacape.speccodingplugin.ui.settings.SpecCodingSettingsState
import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.colors.EditorColorsScheme
import com.intellij.openapi.editor.colors.TextAttributesKey
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Dialog
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GraphicsEnvironment
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import java.util.Locale
import javax.imageio.ImageIO
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.ImageIcon
import javax.swing.Icon
import javax.swing.JDialog
import javax.swing.JPanel
import javax.swing.JScrollPane
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.event.HyperlinkEvent
import javax.swing.border.AbstractBorder
import javax.swing.border.Border
import javax.swing.border.CompoundBorder
import javax.swing.text.PlainDocument
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants
import javax.swing.text.StyledDocument
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.event.MouseWheelEvent
import kotlin.math.min

/**
 * 单条聊天消息的 UI 组件
 */
open class ChatMessagePanel(
    val role: MessageRole,
    initialContent: String = "",
    private val onDelete: ((ChatMessagePanel) -> Unit)? = null,
    private val onRegenerate: ((ChatMessagePanel) -> Unit)? = null,
    private var onContinue: ((ChatMessagePanel) -> Unit)? = null,
    private val onWorkflowFileOpen: ((WorkflowQuickActionParser.FileAction) -> Unit)? = null,
    private val onWorkflowCommandExecute: ((String) -> Unit)? = null,
    private var workflowSectionsEnabled: Boolean = true,
    private var timelineExpandButtonsVisible: Boolean = true,
    private val attachedImagePaths: List<String> = emptyList(),
    startedAtMillis: Long? = null,
    finishedAtMillis: Long? = null,
    private val captureElapsedAutomatically: Boolean = true,
) : JPanel(BorderLayout()) {

    private val contentPane = JTextPane()
    private val contentHost = JPanel(BorderLayout())
    private val contentBuilder = StringBuilder()
    private val traceAssembler = StreamingTraceAssembler()
    private val codeBlocks = mutableListOf<String>()
    private val expandedVerboseEntries = mutableSetOf<String>()
    private val workflowSectionExpanded = mutableMapOf<WorkflowSectionParser.SectionKind, Boolean>()
    private var buttonPanel: JPanel? = null
    private var traceExpanded = false
    private var outputExpanded = false
    private var outputFilterLevel = OutputFilterLevel.KEY
    private var messageFinished = false
    private var messageStartedAtMillis: Long? = startedAtMillis?.takeIf { it >= 0L }
    private var messageFinishedAtMillis: Long? = finishedAtMillis?.takeIf { it >= 0L }
    private var lightweightMode = false
    private var contentVersion = 0
    private var traceVersion = 0
    private var cachedAssistantAnswerVersion = -1
    private var cachedAssistantAnswerContent = ""
    private var cachedTraceSnapshotContentVersion = -1
    private var cachedTraceSnapshotTraceVersion = -1
    private var cachedTraceSnapshotIncludeRawContent = true
    private var cachedTraceSnapshot: StreamingTraceAssembler.TraceSnapshot? = null
    private var cachedStreamingRenderContentVersion = -1
    private var cachedStreamingRenderFontSize = -1
    private val userImageAttachments: List<UserImageAttachment> by lazy(LazyThreadSafetyMode.NONE) {
        if (role == MessageRole.USER) {
            loadUserImageAttachments(attachedImagePaths)
        } else {
            emptyList()
        }
    }

    enum class MessageRole {
        USER, ASSISTANT, SYSTEM, ERROR
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 0, 8, 0)

        // 内容区域
        configureReadableTextPane(contentPane)
        contentPane.border = JBUI.Borders.empty(7, 10)
        contentPane.background = getBackgroundColor()

        contentHost.isOpaque = false
        contentHost.add(contentPane, BorderLayout.CENTER)

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = getBackgroundColor()
        wrapper.border = buildRoundedContainerBorder(
            lineColor = messageCardBorderColor(),
            arc = 12,
            padding = JBUI.insets(7, 10, 6, 10),
        )
        wrapper.add(contentHost, BorderLayout.CENTER)

        add(wrapper, BorderLayout.CENTER)

        if (initialContent.isNotEmpty()) {
            appendContent(initialContent)
        }
    }

    /**
     * 追加内容（用于流式渲染）
     */
    fun appendContent(text: String) {
        appendStreamContent(text = text)
    }

    fun appendStreamContent(text: String, event: ChatStreamEvent? = null) {
        if (event == null) {
            appendStreamContent(text = text, events = emptyList())
            return
        }
        appendStreamContent(text = text, events = listOf(event))
    }

    fun appendStreamContent(text: String, events: List<ChatStreamEvent>) {
        if (
            captureElapsedAutomatically &&
            role == MessageRole.ASSISTANT &&
            messageStartedAtMillis == null &&
            (events.isNotEmpty() || text.isNotEmpty())
        ) {
            messageStartedAtMillis = System.currentTimeMillis()
        }
        if (role == MessageRole.ASSISTANT && events.isNotEmpty()) {
            events.forEach { event ->
                traceAssembler.onStructuredEvent(event)
            }
            traceVersion += 1
        }
        if (text.isNotEmpty()) {
            contentBuilder.append(text)
            contentVersion += 1
        }
        renderContent()
    }

    fun appendStreamEvent(event: ChatStreamEvent) {
        appendStreamContent(text = "", event = event)
    }

    /**
     * 获取消息的原始文本内容
     */
    fun getContent(): String = contentBuilder.toString()

    internal fun isFinishedForTest(): Boolean = messageFinished

    internal fun hasTraceForTest(): Boolean {
        return role == MessageRole.ASSISTANT && resolveTraceSnapshot(contentBuilder.toString()).hasTrace
    }

    open fun updateContinueAction(onContinue: ((ChatMessagePanel) -> Unit)?) {
        if (this.onContinue === onContinue) {
            return
        }
        this.onContinue = onContinue
        if (messageFinished) {
            addActionButtons()
        }
    }

    fun setWorkflowSectionsEnabled(enabled: Boolean) {
        if (workflowSectionsEnabled == enabled) return
        workflowSectionsEnabled = enabled
        renderContent(structured = messageFinished)
    }

    fun setTimelineExpandButtonsVisible(visible: Boolean) {
        if (timelineExpandButtonsVisible == visible) return
        timelineExpandButtonsVisible = visible
        if (!visible) {
            traceExpanded = false
            outputExpanded = false
        }
        renderContent(structured = messageFinished)
    }

    fun setLightweightMode(enabled: Boolean) {
        if (lightweightMode == enabled) return
        lightweightMode = enabled

        if (enabled) {
            traceExpanded = false
            outputExpanded = false
            expandedVerboseEntries.clear()
            workflowSectionExpanded.clear()
            buttonPanel?.let {
                remove(it)
                buttonPanel = null
            }
        } else if (messageFinished) {
            addActionButtons()
        }

        renderContent(structured = messageFinished)
    }

    /**
     * 完成消息（流式结束后调用）
     */
    fun finishMessage() {
        if (captureElapsedAutomatically && role == MessageRole.ASSISTANT && messageFinishedAtMillis == null) {
            messageFinishedAtMillis = System.currentTimeMillis()
        }
        messageFinished = true
        traceAssembler.markRunningItemsDone()
        traceVersion += 1
        renderContent(structured = true)
        extractCodeBlocks()
        addActionButtons()
    }

    private fun renderContent(structured: Boolean = false) {
        val useStructured = (structured || messageFinished) && workflowSectionsEnabled
        val rawContent = contentBuilder.toString()
        val content = if (role == MessageRole.ASSISTANT) sanitizeAssistantDisplayContent(rawContent) else rawContent
        val outputFontSize = configuredOutputFontSize()
        applyConfiguredOutputFont(contentPane, outputFontSize)
        if (lightweightMode && messageFinished) {
            renderLightweightContent(content, outputFontSize)
            revalidate()
            repaint()
            return
        }
        if (role == MessageRole.ASSISTANT) {
            var traceSnapshot: StreamingTraceAssembler.TraceSnapshot? = null
            fun resolveAssistantTraceSnapshot(): StreamingTraceAssembler.TraceSnapshot {
                return traceSnapshot ?: resolveTraceSnapshot(content).also { traceSnapshot = it }
            }

            val shouldRenderStreamingTrace = !messageFinished && resolveAssistantTraceSnapshot().hasTrace
            if (!messageFinished && !useStructured && !shouldRenderStreamingTrace) {
                val shouldRepaintStreamingContent =
                    cachedStreamingRenderContentVersion != contentVersion ||
                        cachedStreamingRenderFontSize != outputFontSize ||
                        contentHost.componentCount != 1 ||
                        contentHost.getComponent(0) !== contentPane
                if (shouldRepaintStreamingContent) {
                    renderAssistantStreamingContent(content, outputFontSize)
                    cachedStreamingRenderContentVersion = contentVersion
                    cachedStreamingRenderFontSize = outputFontSize
                    revalidate()
                    repaint()
                }
                return
            }

            val resolvedTraceSnapshot = resolveAssistantTraceSnapshot()
            if (resolvedTraceSnapshot.hasTrace) {
                val answerContent = resolveAssistantAnswerContent(content)
                renderAssistantTraceContent(answerContent, resolvedTraceSnapshot, useStructured)
            } else if (useStructured) {
                contentHost.removeAll()
                contentHost.add(createAssistantAnswerComponent(content, structured = true), BorderLayout.CENTER)
            } else {
                contentHost.removeAll()
                contentHost.add(contentPane, BorderLayout.CENTER)
                MarkdownRenderer.render(contentPane, formatAssistantAcknowledgementLead(content))
                refreshTextPaneCursor(contentPane)
            }
        } else {
            contentHost.removeAll()
            if (role == MessageRole.USER) {
                if (userImageAttachments.isEmpty()) {
                    contentHost.add(contentPane, BorderLayout.CENTER)
                    renderUserPromptAwareContent(contentPane.styledDocument, content, outputFontSize)
                    refreshTextPaneCursor(contentPane)
                } else {
                    contentHost.add(createUserPromptWithImages(content, outputFontSize), BorderLayout.CENTER)
                }
            } else {
                contentHost.add(contentPane, BorderLayout.CENTER)
                val doc = contentPane.styledDocument
                doc.remove(0, doc.length)
                val attrs = SimpleAttributeSet()
                StyleConstants.setFontFamily(attrs, "Monospaced")
                StyleConstants.setFontSize(attrs, outputFontSize)
                StyleConstants.setLineSpacing(attrs, PLAIN_TEXT_LINE_SPACING)
                doc.insertString(0, content, attrs)
                refreshTextPaneCursor(contentPane)
            }
        }
        revalidate()
        repaint()
    }

    private fun renderAssistantStreamingContent(content: String, fontSize: Int) {
        if (contentHost.componentCount != 1 || contentHost.getComponent(0) !== contentPane) {
            contentHost.removeAll()
            contentHost.add(contentPane, BorderLayout.CENTER)
        }
        val doc = contentPane.styledDocument
        doc.remove(0, doc.length)
        val attrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(attrs, "Monospaced")
        StyleConstants.setFontSize(attrs, fontSize)
        StyleConstants.setLineSpacing(attrs, PLAIN_TEXT_LINE_SPACING)
        doc.insertString(0, content, attrs)
        refreshTextPaneCursor(contentPane)
    }

    private fun resolveAssistantAnswerContent(content: String): String {
        if (cachedAssistantAnswerVersion == contentVersion) {
            return cachedAssistantAnswerContent
        }
        val extracted = extractAssistantAnswerContent(content)
        cachedAssistantAnswerContent = extracted
        cachedAssistantAnswerVersion = contentVersion
        return extracted
    }

    private fun resolveTraceSnapshot(content: String): StreamingTraceAssembler.TraceSnapshot {
        val preferStructuredOnly = !messageFinished &&
            content.length >= TRACE_RAW_PARSE_SKIP_THRESHOLD &&
            traceAssembler.hasStructuredItems()
        val includeRawContent = !preferStructuredOnly

        val cached = cachedTraceSnapshot
        if (
            cached != null &&
            cachedTraceSnapshotContentVersion == contentVersion &&
            cachedTraceSnapshotTraceVersion == traceVersion &&
            cachedTraceSnapshotIncludeRawContent == includeRawContent
        ) {
            return cached
        }

        val snapshot = traceAssembler.snapshot(
            content = content,
            includeRawContent = includeRawContent,
            finalizeRunningItems = messageFinished,
        )
        cachedTraceSnapshot = snapshot
        cachedTraceSnapshotContentVersion = contentVersion
        cachedTraceSnapshotTraceVersion = traceVersion
        cachedTraceSnapshotIncludeRawContent = includeRawContent
        return snapshot
    }

    private fun renderUserPromptAwareContent(doc: StyledDocument, content: String, fontSize: Int) {
        doc.remove(0, doc.length)

        val baseAttrs = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, "Monospaced")
            StyleConstants.setFontSize(this, fontSize)
            StyleConstants.setLineSpacing(this, PLAIN_TEXT_LINE_SPACING)
        }
        doc.insertString(0, content, baseAttrs)

        val promptAttrs = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, "Monospaced")
            StyleConstants.setFontSize(this, fontSize)
            StyleConstants.setLineSpacing(this, PLAIN_TEXT_LINE_SPACING)
            StyleConstants.setBold(this, true)
            StyleConstants.setForeground(this, PROMPT_REFERENCE_FG)
            StyleConstants.setBackground(this, PROMPT_REFERENCE_BG)
        }
        PROMPT_REFERENCE_TOKEN_REGEX.findAll(content).forEach { match ->
            doc.setCharacterAttributes(
                match.range.first,
                match.value.length,
                promptAttrs,
                true,
            )
        }
    }

    private fun createUserPromptWithImages(content: String, fontSize: Int): JPanel {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false

        val textContent = stripUserAttachmentMarkerLines(content)
        if (textContent.isNotBlank()) {
            renderUserPromptAwareContent(contentPane.styledDocument, textContent, fontSize)
            refreshTextPaneCursor(contentPane)
            container.add(contentPane)
            if (userImageAttachments.isNotEmpty()) {
                container.add(createVerticalSpacer(USER_IMAGE_TEXT_GAP))
            }
        }

        if (userImageAttachments.isNotEmpty()) {
            container.add(createUserImageAttachmentStrip())
        }

        return container
    }

    private fun createUserImageAttachmentStrip(): JPanel {
        val strip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(USER_IMAGE_CHIP_GAP), JBUI.scale(USER_IMAGE_ROW_VGAP)))
        strip.isOpaque = false
        userImageAttachments.forEach { attachment ->
            strip.add(createUserImageChip(attachment))
        }
        return strip
    }

    private fun createUserImageChip(attachment: UserImageAttachment): JPanel {
        val chip = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(3), 0))
        chip.isOpaque = true
        chip.background = USER_IMAGE_CHIP_BG
        chip.border = buildRoundedContainerBorder(
            lineColor = USER_IMAGE_CHIP_BORDER,
            arc = 8,
            padding = JBUI.insets(2, 6, 2, 6),
        )

        val label = JBLabel(attachment.displayName, AllIcons.FileTypes.Image, JBLabel.LEADING)
        label.font = JBUI.Fonts.smallFont()
        label.foreground = USER_IMAGE_META_FG
        label.iconTextGap = JBUI.scale(4)
        label.toolTipText = buildUserImageTooltip(attachment)
        chip.add(label)

        installImagePreviewOpenAction(chip, attachment)
        installImagePreviewOpenAction(label, attachment)
        return chip
    }

    private fun buildUserImageTooltip(attachment: UserImageAttachment): String {
        return "${attachment.displayName} (${attachment.originalFileName}, ${attachment.image.width}×${attachment.image.height})"
    }

    private fun createVerticalSpacer(height: Int): JPanel {
        val spacer = JPanel()
        spacer.isOpaque = false
        val scaledHeight = JBUI.scale(height)
        spacer.preferredSize = Dimension(0, scaledHeight)
        spacer.minimumSize = spacer.preferredSize
        spacer.maximumSize = Dimension(Int.MAX_VALUE, scaledHeight)
        return spacer
    }

    private fun stripUserAttachmentMarkerLines(content: String): String {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        val lines = normalized.lines()
        val filtered = lines.filterNot { line ->
            USER_IMAGE_ATTACHMENT_LINE_REGEX.matches(line.trim())
        }
        return filtered.joinToString("\n").trimEnd()
    }

    private fun loadUserImageAttachments(imagePaths: List<String>): List<UserImageAttachment> {
        if (imagePaths.isEmpty()) return emptyList()
        return imagePaths.mapIndexedNotNull { index, rawPath ->
            val path = rawPath.trim()
            if (path.isBlank()) return@mapIndexedNotNull null
            val file = File(path)
            if (!file.isFile) return@mapIndexedNotNull null
            val image = runCatching { ImageIO.read(file) }.getOrNull() ?: return@mapIndexedNotNull null
            val originalName = file.name.ifBlank { path.substringAfterLast(File.separatorChar) }
            UserImageAttachment(
                displayName = "image#${index + 1}",
                originalFileName = originalName,
                image = image,
            )
        }
    }

    private fun installImagePreviewOpenAction(component: JComponent, attachment: UserImageAttachment) {
        component.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        component.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(event: MouseEvent) {
                    if (event.clickCount >= 2 && SwingUtilities.isLeftMouseButton(event)) {
                        openImagePreviewDialog(attachment)
                    }
                }
            }
        )
    }

    private fun openImagePreviewDialog(attachment: UserImageAttachment) {
        if (GraphicsEnvironment.isHeadless()) return
        val owner = SwingUtilities.getWindowAncestor(this)
        val dialog = JDialog(owner, attachment.displayName, Dialog.ModalityType.APPLICATION_MODAL)
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE

        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val maxWidth = (screenSize.width * USER_IMAGE_PREVIEW_MAX_SCREEN_RATIO).toInt().coerceAtLeast(320)
        val maxHeight = (screenSize.height * USER_IMAGE_PREVIEW_MAX_SCREEN_RATIO).toInt().coerceAtLeast(240)
        val preview = scaleImageToFit(attachment.image, maxWidth, maxHeight)
        val imageLabel = JBLabel(ImageIcon(preview)).apply {
            horizontalAlignment = SwingConstants.CENTER
            verticalAlignment = SwingConstants.CENTER
            border = JBUI.Borders.empty(8)
        }
        val scroller = JScrollPane(imageLabel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            viewport.isOpaque = false
            isOpaque = false
        }
        dialog.contentPane.layout = BorderLayout()
        dialog.contentPane.add(scroller, BorderLayout.CENTER)
        dialog.pack()
        dialog.minimumSize = Dimension(320, 240)
        dialog.setLocationRelativeTo(owner ?: this)
        dialog.isVisible = true
    }

    private fun scaleImageToFit(image: BufferedImage, maxWidth: Int, maxHeight: Int): BufferedImage {
        if (image.width <= maxWidth && image.height <= maxHeight) {
            return image
        }

        val scaleFactor = min(
            maxWidth.toDouble() / image.width.toDouble(),
            maxHeight.toDouble() / image.height.toDouble(),
        )
        val targetWidth = (image.width * scaleFactor).toInt().coerceAtLeast(1)
        val targetHeight = (image.height * scaleFactor).toInt().coerceAtLeast(1)
        val scaled = BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_ARGB)
        val graphics = scaled.createGraphics()
        try {
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.drawImage(image, 0, 0, targetWidth, targetHeight, null)
        } finally {
            graphics.dispose()
        }
        return scaled
    }

    private fun renderLightweightContent(content: String, fontSize: Int) {
        contentHost.removeAll()
        contentHost.add(contentPane, BorderLayout.CENTER)

        val summarized = buildLightweightSummary(content)
        val doc = contentPane.styledDocument
        doc.remove(0, doc.length)
        val attrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(attrs, "Monospaced")
        StyleConstants.setFontSize(attrs, fontSize)
        StyleConstants.setLineSpacing(attrs, PLAIN_TEXT_LINE_SPACING)
        doc.insertString(0, summarized, attrs)
    }

    private fun buildLightweightSummary(content: String): String {
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return normalized

        val base = when (role) {
            MessageRole.ASSISTANT -> {
                val answerOnly = extractAssistantAnswerContent(normalized).ifBlank { normalized }
                stripWorkflowSectionHeadings(answerOnly).ifBlank { answerOnly }
            }
            else -> normalized
        }
        return toMarkdownPreview(base, LIGHTWEIGHT_CONTENT_MAX_CHARS)
    }

    private fun renderAssistantTraceContent(
        answerContent: String,
        traceSnapshot: StreamingTraceAssembler.TraceSnapshot,
        structured: Boolean,
    ) {
        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false
        container.border = JBUI.Borders.empty(4, 4, 1, 4)

        val processItems = traceSnapshot.items.filter { item ->
            item.kind != ExecutionTimelineParser.Kind.OUTPUT && shouldRenderProcessItem(item)
        }
        if (processItems.isNotEmpty()) {
            container.add(createTracePanel(processItems))
        }

        val outputItems = traceSnapshot.items.filter { it.kind == ExecutionTimelineParser.Kind.OUTPUT }
        if (outputItems.isNotEmpty()) {
            container.add(createOutputPanel(outputItems))
        }

        if (answerContent.isNotBlank()) {
            container.add(createAssistantAnswerComponent(answerContent, structured))
        }

        contentHost.removeAll()
        contentHost.add(container, BorderLayout.CENTER)
    }

    private fun shouldRenderProcessItem(item: StreamingTraceAssembler.TraceItem): Boolean {
        // Keep process area lightweight: ignore pure thinking traces in the visual timeline.
        if (item.kind == ExecutionTimelineParser.Kind.THINK) return false
        return item.detail.isNotBlank() || item.status != ExecutionTimelineParser.Status.INFO
    }

    private fun createAssistantAnswerComponent(content: String, structured: Boolean): JPanel {
        val formattedContent = formatAssistantAcknowledgementLead(content)
        if (!workflowSectionsEnabled) {
            return createMarkdownContainer(stripWorkflowSectionHeadings(formattedContent))
        }
        if (!structured) {
            return createMarkdownContainer(formattedContent)
        }

        val parseResult = WorkflowSectionParser.parse(formattedContent)
        if (parseResult.sections.isEmpty()) {
            return createMarkdownContainer(formattedContent)
        }

        val container = JPanel()
        container.layout = BoxLayout(container, BoxLayout.Y_AXIS)
        container.isOpaque = false
        container.border = JBUI.Borders.empty(6, 2, 2, 2)

        if (parseResult.remainingText.isNotBlank()) {
            container.add(createMarkdownContainer(parseResult.remainingText))
        }

        parseResult.sections.forEach { section ->
            val title = when (section.kind) {
                WorkflowSectionParser.SectionKind.PLAN -> SpecCodingBundle.message("chat.workflow.section.plan")
                WorkflowSectionParser.SectionKind.EXECUTE -> SpecCodingBundle.message("chat.workflow.section.execute")
                WorkflowSectionParser.SectionKind.VERIFY -> SpecCodingBundle.message("chat.workflow.section.verify")
            }
            val defaultExpanded = section.kind != WorkflowSectionParser.SectionKind.EXECUTE
            val expanded = workflowSectionExpanded.getOrPut(section.kind) { defaultExpanded }

            val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
            header.isOpaque = false
            header.border = JBUI.Borders.emptyTop(6)

            val titleLabel = JBLabel(title)
            titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
            header.add(titleLabel)

            val toggleButton = JButton(
                SpecCodingBundle.message(
                    if (expanded) "chat.workflow.toggle.collapse" else "chat.workflow.toggle.expand"
                )
            )
            styleInlineActionButton(toggleButton)
            toggleButton.addActionListener {
                workflowSectionExpanded[section.kind] = !expanded
                renderContent(structured = true)
            }
            header.add(toggleButton)

            container.add(header)
            if (expanded) {
                container.add(createMarkdownContainer(section.content))
            }
        }
        return container
    }

    private fun formatAssistantAcknowledgementLead(content: String): String {
        if (content.isBlank()) return content
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.isBlank()) return content
        if (normalized.startsWith("**")) return content
        if (!hasAssistantAcknowledgementPrefix(normalized)) return content

        val boundary = findAssistantAcknowledgementLeadBoundary(normalized) ?: return content
        val lead = normalized.substring(0, boundary).trim()
        val body = normalized.substring(boundary).trimStart()
        if (lead.isBlank() || body.isBlank()) return content
        if (lead.length > ASSISTANT_ACK_LEAD_MAX_LENGTH) return content

        return "**$lead**\n\n$body"
    }

    private fun sanitizeAssistantDisplayContent(content: String): String {
        if (content.isBlank()) return content
        val normalized = content
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val repairedMarkdown = repairMalformedMarkdownLayout(normalized)
        val withoutThinkingTags = THINKING_TAG_REGEX.replace(repairedMarkdown, "")
            .replace(EXCESSIVE_EMPTY_LINES_REGEX, "\n\n")
            .trim()
        return if (withoutThinkingTags.isBlank()) {
            repairedMarkdown.trim()
        } else {
            withoutThinkingTags
        }
    }

    private fun repairMalformedMarkdownLayout(content: String): String {
        if (content.isBlank()) return content
        val lines = content.lines()
        var inCodeFence = false
        return buildString(content.length + 32) {
            lines.forEachIndexed { index, rawLine ->
                val trimmed = rawLine.trimStart()
                val isFenceLine = trimmed.startsWith("```")
                val repairedLine = if (!inCodeFence && !isFenceLine) {
                    demoteOrderedMarkdownSubheadings(
                        repairInlineMarkdownHeadings(rawLine),
                    )
                } else {
                    rawLine
                }
                append(repairedLine)
                if (index < lines.lastIndex) {
                    append('\n')
                }
                if (isFenceLine) {
                    inCodeFence = !inCodeFence
                }
            }
        }
    }

    private fun repairInlineMarkdownHeadings(line: String): String {
        var repaired = line
        while (true) {
            val match = INLINE_MARKDOWN_HEADING_REGEX.find(repaired) ?: return repaired
            val prefix = match.groupValues[1]
            val heading = match.groupValues[2].trimStart()
            val next = repaired.replaceRange(match.range, "$prefix\n\n$heading")
            if (next == repaired) {
                return repaired
            }
            repaired = next
        }
    }

    private fun demoteOrderedMarkdownSubheadings(text: String): String {
        return text.lineSequence()
            .joinToString("\n") { line ->
                ORDERED_SUBHEADING_LINE_REGEX.replace(line) { match ->
                    val indent = match.groupValues[1]
                    val orderedPrefix = match.groupValues[2]
                    val body = match.groupValues[3]
                    "$indent$orderedPrefix $body"
                }
            }
    }

    private fun hasAssistantAcknowledgementPrefix(content: String): Boolean {
        val trimmed = content.trimStart()
        if (ASSISTANT_ACK_PREFIXES_ZH.any { trimmed.startsWith(it) }) {
            return true
        }

        val normalized = trimmed.lowercase(Locale.ROOT)
        return ASSISTANT_ACK_PREFIXES_EN.any { prefix ->
            normalized.startsWith(prefix) &&
                (normalized.length == prefix.length || isAssistantAcknowledgementBoundary(normalized[prefix.length]))
        }
    }

    private fun findAssistantAcknowledgementLeadBoundary(content: String): Int? {
        val sentenceEnd = ASSISTANT_ACK_SENTENCE_END_REGEX.find(content)
            ?.range
            ?.last
            ?.plus(1)
        val lineBreak = content.indexOf('\n').takeIf { it > 0 }
        val primaryBoundary = listOfNotNull(sentenceEnd, lineBreak)
            .filter { it in 1 until content.length }
            .minOrNull()
        if (primaryBoundary != null) {
            return primaryBoundary
        }

        val shortComma = ASSISTANT_ACK_COMMA_REGEX.find(content)
            ?.range
            ?.last
            ?.plus(1)
            ?.takeIf { it <= ASSISTANT_ACK_COMMA_MAX_INDEX }
        return shortComma
    }

    private fun isAssistantAcknowledgementBoundary(ch: Char): Boolean {
        return ch.isWhitespace() || ch in ASSISTANT_ACK_BOUNDARY_CHARS
    }

    private fun stripWorkflowSectionHeadings(content: String): String {
        val parseResult = WorkflowSectionParser.parse(content)
        val normalized = if (parseResult.sections.isEmpty()) {
            content
        } else {
            buildString {
                if (parseResult.remainingText.isNotBlank()) {
                    append(parseResult.remainingText.trim())
                }
                parseResult.sections.forEach { section ->
                    val sectionContent = section.content.trim()
                    if (sectionContent.isBlank()) return@forEach
                    if (isNotEmpty()) appendLine().appendLine()
                    append(sectionContent)
                }
            }
        }
        return stripLooseWorkflowHeadingLines(normalized)
    }

    private fun stripLooseWorkflowHeadingLines(content: String): String {
        if (content.isBlank()) return content
        var inCodeFence = false
        val kept = mutableListOf<String>()
        content.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                kept += line
                return@forEach
            }
            if (!inCodeFence && isWorkflowHeadingLine(trimmed)) {
                return@forEach
            }
            kept += line
        }
        return kept.joinToString("\n").trimEnd()
    }

    private fun isWorkflowHeadingLine(trimmedLine: String): Boolean {
        if (trimmedLine.isBlank()) return false
        var normalized = trimmedLine
            .replace(Regex("^#+\\s*"), "")
            .replace(Regex("^[-*+]\\s+"), "")
            .replace(Regex("^\\d+[.)]\\s+"), "")
            .trim()
            .removePrefix("**")
            .removeSuffix("**")
            .removePrefix("__")
            .removeSuffix("__")
            .removePrefix("`")
            .removeSuffix("`")
            .trim()
            .trimEnd(':', '：')
            .trim()
        if (normalized.isBlank()) return false
        normalized = normalized.lowercase(Locale.ROOT)
        return normalized in WORKFLOW_HEADING_TITLES
    }

    private fun createTracePanel(items: List<StreamingTraceAssembler.TraceItem>): JPanel {
        val displayItems = mergeTraceItemsForDisplay(items)
            .takeLast(MAX_TIMELINE_VISIBLE_ITEMS)

        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = traceCardBackgroundColor()
        wrapper.border = buildRoundedContainerBorder(
            lineColor = traceCardBorderColor(),
            arc = 12,
            padding = JBUI.insets(7, 9, 6, 9),
        )

        val summaryBar = JPanel(BorderLayout())
        summaryBar.isOpaque = false

        val summaryLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        summaryLeft.isOpaque = false

        val running = items.any { it.status == ExecutionTimelineParser.Status.RUNNING }
        if (running) {
            summaryLeft.add(
                createRunningIndicator(
                    color = statusColor(ExecutionTimelineParser.Status.RUNNING),
                    size = 11,
                    tooltip = statusLabel(ExecutionTimelineParser.Status.RUNNING),
                )
            )
        } else {
            val summaryIcon = JBLabel("●")
            summaryIcon.foreground = kindColor(ExecutionTimelineParser.Kind.TASK)
            summaryIcon.font = summaryIcon.font.deriveFont(11f)
            summaryLeft.add(summaryIcon)
        }

        val summaryLabel = JBLabel(SpecCodingBundle.message("chat.timeline.summary.label"))
        summaryLabel.font = summaryLabel.font.deriveFont(java.awt.Font.BOLD, 12f)
        summaryLeft.add(summaryLabel)

        val readCount = items.count { it.kind == ExecutionTimelineParser.Kind.READ }
        val editCount = items.count { it.kind == ExecutionTimelineParser.Kind.EDIT }
        val verifyCount = items.count { it.kind == ExecutionTimelineParser.Kind.VERIFY }
        val stepCount = items.size
        summaryLeft.add(createSummaryBadge("${kindLabel(ExecutionTimelineParser.Kind.READ)} $readCount"))
        summaryLeft.add(createSummaryBadge("${kindLabel(ExecutionTimelineParser.Kind.EDIT)} $editCount"))
        summaryLeft.add(createSummaryBadge("${kindLabel(ExecutionTimelineParser.Kind.VERIFY)} $verifyCount"))
        summaryLeft.add(createSummaryBadge(SpecCodingBundle.message("chat.timeline.summary.steps", stepCount)))
        elapsedSummaryText()?.let { elapsed ->
            summaryLeft.add(createSummaryBadge(SpecCodingBundle.message("chat.timeline.summary.elapsed", elapsed)))
        }
        summaryBar.add(summaryLeft, BorderLayout.CENTER)

        if (timelineExpandButtonsVisible) {
            val toggleButton = JButton(
                SpecCodingBundle.message(
                    if (traceExpanded) "chat.timeline.toggle.collapse" else "chat.timeline.toggle.expand"
                )
            )
            styleInlineActionButton(toggleButton)
            toggleButton.addActionListener {
                traceExpanded = !traceExpanded
                renderContent()
            }
            summaryBar.add(toggleButton, BorderLayout.EAST)
        }

        wrapper.add(summaryBar, BorderLayout.NORTH)

        if (traceExpanded) {
            val listPanel = JPanel()
            listPanel.layout = BoxLayout(listPanel, BoxLayout.Y_AXIS)
            listPanel.isOpaque = false
            listPanel.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(traceSectionDividerColor(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(6),
            )

            displayItems.forEach { item ->
                listPanel.add(createTraceItemRow(item))
            }
            wrapper.add(listPanel, BorderLayout.CENTER)
        }

        val container = JPanel(BorderLayout())
        container.isOpaque = false
        container.border = JBUI.Borders.emptyBottom(4)
        container.add(wrapper, BorderLayout.CENTER)
        return container
    }

    private fun createOutputPanel(items: List<StreamingTraceAssembler.TraceItem>): JPanel {
        val mergedOutput = mergeOutputItemsForDisplay(
            items = items.takeLast(MAX_TIMELINE_VISIBLE_ITEMS),
            filterLevel = outputFilterLevel,
            charBudget = if (outputExpanded) null else OUTPUT_COLLAPSED_CHAR_BUDGET,
        )
        val wrapper = JPanel(BorderLayout())
        wrapper.isOpaque = true
        wrapper.background = outputCardBackgroundColor()
        wrapper.border = buildRoundedContainerBorder(
            lineColor = outputCardBorderColor(),
            arc = 12,
            padding = JBUI.insets(7, 9, 6, 9),
        )

        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        left.isOpaque = false

        val running = mergedOutput.status == ExecutionTimelineParser.Status.RUNNING
        if (running) {
            left.add(
                createRunningIndicator(
                    color = statusColor(ExecutionTimelineParser.Status.RUNNING),
                    size = 11,
                    tooltip = statusLabel(ExecutionTimelineParser.Status.RUNNING),
                )
            )
        } else {
            val icon = JBLabel("▤")
            icon.foreground = kindColor(ExecutionTimelineParser.Kind.OUTPUT)
            icon.font = icon.font.deriveFont(12f)
            left.add(icon)
        }

        val title = JBLabel(SpecCodingBundle.message("chat.timeline.kind.output"))
        title.font = title.font.deriveFont(java.awt.Font.BOLD, 12f)
        left.add(title)

        left.add(createSummaryBadge(items.size.toString()))
        header.add(left, BorderLayout.CENTER)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        right.isOpaque = false

        val filterButton = JButton(
            SpecCodingBundle.message(
                "chat.timeline.output.filter.toggle",
                outputFilterLabel(outputFilterLevel),
            )
        )
        styleInlineActionButton(filterButton)
        filterButton.addActionListener {
            outputFilterLevel = outputFilterLevel.next()
            renderContent()
        }
        right.add(filterButton)

        if (timelineExpandButtonsVisible) {
            val toggleButton = JButton(
                SpecCodingBundle.message(
                    if (outputExpanded) "chat.timeline.toggle.collapse" else "chat.timeline.toggle.expand"
                )
            )
            styleInlineActionButton(toggleButton)
            toggleButton.addActionListener {
                outputExpanded = !outputExpanded
                renderContent()
            }
            right.add(toggleButton)
        }
        header.add(right, BorderLayout.EAST)

        wrapper.add(header, BorderLayout.NORTH)

        if (outputExpanded) {
            val detailHost = JPanel(BorderLayout())
            detailHost.isOpaque = false
            detailHost.border = JBUI.Borders.compound(
                JBUI.Borders.customLine(outputSectionDividerColor(), 1, 0, 0, 0),
                JBUI.Borders.emptyTop(6),
            )
            detailHost.add(
                createTraceDetailBlock(
                    StreamingTraceAssembler.TraceItem(
                        kind = ExecutionTimelineParser.Kind.OUTPUT,
                        status = mergedOutput.status,
                        detail = mergedOutput.detail,
                        fileAction = null,
                        isVerbose = true,
                    ),
                    forceVerbose = true,
                    showVerboseToggle = false,
                    alwaysExpanded = true,
                ),
                BorderLayout.CENTER
            )
            wrapper.add(detailHost, BorderLayout.CENTER)
        }

        val container = JPanel(BorderLayout())
        container.isOpaque = false
        container.border = JBUI.Borders.emptyBottom(4)
        container.add(wrapper, BorderLayout.CENTER)
        return container
    }

    private fun createTraceItemRow(item: TraceDisplayItem): JPanel {
        val row = JPanel(BorderLayout())
        row.isOpaque = true
        row.background = traceRowBackgroundColor()
        row.border = JBUI.Borders.empty(4, 2, 3, 2)

        val header = JPanel(BorderLayout())
        header.isOpaque = false

        val headerLeft = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0))
        headerLeft.isOpaque = false

        val icon = JBLabel(kindGlyph(item.kind))
        icon.foreground = kindColor(item.kind)
        icon.font = icon.font.deriveFont(12f)
        headerLeft.add(icon)

        val kindLabel = JBLabel(
            "${kindLabel(item.kind)} · ${statusLabel(item.status)}"
        )
        kindLabel.font = kindLabel.font.deriveFont(java.awt.Font.PLAIN, 11f)
        kindLabel.foreground = statusColor(item.status)
        headerLeft.add(kindLabel)

        if (item.mergedCount > 1) {
            headerLeft.add(createSummaryBadge("x${item.mergedCount}"))
        }

        if (item.fileAction != null && onWorkflowFileOpen != null) {
            val openBtn = JButton(
                SpecCodingBundle.message(
                    "chat.workflow.action.openFile",
                    abbreviateForActionButton(item.fileAction.displayPath),
                )
            )
            styleInlineActionButton(openBtn)
            openBtn.toolTipText = SpecCodingBundle.message("chat.workflow.action.openFile.tooltip", item.fileAction.displayPath)
            openBtn.addActionListener {
                onWorkflowFileOpen.invoke(item.fileAction)
            }
            headerLeft.add(openBtn)
        }
        header.add(headerLeft, BorderLayout.CENTER)

        row.add(header, BorderLayout.NORTH)
        row.add(
            createTraceDetailBlock(
                StreamingTraceAssembler.TraceItem(
                    kind = item.kind,
                    status = item.status,
                    detail = item.detail,
                    fileAction = item.fileAction,
                    isVerbose = item.isVerbose,
                )
            ),
            BorderLayout.CENTER
        )
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(4)
            add(row, BorderLayout.CENTER)
        }
    }

    private fun createTraceDetailBlock(
        item: StreamingTraceAssembler.TraceItem,
        forceVerbose: Boolean = false,
        showVerboseToggle: Boolean = true,
        alwaysExpanded: Boolean = false,
    ): JPanel {
        val block = JPanel(BorderLayout())
        block.isOpaque = false
        block.border = JBUI.Borders.empty(3, 0, 1, 0)

        val key = entryKey(item)
        val verbose = forceVerbose || item.isVerbose
        val previewLength = if (forceVerbose) TRACE_OUTPUT_PREVIEW_LENGTH else TRACE_DETAIL_PREVIEW_LENGTH
        val hasOverflow = item.detail.length > previewLength
        val collapsed = !alwaysExpanded && verbose && key !in expandedVerboseEntries
        val markdownLike = looksLikeMarkdown(item.detail, scanLimit = MARKDOWN_DETECTION_SCAN_LIMIT)
        val previewText = if (markdownLike) {
            toMarkdownPreview(item.detail, previewLength)
        } else {
            toPreview(item.detail, previewLength)
        }
        val visibleText = if (alwaysExpanded) {
            item.detail
        } else if (collapsed) {
            previewText
        } else if (!verbose && hasOverflow) {
            previewText
        } else {
            item.detail
        }

        val detailPane = JTextPane()
        configureReadableTextPane(detailPane)
        detailPane.border = JBUI.Borders.empty()
        renderTraceDetail(detailPane, visibleText)

        val detailHost = JPanel(BorderLayout())
        detailHost.isOpaque = true
        detailHost.background = traceDetailBackgroundColor()
        detailHost.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(traceDetailBorderColor(), 0, 2, 0, 0),
            JBUI.Borders.empty(2, 8, 2, 0),
        )
        detailHost.add(detailPane, BorderLayout.CENTER)
        block.add(detailHost, BorderLayout.CENTER)

        if (verbose && hasOverflow && showVerboseToggle) {
            val toggleText = if (collapsed) {
                SpecCodingBundle.message("chat.workflow.toggle.expand")
            } else {
                SpecCodingBundle.message("chat.workflow.toggle.collapse")
            }
            val toggleBtn = JButton(toggleText)
            styleInlineActionButton(toggleBtn)
            toggleBtn.addActionListener {
                if (collapsed) {
                    expandedVerboseEntries += key
                } else {
                    expandedVerboseEntries -= key
                }
                renderContent()
            }
            val controls = JPanel(FlowLayout(FlowLayout.LEFT, 0, 3))
            controls.isOpaque = false
            controls.add(toggleBtn)
            block.add(controls, BorderLayout.SOUTH)
        }

        return block
    }

    private fun createMarkdownContainer(content: String): JPanel {
        val panel = JPanel()
        panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
        panel.isOpaque = false
        val segments = splitMarkdownSegments(content)
        if (segments.isEmpty()) {
            panel.add(createMarkdownPane(content))
        } else {
            segments.forEachIndexed { index, segment ->
                if (index > 0) {
                    panel.add(JPanel().apply {
                        isOpaque = false
                        preferredSize = Dimension(0, JBUI.scale(MARKDOWN_SEGMENT_GAP))
                        minimumSize = preferredSize
                        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
                    })
                }
                when (segment) {
                    is MarkdownSegment.Markdown -> panel.add(createMarkdownPane(segment.text))
                    is MarkdownSegment.Code -> panel.add(createCodeBlockCard(segment.language, segment.code))
                }
            }
        }

        val commands = if (
            containsLoosePipeTableRows(
                content = content,
                minConsecutiveRows = COMMAND_ACTION_TABLE_BLOCK_MIN_ROWS,
            )
        ) {
            emptyList()
        } else {
            WorkflowQuickActionParser.parse(content)
                .commands
                .take(MAX_COMMAND_ACTIONS)
        }
        if (commands.isNotEmpty()) {
            panel.add(createInlineCommandActions(commands))
        }

        return panel
    }

    private fun createCodeBlockCard(language: String, code: String): JPanel {
        val normalizedLanguage = language.substringBefore(' ').trim().ifBlank { "text" }
        val normalizedCode = code.replace("\r\n", "\n").replace('\r', '\n').trimEnd('\n')
        val displayCode = if (normalizedCode.isBlank()) " " else normalizedCode

        val card = JPanel(BorderLayout())
        card.isOpaque = true
        card.background = CODE_CARD_BG
        card.border = buildRoundedContainerBorder(
            lineColor = CODE_CARD_BORDER,
            arc = 10,
            padding = JBUI.insets(6, 8, 6, 8),
        )

        val codeArea = NoWrapCodeTextPane().apply {
            isEditable = false
            isFocusable = true
            border = JBUI.Borders.empty(4, 2, 4, 2)
            font = Font("JetBrains Mono", Font.PLAIN, JBUI.scale(configuredOutputFontSize()))
            background = CODE_CARD_CODE_BG
            foreground = CODE_CARD_CODE_FG
            caretColor = foreground
            document.putProperty(PlainDocument.tabSizeAttribute, 4)
        }
        applyCodeSyntaxHighlight(codeArea, language = normalizedLanguage, code = displayCode)

        val scrollPane = JScrollPane(codeArea).apply {
            border = JBUI.Borders.empty()
            viewportBorder = JBUI.Borders.empty()
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_NEVER
            isWheelScrollingEnabled = false
            viewport.background = CODE_CARD_CODE_BG
            isOpaque = false
        }

        // Forward wheel gestures to the outer chat scroller so code cards never block list scrolling.
        val forwardWheelToParentScroller: (MouseWheelEvent) -> Unit = wheelForwarder@{ event ->
            if (event.isConsumed) return@wheelForwarder
            val sourceComponent = event.component ?: return@wheelForwarder
            val parentScrollPane =
                SwingUtilities.getAncestorOfClass(JScrollPane::class.java, scrollPane) as? JScrollPane
                    ?: return@wheelForwarder
            val pointInParent = SwingUtilities.convertPoint(sourceComponent, event.point, parentScrollPane)
            val forwarded = MouseWheelEvent(
                parentScrollPane,
                event.id,
                event.`when`,
                event.modifiersEx,
                pointInParent.x,
                pointInParent.y,
                event.xOnScreen,
                event.yOnScreen,
                event.clickCount,
                event.isPopupTrigger,
                event.scrollType,
                event.scrollAmount,
                event.wheelRotation,
                event.preciseWheelRotation,
            )
            parentScrollPane.dispatchEvent(forwarded)
            event.consume()
        }
        codeArea.addMouseWheelListener(forwardWheelToParentScroller)
        scrollPane.viewport.addMouseWheelListener(forwardWheelToParentScroller)
        scrollPane.addMouseWheelListener(forwardWheelToParentScroller)

        val lineCount = normalizedCode.lineSequence().count().coerceAtLeast(1)
        val lineHeight = codeArea.getFontMetrics(codeArea.font).height
        val hasCollapseToggle = lineCount > CODE_CARD_COLLAPSE_THRESHOLD_LINES
        var expanded = !hasCollapseToggle

        val header = JPanel(BorderLayout(JBUI.scale(6), 0))
        header.isOpaque = false
        header.border = JBUI.Borders.emptyBottom(4)

        val languageLabel = JBLabel(SpecCodingBundle.message("chat.markdown.code.languageTag", normalizedLanguage))
        languageLabel.font = JBUI.Fonts.smallFont()
        languageLabel.foreground = CODE_CARD_META_FG
        header.add(languageLabel, BorderLayout.WEST)

        val actionsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply {
            isOpaque = false
        }

        val copyButton = JButton()
        styleIconActionButton(
            button = copyButton,
            icon = AllIcons.Actions.Copy,
            tooltip = SpecCodingBundle.message("chat.message.copy.code"),
        )
        copyButton.addActionListener {
            val copied = copyToClipboard(normalizedCode)
            showCopyFeedback(copyButton, copied = copied, iconOnly = true)
        }

        val toggleButton = if (hasCollapseToggle) {
            JButton().apply {
                styleInlineActionButton(this)
                foreground = CODE_CARD_META_FG
            }
        } else {
            null
        }

        fun applyExpandState() {
            val visibleLines = when {
                !hasCollapseToggle -> lineCount
                expanded -> lineCount
                else -> lineCount.coerceIn(1, CODE_CARD_COLLAPSED_VISIBLE_LINES)
            }
            val preferredHeight = lineHeight * visibleLines + JBUI.scale(12) + codeCardHorizontalScrollbarSafeInset(scrollPane)
            scrollPane.preferredSize = Dimension(0, preferredHeight)
            toggleButton?.let { btn ->
                val textKey = if (expanded) {
                    "chat.message.code.collapse"
                } else {
                    "chat.message.code.expand"
                }
                val text = SpecCodingBundle.message(textKey)
                btn.text = text
                btn.toolTipText = text
                btn.accessibleContext.accessibleName = text
            }
            card.revalidate()
            card.repaint()
        }

        toggleButton?.let { btn ->
            btn.addActionListener {
                expanded = !expanded
                applyExpandState()
            }
            actionsPanel.add(btn)
        }
        actionsPanel.add(copyButton)
        header.add(actionsPanel, BorderLayout.EAST)
        applyExpandState()

        card.add(header, BorderLayout.NORTH)
        card.add(scrollPane, BorderLayout.CENTER)
        return card
    }

    private fun codeCardHorizontalScrollbarSafeInset(scrollPane: JScrollPane): Int {
        if (!System.getProperty("os.name").lowercase(Locale.ROOT).contains("mac")) {
            return 0
        }
        return scrollPane.horizontalScrollBar.preferredSize.height.coerceAtLeast(JBUI.scale(CODE_CARD_MAC_SCROLLBAR_SAFE_INSET))
    }

    private fun applyCodeSyntaxHighlight(
        pane: JTextPane,
        language: String,
        code: String,
    ) {
        val doc = pane.styledDocument
        doc.remove(0, doc.length)
        doc.insertString(0, code, null)

        if (doc.length <= 0) return

        val baseAttrs = SimpleAttributeSet().apply {
            StyleConstants.setFontFamily(this, pane.font.family)
            StyleConstants.setFontSize(this, pane.font.size)
            StyleConstants.setForeground(this, CODE_CARD_CODE_FG)
        }
        doc.setCharacterAttributes(0, doc.length, baseAttrs, true)

        if (code.length > CODE_CARD_HIGHLIGHT_MAX_CHARS) return

        val fileType = resolveCodeFenceFileType(language)
        var highlighted = false

        if (fileType !== PlainTextFileType.INSTANCE) {
            val syntaxHighlighter = runCatching {
                SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, null, null)
            }.getOrNull()

            if (syntaxHighlighter != null) {
                val lexer = syntaxHighlighter.highlightingLexer
                val colorScheme = EditorColorsManager.getInstance().globalScheme
                runCatching {
                    lexer.start(code)
                    while (true) {
                        val tokenType = lexer.tokenType ?: break
                        val start = lexer.tokenStart
                        val end = lexer.tokenEnd
                        if (end > start) {
                            val tokenAttrs = mergeTokenAttributes(
                                keys = syntaxHighlighter.getTokenHighlights(tokenType),
                                scheme = colorScheme,
                            )
                            if (tokenAttrs != null) {
                                doc.setCharacterAttributes(start, end - start, tokenAttrs, false)
                                if (isMeaningfulHighlightStyle(tokenAttrs)) {
                                    highlighted = true
                                }
                            }
                        }
                        lexer.advance()
                    }
                }
            }
        }

        if (!highlighted) {
            applySimpleCodeFallbackHighlight(
                doc = doc,
                language = language,
                code = code,
            )
        }
    }

    private fun mergeTokenAttributes(
        keys: Array<TextAttributesKey>,
        scheme: EditorColorsScheme,
    ): SimpleAttributeSet? {
        var foreground: java.awt.Color? = null
        var background: java.awt.Color? = null
        var bold = false
        var italic = false

        keys.forEach { key ->
            val attrs = scheme.getAttributes(key) ?: key.defaultAttributes ?: return@forEach
            if (foreground == null) {
                foreground = attrs.foregroundColor
            }
            if (background == null) {
                background = attrs.backgroundColor
            }
            bold = bold || (attrs.fontType and Font.BOLD) != 0
            italic = italic || (attrs.fontType and Font.ITALIC) != 0
        }

        if (foreground == null && background == null && !bold && !italic) return null

        return SimpleAttributeSet().apply {
            foreground?.let { StyleConstants.setForeground(this, it) }
            background?.let { StyleConstants.setBackground(this, it) }
            StyleConstants.setBold(this, bold)
            StyleConstants.setItalic(this, italic)
        }
    }

    private fun isMeaningfulHighlightStyle(attrs: SimpleAttributeSet): Boolean {
        val hasForeground = attrs.isDefined(StyleConstants.Foreground)
        val hasBackground = attrs.isDefined(StyleConstants.Background)
        val hasBold = attrs.isDefined(StyleConstants.Bold) && StyleConstants.isBold(attrs)
        val hasItalic = attrs.isDefined(StyleConstants.Italic) && StyleConstants.isItalic(attrs)
        if (hasBackground || hasBold || hasItalic) return true
        if (!hasForeground) return false
        return StyleConstants.getForeground(attrs) != CODE_CARD_CODE_FG
    }

    private fun applySimpleCodeFallbackHighlight(
        doc: StyledDocument,
        language: String,
        code: String,
    ) {
        val normalizedLanguage = language.trim().lowercase(Locale.ROOT)
        val keywordSet = when (normalizedLanguage) {
            "java", "kotlin", "kt", "kts", "javascript", "js", "typescript", "ts", "tsx", "jsx", "go", "rust", "rs", "c", "cpp", "c++", "cxx" -> COMMON_C_STYLE_KEYWORDS
            "python", "py" -> PYTHON_KEYWORDS
            "sql" -> SQL_KEYWORDS
            "shell", "bash", "sh", "zsh", "powershell", "ps1" -> SHELL_KEYWORDS
            else -> COMMON_C_STYLE_KEYWORDS
        }

        val keywordPattern = """\b(?:${keywordSet.joinToString("|") { Regex.escape(it) }})\b"""
        val keywordRegex = if (normalizedLanguage == "sql") {
            Regex(keywordPattern, RegexOption.IGNORE_CASE)
        } else {
            Regex(keywordPattern)
        }
        val numberRegex = Regex("""\b\d+(?:\.\d+)?\b""")
        val stringRegex = Regex(""""(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'""")
        val lineCommentRegex = Regex("""//.*$""", RegexOption.MULTILINE)
        val hashCommentRegex = Regex("""#.*$""", RegexOption.MULTILINE)
        val blockCommentRegex = Regex("""/\*.*?\*/""", setOf(RegexOption.DOT_MATCHES_ALL))

        val keywordAttrs = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, CODE_CARD_FALLBACK_KEYWORD_FG)
            StyleConstants.setBold(this, true)
        }
        val numberAttrs = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, CODE_CARD_FALLBACK_NUMBER_FG)
        }
        val stringAttrs = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, CODE_CARD_FALLBACK_STRING_FG)
        }
        val commentAttrs = SimpleAttributeSet().apply {
            StyleConstants.setForeground(this, CODE_CARD_FALLBACK_COMMENT_FG)
            StyleConstants.setItalic(this, true)
        }

        applyRegexAttributes(doc, code, keywordRegex, keywordAttrs)
        applyRegexAttributes(doc, code, numberRegex, numberAttrs)
        applyRegexAttributes(doc, code, stringRegex, stringAttrs)
        applyRegexAttributes(doc, code, lineCommentRegex, commentAttrs)
        if (normalizedLanguage in setOf("python", "py", "shell", "bash", "sh", "zsh", "powershell", "ps1", "yaml", "yml")) {
            applyRegexAttributes(doc, code, hashCommentRegex, commentAttrs)
        }
        applyRegexAttributes(doc, code, blockCommentRegex, commentAttrs)
    }

    private fun applyRegexAttributes(
        doc: StyledDocument,
        text: String,
        regex: Regex,
        attrs: SimpleAttributeSet,
    ) {
        regex.findAll(text).forEach { match ->
            val start = match.range.first
            val length = match.value.length
            if (length > 0) {
                doc.setCharacterAttributes(start, length, attrs, false)
            }
        }
    }

    private fun resolveCodeFenceFileType(language: String): FileType {
        val normalizedLanguage = language.trim().lowercase(Locale.ROOT)
        if (normalizedLanguage.isBlank()) return PlainTextFileType.INSTANCE

        val extension = CODE_FENCE_LANGUAGE_EXTENSION_ALIASES[normalizedLanguage] ?: normalizedLanguage
        val manager = FileTypeManager.getInstance()
        val byExtension = manager.getFileTypeByExtension(extension)
        if (byExtension !== PlainTextFileType.INSTANCE) {
            return byExtension
        }
        return manager.findFileTypeByName(extension.uppercase(Locale.ROOT)) ?: PlainTextFileType.INSTANCE
    }

    private fun splitMarkdownSegments(content: String): List<MarkdownSegment> {
        val normalized = content.replace("\r\n", "\n").replace('\r', '\n')
        if (normalized.isBlank()) return emptyList()

        val segments = mutableListOf<MarkdownSegment>()
        val textBuffer = StringBuilder()
        val lines = normalized.split('\n')
        var index = 0

        fun appendTextLine(line: String) {
            if (textBuffer.isNotEmpty()) textBuffer.append('\n')
            textBuffer.append(line)
        }

        fun flushText() {
            val text = textBuffer.toString()
            if (text.isNotBlank()) {
                segments += MarkdownSegment.Markdown(text)
            }
            textBuffer.setLength(0)
        }

        while (index < lines.size) {
            val line = lines[index]
            val language = parseFenceLanguage(line)
            if (language == null) {
                appendTextLine(line)
                index += 1
                continue
            }

            val codeLines = mutableListOf<String>()
            var cursor = index + 1
            var closed = false
            while (cursor < lines.size) {
                val candidate = lines[cursor]
                if (isFenceEndLine(candidate)) {
                    closed = true
                    break
                }
                codeLines += candidate
                cursor += 1
            }

            if (!closed) {
                appendTextLine(line)
                codeLines.forEach(::appendTextLine)
                break
            }

            flushText()
            val codeContent = codeLines.joinToString("\n")
            if (shouldRenderFenceAsMarkdown(language, codeContent)) {
                segments += MarkdownSegment.Markdown(codeContent.trim('\n', '\r'))
            } else {
                segments += MarkdownSegment.Code(language = language, code = codeContent)
            }
            index = cursor + 1
        }

        flushText()
        return if (segments.any { it is MarkdownSegment.Code }) segments else emptyList()
    }

    private fun shouldRenderFenceAsMarkdown(language: String, code: String): Boolean {
        val normalizedLanguage = language.trim().lowercase(Locale.ROOT)
        if (normalizedLanguage.isNotEmpty() && normalizedLanguage !in MARKDOWN_FENCE_LANGUAGES) {
            return false
        }
        val normalized = code
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace("｜", "|")
            .replace("\\|", "|")
        val lines = normalized.lines()
        if (lines.size < 2) return false

        for (index in 0 until lines.lastIndex) {
            val header = lines[index].trim()
            val separator = lines[index + 1].trim()
            if (PIPE_TABLE_ROW_REGEX.matches(header) && PIPE_TABLE_SEPARATOR_REGEX.matches(separator)) {
                return true
            }
        }
        return false
    }

    private fun parseFenceLanguage(line: String): String? {
        if (!line.startsWith("```")) return null
        if (line.length > 3 && line[3] == '`') return null
        val suffix = line.substring(3)
        if (suffix.contains('`')) return null
        return suffix.trim()
    }

    private fun isFenceEndLine(line: String): Boolean {
        return line.trimEnd() == "```"
    }

    private fun createInlineCommandActions(commands: List<String>): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
        panel.isOpaque = false
        panel.border = JBUI.Borders.empty(4, 0, 0, 0)

        val label = JBLabel("${SpecCodingBundle.message("chat.workflow.action.commandsLabel")}:")
        label.foreground = JBColor.GRAY
        label.font = label.font.deriveFont(11f)
        panel.add(label)

        commands.forEach { command ->
            panel.add(createWorkflowCommandRunButton(command, inline = true))
        }
        return panel
    }

    private fun createMarkdownPane(content: String): JTextPane {
        val pane = JTextPane()
        configureReadableTextPane(pane)
        pane.border = JBUI.Borders.emptyTop(2)
        MarkdownRenderer.render(pane, content)
        refreshTextPaneCursor(pane)
        return pane
    }

    private fun configureReadableTextPane(pane: JTextPane) {
        pane.isEditable = false
        pane.isOpaque = false
        pane.isFocusable = true
        applyConfiguredOutputFont(pane, configuredOutputFontSize())
        installTextPaneLinkHandlers(pane)
        refreshTextPaneCursor(pane)
    }

    private fun configuredOutputFontSize(): Int {
        val configured = runCatching {
            SpecCodingSettingsState.getInstance().chatOutputFontSize
        }.getOrElse {
            SpecCodingSettingsState.DEFAULT_CHAT_OUTPUT_FONT_SIZE
        }
        return SpecCodingSettingsState.normalizeChatOutputFontSize(configured)
    }

    private fun applyConfiguredOutputFont(pane: JTextPane, size: Int) {
        val current = pane.font ?: Font(Font.SANS_SERIF, Font.PLAIN, size)
        if (current.size != size) {
            pane.font = current.deriveFont(size.toFloat())
        }
    }

    private fun renderTraceDetail(pane: JTextPane, content: String) {
        if (looksLikeMarkdown(content, scanLimit = MARKDOWN_DETECTION_SCAN_LIMIT)) {
            MarkdownRenderer.render(pane, content)
            refreshTextPaneCursor(pane)
            return
        }
        val doc = pane.styledDocument
        doc.remove(0, doc.length)
        val attrs = SimpleAttributeSet()
        StyleConstants.setFontFamily(attrs, "Monospaced")
        StyleConstants.setFontSize(attrs, configuredOutputFontSize())
        StyleConstants.setLineSpacing(attrs, PLAIN_TEXT_LINE_SPACING)
        doc.insertString(0, content, attrs)
        refreshTextPaneCursor(pane)
    }

    private fun looksLikeMarkdown(content: String, scanLimit: Int = Int.MAX_VALUE): Boolean {
        val markerSample = if (scanLimit == Int.MAX_VALUE) {
            content
        } else {
            content.take(scanLimit.coerceAtLeast(0))
        }
        val normalizedSample = normalizeMarkdownSignalSample(
            normalizePipeTableLine(markerSample),
        )
        if (MARKDOWN_INLINE_LINK_REGEX.containsMatchIn(normalizedSample)) return true
        if (normalizedSample.contains("**") || normalizedSample.contains('`')) return true
        var scannedChars = 0
        var previousPipeLikeLine = false
        for (line in normalizedSample.lineSequence()) {
            val trimmed = normalizeMarkdownSignalSample(line).trimStart()
            scannedChars += line.length + 1
            if (MARKDOWN_HORIZONTAL_RULE_REGEX.matches(trimmed.trim())) {
                return true
            }
            val matched = MARKDOWN_HEADING_REGEX.matches(trimmed) ||
                BLOCKQUOTE_LINE_REGEX.matches(trimmed) ||
                UNORDERED_LIST_ITEM_REGEX.matches(trimmed) ||
                ORDERED_LIST_ITEM_REGEX.matches(trimmed)
            if (matched) {
                return true
            }
            val currentPipeLikeLine = PIPE_TABLE_ROW_REGEX.matches(trimmed)
            if (previousPipeLikeLine && PIPE_TABLE_SEPARATOR_REGEX.matches(trimmed)) {
                return true
            }
            previousPipeLikeLine = currentPipeLikeLine
            if (scannedChars >= scanLimit) {
                break
            }
        }
        if (containsLoosePipeTableRows(normalizedSample, minConsecutiveRows = LOOSE_PIPE_TABLE_DETECTION_MIN_ROWS)) {
            return true
        }
        return false
    }

    private fun installTextPaneLinkHandlers(pane: JTextPane) {
        if (pane.getClientProperty(TEXT_PANE_LINK_HANDLERS_KEY) == true) {
            return
        }
        pane.putClientProperty(TEXT_PANE_LINK_HANDLERS_KEY, true)
        pane.addHyperlinkListener { event ->
            if (event.eventType != HyperlinkEvent.EventType.ACTIVATED) {
                return@addHyperlinkListener
            }
            val target = event.url?.toString().orEmpty().ifBlank { event.description.orEmpty() }
            handleActivatedLinkTarget(target)
        }
        val linkMouseHandler = object : MouseAdapter() {
            override fun mouseMoved(event: MouseEvent) {
                updateTextPaneCursorForPointer(pane, event)
            }

            override fun mouseExited(event: MouseEvent) {
                refreshTextPaneCursor(pane)
            }

            override fun mouseClicked(event: MouseEvent) {
                if (!SwingUtilities.isLeftMouseButton(event) || event.clickCount != 1) {
                    return
                }
                val target = resolveStyledLinkTargetAt(pane, event) ?: return
                if (handleActivatedLinkTarget(target)) {
                    event.consume()
                }
            }
        }
        pane.addMouseListener(linkMouseHandler)
        pane.addMouseMotionListener(linkMouseHandler)
    }

    private fun updateTextPaneCursorForPointer(pane: JTextPane, event: MouseEvent) {
        if (pane.contentType.contains("html", ignoreCase = true)) {
            return
        }
        pane.cursor = if (resolveStyledLinkTargetAt(pane, event) != null) {
            Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        } else {
            defaultTextPaneCursor(pane)
        }
    }

    private fun refreshTextPaneCursor(pane: JTextPane) {
        pane.cursor = defaultTextPaneCursor(pane)
    }

    private fun defaultTextPaneCursor(pane: JTextPane): Cursor {
        return if (pane.contentType.contains("html", ignoreCase = true)) {
            Cursor.getDefaultCursor()
        } else {
            Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR)
        }
    }

    private fun resolveStyledLinkTargetAt(pane: JTextPane, event: MouseEvent): String? {
        if (pane.contentType.contains("html", ignoreCase = true) || pane.document.length <= 0) {
            return null
        }
        val offset = runCatching { pane.viewToModel2D(event.point) }.getOrDefault(-1)
        if (offset !in 0 until pane.document.length) {
            return null
        }
        return MarkdownRenderer.extractLinkTarget(
            pane.styledDocument.getCharacterElement(offset).attributes
        )
    }

    private fun handleActivatedLinkTarget(rawTarget: String): Boolean {
        val target = rawTarget.trim()
        if (target.isBlank()) {
            return false
        }

        val fileAction = WorkflowQuickActionParser.parseFileActionCandidate(target)
        if (fileAction != null && onWorkflowFileOpen != null) {
            onWorkflowFileOpen.invoke(fileAction)
            return true
        }

        if (target.startsWith("http://", ignoreCase = true) || target.startsWith("https://", ignoreCase = true)) {
            BrowserUtil.browse(target)
            return true
        }

        return false
    }

    private fun containsLoosePipeTableRows(content: String, minConsecutiveRows: Int): Boolean {
        if (content.isBlank()) return false
        val targetRows = minConsecutiveRows.coerceAtLeast(2)
        var inCodeFence = false
        var consecutiveRows = 0

        content.lineSequence().forEach { rawLine ->
            val trimmed = rawLine.trimStart()
            if (trimmed.startsWith("```")) {
                inCodeFence = !inCodeFence
                consecutiveRows = 0
                return@forEach
            }
            if (inCodeFence) return@forEach

            if (isLoosePipeTableRow(rawLine)) {
                consecutiveRows += 1
                if (consecutiveRows >= targetRows) {
                    return true
                }
            } else {
                consecutiveRows = 0
            }
        }

        return false
    }

    private fun isLoosePipeTableRow(line: String): Boolean {
        val normalized = normalizePipeTableLine(line).trim()
        if (normalized.length < 3) return false
        if (normalized.count { it == '|' } < 2) return false
        val nonBlankCells = normalized
            .trim('|')
            .split('|')
            .count { it.trim().isNotBlank() }
        return nonBlankCells >= 2
    }

    private fun normalizePipeTableLine(text: String): String {
        return text
            .replace("\\|", "|")
            .replace('｜', '|')
            .replace('│', '|')
            .replace('┃', '|')
            .replace('丨', '|')
    }

    private fun normalizeMarkdownSignalSample(text: String): String {
        if (text.isEmpty()) return text
        var changed = false
        val normalized = buildString(text.length) {
            text.forEach { ch ->
                when {
                    MARKDOWN_IGNORED_CHARS.contains(ch) -> {
                        changed = true
                    }
                    MARKDOWN_ASTERISK_ALIASES.contains(ch) -> {
                        if (ch != '*') changed = true
                        append('*')
                    }
                    ch == MARKDOWN_NBSP_CHAR -> {
                        changed = true
                        append(' ')
                    }
                    else -> append(ch)
                }
            }
        }
        var compacted = normalized
        if (compacted.contains('*')) {
            val collapsed = MARKDOWN_STAR_PAIR_WITH_SPACES_REGEX.replace(compacted, "**")
            if (collapsed != compacted) {
                compacted = collapsed
                changed = true
            }
        }
        return if (changed) compacted else text
    }

    private fun kindLabel(kind: ExecutionTimelineParser.Kind): String = when (kind) {
        ExecutionTimelineParser.Kind.THINK -> SpecCodingBundle.message("chat.timeline.kind.think")
        ExecutionTimelineParser.Kind.READ -> SpecCodingBundle.message("chat.timeline.kind.read")
        ExecutionTimelineParser.Kind.EDIT -> SpecCodingBundle.message("chat.timeline.kind.edit")
        ExecutionTimelineParser.Kind.TASK -> SpecCodingBundle.message("chat.timeline.kind.task")
        ExecutionTimelineParser.Kind.VERIFY -> SpecCodingBundle.message("chat.timeline.kind.verify")
        ExecutionTimelineParser.Kind.TOOL -> SpecCodingBundle.message("chat.timeline.kind.tool")
        ExecutionTimelineParser.Kind.OUTPUT -> SpecCodingBundle.message("chat.timeline.kind.output")
    }

    private fun statusLabel(status: ExecutionTimelineParser.Status): String = when (status) {
        ExecutionTimelineParser.Status.RUNNING -> SpecCodingBundle.message("chat.timeline.status.running")
        ExecutionTimelineParser.Status.DONE -> SpecCodingBundle.message("chat.timeline.status.done")
        ExecutionTimelineParser.Status.ERROR -> SpecCodingBundle.message("chat.timeline.status.error")
        ExecutionTimelineParser.Status.INFO -> SpecCodingBundle.message("chat.timeline.status.info")
    }

    private fun statusColor(status: ExecutionTimelineParser.Status): java.awt.Color = when (status) {
        ExecutionTimelineParser.Status.RUNNING -> JBColor(
            java.awt.Color(0, 102, 204),
            java.awt.Color(124, 182, 255),
        )
        ExecutionTimelineParser.Status.DONE -> JBColor(
            java.awt.Color(18, 112, 52),
            java.awt.Color(107, 206, 147),
        )
        ExecutionTimelineParser.Status.ERROR -> JBColor.RED
        ExecutionTimelineParser.Status.INFO -> JBColor.GRAY
    }

    private fun kindGlyph(kind: ExecutionTimelineParser.Kind): String = when (kind) {
        ExecutionTimelineParser.Kind.THINK -> "◔"
        ExecutionTimelineParser.Kind.READ -> "▤"
        ExecutionTimelineParser.Kind.EDIT -> "✎"
        ExecutionTimelineParser.Kind.TASK -> "▸"
        ExecutionTimelineParser.Kind.VERIFY -> "✓"
        ExecutionTimelineParser.Kind.TOOL -> "⌘"
        ExecutionTimelineParser.Kind.OUTPUT -> "≡"
    }

    private fun kindColor(kind: ExecutionTimelineParser.Kind): java.awt.Color = when (kind) {
        ExecutionTimelineParser.Kind.THINK -> JBColor(java.awt.Color(85, 105, 133), java.awt.Color(170, 188, 212))
        ExecutionTimelineParser.Kind.READ -> JBColor(java.awt.Color(58, 112, 171), java.awt.Color(135, 189, 247))
        ExecutionTimelineParser.Kind.EDIT -> JBColor(java.awt.Color(25, 123, 87), java.awt.Color(109, 207, 171))
        ExecutionTimelineParser.Kind.TASK -> JBColor(java.awt.Color(92, 96, 158), java.awt.Color(164, 170, 245))
        ExecutionTimelineParser.Kind.VERIFY -> JBColor(java.awt.Color(44, 132, 79), java.awt.Color(131, 217, 164))
        ExecutionTimelineParser.Kind.TOOL -> JBColor(java.awt.Color(140, 89, 34), java.awt.Color(231, 178, 121))
        ExecutionTimelineParser.Kind.OUTPUT -> JBColor(java.awt.Color(117, 111, 140), java.awt.Color(191, 184, 220))
    }

    private fun mergeTraceItemsForDisplay(items: List<StreamingTraceAssembler.TraceItem>): List<TraceDisplayItem> {
        if (items.isEmpty()) return emptyList()

        val merged = mutableListOf<TraceDisplayItem>()
        var currentGroup = mutableListOf<StreamingTraceAssembler.TraceItem>()

        fun flush() {
            if (currentGroup.isEmpty()) return
            merged += toTraceDisplayItem(currentGroup)
            currentGroup = mutableListOf()
        }

        items.forEach { item ->
            val currentKind = currentGroup.firstOrNull()?.kind
            if (currentKind == null || currentKind == item.kind) {
                currentGroup += item
            } else {
                flush()
                currentGroup += item
            }
        }
        flush()

        return merged
    }

    private fun toTraceDisplayItem(groupItems: List<StreamingTraceAssembler.TraceItem>): TraceDisplayItem {
        val first = groupItems.first()
        val mergedCount = groupItems.size
        val status = groupItems.maxByOrNull { statusPriority(it.status) }?.status ?: first.status
        val detail = if (mergedCount == 1) {
            first.detail
        } else {
            val previews = groupItems
                .asSequence()
                .map { toPreview(it.detail, TRACE_GROUP_DETAIL_PREVIEW_LENGTH) }
                .distinct()
                .take(TRACE_GROUP_DETAIL_SAMPLE_COUNT)
                .toList()
            val more = (mergedCount - previews.size).coerceAtLeast(0)
            val lines = previews.map { "- $it" } +
                if (more > 0) listOf("... (+$more)") else emptyList()
            lines.joinToString("\n")
        }

        return TraceDisplayItem(
            kind = first.kind,
            status = status,
            detail = detail,
            fileAction = if (mergedCount == 1) first.fileAction else null,
            isVerbose = groupItems.any { it.isVerbose },
            mergedCount = mergedCount,
        )
    }

    private fun mergeOutputItemsForDisplay(
        items: List<StreamingTraceAssembler.TraceItem>,
        filterLevel: OutputFilterLevel,
        charBudget: Int? = null,
    ): OutputDisplay {
        if (items.isEmpty()) {
            return OutputDisplay(
                status = ExecutionTimelineParser.Status.INFO,
                detail = "",
            )
        }
        val status = items.maxByOrNull { statusPriority(it.status) }?.status
            ?: ExecutionTimelineParser.Status.INFO

        val lines = mutableListOf<String>()
        var previousLine: String? = null
        var previousBlank = false
        var consumedChars = 0
        var truncated = false
        outer@ for (item in items) {
            for (raw in item.detail.lineSequence()) {
                val line = raw.trimEnd('\r').trimEnd()
                val blank = line.isBlank()
                if (blank && previousBlank) continue
                if (line.isNotBlank() && line == previousLine) continue

                val estimatedChars = line.length + 1
                if (charBudget != null && consumedChars + estimatedChars > charBudget) {
                    truncated = true
                    break@outer
                }

                lines += line
                previousLine = line
                previousBlank = blank
                consumedChars += estimatedChars
            }
        }

        val mergedDetail = when {
            lines.isNotEmpty() -> buildString {
                append(lines.joinToString("\n").trim())
                if (truncated && isNotBlank()) {
                    append("\n...")
                }
            }
            charBudget != null -> {
                val fallback = items.firstOrNull()
                    ?.detail
                    ?.take(charBudget.coerceAtLeast(1))
                    ?.trim()
                    .orEmpty()
                if (fallback.length >= charBudget.coerceAtLeast(1) && fallback.isNotBlank()) {
                    "$fallback\n..."
                } else {
                    fallback
                }
            }
            else -> items.firstOrNull()?.detail?.trim().orEmpty()
        }

        return OutputDisplay(
            status = status,
            detail = applyOutputFilter(mergedDetail, filterLevel),
        )
    }

    private fun applyOutputFilter(rawDetail: String, level: OutputFilterLevel): String {
        if (level == OutputFilterLevel.ALL) return rawDetail
        if (rawDetail.isBlank()) return rawDetail
        if (shouldBypassOutputFilter(rawDetail)) return rawDetail

        val nonBlankLines = rawDetail
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (nonBlankLines.isEmpty()) return rawDetail

        val keyLines = selectKeyOutputLines(nonBlankLines)
        val filteredCount = (nonBlankLines.size - keyLines.size).coerceAtLeast(0)
        if (keyLines.isEmpty()) {
            return if (filteredCount > 0) {
                SpecCodingBundle.message("chat.timeline.output.filtered.more", filteredCount)
            } else {
                ""
            }
        }

        val body = keyLines.joinToString("\n")
        if (filteredCount <= 0) return body

        return buildString {
            append(body)
            append("\n\n")
            append(SpecCodingBundle.message("chat.timeline.output.filtered.more", filteredCount))
        }
    }

    private fun shouldBypassOutputFilter(rawDetail: String): Boolean {
        return containsLoosePipeTableRows(rawDetail, minConsecutiveRows = OUTPUT_FILTER_TABLE_BYPASS_MIN_ROWS)
    }

    private fun selectKeyOutputLines(lines: List<String>): List<String> {
        val deduped = lines
            .filter(::looksLikeWhitelistedOutputLine)
            .distinct()
        if (deduped.isNotEmpty()) {
            return deduped.take(OUTPUT_FILTER_MAX_LINES)
        }

        return emptyList()
    }

    private fun looksLikeWhitelistedOutputLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        if (looksLikeRawCodeOrPatchLine(trimmed)) return false
        if (looksLikeToolDiagnosticLine(trimmed)) return false
        return looksLikeNarrativeOutputLine(trimmed)
    }

    private fun looksLikeNarrativeOutputLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank() || trimmed.startsWith("|")) return false
        if (OUTPUT_ALLOWED_HEADING_REGEX.matches(trimmed)) return true
        if (OUTPUT_METADATA_KEY_VALUE_REGEX.matches(trimmed)) return false
        if (OUTPUT_SHORT_STATUS_REGEX.matches(trimmed)) return true

        val cjkCount = OUTPUT_CJK_CHAR_REGEX.findAll(trimmed).count()
        if (cjkCount >= OUTPUT_NARRATIVE_MIN_CJK_CHARS) return true

        val wordCount = OUTPUT_LATIN_WORD_REGEX.findAll(trimmed).count()
        if (wordCount >= OUTPUT_NARRATIVE_MIN_WORDS) return true

        val hasSentenceHint = trimmed.any { it in OUTPUT_NARRATIVE_HINT_PUNCTUATION }
        return hasSentenceHint && (
            wordCount >= OUTPUT_NARRATIVE_MIN_SENTENCE_WORDS ||
                cjkCount >= OUTPUT_NARRATIVE_MIN_SENTENCE_CJK_CHARS ||
                trimmed.length >= OUTPUT_NARRATIVE_MIN_CHARS
            )
    }

    private fun looksLikeRawCodeOrPatchLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isBlank()) return false
        val normalized = normalizeOutputClassifierLine(trimmed)
        if (trimmed.startsWith("```")) return true
        if (OUTPUT_DIFF_CONTROL_PREFIXES.any { trimmed.startsWith(it) }) return true
        if (OUTPUT_COMMAND_LINE_REGEX.matches(trimmed)) return true
        if (OUTPUT_FILE_PATH_LINE_REGEX.matches(trimmed)) return true
        if (OUTPUT_CODE_DECLARATION_REGEX.matches(normalized)) return true
        if (OUTPUT_CODE_BLOCK_ONLY_LINE_REGEX.matches(normalized)) return true
        if (OUTPUT_OPEN_CALL_REGEX.matches(normalized)) return true
        if (looksLikeSymbolDenseCodeLine(normalized)) return true
        return false
    }

    private fun looksLikeSymbolDenseCodeLine(line: String): Boolean {
        val symbolCount = line.count { it in OUTPUT_CODE_SYMBOL_HINTS }
        if (symbolCount < OUTPUT_CODE_SYMBOL_MIN_COUNT) return false
        if (line.contains("->") || line.contains("::") || line.contains("=>")) return true
        if (line.contains('(') && line.contains(')') && (line.contains('=') || line.contains('{') || line.contains('}'))) {
            return true
        }
        return line.endsWith("{") || line.endsWith(");") || line.endsWith("},")
    }

    private fun looksLikeToolDiagnosticLine(line: String): Boolean {
        val normalized = normalizeOutputClassifierLine(line)
        return OUTPUT_STACKTRACE_LOCATION_REGEX.matches(line) ||
            OUTPUT_TIMING_STATUS_REGEX.matches(line) ||
            OUTPUT_SOURCE_REFERENCE_WITH_VALUE_REGEX.matches(line) ||
            OUTPUT_IDENTIFIER_ASSIGNMENT_REGEX.matches(normalized) ||
            OUTPUT_METHOD_CALL_REGEX.matches(normalized) ||
            OUTPUT_PIPELINE_FRAGMENT_REGEX.containsMatchIn(line)
    }

    private fun normalizeOutputClassifierLine(line: String): String {
        val withoutSourcePrefix = OUTPUT_SOURCE_SNIPPET_PREFIX_REGEX.replaceFirst(line.trim(), "")
        return OUTPUT_SOURCE_LINE_NUMBER_PREFIX_REGEX.replaceFirst(withoutSourcePrefix, "").trimStart()
    }

    private fun outputFilterLabel(level: OutputFilterLevel): String {
        return when (level) {
            OutputFilterLevel.KEY -> SpecCodingBundle.message("chat.timeline.output.filter.key")
            OutputFilterLevel.ALL -> SpecCodingBundle.message("chat.timeline.output.filter.all")
        }
    }

    private fun elapsedSummaryText(): String? {
        if (!messageFinished) return null
        val start = messageStartedAtMillis ?: return null
        val finish = messageFinishedAtMillis ?: return null
        val durationMillis = (finish - start).coerceAtLeast(0L)
        return formatElapsedDuration(durationMillis)
    }

    private fun formatElapsedDuration(durationMillis: Long): String {
        if (durationMillis < 60_000L) {
            val seconds = durationMillis / 1000.0
            return String.format(Locale.US, "%.1fs", seconds)
        }
        val totalSeconds = durationMillis / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "${hours}h ${minutes}m ${seconds}s"
        } else {
            "${minutes}m ${seconds}s"
        }
    }

    private fun statusPriority(status: ExecutionTimelineParser.Status): Int {
        return when (status) {
            ExecutionTimelineParser.Status.ERROR -> 3
            ExecutionTimelineParser.Status.RUNNING -> 2
            ExecutionTimelineParser.Status.DONE -> 1
            ExecutionTimelineParser.Status.INFO -> 0
        }
    }

    private fun entryKey(item: StreamingTraceAssembler.TraceItem): String {
        val detail = item.detail
        if (detail.isBlank()) {
            return "${item.kind.name}:_"
        }
        val head = detail.take(ENTRY_KEY_HEAD_CHARS).lowercase(Locale.ROOT)
        val tail = if (detail.length > ENTRY_KEY_HEAD_CHARS) {
            detail.takeLast(ENTRY_KEY_TAIL_CHARS).lowercase(Locale.ROOT)
        } else {
            ""
        }
        val digest = 31 * (31 + detail.length) + head.hashCode() + 31 * tail.hashCode()
        return "${item.kind.name}:$digest"
    }

    private fun extractCodeBlocks() {
        val content = if (role == MessageRole.ASSISTANT) {
            sanitizeAssistantDisplayContent(contentBuilder.toString())
        } else {
            contentBuilder.toString()
        }
        val regex = Regex("```\\w*\\n([\\s\\S]*?)```")
        codeBlocks.clear()
        regex.findAll(content).forEach {
            codeBlocks.add(it.groupValues[1].trim())
        }
    }

    private fun addActionButtons() {
        // 系统消息不需要操作按钮
        if (role == MessageRole.SYSTEM) return
        if (lightweightMode) return

        buttonPanel?.let { remove(it) }

        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2))
        buttonPanel.isOpaque = false
        buttonPanel.border = JBUI.Borders.emptyTop(6)

        // 复制全文按钮
        val copyAllBtn = JButton()
        styleIconActionButton(
            button = copyAllBtn,
            icon = AllIcons.Actions.Copy,
            tooltip = SpecCodingBundle.message("chat.message.copy.all"),
        )
        copyAllBtn.addActionListener {
            val copyPayload = if (role == MessageRole.ASSISTANT) {
                sanitizeAssistantDisplayContent(contentBuilder.toString())
            } else {
                contentBuilder.toString()
            }
            val copied = copyToClipboard(copyPayload)
            showCopyFeedback(copyAllBtn, copied = copied, iconOnly = true)
        }
        buttonPanel.add(copyAllBtn)

        val continueHandler = onContinue
        if (role == MessageRole.ASSISTANT && continueHandler != null) {
            val continueBtn = JButton()
            styleIconActionButton(
                button = continueBtn,
                icon = AllIcons.Actions.Execute,
                tooltip = SpecCodingBundle.message("chat.message.continue"),
            )
            continueBtn.addActionListener { continueHandler.invoke(this) }
            buttonPanel.add(continueBtn)
        }

        // 重新生成按钮（仅 Assistant 消息）
        if (role == MessageRole.ASSISTANT && onRegenerate != null) {
            val regenBtn = JButton()
            styleIconActionButton(
                button = regenBtn,
                icon = AllIcons.Actions.Refresh,
                tooltip = SpecCodingBundle.message("chat.message.regenerate"),
            )
            regenBtn.addActionListener { onRegenerate.invoke(this) }
            buttonPanel.add(regenBtn)
        }

        // 删除按钮（User 和 Assistant 消息）
        if ((role == MessageRole.USER || role == MessageRole.ASSISTANT) && onDelete != null) {
            val deleteBtn = JButton()
            styleIconActionButton(
                button = deleteBtn,
                icon = AllIcons.Actions.GC,
                tooltip = SpecCodingBundle.message("chat.message.delete"),
            )
            deleteBtn.addActionListener { onDelete.invoke(this) }
            buttonPanel.add(deleteBtn)
        }

        this.buttonPanel = buttonPanel
        add(buttonPanel, BorderLayout.SOUTH)
        revalidate()
    }

    private fun createWorkflowCommandRunButton(command: String, inline: Boolean): JButton {
        val display = if (command.length > MAX_COMMAND_DISPLAY_LENGTH) {
            "${command.take(MAX_COMMAND_DISPLAY_LENGTH - 3)}..."
        } else {
            command
        }

        val btn = JButton(SpecCodingBundle.message("chat.workflow.action.runCommand", display))
        if (inline) {
            styleInlineActionButton(btn)
        } else {
            styleActionButton(btn)
        }
        val tooltipKey = if (command.trim().startsWith("/")) {
            "chat.workflow.action.runSlashCommand.tooltip"
        } else {
            "chat.workflow.action.runCommand.tooltip"
        }
        btn.toolTipText = SpecCodingBundle.message(tooltipKey, command)
        btn.addActionListener {
            if (onWorkflowCommandExecute != null) {
                onWorkflowCommandExecute.invoke(command)
            } else {
                val copied = copyToClipboard(command)
                showCopyFeedback(btn, copied = copied, iconOnly = false)
            }
        }
        return btn
    }

    private fun copyToClipboard(text: String): Boolean {
        val selection = StringSelection(text)
        val copiedByIde = runCatching {
            CopyPasteManager.getInstance().setContents(selection)
        }.isSuccess
        if (copiedByIde) return true

        return runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(selection, null)
            true
        }.getOrElse { false }
    }

    private fun showCopyFeedback(button: JButton, copied: Boolean, iconOnly: Boolean) {
        val previousTimer = button.getClientProperty(COPY_FEEDBACK_TIMER_KEY) as? Timer
        previousTimer?.stop()

        val originalText = button.text
        val originalIcon = button.icon
        val originalToolTip = button.toolTipText
        val originalAccessibleName = button.accessibleContext.accessibleName
        val originalForeground = button.foreground

        val feedbackText = when {
            iconOnly && copied -> COPY_FEEDBACK_ICON_SUCCESS
            iconOnly && !copied -> COPY_FEEDBACK_ICON_FAILURE
            copied -> SpecCodingBundle.message("chat.message.copy.copied")
            else -> SpecCodingBundle.message("chat.message.copy.failed")
        }
        val feedbackTip = SpecCodingBundle.message(
            if (copied) "chat.message.copy.copied" else "chat.message.copy.failed"
        )

        if (iconOnly) {
            button.icon = null
        }
        button.text = feedbackText
        button.toolTipText = feedbackTip
        button.accessibleContext.accessibleName = feedbackTip
        button.foreground = if (copied) {
            JBColor(java.awt.Color(23, 128, 62), java.awt.Color(119, 226, 160))
        } else {
            JBColor(java.awt.Color(175, 48, 48), java.awt.Color(255, 149, 149))
        }

        val timer = Timer(COPY_FEEDBACK_DURATION_MS) {
            button.text = originalText
            button.icon = originalIcon
            button.toolTipText = originalToolTip
            button.accessibleContext.accessibleName = originalAccessibleName
            button.foreground = originalForeground
            button.putClientProperty(COPY_FEEDBACK_TIMER_KEY, null)
        }
        timer.isRepeats = false
        button.putClientProperty(COPY_FEEDBACK_TIMER_KEY, timer)
        timer.start()
    }

    private fun abbreviateForActionButton(displayPath: String): String {
        val normalized = displayPath.trim()
        if (normalized.length <= MAX_ACTION_FILE_DISPLAY_LENGTH) {
            return normalized
        }
        return "...${normalized.takeLast(MAX_ACTION_FILE_DISPLAY_LENGTH - 3)}"
    }

    private fun styleActionButton(button: JButton) {
        button.margin = JBUI.insets(3, 8, 3, 8)
        button.isFocusPainted = false
        button.isFocusable = false
        button.font = button.font.deriveFont(11f)
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.border = JBUI.Borders.empty(1, 6, 1, 6)
        button.putClientProperty("JButton.buttonType", "borderless")
    }

    private fun styleInlineActionButton(button: JButton) {
        button.margin = JBUI.insets(1, 5, 1, 5)
        button.isFocusPainted = false
        button.isFocusable = false
        button.isOpaque = false
        button.isContentAreaFilled = false
        button.border = JBUI.Borders.empty(1, 3, 1, 3)
        button.font = button.font.deriveFont(11f)
        button.foreground = JBColor(
            java.awt.Color(78, 102, 132),
            java.awt.Color(159, 189, 226),
        )
        button.putClientProperty("JButton.buttonType", "borderless")
    }

    private fun styleIconActionButton(button: JButton, icon: Icon, tooltip: String) {
        styleActionButton(button)
        button.icon = icon
        button.text = ""
        button.toolTipText = tooltip
        button.accessibleContext.accessibleName = tooltip
        button.preferredSize = JBUI.size(26, 22)
        button.minimumSize = JBUI.size(26, 22)
    }

    private fun toPreview(text: String, limit: Int): String {
        if (text.isBlank()) return ""
        val targetLength = limit.coerceAtLeast(1)
        val scanBudget = targetLength + PREVIEW_EXTRA_SCAN_CHARS
        val compact = StringBuilder(scanBudget)
        var sawNonWhitespace = false
        var pendingSpace = false
        var index = 0

        while (index < text.length && compact.length < scanBudget) {
            val ch = text[index]
            if (ch.isWhitespace()) {
                if (sawNonWhitespace) {
                    pendingSpace = true
                }
            } else {
                if (pendingSpace && compact.isNotEmpty() && compact.length < scanBudget) {
                    compact.append(' ')
                }
                compact.append(ch)
                sawNonWhitespace = true
                pendingSpace = false
            }
            index += 1
        }

        val normalized = compact.toString().trim()
        if (normalized.isEmpty()) return ""
        val hasOverflow = index < text.length || normalized.length > targetLength
        return if (!hasOverflow) {
            normalized
        } else {
            normalized.take(targetLength).trimEnd() + "..."
        }
    }

    private fun toMarkdownPreview(text: String, limit: Int): String {
        if (text.isBlank()) return ""
        val targetLength = limit.coerceAtLeast(1)
        val rawBudget = (targetLength + MARKDOWN_PREVIEW_EXTRA_SCAN_CHARS).coerceAtLeast(targetLength)
        val sampled = text.take(rawBudget)
        val normalized = sampled
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .trim()
        if (normalized.length <= targetLength && sampled.length == text.length) {
            return normalized
        }

        val clipped = normalized.take(targetLength).trimEnd()
        val splitAt = clipped.lastIndexOf('\n')
        if (splitAt >= targetLength / 2) {
            return clipped.substring(0, splitAt).trimEnd() + "\n..."
        }
        return clipped + "..."
    }

    private fun extractAssistantAnswerContent(content: String): String {
        if (content.isBlank()) return ""

        val kept = mutableListOf<String>()
        var skippingOutputBody = false

        content.lines().forEach { rawLine ->
            val trimmed = rawLine.trim()
            val timelineItem = ExecutionTimelineParser.parseLine(rawLine)
            if (timelineItem != null) {
                skippingOutputBody = timelineItem.kind == ExecutionTimelineParser.Kind.OUTPUT ||
                    timelineItem.kind == ExecutionTimelineParser.Kind.TOOL
                return@forEach
            }

            if (skippingOutputBody) {
                if (trimmed.isBlank()) {
                    skippingOutputBody = false
                } else if (isLikelyWorkflowHeading(trimmed)) {
                    skippingOutputBody = false
                    kept += rawLine
                }
                return@forEach
            }

            kept += rawLine
        }

        return kept.joinToString("\n").trim()
    }

    private fun isLikelyWorkflowHeading(line: String): Boolean {
        val trimmed = line.trim()
        if (WORKFLOW_MARKDOWN_HEADING_REGEX.matches(trimmed)) return true
        val normalized = trimmed
            .removePrefix("**")
            .removeSuffix("**")
            .trim()
            .trimEnd(':', '：')
            .lowercase()
        return normalized in WORKFLOW_HEADING_TITLES
    }

    private fun getBackgroundColor(): java.awt.Color = when (role) {
        MessageRole.USER -> JBColor(
            java.awt.Color(244, 248, 255),
            java.awt.Color(41, 47, 58)
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(249, 251, 253),
            java.awt.Color(38, 44, 52)
        )
        MessageRole.SYSTEM -> JBColor(
            java.awt.Color(246, 247, 249),
            java.awt.Color(45, 47, 51)
        )
        MessageRole.ERROR -> JBColor(
            java.awt.Color(255, 242, 242),
            java.awt.Color(62, 37, 37)
        )
    }

    private fun messageCardBorderColor(): java.awt.Color = when (role) {
        MessageRole.USER -> JBColor(
            java.awt.Color(221, 230, 241),
            java.awt.Color(76, 84, 96),
        )
        MessageRole.ASSISTANT -> JBColor(
            java.awt.Color(223, 230, 240),
            java.awt.Color(74, 82, 94),
        )
        MessageRole.SYSTEM -> JBColor(
            java.awt.Color(223, 228, 236),
            java.awt.Color(76, 82, 90),
        )
        MessageRole.ERROR -> JBColor(
            java.awt.Color(232, 200, 200),
            java.awt.Color(110, 76, 76),
        )
    }

    private fun traceCardBackgroundColor(): java.awt.Color = JBColor(
        java.awt.Color(252, 253, 255),
        java.awt.Color(42, 47, 55),
    )

    private fun traceCardBorderColor(): java.awt.Color = JBColor(
        java.awt.Color(214, 223, 234),
        java.awt.Color(78, 88, 99),
    )

    private fun outputCardBackgroundColor(): java.awt.Color = JBColor(
        java.awt.Color(252, 253, 255),
        java.awt.Color(41, 46, 53),
    )

    private fun outputCardBorderColor(): java.awt.Color = JBColor(
        java.awt.Color(214, 223, 234),
        java.awt.Color(78, 88, 99),
    )

    private fun traceRowBackgroundColor(): java.awt.Color = JBColor(
        java.awt.Color(250, 252, 255),
        java.awt.Color(46, 52, 60),
    )

    private fun traceDetailBackgroundColor(): java.awt.Color = JBColor(
        java.awt.Color(250, 252, 255),
        java.awt.Color(44, 50, 58),
    )

    private fun traceDetailBorderColor(): java.awt.Color = JBColor(
        java.awt.Color(205, 216, 230),
        java.awt.Color(79, 89, 101),
    )

    private fun traceSectionDividerColor(): java.awt.Color = JBColor(
        java.awt.Color(222, 228, 236),
        java.awt.Color(86, 96, 108),
    )

    private fun outputSectionDividerColor(): java.awt.Color = JBColor(
        java.awt.Color(222, 228, 236),
        java.awt.Color(86, 96, 108),
    )

    private fun createSummaryBadge(text: String): JBLabel {
        val badge = JBLabel(text)
        badge.isOpaque = true
        badge.background = JBColor(
            java.awt.Color(246, 249, 253),
            java.awt.Color(58, 65, 76),
        )
        badge.foreground = JBColor(
            java.awt.Color(92, 108, 127),
            java.awt.Color(166, 184, 208),
        )
        badge.font = badge.font.deriveFont(10f)
        badge.border = JBUI.Borders.empty(1, 6, 1, 6)
        return badge
    }

    private fun createRunningIndicator(
        color: java.awt.Color,
        size: Int,
        tooltip: String,
    ): RunningSpinnerIndicator {
        return RunningSpinnerIndicator(
            color = color,
            size = size,
        ).apply {
            toolTipText = tooltip
        }
    }

    private fun buildRoundedContainerBorder(
        lineColor: java.awt.Color,
        arc: Int,
        padding: Insets,
    ): Border {
        return CompoundBorder(
            RoundedLineBorder(
                lineColor = lineColor,
                arc = arc,
            ),
            JBUI.Borders.empty(padding.top, padding.left, padding.bottom, padding.right),
        )
    }

    private class RoundedLineBorder(
        private val lineColor: java.awt.Color,
        private val arc: Int,
        private val thickness: Int = 1,
    ) : AbstractBorder() {
        override fun paintBorder(
            c: Component?,
            g: Graphics?,
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
            g2.color = lineColor
            val safeThickness = thickness.coerceAtLeast(1)
            repeat(safeThickness) { index ->
                val offset = index + 0.5f
                val drawWidth = width - index * 2 - 1f
                val drawHeight = height - index * 2 - 1f
                if (drawWidth <= 0f || drawHeight <= 0f) return@repeat
                val arcSize = (arc - index * 2).coerceAtLeast(2).toFloat()
                g2.draw(
                    RoundRectangle2D.Float(
                        x + offset,
                        y + offset,
                        drawWidth,
                        drawHeight,
                        arcSize,
                        arcSize,
                    )
                )
            }
            g2.dispose()
        }

        override fun getBorderInsets(c: Component?): Insets = Insets(
            thickness,
            thickness,
            thickness,
            thickness,
        )

        override fun getBorderInsets(c: Component?, insets: Insets): Insets {
            insets.set(
                thickness,
                thickness,
                thickness,
                thickness,
            )
            return insets
        }
    }

    private class RunningSpinnerIndicator(
        private val color: java.awt.Color,
        size: Int,
    ) : JComponent() {
        private var angle = 0
        private val timer = Timer(SPINNER_TICK_MS) {
            angle = (angle + SPINNER_STEP_DEGREES) % 360
            repaint()
        }
        private val diameter = JBUI.scale(size)

        init {
            isOpaque = false
            preferredSize = Dimension(diameter, diameter)
            minimumSize = preferredSize
            maximumSize = preferredSize
        }

        override fun addNotify() {
            super.addNotify()
            if (!timer.isRunning) {
                timer.start()
            }
        }

        override fun removeNotify() {
            timer.stop()
            super.removeNotify()
        }

        override fun paintComponent(g: Graphics?) {
            super.paintComponent(g)
            val graphics = g as? Graphics2D ?: return
            val g2 = graphics.create() as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.stroke = BasicStroke(JBUI.scale(1).toFloat())

            val drawSize = (diameter - 2).coerceAtLeast(6)
            val arcExtent = 120

            g2.color = faded(color, alpha = 70)
            g2.drawArc(1, 1, drawSize, drawSize, 0, 360)

            g2.color = color
            g2.drawArc(1, 1, drawSize, drawSize, -angle, arcExtent)
            g2.dispose()
        }

        private fun faded(base: java.awt.Color, alpha: Int): java.awt.Color {
            val resolvedAlpha = alpha.coerceIn(0, 255)
            return java.awt.Color(base.red, base.green, base.blue, resolvedAlpha)
        }
    }

    private class NoWrapCodeTextPane : JTextPane() {
        override fun getScrollableTracksViewportWidth(): Boolean = false
    }

    companion object {
        private const val MAX_FILE_ACTIONS = 4
        private const val MAX_COMMAND_ACTIONS = 4
        private const val MAX_COMMAND_DISPLAY_LENGTH = 26
        private const val MAX_ACTION_FILE_DISPLAY_LENGTH = 30
        private const val MARKDOWN_SEGMENT_GAP = 6
        private const val CODE_CARD_COLLAPSE_THRESHOLD_LINES = 8
        private const val CODE_CARD_COLLAPSED_VISIBLE_LINES = 4
        private const val CODE_CARD_HIGHLIGHT_MAX_CHARS = 20_000
        private const val CODE_CARD_MAC_SCROLLBAR_SAFE_INSET = 12
        private const val TRACE_DETAIL_PREVIEW_LENGTH = 220
        private const val TRACE_OUTPUT_PREVIEW_LENGTH = 140
        private const val TRACE_GROUP_DETAIL_PREVIEW_LENGTH = 96
        private const val TRACE_GROUP_DETAIL_SAMPLE_COUNT = 2
        private const val MAX_TIMELINE_VISIBLE_ITEMS = 10
        private const val TRACE_RAW_PARSE_SKIP_THRESHOLD = 6_000
        private const val OUTPUT_COLLAPSED_CHAR_BUDGET = 12_000
        private const val MARKDOWN_DETECTION_SCAN_LIMIT = 6_000
        private const val ENTRY_KEY_HEAD_CHARS = 160
        private const val ENTRY_KEY_TAIL_CHARS = 80
        private const val PREVIEW_EXTRA_SCAN_CHARS = 120
        private const val MARKDOWN_PREVIEW_EXTRA_SCAN_CHARS = 240
        private const val LIGHTWEIGHT_CONTENT_MAX_CHARS = 900
        private const val PLAIN_TEXT_LINE_SPACING = 0.40f
        private const val LOOSE_PIPE_TABLE_DETECTION_MIN_ROWS = 2
        private const val OUTPUT_FILTER_TABLE_BYPASS_MIN_ROWS = 2
        private const val COMMAND_ACTION_TABLE_BLOCK_MIN_ROWS = 2
        private const val OUTPUT_FILTER_MAX_LINES = 24
        private const val OUTPUT_FILTER_FALLBACK_LINES = 6
        private const val OUTPUT_NARRATIVE_MIN_CHARS = 18
        private const val OUTPUT_NARRATIVE_MIN_WORDS = 4
        private const val OUTPUT_NARRATIVE_MIN_CJK_CHARS = 4
        private const val OUTPUT_NARRATIVE_MIN_SENTENCE_WORDS = 2
        private const val OUTPUT_NARRATIVE_MIN_SENTENCE_CJK_CHARS = 2
        private const val OUTPUT_CODE_SYMBOL_MIN_COUNT = 3
        private const val COPY_FEEDBACK_DURATION_MS = 1000
        private const val COPY_FEEDBACK_TIMER_KEY = "spec.copy.feedback.timer"
        private const val TEXT_PANE_LINK_HANDLERS_KEY = "spec.textPane.linkHandlersInstalled"
        private const val COPY_FEEDBACK_ICON_SUCCESS = "OK"
        private const val COPY_FEEDBACK_ICON_FAILURE = "!"
        private const val SPINNER_TICK_MS = 70
        private const val SPINNER_STEP_DEGREES = 20
        private const val ASSISTANT_ACK_LEAD_MAX_LENGTH = 48
        private const val ASSISTANT_ACK_COMMA_MAX_INDEX = 18
        private const val USER_IMAGE_TEXT_GAP = 8
        private const val USER_IMAGE_CHIP_GAP = 6
        private const val USER_IMAGE_ROW_VGAP = 2
        private const val USER_IMAGE_PREVIEW_MAX_SCREEN_RATIO = 0.75
        private val CODE_CARD_BG = JBColor(java.awt.Color(245, 248, 253), java.awt.Color(47, 53, 63))
        private val CODE_CARD_BORDER = JBColor(java.awt.Color(214, 224, 241), java.awt.Color(78, 88, 104))
        private val CODE_CARD_META_FG = JBColor(java.awt.Color(101, 118, 146), java.awt.Color(156, 178, 210))
        private val CODE_CARD_CODE_BG = JBColor(java.awt.Color(239, 244, 250), java.awt.Color(39, 45, 53))
        private val CODE_CARD_CODE_FG = JBColor(java.awt.Color(49, 59, 72), java.awt.Color(214, 223, 236))
        private val CODE_CARD_FALLBACK_KEYWORD_FG = JBColor(java.awt.Color(0, 92, 197), java.awt.Color(204, 120, 50))
        private val CODE_CARD_FALLBACK_STRING_FG = JBColor(java.awt.Color(6, 125, 23), java.awt.Color(106, 135, 89))
        private val CODE_CARD_FALLBACK_COMMENT_FG = JBColor(java.awt.Color(120, 125, 133), java.awt.Color(128, 128, 128))
        private val CODE_CARD_FALLBACK_NUMBER_FG = JBColor(java.awt.Color(23, 99, 170), java.awt.Color(104, 151, 187))
        private val USER_IMAGE_CHIP_BG = JBColor(java.awt.Color(249, 250, 252), java.awt.Color(45, 50, 58))
        private val USER_IMAGE_CHIP_BORDER = JBColor(java.awt.Color(214, 224, 241), java.awt.Color(78, 88, 104))
        private val USER_IMAGE_META_FG = JBColor(java.awt.Color(101, 118, 146), java.awt.Color(156, 178, 210))
        private val PROMPT_REFERENCE_FG = JBColor(
            java.awt.Color(23, 96, 186),
            java.awt.Color(136, 188, 255),
        )
        private val PROMPT_REFERENCE_BG = JBColor(
            java.awt.Color(226, 239, 255),
            java.awt.Color(55, 75, 101),
        )
        private val MARKDOWN_HEADING_REGEX = Regex("""^#{1,6}(?:\s+|(?=[^\s#])).+$""")
        private val MARKDOWN_HORIZONTAL_RULE_REGEX = Regex("""^(?:-{3,}|\*{3,}|_{3,})\s*$""")
        private val BLOCKQUOTE_LINE_REGEX = Regex("""^\s{0,3}>\s?.*$""")
        private val UNORDERED_LIST_ITEM_REGEX = Regex("""^(?:[-*]|[•●·・▪◦‣])\s*.+$""")
        private val ORDERED_LIST_ITEM_REGEX = Regex("""^\d+[.)、）．](?:\s+|(?=[^\s\d])).+$""")
        private val MARKDOWN_INLINE_LINK_REGEX = Regex("""(?<!!)\[[^\]\n]+]\(([^()\n]|\\[()])+\)""")
        private val WORKFLOW_MARKDOWN_HEADING_REGEX = Regex("""^##+(?:\s+|(?=\S)).+$""")
        private val INLINE_MARKDOWN_HEADING_REGEX = Regex("""(\S)\s*(#{2,6}\s*(?:\d+[.)、）．]\s*)?[^\s#].*)""")
        private val ORDERED_SUBHEADING_LINE_REGEX = Regex("""^(\s*)#{3,6}\s*(\d+[.)、）．])\s*(.+)$""")
        private val PIPE_TABLE_ROW_REGEX = Regex("""^\s*\|?(?:[^|\n]+\|){1,}[^|\n]*\|?\s*$""")
        private val PIPE_TABLE_SEPARATOR_REGEX = Regex("""^\s*\|?(?:\s*:?-{3,}:?\s*\|){1,}\s*$""")
        private val MARKDOWN_ASTERISK_ALIASES = charArrayOf(
            '*', '＊', '﹡', '∗', '⁎', '✱', '✲', '✳', '✻', '❇', '٭',
        )
        private val MARKDOWN_IGNORED_CHARS = charArrayOf(
            '\u200B', '\u200C', '\u200D', '\uFEFF', '\u2060',
            '\uFE0E', '\uFE0F',
        )
        private const val MARKDOWN_NBSP_CHAR = '\u00A0'
        private val MARKDOWN_STAR_PAIR_WITH_SPACES_REGEX = Regex("""\*\s+\*""")
        private val USER_IMAGE_ATTACHMENT_LINE_REGEX = Regex("""^\[(?:图片|images?|image)\]\s+.+$""", RegexOption.IGNORE_CASE)
        private val PROMPT_REFERENCE_TOKEN_REGEX = Regex("""(?<!\S)#([\p{L}\p{N}_.-]+)""")
        private val THINKING_TAG_REGEX = Regex("""</?thinking>""", RegexOption.IGNORE_CASE)
        private val EXCESSIVE_EMPTY_LINES_REGEX = Regex("""\n{3,}""")
        private val ASSISTANT_ACK_SENTENCE_END_REGEX = Regex("[。！？!?]")
        private val ASSISTANT_ACK_COMMA_REGEX = Regex("[，,]")
        private val OUTPUT_NARRATIVE_HINT_PUNCTUATION = setOf('。', '！', '？', '，', '.', '!', '?', ':', '：')
        private val OUTPUT_CJK_CHAR_REGEX = Regex("""\p{IsHan}""")
        private val OUTPUT_LATIN_WORD_REGEX = Regex("""[A-Za-z]+(?:['-][A-Za-z0-9]+)?""")
        private val OUTPUT_ALLOWED_HEADING_REGEX = Regex(
            """^(?:(?:summary|result|results|next step|next steps|note|notes|update|updated|recommendation|recommendations|analysis|answer|explanation|plan)\s*[:：].+|(?:总结|结果|下一步|说明|建议|分析|结论|回答|计划)\s*[:：].+)$""",
            RegexOption.IGNORE_CASE,
        )
        private val OUTPUT_METADATA_KEY_VALUE_REGEX = Regex(
            """^(?!summary\b|result\b|results\b|next step\b|next steps\b|note\b|notes\b|update\b|updated\b|recommendation\b|recommendations\b|analysis\b|answer\b|explanation\b|plan\b|总结|结果|下一步|说明|建议|分析|结论|回答|计划)(?:[\p{L}_][\p{L}\p{N}_.-]{0,31}|\p{IsHan}{1,8})\s*[:：]\s*\S.*$""",
            setOf(RegexOption.IGNORE_CASE),
        )
        private val OUTPUT_SHORT_STATUS_REGEX = Regex(
            """^(?:(?:ok|okay|done|ready|complete(?:d)?|success(?:ful(?:ly)?)?|warning|note|summary|result|next(?:\s+step)?|updated|created|changed|fixed|verified|passed|failed|error)\b.*|(?:已完成|完成|成功|失败|错误|警告|注意|总结|结果|下一步|已更新|已创建|已修改|已验证).*)$""",
            RegexOption.IGNORE_CASE,
        )
        private val OUTPUT_DIFF_CONTROL_PREFIXES = listOf(
            "diff --git ",
            "index ",
            "--- ",
            "+++ ",
            "@@ ",
            "*** Begin Patch",
            "*** End Patch",
            "*** Update File:",
            "*** Add File:",
            "*** Delete File:",
            "*** Move to:",
        )
        private val OUTPUT_COMMAND_LINE_REGEX = Regex(
            """^(?:PS\s+[^>]+>\s+.+|[$#]\s+.+|(?:git|rg|ls|cat|npm|pnpm|yarn|gradle|\.\/gradlew(?:\.bat)?|powershell(?:\.exe)?|python(?:3)?|node|cargo|go|java|javac|mvn|pytest|uv|specify|openspec)\b.*)$""",
            RegexOption.IGNORE_CASE,
        )
        private val OUTPUT_FILE_PATH_LINE_REGEX = Regex(
            """^(?!https?://)(?:(?:[A-Za-z]:)?[\\/])?(?:[A-Za-z0-9_.-]+[\\/])+[A-Za-z0-9_.-]+(?:\.[A-Za-z0-9]+)?(?::\d+(?::\d+)?)?$""",
        )
        private val OUTPUT_CODE_DECLARATION_REGEX = Regex(
            """^(?:[+\-])?\s*(?:@[\w.]+(?:\([^)]*\))?\s*)*(?:(?:private|public|protected|internal|override|open|abstract|sealed|data|final|const|inline|suspend|lateinit|export)\s+)*(?:class|interface|object|enum|fun|val|var|package|import|def|function|const|let|type|return|throw|catch|try|if|else|for|while|switch|case|when)\b.*$""",
            RegexOption.IGNORE_CASE,
        )
        private val OUTPUT_CODE_BLOCK_ONLY_LINE_REGEX = Regex("""^[{}\[\](),;]+$""")
        private val OUTPUT_OPEN_CALL_REGEX = Regex("""^(?:[A-Za-z_][\w.]*|[\p{IsHan}]{2,16})\([^)]*$""")
        private val OUTPUT_CODE_SYMBOL_HINTS = setOf('{', '}', '(', ')', '[', ']', ';', '=', '<', '>')
        private val OUTPUT_STACKTRACE_LOCATION_REGEX = Regex(
            """^(?:At line:\d+ char:\d+|at\s+.+:\d+|Caused by:\s+.+|Exception(?:\b|:).*)$""",
            RegexOption.IGNORE_CASE,
        )
        private val OUTPUT_TIMING_STATUS_REGEX = Regex(
            """^(?:(?:succeeded|failed|finished|completed|timed out)\s+in\s+\d+(?:ms|s|m|h)\s*:?\s*|exited(?:\s+\d+)?\s+in\s+\d+(?:ms|s|m|h)\s*:?\s*)$""",
            RegexOption.IGNORE_CASE,
        )
        private val OUTPUT_SOURCE_REFERENCE_WITH_VALUE_REGEX = Regex(
            """^(?!https?://)(?:(?:[A-Za-z]:)?[\\/])?(?:[A-Za-z0-9_.-]+[\\/])+[A-Za-z0-9_.-]+(?:\.[A-Za-z0-9]+)?(?::\d+(?::\d+)?)?(?::[\p{L}\p{N}_.-]+)+(?:=.*)?$""",
        )
        private val OUTPUT_IDENTIFIER_ASSIGNMENT_REGEX = Regex(
            """^(?:[A-Za-z_][\w.-]*|[\p{IsHan}]{2,16})\s*=\s*.+$""",
        )
        private val OUTPUT_METHOD_CALL_REGEX = Regex(
            """^(?:[A-Za-z_][\w.]*|[\p{IsHan}]{2,16})\([^)]*\)\s*$""",
        )
        private val OUTPUT_PIPELINE_FRAGMENT_REGEX = Regex(
            """\|\s*(?:Select-Object|Where-Object|ForEach-Object|Get-Content|Set-Content|Out-String|Format-Table)\b""",
            RegexOption.IGNORE_CASE,
        )
        private val OUTPUT_SOURCE_SNIPPET_PREFIX_REGEX = Regex(
            """^(?!https?://)(?:(?:[A-Za-z]:)?[\\/])?(?:[A-Za-z0-9_.-]+[\\/])+[A-Za-z0-9_.-]+(?:\.[A-Za-z0-9]+)?:\d+:\s*""",
        )
        private val OUTPUT_SOURCE_LINE_NUMBER_PREFIX_REGEX = Regex("""^\d+:\s*""")
        private val ASSISTANT_ACK_PREFIXES_ZH = listOf(
            "好的",
            "收到",
            "明白了",
            "明白",
            "了解了",
            "了解",
            "没问题",
            "当然可以",
            "可以的",
        )
        private val ASSISTANT_ACK_PREFIXES_EN = listOf(
            "ok",
            "okay",
            "sure",
            "got it",
            "sounds good",
        )
        private val ASSISTANT_ACK_BOUNDARY_CHARS = setOf(
            ',', '，', '.', '。', '!', '！', '?', '？', ':', '：',
        )
        private val WORKFLOW_HEADING_TITLES = setOf(
            "plan", "planning", "计划", "规划",
            "execute", "execution", "implement", "执行", "实施",
            "verify", "verification", "test", "验证", "测试",
        )
        private val MARKDOWN_FENCE_LANGUAGES = setOf(
            "markdown",
            "md",
            "mdx",
            "mkdn",
            "mdown",
        )
        private val CODE_FENCE_LANGUAGE_EXTENSION_ALIASES = mapOf(
            "kotlin" to "kt",
            "kt" to "kt",
            "kts" to "kts",
            "java" to "java",
            "python" to "py",
            "py" to "py",
            "javascript" to "js",
            "js" to "js",
            "typescript" to "ts",
            "ts" to "ts",
            "tsx" to "tsx",
            "jsx" to "jsx",
            "shell" to "sh",
            "bash" to "sh",
            "sh" to "sh",
            "zsh" to "zsh",
            "powershell" to "ps1",
            "ps1" to "ps1",
            "json" to "json",
            "yaml" to "yml",
            "yml" to "yml",
            "xml" to "xml",
            "html" to "html",
            "css" to "css",
            "scss" to "scss",
            "sql" to "sql",
            "go" to "go",
            "rust" to "rs",
            "rs" to "rs",
            "c" to "c",
            "cpp" to "cpp",
            "c++" to "cpp",
            "cxx" to "cpp",
            "h" to "h",
            "hpp" to "hpp",
            "markdown" to "md",
            "md" to "md",
            "dockerfile" to "dockerfile",
            "text" to "txt",
            "txt" to "txt",
        )
        private val COMMON_C_STYLE_KEYWORDS = setOf(
            "abstract", "assert", "async", "await", "bool", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "data", "default", "def", "do", "double", "else", "enum", "export", "extends", "extern",
            "false", "final", "finally", "float", "for", "fun", "function", "if", "implements", "import", "in", "inline",
            "int", "interface", "internal", "let", "long", "mut", "namespace", "new", "null", "object", "open", "operator",
            "override", "package", "private", "protected", "public", "register", "return", "sealed", "short", "signed",
            "static", "struct", "super", "switch", "this", "throw", "throws", "trait", "true", "try", "type", "typeof",
            "union", "unsafe", "unsigned", "use", "val", "var", "void", "volatile", "when", "where", "while", "with",
            "yield",
        )
        private val PYTHON_KEYWORDS = setOf(
            "and", "as", "assert", "async", "await", "break", "class", "continue", "def", "del", "elif", "else", "except",
            "False", "finally", "for", "from", "global", "if", "import", "in", "is", "lambda", "None", "nonlocal", "not",
            "or", "pass", "raise", "return", "True", "try", "while", "with", "yield",
        )
        private val SQL_KEYWORDS = setOf(
            "select", "from", "where", "join", "left", "right", "inner", "outer", "on", "group", "by", "order", "having",
            "limit", "offset", "insert", "into", "values", "update", "set", "delete", "create", "table", "alter", "drop",
            "primary", "key", "foreign", "references", "index", "distinct", "union", "all", "as", "and", "or", "not",
            "null", "is", "like", "between", "exists", "case", "when", "then", "else", "end",
        )
        private val SHELL_KEYWORDS = setOf(
            "if", "then", "else", "fi", "for", "do", "done", "while", "in", "case", "esac", "function", "return", "exit",
            "break", "continue", "export", "readonly", "local", "declare", "set", "unset", "trap", "source",
        )
    }

    private data class TraceDisplayItem(
        val kind: ExecutionTimelineParser.Kind,
        val status: ExecutionTimelineParser.Status,
        val detail: String,
        val fileAction: WorkflowQuickActionParser.FileAction?,
        val isVerbose: Boolean,
        val mergedCount: Int,
    )

    private data class OutputDisplay(
        val status: ExecutionTimelineParser.Status,
        val detail: String,
    )

    private data class UserImageAttachment(
        val displayName: String,
        val originalFileName: String,
        val image: BufferedImage,
    )

    private sealed class MarkdownSegment {
        data class Markdown(val text: String) : MarkdownSegment()

        data class Code(
            val language: String,
            val code: String,
        ) : MarkdownSegment()
    }

    private enum class OutputFilterLevel {
        KEY,
        ALL,
        ;

        fun next(): OutputFilterLevel {
            return when (this) {
                KEY -> ALL
                ALL -> KEY
            }
        }
    }
}
