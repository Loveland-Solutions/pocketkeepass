package mn.lls.pocketkeepass.edit

import mn.lls.pocketkeepass.core.GroupChoice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MoveTargetsTest {
    // Root
    //   Work
    //     Servers
    //       Old
    //   Home
    //   Recycle Bin
    //     Deleted
    private val groups = listOf(
        group("root", 0u),
        group("work", 1u),
        group("servers", 2u),
        group("old", 3u),
        group("home", 1u),
        group("bin", 1u, isBin = true),
        group("deleted", 2u, inBin = true),
    )

    private fun group(id: String, depth: UInt, isBin: Boolean = false, inBin: Boolean = false) =
        GroupChoice(id, id, depth, isRecycleBin = isBin, isInRecycleBin = inBin)

    private fun targets(item: Item) = moveTargets(item, groups).map { it.id }

    @Test
    fun `an entry can go anywhere but the recycle bin`() {
        assertEquals(listOf("root", "work", "servers", "old", "home"), targets(Item.Entry("e", "E", inRecycleBin = false)))
    }

    @Test
    fun `a group can't go into itself or its own subgroups`() {
        val work = Item.Group("work", "Work", inRecycleBin = false, isRecycleBin = false)
        assertEquals(listOf("root", "home"), targets(work))
        val servers = Item.Group("servers", "Servers", inRecycleBin = false, isRecycleBin = false)
        assertEquals(listOf("root", "work", "home"), targets(servers))
    }

    @Test
    fun `something in the recycle bin can be moved back out`() {
        val deleted = Item.Group("deleted", "Deleted", inRecycleBin = true, isRecycleBin = false)
        assertEquals(listOf("root", "work", "servers", "old", "home"), targets(deleted))
    }
}
