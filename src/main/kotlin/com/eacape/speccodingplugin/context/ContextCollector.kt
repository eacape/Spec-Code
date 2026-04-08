package com.eacape.speccodingplugin.context

import com.eacape.speccodingplugin.telemetry.SlowPathBaselineSample
import com.eacape.speccodingplugin.telemetry.emitSlowPathBaseline
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
class ContextCollector(private val project: Project) {
    private val logger = thisLogger()

    private var codeGraphSnapshotProvider: () -> Result<CodeGraphSnapshot> = defaultCodeGraphSnapshotProvider(project)
    private var codeGraphCacheStatsProvider: () -> CodeGraphCacheStats? = defaultCodeGraphCacheStatsProvider(project)
    private var relatedFilesProvider: () -> Result<List<ContextItem>> = defaultRelatedFilesProvider(project)
    private var relatedFileCacheStatsProvider: () -> RelatedFileCacheStats? = defaultRelatedFileCacheStatsProvider(project)
    private var projectStructureProvider: () -> Result<ContextItem?> = defaultProjectStructureProvider(project)
    private var projectStructureCacheStatsProvider: () -> ProjectStructureCacheStats? =
        defaultProjectStructureCacheStatsProvider(project)
    private var nanoTimeProvider: () -> Long = System::nanoTime
    private var baselineTracker: ContextCollectionBaselineTracker = ContextCollectionBaselineTracker()
    private var telemetryConsumer: (ContextCollectionTelemetry) -> Unit = { telemetry ->
        logCollectionTelemetry(telemetry)
    }

    internal constructor(
        project: Project,
        codeGraphSnapshotProvider: () -> Result<CodeGraphSnapshot> = defaultCodeGraphSnapshotProvider(project),
        codeGraphCacheStatsProvider: () -> CodeGraphCacheStats? = defaultCodeGraphCacheStatsProvider(project),
        relatedFilesProvider: () -> Result<List<ContextItem>> = defaultRelatedFilesProvider(project),
        relatedFileCacheStatsProvider: () -> RelatedFileCacheStats? = defaultRelatedFileCacheStatsProvider(project),
        projectStructureProvider: () -> Result<ContextItem?> = defaultProjectStructureProvider(project),
        projectStructureCacheStatsProvider: () -> ProjectStructureCacheStats? =
            defaultProjectStructureCacheStatsProvider(project),
        nanoTimeProvider: () -> Long = System::nanoTime,
        baselineTracker: ContextCollectionBaselineTracker = ContextCollectionBaselineTracker(),
        telemetryConsumer: ((ContextCollectionTelemetry) -> Unit)? = null,
    ) : this(project) {
        this.codeGraphSnapshotProvider = codeGraphSnapshotProvider
        this.codeGraphCacheStatsProvider = codeGraphCacheStatsProvider
        this.relatedFilesProvider = relatedFilesProvider
        this.relatedFileCacheStatsProvider = relatedFileCacheStatsProvider
        this.projectStructureProvider = projectStructureProvider
        this.projectStructureCacheStatsProvider = projectStructureCacheStatsProvider
        this.nanoTimeProvider = nanoTimeProvider
        this.baselineTracker = baselineTracker
        telemetryConsumer?.let {
            this.telemetryConsumer = it
        }
    }

    fun collectContext(config: ContextConfig = ContextConfig()): ContextSnapshot {
        val startedAtNanos = nanoTimeProvider()
        val cacheTelemetryBefore = captureCacheTelemetry(config)
        val skippedStages = mutableListOf<String>()
        val autoItems = collectAutomaticItems(
            config = config,
            seedStats = ContextCollectionBudgetStats.EMPTY,
            startedAtNanos = startedAtNanos,
            skippedStages = skippedStages,
        )
        return finalizeSnapshot(
            operationKey = "collectContext",
            items = autoItems,
            config = config,
            startedAtNanos = startedAtNanos,
            cacheTelemetryBefore = cacheTelemetryBefore,
            skippedStages = skippedStages,
        )
    }

    fun collectForItems(
        explicitItems: List<ContextItem>,
        config: ContextConfig = ContextConfig(),
    ): ContextSnapshot {
        val startedAtNanos = nanoTimeProvider()
        val cacheTelemetryBefore = captureCacheTelemetry(config)
        val skippedStages = mutableListOf<String>()
        val autoItems = collectAutomaticItems(
            config = config,
            seedStats = ContextCollectionBudgetCoordinator.statsFor(explicitItems),
            startedAtNanos = startedAtNanos,
            skippedStages = skippedStages,
        )
        val allItems = deduplicateItems(explicitItems + autoItems)
        return finalizeSnapshot(
            operationKey = "collectForItems",
            items = allItems,
            config = config,
            startedAtNanos = startedAtNanos,
            cacheTelemetryBefore = cacheTelemetryBefore,
            skippedStages = skippedStages,
        )
    }

