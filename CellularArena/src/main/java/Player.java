import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Grow and multiply your organisms to end up larger than your opponent.
 **/
class Player {

    private Grid grid;
    private Pathing pathing;
    private final Timer timer = new Timer();
    private final HashMap<Integer, Entity> entitiesById = new HashMap<>();
    private final HashMap<Integer, List<Entity>> rootIdToDescendents = new HashMap<>();   // All descendents for a given root id
    private final List<Entity> myRoots = new ArrayList<>();
    private final Map<Entity, Double> buildRootMeritMap = new HashMap<>();
    private final Map<Entity, Double> expandMeritMap = new HashMap<>();
    private final List<Tuple2<Entity, Entity>> entitiesChangedFromLastTurn = new ArrayList<>();
    private Map<EntityType, Integer> myHarvesterCountMap = new HashMap<>();                  // The number of harvesters per protein type I have
    private Map<EntityType, Integer> enemyHarvesterCountMap = new HashMap<>();               // The number of harvesters per protein type my enemy has
    private int myA;
    private int myB;
    private int myC;
    private int myD;
    private int enemyA;
    private int enemyB;
    private int enemyC;
    private int enemyD;
    private int turn = 0;
    private Integer currentRootId;
    private static final boolean showRootIdOnCommand = false;
    private final List<Behavior> behaviors = new ArrayList<>();

    private class Grid {
        private final List<List<Entity>> entities;
        private final Set<Entity> entitySet;
        private final Set<Entity> proteins;
        private final int width, height;

