# Monopoly Game – Backend Class Structure Plan

## 1. Overview

This document describes the planned class structure for a Monopoly game backend built with **Kotlin + Spring Boot**. All model classes will be **JSON-serializable** (using Jackson via `data class`es with default constructors) so they can be sent over WebSockets (STOMP).

All new code lives under the base package:
```
at.aau.serg.websocketdemoserver
```

---

## 2. Package Structure

```
at.aau.serg.websocketdemoserver/
├── model/
│   ├── field/
│   │   ├── Field.kt                  // abstract base class
│   │   ├── PropertyField.kt          // buyable property (street)
│   │   ├── RailroadField.kt          // buyable railroad station
│   │   ├── UtilityField.kt           // buyable utility (electric/water)
│   │   ├── TaxField.kt               // income tax / luxury tax
│   │   ├── ChanceField.kt            // chance card trigger
│   │   ├── CommunityChestField.kt    // community chest card trigger
│   │   ├── GoField.kt                // "Go" – collect salary
│   │   ├── JailField.kt              // "Just Visiting / In Jail"
│   │   ├── FreeParkingField.kt       // free parking
│   │   └── GoToJailField.kt          // sends player to jail
│   ├── card/
│   │   ├── Card.kt                   // abstract base class
│   │   ├── ChanceCard.kt
│   │   └── CommunityChestCard.kt
│   ├── Player.kt
│   ├── GameState.kt
│   └── enums/
│       ├── FieldType.kt              // enum discriminator for JSON polymorphism
│       ├── CardAction.kt             // enum for card effects
│       ├── PropertyColor.kt          // color groups for streets
│       └── GamePhase.kt              // e.g. WAITING, ROLLING, BUYING, AUCTIONING, FINISHED
├── controller/
│   └── GameController.kt             // manages multiple GameState instances
├── websocket/
│   └── broker/
│       ├── WebSocketBrokerConfig.kt   // (already exists)
│       └── WebSocketBrokerController.kt // (already exists – will add game endpoints)
└── messaging/
    └── dtos/
        ├── GameAction.kt              // inbound DTO: player action (roll, buy, end turn …)
        └── GameEvent.kt               // outbound DTO: server → clients notification
```

---

## 3. Class Details

### 3.1 Fields (Board Squares)

#### 3.1.1 `Field` (abstract base)

```
abstract class Field(
    val id: Int,               // unique board position index (0–39)
    val name: String,          // display name
    val type: FieldType        // discriminator for JSON polymorphism
)
```

- Annotated with `@JsonTypeInfo` / `@JsonSubTypes` so Jackson can serialize & deserialize the correct subclass automatically.

#### 3.1.2 `PropertyField` (extends `Field`)

```
data class PropertyField(
    id, name, type = FieldType.PROPERTY,
    val color: PropertyColor,  // color group (BROWN, LIGHT_BLUE, …)
    val price: Int,
    val rent: List<Int>,       // rent[0] = no houses, rent[1] = 1 house, … rent[5] = hotel
    val houseCost: Int,
    val hotelCost: Int,
    var ownerId: String? = null,   // null = unowned
    var houses: Int = 0,
    var hasHotel: Boolean = false
)
```

#### 3.1.3 `RailroadField` (extends `Field`)

```
data class RailroadField(
    id, name, type = FieldType.RAILROAD,
    val price: Int = 200,
    var ownerId: String? = null
)
```

#### 3.1.4 `UtilityField` (extends `Field`)

```
data class UtilityField(
    id, name, type = FieldType.UTILITY,
    val price: Int = 150,
    var ownerId: String? = null
)
```

#### 3.1.5 `TaxField` (extends `Field`)

```
data class TaxField(
    id, name, type = FieldType.TAX,
    val amount: Int        // e.g. 200 for Income Tax, 100 for Luxury Tax
)
```

#### 3.1.6 Simple Fields

`GoField`, `JailField`, `FreeParkingField`, `GoToJailField`, `ChanceField`, `CommunityChestField` – each a thin data class extending `Field` with the matching `FieldType` and **no extra properties** (behaviour is handled by the game logic layer later).

---

### 3.2 Cards

#### 3.2.1 `Card` (abstract base)

