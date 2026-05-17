# AGENTS.md – Monopoly Backend

Guidance for AI agents (and developers) working in this repository.

---

## Project Overview

A **Kotlin + Spring Boot 4** backend for a multiplayer Monopoly game.  
Communication with clients is exclusively via **STOMP over WebSocket**.  
All model objects are **JSON-serializable** via Jackson (`jackson-module-kotlin`).

---

## Tech Stack

| Concern | Choice |
|---|---|
| Language | Kotlin (JVM 21) |
| Framework | Spring Boot 4.0.3 |
| WebSocket | STOMP (`spring-boot-starter-websocket`) |
| Serialization | Jackson (`jackson-module-kotlin`) |
| Build | Gradle Kotlin DSL |
| Tests | JUnit 5 + Spring Boot Test |
| Coverage | JaCoCo → SonarCloud |

---

## Repository Layout

```
src/main/kotlin/at/aau/monopoly/klagenfurt/
├── WebSocketDemoServerApplication.kt   # Spring Boot entry point
├── controller/
│   └── GameController.kt               # Service: manages all active GameState instances
├── messaging/
│   └── dtos/
│       ├── GameAction.kt               # Inbound DTO  (client → server)
│       ├── GameEvent.kt                # Outbound DTO (server → all subscribers)
│       ├── GameLobbyInfo.kt            # Lightweight DTO for lobby game listing
│       └── LobbyEvent.kt              # Outbound DTO for lobby topic
├── model/
│   ├── BoardFactory.kt                 # Creates the 40-field board + card decks
│   ├── DiceRoll.kt                     # Value object: die1, die2, total, isDouble
│   ├── GameState.kt                    # Full state of one game
│   ├── Player.kt                       # Player state
│   ├── card/
│   │   ├── Card.kt                     # Abstract base (@JsonTypeInfo)
│   │   ├── ChanceCard.kt
│   │   └── CommunityChestCard.kt
│   ├── enums/
│   │   ├── CardAction.kt
│   │   ├── FieldType.kt
│   │   ├── GamePhase.kt
│   │   └── PropertyColor.kt
│   └── field/
│       ├── Field.kt                    # Abstract base (@JsonTypeInfo / @JsonSubTypes)
│       ├── PropertyField.kt
│       ├── RailroadField.kt
│       ├── UtilityField.kt
│       ├── TaxField.kt
│       ├── GoField.kt
│       ├── JailField.kt
│       ├── FreeParkingField.kt
│       ├── GoToJailField.kt
│       ├── ChanceField.kt
│       └── CommunityChestField.kt
├── service/
│   └── GameService.kt                  # Placeholder service (currently empty)
└── websocket/
    └── broker/
        ├── WebSocketBrokerConfig.kt    # STOMP config: endpoint /ws, prefix /app, broker /topic
        └── WebSocketBrokerController.kt# STOMP @MessageMapping handlers

src/test/kotlin/at/aau/monopoly/klagenfurt/
├── WebSocketBrokerIntegrationTest.kt   # Integration tests for game STOMP endpoints
├── WebSocketHandlerIntegrationTest.kt  # Integration tests for create/join lifecycle
├── WebSocketDemoServerApplicationTest.kt
├── Service/
│   └── GameServiceTest.kt
├── controller/
│   └── GameControllerTest.kt
├── model/
│   ├── BoardFactoryTest.kt
│   ├── DiceRollTest.kt
│   ├── GameStateTest.kt
│   └── PlayerTest.kt
└── websocket/
    ├── StompFrameHandlerClientImpl.kt  # Test helper: receives STOMP frames into a BlockingQueue
    └── broker/
        ├── WebSocketBrokerConfigTest.kt
        └── WebSocketBrokerControllerTest.kt
```

---

## STOMP Endpoints

The WebSocket server listens at **`ws://<host>/ws`**.

### Client → Server (`/app/…`)

