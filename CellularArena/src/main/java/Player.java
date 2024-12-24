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
    private final HashMap<Integer, Entity> entitiesById = new HashMap<>();
    private final HashMap<Integer, List<Entity>> rootIdToDescendents = new HashMap<>();   // All descendents for a given root id
    private final List<Entity> myRoots = new ArrayList<>();
    private final Map<Entity, Double> buildAttractiveness = new HashMap<>();
    private final Map<Integer, AttractivenessResult> nextCreateRootParams = new HashMap<>();
    private final List<Tuple2<Entity, Entity>> entitiesChangedFromLastTurn = new ArrayList<>();
    private Map<EntityType, Long> myHarvesterCountMap = new HashMap<>();                  // The number of harvesters per protein type I have
    private Map<EntityType, Long> enemyHarvesterCountMap = new HashMap<>();               // The number of harvesters per protein type my enemy has
    private int myA;
    private int myB;
    private int myC;
    private int myD;
    private int enemyA;
    private int enemyB;
    private int enemyC;
    private int enemyD;
    private int turn = 0;
    private final List<Behavior> behaviors = new ArrayList<>();

    private class Grid {
        private final List<List<Entity>> entities;
        private final Set<Entity> entitySet;
        private final int width, height;

        public Grid(int width, int height) {
            this.width = width;
            this.height = height;
            this.entities = new ArrayList<>(height);
            for (int y = 0; y < height; y++) {
                List<Entity> row = new ArrayList<>(width);
                for (int x = 0; x < width; x++) {
                    row.add(new Entity(x, y));
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
            entitySet.forEach(entity -> entity.initNeighbors());
        }

        public Set<Entity> getEntitySet() {
            return entitySet;
        }

        public Stream<Entity> myEntitiesStream() {
            return entitySet.stream().filter(Entity::mine);
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

        public Entity midPoint() {
            int midY = entities.size() / 2;
            int midX = entities.get(0).size() / 2;
            return entityAt(midX, midY);
        }

        public List<Entity> rootSpawnLocations(Entity from) {
            List<Entity> possibleSpawn = new ArrayList<>();
            for (Direction direction : Direction.values()) {
                Entity currentTile = from.entityInDirection(direction);
                while (currentTile != null && !currentTile.getType().equals(EntityType.WALL)) {
                    if (currentTile.isBuildable()) {
                        possibleSpawn.add(currentTile);
                    }
                    currentTile = currentTile.entityInDirection(direction);
                }
            }
            return possibleSpawn;
        }
    }

    private class Pathing {

        private Map<Entity, Map<Entity, PathInfo>> paths = new HashMap<>();
        private Map<Entity, List<PathInfo>> sortedPaths = new HashMap<>();

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
            for (Entity entity : grid.getEntitySet()) {
                if (entity.getType().equals(EntityType.WALL)) {
                    continue;
                }
                paths.put(entity, new HashMap<>());
                sortedPaths.put(entity, new ArrayList<>());
                generatePaths(entity);
            }
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
                        queue.addAll(from.neighbors());
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
        public static final Predicate<Entity> ENEMY_ATTACKING = entity -> entity.neighborsStream()
                .anyMatch(enemy -> enemy.enemy() && enemy.getType().equals(EntityType.TENTACLE) && enemy.entityInFront() == entity);
    }

    private class Entity {
        private int id, parentId, rootId;
        private int x;
        private int y;
        private Entity up, down, left, right;
        private EntityType type;
        private EntityType oldType;
        private final List<Entity> children;
        private List<Entity> neighbors;
        private int owner;           // 1 for me, 2 for enemy, 0 for no one
        private Direction direction;

        public Entity(int x, int y) {
            this.x = x;
            this.y = y;
            children = new ArrayList<>();
            reset();
        }

        public Entity(Entity copy) {
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
            return 1 == owner;
        }

        public boolean enemy() {
            return 0 == owner;
        }

        public boolean unowned() {
            return -1 == owner;
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

        public Entity myNeighbor() {
            return myNeighbor(tile -> true);
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
            return isEmpty() || type.isProtein();
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
            if (!canBuild(EntityType.TENTACLE)) {
                return null;
            }
            List<BuildEntityTuple> possibleAttacks = getPossibleBuildsWithTarget(rootId, Entity::enemy)
                    .stream().filter(buildEntityTuple -> !EntityPredicates.ENEMY_ATTACKING.test(buildEntityTuple.buildableTile())).toList();
            if (possibleAttacks.isEmpty()) {
                return null;
            }
            BuildEntityTuple ggNoob = possibleAttacks.stream().max(Comparator.comparingInt(buildEntityTuple -> buildEntityTuple.target().descendentCount())).get();
            return new GrowCommand(ggNoob.mine(), ggNoob.buildableTile(), EntityType.TENTACLE, ggNoob.buildableTile().directionTo(ggNoob.target()));
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
            if (!canBuild(EntityType.SPORER) || !shouldConsiderNewRoot()) {
                return null;
            }
//            empty spaces -> tuple with space to direction -> space/direction/entitiesInFront -> flatmap space/direction/entityInFront
//                sort by attractiveness
            double ATTRACTIVENESS_PER_DISTANCE = .5;
            Function<Entity, Stream<AttractivenessResult>> entityToDirectionTupleMapper = entity -> Arrays.stream(Direction.values()).map(direction -> new AttractivenessResult(entity, direction, null, null));
            AttractivenessResult mostAttractive = grid.adjacentToMine(rootId)
                    .filter(Entity::isEmpty)
                    .flatMap(entityToDirectionTupleMapper)
                    .flatMap(result -> result.from().entitiesInFront(result.direction()).map(potentialRootTile -> new AttractivenessResult(result.from(), result.direction(), potentialRootTile, null)))
                    .map(result -> {
                        double buildLocationAttractiveness = calculateRootAttractiveness(result.to());
                        double totalAttractiveness = buildLocationAttractiveness + ATTRACTIVENESS_PER_DISTANCE * pathing.distance(result.from(), result.to());
                        return new AttractivenessResult(result.from(), result.direction(), result.to(), totalAttractiveness);
                    })
//                    .peek(result -> debug(String.format("%s [%s] from %s %s to %s", this, result.attractiveness(), result.from(), result.direction(), result.to())))
                    .max(Comparator.comparingDouble(AttractivenessResult::attractiveness))
                    .orElse(null);

            debug(String.format("%s Most attractive: %s", this, mostAttractive));
            if (mostAttractive != null && shouldExpand(mostAttractive.attractiveness())) {
                // Store what we found here to prevent calculations next turn, assume it is still good. Will modify this if I observe problems with using the cached value.
                nextCreateRootParams.put(rootId, mostAttractive);
                Entity buildFrom = mostAttractive.from().myNeighbor();
                Entity buildTo = mostAttractive.from();
                Direction direction = mostAttractive.from().directionTo(mostAttractive.to());
                return new GrowCommand(buildFrom, buildTo, EntityType.SPORER, direction);
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
            AttractivenessResult nextRoot = nextCreateRootParams.get(rootId);
            if (nextRoot == null || !canBuild(Player.EntityType.ROOT)) {
                return null;
            }
            if (!nextRoot.to().isBuildable()) {
                debug(String.format("%s Wanted to build new ROOT but %s was built on last turn", this, nextRoot.to()));
                return null;
            }
            return new Player.SporeCommand(nextRoot.from(), nextRoot.to());
        }

        public String toString() {
            return "[Create New Root Behavior]";
        }
    }

    /**
     * If we are out of a protein, consume a neighboring protein of that type
     */
    private class ConsumeProteinBehavior implements Player.Behavior {
        @Override
        public Player.Command getCommand(int rootId) {
            Player.EntityType buildType = getArbitraryBuildableType();
            if (buildType == null) {
                debug(this + " Can't afford any proteins");
                return null;
            }
            for (EntityType proteinType : Arrays.asList(Player.EntityType.A, Player.EntityType.B, Player.EntityType.C, Player.EntityType.D)) {
                if (Player.this.getProteinCount(proteinType) != 0) {
                    continue;
                }
                for (Entity protein : grid.getEntitySet().stream().filter(entity -> entity.getType().equals(proteinType)).toList()) {
                    Entity myNeighbor = protein.myNeighbor();
                    if (myNeighbor != null && !EntityPredicates.HARVESTED_BY_ME.test(protein)) {
                        return new Player.GrowCommand(myNeighbor, protein, buildType, buildType.equals(Player.EntityType.BASIC) ? null : myNeighbor.directionTo(protein));
                    }
                }
            }
            return null;
        }

        public String toString() {
            return "[Consume Protein Behavior]";
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
            // Prioritize not building on something we are harvesting, then what is low on protein count.
            Comparator<BuildEntityTuple> notHarvestingComparator = Comparator.comparingInt(value -> EntityPredicates.HARVESTED_BY_ME.test(value.buildableTile()) ? 1 : 0);
            BuildEntityTuple harvesterToBuild = possibleHarvesterBuilds.stream().min(
                    notHarvestingComparator.thenComparingInt(value -> getProteinCount(value.target().getType()))
            ).get();
            return new GrowCommand(harvesterToBuild.mine(), harvesterToBuild.buildableTile(), EntityType.HARVESTER, harvesterToBuild.buildableTile().directionTo(harvesterToBuild.target()));
        }

        public String toString() {
            return "[Build Harvester Behavior]";
        }
    }

    private class FillRandomSpaceBehavior implements Player.Behavior {
        @Override
        public Player.Command getCommand(int rootId) {
            Player.Entity targetEntity = grid.adjacentToMine(rootId)
                    .filter(entity -> entity.getType().equals(Player.EntityType.EMPTY))
                    .min(distanceToComparator(grid.midPoint())).orElse(null);
            if (targetEntity == null) {
                return null;
            }
            Player.Entity closestOwnedEntity = targetEntity.neighborsStream().filter(Player.Entity::mine).findFirst().orElseThrow(IllegalStateException::new);
            Player.EntityType buildType = getArbitraryBuildableType();
            if (buildType == null) {
                debug(this + " Can't afford any proteins");
                return null;
            }
            return new Player.GrowCommand(closestOwnedEntity, targetEntity, buildType, buildType.equals(Player.EntityType.BASIC) ? null : closestOwnedEntity.directionTo(targetEntity));
        }

        public String toString() {
            return "[Fill Random Space]";
        }
    }


    private class ExpandToClosestProteinBehavior implements Player.Behavior {
        @Override
        public Player.Command getCommand(int rootId) {
            // Get all proteins within distance 3
            //
            return null;
        }

        public String toString() {
            return "[Expand To Closest Protein Behavior]";
        }
    }

    private interface Command {
        String getText();

        default Player.EntityType getBuildType() {
            return null;
        }

        // Called when the command is chosen to be executed. Can call ghostEntity() if it is creating a new entity.
        void updateState();

        /**
         * Used when we are creating an entity for next turn. Treat it like it exists so we make good decisions for organisms that
         * still need to make a move.
         */
        default void ghostEntity(Entity from, Entity to, EntityType type, Direction direction) {
            to.setType(type);
            to.setOwner(Owner.ME);
            to.setDirection(direction);
        }
    }

    private record WaitCommand() implements Player.Command {
        @Override
        public String getText() {
            return "WAIT";
        }

        @Override
        public void updateState() {
        }
    }

    private record GrowCommand(Player.Entity from, Player.Entity to, Player.EntityType type,
                               Player.Direction direction) implements Player.Command {
        @Override
        public String getText() {
            if (direction != null) {
                return String.format("GROW %s %s %s %s %s", from.getId(), to.getX(), to.getY(), type, direction.name());
            }
            return String.format("GROW %s %s %s %s", from.getId(), to.getX(), to.getY(), type);
        }

        @Override
        public Player.EntityType getBuildType() {
            return type;
        }

        @Override
        public void updateState() {
            ghostEntity(from, to, type, direction);
        }
    }

    private record SporeCommand(Player.Entity from, Player.Entity to) implements Player.Command {
        @Override
        public String getText() {
            return String.format("SPORE %s %s %s", from.getId(), to.getX(), to.getY());
        }

        @Override
        public Player.EntityType getBuildType() {
            return Player.EntityType.ROOT;
        }

        @Override
        public void updateState() {
            ghostEntity(from, to, EntityType.ROOT, null);
        }
    }

    private List<Command> getCommands(int commandsNeeded) {
        debug("Commands needed: " + commandsNeeded);
        List<Command> commands = new ArrayList<>();
        // Iterate my roots in reverse order so ostensibly further forward organisms act first
        for (int i = commandsNeeded - 1; i >= 0; i--) {
            Entity currentRoot = myRoots.get(i);
            Command command = null;
            for (Behavior behavior : behaviors) {
                command = behavior.getCommand(currentRoot.getRootId());
                if (command != null) {
                    debug("Current behavior: " + behavior);
                    break;
                }
            }
            if (command == null) {
                command = new WaitCommand();
                debug("Falling back to default " + command);
            }
            debug("Executing command " + command);
            spendProtein(command.getBuildType());
            command.updateState();
            commands.add(command);
        }
        return commands;
    }

    private EntityType getArbitraryBuildableType() {
        Stream<EntityType> ENTITY_BUILD_TYPES = Stream.of(EntityType.BASIC, EntityType.SPORER, EntityType.TENTACLE, EntityType.HARVESTER);
        return ENTITY_BUILD_TYPES.max(Comparator.comparingInt(this::buildCount))
                .filter(this::canBuild)
                .orElse(null);
    }

    private record BuildEntityTuple(Entity mine, Entity buildableTile, Entity target) {
    }

    private record AttractivenessResult(Entity from, Direction direction, Entity to, Double attractiveness) {
    }

    public record Tuple2<A, B>(A a, B b) {
    }

    public record Tuple3<A, B, C>(A a, B b, C c) {
    }

    private List<BuildEntityTuple> getPossibleBuildsWithTarget(int rootId, Predicate<Entity> targetPredicate) {
        List<Entity> buildableTiles = grid.adjacentToMine(rootId)
                .filter(Entity::isBuildable)
                .distinct().toList();
        List<BuildEntityTuple> tuples = new ArrayList<>();
        for (Entity emptyTile : buildableTiles) {
            emptyTile.neighborsStream().filter(targetPredicate).forEach(target -> {
                tuples.add(new BuildEntityTuple(emptyTile.myNeighbor(), emptyTile, target));
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
     * Ideally, we create a root that is 2 spaces away from proteins (for harvesting) and far away from everything else.
     */
    private double calculateRootAttractiveness(Entity source) {
        Double result = buildAttractiveness.get(source);
        return Optional.ofNullable(result).orElseGet(() ->
                pathing.entitiesWithinDistance(source, 3)
                        .stream()
                        .reduce(0.0, (attractivenessSum, closeByEntity) -> attractivenessImpact(source, closeByEntity) + attractivenessSum, Double::sum)
        );
    }

    private double attractivenessImpact(Entity source, Entity closeByEntity) {
        double ZERO_FROM_PROTEIN = .5;
        double ONE_FROM_PROTEIN = .25;
        double TWO_FROM_PROTEIN = 1;
        double THREE_FROM_PROTEIN = .5;
        double FRIENDLY_WITHIN_THREE = -2;

        int distance = pathing.distance(source, closeByEntity);
        double attractivenessImpact = 0;
        if (closeByEntity.getType().isProtein()) {
            attractivenessImpact += switch (distance) {
                case 0 -> ZERO_FROM_PROTEIN;
                case 1 -> ONE_FROM_PROTEIN;
                case 2 -> TWO_FROM_PROTEIN;
                case 3 -> THREE_FROM_PROTEIN;
                default -> 0;
            };
        } else if (closeByEntity.mine()) {
            attractivenessImpact += FRIENDLY_WITHIN_THREE;
        }
        return attractivenessImpact;
    }

    private boolean shouldConsiderNewRoot() {
        int MIN_EXPAND_PROTEIN = 3;
        return Stream.of(myA, myB, myC, myD).allMatch(integer -> integer >= MIN_EXPAND_PROTEIN);
    }

    private boolean shouldExpand(double attractiveness) {
        return attractiveness >= 4;
    }

    private Comparator<Entity> distanceToComparator(Entity entity) {
        return Comparator.comparing(entity1 -> Math.abs(entity.getX() - entity1.getX()) + Math.abs(entity.getY() - entity1.getY()));
    }

    private List<Behavior> silverLeagueBehaviors() {
        return Arrays.asList(new Behavior[]{
                new AttackBehavior(),
                new CreateNewRootBehavior(),
                new CreateSporerBehavior(),
                new BuildHarvesterBehavior(),
                new ConsumeProteinBehavior(),
                new FillRandomSpaceBehavior(),
                new ExpandToClosestProteinBehavior(),
        });
    }

    private void newTurn() {
        turn++;
        myRoots.clear();
        entitiesById.clear();
        rootIdToDescendents.clear();
        grid.getEntitySet().forEach(Entity::reset);
        buildAttractiveness.clear();
        entitiesChangedFromLastTurn.clear();
        myHarvesterCountMap.clear();
        enemyHarvesterCountMap.clear();
    }

    private void postTurnLoad() {
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
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()));

        enemyHarvesterCountMap = grid.getEntitySet().stream()
                .filter(Entity::enemy)
                .filter(entity -> entity.getType().equals(EntityType.HARVESTER))
                .map(Entity::entityInFront)
                .filter(entity -> entity != null && entity.getType().isProtein())
                .distinct()
                .map(Entity::getType)
                .collect(Collectors.groupingBy(w -> w, Collectors.counting()));
    }

    private void start() {
        Scanner in = new Scanner(System.in);
        int width = in.nextInt(); // columns in the game grid
        int height = in.nextInt(); // rows in the game grid

        grid = new Grid(width, height);
        pathing = new Pathing();

        // game loop
        while (true) {
            newTurn();
            int entityCount = in.nextInt();
            debug("Start of turn " + turn);
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

            getCommands(requiredActionsCount).stream()
                    .map(Command::getText)
                    .forEach(System.out::println);
        }
    }

    private void debug(String message) {
        boolean debug = true;
        if (debug) {
            System.err.println(message);
        }
    }

    public void myAssert(boolean b, String message) {
        if (!b) {
            throw new RuntimeException(message);
        }
    }

    public static void main(String args[]) {
        Player player = new Player();
        player.start();
    }
}