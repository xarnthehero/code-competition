import Foundation

public struct StderrOutputStream: TextOutputStream {
    public mutating func write(_ string: String) { fputs(string, stderr) }
}
public var errStream = StderrOutputStream()

//Used to call methods before the are declared, because you can't do that for some reason (come on swift..)
protocol ParserTricker {
    //Will be cast to a Buildable in method implementation, Buildable is undeclared now
    func haveMoneyToBuild(buildableAny: Any) -> Bool
    func canBuild(buildableAny: Any, locationAny: Any) -> Bool
    func updateIncome()
    func updateActiveCells()
}
var stupidSwift: ParserTricker?

let debugOn = true
let traceOn = true
var tempOn = false
var inputOn = false
func debug(_ string: String) {
    if debugOn {
        print(string, to: &errStream)
    }
}
func trace(_ string: String) {
    if traceOn {
        debug(string)
    }
}

func temp(_ string: String) {
    if tempOn {
        debug(string)
    }
}
func getLine() -> String {
    let line = readLine()!
    if inputOn { debug("Reading: \(line)") }
    return line
}

protocol GameObject {
    var owner: Owner { get }
    var stillAlive: Bool { get set }
}

protocol Buildable: GameObject {
    var goldCost: Int { get }
    var incomeCost: Int { get }
    var location: Location { get }
    var buildCommand: String { get }
    func postBuild()
}

enum Owner: Int {
    case me = 0,
    enemy = 1,
    none = 2
}

enum BuildingType: Int {
    case headquarters = 0,
    mine = 1,
    tower = 2
}

struct Queue<T>{
    var items:[T] = []
    mutating func enqueue(element: T) { items.append(element); }
    mutating func enqueueAll(elements: [T?]) {
        for item in elements {
            if let item = item {
                enqueue(element: item)
            }
        }
    }
    mutating func dequeue() -> T?
    {
        guard !items.isEmpty else { return nil }
        return items.remove(at: 0)
    }
}

//(x, y) coordinate system, 0,0 is top left, 11,11 is bottom right
struct Location: Hashable {
    let x: Int
    let y: Int
    
    var topLeft: Bool {
        get {
            return x == 0 && y == 0
        }
    }
    var bottomRight: Bool {
        get {
            return x == 11 && y == 11
        }
    }
    
    init(x: Int, y: Int) {
        self.x = x
        self.y = y
    }
    
    //For convienene
    init() {
        x = 0
        y = 0
    }
    
    func hash(into hasher: inout Hasher) {
        hasher.combine(x)
        hasher.combine(y)
    }
    
    func distance(to: Location) -> Int {
        return abs(x - to.x) + abs(y - to.y)
    }
    
    func printString() -> String {
        return String(x) + " " + String(y)
    }
    
    static func ==(lhs: Location, rhs: Location) -> Bool {
        return lhs.x == rhs.x && lhs.y == rhs.y
    }
}

class GameState: CustomStringConvertible {
    
    var turn: Int = 0 {
        didSet {
            _turnCommand = ""
        }
    }
    private var _turnCommand: String = ""
    var turnCommand: String {
        get {
            if _turnCommand == "" {
                return "WAIT;"
            }
            return _turnCommand
        }
    }
    var baseLocation: Location = Location()
    var enemyBaseLocation: Location = Location()
    var gold: Int = 0
    var income: Int = 0
    var enemyGold: Int = 0
    var enemyIncome: Int = 0
    
    func appendCommand(todo: String) {
        debug("Appending Comand: \(todo)")
        _turnCommand = _turnCommand + todo + ";"
    }
    
    var description: String {
        get {
            return "Gold:\(gold) Income:\(income) EnemyGold:\(enemyGold) EnemyIncome:\(enemyIncome)"
        }
    }
}

/** Global Variables **/
let gs = GameState()



enum Direction {
    case up, down, left, right
}

class GameTile: CustomStringConvertible, Hashable {

    static var map: [Location : GameTile] = [:]
    static var tiles: [GameTile] = []
    