| Destination | Payload type | Description |
|---|---|---|
| `/app/game/create` | `Player` | Create a new game; the sending player is automatically added as the first player. |
| `/app/game/join` | `GameAction` | Join an existing game. `gameId` and `playerId` are required; pass `"name"` and `"iconId"` in `payload`. |
| `/app/game/start` | `GameAction` | Start the game; advances phase to `ROLLING` and sets the first player's turn. |
| `/app/game/action` | `GameAction` | Perform an in-game action. See **Actions** below. |
| `/app/game/state` | `GameAction` | Request a full `GameState` snapshot for a game. |
| `/app/game/list` | `GameAction` | Request the list of all active games (broadcasts to `/topic/lobby`). |
| `/app/game/close` | `GameAction` | Host closes (removes) a game. Only allowed during `WAITING` phase by the host player. |

### Server → Client (`/topic/…`)

| Topic | Payload type | When |
|---|---|---|
| `/topic/game/{gameId}` | `GameEvent` | After every operation on that game (all events go here). |
| `/topic/game/{playerId}` | `GameEvent` | Sent to the creator on `/app/game/create` so they receive the gameId before subscribing to the real topic. |
| `/topic/lobby` | `LobbyEvent` | After game create/join/start/close; contains the current list of open games. |

### `GameEvent` structure

```json
{
  "gameId": "uuid",
  "event": "GAME_CREATED",
  "gameState": { ... },
  "message": "Human-readable description (optional)"
}
```

### `event` values

| Value | Trigger |
|---|---|
| `GAME_CREATED` | `/app/game/create` succeeded |
| `PLAYER_JOINED` | `/app/game/join` succeeded |
| `GAME_STARTED` | `/app/game/start` succeeded |
| `DICE_ROLLED` | `action = "ROLL_DICE"` |
| `TURN_ENDED` | `action = "END_TURN"` |
| `JAIL_FINE_PAID` | `action = "PAY_JAIL_FINE"` |
| `JAIL_CARD_USED` | `action = "USE_JAIL_CARD"` |
| `ACTION_DRAWN` | `action = "DRAW_CARD"` |
| `ACTION_EXECUTED` | `action = "EXECUTE_ACTION"` |
| `PROPERTY_BOUGHT` | `action = "BUY_PROPERTY"` |
| `GAME_CLOSED` | `/app/game/close` succeeded |
| `LOBBY_UPDATE` | Sent on `/topic/lobby` after lobby-changing operations |
| `STATE_SNAPSHOT` | `/app/game/state` (game found) |
| `ERROR` | Any operation on an unknown gameId, or unrecognised action |

### `LobbyEvent` structure

```json
{
  "event": "LOBBY_UPDATE",
  "games": [
    {
      "gameId": "uuid",
      "hostPlayerName": "Alice",
      "playerCount": 2,
      "maxPlayers": 5,
      "phase": "WAITING",
      "playerIds": ["uuid1", "uuid2"]
    }
  ],
  "message": null
}
```

### `GameAction` structure

```json
{
  "gameId": "uuid",
  "playerId": "uuid",
  "action": "ROLL_DICE",
  "payload": {}
}
```

### Currently supported `action` values

| Action | Effect |
|---|---|
| `ROLL_DICE` | Rolls two dice, moves player, handles jail/doublets/Go To Jail/passing Go. Sets phase to `BUYING` or `TURN_END`. Supports `payload: {"cheat": "true"}` for forced double-six. |
| `END_TURN` | If doublet was rolled (and player not in jail), grants another turn (stays same player, phase → `ROLLING`). Otherwise advances to next player. |
| `PAY_JAIL_FINE` | Pays 50M to leave jail. Only valid when player `inJail`. |
| `USE_JAIL_CARD` | Uses a Get Out of Jail Free card. Only valid when player has one and is `inJail`. |
| `DRAW_CARD` | Draws a Chance or Community Chest card. Requires `payload: {"cardType": "CHANCE" | "COMMUNITY_CHEST"}`. Player must be on matching field. One card per turn. |
| `EXECUTE_ACTION` | Executes the previously drawn card's action (money transfer, movement, jail, etc.). |
| `BUY_PROPERTY` | Buys the property at `payload: {"fieldId": "N"}`. Must be current player, `BUYING` phase, standing on field, field unowned, sufficient funds. |

