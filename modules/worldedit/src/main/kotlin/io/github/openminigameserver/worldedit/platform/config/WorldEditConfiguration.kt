package io.github.openminigameserver.worldedit.platform.config

import com.sk89q.worldedit.LocalConfiguration
import com.sk89q.worldedit.LocalSession
import com.sk89q.worldedit.session.SessionManager
import com.sk89q.worldedit.util.report.Unreported
import com.sk89q.worldedit.world.registry.LegacyMapper
import io.github.openminigameserver.worldedit.MinestomWorldEdit
import org.slf4j.Logger
import org.spongepowered.configurate.CommentedConfigurationNode
import org.spongepowered.configurate.ConfigurationOptions
import org.spongepowered.configurate.loader.ConfigurationLoader
import org.spongepowered.configurate.serialize.SerializationException
import java.io.IOException
import java.nio.file.Path
import java.util.*
import kotlin.math.max

open class WorldEditConfiguration(
    @field:Unreported
    protected val config: ConfigurationLoader<CommentedConfigurationNode>,
    @field:Unreported protected val logger: Logger
) : LocalConfiguration() {
    @Unreported
    protected var node: CommentedConfigurationNode? = null
    override fun getWorkingDirectoryPath(): Path {
        return MinestomWorldEdit.dataFolder.toPath()
    }

    override fun load() {
        try {
            var options = ConfigurationOptions.defaults()
            options = options.shouldCopyDefaults(true)
            node = config.load(options)
        } catch (e: IOException) {
            logger.warn("Error loading WorldEdit configuration", e)
        }
        profile = node!!.node("debug").getBoolean(profile)
        traceUnflushedSessions = node!!.node("debugging", "trace-unflushed-sessions").getBoolean(traceUnflushedSessions)
        wandItem = node!!.node("wand-item").getString(wandItem).toLowerCase(Locale.ROOT)
        try {
            wandItem = LegacyMapper.getInstance().getItemFromLegacy(wandItem.toInt())!!.id
        } catch (ignored: Throwable) {
        }
        defaultChangeLimit = max(
            -1,
            node!!.node("limits", "max-blocks-changed", "default").getInt(defaultChangeLimit)
        )
        maxChangeLimit = max(-1, node!!.node("limits", "max-blocks-changed", "maximum").getInt(maxChangeLimit))
        defaultVerticalHeight = max(
            1,
            node!!.node("limits", "vertical-height", "default").getInt(defaultVerticalHeight)
        )
        defaultMaxPolygonalPoints = max(
            -1,
            node!!.node("limits", "max-polygonal-points", "default").getInt(defaultMaxPolygonalPoints)
        )
        maxPolygonalPoints = max(
            -1,
            node!!.node("limits", "max-polygonal-points", "maximum").getInt(maxPolygonalPoints)
        )
        maxRadius = max(-1, node!!.node("limits", "max-radius").getInt(maxRadius))
        maxBrushRadius = node!!.node("limits", "max-brush-radius").getInt(maxBrushRadius)
        maxSuperPickaxeSize = max(1, node!!.node("limits", "max-super-pickaxe-size").getInt(maxSuperPickaxeSize))
        butcherDefaultRadius = max(
            -1,
            node!!.node("limits", "butcher-radius", "default").getInt(butcherDefaultRadius)
        )
        butcherMaxRadius = max(-1, node!!.node("limits", "butcher-radius", "maximum").getInt(butcherMaxRadius))
        try {
            disallowedBlocks = HashSet(
                node!!.node("limits", "disallowed-blocks").getList(
                    String::class.java
                )!!
            )
        } catch (e: SerializationException) {
            logger.warn("Error loading WorldEdit configuration", e)
        }
        try {
            allowedDataCycleBlocks = HashSet(
                node!!.node("limits", "allowed-data-cycle-blocks").getList(
                    String::class.java
                )!!
            )
        } catch (e: SerializationException) {
            logger.warn("Error loading WorldEdit configuration", e)
        }
        registerHelp = node!!.node("register-help").getBoolean(true)
        logCommands = node!!.node("logging", "log-commands").getBoolean(logCommands)
        logFile = node!!.node("logging", "file").getString(logFile)
        logFormat = node!!.node("logging", "format").getString(logFormat)
        superPickaxeDrop = node!!.node("super-pickaxe", "drop-items").getBoolean(superPickaxeDrop)
        superPickaxeManyDrop = node!!.node("super-pickaxe", "many-drop-items").getBoolean(superPickaxeManyDrop)
        useInventory = node!!.node("use-inventory", "enable").getBoolean(useInventory)
        useInventoryOverride = node!!.node("use-inventory", "allow-override").getBoolean(useInventoryOverride)
        useInventoryCreativeOverride =
            node!!.node("use-inventory", "creative-mode-overrides").getBoolean(useInventoryCreativeOverride)
        navigationWand = node!!.node("navigation-wand", "item").getString(navigationWand).toLowerCase(Locale.ROOT)
        try {
            navigationWand = LegacyMapper.getInstance().getItemFromLegacy(navigationWand.toInt())!!.id
        } catch (ignored: Throwable) {
        }
        navigationWandMaxDistance = node!!.node("navigation-wand", "max-distance").getInt(navigationWandMaxDistance)
        navigationUseGlass = node!!.node("navigation", "use-glass").getBoolean(navigationUseGlass)
        scriptTimeout = node!!.node("scripting", "timeout").getInt(scriptTimeout)
        scriptsDir = node!!.node("scripting", "dir").getString(scriptsDir)
        saveDir = node!!.node("saving", "dir").getString(saveDir)
        allowSymlinks = node!!.node("files", "allow-symbolic-links").getBoolean(false)
        LocalSession.MAX_HISTORY_SIZE = max(0, node!!.node("history", "size").getInt(15))
        SessionManager.EXPIRATION_GRACE = node!!.node("history", "expiration").getInt(10) * 60 * 1000
        showHelpInfo = node!!.node("show-help-on-first-use").getBoolean(true)
        serverSideCUI = node!!.node("server-side-cui").getBoolean(true)
        val snapshotsDir = node!!.node("snapshots", "directory").getString("")
        val experimentalSnapshots = node!!.node("snapshots", "experimental").getBoolean(false)
        initializeSnapshotConfiguration(snapshotsDir, experimentalSnapshots)
        val type = node!!.node("shell-save-type").getString("").trim { it <= ' ' }
        shellSaveType = if (type == "") null else type
        extendedYLimit = node!!.node("compat", "extended-y-limit").getBoolean(false)
        setDefaultLocaleName(node!!.node("default-locale").getString(defaultLocaleName))
        try {
            config.save(node)
        } catch (e: IOException) {
            logger.warn("Error loading WorldEdit configuration", e)
        }
    }
}