    var symbol: Character {
        didSet {
            symbolSet()
        }
    }
    var canMoveTo: Bool = true
    var owner: Owner = .me
    var active: Bool = true
    let location: Location
    //True if a mine can be placed here
    var mineSpot: Bool = false
    
    var above: GameTile? = nil
    var below: GameTile? = nil
    var left: GameTile? = nil
    var right: GameTile? = nil
    var adjacentTiles: [GameTile] {
        get {
            var array: [GameTile] = []
            if above != nil { array.append(above!) }
            if below != nil { array.append(below!) }
            if left != nil { array.append(left!) }
            if right != nil { array.append(right!) }
            return array
        }
    }
    
    //Each tile has a map of how far any other tile's shortest distance is
    var heatMap: [GameTile : Int] = [:]
    
    //A thing that is on top of this tile (fighter, building...)
    var gameObject: GameObject? = nil {
        didSet {
            if let obj = gameObject {
                trace("Setting Tile Object - \(obj) at \(location)")
                active = true
                //Someday if my code gets better I need to update the inactive cells that possibly became active
                owner = obj.owner
            }
        }
    }
    
    init(symbol: Character, at: Location) {
        self.symbol = symbol
        self.location = at
        //Silly swift doesn't call didSet in inits...
        symbolSet()
    }
    
    func createHeatMap() {
        guard canMoveTo else { return }
        var queue = Queue<GameTile>()
        heatMap[self] = 0
        if location == Location(x: 3, y: 3) {
            tempOn = true
        } else {tempOn = false}
        queue.enqueueAll(elements: adjacentTiles.filter { tile in tile.canMoveTo })
        while let currentTile = queue.dequeue() {
            guard heatMap[currentTile] == nil else { continue }
            guard currentTile.canMoveTo else { continue }
            var minDistance: Int? = nil
            for tile in currentTile.adjacentTiles {
                if let distance = heatMap[tile], tile.canMoveTo {
                    minDistance = minDistance == nil ? distance : min(minDistance!, distance)
                }
            }
            if let minDistance = minDistance {
                let newDistance = minDistance + 1
                heatMap[currentTile] = heatMap[currentTile] == nil ? newDistance : min(heatMap[currentTile]!, newDistance)
                queue.enqueueAll(elements: currentTile.adjacentTiles.filter { tile in tile.canMoveTo })
            }
        }
    }
    
    private func symbolSet() {
        switch(symbol) {
        case "#":
            canMoveTo = false
            owner = .none
            active = false
        case ".":
            canMoveTo = true
            owner = .none
            active = false
        case "O":
            canMoveTo = true
            owner = .me
            active = true
        case "o":
            canMoveTo = true
            owner = .me
            active = false
        case "X":
            canMoveTo = true
            owner = .enemy
            active = true
        case "x":
            canMoveTo = true
            owner = .enemy
            active = false
        default:
            canMoveTo = false
            owner = .enemy
            active = false
        }
    }
    
    func next(direction: Direction) -> GameTile? {
        switch direction {
        case .up:
            return above
        case .down:
            return below
        case .left:
            return left
        case .right:
            return right
        }
    }
    
    func allTheWay(direction: Direction, movableOnly: Bool = false) -> GameTile {
        var goodTile = self
        while let nextTile = goodTile.next(direction: direction), !movableOnly || nextTile.canMoveTo {
            goodTile = nextTile
        }
        return goodTile
    }
    
    func distance(to: GameTile) -> Int {
        return location.distance(to: to.location)
    }
    
    var description: String {
        get {
            return "\(location)"
        }
    }
    
    func hash(into hasher: inout Hasher) {
        hasher.combine(location)
    }
    
    /** Static Methods **/
    static func printMap(tile: GameTile) {
        var tile = tile.allTheWay(direction: .up).allTheWay(direction: .left)
        
        while tile.right != nil {
            tile = tile.right!
        }
    }
    
    //Returns the closest tile to the location out of the list of tiles
    private static func closest(to: Location, outOf: [GameTile]) -> GameTile? {
        return outOf.reduce(nil, { result, tile in
            guard result != nil else { return tile }
            if tile.location.distance(to: to) < result!.location.distance(to: to) {
                trace("Closer: \(tile)")
                //                    trace("\(tile.owner) \(tile.active) \(tile.mineSpot)")
                return tile
            }
            return result
        })
    }
    
