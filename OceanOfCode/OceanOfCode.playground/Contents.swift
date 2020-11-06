    //import Glibc
    import Foundation

    public struct StderrOutputStream: TextOutputStream {
        public mutating func write(_ string: String) { fputs(string, stderr) }
    }
    public var errStream = StderrOutputStream()

    // Write an action using print("message...")
    // To debug: print("Debug messages...", to: &errStream)
    func debug(_ message: Any) {
        print(message, to: &errStream)
    }
    
    func assertFailed(_ message: Any) {
        debug("SEVERE ERROR!!!")
        debug(message)
        exit(1)
    }

    class CodeTimer {
        
        private var startTime: DispatchTime?
        private var lastTime: DispatchTime?
        private let taskName: String
        
        init(_ taskName: String = "Anonymous") {
            self.taskName = taskName
        }
        
        func start() -> CodeTimer {
            debug("Event [\(taskName)] started")
            startTime = DispatchTime.now()
            lastTime = startTime
            return self
        }
        
        func stop() {
            let miliseconds = (DispatchTime.now().uptimeNanoseconds - startTime!.uptimeNanoseconds) / 1000000
            debug("Event [\(taskName)] complete in \(String(miliseconds))ms")
        }
        
        func elapsed(_ update: String = "") {
            let miliseconds = (DispatchTime.now().uptimeNanoseconds - lastTime!.uptimeNanoseconds) / 1000000
            debug("Event [\(taskName)] updated: \(update) after \(String(miliseconds))ms")
            lastTime = DispatchTime.now()
        }
    }


    // ------------------ INIT ------------------ \\

    enum MyError: Error {
        case runtimeError(String)
    }


    class GameState: CustomStringConvertible {
        let height: Int
        let width: Int
        let myId: Int
        var turnNumber: Int
        var myLocation: Location
        var myLife = 6
        var opponentLife = 6
        var torpedoCooldown = 0
        var sonarCooldown = 0
        var silenceCooldown = 0
        var mineCooldown = 0
        var myOrders: [Order] = []
        var opponentOrders: [Order] = []
        
        
        init(w: Int, h: Int, myId: Int, start: Location) {
            width = w
            height = h
            self.myId = myId
            myLocation = start
            turnNumber = 0
        }
        
        init(gs: GameState) {
            width = gs.width
            height = gs.height
            myId = gs.myId
            turnNumber = gs.turnNumber
            myLocation = gs.myLocation
            myLife = gs.myLife
            opponentLife = gs.opponentLife
            torpedoCooldown = gs.torpedoCooldown
            sonarCooldown = gs.sonarCooldown
            silenceCooldown = gs.silenceCooldown
            mineCooldown = gs.mineCooldown
        }
        
        public var description: String {
            return """
            Turn = \(turnNumber)
            """
        }
    }

    //(x, y) coordinate system, 0,0 is top left, 15,15 is bottom right
    struct Location: Hashable, CustomStringConvertible {
        
        static let some = Location(0, 0)
        
        let x: Int
        let y: Int
        
        public var description: String {
            get {
                return "\(x) \(y)"
            }
        }
        
        init(_ x: Int, _ y: Int) {
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
        
        func isAdjacent(to: Location, diagonal: Bool = false) -> Bool {
            let xDif = abs(x - to.x)
            let yDif = abs(y - to.y)
            return xDif <= 1 && yDif <= 1 && (!diagonal || (xDif + yDif) <= 2) && (xDif + yDif) != 0
        }
        
        static func ==(lhs: Location, rhs: Location) -> Bool {
            return lhs.x == rhs.x && lhs.y == rhs.y
        }
    }

    enum Direction: CaseIterable {
        case up, down, left, right
        
        func opposite() -> Direction {
            switch self {
            case .up: return .down
            case .down: return .up
            case .left: return .right
            case .right: return .left
            }
        }
    }

    extension Direction {
        
        static func by(symbol: String) -> Direction {
            switch symbol {
            case "N": return .up
            case "S": return .down
            case "W": return .left
            case "E": return .right
            default: assertFailed("Where are we going??"); return .down;
            }
        }
        func symbol() -> String {
            switch self {
            case .up: return "N"
            case .down: return "S"
            case .left: return "W"
            case .right: return "E"
            }
        }
    }

    //Define in extension when we can move to a tile
    protocol Movable {
        var canMoveTo: Bool {get}
    }

    class GameTile: CustomStringConvertible, Hashable, Movable {
        
        static var map: [Location : GameTile] = [:]
        static var tiles: [GameTile] = []
        
        static func at(_ x: Int, _ y: Int) -> GameTile? {
            return map[Location(x, y)]
        }

        var active: Bool = true
        let location: Location
        
        var above: GameTile? = nil
        var below: GameTile? = nil
        var left: GameTile? = nil
        var right: GameTile? = nil
        
        var surroundingTilesIncludingDiagonals: [GameTile] {
            get {
                var array: [GameTile] = []
                for x in (location.x - 1)...(location.x + 1) {
                    for y in (location.y - 1)...(location.y + 1) {
                        if let tile = GameTile.map[Location(x,y)], tile != self {
                            array.append(tile)
                        }
                    }
                }
                return array
            }
        }
        
        init(at: Location) {
            self.location = at
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
        
        func adjacentTiles(includeSelf: Bool = false, includeDiagonal: Bool = false) -> [GameTile] {
            var array: [GameTile] = []
            for xVal in location.x-1...location.x+1 {
                for yVal in location.y-1...location.y+1 {
                    let tileLocation = Location(xVal, yVal)
                    let distance = tileLocation.distance(to: location)
                    if  (includeSelf || distance > 0),
                        distance < (includeDiagonal ? 2 : 1),
                        let tile = GameTile.map[Location(xVal, yVal)]
                    {
                        array.append(tile)
                    }
                }
            }
            return array
        
        }
        
        func allTheWay(direction: Direction, movableOnly: Bool = false) -> GameTile {
            var goodTile = self
            while let nextTile = goodTile.next(direction: direction), !movableOnly || nextTile.canMoveTo {
                goodTile = nextTile
            }
            return goodTile
        }
        
        func tilesInDirection(direction: Direction, movableOnly: Bool = false, max: Int? = nil) -> [GameTile] {
            var goodTile = self
            var tileList: [GameTile] = []
            while let nextTile = goodTile.next(direction: direction), !movableOnly || nextTile.canMoveTo, max == nil || tileList.count < max! {
                tileList.append(nextTile)
                goodTile = nextTile
            }
            return tileList
        }
        
        func distance(to: GameTile) -> Int {
            return location.distance(to: to.location)
        }
        
        public var description: String {
            get {
                return "\(location)"
            }
        }
        
        func hash(into hasher: inout Hasher) {
            hasher.combine(location)
        }
        
        /** Static Methods **/

        static func closest(to: Location, outOf: [GameTile]) -> GameTile? {
            return outOf.reduce(nil, { result, tile in
                guard result != nil else { return tile }
                if tile.location.distance(to: to) < result!.location.distance(to: to) {
                    return tile
                }
                return result
            })
        }
        
        static func createMap(width: Int, height: Int) {
            
            for x in 0..<width {
                for y in 0..<height {
                    let tile = GameTile(at: Location(x, y))
                    tiles.append(tile)
                    map[tile.location] = tile
                }
            }
            
            //Link up tiles
            for tile in tiles {
                if let tileBelow = map[Location(tile.location.x, tile.location.y + 1)] {
                    tile.below = tileBelow
                    tileBelow.above = tile
                }
                if let tileRight = map[Location(tile.location.x + 1, tile.location.y)] {
                    tile.right = tileRight
                    tileRight.left = tile
                }
            }
        }
        
        static func == (lhs: GameTile, rhs: GameTile) -> Bool {
            return lhs.location == rhs.location
        }
        
    }

    //For game specific logic
    extension GameTile {
        private static var _water = [String:Bool]()
        private static var _opponentPossibility = [String:Bool]()
        
        var canMoveTo: Bool {
            return isWater
        }
        
        var isWater: Bool {
            get {
                let tmpAddress = String(format: "%p", unsafeBitCast(self, to: Int.self))
                return GameTile._water[tmpAddress]!
            }
            set(newValue) {
                let tmpAddress = String(format: "%p", unsafeBitCast(self, to: Int.self))
                GameTile._water[tmpAddress] = newValue
            }
        }
        
        var isOpponentPossibility: Bool {
            get {
                let tmpAddress = String(format: "%p", unsafeBitCast(self, to: Int.self))
                return GameTile._opponentPossibility[tmpAddress]!
            }
            set(newValue) {
                let tmpAddress = String(format: "%p", unsafeBitCast(self, to: Int.self))
                GameTile._opponentPossibility[tmpAddress] = newValue && canMoveTo
            }
        }
        
        func quadrant() -> Int {
            return (((location.y) / 5) * 3 + ((location.x) / 5)) + 1
        }
        
        static func getMapState() -> String {
            var tile: GameTile? = GameTile.tiles.first!.allTheWay(direction: .up).allTheWay(direction: .left)
            var printString = ""
            while tile != nil {
                let currentTile = tile!
                printString = printString + (currentTile.isWater ? "W" : "L")
                tile = currentTile.right
                if tile == nil {
                    tile = currentTile.below
                    if tile != nil {
                        tile = tile?.allTheWay(direction: .left)
                        printString = printString + "\n"
                    }
                }
            }
            return printString
        }
        
        static func possibleOpponentTiles() -> [GameTile] {
            return GameTile.tiles.filter{ $0.isOpponentPossibility }
        }
    }

        
    enum Order: Equatable {
        case move(Direction, String?),       //Direction, Charge
        torpedo(Location),                  //Fire location
        surface(Int?),                       //Quadrant
        silence(Direction?, Int?),            //Move direction, number of moves
        sonar(Int),                          //Quadrant
        mine(Direction?),                    //Mine placement direction
        trigger(Location),                   //Which mine to trigger
        message(String)
        
        static func getOrder(_ orderString: String) -> Order {
            let parts = orderString.split(separator: " ")
            let order = parts[0]
            if order == "MOVE" {
                return .move(Direction.by(symbol: parts[1].description), nil)
            } else if order == "TORPEDO" {
                return .torpedo(Location(Int(String(parts[1]))!, Int(String(parts[2]))!))
            } else if order == "SURFACE" {
                var returnQuadrent: Int? = nil
                if parts.count > 1, let quadrent = Int(String(parts[1])) {
                    returnQuadrent = quadrent
                }
                return .surface(returnQuadrent)
            } else if order == "SILENCE" {
                if parts.count == 3 {
                    return .silence(Direction.by(symbol: String(parts[1])), Int(String(parts[2]))!)
                }
                return .silence(nil, nil)
            } else if order == "SONAR" {
                return .sonar(Int(String(parts[1]))!)
            } else if order == "MINE" {
                return .mine(nil)
            } else if order == "TRIGGER" {
                return .trigger(Location(Int(String(parts[1]))!, Int(String(parts[2]))!))
            } else if order == "MSG" {
                return .message(String(parts[1]))
            }
            
            assertFailed("Unable to parse \(order)")
            return .message("HMM")
        }
        
        func command() -> String {
            switch self {
            case .move(let direction, let charge): return "MOVE \(direction.symbol()) \(charge ?? "")"
            case .torpedo(let location): return "TORPEDO \(location.x) \(location.y)"
            case .surface: return "SURFACE"
            case .silence(let direction, let moves): return "SILENCE \(direction?.symbol() ?? "") \(moves != nil ? String(moves!) : "")"
            case .sonar(let quadrant): return "SONAR \(quadrant)"
            case .mine(let direction): return "MINE \(direction?.symbol() ?? "")"
            case .trigger(let location): return "TRIGGER \(location.x) \(location.y)"
            case .message(let message): return "MSG \(message)"
            }
        }
        
        public static func ==(lhs: Order, rhs: Order) -> Bool {
            switch (lhs, rhs) {
            case (.move, .move),
                 (.torpedo, .torpedo),
                 (.surface, .surface),
                 (.silence, .silence),
                 (.sonar, .sonar),
                 (.mine, .mine),
                 (.trigger, .trigger),
                 (.message, .message):
              return true
            default:
              return false
            }
        }
    }




    var gameState: GameState! = nil
    var allGameStates: [GameState] = []
    var lastTurnGameState: GameState? {
        return allGameStates.last
    }




    func handleOpponentOrders(_ order: String) {
        gameState.opponentOrders = order.split(separator: "|").map(String.init).map(Order.getOrder(_:))
        for order in gameState.opponentOrders {
            switch order {
            case .move(let direction, _):
                let otherDirection = direction.opposite()
                var tilePossibilityMap: [GameTile:Bool] = [:]
                GameTile.tiles.forEach {
                    tile in
                    //If opponent is moving N, for each tile, check the tile? to the sound. If it was a possiblity and it is reachable, then this tile is a possiblity.
                    tilePossibilityMap[tile] = (tile.next(direction: otherDirection)?.isOpponentPossibility ?? false)
                }
                tilePossibilityMap.forEach{ (tile, possibility) in
                    tile.isOpponentPossibility = possibility
                }
            case .surface(let quadrant):
                let opponentQuadrent = quadrant!
                debug("Surfacing q\(opponentQuadrent)")
                GameTile.possibleOpponentTiles().forEach {
                    tile in
                    tile.isOpponentPossibility = tile.isOpponentPossibility && tile.quadrant() == opponentQuadrent
                }
            case .torpedo(let fireLocation):
                GameTile.tiles.forEach{ tile in
                    if (tile.location.distance(to: fireLocation) > 4) {
                        tile.isOpponentPossibility = false
                    }
                }
            case .silence:
                GameTile.tiles.filter{ $0.isOpponentPossibility }.forEach {
                    for direction in Direction.allCases {
                        $0.tilesInDirection(direction: direction, movableOnly: true, max: 4).forEach{ $0.isOpponentPossibility = true }
                    }
                }
            case .sonar(_), .mine(_), .trigger(_), .message(_):
                break
            }
        }
    }

    func checkSonarResult(result: String, quadrant: Int) {
        guard result != "NA" else { return }
        debug("Checking sonar result \(result) \(quadrant)")
        //If result was "No", set all possible opponent tiles in that quadrant to no longer be a possibility, "Yes" is opposite
        let tilesToRemove = GameTile.possibleOpponentTiles().filter{ result == "N" ? $0.quadrant() == quadrant : $0.quadrant() != quadrant }
        debug("removing \(tilesToRemove) tiles from consideration")
        tilesToRemove.forEach{ $0.isOpponentPossibility = false }
    }

    //Check if the opponent's health has changed, and compare that with torpedoes either of us fired or mines that went off.
    func updateEnemyLocationFromHealthChange() {
        guard let lastTurnGameState = lastTurnGameState else { return }
        
        var explosionTiles: [GameTile] = (lastTurnGameState.myOrders + lastTurnGameState.opponentOrders)
            .filter{ return ($0 == .torpedo(Location.some) || ($0 == .trigger(Location.some))) }
            .map{
                switch $0 {
                case .torpedo(let location), .trigger(let location):
                    return GameTile.map[location]!
                default:
                    assertFailed("Shouldn't be other order type"); return GameTile.tiles.first!;
                }
        }
        guard explosionTiles.count > 0 else { return }
        
        //If the opponent surfaced last turn, take that into account
        let damageFromExplosions = lastTurnGameState.opponentLife - gameState.opponentLife + (lastTurnGameState.opponentOrders.contains(.surface(nil)) ? 1 : 0)
        debug("Enemy took \(damageFromExplosions) from explosions")
        let tilesAroundExplosions = Set(explosionTiles.flatMap{ $0.adjacentTiles(includeSelf: true, includeDiagonal: true) }.filter{ $0.isOpponentPossibility })
        if damageFromExplosions == 0 {
            tilesAroundExplosions.forEach{ $0.isOpponentPossibility = false }
        } else {
            GameTile.tiles.filter{ !tilesAroundExplosions.contains($0) }.forEach{ $0.isOpponentPossibility = false }
            for tile in tilesAroundExplosions {
                let damageToTile = explosionTiles.reduce(0, {(damage, explosionTile) in
                    if tile == explosionTile {
                        return damage + 2
                    }
                    return damage + (tile.location.isAdjacent(to: explosionTile.location, diagonal: true) ? 1 : 0)
                })
                debug("tile \(tile) took \(damageToTile)")
                if damageToTile != damageFromExplosions {
                    tile.isOpponentPossibility = false
                }
            }
        }
        
//        let tilesSurroundingExplosions =
//        switch damageTakeFromTorpedo {
//        case 0:
//            debug("No torpedo hit, setting \(surroundingTiles) to not possible")
//            surroundingTiles.forEach{ $0.isOpponentPossibility = false }
//            torpedoTile.isOpponentPossibility = false
//        case 1:
//            debug("Torpedo hit for 1, setting \(surroundingTiles) are still possible")
//            GameTile.tiles.forEach{
//                if !surroundingTiles.contains($0) { $0.isOpponentPossibility = false }
//            }
//        case 2:
//            debug("Torpedo hit for 2, got em at \(torpedoTile)")
//            GameTile.tiles.forEach{ $0.isOpponentPossibility = false }
//            torpedoTile.isOpponentPossibility = true
//        default: debug("--- ERROR DAMAGE TAKE \(damageTakeFromTorpedo) ---")
//        }
    }


    var moveDirection: Direction = .down
    var nextDirection: Direction? = nil
    var overallDirection: Direction = .right
    var needToSurface: Bool = false

    func updateMove() {
        if let nextDir = nextDirection {
            moveDirection = nextDir
            nextDirection = nil
        }
        let tile: GameTile = GameTile.map[gameState.myLocation]!
        
        if !(tile.next(direction: moveDirection)?.isWater ?? false) {
            nextDirection = moveDirection == Direction.up ? Direction.down : Direction.up
            moveDirection = overallDirection
            if !(tile.next(direction: moveDirection)?.isWater ?? false) {
                overallDirection = overallDirection == .right ? .left : .right
                needToSurface = true
                //aribrary
                moveDirection = Direction.up
                updateMove()
            }
            
        }
    }

    
    func fireTorpedo() -> Order? {
        guard gameState.torpedoCooldown == 0 else { return nil }
        let opponentPossibleTiles = GameTile.possibleOpponentTiles()
        //Don't fire if there are too many possibilities
        guard opponentPossibleTiles.count <= 10 else { return nil }
        
        let torpedoTiles = GameTile.possibleOpponentTiles().filter {
            let distance = $0.location.distance(to: gameState.myLocation)
            return distance <= 4 && distance >= 2
        }
        
       
        
        if let torpedoLocation = torpedoTiles.first?.location {
            return .torpedo(torpedoLocation)
        }
        return nil
    }
    
    var lastSonarQuadrant = 0
    func useSonar() -> Order? {
        guard gameState.sonarCooldown == 0 else { return nil }
        var quadrantCountMap: [Int : Int] = [:]
        GameTile.possibleOpponentTiles().forEach {
            tile in
            quadrantCountMap[tile.quadrant()] = ((quadrantCountMap[tile.quadrant()] ?? 0) + 1)
        }
        
        var enemyQuadrantWithCount = [(Int, Int)]()
        for (key, value) in quadrantCountMap {
            enemyQuadrantWithCount.append((key, value))
        }
        enemyQuadrantWithCount.sort{ val1, val2 in val1.0 > val2.0 }
        debug(enemyQuadrantWithCount.description)
        
        var bestQuadrant: Int? = nil
        var highestCount: Int? = nil
        for (_, (quadrant, count)) in quadrantCountMap.enumerated() {
            if count > (highestCount ?? 0) {
                bestQuadrant = quadrant
                highestCount = count
            }
        }
        
        //Looks complicated ahead. We have a list of quadrents along with their counts, ordered by count descending.
        //ex [ (4, 12), (7, 5), (9, 1) ]
        //Scan only if we can eliminate at least 1/3 of the quads (example above, scan because we can check if they are in q4 - 12, eliminate 6 possibilities)
        guard enemyQuadrantWithCount.count > 1 else { return nil }
        let possibleSquareCount = enemyQuadrantWithCount.reduce(0, {result, quadWithCount in
            return result + quadWithCount.1
        })
        let possibleSquareCountBesidesHighest = possibleSquareCount - enemyQuadrantWithCount[0].0
        if let bestQuadrant = bestQuadrant, possibleSquareCountBesidesHighest >= (enemyQuadrantWithCount[0].0 / 2) {
            debug("Scanning q\(bestQuadrant) with \(highestCount!) possibilities")
            lastSonarQuadrant = bestQuadrant
            return .sonar(bestQuadrant)
        }
        return nil
    }
    
    func useSilence() -> Order? {
        guard gameState.silenceCooldown == 0 else { return nil }
        guard let tileAfterMove = GameTile.map[gameState.myLocation]?.next(direction: moveDirection) else { return nil }
        
        //Don't silence within x turns of last silence, even if it is charged
        let turnsBetweenSilences = 12
        
        let lastSilenceGameState = allGameStates.last {
            $0.myOrders.contains(.silence(nil, nil))
        }
        guard gameState.turnNumber - (lastSilenceGameState?.turnNumber ?? 0) >= turnsBetweenSilences else { return nil }
        
        //Figure out how many tiles we can go in moveDirection, cap that at 4, and silence a random amount 0-cap in that direction
        var canMoveToTile: GameTile = tileAfterMove
        while let nextTile = canMoveToTile.next(direction: moveDirection), nextTile.canMoveTo {
            canMoveToTile = nextTile
        }
        let possibleMoveSpaces = min(canMoveToTile.distance(to: tileAfterMove), 4)
        debug("Using silence, can move up to \(possibleMoveSpaces) spaces \(moveDirection.symbol())")
        return .silence(moveDirection, Int.random(in: 0...possibleMoveSpaces))
    }


    func initGame() {
        let inputs = (readLine()!).split(separator: " ").map(String.init)
        let width = Int(inputs[0])!
        let height = Int(inputs[1])!
        let myId = Int(inputs[2])!
        GameTile.createMap(width: width, height: height)

        if height > 0 {
            for y in 0...(height-1) {
                for (x, value) in readLine()!.enumerated() {
                    GameTile.at(x, y)!.isWater = value == "."
                }
            }
        }
        GameTile.tiles.forEach{ $0.isOpponentPossibility = true }
        var tile = GameTile.map[Location(0,0)]!
        while(!tile.isWater) {
            tile = tile.below!
        }
        
        let startLocation = tile.location
        gameState = GameState(w: width, h: height, myId: myId, start: startLocation)
        //Print starting location
        print("\(startLocation.x) \(startLocation.y)");
    }
    initGame()

    // game loop
    while true {
        if gameState.turnNumber > 0 {
            allGameStates.append(gameState)
        }
        gameState = GameState(gs: gameState)
        let line = readLine()!
        let inputs = line.split(separator: " ").map(String.init)
        gameState.myLocation = Location(Int(inputs[0])!, Int(inputs[1])!)
        gameState.myLife = Int(inputs[2])!
        gameState.opponentLife = Int(inputs[3])!
        gameState.torpedoCooldown = Int(inputs[4])!
        gameState.sonarCooldown = Int(inputs[5])!
        gameState.silenceCooldown = Int(inputs[6])!
        gameState.mineCooldown = Int(inputs[7])!
        let sonarResult = readLine()!
        gameState.turnNumber = gameState.turnNumber + 1
        debug("Start turn \(gameState.turnNumber)")
        
        checkSonarResult(result: sonarResult, quadrant: lastSonarQuadrant)
        updateEnemyLocationFromHealthChange()
        
        
        let opponentOrders = readLine()!
        if opponentOrders != "NA" {
            handleOpponentOrders(opponentOrders)
        }
        
        updateMove()

        if needToSurface {
            gameState.myOrders.append(.surface(nil))
            needToSurface = false
        }
        else {
            if let torpedoOrder = fireTorpedo() {
                gameState.myOrders.append(torpedoOrder)
            }
            if let sonarOrder = useSonar() {
                gameState.myOrders.append(sonarOrder)
            }
            if let silenceOrder = useSilence() {
                gameState.myOrders.append(silenceOrder)
            }
            

            let canChargeTorpedo = gameState.torpedoCooldown > 0 || gameState.myOrders.contains(.torpedo(Location.some))
            let canChargeSonar = gameState.sonarCooldown > 0 || gameState.myOrders.contains(.sonar(0))
            let canChargeSilence = gameState.silenceCooldown > 0 || gameState.myOrders.contains(.silence(nil, nil))

            let chargeAction = canChargeTorpedo ? "TORPEDO" : canChargeSilence ? "SILENCE" : canChargeSonar ? "SONAR" : "MINE"
            
            gameState.myOrders.append(.message(String(GameTile.possibleOpponentTiles().count)))
            
            gameState.myOrders.append(.move(moveDirection, chargeAction))
        }
        
        if let lastTurn = lastTurnGameState {
            debug("Last turn I executed \(lastTurn.myOrders.map{ $0.command() }.joined(separator: "|"))")
            debug("Then opponent executed \(lastTurn.opponentOrders.map{ $0.command() }.joined(separator: "|"))")
        }
        if GameTile.possibleOpponentTiles().count <= 10 {
            debug("Possibilities - \(GameTile.possibleOpponentTiles())")
        }
        
        //print orders
        print(gameState.myOrders.map{ $0.command() }.joined(separator: "|"))
    }

