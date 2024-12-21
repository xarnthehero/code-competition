import java.util.*;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Grow and multiply your organisms to end up larger than your opponent.
 **/
class Player {

    private Grid grid;
    private HashMap<Integer, Entity> entitiesById = new HashMap<>();
    private Entity myRoot;
    private List<Entity> myRoots = new ArrayList<>();
    private Entity enemyRoot;
    private int myA;
    private int myB;
    private int myC;
    private int myD;
    private int enemyA;
    private int enemyB;
    private int enemyC;
    private int enemyD;
    private HashMap<Entity, Integer> distanceToMyRoot;
    private int turn = 0;
    private final List<Behavior> behaviors = new ArrayList<>();

    private Random random = new Random();

    private class Grid {
        private final List<List<Entity>> entities;
        private final Set<Entity> entitySet;
        private final int width, height;

        public Grid(int width, int height) {
            this.width = width;
            this.height = height;
            this.entities = new ArrayList<>(height);
            for(int y = 0; y < height; y++) {
                List<Entity> row = new ArrayList<>(width);
                for(int x = 0; x < width; x++) {
                    row.add(new Entity(x, y));
                }
                entities.add(row);
            }
            for(int y = 0; y < height; y++) {
                for(int x = 0; x < width; x++) {
                    Entity entity = entityAt(x, y);
                    if(x > 0) {
                        Entity left = entityAt(x - 1, y);
                        left.setRight(entity);
                        entity.setLeft(left);
                    }
                    if(y > 0) {
                        Entity up = entityAt(x, y - 1);
                        up.setDown(entity);
                        entity.setUp(up);
                    }
                }
            }
            entitySet = entities.stream().flatMap(Collection::stream).collect(Collectors.toSet());
        }

        public Set<Entity> getEntitySet() {
            return entitySet;
        }

        public Set<Entity> myEntitys() {
            return entitySet.stream().filter(Entity::mine).collect(Collectors.toSet());
        }

        public Entity entityAt(int x, int y) {
            if(x >= 0 && x < width && y >= 0 && y < height) {
                return entities.get(y).get(x);
            }
            return null;
        }

        public Entity closestToRoot(Predicate<Entity> predicate) {
            return entitySet.stream().filter(predicate).min(Comparator.comparing(entity -> distanceToMyRoot.get(entity))).orElse(null);
        }

        public Stream<Entity> adjacentToMine(int rootId) {
            return myEntitys().stream()
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

        public static List<Entity> entitysBetween(Entity t1, Entity t2) {
            List<Entity> entitys = new ArrayList<>();
            if(t1.getX() != t2.getX() && t1.getY() != t2.getY()) {
                throw new IllegalArgumentException(String.format("Entitys %s and %s are not in a line", t1, t2));
            } else if(t1.getX() == t2.getX() && t1.getY() == t2.getY()) {
                return entitys;
            }
            if(t1.getX() != t2.getX()) {
                Entity leftEntity = t1.getX() < t2.getX() ? t1 : t2;
                Entity rightEntity = leftEntity == t1 ? t2 : t1;
                while(leftEntity != rightEntity) {
                    Entity next = leftEntity.getRight();
                    entitys.add(next);
                    leftEntity = next;
                }
            } else {
                Entity topEntity = t1.getY() < t2.getY() ? t1 : t2;
                Entity botEntity = topEntity == t1 ? t2 : t1;
                while(topEntity != botEntity) {
                    Entity next = topEntity.getDown();
                    entitys.add(next);
                    topEntity = next;
                }
            }
            return entitys;
        }
    }

    private enum Direction {
        N, S, E, W
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
            this.cost = new int[] {a, b, c, d};
        }

        private static final List<EntityType> PROTEIN_TYPES = Arrays.asList(A, B, C, D);
        public boolean isProtein() {
            return PROTEIN_TYPES.contains(this);
        }

        public int[] getCost() {
            return cost;
        }
    }

    private static class Entity {
        private int id, parentId, rootId;
        private int x;
        private int y;
        private Entity up, down, left, right;
        private EntityType type;
        private int owner;           // 1 for me, 2 for enemy, 0 for no one
        private Direction direction;