```
abstract class Card(
    val id: Int,
    val description: String,
    val action: CardAction     // enum value describing the effect
)
```

#### 3.2.2 `ChanceCard` / `CommunityChestCard`

Extend `Card`. May carry extra payload fields depending on `CardAction` (e.g. `amount: Int`, `targetFieldId: Int`).

#### `CardAction` enum (examples)

| Value | Meaning |
|---|---|
| `COLLECT_MONEY` | Player receives money from bank |
| `PAY_MONEY` | Player pays money to bank |
| `MOVE_TO` | Move to a specific field |
| `MOVE_FORWARD` | Move forward N spaces |
| `GO_TO_JAIL` | Go directly to jail |
| `GET_OUT_OF_JAIL` | Receive a "Get Out of Jail Free" card |
| `PAY_EACH_PLAYER` | Pay every other player |
| `COLLECT_FROM_EACH` | Collect from every other player |

---

### 3.3 `Player`

```
data class Player(
    val id: String,                    // unique ID (UUID)
    var name: String,
    var position: Int = 0,             // board field index
    var money: Int = 1500,             // starting cash
    var inJail: Boolean = false,
    var jailTurns: Int = 0,
    var getOutOfJailCards: Int = 0,
    val ownedPropertyIds: MutableList<Int> = mutableListOf()
)
```

---

### 3.4 `GameState`

```
data class GameState(
    val gameId: String,                       // UUID
    val fields: List<Field>,                  // the 40 board fields (initialised once)
    val players: MutableList<Player>,
    var currentPlayerIndex: Int = 0,
    var phase: GamePhase = GamePhase.WAITING,
    val chanceCards: MutableList<ChanceCard>,
    val communityChestCards: MutableList<CommunityChestCard>,
    var freeParkingMoney: Int = 0,            // optional house rule
    var lastDiceRoll: Pair<Int, Int>? = null
)
```

Key responsibilities (later – **not yet implemented**):
- Hold the full, authoritative state of one game.
- Be fully serializable so the entire snapshot can be pushed to all clients.

---

### 3.5 `GameController` (Service / Singleton)

```
@Service
class GameController {
    private val games: ConcurrentHashMap<String, GameState>

    fun createGame(): GameState
    fun joinGame(gameId: String, player: Player): GameState
    fun getGameState(gameId: String): GameState?
    fun removeGame(gameId: String)
}
```

- Manages the lifecycle of multiple concurrent games.
- Will be injected into the WebSocket broker controller.

---

### 3.6 WebSocket DTOs

#### `GameAction` (client → server)

```
data class GameAction(
    val gameId: String,
    val playerId: String,
    val action: String,      // "ROLL_DICE", "BUY_PROPERTY", "END_TURN", …
    val payload: Map<String, Any>? = null   // optional extra data
)
```

#### `GameEvent` (server → client)

```
data class GameEvent(
    val gameId: String,
    val event: String,       // "GAME_STARTED", "DICE_ROLLED", "PROPERTY_BOUGHT", …
    val gameState: GameState,
    val message: String? = null
)
```

---

### 3.7 Enums

| Enum | Values |
|---|---|
| `FieldType` | `GO`, `PROPERTY`, `COMMUNITY_CHEST`, `TAX`, `RAILROAD`, `CHANCE`, `JAIL`, `UTILITY`, `FREE_PARKING`, `GO_TO_JAIL` |
| `PropertyColor` | `BROWN`, `LIGHT_BLUE`, `PINK`, `ORANGE`, `RED`, `YELLOW`, `GREEN`, `DARK_BLUE` |
| `GamePhase` | `WAITING`, `ROLLING`, `BUYING`, `AUCTIONING`, `TURN_END`, `FINISHED` |
| `CardAction` | *(see §3.2)* |

---

## 4. JSON Serialization Strategy

Since the project already includes `com.fasterxml.jackson.module:jackson-module-kotlin`, we leverage Jackson's **polymorphic type handling** for the `Field` hierarchy:

