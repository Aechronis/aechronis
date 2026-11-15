package net.aechronis.printer

import net.aechronis.utils.Command

class PrinterCommand : Command("printer", "printer.use") {
    init {
        setDefaultExecutor { player, _ -> Printer.toggle(player) }
    }
}
