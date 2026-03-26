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
src/main/kotlin/at/aau/serg/websocketdemoserver/
├── WebSocketDemoServerApplication.kt   # Spring Boot entry point
├── controller/
│   └── GameController.kt               # Service: manages all active GameState instances
├── messaging/
│   └── dtos/
│       ├── GameAction.kt               # Inbound DTO  (client → server)
│       └── GameEvent.kt                # Outbound DTO (server → all subscribers)
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
└── websocket/
    └── broker/
        ├── WebSocketBrokerConfig.kt    # STOMP config: endpoint /ws, prefix /app, broker /topic
        └── WebSocketBrokerController.kt# STOMP @MessageMapping handlers

src/test/kotlin/at/aau/serg/websocketdemoserver/
├── WebSocketBrokerIntegrationTest.kt   # Integration tests for game STOMP endpoints
├── WebSocketHandlerIntegrationTest.kt  # Integration tests for create/join lifecycle
└── websocket/
    └── StompFrameHandlerClientImpl.kt  # Test helper: receives STOMP frames into a BlockingQueue
```

---

## STOMP Endpoints

The WebSocket server listens at **`ws://<host>/ws`**.

### Client → Server (`/app/…`)

| Destination | Payload type | Description |
|---|---|---|
| `/app/game/create` | `Player` | Create a new game; the sending player is automatically added as the first player. |
| `/app/game/join` | `GameAction` | Join an existing game. `gameId` and `playerId` are required; pass `"name"` in `payload`. |
| `/app/game/start` | `GameAction` | Start the game; advances phase to `ROLLING` and sets the first player's turn. |
| `/app/game/action` | `GameAction` | Perform an in-game action. See **Actions** below. |
| `/app/game/state` | `GameAction` | Request a full `GameState` snapshot for a game. |

### Server → Client (`/topic/…`)

| Topic | Payload type | When |
|---|---|---|
| `/topic/game/{gameId}` | `GameEvent` | After every operation on that game (all events go here). |

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
| `STATE_SNAPSHOT` | `/app/game/state` (game found) |
| `ERROR` | Any operation on an unknown gameId, or unrecognised action |

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
| `ROLL_DICE` | Rolls two dice, stores `DiceRoll` on `GameState.lastDiceRoll`, sets phase to `BUYING` |
| `END_TURN` | Calls `GameState.advanceTurn()`, wraps player index, sets phase to `ROLLING` |

---

## Key Classes

### `GameController` (`@Service`)

Manages the lifecycle of all concurrent games via a `ConcurrentHashMap<String, GameState>`.

| Method | Description |
|---|---|
| `createGame()` | Creates a UUID-keyed `GameState` with a fresh board and shuffled card decks |
| `joinGame(gameId, player)` | Adds a player; enforces max 6 players and no duplicate IDs |
| `getGameState(gameId)` | Returns `GameState?` |
| `removeGame(gameId)` | Removes a game; returns `true` if it existed |
| `listGameIds()` | Returns all active game IDs |

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

Computed helpers: `currentPlayer`, `advanceTurn()`, `isGameOver()`.

### `Player`

| Field | Default | Description |
|---|---|---|
| `id` | — | Unique string (UUID) |
| `name` | — | Display name |
| `position` | `0` | Board index (0–39) |
| `money` | `1500` | Cash on hand |
| `inJail` | `false` | Jail flag |
| `jailTurns` | `0` | Turns spent in jail |
| `getOutOfJailCards` | `0` | Number of GOOJF cards held |
| `ownedPropertyIds` | `[]` | List of owned field IDs |

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

- Player movement on the board after rolling dice
- Buying properties / auctions
- Rent calculation and payment
- Building houses and hotels
- Jail mechanics (going to jail, paying bail, rolling doubles)
- Mortgage / unmortgage
- Trading between players
- Bankruptcy and game-over detection wired into the WebSocket flow
- Card draw and resolution for Chance / Community Chest fields
- `GameController.removeGame()` called on disconnect / game-over

---

## Coding Conventions

- Package root: `at.aau.serg.websocketdemoserver`
- New model classes go under `model/` (or an appropriate sub-package).
- New STOMP handlers go in `WebSocketBrokerController` (or a new controller in `websocket/broker/`).
- New service-layer logic goes in `controller/GameController` or a dedicated service class.
- Do **not** add raw WebSocket handlers (`TextWebSocketHandler` / `WebSocketHandlerConfig`) — STOMP only.
- Keep all DTO and model classes as `data class` with default values to remain Jackson-friendly.

