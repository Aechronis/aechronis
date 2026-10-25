package net.aechronis.logger.objects

internal fun StorageChangeAction.inverse(): StorageChangeAction =
    when (this) {
        StorageChangeAction.DEPOSIT -> StorageChangeAction.WITHDRAW
        StorageChangeAction.WITHDRAW -> StorageChangeAction.DEPOSIT
    }