    private fun collectAutomaticItems(
        config: ContextConfig,
        seedStats: ContextCollectionBudgetStats,
        startedAtNanos: Long,
        skippedStages: MutableList<String>,
    ): List<ContextItem> {
        val items = mutableListOf<ContextItem>()

        if (config.includeSelectedCode) {
            EditorContextProvider.getSelectedCodeContext(project)?.let(items::add)
        }

        if (config.includeContainingScope) {
            EditorContextProvider.getContainingScopeContext(project)?.let(items::add)
        }

        if (config.includeCurrentFile) {
            EditorContextProvider.getCurrentFileContext(project)?.let(items::add)
        }

        if (config.includeImportDependencies) {
            maybeCollectOptionalStage(
                stage = ContextCollectionStage.IMPORT_DEPENDENCIES,
                config = config,
                seedStats = seedStats,
                collectedItems = items,
                startedAtNanos = startedAtNanos,
                skippedStages = skippedStages,
            ) {
                relatedFilesProvider()
                    .onFailure {
                        skippedStages += "${ContextCollectionStage.IMPORT_DEPENDENCIES.wireName}:error"
                    }
                    .getOrDefault(emptyList())
            }
        }

        if (config.includeProjectStructure) {
            maybeCollectOptionalStage(
                stage = ContextCollectionStage.PROJECT_STRUCTURE,
                config = config,
                seedStats = seedStats,
                collectedItems = items,
                startedAtNanos = startedAtNanos,
                skippedStages = skippedStages,
            ) {
                listOfNotNull(
                    projectStructureProvider()
                        .onFailure {
                            skippedStages += "${ContextCollectionStage.PROJECT_STRUCTURE.wireName}:error"
                        }
                        .getOrNull()
                        ?.takeIf { it.content.isNotBlank() },
                )
            }
        }

        return items
    }

    private fun maybeCollectOptionalStage(
        stage: ContextCollectionStage,
        config: ContextConfig,
        seedStats: ContextCollectionBudgetStats,
        collectedItems: MutableList<ContextItem>,
        startedAtNanos: Long,
        skippedStages: MutableList<String>,
        provider: () -> List<ContextItem>,
    ) {
        val currentStats = ContextCollectionBudgetCoordinator.merge(
            seedStats,
            ContextCollectionBudgetCoordinator.statsFor(collectedItems),
        )
        val skipReason = ContextCollectionBudgetCoordinator.optionalStageSkipReason(
            stage = stage,
            stats = currentStats,
            config = config,
            elapsedMs = elapsedMillisSince(startedAtNanos),
        )
        if (skipReason != null) {
            skippedStages += "${stage.wireName}:$skipReason"
            return
        }
        collectedItems += provider()
    }

    private fun finalizeSnapshot(
        operationKey: String,
        items: List<ContextItem>,
        config: ContextConfig,
        startedAtNanos: Long,
        cacheTelemetryBefore: ContextCollectionCacheTelemetry?,
        skippedStages: MutableList<String>,
    ): ContextSnapshot {
        val prioritizedItems = applyGraphAwarePrioritization(
            items = items,
            config = config,
            startedAtNanos = startedAtNanos,
            skippedStages = skippedStages,
        )
        val budgetResult = ContextCollectionBudgetCoordinator.enforce(prioritizedItems, config)
        val snapshot = ContextTrimmer.trim(
            budgetResult.items,
            config.tokenBudget,
        )
        val elapsedMs = elapsedMillisSince(startedAtNanos)
        val cacheTelemetry = ContextCollectionBaselineCoordinator.attachRunOutcomes(
            before = cacheTelemetryBefore,
            after = captureCacheTelemetry(config),
        )
        val baselineComparison = baselineTracker.record(
            scenarioKey = ContextCollectionBaselineCoordinator.scenarioKey(operationKey, config),
            elapsedMs = elapsedMs,
            phase = ContextCollectionBaselineCoordinator.determinePhase(cacheTelemetry),
        )

        telemetryConsumer(
            ContextCollectionTelemetry(
                operationKey = operationKey,
                elapsedMs = elapsedMs,
                candidateItemCount = prioritizedItems.size,
                budgetAcceptedItemCount = budgetResult.items.size,
                finalItemCount = snapshot.items.size,
                budgetStats = budgetResult.stats,
                tokenEstimate = snapshot.totalTokenEstimate,
                tokenBudget = config.tokenBudget,
                maxFileItems = config.maxFileItems,
                maxSymbolItems = config.maxSymbolItems,
                maxContentBytes = config.maxContentBytes,
                maxCollectionTimeMs = config.maxCollectionTimeMs,
                wasTokenTrimmed = snapshot.wasTrimmed,
                budgetDropSummary = budgetResult.dropSummary(),
                skippedStages = skippedStages.toList(),
                cacheView = cacheTelemetry,
                baselineComparison = baselineComparison,
            ),
        )

        return snapshot
    }