    static func closestSpawnForFighter(to: Location) -> Location? {
        debug("Finding spawn location for fighter")
        let filteredTiles = GameTile.tiles.filter{ tile in
            tile.owner != .me && tile.gameObject == nil && tile.canMoveTo
                && tile.adjacentTiles.filter{ adjacentTile in
                    return adjacentTile.owner == .me && adjacentTile.active
            }.count > 0
        }
        return closest(to: to, outOf: filteredTiles)?.location
    }
    
    static func closest(to: Location, ownershipMatters: Bool = true, ownedBy: Owner = .me, activeMatters: Bool = true, active: Bool = true, empty: Bool = true, mineSpot: Bool = false, random: Bool = false) -> Location? {
        debug("Finding closest to \(to)")
        var closestTile: GameTile? = nil
        //Get the list of all tiles and filter it based on parameters
        let filteredTiles = GameTile.tiles.filter { tile in
            return (!empty || tile.gameObject == nil) &&
            (!ownershipMatters || (ownedBy == tile.owner)) &&
            (!ownershipMatters || !activeMatters || (active == tile.active)) &&
            (!mineSpot || tile.mineSpot) &&
            tile.canMoveTo
        }
        if random {
            closestTile = filteredTiles.randomElement()
        }
        else {
            closestTile = closest(to: to, outOf: filteredTiles)
        }
        trace("Found \(String(describing: closestTile))")
        return closestTile?.location
    }

    static func == (lhs: GameTile, rhs: GameTile) -> Bool {
        return lhs.location == rhs.location
    }
}


func build(thing: Buildable) -> Bool {
    guard stupidSwift!.canBuild(buildableAny: thing, locationAny: thing.location) else { return false }
    gs.gold = gs.gold - thing.goldCost
    gs.income = gs.income - thing.incomeCost
    gs.appendCommand(todo: thing.buildCommand)
    GameTile.map[thing.location]?.gameObject = thing
    thing.postBuild()
    stupidSwift!.updateIncome()
    return true
}

class Building: CustomStringConvertible, Buildable, Equatable {
    
    let _owner: Owner
    var owner: Owner { get { return _owner } }
    let type: BuildingType
    let location: Location
    var stillAlive: Bool
    
    var goldCost: Int {
        get {
            var value = 0
            switch type {
            case .mine:
                value = 20 + (buildingList.filter { building in building.type == .mine }.count * 4)
            default: break
            }
            return value
        }
    }
    //Buildings don't cost income
    var incomeCost: Int {
        get {
            return 0
        }
    }
    var buildCommand: String {
        get {
            var command = "BUILD "
            switch type {
            case .mine: command = command + "MINE "
            default: break
            }
            return command + location.printString()
        }
    }
    
    var description: String {
        get {
            return "Owner:\(owner) Type:\(type) Location:\(location)"
        }
    }
    
    init(owner: Owner, type: BuildingType, at: Location) {
        self._owner = owner
        self.type = type
        self.location = at
        self.stillAlive = true
    }
    
    func postBuild() {
        buildingList.append(self)
    }
    
    static func testBuilding(type: BuildingType) -> Building {
        return Building(owner: .me, type: type, at: Location())
    }
    
    static func ==(lhs: Building, rhs: Building) -> Bool {
        return lhs.description == rhs.description
    }
}
var buildingList: [Building] = []
//Used to hold the initial mine locations, before the game board is parsed
var mineSpots: [Location] = []



enum FighterStrategy {
    case wait,
    attack,
    unclaimedMine,
    hunt,
    randomLocation
}

class Fighter: CustomStringConvertible, Buildable, Equatable {
    
    let _owner: Owner
    var owner: Owner { get { return _owner } }
    var unitId: Int?
    let level: Int
    var location: Location
    //Location only used for 1 turn, calculated by smartness
    var smartLocation: Location?
    var actionTaken: Bool
    var strategy: FighterStrategy?
    var stillAlive: Bool
    
