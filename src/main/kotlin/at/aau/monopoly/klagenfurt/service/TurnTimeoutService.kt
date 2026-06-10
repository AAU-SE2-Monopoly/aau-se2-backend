package at.aau.monopoly.klagenfurt.service

import at.aau.monopoly.klagenfurt.controller.GameController
import at.aau.monopoly.klagenfurt.model.enums.GamePhase
import at.aau.monopoly.klagenfurt.websocket.broker.WebSocketBrokerController
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service

/**
 * Periodically scans all active games and force-ends the turn of any player who has
 * been idle longer than the configured timeout. This keeps games from getting stuck
 * if a player's device dies mid-turn.
 *
 * The actual turn resolution is delegated to [WebSocketBrokerController.forceEndTurn],
 * which acquires the per-game lock and executes any intermediate actions the player
 * would normally have to trigger themselves.
 */
@Service
class TurnTimeoutService(
    private val gameController: GameController,
    @Lazy private val brokerController: WebSocketBrokerController,
    @Value("\${app.turn-timeout-seconds:60}") private val turnTimeoutSeconds: Long
) {

    private val timeoutMillis: Long
        get() = turnTimeoutSeconds * 1000L

    /**
     * Runs every 5 seconds. For each in-progress game whose current player has exceeded
     * the turn timeout, forces the turn to end.
     */
    @Scheduled(fixedDelayString = "\${app.turn-timeout-check-interval-ms:5000}")
    fun sweepExpiredTurns() {
        val now = System.currentTimeMillis()
        gameController.listGameIds().forEach { gameId ->
            val gameState = gameController.getGameState(gameId) ?: return@forEach
            if (gameState.phase == GamePhase.WAITING || gameState.phase == GamePhase.FINISHED) {
                return@forEach
            }
            if (gameState.pendingTradeOffer != null) {
                gameState.resetTurnTimer(now)
                return@forEach
            }
            val startedAt = gameState.turnTimerStartedAtMillis
            if (startedAt <= 0) return@forEach
            if (now - startedAt < timeoutMillis) return@forEach

            try {
                val ended = brokerController.forceEndTurn(gameId)
                if (ended) {
                    logger.info("Turn auto-ended for game {} after {}s of inactivity.", gameId, turnTimeoutSeconds)
                }
            } catch (e: Exception) {
                logger.error("Failed to auto-end turn for game {}: {}", gameId, e.message, e)
            }
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(TurnTimeoutService::class.java)
    }
}
