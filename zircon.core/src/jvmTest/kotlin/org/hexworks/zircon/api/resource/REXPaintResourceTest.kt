package org.hexworks.zircon.api.resource

import org.junit.Test

class REXPaintResourceTest {

    @Test
    fun test() {
        REXPaintResources.loadREXFile(this.javaClass.getResourceAsStream("/rex_files/cp437_table.xp"))
    }
}
