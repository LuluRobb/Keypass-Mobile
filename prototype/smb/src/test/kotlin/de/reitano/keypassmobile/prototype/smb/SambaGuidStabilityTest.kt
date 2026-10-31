package de.reitano.keypassmobile.prototype.smb

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance

/**
 * Does a Samba server keep its GUID across restarts? Uses host port 1446 and its
 * own containers and volume. Skipped without Docker.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SambaGuidStabilityTest {

    private val volume = "kpm-smb-guid-state"
    private val containers = mutableListOf<SambaContainer>()

    @BeforeAll
    fun requireDocker() {
        SambaContainer.assumeDocker()
        SambaContainer.docker("volume", "rm", "-f", volume, check = false)
    }

    @AfterAll
    fun cleanUp() {
        if (!SambaContainer.dockerAvailable) return
        containers.forEach { it.remove() }
        SambaContainer.docker("volume", "rm", "-f", volume, check = false)
    }

    private fun startAndRead(name: String, hostname: String?, withVolume: Boolean): Pair<SambaContainer, UUID> {
        containers.forEach { it.remove() }
        val c = SambaContainer(name, 1446, hostname = hostname, stateVolume = if (withVolume) volume else null)
        containers += c
        c.start()
        return c to c.awaitReady().serverGuid
    }

    @Test
    fun guidAcrossRestartsAndRecreation() {
        val expected = SambaContainer.sambaGuidFor("kpm-nas")

        // 1. Fresh server with persistent state volume.
        val (a, g1) = startAndRead("kpm-smb-guid-a", "kpm-nas", withVolume = true)
        println("[guid] 1 fresh, volume, hostname kpm-nas:        $g1")
        assertEquals(expected, g1)

        // 2. Restart of the same container (like a NAS reboot).
        a.restart()
        val g2 = a.awaitReady().serverGuid
        println("[guid] 2 docker restart:                         $g2")
        assertEquals(g1, g2)

        // 3. New container, same volume, same hostname (like a NAS update).
        val (_, g3) = startAndRead("kpm-smb-guid-b", "kpm-nas", withVolume = true)
        println("[guid] 3 recreated, same volume, same hostname:   $g3")
        assertEquals(g1, g3)

        // 4. New container without any state, same hostname (like a reinstall with the same name).
        val (_, g4) = startAndRead("kpm-smb-guid-c", "kpm-nas", withVolume = false)
        println("[guid] 4 recreated, no volume, same hostname:     $g4")
        assertEquals(g1, g4)

        // 5. New container, same state volume, different hostname (Docker default: container id).
        val (d, g5) = startAndRead("kpm-smb-guid-d", null, withVolume = true)
        val hostname = d.containerHostname()
        println("[guid] 5 recreated, same volume, hostname $hostname: $g5")
        assertNotEquals(g1, g5)
        assertEquals(SambaContainer.sambaGuidFor(hostname), g5)
    }
}
