package at.aau.monopoly.klagenfurt.model

import at.aau.monopoly.klagenfurt.model.card.ChanceCard
import at.aau.monopoly.klagenfurt.model.card.CommunityChestCard
import at.aau.monopoly.klagenfurt.model.enums.CardAction
import at.aau.monopoly.klagenfurt.model.enums.PropertyColor
import at.aau.monopoly.klagenfurt.model.field.*

/**
 * Factory that creates the standard 40-field Monopoly board and the two card decks.
 */
object BoardFactory {

    fun createDefaultBoard(): List<Field> = listOf(
        // 0
        GoField(),
        // 1
        PropertyField(id = 1,  name = "Herrengasse",  color = PropertyColor.BROWN,      price = 60,  rent = listOf(2, 10, 30, 90, 160, 250), houseCost = 50, hotelCost = 250),
        // 2
        CommunityChestField(id = 2),
        // 3
        PropertyField(id = 3,  name = "Heiligengeistplatz",         color = PropertyColor.BROWN,      price = 60,  rent = listOf(4, 20, 60, 180, 320, 450), houseCost = 50, hotelCost = 250),
        // 4
        TaxField(id = 4,  name = "Reichensteuer",    amount = 200),
        // 5
        RailroadField(id = 5,  name = "Hauptbahnhof"),
        // 6
        PropertyField(id = 6,  name = "Neuer Platz",       color = PropertyColor.LIGHT_BLUE, price = 100, rent = listOf(6, 30, 90, 270, 400, 550), houseCost = 50, hotelCost = 250),
        // 7
        ChanceField(id = 7),
        // 8
        PropertyField(id = 8,  name = "Alter Platz",        color = PropertyColor.LIGHT_BLUE, price = 100, rent = listOf(6, 30, 90, 270, 400, 550), houseCost = 50, hotelCost = 250),
        // 9
        PropertyField(id = 9,  name = "Benediktiner Platz",    color = PropertyColor.LIGHT_BLUE, price = 120, rent = listOf(8, 40, 100, 300, 450, 600), houseCost = 50, hotelCost = 250),
        // 10
        JailField(),
        // 11
        PropertyField(id = 11, name = "Cine City",     color = PropertyColor.PINK,       price = 140, rent = listOf(10, 50, 150, 450, 625, 750), houseCost = 100, hotelCost = 500),
        // 12
        UtilityField(id = 12, name = "Kelag Klagenfurt", price = 150),
        // 13
        PropertyField(id = 13, name = "McDonalds",         color = PropertyColor.PINK,       price = 140, rent = listOf(10, 50, 150, 450, 625, 750), houseCost = 100, hotelCost = 500),
        // 14
        PropertyField(id = 14, name = "Ruthar",       color = PropertyColor.PINK,       price = 160, rent = listOf(12, 60, 180, 500, 700, 900), houseCost = 100, hotelCost = 500),
        // 15
        RailroadField(id = 15, name = "Ostbahnhof"),
        // 16
        PropertyField(id = 16, name = "Wohnzimmer",       color = PropertyColor.ORANGE,     price = 180, rent = listOf(14, 70, 200, 550, 750, 950), houseCost = 100, hotelCost = 500),
        // 17
        CommunityChestField(id = 17),
        // 18
        PropertyField(id = 18, name = "Hafenstadt",      color = PropertyColor.ORANGE,     price = 180, rent = listOf(14, 70, 200, 550, 750, 950), houseCost = 100, hotelCost = 500),
        // 19
        PropertyField(id = 19, name = "Lendcafe",       color = PropertyColor.ORANGE,     price = 200, rent = listOf(16, 80, 220, 600, 800, 1000), houseCost = 100, hotelCost = 500),
        // 20
        FreeParkingField(),
        // 21
        PropertyField(id = 21, name = "City Arkaden",       color = PropertyColor.RED,        price = 220, rent = listOf(18, 90, 250, 700, 875, 1050), houseCost = 150, hotelCost = 750),
        // 22
        ChanceField(id = 22),
        // 23
        PropertyField(id = 23, name = "Le Burger",        color = PropertyColor.RED,        price = 220, rent = listOf(18, 90, 250, 700, 875, 1050), houseCost = 150, hotelCost = 750),
        // 24
        PropertyField(id = 24, name = "McMullens",       color = PropertyColor.RED,        price = 240, rent = listOf(20, 100, 300, 750, 925, 1100), houseCost = 150, hotelCost = 750),
        // 25
        RailroadField(id = 25, name = "Westbahnhof"),
        // 26
        PropertyField(id = 26, name = "Mensa",       color = PropertyColor.YELLOW,     price = 260, rent = listOf(22, 110, 330, 800, 975, 1150), houseCost = 150, hotelCost = 750),
        // 27
        PropertyField(id = 27, name = "Universität Klagenfurt",        color = PropertyColor.YELLOW,     price = 260, rent = listOf(22, 110, 330, 800, 975, 1150), houseCost = 150, hotelCost = 750),
        // 28
        UtilityField(id = 28, name = "Stadtwerke Klagenfurt", price = 150),
        // 29
        PropertyField(id = 29, name = "Lakeside",        color = PropertyColor.YELLOW,     price = 280, rent = listOf(24, 120, 360, 850, 1025, 1200), houseCost = 150, hotelCost = 750),
        // 30
        GoToJailField(),
        // 31
        PropertyField(id = 31, name = "Strandbad",        color = PropertyColor.GREEN,      price = 300, rent = listOf(26, 130, 390, 900, 1100, 1275), houseCost = 200, hotelCost = 1000),
        // 32
        PropertyField(id = 32, name = "Loretto", color = PropertyColor.GREEN,      price = 300, rent = listOf(26, 130, 390, 900, 1100, 1275), houseCost = 200, hotelCost = 1000),
        // 33
        CommunityChestField(id = 33),
        // 34
        PropertyField(id = 34, name = "Villa Lido",   color = PropertyColor.GREEN,      price = 320, rent = listOf(28, 150, 450, 1000, 1200, 1400), houseCost = 200, hotelCost = 1000),
        // 35
        RailroadField(id = 35, name = "Lendbahnhof"),
        // 36
        ChanceField(id = 36),
        // 37
        PropertyField(id = 37, name = "Botanischer Garten",            color = PropertyColor.DARK_BLUE,  price = 350, rent = listOf(35, 175, 500, 1100, 1300, 1500), houseCost = 200, hotelCost = 1000),
        // 38
        TaxField(id = 38, name = "Reichensteuer", amount = 100),
        // 39
        PropertyField(id = 39, name = "Kreuzbergl",             color = PropertyColor.DARK_BLUE,  price = 400, rent = listOf(50, 200, 600, 1400, 1700, 2000), houseCost = 200, hotelCost = 1000)
    )

