/**
 * Enum statuses for result of claim/unclaim territory
 * for a town.
 *
 * Allows printing/handling different error results of
 * claiming territory
 */

package luna.nodes.constants

// claim errors
val ErrorTerritoryNotConnected = Exception("Territory not connected to town's claims")
val ErrorTerritoryHasClaim = Exception("Territory already has town")

// unclaim errors
val ErrorTerritoryIsTownHome = Exception("Territory is town home")
val ErrorTerritoryNotInTown = Exception("Territory does not belong to town")
