package luna.nodes.constants

// ally/enemy
val ErrorAllyRequestEnemies = Exception("Nations are enemies")
val ErrorAllyRequestAlreadyAllies = Exception("Already allies")
val ErrorAllyRequestAlreadyCreated = Exception("Already sent an ally request")
val ErrorNotAllies = Exception("Not allies")
val ErrorWarAlly = Exception("Cannot declare war against an ally")
val ErrorAlreadyEnemies = Exception("Already enemies")
val ErrorAlreadyAllies = Exception("Already allies")
val ErrorWarSameNation = Exception("Cannot declare war on your own nation")

// towns/nations
val ErrorNationExists = Exception("Nation name already exists")
val ErrorTownHasNation = Exception("Town already has a nation")
val ErrorPlayerHasNation = Exception("Player already has a nation")
val ErrorPlayerNotInTown = Exception("Player not in this town")
val ErrorNationDoesNotHaveTown = Exception("Nation does not have town")
val ErrorTownDoesNotExist = Exception("Town does not exist")
val ErrorTownExists = Exception("Town name already exists")
val ErrorPlayerHasTown = Exception("Player already in a town")
val ErrorTerritoryOwned = Exception("Territory already has town")

// ports
val ErrorPortExists = Exception("Port already exists")
val ErrorPortInGroup = Exception("Port already in this group")

// claim/unclaim errors
val ErrorTerritoryIsTownHome = Exception("Territory is town home")
val ErrorTerritoryNotInTown = Exception("Territory does not belong to town")

// war
val ErrorNoTerritory = Exception("[War] There is no territory here")
val ErrorAlreadyUnderAttack = Exception("[War] Chunk already under attack")
val ErrorAlreadyCaptured = Exception("[War] Chunk already captured by town or allies")
val ErrorTownBlacklisted = Exception("[War] Cannot attack this town (blacklisted)")
val ErrorTownNotWhitelisted = Exception("[War] Cannot attack this town (not whitelisted)")
val ErrorNotEnemy = Exception("[War] Chunk does not belong to an enemy")
val ErrorNotBorderTerritory = Exception("[War] Can only attack border territories")
val ErrorChunkNotEdge = Exception("[War] Chunk is not at the edge")
val ErrorFlagTooHigh = Exception("[War] Flag placement too high, cannot create flag")
val ErrorSkyBlocked = Exception("[War] Flag must see the sky")
val ErrorTooManyAttacks = Exception("[War] You cannot attack any more chunks at the same time")