    var goldCost: Int {
        get {
            var amount = 0
            switch level {
                case 1:
                    amount = 10
                case 2:
                    amount = 20
                case 3:
                    amount = 30
                default: break
            }
            return amount
        }
    }
    var incomeCost: Int {
        get {
            var amount = 0
            switch level {
                case 1:
                    amount = 1
                case 2:
                    amount = 4
                case 3:
                    amount = 20
                default: break
            }
            return amount
        }
    }
    var buildCommand: String {
        get {
            return "TRAIN " + String(level) + " " + location.printString()
        }
    }
    
    
    private var destinationList: [Location] = []
    var destination: Location? {
        get {
            return destinationList.first
        }
        set {
            var newDestinationList = [Location]()
            if newValue != nil {
                newDestinationList.append(newValue!)
            }
            destinationList = newDestinationList
        }
    }
    
    var moveCommand: String {
        get {
            return "MOVE " + String(unitId!) + " " + smartLocation!.printString()
        }
    }
    var ableToMove: Bool
    
    var description: String {
        get {
            return "Owner:\(owner) Id:\(unitId ?? 0) Level:\(level) Location:\(location)"
        }
    }
    
    init(owner: Owner, unitId: Int?, level: Int, location: Location) {
        self._owner = owner
        self.unitId = unitId;
        self.level = level
        self.location = location
        self.ableToMove = true
        self.actionTaken = false
        self.stillAlive = true
    }
    
    func postBuild() {
        allFighters.append(self)
    }
    
    //Returns true if the fighter was able to move
    func move() -> Bool {
        if let destination = destination {
            if destination != location {
                if setSmartDestination() {
                    gs.appendCommand(todo: moveCommand)
                    actionTaken = true
                    //Update Fighter's location
                    GameTile.map[location]?.gameObject = nil
                    location = smartLocation!
                    let tile = GameTile.map[smartLocation!]!
                    tile.gameObject = self
                    smartLocation = nil
                    return true
                }
            }
            else {
                destinationList.remove(at: 0)
                return move()
            }
        }
        else if let strategy = strategy {
            switch strategy {
                case .wait: break
//                case .hunt:
//                    enemyFighters.values.
                case .unclaimedMine:
                    if let location = GameTile.closest(to: location, ownershipMatters: true, ownedBy: .none, activeMatters: false, active: false, empty: true, mineSpot: true) {
                        destination = location
                        trace("Moving to mine at \(destination!)")
                        return move()
                }
                case .randomLocation:
                    if let location = GameTile.closest(to: location, ownershipMatters: true, ownedBy: .none, activeMatters: false, active: false, empty: false, mineSpot: false, random: true) {
                        destination = location
                        trace("Moving #\(unitId!) to random location \(destination!)")
                }
                default: break
            }
            
        }
        return false
    }
    
    func next(to: Location?) -> Fighter {
        if let to = to {
            destinationList.append(to)
        }
        return self
    }
    
    //Will prioritize killing enemies and taking territory not owned by me.
    //Returns a location that is 1 unit away or nil.
    func setSmartDestination() -> Bool {
        guard destination != nil else { return false }

        trace("Finding Smart Destination for \(self)")
        trace("At \(location)")
        trace("Moving To \(destination!)")
        
        //Loop through adjacent tiles, figure out which one is closer / best to move to
        if let smartTileRank: (GameTile?, Int) = GameTile.map[location]!.adjacentTiles.reduce((nil, 0), { result, tile in
            let newRank = getMoveRanking(to: tile)
            return newRank > result.1 ? (tile, newRank) : result
        }) {
            trace("Found Smart Location: \(String(describing: smartTileRank.0)), Rank \(smartTileRank.1)")
            //If we returned a smart location ranking > 0, lets go!
            if smartTileRank.1 > 0 {
                smartLocation = smartTileRank.0?.location
            }
        }
        
        return smartLocation != nil
    }
    
