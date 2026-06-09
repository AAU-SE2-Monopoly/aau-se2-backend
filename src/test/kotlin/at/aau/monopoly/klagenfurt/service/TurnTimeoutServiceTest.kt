package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.controller.GameController
import at.aau.monopoly.klagenfurt.model.Player
import at.aau.monopoly.klagenfurt.model.TradeOffer
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.websocket.broker.WebSocketBrokerController
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class TurnTimeoutServiceTest {

    private fun setup(): Triple<TurnTimeoutService, GameController, WebSocketBrokerController> {
        val gameController = GameController()
        val broker = Mockito.mock(WebSocketBrokerController::class.java)
        val service = TurnTimeoutService(gameController, broker, turnTimeoutSeconds = 60)
        return Triple(service, gameController, broker)
    }

    @Test
    fun `sweep does not force turn when within timeout`() {
        val (service, gameController, broker) = setup()
        val game = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(game.gameId, Player(id = "host-1", name = "Alice"))
        game.advanceTurn()
        game.turnTimerStartedAtMillis = System.currentTimeMillis() // fresh

        service.sweepExpiredTurns()

        Mockito.verify(broker, Mockito.never()).forceEndTurn(Mockito.anyString())
    }

    @Test
    fun `sweep forces turn when timeout exceeded`() {
        val (service, gameController, broker) = setup()
        val game = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(game.gameId, Player(id = "host-1", name = "Alice"))
        game.advanceTurn()
        // 61 seconds ago — beyond the 60s timeout.
        game.turnTimerStartedAtMillis = System.currentTimeMillis() - 61_000L

        service.sweepExpiredTurns()

        Mockito.verify(broker).forceEndTurn(game.gameId)
    }

    @Test
    fun `sweep ignores WAITING games`() {
        val (service, gameController, broker) = setup()
        val game = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(game.gameId, Player(id = "host-1", name = "Alice"))
        // Still WAITING; ancient timer should be ignored.
        game.turnTimerStartedAtMillis = 1L

        service.sweepExpiredTurns()

        Mockito.verify(broker, Mockito.never()).forceEndTurn(Mockito.anyString())
    }

    @Test
    fun `sweep ignores games with no active timer`() {
        val (service, gameController, broker) = setup()
        val game = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(game.gameId, Player(id = "host-1", name = "Alice"))
        game.phase = GamePhase.ROLLING
        game.turnTimerStartedAtMillis = 0L

        service.sweepExpiredTurns()

        Mockito.verify(broker, Mockito.never()).forceEndTurn(Mockito.anyString())
    }

    @Test
    fun `sweep swallows exceptions from forceEndTurn`() {
        val (service, gameController, broker) = setup()
        val game = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(game.gameId, Player(id = "host-1", name = "Alice"))
        game.advanceTurn()
        game.turnTimerStartedAtMillis = System.currentTimeMillis() - 61_000L
        Mockito.`when`(broker.forceEndTurn(game.gameId)).thenThrow(RuntimeException("boom"))

        // Should not propagate.
        service.sweepExpiredTurns()

        Mockito.verify(broker).forceEndTurn(game.gameId)
    }

    @Test
    fun `sweep refreshes timer and does not force turn while trade is active`() {
        val (service, gameController, broker) = setup()
        val game = gameController.createGame(hostPlayerId = "host-1")
        gameController.joinGame(game.gameId, Player(id = "host-1", name = "Alice", iconId = "lindwurm"))
        gameController.joinGame(game.gameId, Player(id = "player-2", name = "Bob", iconId = "woerthersee"))
        game.currentPlayerIndex = 0
        game.phase = GamePhase.BUYING
        game.turnTimerStartedAtMillis = System.currentTimeMillis() - 61_000L
        val oldStartedAt = game.turnTimerStartedAtMillis
        game.pendingTradeOffer = TradeOffer(
            id = "trade-1",
            fromPlayerId = "host-1",
            toPlayerId = "player-2"
        )

        service.sweepExpiredTurns()

        Mockito.verify(broker, Mockito.never()).forceEndTurn(Mockito.anyString())
        org.junit.jupiter.api.Assertions.assertTrue(game.turnTimerStartedAtMillis > oldStartedAt)
    }
}
