package dev.dotclient.android.core.diagnostics

import dev.dotclient.android.core.settings.SettingsRepository
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch

class SettingsRepositoryTest {
    private class Storage {
        var settings: String?=null
        var results: String?=null
        var failWrite=false
        fun open() = SettingsRepository({settings},{raw -> settings=raw; !failWrite},{results},{results=it})
    }
    @Test fun `legacy empty settings migrate and preferences and results survive reopening`() {
        val storage=Storage()
        val repository=storage.open()
        val defaults=repository.load()
        assertEquals(PingSettings(),defaults.ping)
        val custom=defaults.copy(dns="8.8.8.8",autoExcluded=setOf("node"),ping=defaults.ping.copy(attempts=5))
        repository.update(null,custom)
        val result=PingResult(custom.ping,35,System.currentTimeMillis(),true,null,listOf(35,35,35,35,35))
        repository.saveResults(mapOf("node" to result))
        assertEquals(custom,storage.open().load())
        assertEquals(result,storage.open().results()["node"])
    }
    @Test fun `failed commit rolls back preference memory as well as reporting failure`() {
        val storage=Storage(); val repository=storage.open()
        val before=repository.load().copy(dns="8.8.8.8")
        repository.update(null,before)
        storage.failWrite=true
        assertTrue(runCatching {repository.update(null,before.copy(showLatency=false))}.isFailure)
        assertEquals(before,repository.load())
    }
    @Test fun `parallel settings edits merge instead of losing independently edited fields`() {
        val storage=Storage(); val repository=storage.open(); val base=repository.load()
        val start=CountDownLatch(1)
        val errors=java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val workers=listOf(base.copy(dns="8.8.8.8"),base.copy(showLatency=false)).map { next ->
            Thread { try { start.await(); repository.update(base,next) } catch(e: Throwable) {errors.add(e)} }.also {it.start()}
        }
        start.countDown(); workers.forEach {it.join()}
        assertTrue(errors.toString(),errors.isEmpty())
        assertEquals("8.8.8.8",storage.open().load().dns)
        assertFalse(storage.open().load().showLatency)
    }
}