    //Returns a number ranking how excited we are to move to this tile!
    //If a result of 0 or less is returned, we can't move there
    func getMoveRanking(to: GameTile) -> Int {
        let MIN_RANKING = -100
        let OWNED = 1
        let UNOWNED = 4
        let ENEEMY_OWNED = 5
        let KILL_ENEMY = 7
        let KILL_BUILDING = 10
        //Each point closer is worth this many points
        let DISTANCE_MULTIPLIER = 3
        var ranking: Int = MIN_RANKING
        if !to.canMoveTo { ranking = MIN_RANKING }
        else if let object = to.gameObject {
            if object.owner == .me { ranking = MIN_RANKING }
            else {
                if let enemyFighter = object as? Fighter {
                    //If we can kill the other fighter, go for it
                    ranking = ((level == 3) || (level > enemyFighter.level)) ? KILL_ENEMY : MIN_RANKING
                }
                else if object is Building {
                    ranking = KILL_BUILDING
                }
            }
        }
        else {
            switch to.owner {
                case .enemy: ranking = ENEEMY_OWNED
                case .me: ranking = OWNED
                case .none: ranking = UNOWNED
            }
        }
        let destinationHeatMap = GameTile.map[destination!]!.heatMap
        //Add the difference between how far we are and how far the new location is. Increase in location by 1 is worth 3 points.
        if let destinationDistance = destinationHeatMap[to], let myDistance = destinationHeatMap[GameTile.map[location]!] {
            ranking = ranking + (myDistance - destinationDistance) * DISTANCE_MULTIPLIER
        }
        
        return ranking
    }
    
    func canKill(other: Fighter) -> Bool {
        return (level == 3 || level > other.level) && owner != other.owner
    }
    
    static func createFighter(level: Int = 1, at: Location? = nil) -> Fighter? {
        var at = at
        if at == nil {
            let baseTile = GameTile.map[gs.baseLocation]!
            at = baseTile.above?.location ?? baseTile.below?.location
            if GameTile.map[at!]!.gameObject != nil {
                at = baseTile.left?.location ?? baseTile.right?.location
            }
        }
        if at != nil {
            let fighter = Fighter(owner: .me, unitId: nil, level: level, location: at!)
            if build(thing: fighter) {
                return fighter
            }
        }
        return nil
    }
    
    //For convienence when checking if I can build one of these guys
    static func testFighter(level: Int) -> Fighter {
        let level = max(1, min(level, 3))
        return Fighter(owner: .me, unitId: nil, level: level, location: Location())
    }
    
    static func ==(lhs: Fighter, rhs: Fighter) -> Bool {
        return lhs.unitId == rhs.unitId && rhs.location == rhs.location
    }
}
var allFighters: [Fighter] = []
var myFighters: [Fighter] {
    get {
        return allFighters.filter {
            fighter in fighter.owner == .me
        }
    }
}
var enemyFighters: [Fighter] {
    get {
        return allFighters.filter {
            fighter in fighter.owner == Owner.enemy
        }
    }
}

func moveFighters() {
    for fighter in myFighters {
        if !fighter.actionTaken {
            fighter.move()
        }
    }
}

func createEarlyFighter(number: Int) {
    var spawnLocation: GameTile?
    switch number {
        case 1:
            spawnLocation = GameTile.map[gs.baseLocation]
            spawnLocation = spawnLocation!.left ?? spawnLocation!.right
        case 2:
            spawnLocation = GameTile.map[gs.baseLocation]
            spawnLocation = spawnLocation!.above ?? spawnLocation!.below
        case 3:
            spawnLocation = GameTile.map[gs.baseLocation]
            spawnLocation = spawnLocation!.location.topLeft ? spawnLocation!.below!.below : spawnLocation!.above!.above
        default: break
    }
    let fighter = Fighter.createFighter(at: spawnLocation!.location)
    if let fighter = fighter {
        fighter.strategy = FighterStrategy.randomLocation
        let direction = gs.baseLocation.topLeft ? Direction.right : Direction.left
        let nextLocation = spawnLocation!.allTheWay(direction: direction, movableOnly: true).location
        trace("Next - \(nextLocation)")
        fighter.next(to: nextLocation)
    }
}