        public Entity(int x, int y) {
            this.x = x;
            this.y = y;
            type = EntityType.EMPTY;
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

        public int getX() {
            return x;
        }

        public void setX(int x) {
            this.x = x;
        }

        public int getY() {
            return y;
        }

        public void setY(int y) {
            this.y = y;
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

        public Direction getDirection() {
            return direction;
        }

        public void setDirection(Direction direction) {
            this.direction = direction;
        }

        public boolean mine() {
            return 1 == owner;
        }

        public boolean enemies() {
            return -1 == owner;
        }

        public boolean unowned() {
            return 0 == owner;
        }

        public List<Entity> neighbors() {
            return neighborsStream().collect(Collectors.toList());
        }

        public Stream<Entity> neighborsStream() {
            return Stream.of(up, down, left, right).filter(Objects::nonNull);
        }

        public Entity myNeighbor(Predicate<Entity> predicate) {
            return neighborsStream().filter(Entity::mine).filter(predicate).findFirst().orElse(null);
        }

        public boolean isEmpty() {
            return type.equals(EntityType.EMPTY);
        }

        public boolean isAdjacentTo(Predicate<Entity> predicate) {
            return neighborsStream().anyMatch(predicate);
        }

        public boolean pointedAt(Entity other) {
            if(direction == null) {
                return false;
            }
            return switch(direction) {
                case N -> x == other.getX() && y > other.getY();
                case S -> x == other.getX() && y < other.getY();
                case E -> x < other.getX() && y == other.getY();
                case W -> x > other.getX() && y == other.getY();
            };
        }

        public Direction directionTo(Entity other) {
            int xDiff = other.getX() - x;
            if(xDiff != 0) {
                return xDiff > 0 ? Direction.E : Direction.W;
            }
            return other.getY() - y > 0 ? Direction.S : Direction.N;
        }

        @Override
        public String toString() {
            return String.format("[Entity %s,%s  %s]", x, y, type);
        }
    }

    private interface Behavior {
        // Returns null if there isn't a good command for this behavior
        Command getCommand(int rootId);
    }

    private class CreateSporerBehavior implements Behavior {
        @Override
        public Command getCommand(int rootId) {
            if(
                    canBuild(EntityType.SPORER) &&
                    grid.myEntitys().stream().noneMatch(entity -> entity.getRootId() == rootId && entity.getType().equals(EntityType.SPORER))
            ) {
                // No sporer for this root id, build 1
                Entity potentialSporer = grid.adjacentToMine(rootId)
                        .filter(Entity::isEmpty)
                        .min(distanceToComparator(grid.midPoint()))
                        .orElse(null);
                if(potentialSporer != null) {
                    debug(this + " No tile to spawn sporer");
                }
                Entity myEntity = potentialSporer.myNeighbor(entity -> entity.getRootId() == rootId);
                Direction randomDirection = Direction.values()[random.nextInt(Direction.values().length)];
                return new GrowCommand(myEntity, potentialSporer, EntityType.SPORER, randomDirection);
            }
            return null;
        }
        public String toString() {
            return "[Create Sporer Behavior]";
        }
    }

    private class ConsumeProteinBehavior implements Behavior {
        @Override
        public Command getCommand(int rootId) {
            Stream<EntityType> proteinStream = Stream.of(EntityType.A, EntityType.B, EntityType.C, EntityType.D);
            Entity buildOnProtein = proteinStream
                    .filter(entityType -> Player.this.getProteinCount(entityType) == 0)
                    .map(entityType -> grid.getEntitySet().stream()
                            .filter(entity -> entity.getType().equals(entityType))
                            .filter(entity -> entity.isAdjacentTo(Entity::mine))
                            .map(entity -> entity.neighborsStream().filter(Entity::mine).findFirst().orElseThrow())
                            .findAny()
                            .orElse(null)
                    ).filter(Objects::nonNull)
                    .findAny()
                    .orElse(null);
            if(buildOnProtein == null) {
                return null;
            }
            EntityType buildType = getBuildableType();
            if(buildType == null) {
                debug(this + " Can't afford any proteins");
                return null;
            }
            Entity myAdjacentEntity = buildOnProtein.myNeighbor(entity -> entity.getRootId() == rootId);
            return new GrowCommand(myAdjacentEntity, buildOnProtein, buildType, buildType.equals(EntityType.BASIC) ? null : myAdjacentEntity.directionTo(buildOnProtein));
        }
        public String toString() {
            return "[Consume Protein Behavior]";
        }
    }

    private class BuildHarvesterBehavior implements Behavior {
        @Override
        public Command getCommand(int rootId) {
            if(!canBuild(EntityType.HARVESTER)) {
                return null;
            }
            Entity adjacentEmptyEntitiesTouchingProtein = grid
                    .adjacentToMine(rootId)
                    .filter(Entity::isEmpty)
                    .filter(entity -> entity.isAdjacentTo(
                            protein -> protein.getType().isProtein()
                                    && !protein.isAdjacentTo(existingHarvester -> existingHarvester.getType().equals(EntityType.HARVESTER) && existingHarvester.mine())))
                    .findFirst()
                    .orElse(null);
            if(adjacentEmptyEntitiesTouchingProtein == null) {
                return null;
            }
            Entity buildOnProtein = adjacentEmptyEntitiesTouchingProtein.neighborsStream()
                    .filter(entity -> entity.getType().isProtein())
                    .findFirst()
                    .orElseThrow();
            Entity myAdjacentEntity = adjacentEmptyEntitiesTouchingProtein.neighborsStream()
                    .filter(Entity::mine)
                    .findFirst()
                    .orElseThrow();
            return new GrowCommand(myAdjacentEntity, adjacentEmptyEntitiesTouchingProtein, EntityType.HARVESTER, adjacentEmptyEntitiesTouchingProtein.directionTo(buildOnProtein));
        }
        public String toString() {
            return "[Build Harvester Behavior]";
        }
    }

    private class FillRandomSpaceBehavior implements Behavior {
        @Override
        public Command getCommand(int rootId) {
            Entity targetEntity = grid.adjacentToMine(rootId)
                    .filter(entity -> entity.getType().equals(EntityType.EMPTY))
                    .min(distanceToComparator(grid.midPoint())).orElse(null);
            if(targetEntity == null) {
                return null;
            }
            Entity closestOwnedEntity = targetEntity.neighborsStream().filter(Entity::mine).findFirst().orElseThrow(IllegalStateException::new);
            EntityType buildType = getBuildableType();
            if(buildType == null) {
                debug(this + " Can't afford any proteins");
                return null;
            }
            return new GrowCommand(closestOwnedEntity, targetEntity, buildType, buildType.equals(EntityType.BASIC) ? null : closestOwnedEntity.directionTo(targetEntity));
        }

        public String toString() {
            return "[Fill Random Space]";
        }
    }

    private interface Command {
        String getText();
        EntityType getBuildType();
        void updateState();
    }

    private static class WaitCommand implements Command {
        @Override
        public String getText() {
            return "WAIT";
        }

        @Override
        public EntityType getBuildType() {
            return null;
        }

        @Override
        public void updateState() { }
    }

    private record GrowCommand(Entity from, Entity to, EntityType type, Direction direction) implements Command {
        @Override
        public String getText() {
            if(direction != null) {
                return String.format("GROW %s %s %s %s %s", from.getId(), to.getX(), to.getY(), type, direction.name());
            }
            return String.format("GROW %s %s %s %s", from.getId(), to.getX(), to.getY(), type);
        }

        @Override
        public EntityType getBuildType() {
            return type;
        }

        @Override
        public void updateState() {

        }
    }

    private record SporeCommand(Entity from, Entity to) implements Command {
        @Override
        public String getText() {
            return String.format("SPORE %s %s %s", from.getId(), to.getX(), to.getY());
        }

        @Override
        public EntityType getBuildType() {
            return EntityType.ROOT;
        }

        @Override
        public void updateState() {
            to.setType(EntityType.ROOT);
            to.setOwner(1);
        }
    }

    private List<Command> getCommands(int commandsNeeded) {
        debug("Commands needed: " + commandsNeeded);
        List<Command> commands = new ArrayList<>();
        for(int i = 0; i < commandsNeeded; i++) {
            Entity currentRoot = myRoots.get(i);
            Command command = null;
            for(Behavior behavior : behaviors) {
                command = behavior.getCommand(currentRoot.getRootId());
                if(command != null) {
                    debug("Current behavior: " + behavior);
                    break;
                } else {
                    debug("Behavior return no command - " + behavior);
                }
            }
            if(command == null) {
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

    private EntityType getBuildableType() {
        // The entities we can build in order of priority. If we are out of As, we can't build a BASIC, so need to fall back to other types.
        Stream<EntityType> ENTITY_BUILD_TYPES = Stream.of(EntityType.BASIC, EntityType.SPORER, EntityType.TENTACLE, EntityType.HARVESTER);
        return ENTITY_BUILD_TYPES.filter(Player.this::canBuild)
                .findFirst()
                .orElse(null);
    }

    private boolean canBuild(EntityType type) {
        return switch (type) {
            case BASIC -> myA > 0;
            case HARVESTER -> myC > 0 && myD > 0;
            case TENTACLE -> myB > 0 && myC > 0;
            case SPORER -> myB > 0 && myD > 0;
            case ROOT -> myA > 0 && myB > 0 && myC > 0 && myD > 0;
            default -> throw new IllegalArgumentException("Can't build type " + type);
        };
    }

    private int getProteinCount(EntityType type) {
        return switch(type) {
            case A -> myA;
            case B -> myB;
            case C -> myC;
            case D -> myD;
            default -> throw new IllegalArgumentException();
        };
    }

    private void spendProtein(EntityType type) {
        if(type == null) {
            return;
        }
        int[] costs = type.getCost();
        myA -= costs[0];
        myB -= costs[1];
        myC -= costs[2];
        myD -= costs[3];
    }

    private Stream<Entity> filterStream(List<Entity> entitys, Predicate<Entity> predicate) {
        return entitys.stream().filter(Objects::nonNull);
    }

    private List<Entity> filter(List<Entity> entitys, Predicate<Entity> predicate) {
        return entitys.stream().filter(entity -> entity != null && predicate.test(entity)).toList();
    }

    private Comparator<Entity> distanceToComparator(Entity entity) {
        return Comparator.comparing(entity1 -> Math.abs(entity.getX() - entity1.getX()) + Math.abs(entity.getY() - entity1.getY()));
    }

    private void calculateDistances() {
        distanceToMyRoot = new HashMap<>();
        distanceToMyRoot.put(myRoot, 0);
        Queue<Entity> entityQueue = new LinkedList<>(myRoot.neighbors());
        int distance = 1;
        while(!entityQueue.isEmpty()) {
            int size = entityQueue.size();
            for(int i = 0; i < size; i++) {
                Entity entity = entityQueue.poll();
                if(entity == null) {
                    continue;
                }
                if(!distanceToMyRoot.containsKey(entity) && !EntityType.WALL.equals(entity.getType())) {
                    distanceToMyRoot.put(entity, distance);
                    entityQueue.addAll(entity.neighbors());
                }
            }
            distance++;
        }
    }

    private List<Behavior> bronzeLeague() {
        return Arrays.asList(
                new CreateSporerBehavior(),
                new ConsumeProteinBehavior(),
                new BuildHarvesterBehavior(),
                new FillRandomSpaceBehavior()
        );
    }

    private static int[] proteinCost(EntityType type) {
        return switch (type) {
            case BASIC -> new int[] {-1, 0, 0, 0};
            case HARVESTER -> new int[] {0, 0, -1, -1};
            case TENTACLE -> new int[] {0, -1, -1, 0};
            case SPORER -> new int[] {0, -1, 0, -1};
            case ROOT -> new int[] {-1, -1, -1, -1};
            default -> new int[] {0, 0, 0, 0};
        };
    }

    private void start() {
        Scanner in = new Scanner(System.in);
        int width = in.nextInt(); // columns in the game grid
        int height = in.nextInt(); // rows in the game grid

        grid = new Grid(width, height);

        // game loop
        while (true) {
            turn++;
            myRoots.clear();
            entitiesById.clear();
            int entityCount = in.nextInt();
            for (int i = 0; i < entityCount; i++) {
                int x = in.nextInt();
                int y = in.nextInt(); // grid coordinate
                String type = in.next(); // WALL, ROOT, BASIC, TENTACLE, HARVESTER, SPORER, A, B, C, D
                EntityType entityType = EntityType.valueOf(type);
                Entity entity = grid.entityAt(x, y);
                entity.setType(entityType);
                entity.setOwner(in.nextInt()); // 1 if your organ, 0 if enemy organ, -1 if neither
                if(entityType.equals(EntityType.ROOT)) {
                    if(entity.mine()) {
                        myRoot = grid.entityAt(x, y);
                        myRoots.add(entity);
                    } else {
                        enemyRoot = grid.entityAt(x, y);
                    }
                }
                entity.setId(in.nextInt()); // id of this entity if it's an organ, 0 otherwise
                entitiesById.put(entity.getId(), entity);
                String organDir = in.next(); // N,E,S,W or X if not an organ
                if(!"X".equals(organDir)) {
                    entity.setDirection(Direction.valueOf(organDir));
                }
                entity.setParentId(in.nextInt());
                entity.setRootId(in.nextInt());
            }
            myA = in.nextInt();
            myB = in.nextInt();
            myC = in.nextInt();
            myD = in.nextInt(); // your protein stock
            enemyA = in.nextInt();
            enemyB = in.nextInt();
            enemyC = in.nextInt();
            enemyD = in.nextInt(); // opponent's protein stock
            int requiredActionsCount = in.nextInt(); // your number of organisms, output an action for each one in any order

            myRoots.sort(Comparator.comparingInt(Entity::getId));

            if(turn == 1) {
                calculateDistances();
                behaviors.addAll(bronzeLeague());
            }

            for (int i = 0; i < requiredActionsCount; i++) {
                getCommands(requiredActionsCount).stream()
                        .map(Command::getText)
                        .forEach(System.out::println);
            }
        }
    }

    private void debug(String message) {
        boolean debug = true;
        if(debug) {
            System.err.println(message);
        }
    }

    public static void main(String args[]) {
        Player player = new Player();
        player.start();
    }
}