    fun createChanceCards(): MutableList<ChanceCard> = mutableListOf(
        ChanceCard(id = 1,  description = "A GTI driver cruises through Reifnitz and gives you a ride to Go. Collect 200€.",                          action = CardAction.MOVE_TO,           targetFieldId = 0,  amount = 200),
        ChanceCard(id = 2,  description = "You meet your friends for a Guinness at McMullens. If you pass Go, collect 200€.",                               action = CardAction.MOVE_TO,           targetFieldId = 24),
        ChanceCard(id = 3,  description = "You have a date, go bowling at CineCity. If you pass Go, collect 200€.",                          action = CardAction.MOVE_TO,           targetFieldId = 11),
        ChanceCard(id = 4,  description = "Your train is about to leave without you! Go immediately to the nearest railroad!",                           action = CardAction.MOVE_TO,           targetFieldId = -1),
        ChanceCard(id = 5,  description = "You must leave the city immediately, go to the nearest means of transport!",                            action = CardAction.MOVE_TO,           targetFieldId = -2),
        ChanceCard(id = 6,  description = "You make a profit selling old clothes on Vinted. Collect 50€.",                        action = CardAction.COLLECT_MONEY,     amount = 50),
        ChanceCard(id = 7,  description = "You have been acquitted due to lack of evidence! Get out of jail free!",                                  action = CardAction.GET_OUT_OF_JAIL),
        ChanceCard(id = 8,  description = "You are too drunk and stumble 3 spaces back!",                                      action = CardAction.MOVE_FORWARD,      moveSpaces = -3),
        ChanceCard(id = 9,  description = "Due to excessive drinking you spend the next while in the drunk tank. Go directly to jail. Do not pass Go, do not collect 200€!",                                            action = CardAction.GO_TO_JAIL),
        ChanceCard(id = 10, description = "A climate protest got out of control, your houses and hotels were damaged. Pay 25€ for each house and 100€ for each hotel.", action = CardAction.PAY_MONEY, amount = 25),
        ChanceCard(id = 11, description = "The student union fee is due. Pay 15€.",                                  action = CardAction.PAY_MONEY,         amount = 15),
        ChanceCard(id = 12, description = "You have a discount train ticket and want to leave the city. Go to Hauptbahnhof.",                       action = CardAction.MOVE_TO,           targetFieldId = 5),
        ChanceCard(id = 13, description = "You take a walk on Kreuzbergl and admire the scenery.",                          action = CardAction.MOVE_TO,           targetFieldId = 39),
        ChanceCard(id = 14, description = "You invite all players to the Starnacht am Wörthersee. Pay each player 50€.",  action = CardAction.PAY_EACH_PLAYER,   amount = 50),
        ChanceCard(id = 15, description = "Your building savings contract matures. Collect 150€.",         action = CardAction.COLLECT_MONEY,     amount = 150),
        ChanceCard(id = 16, description = "You win the Bachmann Prize and receive 100€.",   action = CardAction.COLLECT_MONEY,     amount = 100)
    ).also { it.shuffle() }