//See if we can create level 1 fighters in a line to cut off the enemies troops
func checkForCutoffKill() {
    stupidSwift!.updateActiveCells()
    //Find all tiles I own that are next to an empty enemy owned tile
    //See if we can shoot across that direction
    //If we can, do we have money for it, how many people / how much income would it destroy
    let filteredTiles = GameTile.tiles.filter{ tile in
        tile.owner == .me && tile.active && tile.adjacentTiles.filter{ adjacentTile in
            adjacentTile.owner == .enemy && adjacentTile.active && adjacentTile.gameObject == nil
        }.count > 0
    }
    for tile in filteredTiles {
        
    }
}

func checkAndCreateFighter() {
    
    var fighterBuilt = true
    while fighterBuilt && stupidSwift!.haveMoneyToBuild(buildableAny: Fighter.testFighter(level: 1)) {
        let level1Fighters = (myFighters.filter { fighter in fighter.level == 1 }).count
        if level1Fighters < 3 {
            debug("Building Level 1 Fighter #\(level1Fighters+1)")
            createEarlyFighter(number: level1Fighters + 1)
        }
        else if level1Fighters < 8 {
            debug("Building Level 1 Fighter")
            let fighter = Fighter.createFighter(at: GameTile.closestSpawnForFighter(to: gs.enemyBaseLocation))
            if let fighter = fighter {
                fighter.strategy = FighterStrategy.randomLocation
            }
            fighterBuilt = fighter != nil
        }
        else {
            fighterBuilt = false
        }
    }
    checkForCutoffKill()
    
    //Find an enemy that is adjacent to me, gg buddy
    if let unluckyEnemy = (enemyFighters.filter { fighter in
        ((GameTile.map[fighter.location]?.adjacentTiles.filter {
            tile in tile.owner == .me && tile.active == true
            })?.count)! > 0
    }).first {
        let level = unluckyEnemy.level + 1
        debug("Building Level \(level) Fighter to kill \(unluckyEnemy)")
        if stupidSwift!.haveMoneyToBuild(buildableAny: Fighter.testFighter(level: level)) {
            let fighter = Fighter.createFighter(level: level, at: unluckyEnemy.location)
            fighter?.strategy = .hunt
            fighter?.next(to: gs.enemyBaseLocation)
        }
    }

}

func checkAndCreateMine() {
    trace("Checking to create mines")
    if stupidSwift!.haveMoneyToBuild(buildableAny: Building.testBuilding(type: .mine)),
    let mineLocation = GameTile.closest(to: gs.baseLocation, ownershipMatters: true, ownedBy: .me, activeMatters: true, active: true, empty: true, mineSpot: true) {
        debug("Building Mine at \(mineLocation)")
        let mine = Building(owner: .me, type: .mine, at: mineLocation)
        build(thing: mine)
    }
}

func associateBuildablesWithTiles() {
    allFighters.removeAll { !$0.stillAlive }
    buildingList.removeAll{ !$0.stillAlive }
    
    //Associate game tile with its fighter or building
    allFighters.forEach{ GameTile.map[$0.location]!.gameObject = $0 }
    buildingList.forEach{ GameTile.map[$0.location]!.gameObject = $0 }
}

func parseMines() {
    debug("Parsing Mines")
    let numberMineSpots = Int(getLine())!
    if numberMineSpots > 0 {
        for _ in 0...(numberMineSpots-1) {
            let line = readLine()
            let inputs = (line!).split{$0 == " "}.map(String.init)
            let location = Location(x: Int(inputs[0])!, y: Int(inputs[1])!)
            mineSpots.append(location)
        }
    }
}

func parseGameState() {
    debug("Parsing Game State")
    gs.gold = Int(getLine())!
    gs.income = Int(getLine())!
    gs.enemyGold = Int(getLine())!
    gs.enemyIncome = Int(getLine())!
}


