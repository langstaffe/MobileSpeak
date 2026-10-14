package dev.mobilespeak.mobilespeak

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.os.SystemClock
import android.system.Os
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.io.File
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// Run this class in a fresh instrumentation process to exercise first initialization.
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class AndroidDnsInitializationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun a_failedInitializationDoesNotCreateCoreOrPreventRetry() {
        val create = NativeCore::class.java.getDeclaredMethod("create", Context::class.java)
        val socketsBefore = sockets()
        repeat(3) {
            val nullFailure = assertThrows(InvocationTargetException::class.java) {
                create.invoke(NativeCore, null)
            }
            assertTrue(nullFailure.cause is RuntimeException)
            assertThrows(RuntimeException::class.java) {
                NativeCore.create(object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context? = null
                })
            }
            val failure = assertThrows(IllegalStateException::class.java) {
                NativeCore.create(object : ContextWrapper(context) {
                    override fun getApplicationContext(): Context = error("test application Context failure")
                })
            }
            assertEquals("test application Context failure", failure.message)
        }
        // Bridge creation allocates wake sockets; exceptions must leave no abandoned core.
        assertTrue("Failed JNI create leaked sockets", (sockets() - socketsBefore).isEmpty())
    }

    @Test fun b_concurrentFirstCreateAndDestroyedCoresKeepDnsContextAlive() {
        assertEquals(PackageManager.PERMISSION_GRANTED, context.checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE))
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val network = checkNotNull(manager.activeNetwork) { "This DNS regression requires an active test network" }
        assertTrue(checkNotNull(manager.getLinkProperties(network)).dnsServers.isNotEmpty())
        val pool = Executors.newFixedThreadPool(4)
        val ready = CountDownLatch(4)
        val start = CountDownLatch(1)
        val handles = mutableListOf<Long>()
        try {
            val futures = List(4) {
                pool.submit<Long> {
                    ready.countDown()
                    check(start.await(5, TimeUnit.SECONDS))
                    NativeCore.create(context)
                }
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            val results = futures.map { future -> runCatching { future.get(10, TimeUnit.SECONDS).also { handles += it } } }
            results.forEach { it.getOrThrow() }
            assertEquals(4, handles.toSet().size)
            handles.forEach { handle ->
                assertTrue(handle != 0L)
                connectAndCancel(handle, "localhost")
            }
        } finally {
            start.countDown()
            pool.shutdown()
            pool.awaitTermination(15, TimeUnit.SECONDS)
            handles.forEach(NativeCore::destroy)
        }
        repeat(2) {
            val handle = NativeCore.create(context)
            try { connectAndCancel(handle, "localhost") }
            finally { NativeCore.destroy(handle) }
        }
    }

    @Test fun c_domainIpAndReconnectUseTheSameJniCore() {
        val handle = NativeCore.create(context)
        try {
            // localhost traverses both Hickory system-config reads before ordinary lookup.
            listOf("localhost", "127.0.0.1", "localhost", "[::1]").forEach { connectAndCancel(handle, it) }
        } finally { NativeCore.destroy(handle) }
        // Even after initialization, invalid input must fail before creating another core.
        a_failedInitializationDoesNotCreateCoreOrPreventRetry()
    }

    @Test fun d_activityRecreationKeepsDnsContextAlive() {
        var handle = 0L
        lateinit var oldActivity: WeakReference<MainActivity>
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    oldActivity = WeakReference(activity)
                    handle = NativeCore.create(activity)
                }
                scenario.recreate()
                scenario.onActivity { activity ->
                    assertTrue(oldActivity.get() !== activity)
                    assertTrue(oldActivity.get()?.isDestroyed != false)
                    val next = NativeCore.create(activity)
                    try { assertEquals("disconnected", poll(next).getJSONObject("snapshot").getString("status")) }
                    finally { NativeCore.destroy(next) }
                }
            }
            connectAndCancel(handle, "localhost")
        } finally { if (handle != 0L) NativeCore.destroy(handle) }
    }

    @Test fun e_dnsFailureCleansUpAndAllowsIpRetry() {
        val handle = NativeCore.create(context)
        try {
            val id = UUID.randomUUID().toString()
            val command = JSONObject().put("type", "connect").put("connectionId", id)
                .put("address", "$id.invalid:9987").put("name", "DNS failure regression")
                .put("password", "").put("identity", JSONObject.NULL)
            assertEquals(0, NativeCore.command(handle, command.toString().toByteArray(Charsets.UTF_8)))
            val deadline = SystemClock.elapsedRealtime() + 35_000
            var failed = false
            var disconnected = false
            while ((!failed || !disconnected) && SystemClock.elapsedRealtime() < deadline) {
                val envelope = poll(handle)
                val events = envelope.getJSONArray("events")
                for (i in 0 until events.length()) {
                    val event = events.getJSONObject(i)
                    if (event.optString("type") == "error") {
                        assertEquals(id, event.getString("connectionId"))
                        assertTrue("DNS failure must be an ordinary error, not a panic", !event.optString("detail").contains("panicked"))
                        failed = true
                    }
                }
                val snapshot = envelope.getJSONObject("snapshot")
                disconnected = snapshot.optString("connectionId") == id && snapshot.getString("status") == "disconnected"
                if (!failed || !disconnected) SystemClock.sleep(20)
            }
            assertTrue("An unresolvable domain must report failure and finish cleanup", failed && disconnected)
            connectAndCancel(handle, "127.0.0.1")
        } finally { NativeCore.destroy(handle) }
    }

    private fun connectAndCancel(handle: Long, host: String) {
        val loopback = if (host == "[::1]") "::1" else "127.0.0.1"
        DatagramSocket(0, InetAddress.getByName(loopback)).use { receiver ->
            receiver.soTimeout = 100
            val id = UUID.randomUUID().toString()
            val command = JSONObject().put("type", "connect").put("connectionId", id)
                .put("address", "$host:${receiver.localPort}").put("name", "DNS regression")
                .put("password", "").put("identity", JSONObject.NULL)
            assertEquals(0, NativeCore.command(handle, command.toString().toByteArray(Charsets.UTF_8)))
            try {
                val packet = DatagramPacket(ByteArray(2048), 2048)
                val deadline = SystemClock.elapsedRealtime() + 35_000
                var received = false
                while (!received && SystemClock.elapsedRealtime() < deadline) {
                    assertNoErrors(poll(handle))
                    try {
                        receiver.receive(packet)
                        received = packet.length > 0
                    } catch (_: SocketTimeoutException) { }
                }
                assertTrue("$host must resolve and send a TeamSpeak handshake to the isolated UDP receiver", received)
                assertNoErrors(poll(handle))
            } finally {
                assertEquals(0, NativeCore.command(handle, """{"type":"disconnect"}""".toByteArray()))
                val deadline = SystemClock.elapsedRealtime() + 5_000
                var disconnected = false
                while (!disconnected && SystemClock.elapsedRealtime() < deadline) {
                    val envelope = poll(handle)
                    assertNoErrors(envelope)
                    val snapshot = envelope.getJSONObject("snapshot")
                    disconnected = snapshot.optString("connectionId") == id && snapshot.getString("status") == "disconnected"
                    if (!disconnected) SystemClock.sleep(20)
                }
                assertTrue("Cancellation must complete before reconnecting", disconnected)
            }
        }
    }

    private fun poll(handle: Long) = JSONObject(String(NativeCore.poll(handle), Charsets.UTF_8))

    private fun assertNoErrors(envelope: JSONObject) {
        val events = envelope.getJSONArray("events")
        for (i in 0 until events.length()) {
            val event = events.getJSONObject(i)
            if (event.optString("type") == "error") {
                throw AssertionError("Unexpected core error: ${event.optString("detail", event.optString("message"))}")
            }
        }
    }

    private fun sockets(): Set<String> = File("/proc/self/fd").listFiles().orEmpty().mapNotNull { file ->
        runCatching { Os.readlink(file.path) }.getOrNull()?.takeIf { it.startsWith("socket:[") }
    }.toSet()
}
