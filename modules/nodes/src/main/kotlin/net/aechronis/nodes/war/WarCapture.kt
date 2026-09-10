package net.aechronis.nodes.war

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.serdes.WarSerializer

/** Applies chunk/core captures and town defeat decisions under the occupation persistence lock. */
internal class WarCapture(
    private val state: FlagWarState,
    private val rules: WarAttackRules,
    private val occupations: WarOccupations,
    private val presentation: WarPresentation,
) {
    fun resolveTownDefeat(
        attackerTown: Town,
        defeatedTown: Town,
        mode: AttackMode,
    ): TownDefeatOutcome {
        if (mode != AttackMode.WAR) {
            Town.annex(attackerTown, defeatedTown, colonized = mode == AttackMode.COLONIZATION)
            WarSerializer.save(false)
            return TownDefeatOutcome.ANNEXED
        }

        if (!state.townsDefeatedThisWar.add(defeatedTown.uuid)) {
            return TownDefeatOutcome.ALREADY_DEFEATED_THIS_WAR
        }

        val outcome = when {
            defeatedTown.lives > 1 -> {
                Town.annex(attackerTown, defeatedTown)
                TownDefeatOutcome.LOST_LIFE
            }
            rules.canAnnexDefeatedTown(mode) -> {
                Town.annex(attackerTown, defeatedTown)
                TownDefeatOutcome.ANNEXED
            }
            else -> TownDefeatOutcome.FINAL_LIFE_PROTECTED
        }
        state.needsSave = true
        // Persist the life and per-war defeat marker together. towns.json will
        // catch up through the normal world save queue; war.json is the journal
        // used to recover either value after an abrupt stop.
        WarSerializer.save(false)
        return outcome
    }

    fun capture(attack: Attack) {
        // chunk should not be null unless territory swapped
        // out during attack and chunks were modified in new territory
        val chunk = TerritoryChunk.fromCoord(attack.coord)
        if (chunk == null || chunk.territory !== attack.targetTerritory) {
            println("finishAttack(): TerritoryChunk at ${attack.coord} is null")
            presentation.requestMinimapRefresh()
            return
        }

        if (attack.mode == AttackMode.WAR && chunk.coord == chunk.territory.core && !rules.canCaptureTerritoryCore()) {
            chunk.attacker = null
            presentation.requestMinimapRefresh()
            return
        }

        // handle occupation state of chunk
        // if chunk is core chunk of territory, attacking town occupies territory
        if (chunk.coord == chunk.territory.core) {
            presentation.deferMinimapRefresh {
                synchronized(Nodes.occupationPersistenceLock) {
                    val territory = chunk.territory
                    val territoryTown = territory.town
                    val attacker = Resident.fromUuid(attack.attacker)
                    val attackerTown = attack.town
                    val attackerNation = attackerTown.nation

                    // cleanup territory chunks
                    for (coord in territory.chunks) {
                        val territoryChunk = TerritoryChunk.fromCoord(coord)
                        if (territoryChunk != null) {
                            // cancel any concurrent attacks in this territory
                            state.chunkToAttacker.get(territoryChunk.coord)?.cancel()

                            // clear occupy/attack status from chunks
                            territoryChunk.attacker = null
                            territoryChunk.occupier = null

                            // remove from internal list of occupied chunks
                            state.occupiedChunks.remove(territoryChunk.coord)
                            state.colonizedChunks.remove(territoryChunk.coord)
                        }
                    }

                    // handle re-capturing your own territory, nation territory, or ally territory from enemy
                    if (territoryTown === attackerTown ||
                        (attackerNation !== null && attackerNation === territoryTown?.nation) ||
                        Town.areAllied(attackerTown, territoryTown)
                    ) {
                        val occupier = territory.occupier
                        Town.release(territory)
                        presentation.liberatedTerritory(attack.mode, attacker, territory, occupier)
                    }
                    // captured enemy territory
                    else {
                        Town.capture(attackerTown, territory, commitWarState = false)
                        occupations.commitTerritoryOccupation(
                            territory,
                            attackerTown,
                            colonized = attack.mode == AttackMode.COLONIZATION,
                        )
                        // Warzone scoring starts only when normal war mechanics
                        // complete a core-chunk capture of this territory.
                        if (attack.mode == AttackMode.WARZONE) {
                            Warzone.onTerritoryOccupied(territory, attackerTown)
                        }
                        presentation.capturedTerritory(attack.mode, attacker, territory, territoryTown)
                        // Warzones do not trigger town-wide occupation or life loss,
                        // including stopped warzones captured during normal war.
                        if (territoryTown != null && !Warzone.isRegistered(territory) && rules.shouldAnnexTown(territoryTown, territory)) {
                            val defeatedTownName = territoryTown.name
                            val outcome = resolveTownDefeat(attackerTown, territoryTown, attack.mode)
                            presentation.townDefeated(outcome, attackerTown, territoryTown, defeatedTownName, attacker)
                        }
                    }
                }
            }
        }
        // else, attacking normal chunk cases:
        // 1. your town, chunk captured by enemy -> liberating, remove flag
        // 2. your town (occupied) -> liberating, put flag
        // 3. territory occupied by your town, captured -> liberating, remove flag
        // 4. enemy town, empty chunk -> attacking, put flag
        else {
            val town = chunk.territory.town
            val occupier = chunk.territory.occupier
            val attacker = Resident.fromUuid(attack.attacker)

            chunk.attacker = null

            if (town === attack.town) {
                // re-capturing territory from occupier
                if (occupier !== null) {
                    chunk.occupier = town
                    state.occupiedChunks.add(chunk.coord)
                    state.colonizedChunks.remove(chunk.coord)

                    presentation.liberatedChunk(attack.mode, attacker, chunk, occupier)
                }
                // must be defending captured chunk
                else {
                    val chunkOccupier = chunk.occupier

                    chunk.occupier = null
                    state.occupiedChunks.remove(chunk.coord)
                    state.colonizedChunks.remove(chunk.coord)

                    if (chunkOccupier !== null) {
                        presentation.defendedChunk(attack.mode, attacker, chunk, chunkOccupier)
                    }
                }
            } else if (occupier === attack.town && chunk.occupier !== null) {
                val chunkOccupier = chunk.occupier
                if (attack.mode == AttackMode.COLONIZATION) {
                    // restore explicit provenance for a recaptured piece of a
                    // full colony so its control still works outside global war
                    chunk.occupier = attack.town
                    state.occupiedChunks.add(chunk.coord)
                    state.colonizedChunks.add(chunk.coord)
                } else {
                    chunk.occupier = null
                    state.occupiedChunks.remove(chunk.coord)
                    state.colonizedChunks.remove(chunk.coord)
                }

                presentation.defendedChunk(attack.mode, attacker, chunk, chunkOccupier)
            } else {
                chunk.occupier = attack.town
                state.occupiedChunks.add(chunk.coord)
                if (attack.mode == AttackMode.COLONIZATION) {
                    state.colonizedChunks.add(chunk.coord)
                } else {
                    state.colonizedChunks.remove(chunk.coord)
                }

                presentation.capturedChunk(attack.mode, attacker, chunk)
            }

            // update minimaps
            presentation.requestMinimapRefresh()
        }
    }
}