func parseGameBoard() {
    let firstTurn = gs.turn == 1
    debug("Parsing Game Board")
    var tileAbove: GameTile? = nil
    for y in 0...11 {
        let line = getLine()
        var tileToTheLeft: GameTile? = nil
        for (x, character) in line.enumerated() {
            let location = Location(x: x, y: y)
            if firstTurn {
                let tile = GameTile(symbol: character, at: location)
                tile.mineSpot = mineSpots.contains(tile.location)
                if tileToTheLeft != nil {
                    tileToTheLeft?.right = tile
                    tile.left = tileToTheLeft
                }
                if tileAbove != nil {
                    tileAbove?.below = tile
                    tile.above = tileAbove
                    tileAbove = tileAbove?.right
                }
                GameTile.map[location] = tile
                tileToTheLeft = tile
            }
            else {
                GameTile.map[location]!.symbol = character
            }
        }
        
        //Set tileAbove to the left most tile in preparation for the next row
        tileAbove = tileToTheLeft?.allTheWay(direction: .left)
        tileToTheLeft = nil
    }
    
    if gs.turn == 1 {
        GameTile.tiles = [GameTile](GameTile.map.values)
        GameTile.tiles.forEach({ tile in tile.createHeatMap() })
    }
}

func parseBuildings() {
    debug("Parsing Buildings")
    let buildingCount = Int(getLine())!
    if buildingCount > 0 {
        for _ in 0...(buildingCount-1) {
            let inputs = (getLine()).split{$0 == " "}.map(String.init)
            let owner = Owner(rawValue: Int(inputs[0])!)!
            let buildingType = BuildingType(rawValue: Int(inputs[1])!)!
            let location = Location(x: Int(inputs[2])!, y: Int(inputs[3])!)
            
            let gameTile = GameTile.map[location]!
            if (gameTile.gameObject as? Building)?.type == buildingType { }
            else {
                //Building doesn't exist, add it to the list of buildings
                let building = Building(owner: owner, type: buildingType, at: location)
                buildingList.append(building)
                
                if BuildingType.headquarters == building.type {
                    if Owner.me == building.owner {
                        gs.baseLocation = building.location
                    } else {
                        gs.enemyBaseLocation = building.location
                    }
                }
            }
        }
    }
}

func parseUnits() {
    debug("Parsing Units")
    
    //Create a lookup by unitId
    let fighterMap = allFighters.reduce([Int: Fighter]()) { dictionary, fighter in
        var dictionary = dictionary
        if let unitId = fighter.unitId {
            dictionary[unitId] = fighter
        }
        return dictionary
    }
    
    let unitCount = Int(getLine())!
    if unitCount > 0 {
        for _ in 0...(unitCount-1) {
            let inputs = (getLine()).split{$0 == " "}.map(String.init)
            let owner = Owner(rawValue: Int(inputs[0])!)!
            let unitId = Int(inputs[1])!
            let level = Int(inputs[2])!
            let location = Location(x: Int(inputs[3])!, y: Int(inputs[4])!)
            
            var fighter: Fighter
            
            //Update Fighter with new coordinates
            if let existingFighter = fighterMap[unitId] {
                fighter = existingFighter
            }
            //New fighter, welcome!
            else if let newFighter = (allFighters.filter { fighter in fighter.location == location && fighter.owner == Owner.me }).first {
                fighter = newFighter
                    fighter.unitId = unitId
            }
            //New enemy fighter (boo)
            else {
                fighter = Fighter(owner: owner, unitId: unitId, level: level, location: location)
                allFighters.append(fighter)
            }
            
            //Give fighter new location
            fighter.location = location
            fighter.actionTaken = false
            fighter.stillAlive = true
        }
    }
}


class ParserTrickerImpl: ParserTricker {
    
    func haveMoneyToBuild(buildableAny: Any) -> Bool {
        guard let buildable = buildableAny as? Buildable else { return false }
        let gotThaMoney = buildable.goldCost <= gs.gold && buildable.incomeCost <= gs.income
        return gotThaMoney
    }
    