```kotlin
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes(
    JsonSubTypes.Type(value = PropertyField::class,        name = "PROPERTY"),
    JsonSubTypes.Type(value = RailroadField::class,        name = "RAILROAD"),
    JsonSubTypes.Type(value = UtilityField::class,         name = "UTILITY"),
    JsonSubTypes.Type(value = TaxField::class,             name = "TAX"),
    JsonSubTypes.Type(value = GoField::class,              name = "GO"),
    JsonSubTypes.Type(value = JailField::class,            name = "JAIL"),
    JsonSubTypes.Type(value = FreeParkingField::class,     name = "FREE_PARKING"),
    JsonSubTypes.Type(value = GoToJailField::class,        name = "GO_TO_JAIL"),
    JsonSubTypes.Type(value = ChanceField::class,          name = "CHANCE"),
    JsonSubTypes.Type(value = CommunityChestField::class,  name = "COMMUNITY_CHEST"),
)
abstract class Field(...)
```

All `data class` properties use **default values** so Jackson can construct objects via the no-arg-like Kotlin constructor.

The same pattern applies to the `Card` hierarchy.

---

## 5. WebSocket Endpoints (planned)

These endpoints will be added to `WebSocketBrokerController`:

| STOMP Destination (client sends) | Response Topic | Description |
|---|---|---|
| `/app/game/create` | `/topic/game/{gameId}` | Create a new game lobby |
| `/app/game/join` | `/topic/game/{gameId}` | Join an existing game |
| `/app/game/start` | `/topic/game/{gameId}` | Start the game |
| `/app/game/action` | `/topic/game/{gameId}` | Perform an in-game action (roll, buy, end turn …) |
| `/app/game/state` | `/topic/game/{gameId}` | Request the current game state snapshot |

All responses are `GameEvent` objects containing the full `GameState`.

---

## 6. Board Initialisation

A utility function `createDefaultBoard(): List<Field>` will create the standard 40-field Monopoly board in order (Go → Mediterranean Avenue → … → Boardwalk). This will be called once when a new `GameState` is created.

Similarly, `createChanceCards(): List<ChanceCard>` and `createCommunityChestCards(): List<CommunityChestCard>` will populate and shuffle the card decks.

---

## 7. Implementation Order (suggested)

| Step | Task | Files |
|---|---|---|
| **1** | Create enums | `FieldType`, `PropertyColor`, `GamePhase`, `CardAction` |
| **2** | Create `Field` hierarchy | `Field.kt`, `PropertyField.kt`, `RailroadField.kt`, … |
| **3** | Create `Card` hierarchy | `Card.kt`, `ChanceCard.kt`, `CommunityChestCard.kt` |
| **4** | Create `Player` | `Player.kt` |
| **5** | Create `GameState` | `GameState.kt` |
| **6** | Create board initialisation utility | `BoardFactory.kt` |
| **7** | Create `GameController` service | `GameController.kt` |
| **8** | Create WebSocket DTOs | `GameAction.kt`, `GameEvent.kt` |
| **9** | Add STOMP endpoints | Update `WebSocketBrokerController.kt` |
| **10** | Write unit tests for each class | `src/test/kotlin/…` |
| **11** | Implement game logic (dice, buying, rent, jail …) | Across model & controller |

---

## 8. Testing Strategy

- **Unit tests** for each model class: verify JSON serialization round-trip (serialize → deserialize → assert equality).
- **Unit tests** for `GameController`: create / join / remove games.
- **Integration tests** for WebSocket endpoints using `StompSession` (similar to existing `WebSocketBrokerIntegrationTest`).
- **Game logic tests**: simulate turns, buying, rent payment, jail, winning condition.

---

## 9. Open Questions / Decisions

| # | Question | Notes |
|---|---|---|
| 1 | Auction mechanic? | Classic rules require auctions when a player declines to buy. Can be added later. |
| 2 | Trading between players? | Needs a separate negotiation flow. Defer to a later phase. |
| 3 | House building rules? | "Must build evenly across a color group" – enforce in game logic. |
| 4 | Mortgage mechanic? | Add `isMortgaged: Boolean` to buyable fields later. |
| 5 | Max players per game? | Standard Monopoly: 2–6 (or 2–8). Enforce in `GameController.joinGame()`. |
| 6 | Timeout / disconnect handling? | Handle via WebSocket session lifecycle events. |