        public Grid(int width, int height) {
            this.width = width;
            this.height = height;
            this.entities = new ArrayList<>(height);
            this.proteins = new HashSet<>();
            for (int y = 0; y < height; y++) {
                List<Entity> row = new ArrayList<>(width);
                for (int x = 0; x < width; x++) {
                    row.add(new Entity(this, x, y));
                }
                entities.add(row);
            }
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    Entity entity = entityAt(x, y);
                    if (x > 0) {
                        Entity left = entityAt(x - 1, y);
                        left.setRight(entity);
                        entity.setLeft(left);
                    }
                    if (y > 0) {
                        Entity up = entityAt(x, y - 1);
                        up.setDown(entity);
                        entity.setUp(up);
                    }
                }
            }
            entitySet = entities.stream().flatMap(Collection::stream).collect(Collectors.toSet());
            entitySet.forEach(Entity::initNeighbors);
        }

        public Set<Entity> getEntitySet() {
            return entitySet;
        }

        public Stream<Entity> myEntitiesStream() {
            return entitySet.stream().filter(Entity::mine);
        }

        public Set<Entity> getProteins() {
            return proteins;
        }

        public Entity entityAt(int x, int y) {
            if (x >= 0 && x < width && y >= 0 && y < height) {
                return entities.get(y).get(x);
            }
            return null;
        }

        public Stream<Entity> adjacentToMine(int rootId) {
            return myEntitiesStream()
                    .filter(entity -> entity.getRootId() == rootId)
                    .flatMap(Entity::neighborsStream)
                    .distinct()
                    .filter(entity -> !entity.mine());
        }

        public void buildGhostEntity(Entity entity, EntityType type, Direction direction) {
            if (entity.getType().isProtein() && !type.isProtein()) {
                proteins.remove(entity);
            }
            entity.setType(type);
            entity.setOwner(Owner.ME);
            entity.setDirection(direction);
        }
    }

    private class Pathing {

        private final Map<Entity, Map<Entity, PathInfo>> paths = new HashMap<>();
        private final Map<Entity, List<PathInfo>> sortedPaths = new HashMap<>();
        private final int maxDepth;

        public Pathing(int maxDepth) {
            this.maxDepth = maxDepth;
        }

        public Integer distance(Entity from, Entity to) {
            return Optional.ofNullable(pathInfo(from, to))
                    .map(PathInfo::distance)
                    .orElse(null);
        }

        public List<Direction> nextDirections(Entity from, Entity to) {
            return Optional.ofNullable(pathInfo(from, to))
                    .map(PathInfo::directions)
                    .orElse(Collections.emptyList());
        }

        public PathInfo pathInfo(Entity from, Entity to) {
            return Optional.ofNullable(paths.get(to))
                    .map(map -> map.get(from)).orElse(null);
        }

        public List<Entity> entitiesWithinDistance(Entity from, int searchDistance) {
            myAssert(searchDistance > 0, "Search distance must be greater than 0");
            List<PathInfo> pathInfoList = sortedPaths.get(from);
            if (pathInfoList == null) {
                return Collections.emptyList();
            }
            PathInfo searchValue = new PathInfo(null, null, searchDistance, null);
            int index = Collections.binarySearch(pathInfoList, searchValue, Comparator.comparingInt(PathInfo::distance));
            if (index >= 0) {
                // Binary search will give an arbitrary value with this distance, need to find the last one
                while (index < pathInfoList.size() - 1 && pathInfoList.get(index + 1).distance() == searchDistance) {
                    index++;
                }
            }
            // If we didn't find the number, it must be greater than everything that is within distance, return all reachable entities
            List<PathInfo> pathInfos = index < 0 ? pathInfoList : pathInfoList.subList(0, index + 1);
            return pathInfos.stream().map(PathInfo::from).toList();
        }

        public void generatePaths() {
            timer.start(this);
            for (Entity entity : grid.getEntitySet()) {
                if (entity.getType().equals(EntityType.WALL)) {
                    continue;
                }
                paths.put(entity, new HashMap<>());
                sortedPaths.put(entity, new ArrayList<>());
                generatePaths(entity);
            }
            timer.end(this);
        }

        private void generatePaths(Entity entity) {
            Map<Entity, PathInfo> pathsForEntity = paths.get(entity);
            pathsForEntity.clear();
            pathsForEntity.put(entity, new PathInfo(entity, entity, 0, Collections.emptyList()));
            int distance = 1;
            Queue<Entity> queue = new LinkedList<>(entity.neighbors());
            while (!queue.isEmpty()) {
                int entitiesToProcess = queue.size();
                for (int i = 0; i < entitiesToProcess; i++) {
                    Entity from = queue.poll();
                    if (pathsForEntity.get(from) != null || from.getType().equals(EntityType.WALL)) {
                        continue;
                    }
                    final int currentDistance = distance;
                    List<Direction> directions = from.neighbors().stream()
                            .filter(neighbor -> {
                                PathInfo neighborPathInfo = pathsForEntity.get(neighbor);
                                return neighborPathInfo != null && neighborPathInfo.distance() == currentDistance - 1;
                            }).map(from::directionTo)
                            .toList();
                    if (!directions.isEmpty()) {
                        pathsForEntity.put(from, new PathInfo(from, entity, distance, directions));
                        for (Entity neighbor : from.neighbors()) {
                            if (distance < maxDepth || neighbor.isInLineWith(entity)) {
                                queue.offer(neighbor);
                            }
                        }
                    }
                }
                distance++;
            }
            // Remove the path to self with distance 0. I don't want this coming back in query results, saying there is no path to self should be fine.
            pathsForEntity.remove(entity);
            List<PathInfo> sortedPathList = sortedPaths.get(entity);
            sortedPathList.clear();
            sortedPathList.addAll(pathsForEntity.values());
            sortedPathList.sort(Comparator.comparingInt(PathInfo::distance));
        }

    }

    record PathInfo(Entity from, Entity to, int distance, List<Direction> directions) {
    }

    private enum Direction {
        N, S, E, W
    }

    private static class Owner {
        public static final int ME = 1;
        public static final int ENEMY = 0;
        public static final int NOBODY = -1;
    }

    private enum EntityType {
        EMPTY, WALL, A, B, C, D,
        BASIC(1, 0, 0, 0),
        HARVESTER(0, 0, 1, 1),
        TENTACLE(0, 1, 1, 0),
        SPORER(0, 1, 0, 1),
        ROOT(1, 1, 1, 1);

        private final int[] cost;

        EntityType() {
            this(0, 0, 0, 0);
        }

        EntityType(int a, int b, int c, int d) {
            this.cost = new int[]{a, b, c, d};
        }

        private static final List<EntityType> PROTEIN_TYPES = Arrays.asList(A, B, C, D);

        public boolean isProtein() {
            return PROTEIN_TYPES.contains(this);
        }

        public int[] getCost() {
            return cost;
        }
    }

    private static class EntityPredicates {
        public static final Predicate<Entity> HARVESTED_BY_ME = entity -> entity.getType().isProtein()
                && entity.myNeighbor(myNeighbor ->
                myNeighbor.getType().equals(EntityType.HARVESTER)
                        && myNeighbor.directionTo(entity).equals(myNeighbor.getDirection())) != null;
        public static final Predicate<Entity> SHOOT_ROOT_OVER = entity -> entity.getType().isProtein() || entity.getType().equals(EntityType.EMPTY);
        public static final Predicate<Entity> ENEMY_NOT_ATTACKING = entity -> entity.neighborsStream()
                .noneMatch(enemy -> enemy.enemy() && enemy.getType().equals(EntityType.TENTACLE) && enemy.entityInFront() == entity);
    }

    private static class Entity {
        private int id, parentId, rootId;
        private final int x;
        private final int y;
        private final Grid grid;
        private Entity up, down, left, right;
        private EntityType type;
        private EntityType oldType;
        private final List<Entity> children;
        private List<Entity> neighbors;
        private int owner;           // 1 for me, 2 for enemy, 0 for no one
        private Direction direction;

        public Entity(Grid grid, int x, int y) {
            this.grid = grid;
            this.x = x;
            this.y = y;
            children = new ArrayList<>();
            reset();
        }

        public Entity(Entity copy) {
            this.grid = copy.grid;
            this.x = copy.x;
            this.y = copy.y;
            this.type = copy.type;
            this.children = new ArrayList<>(copy.children);
        }

        public void reset() {
            oldType = type;
            type = EntityType.EMPTY;
            children.clear();
            owner = -1;
        }

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public int getParentId() {
            return parentId;
        }

        public void setParentId(int parentId) {
            this.parentId = parentId;
        }

        public int getRootId() {
            return rootId;
        }

        public void setRootId(int rootId) {
            this.rootId = rootId;
        }

        public EntityType getType() {
            return type;
        }

        public void setType(EntityType type) {
            this.type = type;
        }

        public EntityType getOldType() {
            return oldType;
        }

        public List<Entity> getChildren() {
            return children;
        }

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public Grid grid() {
            return grid;
        }

        public Entity getUp() {
            return up;
        }

        public void setUp(Entity up) {
            this.up = up;
        }

        public Entity getDown() {
            return down;
        }

        public void setDown(Entity down) {
            this.down = down;
        }

        public Entity getLeft() {
            return left;
        }

        public void setLeft(Entity left) {
            this.left = left;
        }

        public Entity getRight() {
            return right;
        }

        public void setRight(Entity right) {
            this.right = right;
        }

        public void setOwner(int owner) {
            this.owner = owner;
        }

        public Player.Direction getDirection() {
            return direction;
        }

        public void setDirection(Player.Direction direction) {
            this.direction = direction;
        }

        public boolean mine() {
            return Owner.ME == owner;
        }

        public boolean enemy() {
            return Owner.ENEMY == owner;
        }

        public boolean unowned() {
            return Owner.NOBODY == owner;
        }

        public void initNeighbors() {
            neighbors = Stream.of(up, down, left, right).filter(Objects::nonNull).toList();
        }

        public List<Entity> neighbors() {
            return neighbors;
        }

        public Stream<Entity> neighborsStream() {
            return neighbors.stream();
        }

        public Entity myNeighbor(int rootId) {
            return myNeighbor(tile -> rootId == tile.getRootId());
        }

        public Entity myNeighbor(Predicate<Entity> predicate) {
            return neighborsStream().filter(Entity::mine).filter(predicate).findFirst().orElse(null);
        }

        public Entity entityInDirection(Player.Direction direction) {
            return switch (direction) {
                case N -> up;
                case S -> down;
                case E -> right;
                case W -> left;
            };
        }

        public Entity entityInFront() {
            if (direction == null) {
                return null;
            }
            return entityInDirection(direction);
        }

        public Stream<Entity> entitiesInFront() {
            return entitiesInFront(direction);
        }

        public Stream<Entity> entitiesInFront(Direction givenDirection) {
            List<Entity> entities = new ArrayList<>();
            Entity currentEntity = this.entityInDirection(givenDirection);
            while (currentEntity != null && EntityPredicates.SHOOT_ROOT_OVER.test(currentEntity)) {
                entities.add(currentEntity);
                currentEntity = currentEntity.entityInDirection(givenDirection);
            }
            return entities.stream();
        }

        public boolean isInLineWith(Entity other) {
            return x == other.getX() ^ y == other.getY();
        }

        public boolean isEmpty() {
            return type.equals(Player.EntityType.EMPTY);
        }

        public boolean isBuildable() {
            return (isEmpty() || type.isProtein()) && EntityPredicates.ENEMY_NOT_ATTACKING.test(this);
        }

        public Player.Direction directionTo(Entity other) {
            int xDiff = other.getX() - x;
            if (xDiff != 0) {
                return xDiff > 0 ? Player.Direction.E : Player.Direction.W;
            }
            return other.getY() - y > 0 ? Player.Direction.S : Player.Direction.N;
        }

        public int descendentCount() {
            return children.size() + children.stream().mapToInt(Entity::descendentCount).sum();
        }

        @Override
        public String toString() {
            return String.format("[Entity %s,%s  %s]", x, y, type);
        }
    }

    private interface Behavior {
        // Returns null if there isn't a good command for this behavior
        Player.Command getCommand(int rootId);
    }

    private class AttackBehavior implements Behavior {
        @Override
        public Command getCommand(int rootId) {
            Player.EntityType buildType = EntityType.TENTACLE;
            if (!canBuild(buildType)) {
                debug("Can't build a sporer, skipping " + this);
                return null;
            }
            Function<Entity, Stream<MeritResult>> entityToDirectionTupleMapper = entity -> Arrays.stream(Direction.values())
                    .filter(direction -> {
                        Entity neighbor = entity.entityInDirection(direction);
                        return neighbor != null && !neighbor.mine() && !neighbor.getType().equals(EntityType.WALL);
                    })
                    .map(direction -> new MeritResult(entity.myNeighbor(rootId), direction, entity, null));

            MeritResult attackResult = grid.adjacentToMine(rootId)
                    .filter(Entity::isBuildable)
                    .filter(entity -> pathing.entitiesWithinDistance(entity, 3).stream().anyMatch(Entity::enemy))
                    .flatMap(entityToDirectionTupleMapper)
                    .map(result -> new MeritResult(result.from(), result.direction(), result.to(), calculateAttackMerit(result.to(), result.direction())))
//                    .peek(result -> debug(String.format("%.2f merit attacking at %s %s", result.merit(), result.to(), result.direction())))
                    .max(Comparator.comparingDouble(MeritResult::merit))
                    .orElse(null);
            if (attackResult == null) {
                return null;
            }
            return new GrowCommand(rootId, attackResult.from(), attackResult.to(), buildType, attackResult.direction(), attackResult.merit());
        }

        public String toString() {
            return "[Attack Behavior]";
        }
    }


    /**
     * When an organism doesn't have a sporer, create a new one so we can branch out.
     */
    private class CreateSporerBehavior implements Behavior {
        @Override
        public Command getCommand(int rootId) {
            if (!shouldConsiderNewRoot(true)) {
                return null;
            }
            MeritResult mostMerit = getRootExpandLocation(rootId, false);

//            debug(String.format("%s Most Merit: %s", this, mostMerit));
            if (mostMerit != null) {
                // Store what we found here to prevent calculations next turn, assume it is still good. Will modify this if I observe problems with using the cached value.
                Entity buildFrom = mostMerit.from().myNeighbor(rootId);
                Entity buildTo = mostMerit.from();
                Direction direction = mostMerit.from().directionTo(mostMerit.to());
                return new GrowCommand(rootId, buildFrom, buildTo, EntityType.SPORER, direction, mostMerit.merit());
            }
            return null;
        }

        public String toString() {
            return "[Create Sporer Behavior]";
        }
    }

    private class CreateNewRootBehavior implements Player.Behavior {
        @Override
        public Player.Command getCommand(int rootId) {
            if (shouldConsiderNewRoot(false)) {
                MeritResult nextRoot = getRootExpandLocation(rootId, true);
                if (nextRoot != null) {
                    return new Player.SporeCommand(rootId, nextRoot.from(), nextRoot.to(), nextRoot.merit());
                }
            }
            return null;
        }

        public String toString() {
            return "[Create New Root Behavior]";
        }
    }

    private class BuildHarvesterBehavior implements Player.Behavior {
        @Override
        public Player.Command getCommand(int rootId) {
            if (!canBuild(Player.EntityType.HARVESTER)) {
                return null;
            }
            Predicate<Entity> harvestablePredicate = entity -> entity.getType().isProtein() && !EntityPredicates.HARVESTED_BY_ME.test(entity);
            List<BuildEntityTuple> possibleHarvesterBuilds = getPossibleBuildsWithTarget(rootId, harvestablePredicate);
            if (possibleHarvesterBuilds.isEmpty()) {
                return null;
            }
            MeritResult harvesterToBuild = possibleHarvesterBuilds.stream()
                    .map(Player.this::getHarvesterExpandMeritResult)
                    .max(Comparator.comparingDouble(MeritResult::merit))
                    .orElse(null);
            return new GrowCommand(rootId, harvesterToBuild.from(), harvesterToBuild.to(), EntityType.HARVESTER, harvesterToBuild.direction(), harvesterToBuild.merit());
        }

        public String toString() {
            return "[Build Harvester Behavior]";
        }
    }

    private class ExpandToSpaceBehavior implements Player.Behavior {
        @Override
        public Command getCommand(int rootId) {
            // Get adjacent buildable spaces
            // Get merit ranking for building there
            // Sort by ranking
            MeritResult bestExpandResult = grid.adjacentToMine(rootId)
                    .filter(Entity::isBuildable)
                    .map(entity -> new MeritResult(entity.myNeighbor(rootId), null, entity, calculateExpandMerit(entity)))
//                    .peek(result -> debug(String.format("%.2f merit expanding to %s", result.merit(), result.to())))
                    .max(Comparator.comparingDouble(MeritResult::merit))
                    .orElse(null);
            Player.EntityType buildType = getArbitraryBuildableType();
            if (bestExpandResult == null || buildType == null) {
                return null;
            }
            return new GrowCommand(rootId, bestExpandResult.from(), bestExpandResult.to(), buildType, Direction.N, bestExpandResult.merit());
        }

        public String toString() {
            return "[Expand To Space Behavior]";
        }
    }

    private static class WaitBehavior implements Player.Behavior {
        @Override
        public Command getCommand(int rootId) {
            return new WaitCommand(rootId);
        }

        public String toString() {
            return "[Wait Behavior]";
        }
    }

    private interface Command {
        String getText();

        double merit();

        default Player.EntityType getBuildType() {
            return null;
        }

        default Player.Entity getBuildFrom() {
            return null;
        }

        int rootId();

        // Called when the command is chosen to be executed. Can call ghostEntity() if it is creating a new entity.
        void updateState();
    }

    private record WaitCommand(int rootId) implements Player.Command {
        @Override
        public String getText() {
            return "WAIT" + (showRootIdOnCommand ? " " + rootId + " WAIT" : "");
        }

        @Override
        public double merit() {
            return 0;
        }

        @Override
        public void updateState() {
        }

        public String toString() {
            return String.format("[%.2f] WAIT", merit());
        }
    }

    private record GrowCommand(int rootId, Player.Entity from, Player.Entity to, Player.EntityType type,
                               Player.Direction direction, double merit) implements Player.Command {
        @Override
        public String getText() {
            if (direction != null) {
                return String.format("GROW %s %s %s %s %s%s", from.getId(), to.getX(), to.getY(), type, direction.name(), showRootIdOnCommand ? " " + rootId + " GROW" : "");
            }
            return String.format("GROW %s %s %s %s%s", from.getId(), to.getX(), to.getY(), type, showRootIdOnCommand ? " " + rootId + " GROW" : "");
        }

        @Override
        public Player.EntityType getBuildType() {
            return type;
        }

        @Override
        public Entity getBuildFrom() {
            return from;
        }

        @Override
        public void updateState() {
            to.grid().buildGhostEntity(to, type, direction);
        }

        public String toString() {
            return String.format("[%.2f] %s", merit(), getText());
        }
    }

    private record SporeCommand(int rootId, Player.Entity from, Player.Entity to,
                                double merit) implements Player.Command {
        @Override
        public String getText() {
            return String.format("SPORE %s %s %s%s", from.getId(), to.getX(), to.getY(), showRootIdOnCommand ? " " + rootId + " SPORE" : "");
        }

        @Override
        public Player.EntityType getBuildType() {
            return Player.EntityType.ROOT;
        }

        @Override
        public Entity getBuildFrom() {
            return from;
        }

        @Override
        public void updateState() {
            to.grid().buildGhostEntity(to, EntityType.ROOT, null);
        }

        public String toString() {
            return String.format("[%.2f] %s", merit(), getText());
        }
    }

    private List<Command> getCommands(int commandsNeeded) {
        debug("Commands needed: " + commandsNeeded);
        List<Command> commands = new ArrayList<>();
        // Iterate my roots in reverse order so ostensibly further forward organisms act first
        for (int i = commandsNeeded - 1; i >= 0; i--) {
            Entity currentRoot = myRoots.get(i);
            currentRootId = currentRoot.getId();
            List<Command> possibleCommands = new ArrayList<>();
            for (Behavior behavior : behaviors) {
                timer.start(behavior);
                Optional.ofNullable(behavior.getCommand(currentRoot.getRootId()))
                        .ifPresent(possibleCommands::add);
                timer.end(behavior);
            }
            debug("Commands considered:");
            for (Command command : possibleCommands) {
                debug("\t" + command);
            }
            Command bestCommand = possibleCommands.stream().max(Comparator.comparingDouble(Command::merit)).orElseThrow(() -> new IllegalStateException("No command found"));
            debug("Executing command " + bestCommand);
            spendProtein(bestCommand.getBuildType());
            bestCommand.updateState();
            Entity buildFrom = bestCommand.getBuildFrom();
            if (buildFrom != null) {
                myAssert(buildFrom.getRootId() == bestCommand.rootId(), String.format("It is root %s's turn but we are producing from %s with root id %s", bestCommand.rootId(), buildFrom, buildFrom.getRootId()));
            }
            commands.add(bestCommand);
        }
        currentRootId = null;
        return commands;
    }

    private EntityType getArbitraryBuildableType() {
        int tentacleBuildCount = buildCount(EntityType.TENTACLE);
        if (tentacleBuildCount > 20) {
            // If we can build a ton of tentacles, just build it. It's the only one that may incidentally help down the road
            return EntityType.TENTACLE;
        }
        Stream<EntityType> ENTITY_BUILD_TYPES = Stream.of(EntityType.BASIC, EntityType.SPORER, EntityType.TENTACLE, EntityType.HARVESTER);
        return ENTITY_BUILD_TYPES.max(Comparator.comparingInt(this::buildCount))
                .filter(this::canBuild)
                .orElse(null);
    }

    private record BuildEntityTuple(Entity mine, Entity buildableTile, Entity target) {
    }

    private record MeritResult(Entity from, Direction direction, Entity to, Double merit) {
    }

    public record Tuple2<A, B>(A a, B b) {
    }

    public record Tuple3<A, B, C>(A a, B b, C c) {
    }

    private List<BuildEntityTuple> getPossibleBuildsWithTarget(int rootId, Predicate<Entity> targetPredicate) {
        List<Entity> buildableTiles = grid.adjacentToMine(rootId)
                .filter(Entity::isBuildable)
                .distinct()
                .toList();
        List<BuildEntityTuple> tuples = new ArrayList<>();
        for (Entity emptyTile : buildableTiles) {
            emptyTile.neighborsStream().filter(targetPredicate).forEach(target -> {
                tuples.add(new BuildEntityTuple(emptyTile.myNeighbor(rootId), emptyTile, target));
            });
        }
        return tuples;
    }

    private boolean canBuild(EntityType type) {
        return buildCount(type) > 0;
    }

    private int buildCount(EntityType type) {
        return switch (type) {
            case BASIC -> myA;
            case HARVESTER -> Math.min(myC, myD);
            case TENTACLE -> Math.min(myB, myC);
            case SPORER -> Math.min(myB, myD);
            case ROOT -> Math.min(Math.min(Math.min(myA, myB), myC), myD);
            default -> throw new IllegalArgumentException("Can't build type " + type);
        };
    }

    private int getProteinCount(EntityType type) {
        return switch (type) {
            case A -> myA;
            case B -> myB;
            case C -> myC;
            case D -> myD;
            default -> throw new IllegalArgumentException();
        };
    }

    private void spendProtein(EntityType type) {
        if (type == null) {
            return;
        }
        int[] costs = type.getCost();
        myA -= costs[0];
        myB -= costs[1];
        myC -= costs[2];
        myD -= costs[3];
    }

    /**
     * @param rootId           The rootId for the entity
     * @param useExistingSpore True if we want to try spawning a root from an existing spore, false if are looking
     *                         to create a spore, then spawn a root next turn.
     * @return The result of all possibilities for new ROOT expansion
     */
    private MeritResult getRootExpandLocation(int rootId, boolean useExistingSpore) {
        Function<Entity, Stream<MeritResult>> entityToDirectionTupleMapper = entity -> Arrays.stream(Direction.values()).map(direction -> new MeritResult(entity, direction, null, null));

        // Stream with the place we want to place a spore and its direction
        Stream<MeritResult> sporeDirectionStream;
        if (useExistingSpore) {
            sporeDirectionStream = grid.myEntitiesStream()
                    .filter(entity -> entity.getRootId() == rootId)
                    .filter(entity -> entity.getType().equals(EntityType.SPORER))
                    .map(entity -> new MeritResult(entity, entity.getDirection(), null, null));
        } else {
            sporeDirectionStream = grid.adjacentToMine(rootId)
                    .filter(Entity::isBuildable)
                    .flatMap(entityToDirectionTupleMapper);
        }

        return sporeDirectionStream
                .flatMap(result -> result.from().entitiesInFront(result.direction()).map(potentialRootTile -> new MeritResult(result.from(), result.direction(), potentialRootTile, null)))
                .filter(result -> EntityPredicates.ENEMY_NOT_ATTACKING.test(result.to()))
                .map(result -> {
                    double totalMerit = calculateRootMerit(result.to()) + getRootMeritWithSource(result.from(), result.to());
                    return new MeritResult(result.from(), result.direction(), result.to(), totalMerit);
                })
//                    .peek(result -> debug(String.format("%s [%s] from %s %s to %s", this, result.merit(), result.from(), result.direction(), result.to())))
                .max(Comparator.comparingDouble(MeritResult::merit))
                .orElse(null);
    }

    // These are the knobs we can turn to influence decision-making
    public static class Merit {
        // --- New Root ---
        // Give this much merit per space the root is from the sporer
        public static final double NEW_ROOT_MERIT_PER_DISTANCE_FROM_SPORER = .3;
        // Don't give extra bonus after this many spaces, it may shove us in a corner instead of a better spot
        public static final double NEW_ROOT_MERIT_MAX_DISTANCE_FOR_BONUS = 10;
        // The first new ROOT gives [0], second [1], etc
        public static final List<Double> NEW_ROOT_MERIT_BY_ENTITY_COUNT = Arrays.asList(12.0, 8.0, 4.0);
        // Merit for new ROOTs after the above benefits are exhausted
        public static final double NEW_ROOT_MERIT_DEFAULT = 0;

        public static final List<Double> NEW_ROOT_MERIT_FROM_PROTEIN_BY_DISTANCE = Arrays.asList(.5, .25, 1.0, .25);
        public static final double NEW_ROOT_MERIT_FROM_FRIENDLY_WITHIN_THREE_DISTANCE = -3;

        // -- New Harvester --
        // Same as above, first harvester of this protein type gets [0], etc
        public static final List<Double> NEW_HARVESTER_MERIT_BY_ENTITY_COUNT = Arrays.asList(8.0, 5.0);
        // Default merit count of harvesters of a protein type after the 2nd
        public static final double NEW_HARVESTER_DEFAULT_MERIT = 4.0;
        // Don't harvest near enemies
        public static final double NEW_HARVESTER_PROTEIN_CLOSE_TO_ENEMY = -5;
        // Depending on how badly we need a protein, give a multiplier between the blow values ( _MIN_ to _MAX_, linearly scaling from 0 to the below value)
        // For example, given the below 3 values of 20, 2, .5, if we have 6 of that protein, give a (20-6)/20 * (2-.5) + .5 = 1.55 multiplier because we are relatively low
        // Having 20 would give the min of (20-20)/20 * (2-.5) + .5 = .5
        public static final double NEW_HARVESTER_PROTEIN_THRESHOLD = 8;
        public static final double NEW_HARVESTER_MAX_PROTEIN_MERIT = 5;
        public static final double NEW_HARVESTER_MIN_PROTEIN_MERIT = 0;

        // --New Arbitrary Expansion --
        // Same as above. The multiplier is applied per protein and is divided by the distances to that protein.
        public static final double NEW_EXPANSION_TO_PROTEIN_THRESHOLD = 20;
        public static final double NEW_EXPANSION_TO_PROTEIN_MAX_PROTEIN_MULTIPLIER = 2;
        public static final double NEW_EXPANSION_TO_PROTEIN_MIN_PROTEIN_MULTIPLIER = 0;

        // Consuming my own harvested protein as part of expansion gives this negative merit
        public static final double NEW_EXPANSION_CONSUME_HARVESTED_PROTEIN_MERIT = -4;
        // Late game (turn 70+) when the field is likely locked up, increase merit of taking spaces (harvested proteins)
        public static final double NEW_EXPANSION_LATE_GAME_EXPAND_MERIT = 5;
        // Merit bonus when I'm out of a protein and can consume one
        public static final double NEW_EXPANSION_NEED_PROTEIN_MERIT = 3;

        // -- New Attacker --
        // Merit for pointing in the direction of an enemy 1, 2, 3 spaces away. 1 space is covered below by killing, building 1 space away pointing the wrong way doesn't help.
        public static final List<Double> NEW_ATTACKER_DISTANCE_FROM_ENEMY_MERIT = Arrays.asList(0.0, 10., 10.);
        // Get a kill - build distance 1 away and pointing in the right direction
        public static final double NEW_ATTACKER_PARENT_KILL_MERIT = 6;
        // Merit for each child of the parent killed. Not sure yet if this is an important distinction, or if a kill is a kill
        public static final double NEW_ATTACKER_CHILD_KILL_MERIT = 1;
        // If a protein is contested, better we take it than the opponent
        public static final double NEW_ATTACKER_BUILD_ON_PROTEIN_MERIT = 3;
        // If we are already pointing an attacker at the tile we are considering building in, it isn't as important to build there because the enemy can't
        // It still may be important if the enemy could build up a tentacle coming into this square and us building one would prevent that (both new tentacles die)
        public static final double NEW_ATTACKER_TILE_CONTROL_MERIT = -2;
    }

    /**
     * Ideally, we create a root that is 2 spaces away from proteins (for harvesting) and far away from everything else.
     */
    private double calculateRootMerit(Entity newRoot) {
        return buildRootMeritMap.computeIfAbsent(newRoot, entity ->
                pathing.entitiesWithinDistance(newRoot, 3)
                        .stream()
                        .reduce(0.0, (meritSum, closeByEntity) -> getRootMeritFromNearbyTile(newRoot, closeByEntity) + meritSum, Double::sum)
                        + getRootMeritFromState(newRoot)
        );
    }

    private double getRootMeritWithSource(Entity from, Entity newRoot) {
        return Merit.NEW_ROOT_MERIT_PER_DISTANCE_FROM_SPORER * Math.min(pathing.distance(from, newRoot), Merit.NEW_ROOT_MERIT_MAX_DISTANCE_FOR_BONUS);
    }

    private double getRootMeritFromState(Entity newRoot) {
        // Give merit based on how many roots we have. Creating a second gives [0], a third gives [1], etc.
        int entities = myRoots.size();
        return entities < Merit.NEW_ROOT_MERIT_BY_ENTITY_COUNT.size() ? Merit.NEW_ROOT_MERIT_BY_ENTITY_COUNT.get(entities) : Merit.NEW_ROOT_MERIT_DEFAULT;
    }

    private double calculateExpandMerit(Entity source) {
//        debug("Calculating merit for " + source + ":");
        return expandMeritMap.computeIfAbsent(source, entity -> grid.getProteins().stream()
                .mapToDouble(protein -> getNearbyProteinExpandMerit(entity, protein))
                .sum() + getLocationExpandMerit(source)
        );
    }

    // Gives the amount of merit for expanding to source given the fact that a given protein entity is nearby
    private double getNearbyProteinExpandMerit(Entity source, Entity protein) {
        myAssert(protein.getType().isProtein(), protein + " is not a protein");
        Integer distance = pathing.distance(source, protein);
        if (distance == null || distance > 6) {
            return 0;
        }

        // Don't give additional bonus to proteins that are 1 away
        distance = Math.max(2, distance);
        int proteinCount = getProteinCount(protein.getType());
        double proteinMultiplier = linearlyScaledPercent(proteinCount, Merit.NEW_EXPANSION_TO_PROTEIN_THRESHOLD, Merit.NEW_EXPANSION_TO_PROTEIN_MIN_PROTEIN_MULTIPLIER, Merit.NEW_EXPANSION_TO_PROTEIN_MAX_PROTEIN_MULTIPLIER);
        double totalMerit = proteinMultiplier / distance;
//        debug(String.format("\t%.2f merit from %s", totalMerit, protein));
        return totalMerit;
    }

    private double getLocationExpandMerit(Entity source) {
        return (EntityPredicates.HARVESTED_BY_ME.test(source) ? Merit.NEW_EXPANSION_CONSUME_HARVESTED_PROTEIN_MERIT : 0)
                + (turn >= 70 ? Merit.NEW_EXPANSION_LATE_GAME_EXPAND_MERIT : 0)
                + (source.getType().isProtein() && getProteinCount(source.getType()) == 0 ? Merit.NEW_EXPANSION_NEED_PROTEIN_MERIT : 0)
                ;
    }

    // See examples in Merit.class
    private double linearlyScaledPercent(double count, double threshold, double min, double max) {
        return (Math.max(0, threshold - count) / threshold) * (max - min) + min;
    }

    private MeritResult getHarvesterExpandMeritResult(BuildEntityTuple tuple) {
        // Give merit based on how many harvesters we currently have of that type. With zero, give HARVESTER_MERIT[0], etc.
        List<Double> HARVESTER_MERIT_LIST = Merit.NEW_HARVESTER_MERIT_BY_ENTITY_COUNT;
        EntityType protein = tuple.target().getType();
        int harvesterCount = myHarvesterCountMap.get(protein);
        double harvesterMerit = harvesterCount < HARVESTER_MERIT_LIST.size() ? HARVESTER_MERIT_LIST.get(harvesterCount) : Merit.NEW_HARVESTER_DEFAULT_MERIT;
        int proteinCount = getProteinCount(protein);
        double proteinMerit = linearlyScaledPercent(proteinCount, Merit.NEW_HARVESTER_PROTEIN_THRESHOLD, Merit.NEW_HARVESTER_MIN_PROTEIN_MERIT, Merit.NEW_HARVESTER_MAX_PROTEIN_MERIT);
        double closeEnemyMerit = pathing.entitiesWithinDistance(tuple.target(), 2).stream()
                .filter(Entity::enemy)
                .count() * Merit.NEW_HARVESTER_PROTEIN_CLOSE_TO_ENEMY;
        double buildMerit = harvesterMerit + proteinMerit + closeEnemyMerit;
//        debug(String.format("%.2f merit building harvester on %s %s", buildMerit, tuple.target().getType(), tuple.buildableTile()));
//        debug(String.format("\t %.2f from lack of harvesters", harvesterMerit));
//        debug(String.format("\t %.2f from lack of protein", proteinMerit));
        return new MeritResult(tuple.mine(), tuple.buildableTile().directionTo(tuple.target()), tuple.buildableTile(), buildMerit);
    }

    private double getRootMeritFromNearbyTile(Entity source, Entity closeByEntity) {
        int distance = pathing.distance(source, closeByEntity);
        List<Double> meritByDistance = Merit.NEW_ROOT_MERIT_FROM_PROTEIN_BY_DISTANCE;
        double meritImpact = 0;
        if (closeByEntity.getType().isProtein() && distance < meritByDistance.size()) {
            meritImpact += meritByDistance.get(distance);
        } else if (closeByEntity.mine()) {
            meritImpact += Merit.NEW_ROOT_MERIT_FROM_FRIENDLY_WITHIN_THREE_DISTANCE;
        }
        return meritImpact;
    }

    private boolean shouldConsiderNewRoot(boolean buildingSporer) {
        // Allow 1 more resource for sporer compared to root, otherwise we look dumb creating a sporer and not following up with a root
        int MIN_EXPAND_PROTEIN = 3 - (buildingSporer ? 0 : 1);
        return Stream.of(myA, myB, myC, myD).allMatch(integer -> integer >= MIN_EXPAND_PROTEIN);
    }

    private double calculateAttackMerit(Entity newTentacle, Direction buildDirection) {
        // Points for pointing at nearby enemies where the next direction is our attack direction
        // Points for enemies that are 1 or 2 spaces away?
        return pathing.entitiesWithinDistance(newTentacle, 3).stream()
                .filter(Entity::enemy)
                .mapToDouble(protein -> getNearbyEnemyAttackMerit(newTentacle, buildDirection, protein))
                .sum() + getLocationAttackMerit(newTentacle);
    }

    private double getNearbyEnemyAttackMerit(Entity newTentacle, Direction buildDirection, Entity enemy) {
        PathInfo pathInfo = pathing.pathInfo(newTentacle, enemy);
        if (pathInfo == null) {
            return 0;
        }
        List<Double> enemyDistanceMerits = Merit.NEW_ATTACKER_DISTANCE_FROM_ENEMY_MERIT;
        int distance = pathInfo.distance();

        Entity nextEntity = newTentacle.entityInDirection(buildDirection);
        if (nextEntity == null) {
            return 0;
        }

        boolean goingInRightDirection;
        boolean kill = false;
        if(nextEntity == enemy) {
            goingInRightDirection = true;
            kill = true;
        } else {
            PathInfo nextPathInfo = pathing.pathInfo(nextEntity, enemy);
            if (nextPathInfo == null) {
                return 0;
            }
            goingInRightDirection = nextPathInfo.distance() < pathInfo.distance();
        }

        double closeToEnemyMerit = goingInRightDirection ? enemyDistanceMerits.get(distance - 1) : 0;
        double killParentMerit = kill ? Merit.NEW_ATTACKER_PARENT_KILL_MERIT : 0;
        double killChildMerit = kill ? enemy.getChildren().size() * Merit.NEW_ATTACKER_CHILD_KILL_MERIT : 0;
        debug(String.format("Attack merits for %s %s   %.2f  %.2f  %.2f attacking %s", newTentacle, buildDirection, closeToEnemyMerit, killParentMerit, killChildMerit, enemy));
        return closeToEnemyMerit + killParentMerit + killChildMerit;
    }

    private double getLocationAttackMerit(Entity newTentacle) {
        Entity myNeighborAttackingNewTentacle = newTentacle.myNeighbor(entity -> entity.getType().equals(EntityType.TENTACLE) && entity.entityInFront() == newTentacle);
        return (newTentacle.getType().isProtein() ? Merit.NEW_ATTACKER_BUILD_ON_PROTEIN_MERIT : 0)
                + (myNeighborAttackingNewTentacle != null ? Merit.NEW_ATTACKER_TILE_CONTROL_MERIT : 0)
                ;
    }

    private List<Behavior> silverLeagueBehaviors() {
        return Arrays.asList(new Behavior[]{
                new AttackBehavior(),
                new CreateNewRootBehavior(),
                new CreateSporerBehavior(),
                new BuildHarvesterBehavior(),
                new ExpandToSpaceBehavior(),
                new WaitBehavior(),
        });
    }

    private void newTurn() {
        turn++;
        timer.start("Turn " + turn);
        debug("Start of turn " + turn);
        myRoots.clear();
        entitiesById.clear();
        rootIdToDescendents.clear();
        grid.getEntitySet().forEach(Entity::reset);
        grid.getProteins().clear();
        buildRootMeritMap.clear();
        expandMeritMap.clear();
        entitiesChangedFromLastTurn.clear();
        myHarvesterCountMap.clear();
        enemyHarvesterCountMap.clear();
    }

    private void postTurnLoad() {
        timer.start("Post Turn Load");
        // Give parents their children
        entitiesById.values().forEach(entity -> entitiesById.get(entity.getParentId()).getChildren().add(entity));

        myRoots.sort(Comparator.comparingInt(Entity::getId));

        // Process changed entities - this will miss entities that were destroyed for now. I don't think they are needed yet.
        // A() is the old value, B() the new
        // Reprocess pathing for all changes in walls and their neighbors within n tiles
        // If we start hitting processing timeouts, we can modify this behavior to reprocess fewer tiles or set a limit on search distance
        int DISTANCE_TO_TO_REPROCESS = 3;
        entitiesChangedFromLastTurn.stream()
                .filter(tuple -> tuple.b().getType().equals(EntityType.WALL))
                .map(Tuple2::b)
                .flatMap(entity -> pathing.entitiesWithinDistance(entity, DISTANCE_TO_TO_REPROCESS).stream())
                .distinct()
//                .peek(entity -> debug("Reprocessing pathing for " + entity))
                .forEach(entity -> pathing.generatePaths(entity));

        myHarvesterCountMap = grid.myEntitiesStream()
                .filter(entity -> entity.getType().equals(EntityType.HARVESTER))
                .map(Entity::entityInFront)
                .filter(entity -> entity != null && entity.getType().isProtein())
                .distinct()
                .map(Entity::getType)
                .collect(Collectors.groupingBy(w -> w, Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        enemyHarvesterCountMap = grid.getEntitySet().stream()
                .filter(Entity::enemy)
                .filter(entity -> entity.getType().equals(EntityType.HARVESTER))
                .map(Entity::entityInFront)
                .filter(entity -> entity != null && entity.getType().isProtein())
                .distinct()
                .map(Entity::getType)
                .collect(Collectors.groupingBy(w -> w, Collectors.collectingAndThen(Collectors.counting(), Long::intValue)));

        List<EntityType> proteins = Arrays.asList(EntityType.A, EntityType.B, EntityType.C, EntityType.D);
        proteins.forEach(protein -> {
            myHarvesterCountMap.putIfAbsent(protein, 0);
            enemyHarvesterCountMap.putIfAbsent(protein, 0);
        });

        timer.end("Post Turn Load");
    }

    private void start() {
        Scanner in = new Scanner(System.in);
        int width = in.nextInt(); // columns in the game grid
        int height = in.nextInt(); // rows in the game grid

        grid = new Grid(width, height);
        pathing = new Pathing(10);

        // game loop
        while (true) {
            int entityCount = in.nextInt();
            newTurn();
            timer.start("Reading Entities");
            for (int i = 0; i < entityCount; i++) {
                int x = in.nextInt();
                int y = in.nextInt(); // grid coordinate
                String type = in.next(); // WALL, ROOT, BASIC, TENTACLE, HARVESTER, SPORER, A, B, C, D
                EntityType entityType = EntityType.valueOf(type);
                Entity entity = grid.entityAt(x, y);
                if (turn > 1 && !entityType.equals(entity.getOldType())) {
                    // Entity changed type between this turn and last, record a copy of change for later processing
                    // The first entry is a copy (the old value), second is the current value
                    entitiesChangedFromLastTurn.add(new Tuple2<>(new Entity(entity), entity));
                }
                entity.setType(entityType);
                if (entityType.isProtein()) {
                    grid.getProteins().add(entity);
                }
                entity.setOwner(in.nextInt()); // 1 if your organ, 0 if target organ, -1 if neither
                if (entityType.equals(EntityType.ROOT)) {
                    if (entity.mine()) {
                        myRoots.add(entity);
                    }
                }
                entity.setId(in.nextInt()); // id of this entity if it's an organ, 0 otherwise
                String organDir = in.next(); // N,E,S,W or X if not an organ
                if (!"X".equals(organDir)) {
                    entity.setDirection(Direction.valueOf(organDir));
                }
                entity.setParentId(in.nextInt());
                entity.setRootId(in.nextInt());
                entitiesById.put(entity.getId(), entity);
                rootIdToDescendents.computeIfAbsent(entity.getRootId(), k -> new ArrayList<>()).add(entity);
            }
            timer.end("Reading Entities");
            myA = in.nextInt();
            myB = in.nextInt();
            myC = in.nextInt();
            myD = in.nextInt();
            enemyA = in.nextInt();
            enemyB = in.nextInt();
            enemyC = in.nextInt();
            enemyD = in.nextInt();

            int requiredActionsCount = in.nextInt(); // your number of organisms, output an action for each one in any order

            postTurnLoad();

            if (turn == 1) {
                pathing.generatePaths();
                behaviors.addAll(silverLeagueBehaviors());
            }

            List<String> commandText = getCommands(requiredActionsCount).stream()
                    .map(Command::getText)
                    .toList();
            timer.end("Turn " + turn);
            commandText.forEach(System.out::println);
            System.out.flush();
        }
    }

    enum DebugCategory {
        GENERAL,
        TIMER
    }

    Map<DebugCategory, Boolean> debugCategoryMap = Map.of(
            DebugCategory.GENERAL, false,
            DebugCategory.TIMER, false
    );

    private void debug(Object message) {
        debug(message, DebugCategory.GENERAL);
    }

    private void debug(Object message, DebugCategory category) {
        boolean print = debugCategoryMap.get(category);
        if (print) {
            if (currentRootId != null) {
                message = "[" + currentRootId + "] " + message;
            }
            System.err.println(message.toString());
        }
    }

    public static void myAssert(boolean b, String message) {
        if (!b) {
            throw new RuntimeException(message);
        }
    }

    private class Timer {
        Map<Object, LocalDateTime> startTimeMap = new HashMap<>();

        void start(Object obj) {
            startTimeMap.put(obj, LocalDateTime.now());
        }

        void end(Object obj) {
            // Return number of milliseconds
            LocalDateTime startTime = startTimeMap.remove(obj);
            LocalDateTime endTime = LocalDateTime.now();
            myAssert(startTime != null, "Start time for timer " + obj + " is missing");
            long millis = ChronoUnit.MILLIS.between(startTime, endTime);
            debug(String.format("[%sms] Timer %s", millis, obj), DebugCategory.TIMER);
        }
    }

    public static void main(String[] args) {
        Player player = new Player();
        player.start();
    }
}