    //Returns true if we can build this fighter or building on the location
    func canBuild(buildableAny: Any, locationAny: Any) -> Bool {
        trace("Can build \(buildableAny) at \(locationAny)?")
        guard let buildable = buildableAny as? Buildable,
            let location = locationAny as? Location else { return false }
        let fighter = buildable as? Fighter
        let building = buildable as? Building
        let tile = GameTile.map[location]!
        var canBuildHere = true
        //Check the object that is already on that square, can we smash it down?
        let existingGameObject = tile.gameObject
        if fighter != nil {
            //If building a fighter, must be adjacent to an active square I own
            let hasAdjacentOwnedTile = tile.adjacentTiles.reduce(false, {
                result, AdjTile in
                let active = AdjTile.active && AdjTile.owner == .me
                return result || active
            })
            trace("Has adjacent owned tile - \(hasAdjacentOwnedTile)")
            canBuildHere = canBuildHere && hasAdjacentOwnedTile
        }
        else if building != nil {
            canBuildHere = canBuildHere && existingGameObject == nil && tile.canMoveTo && tile.owner == .me && tile.active
            if building!.type == .mine {
                canBuildHere = canBuildHere && tile.mineSpot
            }
        }
        
        switch existingGameObject {
            //If the existing object is a fighter...
            case let existingFighter as Fighter:
                if fighter != nil {
                    //If we are trying to build a fighter here
                    canBuildHere = canBuildHere && fighter!.canKill(other: existingFighter)
                }
                else if building != nil {
                    //If only we could squish fighters with a building
                    canBuildHere = false
                }
            case let existingBuilding as Building:
                //Can't build a building on an enemy building, although we shouldn't get here because we should only be building on our property
                if building != nil { canBuildHere = false }
            default:
                break
        }
        return canBuildHere && haveMoneyToBuild(buildableAny: buildable)
    }
    
    private func calculateIncome(owner: Owner) -> Int {
        //Get fighters owned by owner and add up their income costs
        let fighterIncome = allFighters.filter{ $0.owner == owner }.reduce(0, { result, fighter in
            return result - fighter.incomeCost
        })
        let tileIncome = GameTile.tiles.reduce(0, { result, tile in
            return result + ((tile.owner == owner && tile.active) ? 1 : 0)
        })
        let mineIncome = buildingList.filter({ building in building.type == .mine && building.owner == owner }).count * 4
        
        return fighterIncome + tileIncome + mineIncome
    }
    
    func updateIncome() {
        gs.income = calculateIncome(owner: .me)
        gs.enemyIncome = calculateIncome(owner: .enemy)
    }
    
    
    //Update the tile map to correctly represent the game tiles (as this isn't done after each move)
    func updateActiveCells() {
        //My tiles should be correct, only need to update enemy tiles
        //Set all enemy tiles to inactive, then go out from their headquarters and set attached tiles to active
        let tiles = GameTile.tiles
        for tile in tiles {
            if tile.owner == .enemy {
                tile.active = false
            }
        }
        
        var queue = Queue<GameTile>()
        queue.enqueue(element: GameTile.map[gs.enemyBaseLocation]!)
        while let tile = queue.dequeue() {
            tile.active = true
            queue.enqueueAll(elements: tile.adjacentTiles.filter{ $0.owner == .enemy && !$0.active })
        }
        
        let tilesWithDeadFighters = tiles.filter{ tile in
            tile.owner == .enemy && !tile.active && (tile.gameObject as? Fighter) != nil
        }
        for tile in tilesWithDeadFighters {
            let fighter = tile.gameObject as! Fighter
            allFighters.removeAll { $0 == fighter }
            tile.gameObject = nil
        }
    }
    
    
}

func startTurn() {
    gs.turn = gs.turn + 1
    debug("Turn: " + String(gs.turn))
    GameTile.tiles.forEach({ tile in tile.gameObject = nil })
    allFighters.forEach{ $0.stillAlive = false }
    buildingList.forEach{ $0.stillAlive = false }
}

//Silly swift, have to create object down here
stupidSwift = ParserTrickerImpl()

// game loop
while true {
    
    startTurn()
    
    if gs.turn == 1 {
        parseMines()
    }
    parseGameState()
    parseGameBoard()
    parseBuildings()
    parseUnits()
    
    associateBuildablesWithTiles()
    
    moveFighters()
    moveFighters()
    moveFighters()
    checkAndCreateFighter()
    checkAndCreateMine()
    print(gs.turnCommand)
    debug("End Turn Gold:\(gs.gold), Income:\(gs.income)")
}
