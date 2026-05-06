package at.aau.monopoly.klagenfurt.service

import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class GameServiceTest {

    @Test
    fun `game service can be instantiated`() {
        assertNotNull(GameService())
    }
}
