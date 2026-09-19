package com.splitfree.data.settings

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class DisplayNamePreferencesTest {
    private val app = RuntimeEnvironment.getApplication()
    private lateinit var settings: UserPreferences

    @Before fun setup() {
        app.getSharedPreferences("splitfree_settings", 0).edit().clear().commit()
        settings = UserPreferences(app)
    }

    @Test fun `intent survives preferences reconstruction before any publisher exists`() {
        val saved = settings.saveDisplayNameIntent("alice", "  Alice  ")
        val reopened = UserPreferences(app)
        assertEquals(saved, reopened.getDisplayNameIntent("alice"))
        assertEquals("Alice", reopened.displayNameFor("alice"))
    }

    @Test fun `retyping the same name reuses durable revision and changed names allocate new revisions`() {
        val first = settings.saveDisplayNameIntent("alice", "Alice")
        assertEquals(first, settings.saveDisplayNameIntent("alice", "Alice"))
        val second = settings.saveDisplayNameIntent("alice", "Bob")
        assertNotEquals(first.revision, second.revision)
        assertNotEquals(second.revision, settings.saveDisplayNameIntent("alice", "Alice").revision)
    }

    @Test fun `identity switch never inherits another identity desired name or alters it during initialization`() {
        settings.saveDisplayNameIntent("alice", "Alice")
        assertEquals("", settings.displayNameFor("bob"))
        assertEquals("", settings.initializeDisplayNameIntent("bob").name)
        assertEquals("Alice", settings.displayName)
        settings.saveDisplayNameIntent("bob", "Bob")
        assertEquals("Alice", settings.initializeDisplayNameIntent("alice").name)
        assertEquals("Bob", settings.displayName)
        assertEquals("Alice", settings.displayNameFor("alice"))
        assertEquals("Bob", settings.displayNameFor("bob"))
    }

    @Test fun `legacy desired name migrates once and later identities start without a name`() {
        settings.displayName = "Legacy"
        val migrated = settings.initializeDisplayNameIntent("alice")
        assertEquals("Legacy", migrated.name)
        assertEquals(migrated, UserPreferences(app).initializeDisplayNameIntent("alice"))
        assertEquals("", settings.initializeDisplayNameIntent("bob").name)
    }

    @Test fun `revocation transfers the desired name durably without mutating the retired identity`() {
        val old = settings.saveDisplayNameIntent("alice", "Alice")
        val successor = settings.transferDisplayNameIntent("alice", "successor")
        assertEquals("Alice", successor.name)
        assertEquals("successor", successor.identityPubkey)
        assertNotEquals(old.revision, successor.revision)
        assertEquals(old, settings.getDisplayNameIntent("alice"))
        assertEquals(successor, UserPreferences(app).transferDisplayNameIntent("alice", "successor"))
        assertEquals("", settings.initializeDisplayNameIntent("unrelated").name)
    }

    @Test fun `transfer refreshes pending successor from still active retiring identity including explicit blank`() {
        settings.saveDisplayNameIntent("alice", "Alice")
        val first = settings.transferDisplayNameIntent("alice", "successor")
        settings.saveDisplayNameIntent("alice", "Bob")
        val newer = settings.transferDisplayNameIntent("alice", "successor")
        assertEquals("Bob", newer.name)
        assertNotEquals(first.revision, newer.revision)
        assertEquals(newer, UserPreferences(app).transferDisplayNameIntent("alice", "successor"))
        settings.saveDisplayNameIntent("alice", "")
        val cleared = settings.transferDisplayNameIntent("alice", "successor")
        assertEquals("", cleared.name)
        assertEquals(cleared, UserPreferences(app).transferDisplayNameIntent("alice", "successor"))
    }

    @Test fun `legacy name belongs to retiring identity and its successor but no unrelated identity`() {
        settings.displayName = "Legacy Alice"
        assertEquals("Legacy Alice", settings.transferDisplayNameIntent("alice", "successor").name)
        assertEquals("Legacy Alice", UserPreferences(app).displayNameFor("alice"))
        assertEquals("", settings.initializeDisplayNameIntent("unrelated").name)
    }

    @Test fun `transfer rejects an absent or identical identity`() {
        assertThrows(IllegalArgumentException::class.java) { settings.transferDisplayNameIntent("", "next") }
        assertThrows(IllegalArgumentException::class.java) { settings.transferDisplayNameIntent("old", "") }
        assertThrows(IllegalArgumentException::class.java) { settings.transferDisplayNameIntent("old", "old") }
    }

    @Test fun `failed synchronous commit cannot expose or later flush an unpublished name`() {
        val memory = mutableMapOf<String, Any?>()
        var fail = true
        val prefs = mockk<SharedPreferences>()
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.getString(any(), any()) } answers { memory[firstArg()] as String? ?: secondArg() }
        every { prefs.getBoolean(any(), any()) } answers { memory[firstArg()] as Boolean? ?: secondArg() }
        every { prefs.edit() } answers {
            val changes = mutableMapOf<String, Any?>()
            val editor = mockk<SharedPreferences.Editor>()
            every { editor.putString(any(), any()) } answers {
                changes[firstArg()] = secondArg<String?>()
                editor
            }
            every { editor.putBoolean(any(), any()) } answers {
                changes[firstArg()] = secondArg<Boolean>()
                editor
            }
            every { editor.commit() } answers {
                memory.putAll(changes)
                !fail
            }
            editor
        }
        val failingSettings = UserPreferences(context)
        assertThrows(IllegalStateException::class.java) { failingSettings.saveDisplayNameIntent("alice", "Unsaved") }
        assertThrows(IllegalStateException::class.java) { failingSettings.getDisplayNameIntent("alice") }
        assertNull(memory["display_name_intent:alice"])
        assertNull(memory["display_name"])
        fail = false
        val saved = failingSettings.saveDisplayNameIntent("alice", "Saved")
        assertEquals(saved, failingSettings.getDisplayNameIntent("alice"))
    }
}
