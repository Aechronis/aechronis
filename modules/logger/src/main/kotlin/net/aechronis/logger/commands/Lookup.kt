package net.aechronis.logger.commands

import net.aechronis.logger.Logger
import net.aechronis.logger.objects.showFeatureLookup
import net.aechronis.logger.objects.showLookup
import net.aechronis.logger.objects.showStorageLookup
import net.aechronis.logger.params.LookupQuery
import net.aechronis.logger.params.LookupSuggestions
import net.aechronis.logger.params.ParamManager
import net.aechronis.logger.params.ParseResult
import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.entity.Player

const val LOOKUP_USAGE =
    "Usage: /logger lookup u:<user> t:<time> r:<radius> a:<action> i:<include> e:<exclude>" +
        " c:<source> o:<origin>  |  s:<feature-source> u:<user> t:<time> r:<radius> a:<action> o:<origin>"

class LoggerLookupCommand : Command("lookup", "logger.lookup", "l") {
    init {
        setDefaultExecutor { sender, _ -> sender.sendMessage(Component.text(LOOKUP_USAGE, NamedTextColor.GOLD)) }
        val params =
            ArgumentType.StringArray("params").setSuggestionCallback { sender, context, suggestion ->
                LookupSuggestions.suggest(sender, context, suggestion)
            }

        addSyntax({ sender: Player, context ->
            when (val result = ParamManager.parse(context.get(params))) {
                is ParseResult.Err -> {
                    sender.sendMessage(Component.text(result.message, NamedTextColor.RED))
                    sender.sendMessage(Component.text(LOOKUP_USAGE, NamedTextColor.GRAY))
                }

                is ParseResult.Ok -> {
                    val position = sender.position
                    when (val query = result.query) {
                        is LookupQuery.Block -> {
                            Logger.repository
                                .searchAsync(query.params, position.blockX(), position.blockY(), position.blockZ())
                                .whenComplete { entries, failure ->
                                    if (failure == null) {
                                        showLookup(sender, entries, query.params.human())
                                    } else {
                                        println("lookup failed: $failure")
                                        sender.sendMessage(Component.text("[Logger] lookup failed", NamedTextColor.RED))
                                    }
                                }
                        }

                        is LookupQuery.Feature -> {
                            Logger.featureLog
                                .searchAsync(query.params, position.blockX(), position.blockY(), position.blockZ())
                                .whenComplete { entries, failure ->
                                    if (failure == null) {
                                        showFeatureLookup(sender, entries, query.params.human())
                                    } else {
                                        println("lookup failed: $failure")
                                        sender.sendMessage(Component.text("[Logger] lookup failed", NamedTextColor.RED))
                                    }
                                }
                        }

                        is LookupQuery.Storage -> {
                            Logger.storageChange
                                .searchAsync(
                                    query.params,
                                    query.actions,
                                    position.blockX(),
                                    position.blockY(),
                                    position.blockZ(),
                                ).whenComplete { entries, failure ->
                                    if (failure == null) {
                                        showStorageLookup(sender, entries, query.human())
                                    } else {
                                        println("storage lookup failed: $failure")
                                        sender.sendMessage(Component.text("[Logger] lookup failed", NamedTextColor.RED))
                                    }
                                }
                        }
                    }
                }
            }
        }, params)
    }
}