    private fun deduplicateItems(items: List<ContextItem>): List<ContextItem> {
        val seen = mutableSetOf<String>()
        return items.filter { item ->
            val key = "${item.type}:${item.filePath ?: item.label}"
            seen.add(key)
        }
    }

    private fun applyGraphAwarePrioritization(
        items: List<ContextItem>,
        config: ContextConfig,
        startedAtNanos: Long,
        skippedStages: MutableList<String>,
    ): List<ContextItem> {
        if (
            !ContextCollectionBudgetCoordinator.shouldRunGraphPrioritization(
                items = items,
                config = config,
                elapsedMs = elapsedMillisSince(startedAtNanos),
            )
        ) {
            if (config.preferGraphRelatedContext && elapsedMillisSince(startedAtNanos) >= config.maxCollectionTimeMs) {
                skippedStages += "graph-prioritization:time-budget"
            }
            return items
        }
        val graph = codeGraphSnapshotProvider()
            .onFailure {
                skippedStages += "graph-prioritization:error"
            }
            .getOrNull() ?: return items
        return ContextGraphPrioritizer.prioritize(items, graph)
    }

    private fun logCollectionTelemetry(telemetry: ContextCollectionTelemetry) {
        emitSlowPathBaseline(
            logger = logger,
            sample = SlowPathBaselineSample(
                operationKey = "ContextCollector.${telemetry.operationKey}",
                elapsedMs = telemetry.elapsedMs,
            ),
        )
        val severity = determineContextTelemetrySeverity(telemetry.elapsedMs)
        val shouldLogInfo =
            severity == ContextTelemetrySeverity.INFO ||
                shouldEmitContextTelemetryInfo(telemetry)
        val message = "ContextCollector: ${telemetry.summary()}"
        when {
            severity == ContextTelemetrySeverity.WARN -> logger.warn(message)
            shouldLogInfo -> logger.info(message)
        }
    }

    private fun elapsedMillisSince(startedAtNanos: Long): Long {
        return (nanoTimeProvider() - startedAtNanos) / 1_000_000
    }

    private fun captureCacheTelemetry(config: ContextConfig): ContextCollectionCacheTelemetry? {
        val cacheTelemetry = ContextCollectionCacheTelemetry(
            codeGraph = if (config.preferGraphRelatedContext) {
                codeGraphCacheStatsProvider()
            } else {
                null
            },
            relatedFiles = if (config.includeImportDependencies) {
                relatedFileCacheStatsProvider()
            } else {
                null
            },
            projectStructure = if (config.includeProjectStructure) {
                projectStructureCacheStatsProvider()
            } else {
                null
            },
        )
        return cacheTelemetry.takeIf {
            it.codeGraph != null || it.relatedFiles != null || it.projectStructure != null
        }
    }

    companion object {
        private fun defaultCodeGraphSnapshotProvider(project: Project): () -> Result<CodeGraphSnapshot> = {
            runCatching {
                CodeGraphService.getInstance(project).buildFromActiveEditor().getOrThrow()
            }
        }

        private fun defaultCodeGraphCacheStatsProvider(project: Project): () -> CodeGraphCacheStats? = {
            runCatching {
                CodeGraphService.getInstance(project).cacheStatsSnapshot()
            }.getOrNull()
        }

        private fun defaultRelatedFilesProvider(project: Project): () -> Result<List<ContextItem>> = {
            runCatching {
                RelatedFileDiscovery.getInstance(project).discoverRelatedFiles()
            }
        }

        private fun defaultRelatedFileCacheStatsProvider(project: Project): () -> RelatedFileCacheStats? = {
            runCatching {
                RelatedFileDiscovery.getInstance(project).cacheStatsSnapshot()
            }.getOrNull()
        }

        private fun defaultProjectStructureProvider(project: Project): () -> Result<ContextItem?> = {
            runCatching {
                ProjectStructureScanner.getInstance(project).getProjectStructureContext()
            }
        }

        private fun defaultProjectStructureCacheStatsProvider(project: Project): () -> ProjectStructureCacheStats? = {
            runCatching {
                ProjectStructureScanner.getInstance(project).cacheStatsSnapshot()
            }.getOrNull()
        }

        fun getInstance(project: Project): ContextCollector {
            return project.getService(ContextCollector::class.java)
        }
    }
}
