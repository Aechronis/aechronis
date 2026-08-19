package io.github.openminigameserver.worldedit.platform.config

import com.sk89q.worldedit.LocalConfiguration
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.session.SessionManager
import com.sk89q.worldedit.util.report.Unreported
import com.sk89q.worldedit.world.registry.LegacyMapper
import java.nio.file.Path
import java.util.Locale
import kotlin.math.max

open class WorldEditConfiguration(
    @field:Unreported
    protected val config: WorldEditConfig,
) : LocalConfiguration() {
    override fun getWorkingDirectoryPath(): Path = config.dataFolder.toPath()

    override fun load() {
        profile = config.debug
        traceUnflushedSessions = config.traceUnflushedSessions
        wandItem = config.wandItem.lowercase(Locale.ROOT)
        try {
            wandItem = LegacyMapper.getInstance().getItemFromLegacy(wandItem.toInt())!!.id()
        } catch (ignored: Throwable) {
        }
        defaultChangeLimit = max(-1, config.defaultChangeLimit)
        maxChangeLimit = max(-1, config.maxChangeLimit)
        defaultVerticalHeight = max(1, config.defaultVerticalHeight)
        defaultMaxPolygonalPoints = max(-1, config.defaultMaxPolygonalPoints)
        maxPolygonalPoints = max(-1, config.maxPolygonalPoints)
        maxRadius = max(-1, config.maxRadius)
        maxBrushRadius = config.maxBrushRadius
        maxSuperPickaxeSize = max(1, config.maxSuperPickaxeSize)
        butcherDefaultRadius = max(-1, config.butcherDefaultRadius)
        butcherMaxRadius = max(-1, config.butcherMaxRadius)
        disallowedBlocks = HashSet(config.disallowedBlocks)
        allowedDataCycleBlocks = HashSet(config.allowedDataCycleBlocks)
        registerHelp = config.registerHelp
        logCommands = config.logCommands
        logFile = config.logFile
        logFormat = config.logFormat
        superPickaxeDrop = config.superPickaxeDrop
        superPickaxeManyDrop = config.superPickaxeManyDrop
        useInventory = config.useInventory
        useInventoryOverride = config.useInventoryOverride
        useInventoryCreativeOverride = config.useInventoryCreativeOverride
        navigationWand = config.navigationWand.lowercase(Locale.ROOT)
        try {
            navigationWand = LegacyMapper.getInstance().getItemFromLegacy(navigationWand.toInt())!!.id()
        } catch (ignored: Throwable) {
        }
        navigationWandMaxDistance = config.navigationWandMaxDistance
        navigationUseGlass = config.navigationUseGlass
        scriptTimeout = config.scriptTimeout
        scriptsDir = config.scriptsDir
        saveDir = config.saveDir
        allowSymlinks = config.allowSymlinks
        LocalSession.MAX_HISTORY_SIZE = max(0, config.historySize)
        SessionManager.EXPIRATION_GRACE = config.historyExpirationMinutes * 60 * 1000
        showHelpInfo = config.showHelpOnFirstUse
        serverSideCUI = config.serverSideCUI
        initializeSnapshotConfiguration(config.snapshotsDirectory, config.snapshotsExperimental)
        shellSaveType = config.shellSaveType
        // The compact WorldEdit position encoding only supports 0..255. Minestom
        // worlds use negative build heights, so always use the extended encoding.
        extendedYLimit = true
        setDefaultLocaleName(config.defaultLocale)
    }
}
