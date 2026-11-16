package com.v2ray.ang.ui.main

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MainImportMenuTest {

    /** 验证一键清理紧跟组合测速，避免入口被放到其他菜单。 */
    @Test
    fun shouldDisplayInvalidNodeCleanupImmediatelyAfterCombinedSpeedtest() {
        val actions = MainMoreMenuAction.entries
        val speedtestPosition = actions.indexOf(MainMoreMenuAction.TestAllSpeed)

        assertEquals(MainMoreMenuAction.ClearInvalid, actions[speedtestPosition + 1])
    }

    @Test
    fun regularShareMenuContainsOnlyShareActions() {
        val expected = listOf(
            ServerMenuAction.ShareQRCode,
            ServerMenuAction.ShareClipboard,
            ServerMenuAction.ShareFullContent,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = false, includeManagementActions = false))
    }

    @Test
    fun regularMoreMenuContainsEveryActionInDisplayOrder() {
        assertEquals(
            ServerMenuAction.entries,
            serverMenuActions(isComplexProfile = false, includeManagementActions = true),
        )
    }

    @Test
    fun complexShareMenuContainsOnlyFullContent() {
        assertEquals(
            listOf(ServerMenuAction.ShareFullContent),
            serverMenuActions(isComplexProfile = true, includeManagementActions = false),
        )
    }

    @Test
    fun complexMoreMenuRetainsManagementActions() {
        val expected = listOf(
            ServerMenuAction.ShareFullContent,
            ServerMenuAction.Edit,
            ServerMenuAction.Delete,
        )
        assertEquals(expected, serverMenuActions(isComplexProfile = true, includeManagementActions = true))
    }
}