    fun createCommunityChestCards(): MutableList<CommunityChestCard> = mutableListOf(
        CommunityChestCard(id = 1,  description = "You fly with RyanAir to Go. Collect 200€.", action = CardAction.MOVE_TO,           targetFieldId = 0, amount = 200),
        CommunityChestCard(id = 2,  description = "You win at the Admiral Waidmannsdorf betting shop. Collect 200€.", action = CardAction.COLLECT_MONEY,     amount = 200),
        CommunityChestCard(id = 3,  description = "A bill from the cosmetic surgeon. Pay 50€.",                       action = CardAction.PAY_MONEY,         amount = 50),
        CommunityChestCard(id = 4,  description = "Your ETFs yield a profit when sold, you receive 50€.",              action = CardAction.COLLECT_MONEY,     amount = 50),
        CommunityChestCard(id = 5,  description = "Klagenfurt prison releases you for good behavior.",                         action = CardAction.GET_OUT_OF_JAIL),
        CommunityChestCard(id = 6,  description = "You were caught dealing drugs in Göthepark. You are immediately taken to Klagenfurt prison. Do not pass Go, do not collect 200€!",                                   action = CardAction.GO_TO_JAIL),
        CommunityChestCard(id = 7,  description = "You sell roses on a Saturday night at the Eventstage. Collect 100€.", action = CardAction.COLLECT_MONEY, amount = 100),
        CommunityChestCard(id = 8,  description = "You work as a temporary waiter at Speki and receive 100€ in tips.",          action = CardAction.COLLECT_MONEY,     amount = 100),
        CommunityChestCard(id = 9,  description = "You win an AK education voucher worth 20€.",              action = CardAction.COLLECT_MONEY,     amount = 20),
        CommunityChestCard(id = 10, description = "You sell tickets for the Altstadtzauber. Collect 10€ from each player.", action = CardAction.COLLECT_FROM_EACH, amount = 10),
        CommunityChestCard(id = 11, description = "You sell cheap mulled wine at the Glühwein opening and collect 100€.",        action = CardAction.COLLECT_MONEY,     amount = 100),
        CommunityChestCard(id = 12, description = "You treat your homies to a döner, pay 100€.",                   action = CardAction.PAY_MONEY,         amount = 100),
        CommunityChestCard(id = 13, description = "You lose track of time shopping at Neuer Platz. Your car is in the Lindwurm parking garage. Pay 50€!",                     action = CardAction.PAY_MONEY,         amount = 50),
        CommunityChestCard(id = 14, description = "You sing at the After Work Market on Benediktiner Platz and receive 25€.",                 action = CardAction.COLLECT_MONEY,     amount = 25),
        CommunityChestCard(id = 15, description = "Your properties were damaged by youth vandalism. Pay 40€ for each house and 115€ for each hotel.", action = CardAction.PAY_MONEY, amount = 40),
        CommunityChestCard(id = 16, description = "You finish second at the Ironman and receive 10€.", action = CardAction.COLLECT_MONEY, amount = 10)
    ).also { it.shuffle() }
}