---

## Key Classes

### `GameController` (`@Service`)

Manages the lifecycle of all concurrent games via a `ConcurrentHashMap<String, GameState>`.

| Method | Description |
|---|---|
| `createGame(hostPlayerId)` | Creates a UUID-keyed `GameState` with a fresh board, shuffled card decks, and records the host player ID |
| `joinGame(gameId, player)` | Adds a player; enforces max 5 players, no duplicate IDs, and WAITING phase. Silently accepts rejoins (same player ID). |
| `getGameState(gameId)` | Returns `GameState?` |
| `removeGame(gameId)` | Removes a game; returns `true` if it existed |
| `closeGame(gameId, playerId)` | Removes a game if requester is the host and game is in WAITING phase |
| `listGameIds()` | Returns all active game IDs |
| `listAllGames()` | Returns `List<GameLobbyInfo>` with summary info for all active games |

### `GameState`

| Field | Type | Description |
|---|---|---|
| `gameId` | `String` | UUID |
| `fields` | `List<Field>` | The 40 board fields (immutable after creation) |
| `players` | `MutableList<Player>` | All players in join order |
| `currentPlayerIndex` | `Int` | Index into `players` |
| `phase` | `GamePhase` | `WAITING → ROLLING → BUYING → … → FINISHED` |
| `chanceCards` | `MutableList<ChanceCard>` | Pre-shuffled deck |
| `communityChestCards` | `MutableList<CommunityChestCard>` | Pre-shuffled deck |
| `freeParkingMoney` | `Int` | House-rule pot |
| `lastDiceRoll` | `DiceRoll?` | Result of the most recent roll |
| `hostPlayerId` | `String` | The player who created the game (host) |
| `currentActionCard` | `Card?` | Current Chance/Community Chest card waiting for execution |
| `hasDrawnCardThisTurn` | `Boolean` | Track if player has already drawn a card this turn |

Computed helpers: `currentPlayer`, `advanceTurn()`, `endCurrentTurn()`, `isGameOver()`.

### `Player`

| Field | Default | Description |
|---|---|---|
| `id` | — | Unique string (UUID) |
| `name` | — | Display name |
| `iconId` | `"lindwurm"` | Player icon identifier (defaults to "lindwurm" if null/blank) |
| `position` | `0` | Board index (0–39) |
| `money` | `1500` | Cash on hand |
| `inJail` | `false` | Jail flag |
| `jailTurns` | `0` | Turns spent in jail |
| `consecutiveDoublets` | `0` | Consecutive doublet counter (3 → go to jail) |
| `getOutOfJailCards` | `0` | Number of GOOJF cards held |
| `ownedPropertyIds` | `[]` | List of owned field IDs (as `MutableList<Int>`) |

Helper: `isBankrupt()` — returns `true` when `money <= 0` and no owned properties.

### `BoardFactory` (singleton `object`)

| Method | Returns |
|---|---|
| `createDefaultBoard()` | Standard 40-field Monopoly board in order (field indices 0–39) |
| `createChanceCards()` | 16 `ChanceCard`s, shuffled |
| `createCommunityChestCards()` | 16 `CommunityChestCard`s, shuffled |

### Field hierarchy

`Field` (abstract) ← `PropertyField`, `RailroadField`, `UtilityField`, `TaxField`, `GoField`, `JailField`, `FreeParkingField`, `GoToJailField`, `ChanceField`, `CommunityChestField`.

Jackson polymorphism uses the existing `"type"` property as the discriminator (`@JsonTypeInfo(As.EXISTING_PROPERTY)`), so no extra field is added to the JSON.

### Card hierarchy

`Card` (abstract) ← `ChanceCard`, `CommunityChestCard`.

