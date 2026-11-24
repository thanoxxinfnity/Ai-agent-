package com.aura.agent

import android.net.Uri
import androidx.compose.ui.test.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.aura.agent.agent.Workspace
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FolderBrowserUiTest : UiBase() {
    private fun tap(n: SemanticsNodeInteraction) { n.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.OnClick) { it() }; rule.waitForIdle() }
    private fun waitText(t: String, ms: Long = 6000): Boolean { val end = System.currentTimeMillis() + ms; while (System.currentTimeMillis() < end) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); rule.waitForIdle(); if (rule.onAllNodesWithText(t, substring = true).fetchSemanticsNodes().isNotEmpty()) return true; Thread.sleep(60) }; return false }

    @Test fun a_connected_folder_can_be_looked_into_files_opened_edited_and_saved_back() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        val dir = File(app.cacheDir, "browse-docs").also { it.deleteRecursively(); it.mkdirs() }
        File(dir, "notes/2026").mkdirs(); File(dir, "readme.md").writeText("# Hello folder"); File(dir, "notes/2026/todo.txt").writeText("buy milk")
        vm().extras.folders.value.forEach { vm().extras.removeFolder(it.id) }; vm().extras.addFolder("Docs", Uri.fromFile(dir).toString())
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("folder-row")); rule.waitForIdle()
        assertTrue(text("tap to look inside"))
        tap(rule.onNodeWithTag("folder-row"))
        assertTrue("the folder opens", waitText("readme.md")); assertTrue(text("notes")); assertTrue("folders come first", rule.onAllNodesWithTag("browse-entry").fetchSemanticsNodes().size == 2)
        // go down two levels, open a file
        tap(rule.onAllNodesWithTag("browse-entry")[0]); assertTrue(waitText("2026")); assertTrue(text("/notes"))
        tap(rule.onAllNodesWithTag("browse-entry")[0]); assertTrue(waitText("todo.txt")); assertTrue(text("/notes/2026"))
        tap(rule.onAllNodesWithTag("browse-entry")[0])
        assertTrue("the viewer shows the text", waitText("buy milk"))
        // edit and save: the change lands in the real folder
        tap(rule.onNodeWithTag("file-edit-open"))
        val until = System.currentTimeMillis() + 6000; while (System.currentTimeMillis() < until && rule.onAllNodesWithTag("file-editor", useUnmergedTree = true).fetchSemanticsNodes().isEmpty()) { org.robolectric.shadows.ShadowLooper.idleMainLooper(); rule.waitForIdle(); Thread.sleep(60) }
        rule.onNodeWithTag("file-editor", useUnmergedTree = true).performTextClearance(); rule.onNodeWithTag("file-editor", useUnmergedTree = true).performTextInput("buy milk and bread"); rule.waitForIdle()
        tap(rule.onNodeWithTag("file-edit-save"))
        val end = System.currentTimeMillis() + 6000; while (System.currentTimeMillis() < end && File(dir, "notes/2026/todo.txt").readText() != "buy milk and bread") Thread.sleep(50)
        assertEquals("buy milk and bread", File(dir, "notes/2026/todo.txt").readText())
        tap(rule.onNodeWithTag("file-preview-close"))
        // back goes up one level at a time and then out of the browser
        rule.runOnUiThread { assertTrue(vm().browseUp()) }; assertTrue(waitText("2026")); rule.runOnUiThread { assertTrue(vm().browseUp()) }; assertTrue(waitText("readme.md"))
        rule.runOnUiThread { assertFalse("at the top the browser is left", vm().browseUp()) }
    }

    @Test fun the_built_in_workspace_and_an_empty_or_lost_folder_are_handled() {
        rule.onNodeWithText("Skip for now").performClick(); rule.waitForIdle()
        val app = rule.activity.application as App
        Workspace.dir(app).deleteRecursively(); Workspace.dir(app)
        rule.runOnUiThread { vm().go(Route.Settings) }; rule.waitForIdle()
        rule.onNodeWithTag("settings-list").performScrollToNode(hasTestTag("workspace-row")); rule.waitForIdle()
        tap(rule.onNodeWithTag("workspace-row")); assertTrue("an empty folder says so", waitText("This folder is empty."))
        File(Workspace.dir(app), "site").mkdirs(); File(Workspace.dir(app), "site/index.html").writeText("<h1>x</h1>")
        tap(rule.onNodeWithTag("browse-refresh")); assertTrue(waitText("site"))
        // a folder that no longer exists is explained
        rule.runOnUiThread { vm().openFolder(com.aura.agent.data.LocalFolder("x", "Gone", Uri.fromFile(File(app.cacheDir, "no-such-dir")).toString())) }
        assertTrue(waitText("cannot be opened any more") || waitText("This folder is empty."))
    }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AddFolderCheckTest {
    @Test fun a_picked_folder_that_cannot_be_opened_is_refused_with_advice_and_not_kept() {
        val m = Mock().start(); val app = Env.setup(m)
        app.extras.folders.value.forEach { app.extras.removeFolder(it.id) }
        val vm = androidx.lifecycle.ViewModelProvider(androidx.lifecycle.ViewModelStore(), androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))[MainVM::class.java]
        val ok = File(app.cacheDir, "addok").also { it.mkdirs() }
        assertEquals("Connected addok.", vm.addFolder(Uri.fromFile(ok))); assertEquals(1, app.extras.folders.value.size)
        val bad = vm.addFolder(Uri.parse("content://com.android.externalstorage.documents/tree/primary%3ADownload"))
        assertTrue(bad, bad.contains("does not let Aura open that folder") && bad.contains("new folder")); assertEquals("the unusable folder is not kept", 1, app.extras.folders.value.size)
    }
}