Jackson adds a `"cardType"` property as the discriminator (`@JsonTypeInfo(As.PROPERTY)`).

---

## Enums

| Enum | Values |
|---|---|
| `FieldType` | `GO`, `PROPERTY`, `COMMUNITY_CHEST`, `TAX`, `RAILROAD`, `CHANCE`, `JAIL`, `UTILITY`, `FREE_PARKING`, `GO_TO_JAIL` |
| `PropertyColor` | `BROWN`, `LIGHT_BLUE`, `PINK`, `ORANGE`, `RED`, `YELLOW`, `GREEN`, `DARK_BLUE` |
| `GamePhase` | `WAITING`, `ROLLING`, `BUYING`, `AUCTIONING`, `TURN_END`, `FINISHED` |
| `CardAction` | `COLLECT_MONEY`, `PAY_MONEY`, `MOVE_TO`, `MOVE_FORWARD`, `GO_TO_JAIL`, `GET_OUT_OF_JAIL`, `PAY_EACH_PLAYER`, `COLLECT_FROM_EACH` |

---

## JSON Serialization Rules

1. **All model classes are Kotlin `data class`es** with default values on every parameter so Jackson can construct them without a no-arg constructor plugin.
2. **`Field` subclasses** are disambiguated by the `"type"` string already present in every JSON object (e.g. `"type": "PROPERTY"`).
3. **`Card` subclasses** are disambiguated by an injected `"cardType"` string (e.g. `"cardType": "CHANCE"`).
4. **`DiceRoll`** is a plain `data class` — never use `Pair<Int,Int>` in model objects sent over the wire.
5. The `JacksonJsonMessageConverter` is used in all STOMP sessions (both production and test).
6. **`Player` fields** use `@JsonSetter(nulls = Nulls.SKIP)` to preserve defaults when JSON sends `null`.

---

## Build & Run

```bash
# Run the application
./gradlew bootRun

# Run all tests
./gradlew test

# Generate JaCoCo coverage report
./gradlew jacocoTestReport
# Report: build/reports/jacoco/jacocoTestReport/jacocoTestReport.xml
```

---

## Testing Guidelines

- **Integration tests** use `@SpringBootTest(webEnvironment = RANDOM_PORT)` and a real `WebSocketStompClient`.
- Connect to `ws://localhost:{port}/ws`.
- Use `JacksonJsonMessageConverter` for all game message tests.
- Use `StompFrameHandlerClientImpl<T>` (in `src/test/…/websocket/`) to drain received frames into a `BlockingQueue<T>`.
- For error-path tests, subscribe to `/topic/game/{fakeGameId}` **before** sending an action — the simple broker only delivers to active subscribers.
- All new game logic must be covered by at least one integration or unit test.

---

## What Is Not Yet Implemented

The following items are tracked in `MONOPOLY_PLAN.md` and should be added in future iterations:

- Rent calculation and payment
- Building houses and hotels
- Mortgage / unmortgage
- Trading between players
- Bankruptcy and game-over detection wired into the WebSocket flow
- `GameController.removeGame()` called on disconnect / game-over
- Auctions when a player declines to buy a property

---

## Coding Conventions

- Package root: `at.aau.monopoly.klagenfurt`
- New model classes go under `model/` (or an appropriate sub-package).
- New STOMP handlers go in `WebSocketBrokerController` (or a new controller in `websocket/broker/`).
- New service-layer logic goes in `controller/GameController` or a dedicated service class under `service/`.
- Do **not** add raw WebSocket handlers (`TextWebSocketHandler` / `WebSocketHandlerConfig`) — STOMP only.
- Keep all DTO and model classes as `data class` with default values to remain Jackson-friendly.
- Use `@JsonSetter(nulls = Nulls.SKIP)` on mutable fields with defaults to guard against null payloads from clients.
- Max players per game is **5** (enforced in `GameController.maxPlayersPerGame`).
- On WebSocket disconnect, players are **not** removed from the game — their slot persists to allow reconnection via the rejoin logic in `joinGame`.
