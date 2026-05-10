import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.SpotLight;
import com.jme3.light.PointLight;
import com.jme3.light.DirectionalLight;
import com.jme3.light.AmbientLight;
import com.jme3.material.Material;
import com.jme3.material.RenderState;
import com.jme3.math.ColorRGBA;
import com.jme3.math.FastMath;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.scene.Geometry;
import com.jme3.scene.Node;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.lang.reflect.Field;

/**
 * TankBattle2D v18.
 * 2D мини-игра карта для SnakeApp / Turbo Snake.
 *
 * Новое в v18:
 * - процедурная генерация арены;
 * - самолёт сверху сбрасывает контейнеры с бонусами;
 * - бонусы: ремонт, щит, скорострельность, две пушки, мина;
 * - X ставит мину;
 * - новый танк SturmTiger: SHIFT — прицеливание, SPACE — выстрел после наведения;
 * - уничтоженный танк оставляет горящий корпус и отлетевшую башню;
 * - локальный цикл день/ночь для карты;
 * - в мультиплеере AI-боты отключены, игроки ждут выбора танков друг друга.
 */
public class TankBattle2D implements SnakeApp.ExternalMapDef {
    private enum Mode { GARAGE, PLAYING, FINISHED }
    private enum Turret { DEFAULT, FIRE, FREEZE, SNIPER, DUAL, STURM }
    private enum PickupKind { SHIELD, REPAIR, RAPID, DUAL_CANNON, MINE }

    private static final float MAP_HALF_X = 42f;
    private static final float MAP_HALF_Z = 31f;
    private static final float TANK_RADIUS = 1.35f;
    private static final float BULLET_RADIUS = 0.34f;
    private static final float WORM_RADIUS = 0.85f;

    private Node world;
    private Node arenaRoot;
    private Node wallRoot;
    private Node tankRoot;
    private Node bulletRoot;
    private Node wormRoot;
    private Node pickupRoot;
    private Node mineRoot;
    private Node airplaneRoot;
    private Node fxRoot;
    private Node wreckRoot;
    private Node trackRoot;
    private Node cloudRoot;
    private Node decorRoot;
    private Node uiRoot;

    private Geometry floorGeo;
    private Geometry aimLine;
    private Material cloudMaterial;
    private Material cloudShadowMaterial;

    private final Random rng = new Random();
    private Random mapRng = new Random(1);
    private int arenaSeed = 1;
    private boolean seedReceived = false;

    private final List<Wall2D> walls = new ArrayList<>();
    private final List<Geometry> tintGeometries = new ArrayList<>();
    private final List<Tank2D> enemyTanks = new ArrayList<>();
    private final List<Bullet2D> bullets = new ArrayList<>();
    private final List<Worm2D> worms = new ArrayList<>();
    private final List<Particle2D> particles = new ArrayList<>();
    private final List<Beam2D> beams = new ArrayList<>();
    private final List<Pickup2D> pickups = new ArrayList<>();
    private final List<Mine2D> mines = new ArrayList<>();
    private final List<Airplane2D> airplanes = new ArrayList<>();
    private final List<ContainerDrop2D> containers = new ArrayList<>();
    private final List<BurningWreck2D> burningWrecks = new ArrayList<>();
    private final List<WreckCollider2D> wreckColliders = new ArrayList<>();
    private final List<TrackMark2D> trackMarks = new ArrayList<>();
    private final Map<Integer, Tank2D> remoteTanks = new HashMap<>();
    private final Map<Integer, Boolean> readyPlayers = new HashMap<>();
    private final Map<Integer, Integer> readyTankModels = new HashMap<>();
    private final Map<Integer, Turret> readyTurrets = new HashMap<>();

    private Tank2D player;
    private Node previewTank;

    private BitmapText titleText;
    private BitmapText helpText;
    private BitmapText statsText;
    private BitmapText turretText;
    private BitmapText centerText;
    private Geometry uiPanel;

    private Mode mode = Mode.GARAGE;
    private Turret selectedTurret = Turret.DEFAULT;
    private int selectedTank = 1;
    private boolean botsEnabled = true;
    private boolean deathTriggered = false;
    private boolean localReady = false;

    private boolean cameraSaved = false;
    private boolean oldParallelProjection = false;
    private float oldFrustumNear = 1f;
    private float oldFrustumFar = 1000f;
    private float oldFrustumLeft = -1f;
    private float oldFrustumRight = 1f;
    private float oldFrustumTop = 1f;
    private float oldFrustumBottom = -1f;

    private static final String[] TANK_NAMES = {
            "Разведчик", "Классический", "Тяжёлый", "Снайпер", "SturmTiger"
    };
    private static final int[] TANK_HP = { 75, 100, 145, 85, 180 };
    private static final float[] TANK_SPEED = { 15.8f, 11.5f, 8.0f, 10.2f, 5.9f };
    private static final float[] TANK_TURN = { 4.1f, 3.3f, 2.35f, 2.75f, 1.75f };

		private final List<SpotLight> previewSpots = new ArrayList<>();
		private final List<PointLight> previewPoints = new ArrayList<>();

    private boolean keyForward;
    private boolean keyBack;
    private boolean keyLeft;
    private boolean keyRight;
    private boolean keyShoot;
    private boolean keyAim;

    private float gameTime = 0f;
    private float enemySpawnTimer = 0f;
    private float wormSpawnTimer = 0f;
    private float pickupSpawnTimer = 0f;
    private float airDropTimer = 5f;
    private float notifyTimer = 0f;
    private float playerShieldTimer = 0f;
    private float rapidTimer = 0f;
    private float dualTimer = 0f;
    private float currentNight = 0f;
    private boolean headlightsActive = false;
    private float sturmAimCharge = 0f;
    private float netSyncTimer = 0f;

    private int mineCharges = 0;
    private int score = 0;
    private int wave = 1;
    private int kills = 0;

    private static final String[] ACTIONS = new String[] {
            "TB2D_FORWARD", "TB2D_BACK", "TB2D_LEFT", "TB2D_RIGHT",
            "TB2D_SHOOT", "TB2D_AIM", "TB2D_MINE", "TB2D_RESTART", "TB2D_START",
            "TB2D_TURRET_PREV", "TB2D_TURRET_NEXT"
    };

		private static final String MAP_MUSIC = "Sounds/MSounds/TankBattle2D/main1.ogg";

    private SnakeApp.MapContext lastCtx;

    private final ActionListener actionListener = (name, isPressed, tpf) -> {
        if ("TB2D_FORWARD".equals(name)) keyForward = isPressed;
        else if ("TB2D_BACK".equals(name)) keyBack = isPressed;
        else if ("TB2D_LEFT".equals(name)) keyLeft = isPressed;
        else if ("TB2D_RIGHT".equals(name)) keyRight = isPressed;
        else if ("TB2D_SHOOT".equals(name)) keyShoot = isPressed;
        else if ("TB2D_AIM".equals(name)) keyAim = isPressed;

        if (!isPressed) return;

        if (mode == Mode.GARAGE) {
            if ("TB2D_START".equals(name) || "TB2D_SHOOT".equals(name)) {
                confirmTankSelection(lastCtx);
            } else if (!localReady && ("TB2D_TURRET_PREV".equals(name) || "TB2D_LEFT".equals(name))) {
                selectedTank = wrap(selectedTank - 1, TANK_NAMES.length);
                rebuildPreviewTank(lastCtx);
                showCenter("Танк: " + TANK_NAMES[selectedTank], 1.0f, new ColorRGBA(1f, 1f, 1f, 1f));
            } else if (!localReady && ("TB2D_TURRET_NEXT".equals(name) || "TB2D_RIGHT".equals(name))) {
                selectedTank = wrap(selectedTank + 1, TANK_NAMES.length);
                rebuildPreviewTank(lastCtx);
                showCenter("Танк: " + TANK_NAMES[selectedTank], 1.0f, new ColorRGBA(1f, 1f, 1f, 1f));
            }
            return;
        }

        if ("TB2D_RESTART".equals(name)) {
            if (mode == Mode.FINISHED) {
                backToGarage(lastCtx);
            } else if (mode == Mode.PLAYING) {
                showCenter("Нельзя вернуться в гараж во время матча", 1.2f, new ColorRGBA(1f, 0.45f, 0.15f, 1f));
            } else {
                backToGarage(lastCtx);
            }
        } else if ("TB2D_MINE".equals(name)) {
            placeMine(lastCtx, player);
        } else if ("TB2D_TURRET_PREV".equals(name)) {
            selectedTurret = Turret.values()[wrap(selectedTurret.ordinal() - 1, Turret.values().length)];
            showCenter("Башня: " + turretName(selectedTurret), 1.2f, turretColor(selectedTurret));
        } else if ("TB2D_TURRET_NEXT".equals(name)) {
            selectedTurret = Turret.values()[wrap(selectedTurret.ordinal() + 1, Turret.values().length)];
            showCenter("Башня: " + turretName(selectedTurret), 1.2f, turretColor(selectedTurret));
        }
    };

    @Override public String id() { return "TankBattle2D"; }
    @Override public String displayName() { return "Tank Battle 2D"; }
    @Override public String previewImage() { return "maps/TankBattle2D.png"; }
    @Override public ColorRGBA accentColor() { return new ColorRGBA(0.25f, 0.95f, 0.35f, 1f); }

    @Override
    public SnakeApp.MapRuntimeSettings settings() {
        SnakeApp.MapRuntimeSettings s = new SnakeApp.MapRuntimeSettings();
        s.mapHalf = 1000f;
        s.mode = "tank-battle-2d";
        s.maxFood = 1;
        s.snakeSpeed = 2f;
        s.turnSpeed = 0.5f;
        s.allowGrowth = false;
        s.enableRegularFood = false;
        s.enableBadFood = false;
        s.enableBallRain = false;
        s.enableRain = true;       // оставляем атмосферу включённой, если SnakeApp использует это для окружения
        s.enableFrozenArena = false;
        s.enableSandstorm = false;
        s.enableBlackCubesDefault = false;
        return s;
    }

    @Override public boolean overridesArena() { return true; }
    @Override public boolean overridesCamera() { return true; }
		@Override public boolean overridesDayNight() { return true; }

    @Override
    public void buildWorld(SnakeApp.MapContext ctx) {
        lastCtx = ctx;
        seedReceived = false;
        arenaSeed = makeLocalSeed(ctx);
        mapRng = new Random(arenaSeed);

        world = new Node("TankBattle2DWorld");
        arenaRoot = new Node("TankBattle2DArena");
        wallRoot = new Node("TankBattle2DWalls");
        tankRoot = new Node("TankBattle2DTanks");
        bulletRoot = new Node("TankBattle2DBullets");
        wormRoot = new Node("TankBattle2DWorms");
        pickupRoot = new Node("TankBattle2DPickups");
        mineRoot = new Node("TankBattle2DMines");
        airplaneRoot = new Node("TankBattle2DAirplanes");
        fxRoot = new Node("TankBattle2DFx");
        wreckRoot = new Node("TankBattle2DWrecks");
        trackRoot = new Node("TankBattle2DTrackMarks");
        cloudRoot = new Node("TankBattle2DClouds");
        decorRoot = new Node("TankBattle2DOutsideDecor");
        uiRoot = new Node("TankBattle2DUI");

        ctx.rootNode.attachChild(world);
        world.attachChild(arenaRoot);
        world.attachChild(wallRoot);
        world.attachChild(tankRoot);
        world.attachChild(bulletRoot);
        world.attachChild(wormRoot);
        world.attachChild(pickupRoot);
        world.attachChild(mineRoot);
        world.attachChild(airplaneRoot);
        world.attachChild(fxRoot);
        world.attachChild(wreckRoot);
        world.attachChild(trackRoot);
        world.attachChild(decorRoot);
        world.attachChild(cloudRoot);
        ctx.guiNode.attachChild(uiRoot);

        buildArena(ctx, arenaSeed);
        mode = Mode.GARAGE;
    }

    @Override
    public void onStart(SnakeApp.MapContext ctx) {
        lastCtx = ctx;
        if (ctx != null) {
            ctx.setStandardSnakesVisible(false);
            ctx.setCoreHudVisible(false);
            if (!ctx.solo && ctx.host) {
                arenaSeed = 100000 + rng.nextInt(900000);
                seedReceived = true;
                broadcast(ctx, "TB2D_SEED|" + arenaSeed);
                buildArena(ctx, arenaSeed);
            }
        }

        localReady = false;
        readyPlayers.clear();
        readyTankModels.clear();
        readyTurrets.clear();

        installInput(ctx);
        buildUi(ctx);
        rebuildPreviewTank(ctx);
        showCenter("Выбери танк и нажми ENTER", 2.5f, new ColorRGBA(0.25f, 0.95f, 0.35f, 1f));
				ctx.setMusic(MAP_MUSIC, 0.5f);
    }

    @Override
    public void update(SnakeApp.MapContext ctx, float tpf) {
        lastCtx = ctx;
        tpf = clamp(tpf, 0f, 0.05f);

				updateUiLayout(ctx);
				if (mode == Mode.PLAYING || mode == Mode.FINISHED) {
						updateDayNight(ctx, tpf);
				}

				if (notifyTimer > 0f) {
						notifyTimer -= tpf;
						if (notifyTimer <= 0f) {
								if (centerText != null) centerText.setText("");
						}
				}

        if (mode == Mode.GARAGE) {
            updateGarage(ctx, tpf);
            updateParticles(tpf);
            updateBeams(tpf);
            updateBurningWrecks(tpf);
            if (localReady && allPlayersReady(ctx)) startBattle(ctx);
            updateUiText();
            return;
        }

        if (mode == Mode.FINISHED) {
            updateParticles(tpf);
            updateBeams(tpf);
            updateBurningWrecks(tpf);
            updateUiText();
            return;
        }

        gameTime += tpf;
        enemySpawnTimer -= tpf;
        wormSpawnTimer -= tpf;
        pickupSpawnTimer -= tpf;
        airDropTimer -= tpf;

        if (playerShieldTimer > 0f) playerShieldTimer -= tpf;
        if (rapidTimer > 0f) rapidTimer -= tpf;
        if (dualTimer > 0f) dualTimer -= tpf;

        updatePlayer(ctx, tpf);
        syncMyTank(ctx, tpf);
        updateEnemyTanks(ctx, tpf);
        // v13: оранжевые атакующие змейки полностью отключены.
        updateBullets(ctx, tpf);
        updatePickups(ctx, tpf);
        updateMines(ctx, tpf);
        updateAirplanesAndDrops(ctx, tpf);
        updateTrackMarks(tpf);
        updateClouds(tpf);
        updateParticles(tpf);
        updateBeams(tpf);
        updateBurningWrecks(tpf);
        updateSpawns(ctx);

        updateUiText();
    }

    @Override
    public void updateCamera(SnakeApp.MapContext ctx, float tpf) {
        if (ctx == null || ctx.camera == null) return;
        saveCameraState(ctx);
        float aspect = ctx.camera.getWidth() / Math.max(1f, (float)ctx.camera.getHeight());
        float halfH = 33.5f;
        float halfW = halfH * aspect;
        ctx.camera.setParallelProjection(true);
        ctx.camera.setFrustum(1f, 220f, -halfW, halfW, halfH, -halfH);
        ctx.camera.setLocation(new Vector3f(0f, 90f, 0f));
        ctx.camera.lookAt(new Vector3f(0f, 0f, 0f), new Vector3f(0f, 0f, -1f));
    }

    @Override
    public void cleanup(SnakeApp.MapContext ctx) {
        if (ctx != null) {
            ctx.setStandardSnakesVisible(true);
            ctx.setCoreHudVisible(true);
            restoreCameraState(ctx);
        }
				ctx.setMusic("Sounds/theme/main1.ogg", 0.5f);
        uninstallInput(ctx);
        removeAllAirplaneLights();
        removeAllTankLights();
        clearNode(world);
        clearNode(uiRoot);
        if (world != null) world.removeFromParent();
        if (uiRoot != null) uiRoot.removeFromParent();

        walls.clear(); tintGeometries.clear(); enemyTanks.clear(); bullets.clear(); worms.clear();
        particles.clear(); beams.clear(); pickups.clear(); mines.clear(); airplanes.clear(); containers.clear();
        burningWrecks.clear(); wreckColliders.clear(); trackMarks.clear(); remoteTanks.clear();
    }

    private void installInput(SnakeApp.MapContext ctx) {
        if (ctx == null || ctx.inputManager == null) return;
        try {
            addMap(ctx, "TB2D_FORWARD", KeyInput.KEY_W, KeyInput.KEY_UP);
            addMap(ctx, "TB2D_BACK", KeyInput.KEY_S, KeyInput.KEY_DOWN);
            addMap(ctx, "TB2D_LEFT", KeyInput.KEY_A, KeyInput.KEY_LEFT);
            addMap(ctx, "TB2D_RIGHT", KeyInput.KEY_D, KeyInput.KEY_RIGHT);
            addMap(ctx, "TB2D_SHOOT", KeyInput.KEY_SPACE);
            addMap(ctx, "TB2D_AIM", KeyInput.KEY_LSHIFT, KeyInput.KEY_RSHIFT);
            addMap(ctx, "TB2D_MINE", KeyInput.KEY_X);
            addMap(ctx, "TB2D_RESTART", KeyInput.KEY_R);
            addMap(ctx, "TB2D_START", KeyInput.KEY_RETURN);
            addMap(ctx, "TB2D_TURRET_PREV", KeyInput.KEY_Q);
            addMap(ctx, "TB2D_TURRET_NEXT", KeyInput.KEY_E);
            ctx.inputManager.addListener(actionListener, ACTIONS);
        } catch (Exception ignored) {}
    }

    private void uninstallInput(SnakeApp.MapContext ctx) {
        if (ctx == null || ctx.inputManager == null) return;
        try { ctx.inputManager.removeListener(actionListener); } catch (Exception ignored) {}
        for (String a : ACTIONS) {
            try { if (ctx.inputManager.hasMapping(a)) ctx.inputManager.deleteMapping(a); } catch (Exception ignored) {}
        }
    }

    private void addMap(SnakeApp.MapContext ctx, String name, int key) {
        if (ctx.inputManager.hasMapping(name)) ctx.inputManager.deleteMapping(name);
        ctx.inputManager.addMapping(name, new KeyTrigger(key));
    }

    private void addMap(SnakeApp.MapContext ctx, String name, int keyA, int keyB) {
        if (ctx.inputManager.hasMapping(name)) ctx.inputManager.deleteMapping(name);
        ctx.inputManager.addMapping(name, new KeyTrigger(keyA), new KeyTrigger(keyB));
    }

		private void updateCloudBrightness(float brightness) {
				if (cloudMaterial == null) return;
				float b = clamp(brightness, 0f, 1f);
				setMatColor(cloudMaterial, new ColorRGBA(
						0.20f + 0.70f * b,
						0.22f + 0.70f * b,
						0.28f + 0.68f * b,
						0.28f + 0.14f * b
				));
				if (cloudShadowMaterial != null) {
						setMatColor(cloudShadowMaterial, new ColorRGBA(
								0.02f, 0.025f, 0.03f,
								0.10f + 0.12f * b
						));
				}
		}

    private void buildArena(SnakeApp.MapContext ctx, int seed) {
        if (ctx == null) return;
        clearNode(arenaRoot);
        clearNode(wallRoot);
        clearNode(decorRoot);
        clearNode(cloudRoot);
        walls.clear();
        tintGeometries.clear();
        floorGeo = null;
        mapRng = new Random(seed);

				// --- ОСНОВНОЙ ПОЛ: СЕТКА ИЗ МАЛЕНЬКИХ BOX'ОВ ---
				int gridResX = 20;
				int gridResZ = 15;
				float cellW = (MAP_HALF_X * 2f) / gridResX;
				float cellD = (MAP_HALF_Z * 2f) / gridResZ;
				ColorRGBA floorColor = new ColorRGBA(0.12f, 0.18f, 0.11f, 1f);

				for (int ix = 0; ix < gridResX; ix++) {
						for (int iz = 0; iz < gridResZ; iz++) {
								float cx = -MAP_HALF_X + cellW * (ix + 0.5f);
								float cz = -MAP_HALF_Z + cellD * (iz + 0.5f);
								Geometry cell = ctx.addVisualBox(arenaRoot, "FloorCell_" + ix + "_" + iz,
										new Vector3f(cx, -0.05f, cz),
										new Vector3f(cellW * 0.5f, 0.04f, cellD * 0.5f),
										floorColor);
								if (ix == 0 && iz == 0) floorGeo = cell; // для tintGeometries
						}
				}
				tintGeometries.add(floorGeo);

        for (int i = -4; i <= 4; i++) {
            Geometry gx = ctx.addVisualBox(arenaRoot, "GridX" + i, new Vector3f(i * 10f, 0.01f, 0f), new Vector3f(0.035f, 0.02f, MAP_HALF_Z), new ColorRGBA(0.17f, 0.24f, 0.15f, 1f));
            tintGeometries.add(gx);
        }
        for (int i = -3; i <= 3; i++) {
            Geometry gz = ctx.addVisualBox(arenaRoot, "GridZ" + i, new Vector3f(0f, 0.012f, i * 10f), new Vector3f(MAP_HALF_X, 0.02f, 0.035f), new ColorRGBA(0.17f, 0.24f, 0.15f, 1f));
            tintGeometries.add(gz);
        }

        addWall(ctx, "BorderN", 0f, MAP_HALF_Z + 0.5f, MAP_HALF_X + 1f, 0.55f);
        addWall(ctx, "BorderS", 0f, -MAP_HALF_Z - 0.5f, MAP_HALF_X + 1f, 0.55f);
        addWall(ctx, "BorderE", MAP_HALF_X + 0.5f, 0f, 0.55f, MAP_HALF_Z + 1f);
        addWall(ctx, "BorderW", -MAP_HALF_X - 0.5f, 0f, 0.55f, MAP_HALF_Z + 1f);

        // Процедурные стены. Центральная зона и спавны оставлены свободными.
        int count = 11 + Math.abs(seed % 5);
        for (int i = 0; i < count; i++) {
            boolean vertical = mapRng.nextBoolean();
            float hx = vertical ? (0.75f + mapRng.nextFloat() * 0.55f) : (2.8f + mapRng.nextFloat() * 4.7f);
            float hz = vertical ? (2.8f + mapRng.nextFloat() * 4.7f) : (0.75f + mapRng.nextFloat() * 0.55f);
            float x = -31f + mapRng.nextFloat() * 62f;
            float z = -23f + mapRng.nextFloat() * 46f;

            if (reservedZone(x, z, hx, hz)) { i--; continue; }
            addWall(ctx, "ProcWall" + i, x, z, hx, hz);
        }

        ctx.addVisualBox(arenaRoot, "SpawnPlayer", new Vector3f(-31f, 0.03f, 0f), new Vector3f(3f, 0.025f, 3f), new ColorRGBA(0.05f, 0.55f, 0.20f, 1f));
        ctx.addVisualBox(arenaRoot, "SpawnEnemy", new Vector3f(31f, 0.03f, 0f), new Vector3f(3f, 0.025f, 3f), new ColorRGBA(0.55f, 0.10f, 0.08f, 1f));

        buildOutsideDecor(ctx);
        buildClouds(ctx);
    }

    private boolean reservedZone(float x, float z, float hx, float hz) {
        if (Math.abs(x) < 7f && Math.abs(z) < 7f) return true;
        if (Math.abs(x + 31f) < 7f && Math.abs(z) < 7f) return true;
        if (Math.abs(x - 31f) < 7f && Math.abs(z) < 7f) return true;
        for (Wall2D w : walls) {
            if (Math.abs(x - w.x) < hx + w.hx + 2.5f && Math.abs(z - w.z) < hz + w.hz + 2.5f) return true;
        }
        return false;
    }

    private void addWall(SnakeApp.MapContext ctx, String name, float x, float z, float hx, float hz) {
        Geometry g = ctx.addVisualBox(wallRoot, name, new Vector3f(x, 0.35f, z), new Vector3f(hx, 0.36f, hz), new ColorRGBA(0.42f, 0.34f, 0.23f, 1f));
        if (g != null) {
            g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            tintGeometries.add(g);
        }
        Wall2D w = new Wall2D();
        w.x = x; w.z = z; w.hx = hx; w.hz = hz;
        walls.add(w);
    }

    private void backToGarage(SnakeApp.MapContext ctx) {
        clearDynamicNodes();
        mode = Mode.GARAGE;
        keyForward = keyBack = keyLeft = keyRight = keyShoot = keyAim = false;
        deathTriggered = false;
        localReady = false;
        readyPlayers.clear(); readyTankModels.clear(); readyTurrets.clear();
        if (ctx != null && (ctx.solo || ctx.host)) {
            arenaSeed = 100000 + rng.nextInt(900000);
            if (!ctx.solo) broadcast(ctx, "TB2D_SEED|" + arenaSeed);
            buildArena(ctx, arenaSeed);
        }
        rebuildPreviewTank(ctx);
        showCenter("Выбери танк и нажми ENTER", 1.8f, new ColorRGBA(0.25f, 0.95f, 0.35f, 1f));
    }

    private void confirmTankSelection(SnakeApp.MapContext ctx) {
        if (ctx == null || localReady) return;
        selectedTurret = defaultTurretForTank(selectedTank);
        localReady = true;
        readyPlayers.put(ctx.myIndex, true);
        readyTankModels.put(ctx.myIndex, selectedTank);
        readyTurrets.put(ctx.myIndex, selectedTurret);

        if (ctx.solo) {
            startBattle(ctx);
            return;
        }

        broadcast(ctx, "TB2D_READY|" + ctx.myIndex + "|" + selectedTank + "|" + selectedTurret.ordinal());
        showCenter("Танк выбран. Ожидание игроков...", 999f, new ColorRGBA(0.25f, 0.85f, 1f, 1f));
        if (allPlayersReady(ctx)) startBattle(ctx);
    }

    private boolean allPlayersReady(SnakeApp.MapContext ctx) {
        if (ctx == null) return false;
        if (ctx.solo) return localReady;
        int expected = ctx.players != null && !ctx.players.isEmpty() ? ctx.players.size() : 2;
        expected = Math.max(2, expected);
        int ready = 0;
        for (int i = 0; i < expected; i++) {
            if (i == ctx.myIndex) { if (localReady) ready++; }
            else if (Boolean.TRUE.equals(readyPlayers.get(i))) ready++;
        }
        return ready >= expected;
    }

    private int readyCount(SnakeApp.MapContext ctx) {
        if (ctx == null) return 0;
        int expected = ctx.players != null && !ctx.players.isEmpty() ? ctx.players.size() : (ctx.solo ? 1 : 2);
        expected = Math.max(ctx.solo ? 1 : 2, expected);
        int ready = 0;
        for (int i = 0; i < expected; i++) {
            if (i == ctx.myIndex) { if (localReady) ready++; }
            else if (Boolean.TRUE.equals(readyPlayers.get(i))) ready++;
        }
        return ready;
    }

    private void startBattle(SnakeApp.MapContext ctx) {
        if (ctx == null) return;
        clearDynamicNodesKeepArena();
        mode = Mode.PLAYING;
        botsEnabled = ctx.solo;
        deathTriggered = false;
        gameTime = 0f; score = 0; wave = 1; kills = 0;
        playerShieldTimer = 0f; rapidTimer = 0f; dualTimer = 0f; sturmAimCharge = 0f;
        mineCharges = 0; netSyncTimer = 0f; airDropTimer = 5f;
        headlightsActive = false;
        selectedTurret = defaultTurretForTank(selectedTank);

        float spawnX = (ctx.myIndex % 2 == 0) ? -31f : 31f;
        float spawnZ = ctx.myIndex <= 1 ? 0f : (-18f + (ctx.myIndex % 4) * 12f);
        float heading = spawnX < 0f ? 0f : FastMath.PI;
        Vector3f playerSpawn = safeSpawnPoint(spawnX, spawnZ, TANK_RADIUS + 0.7f);
        player = createTank(ctx, "PlayerTank", playerSpawn.x, playerSpawn.z, heading, false, selectedTurret, tankColor(selectedTank), selectedTank, ctx.myIndex);

        if (botsEnabled) {
            spawnEnemyTank(ctx, 31f, 0f, FastMath.PI, Turret.DEFAULT);
            spawnEnemyTank(ctx, 25f, -22f, FastMath.PI * 0.75f, Turret.FIRE);
            // v13: змейки-враги отключены, остаются только танки-боты.
            showCenter("Одиночная игра: AI включены", 2f, new ColorRGBA(0.95f, 0.95f, 0.6f, 1f));
        } else {
            createRemotePlaceholders(ctx);
            broadcast(ctx, "TB2D_JOIN|" + ctx.myIndex + "|" + selectedTank + "|" + selectedTurret.ordinal());
            showCenter("Мультиплеер: ожидание боя", 2f, new ColorRGBA(0.25f, 0.85f, 1f, 1f));
        }

        Vector3f pickupA = safeSpawnPoint(-31f, -22f, 2.0f);
        Vector3f pickupB = safeSpawnPoint(31f, 22f, 2.0f);
        spawnPickup(ctx, PickupKind.SHIELD, pickupA.x, pickupA.z);
        spawnPickup(ctx, PickupKind.REPAIR, pickupB.x, pickupB.z);
        enemySpawnTimer = 10f; wormSpawnTimer = 9999f; pickupSpawnTimer = 14f;
    }

    private void clearDynamicNodes() {
        clearDynamicNodesKeepArena();
        clearNode(wreckRoot);
        burningWrecks.clear();
				previewSpots.clear();
				previewPoints.clear();
    }

    private void clearDynamicNodesKeepArena() {
        removeAllAirplaneLights();
        removeAllTankLights();
        clearNode(trackRoot);
        trackMarks.clear();
        wreckColliders.clear();
        clearNode(tankRoot); clearNode(bulletRoot); clearNode(wormRoot); clearNode(pickupRoot);
        clearNode(mineRoot); clearNode(airplaneRoot); clearNode(fxRoot);
        enemyTanks.clear(); remoteTanks.clear(); bullets.clear(); worms.clear(); pickups.clear();
        mines.clear(); airplanes.clear(); containers.clear(); particles.clear(); beams.clear();
        player = null; previewTank = null; aimLine = null;
    }

		private void rebuildPreviewTank(SnakeApp.MapContext ctx) {
				if (ctx == null || tankRoot == null) return;

				// 1. Удалить старые источники света превью из world
				if (world != null) {
						for (SpotLight sl : previewSpots) world.removeLight(sl);
						for (PointLight pl : previewPoints) world.removeLight(pl);
				}
				previewSpots.clear();
				previewPoints.clear();

				// 2. Убрать старую модель
				if (previewTank != null) {
						previewTank.removeFromParent();
						previewTank = null;
				}

				// 3. Собрать новый превью-танк (фары будут созданы, но не добавлены в world, т.к. headlightsActive = false в гараже)
				Tank2D preview = new Tank2D();
				preview.name = "PreviewTank";
				preview.tankModel = selectedTank;
				preview.turret = defaultTurretForTank(selectedTank);
				preview.maxHp = tankHp(selectedTank);
				preview.hp = preview.maxHp;

				previewTank = buildTankNode(ctx, preview, tankColor(selectedTank));

				// 4. На всякий случай принудительно отключить фары (не должны были добавиться)
				setHeadlightsVisible(preview, false);

				previewTank.setLocalTranslation(0f, 0.35f, 0f);
				previewTank.setLocalScale(selectedTank == 4 ? 1.75f : 2.2f);
				tankRoot.attachChild(previewTank);
		}

    private void updateGarage(SnakeApp.MapContext ctx, float tpf) {
        if (previewTank != null) previewTank.rotate(0f, tpf * 0.85f, 0f);
    }

    private Tank2D createTank(SnakeApp.MapContext ctx, String name, float x, float z, float heading, boolean bot, Turret turret, ColorRGBA color, int tankModel, int ownerIndex) {
        Tank2D t = new Tank2D();
        t.name = name; t.x = x; t.z = z; t.heading = heading; t.bot = bot; t.turret = turret;
        t.color = color; t.tankModel = tankModel; t.ownerIndex = ownerIndex;
        t.moveSpeed = tankSpeed(tankModel); t.turnSpeed = tankTurn(tankModel);
        t.hp = bot ? Math.max(55, tankHp(tankModel) - 20) : tankHp(tankModel);
        t.maxHp = t.hp; t.fireCooldown = 0f; t.frozenTimer = 0f; t.aiTimer = 0f;
        t.aiTargetX = x; t.aiTargetZ = z; t.aiLastX = x; t.aiLastZ = z; t.aiAvoidHeading = heading;
        t.aiAvoidTimer = 0f; t.aiReverseTimer = 0f; t.aiStuckTimer = 0f; t.aiInit = true; t.trackTimer = 0f; t.alive = true;
        t.node = buildTankNode(ctx, t, color);
        t.node.setLocalTranslation(x, 0.3f, z);
        t.node.setLocalRotation(new Quaternion().fromAngleAxis(heading, Vector3f.UNIT_Y));
        tankRoot.attachChild(t.node);
        if (bot) enemyTanks.add(t);
        return t;
    }

    private float tankBodyHalfWidth(int model) {
        return model == 4 ? 1.65f : model == 2 ? 1.28f : model == 0 ? 0.95f : 1.10f;
    }

    private float tankBodyHalfLength(int model) {
        return model == 4 ? 1.95f : model == 2 ? 1.55f : model == 3 ? 1.48f : 1.35f;
    }

    private float tankBarrelHalfLength(int model) {
        return model == 4 ? 1.75f : model == 3 ? 1.25f : 0.82f;
    }

    private float tankBarrelHalfWidth(int model) {
        return model == 4 ? 0.34f : 0.16f;
    }

    private Node buildTankNode(SnakeApp.MapContext ctx, Tank2D tank, ColorRGBA color) {
        Node n = new Node(tank.name == null ? "Tank" : tank.name);
        int model = tank.tankModel;
        float bodyW = tankBodyHalfWidth(model);
        float bodyL = tankBodyHalfLength(model);
        float barrelL = tankBarrelHalfLength(model);
        float barrelW = tankBarrelHalfWidth(model);

        addBox(ctx, n, "Body", new Vector3f(0f, 0.15f, 0f), new Vector3f(bodyW, 0.14f, bodyL), color);
        addBox(ctx, n, "TrackL", new Vector3f(-bodyW + 0.15f, 0.08f, 0f), new Vector3f(0.24f, 0.12f, bodyL + 0.12f), darken(color, 0.35f));
        addBox(ctx, n, "TrackR", new Vector3f(bodyW - 0.15f, 0.08f, 0f), new Vector3f(0.24f, 0.12f, bodyL + 0.12f), darken(color, 0.35f));
        addBox(ctx, n, "Turret", new Vector3f(0f, 0.34f, model == 4 ? 0.18f : 0f), new Vector3f(model == 4 ? 0.82f : 0.58f, 0.13f, model == 4 ? 0.70f : 0.58f), brighten(color, 1.25f));

        if (tank.dualVisual || tank.turret == Turret.DUAL) {
            addBox(ctx, n, "BarrelL", new Vector3f(-0.24f, 0.35f, bodyL * 0.78f), new Vector3f(0.12f, 0.08f, barrelL), new ColorRGBA(0.08f, 0.09f, 0.08f, 1f));
            addBox(ctx, n, "BarrelR", new Vector3f(0.24f, 0.35f, bodyL * 0.78f), new Vector3f(0.12f, 0.08f, barrelL), new ColorRGBA(0.08f, 0.09f, 0.08f, 1f));
        } else {
            addBox(ctx, n, "Barrel", new Vector3f(0f, 0.35f, bodyL * 0.78f), new Vector3f(barrelW, 0.08f, barrelL), new ColorRGBA(0.08f, 0.09f, 0.08f, 1f));
        }

        if (model == 4) {
            addBox(ctx, n, "SturmMantlet", new Vector3f(0f, 0.37f, 1.16f), new Vector3f(0.55f, 0.16f, 0.22f), new ColorRGBA(0.07f, 0.08f, 0.06f, 1f));
            addBox(ctx, n, "GermanMark", new Vector3f(0f, 0.49f, -0.65f), new Vector3f(0.32f, 0.035f, 0.06f), new ColorRGBA(0.95f, 0.95f, 0.88f, 1f));
        }

        // Фары включаются только вечером/ночью через updateDayNight().
        tank.headlightL = addBox(ctx, n, "HeadlightL", new Vector3f(-bodyW * 0.38f, 0.37f, bodyL + 0.10f), new Vector3f(0.12f, 0.045f, 0.08f), new ColorRGBA(1f, 0.95f, 0.62f, 1f));
        tank.headlightR = addBox(ctx, n, "HeadlightR", new Vector3f(bodyW * 0.38f, 0.37f, bodyL + 0.10f), new Vector3f(0.12f, 0.045f, 0.08f), new ColorRGBA(1f, 0.95f, 0.62f, 1f));

        // Никаких фальшивых светлых фигур на полу: только настоящие источники света.
        tank.headlightBeamL = null;
        tank.headlightBeamR = null;
        tank.headSpotL = createHeadlightSpot();
        tank.headSpotR = createHeadlightSpot();
        tank.headPointL = createHeadlightPoint();
        tank.headPointR = createHeadlightPoint();
        setHeadlightsVisible(tank, headlightsActive);

        Geometry hpBg = addBox(ctx, n, "HpBg", new Vector3f(0f, 0.55f, -1.9f), new Vector3f(1.25f, 0.05f, 0.08f), new ColorRGBA(0.05f, 0.05f, 0.05f, 1f));
        Geometry hpBar = addBox(ctx, n, "HpBar", new Vector3f(0f, 0.59f, -1.9f), new Vector3f(1.20f, 0.055f, 0.09f), new ColorRGBA(0.2f, 1f, 0.25f, 1f));
        tank.hpBar = hpBar;
        return n;
    }

    private Geometry addBox(SnakeApp.MapContext ctx, Node parent, String name, Vector3f pos, Vector3f half, ColorRGBA color) {
        Geometry g = new Geometry(name, new Box(half.x, half.y, half.z));
        g.setMaterial(ctx.lit(color));
        g.setLocalTranslation(pos);
        g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        parent.attachChild(g);
        return g;
    }

    private void updatePlayer(SnakeApp.MapContext ctx, float tpf) {
        if (player == null || !player.alive) return;
        player.turret = selectedTurret;
        player.fireCooldown = Math.max(0f, player.fireCooldown - tpf);

        if (selectedTank == 4) updateSturmAim(ctx, tpf);
        else sturmAimCharge = 0f;

        float moveSpeed = rapidTimer > 0f ? player.moveSpeed * 1.35f : player.moveSpeed;
        float turnSpeed = keyAim && selectedTank == 4 ? player.turnSpeed * 0.35f : player.turnSpeed;
        if (keyLeft) player.heading += turnSpeed * tpf;
        if (keyRight) player.heading -= turnSpeed * tpf;

        float move = 0f;
        if (keyForward) move += moveSpeed;
        if (keyBack) move -= moveSpeed * 0.65f;
        if (keyAim && selectedTank == 4) move *= 0.25f;
        if (move != 0f) {
            Vector3f dir = dirFromHeading(player.heading);
            tryMoveTank(player, dir.x * move * tpf, dir.z * move * tpf);
        }
        if (keyShoot) fireBullet(ctx, player);
        updateTankNode(player);
    }

    private void updateSturmAim(SnakeApp.MapContext ctx, float tpf) {
        if (player == null) return;
        if (keyAim && player.fireCooldown <= 0f) {
            sturmAimCharge = Math.min(1.25f, sturmAimCharge + tpf);
            updateAimLine(ctx, sturmAimCharge >= 1.1f);
        } else {
            sturmAimCharge = Math.max(0f, sturmAimCharge - tpf * 2f);
            removeAimLine();
        }
    }

    private void updateAimLine(SnakeApp.MapContext ctx, boolean ready) {
        if (ctx == null || fxRoot == null || player == null) return;
        removeAimLine();
        Vector3f dir = dirFromHeading(player.heading);
        float len = 35f;
        float startX = player.x + dir.x * 2.0f;
        float startZ = player.z + dir.z * 2.0f;
        float midX = startX + dir.x * len * 0.5f;
        float midZ = startZ + dir.z * len * 0.5f;
        aimLine = new Geometry("SturmAimLine", new Box(ready ? 0.16f : 0.08f, 0.025f, len * 0.5f));
        Material m = ctx.unshaded(ready ? new ColorRGBA(1f, 1f, 1f, 0.60f) : new ColorRGBA(1f, 0.25f, 0.10f, 0.35f));
        try { m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha); } catch (Exception ignored) {}
        aimLine.setMaterial(m);
        aimLine.setQueueBucket(RenderQueue.Bucket.Transparent);
        aimLine.setLocalTranslation(midX, 0.72f, midZ);
        aimLine.setLocalRotation(new Quaternion().fromAngleAxis(player.heading, Vector3f.UNIT_Y));
        fxRoot.attachChild(aimLine);
    }

    private void removeAimLine() {
        if (aimLine != null) {
            aimLine.removeFromParent();
            aimLine = null;
        }
    }

    private void updateEnemyTanks(SnakeApp.MapContext ctx, float tpf) {
        for (Tank2D t : enemyTanks) {
            if (!t.alive) continue;
            t.fireCooldown = Math.max(0f, t.fireCooldown - tpf);
            updateBotStuckState(t, tpf);

            if (t.frozenTimer > 0f) {
                t.frozenTimer -= tpf;
                updateTankNode(t);
                continue;
            }

            if (player != null && player.alive) {
                float dx = player.x - t.x, dz = player.z - t.z;
                float dist = (float)Math.sqrt(dx * dx + dz * dz);
                float desired = (float)Math.atan2(dx, dz);

                if (dist > 12f) moveBotAware(t, desired, t.moveSpeed * 0.52f, tpf);
                else if (dist < 7f) moveBotAware(t, desired + FastMath.PI, t.moveSpeed * 0.40f, tpf);
                else {
                    float diff = angleDiff(t.heading, desired);
                    t.heading += clamp(diff, -t.turnSpeed * tpf, t.turnSpeed * tpf);
                }

                float aimDiff = angleDiff(t.heading, desired);
                if (dist < 31f && Math.abs(aimDiff) < 0.23f && hasClearShot(t.x, t.z, player.x, player.z, t.turret == Turret.STURM)) {
                    fireBullet(ctx, t);
                }
            } else updateBotPatrol(t, tpf);

            updateTankNode(t);
        }
    }

    private void updateBotPatrol(Tank2D t, float tpf) {
        t.aiTimer -= tpf;
        if (t.aiTimer <= 0f || distance2D(t.x, t.z, t.aiTargetX, t.aiTargetZ) < 4.0f) pickNewBotPatrolTarget(t);
        float dx = t.aiTargetX - t.x, dz = t.aiTargetZ - t.z;
        float desired = (float)Math.atan2(dx, dz);
        moveBotAware(t, desired, t.moveSpeed * 0.45f, tpf);
    }

    private void updateTankNode(Tank2D t) {
        if (t == null || t.node == null) return;
        t.node.setLocalTranslation(t.x, 0.3f, t.z);
        t.node.setLocalRotation(new Quaternion().fromAngleAxis(t.heading, Vector3f.UNIT_Y));
        if (t.hpBar != null) {
            float ratio = clamp(t.hp / Math.max(1f, (float)t.maxHp), 0f, 1f);
            t.hpBar.setLocalScale(ratio, 1f, 1f);
            Material m = t.hpBar.getMaterial();
            if (m != null) {
                ColorRGBA c = ratio > 0.5f ? new ColorRGBA(0.2f, 1f, 0.25f, 1f) : ratio > 0.25f ? new ColorRGBA(1f, 0.75f, 0.15f, 1f) : new ColorRGBA(1f, 0.12f, 0.08f, 1f);
                setMatColor(m, c);
            }
        }
        setHeadlightsVisible(t, headlightsActive && t.alive);
        if (headlightsActive && t.alive) updateHeadlightSpots(t);
    }

    private SpotLight createHeadlightSpot() {
        SpotLight light = new SpotLight();
        light.setColor(new ColorRGBA(1.0f, 0.92f, 0.58f, 5.2f));
				light.setSpotRange(65f);
				light.setSpotInnerAngle(20f * FastMath.DEG_TO_RAD);
				light.setSpotOuterAngle(55f * FastMath.DEG_TO_RAD);
        return light;
    }

    private PointLight createHeadlightPoint() {
        PointLight light = new PointLight();
        light.setColor(new ColorRGBA(1.0f, 0.86f, 0.48f, 2.8f));
        light.setRadius(10.5f);
        return light;
    }

    private void setHeadlightsVisible(Tank2D t, boolean visible) {
        if (t == null) return;

        com.jme3.scene.Spatial.CullHint hint = visible ? com.jme3.scene.Spatial.CullHint.Inherit : com.jme3.scene.Spatial.CullHint.Always;
        try { if (t.headlightL != null) t.headlightL.setCullHint(hint); } catch (Exception ignored) {}
        try { if (t.headlightR != null) t.headlightR.setCullHint(hint); } catch (Exception ignored) {}
        try { if (t.headlightBeamL != null) t.headlightBeamL.setCullHint(hint); } catch (Exception ignored) {}
        try { if (t.headlightBeamR != null) t.headlightBeamR.setCullHint(hint); } catch (Exception ignored) {}

        if (visible) {
            updateHeadlightSpots(t);
            if (!t.headlightsAttached && world != null) {
                try { if (t.headSpotL != null) world.addLight(t.headSpotL); } catch (Exception ignored) {}
                try { if (t.headSpotR != null) world.addLight(t.headSpotR); } catch (Exception ignored) {}
                try { if (t.headPointL != null) world.addLight(t.headPointL); } catch (Exception ignored) {}
                try { if (t.headPointR != null) world.addLight(t.headPointR); } catch (Exception ignored) {}
                t.headlightsAttached = true;
            }
        } else {
            removeTankLights(t);
        }
    }

    private void updateHeadlightSpots(Tank2D t) {
        if (t == null) return;

        Vector3f dir = dirFromHeading(t.heading);
        Vector3f side = new Vector3f(dir.z, 0f, -dir.x);
        float bodyW = tankBodyHalfWidth(t.tankModel);
        float bodyL = tankBodyHalfLength(t.tankModel);

        Vector3f forward = dir.mult(bodyL + 0.52f);
        Vector3f left = side.mult(-bodyW * 0.38f);
        Vector3f right = side.mult(bodyW * 0.38f);

        // Источник света стоит чуть над фарой и светит вниз-вперёд на пол.
        Vector3f base = new Vector3f(t.x, 1.15f, t.z);
        Vector3f lightDir = new Vector3f(dir.x * 0.85f, -0.25f, dir.z * 0.85f).normalizeLocal(); // луч вперёд под углом ~12° к полу

        Vector3f posL = base.add(forward).add(left);
        Vector3f posR = base.add(forward).add(right);

        if (t.headSpotL != null) {
            t.headSpotL.setPosition(posL);
            t.headSpotL.setDirection(lightDir);
        }
        if (t.headSpotR != null) {
            t.headSpotR.setPosition(posR);
            t.headSpotR.setDirection(lightDir);
        }
        if (t.headPointL != null) t.headPointL.setPosition(posL);
        if (t.headPointR != null) t.headPointR.setPosition(posR);
    }

    private void removeTankLights(Tank2D t) {
        if (t == null || world == null) return;
        try { if (t.headSpotL != null) world.removeLight(t.headSpotL); } catch (Exception ignored) {}
        try { if (t.headSpotR != null) world.removeLight(t.headSpotR); } catch (Exception ignored) {}
        try { if (t.headPointL != null) world.removeLight(t.headPointL); } catch (Exception ignored) {}
        try { if (t.headPointR != null) world.removeLight(t.headPointR); } catch (Exception ignored) {}
        t.headlightsAttached = false;
    }

    private void removeAllTankLights() {
        removeTankLights(player);
        for (Tank2D t : enemyTanks) removeTankLights(t);
        for (Tank2D t : remoteTanks.values()) removeTankLights(t);
    }

    private void tryMoveTank(Tank2D t, float dx, float dz) {
        boolean moved = tryMoveTankResult(t, dx, dz);
        if (moved) spawnTrackMarks(t);
    }

    private boolean tryMoveTankResult(Tank2D t, float dx, float dz) {
        if (t == null) return false;

        float oldX = t.x;
        float oldZ = t.z;
        boolean moved = false;

        float nextX = clamp(t.x + dx, -MAP_HALF_X + TANK_RADIUS, MAP_HALF_X - TANK_RADIUS);
        if (!tankBlockedAt(t, nextX, t.z, TANK_RADIUS)) {
            t.x = nextX;
            moved |= Math.abs(t.x - oldX) > 0.001f;
        }

        float nextZ = clamp(t.z + dz, -MAP_HALF_Z + TANK_RADIUS, MAP_HALF_Z - TANK_RADIUS);
        if (!tankBlockedAt(t, t.x, nextZ, TANK_RADIUS)) {
            t.z = nextZ;
            moved |= Math.abs(t.z - oldZ) > 0.001f;
        }

        return moved;
    }

    private boolean tankBlocked(Tank2D t) {
        return t != null && tankBlockedAt(t, t.x, t.z, TANK_RADIUS);
    }

    private boolean tankBlockedAt(float x, float z, float radius) {
        return tankBlockedAt(null, x, z, radius);
    }

    private boolean tankBlockedAt(Tank2D self, float x, float z, float radius) {
        if (x < -MAP_HALF_X + radius || x > MAP_HALF_X - radius || z < -MAP_HALF_Z + radius || z > MAP_HALF_Z - radius) return true;
        for (Wall2D w : walls) if (circleAabb(x, z, radius, w.x, w.z, w.hx, w.hz)) return true;
        for (WreckCollider2D w : wreckColliders) if (circleAabb(x, z, radius, w.x, w.z, w.hx, w.hz)) return true;

        // v16: живая коллизия танк ↔ танк. Нельзя проехать сквозь игрока, AI или remote-танк.
        float minDist = radius + TANK_RADIUS;
        float minDistSq = minDist * minDist;

        if (player != null && player != self && player.alive && dist2(x, z, player.x, player.z) < minDistSq) return true;

        for (Tank2D other : enemyTanks) {
            if (other != null && other != self && other.alive && dist2(x, z, other.x, other.z) < minDistSq) return true;
        }

        for (Tank2D other : remoteTanks.values()) {
            if (other != null && other != self && other.alive && dist2(x, z, other.x, other.z) < minDistSq) return true;
        }

        return false;
    }

    private void updateBotStuckState(Tank2D t, float tpf) {
        if (t == null) return;
        if (!t.aiInit) {
            t.aiLastX = t.x;
            t.aiLastZ = t.z;
            t.aiInit = true;
            return;
        }
        float moved = distance2D(t.x, t.z, t.aiLastX, t.aiLastZ);
        if (moved < 0.035f) t.aiStuckTimer += tpf;
        else t.aiStuckTimer = Math.max(0f, t.aiStuckTimer - tpf * 2f);
        t.aiLastX = t.x;
        t.aiLastZ = t.z;

        if (t.aiStuckTimer > 0.45f) {
            t.aiReverseTimer = 0.35f + rng.nextFloat() * 0.30f;
            t.aiAvoidTimer = 0.9f + rng.nextFloat() * 0.65f;
            t.aiAvoidHeading = chooseOpenHeading(t, t.heading + FastMath.PI);
            t.aiTimer = 0f;
            t.aiStuckTimer = 0f;
        }
    }

    private void moveBotAware(Tank2D t, float desiredHeading, float speed, float tpf) {
        if (t == null || speed <= 0f) return;

        if (t.aiReverseTimer > 0f) {
            t.aiReverseTimer -= tpf;
            t.heading += clamp(angleDiff(t.heading, t.aiAvoidHeading), -t.turnSpeed * 1.25f * tpf, t.turnSpeed * 1.25f * tpf);
            Vector3f back = dirFromHeading(t.heading).mult(-speed * 0.70f * tpf);
            if (!tryMoveTankResult(t, back.x, back.z)) t.heading += t.turnSpeed * 1.6f * tpf;
            return;
        }

        if (t.aiAvoidTimer > 0f) {
            t.aiAvoidTimer -= tpf;
            desiredHeading = t.aiAvoidHeading;
        } else if (wallAhead(t, desiredHeading, 4.2f)) {
            t.aiAvoidHeading = chooseOpenHeading(t, desiredHeading);
            t.aiAvoidTimer = 0.45f + rng.nextFloat() * 0.45f;
            desiredHeading = t.aiAvoidHeading;
        }

        float diff = angleDiff(t.heading, desiredHeading);
        t.heading += clamp(diff, -t.turnSpeed * tpf, t.turnSpeed * tpf);

        Vector3f dir = dirFromHeading(t.heading);
        boolean moved = tryMoveTankResult(t, dir.x * speed * tpf, dir.z * speed * tpf);
        if (moved) spawnTrackMarks(t);

        if (!moved || wallAhead(t, t.heading, 2.2f)) {
            t.aiAvoidHeading = chooseOpenHeading(t, t.heading + (rng.nextBoolean() ? 1.2f : -1.2f));
            t.aiAvoidTimer = 0.60f + rng.nextFloat() * 0.55f;
            if (!moved) t.aiReverseTimer = 0.18f + rng.nextFloat() * 0.20f;
        }
    }

    private boolean wallAhead(Tank2D t, float heading, float lookAhead) {
        if (t == null) return false;
        Vector3f d = dirFromHeading(heading);
        float r = TANK_RADIUS + 0.22f;
        for (float s = 1.1f; s <= lookAhead; s += 0.95f) {
            if (tankBlockedAt(t, t.x + d.x * s, t.z + d.z * s, r)) return true;
        }
        return false;
    }

    private float chooseOpenHeading(Tank2D t, float desiredHeading) {
        if (t == null) return desiredHeading;
        float[] offsets = new float[] { 0f, 0.38f, -0.38f, 0.72f, -0.72f, 1.08f, -1.08f, 1.52f, -1.52f, FastMath.PI };
        float best = desiredHeading;
        float bestScore = Float.MAX_VALUE;
        for (float off : offsets) {
            float h = desiredHeading + off;
            Vector3f d = dirFromHeading(h);
            float score = Math.abs(off) * 2f;
            for (float s = 1.2f; s <= 7.2f; s += 1.2f) {
                if (tankBlockedAt(t, t.x + d.x * s, t.z + d.z * s, TANK_RADIUS + 0.25f)) {
                    score += (8.5f - s) * 9f;
                    break;
                }
                score -= 1f;
            }
            if (score < bestScore) { bestScore = score; best = h; }
        }
        return best;
    }

    private void pickNewBotPatrolTarget(Tank2D t) {
        if (t == null) return;
        Vector3f p = randomSafeSpawnPoint(TANK_RADIUS + 0.7f);
        t.aiTargetX = p.x;
        t.aiTargetZ = p.z;
        t.aiTimer = 2.4f + rng.nextFloat() * 2.6f;
    }

    private void fireBullet(SnakeApp.MapContext ctx, Tank2D shooter) {
        if (ctx == null || shooter == null || !shooter.alive || shooter.fireCooldown > 0f) return;
        Turret turret = shooter.turret;

        if (turret == Turret.STURM) {
            if (shooter == player) {
                if (!keyAim || sturmAimCharge < 1.1f) {
                    showCenter("SturmTiger: зажми SHIFT для прицеливания", 0.8f, new ColorRGBA(1f, 0.35f, 0.15f, 1f));
                    return;
                }
                sturmAimCharge = 0f;
                removeAimLine();
            }
            shooter.fireCooldown = turretCooldown(turret);
            fireSturmShell(ctx, shooter, dirFromHeading(shooter.heading));
            return;
        }

        float cooldown = turretCooldown(turret);
        if (shooter == player && rapidTimer > 0f) cooldown *= 0.45f;
        shooter.fireCooldown = cooldown;
        Vector3f dir = dirFromHeading(shooter.heading);

        if (turret == Turret.SNIPER) {
            fireSniperBeam(ctx, shooter, dir);
            return;
        }

        if (turret == Turret.DUAL || (shooter == player && dualTimer > 0f)) {
            spawnBullet(ctx, shooter, turret, dir, -0.35f);
            spawnBullet(ctx, shooter, turret, dir, 0.35f);
        } else {
            spawnBullet(ctx, shooter, turret, dir, 0f);
        }
    }

    private void fireSturmShell(SnakeApp.MapContext ctx, Tank2D shooter, Vector3f dir) {
        spawnBullet(ctx, shooter, Turret.STURM, dir, 0f);
        showCenter("STURMTIGER FIRE!", 1.0f, new ColorRGBA(1f, 0.65f, 0.15f, 1f));
    }

    private void spawnBullet(SnakeApp.MapContext ctx, Tank2D shooter, Turret turret, Vector3f dir, float sideOffset) {
        Vector3f side = new Vector3f(dir.z, 0f, -dir.x).mult(sideOffset);
        Bullet2D b = new Bullet2D();
        b.x = shooter.x + dir.x * 1.9f + side.x;
        b.z = shooter.z + dir.z * 1.9f + side.z;
        b.vx = dir.x * turretBulletSpeed(turret);
        b.vz = dir.z * turretBulletSpeed(turret);
        b.life = turret == Turret.STURM ? 2.0f : turret == Turret.FIRE ? 1.2f : 2.2f;
        b.owner = shooter; b.ownerIndex = shooter.ownerIndex; b.turret = turret; b.damage = turretDamage(turret);
        b.bounces = 0; b.maxBounces = turret == Turret.STURM ? 0 : 2;
        float radius = turret == Turret.STURM ? 0.72f : turret == Turret.FIRE ? 0.48f : 0.34f;
        Geometry g = new Geometry("TB2D_Bullet", new Sphere(12, 12, radius));
        g.setMaterial(ctx.unshaded(turretColor(turret)));
        g.setLocalTranslation(b.x, 0.52f, b.z);
        bulletRoot.attachChild(g);
        b.geo = g;
        bullets.add(b);
        spawnParticles(b.x, b.z, turretColor(turret), turret == Turret.STURM ? 14 : 5, turret == Turret.STURM ? 1.3f : 0.8f);
        if (ctx != null && !ctx.solo && shooter == player) {
            broadcast(ctx, "TB2D_SHOT|" + ctx.myIndex + "|" + fmt(b.x) + "|" + fmt(b.z) + "|" + fmt(b.vx) + "|" + fmt(b.vz) + "|" + turret.ordinal() + "|" + fmt(sideOffset));
        }
    }

    private void fireSniperBeam(SnakeApp.MapContext ctx, Tank2D shooter, Vector3f dir) {
        if (ctx == null || shooter == null || dir == null) return;
        float startX = shooter.x + dir.x * 2.0f;
        float startZ = shooter.z + dir.z * 2.0f;
        float maxLen = 56f;
        float hitLen = maxLen;
        Tank2D hitTank = null;
        Worm2D hitWorm = null;
        boolean hitPlayer = false;
        for (float d = 0f; d <= maxLen; d += 0.35f) {
            float x = startX + dir.x * d, z = startZ + dir.z * d;
            if (x < -MAP_HALF_X || x > MAP_HALF_X || z < -MAP_HALF_Z || z > MAP_HALF_Z || pointInWall(x, z, 0.18f)) { hitLen = d; break; }
            if (shooter != player && player != null && player.alive && dist2(x, z, player.x, player.z) < TANK_RADIUS * TANK_RADIUS) { hitPlayer = true; hitLen = d; break; }
            if (shooter == player && botsEnabled) {
                for (Tank2D t : enemyTanks) if (t.alive && dist2(x, z, t.x, t.z) < TANK_RADIUS * TANK_RADIUS) { hitTank = t; hitLen = d; break; }
                if (hitTank != null) break;
                for (Worm2D w : worms) if (w.alive && dist2(x, z, w.headX, w.headZ) < WORM_RADIUS * WORM_RADIUS) { hitWorm = w; hitLen = d; break; }
                if (hitWorm != null) break;
            }
        }
        hitLen = Math.max(1.5f, hitLen);
        createSniperBeam(ctx, startX, startZ, shooter.heading, hitLen);
        float hitX = startX + dir.x * hitLen, hitZ = startZ + dir.z * hitLen;
        spawnParticles(hitX, hitZ, new ColorRGBA(1f, 1f, 1f, 1f), 16, 1.4f);
        if (hitPlayer) damageOrShield(ctx, player, turretDamage(Turret.SNIPER), null, "Щит поглотил снайперский луч!");
        if (hitTank != null) damageTank(ctx, hitTank, turretDamage(Turret.SNIPER), null);
        if (hitWorm != null) damageWorm(hitWorm, 3, true);
        if (ctx != null && !ctx.solo && shooter == player) broadcast(ctx, "TB2D_BEAM|" + ctx.myIndex + "|" + fmt(startX) + "|" + fmt(startZ) + "|" + fmt(shooter.heading) + "|" + fmt(hitLen));
    }

    private void createSniperBeam(SnakeApp.MapContext ctx, float startX, float startZ, float heading, float length) {
        if (ctx == null || fxRoot == null) return;
        Vector3f dir = dirFromHeading(heading);
        float midX = startX + dir.x * length * 0.5f;
        float midZ = startZ + dir.z * length * 0.5f;
        Geometry beam = new Geometry("SniperWhiteBeam", new Box(0.13f, 0.035f, length * 0.5f));
        Material m = ctx.unshaded(new ColorRGBA(1f, 1f, 1f, 0.82f));
        try { m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha); } catch (Exception ignored) {}
        beam.setMaterial(m); beam.setQueueBucket(RenderQueue.Bucket.Transparent);
        beam.setLocalTranslation(midX, 0.66f, midZ);
        beam.setLocalRotation(new Quaternion().fromAngleAxis(heading, Vector3f.UNIT_Y));
        fxRoot.attachChild(beam);
        Geometry glow = new Geometry("SniperWhiteBeamGlow", new Box(0.34f, 0.025f, length * 0.5f));
        Material gm = ctx.unshaded(new ColorRGBA(0.75f, 0.95f, 1f, 0.22f));
        try { gm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha); } catch (Exception ignored) {}
        glow.setMaterial(gm); glow.setQueueBucket(RenderQueue.Bucket.Transparent);
        glow.setLocalTranslation(midX, 0.64f, midZ);
        glow.setLocalRotation(new Quaternion().fromAngleAxis(heading, Vector3f.UNIT_Y));
        fxRoot.attachChild(glow);
        Beam2D b = new Beam2D(); b.beam = beam; b.glow = glow; b.life = 0.18f; beams.add(b);
    }

    private void updateBullets(SnakeApp.MapContext ctx, float tpf) {
        Iterator<Bullet2D> it = bullets.iterator();
        while (it.hasNext()) {
            Bullet2D b = it.next();
            b.life -= tpf;
            float oldX = b.x, oldZ = b.z;
            b.x += b.vx * tpf; b.z += b.vz * tpf;
            boolean dead = b.life <= 0f;
            if (b.x < -MAP_HALF_X + BULLET_RADIUS) { b.x = -MAP_HALF_X + BULLET_RADIUS; b.vx = -b.vx; b.bounces++; }
            if (b.x > MAP_HALF_X - BULLET_RADIUS) { b.x = MAP_HALF_X - BULLET_RADIUS; b.vx = -b.vx; b.bounces++; }
            if (b.z < -MAP_HALF_Z + BULLET_RADIUS) { b.z = -MAP_HALF_Z + BULLET_RADIUS; b.vz = -b.vz; b.bounces++; }
            if (b.z > MAP_HALF_Z - BULLET_RADIUS) { b.z = MAP_HALF_Z - BULLET_RADIUS; b.vz = -b.vz; b.bounces++; }
            Wall2D hitWall = bulletWallHit(b);
            if (hitWall != null) {
                if (b.turret == Turret.STURM) dead = true;
                else {
                    boolean sideHit = oldX < hitWall.x - hitWall.hx || oldX > hitWall.x + hitWall.hx;
                    if (sideHit) b.vx = -b.vx; else b.vz = -b.vz;
                    b.x = oldX; b.z = oldZ; b.bounces++;
                    spawnParticles(b.x, b.z, turretColor(b.turret), 5, 0.5f);
                }
            }
            if (b.bounces > b.maxBounces) dead = true;
            if (!dead && b.ownerIndex != (ctx == null ? -1 : ctx.myIndex) && player != null && player.alive && dist2(b.x, b.z, player.x, player.z) < (TANK_RADIUS + BULLET_RADIUS) * (TANK_RADIUS + BULLET_RADIUS)) {
                damageOrShield(ctx, player, b.damage, b, "Щит поглотил снаряд!");
                dead = true;
            }
            if (!dead && b.owner == player) {
                for (Tank2D t : enemyTanks) if (t.alive && dist2(b.x, b.z, t.x, t.z) < (TANK_RADIUS + BULLET_RADIUS) * (TANK_RADIUS + BULLET_RADIUS)) { damageTank(ctx, t, b.damage, b); dead = true; break; }
            }
            if (!dead) {
                for (Worm2D w : worms) if (w.alive && dist2(b.x, b.z, w.headX, w.headZ) < (WORM_RADIUS + BULLET_RADIUS) * (WORM_RADIUS + BULLET_RADIUS)) { damageWorm(w, Math.max(1, b.damage / 20), false); dead = true; break; }
            }
            if (b.geo != null) b.geo.setLocalTranslation(b.x, 0.52f, b.z);
            if (dead) {
                spawnBulletExplosion(b.x, b.z, b.turret);
                if (b.turret == Turret.FIRE) splashDamage(ctx, b.x, b.z, 4.2f, 10, b.owner);
                if (b.turret == Turret.STURM) splashDamage(ctx, b.x, b.z, 9.2f, 95, b.owner);
                if (b.geo != null) b.geo.removeFromParent();
                it.remove();
            }
        }
    }

    private Wall2D bulletWallHit(Bullet2D b) {
        for (Wall2D w : walls) if (circleAabb(b.x, b.z, BULLET_RADIUS, w.x, w.z, w.hx, w.hz)) return w;
        for (WreckCollider2D wc : wreckColliders) {
            if (circleAabb(b.x, b.z, BULLET_RADIUS, wc.x, wc.z, wc.hx, wc.hz)) {
                Wall2D fake = new Wall2D();
                fake.x = wc.x; fake.z = wc.z; fake.hx = wc.hx; fake.hz = wc.hz;
                return fake;
            }
        }
        return null;
    }

    private void damageOrShield(SnakeApp.MapContext ctx, Tank2D t, int damage, Bullet2D source, String shieldText) {
        if (t == player && playerShieldTimer > 0f) {
            playerShieldTimer = 0f;
            showCenter(shieldText, 1.3f, new ColorRGBA(0.2f, 0.85f, 1f, 1f));
        } else damageTank(ctx, t, damage, source);
    }

    private void damageTank(SnakeApp.MapContext ctx, Tank2D t, int damage, Bullet2D source) {
        if (t == null || !t.alive) return;
        t.hp -= damage;
        if (source != null && source.turret == Turret.FREEZE) t.frozenTimer = 2.0f;
        spawnParticles(t.x, t.z, new ColorRGBA(1f, 0.35f, 0.15f, 1f), 12, 1.2f);
        if (t.hp <= 0) destroyTank(ctx, t, source != null ? "танк уничтожен снарядом" : "танк уничтожен");
    }

    private void destroyTank(SnakeApp.MapContext ctx, Tank2D t, String reason) {
        if (t == null || !t.alive) return;
        t.alive = false; t.hp = 0;
        createTankWreck(ctx, t);
        spawnBigExplosion(t.x, t.z);
        removeTankLights(t);
        if (t.node != null) t.node.removeFromParent();
        if (t == player) killPlayerTank(ctx, reason);
        else {
            kills++; score += t.tankModel == 4 ? 250 : 100;
            showCenter(t.tankModel == 4 ? "+250 SturmTiger уничтожен" : "+100 Танк уничтожен", 1f, new ColorRGBA(1f, 0.8f, 0.25f, 1f));
            broadcast(ctx, "TANK_BATTLE_2D_ENEMY_TANK_DEAD");
        }
    }

    private void splashDamage(SnakeApp.MapContext ctx, float x, float z, float radius, int damage, Tank2D owner) {
        float r2 = radius * radius;
        if (owner != player && player != null && player.alive && dist2(x, z, player.x, player.z) < r2) damageOrShield(ctx, player, damage, null, "Щит поглотил взрыв!");
        for (Tank2D t : enemyTanks) if (t.alive && t != owner && dist2(x, z, t.x, t.z) < r2) damageTank(ctx, t, damage, null);
        for (Worm2D w : worms) if (w.alive && dist2(x, z, w.headX, w.headZ) < r2) damageWorm(w, 2, false);
        for (Mine2D m : mines) if (m.active && dist2(x, z, m.x, m.z) < r2) detonateMine(ctx, m);
    }

    private void damageWorm(Worm2D w, int amount, boolean byBeam) {
        if (w == null || !w.alive) return;
        w.hp -= amount;
        spawnParticles(w.headX, w.headZ, new ColorRGBA(1f, 0.20f, 0.05f, 1f), 8, 1.0f);
        if (w.hp <= 0) {
            w.alive = false;
            if (w.node != null) w.node.removeFromParent();
            kills++; score += 35;
            showCenter(byBeam ? "+35 Змейка уничтожена лучом" : "+35 Змейка уничтожена", 0.8f, new ColorRGBA(1f, 0.85f, 0.25f, 1f));
        }
    }

    private void updateWorms(float tpf) {
        for (Worm2D w : worms) {
            if (!w.alive || player == null || !player.alive) continue;
            float dx = player.x - w.headX, dz = player.z - w.headZ;
            float desired = (float)Math.atan2(dx, dz);
            float diff = angleDiff(w.heading, desired);
            w.heading += clamp(diff, -2.7f * tpf, 2.7f * tpf);
            float oldX = w.headX, oldZ = w.headZ;
            float spd = 4.4f + wave * 0.18f;
            Vector3f dir = dirFromHeading(w.heading);
            w.headX += dir.x * spd * tpf; w.headZ += dir.z * spd * tpf;
            w.headX = clamp(w.headX, -MAP_HALF_X + WORM_RADIUS, MAP_HALF_X - WORM_RADIUS);
            w.headZ = clamp(w.headZ, -MAP_HALF_Z + WORM_RADIUS, MAP_HALF_Z - WORM_RADIUS);
            boolean blocked = false;
            for (Wall2D wall : walls) if (circleAabb(w.headX, w.headZ, WORM_RADIUS, wall.x, wall.z, wall.hx, wall.hz)) { blocked = true; break; }
            if (blocked) { w.headX = oldX; w.headZ = oldZ; w.heading += FastMath.PI + (rng.nextFloat() - 0.5f) * 0.8f; }
            updateWormBody(w);
            if (dist2(w.headX, w.headZ, player.x, player.z) < (TANK_RADIUS + WORM_RADIUS) * (TANK_RADIUS + WORM_RADIUS)) {
                if (playerShieldTimer > 0f) { playerShieldTimer = Math.max(0f, playerShieldTimer - 0.5f); w.heading += FastMath.PI; }
                else {
                    player.hp -= 1;
                    if (player.hp <= 0) destroyTank(lastCtx, player, "змейка добралась до танка");
                }
            }
        }
    }

    private void updateWormBody(Worm2D w) {
        if (w.node == null) return;
        for (int i = w.segments.size() - 1; i > 0; i--) {
            WormSeg cur = w.segments.get(i), prev = w.segments.get(i - 1);
            cur.x = prev.x; cur.z = prev.z;
        }
        if (!w.segments.isEmpty()) { WormSeg head = w.segments.get(0); head.x = w.headX; head.z = w.headZ; }
        for (int i = 0; i < w.segments.size(); i++) {
            WormSeg s = w.segments.get(i);
            if (s.geo != null) { float scale = Math.max(0.55f, 1.0f - i * 0.045f); s.geo.setLocalTranslation(s.x, 0.34f, s.z); s.geo.setLocalScale(scale); }
        }
    }

    private void spawnWorm(SnakeApp.MapContext ctx, float x, float z) {
        // v13: оранжевые атакующие змейки/точки полностью отключены.
    }

    private void updatePickups(SnakeApp.MapContext ctx, float tpf) {
        if (player == null || !player.alive) return;
        for (Pickup2D p : pickups) {
            if (!p.active) continue;
            p.spin += tpf * 2.8f;
            if (p.node != null) { p.node.setLocalRotation(new Quaternion().fromAngleAxis(p.spin, Vector3f.UNIT_Y)); p.node.setLocalTranslation(p.x, 0.55f + FastMath.sin(p.spin * 1.4f) * 0.18f, p.z); }
            if (dist2(player.x, player.z, p.x, p.z) < 5.0f) {
                p.active = false; if (p.node != null) p.node.removeFromParent();
                if (p.kind == PickupKind.SHIELD) { playerShieldTimer = 8f; showCenter("Щит +8 сек.", 1.2f, pickupColor(p.kind)); }
                else if (p.kind == PickupKind.REPAIR) { player.hp = Math.min(player.maxHp, player.hp + 45); showCenter("Ремонт +45 HP", 1.2f, pickupColor(p.kind)); }
                else if (p.kind == PickupKind.RAPID) { rapidTimer = 8f; showCenter("Скорострельность +8 сек.", 1.2f, pickupColor(p.kind)); }
                else if (p.kind == PickupKind.DUAL_CANNON) { dualTimer = 12f; player.dualVisual = true; rebuildPlayerTankVisual(ctx); showCenter("Две пушки +12 сек.", 1.2f, pickupColor(p.kind)); }
                else if (p.kind == PickupKind.MINE) { mineCharges++; showCenter("Мина получена. Нажми X", 1.2f, pickupColor(p.kind)); }
                updateTankNode(player);
            }
        }
        if (player != null && player.dualVisual && dualTimer <= 0f && player.turret != Turret.DUAL) {
            player.dualVisual = false;
            rebuildPlayerTankVisual(ctx);
        }
    }

    private void rebuildPlayerTankVisual(SnakeApp.MapContext ctx) {
        if (ctx == null || player == null) return;
        removeTankLights(player);
        if (player.node != null) player.node.removeFromParent();
        player.node = buildTankNode(ctx, player, player.color == null ? tankColor(player.tankModel) : player.color);
        tankRoot.attachChild(player.node);
        updateTankNode(player);
    }

    private void spawnPickup(SnakeApp.MapContext ctx, PickupKind kind, float x, float z) {
        if (ctx == null || pickupRoot == null) return;
        Vector3f safe = safeSpawnPoint(x, z, 1.6f);
        x = safe.x; z = safe.z;
        Pickup2D p = new Pickup2D(); p.kind = kind; p.x = x; p.z = z; p.active = true;
        Node n = new Node("Pickup_" + kind);
        if (kind == PickupKind.DUAL_CANNON) {
            addBox(ctx, n, "DualBox", new Vector3f(0f, 0f, 0f), new Vector3f(0.55f, 0.20f, 0.55f), pickupColor(kind));
            addBox(ctx, n, "GunL", new Vector3f(-0.18f, 0.22f, 0.55f), new Vector3f(0.07f, 0.05f, 0.55f), new ColorRGBA(0.08f,0.08f,0.08f,1f));
            addBox(ctx, n, "GunR", new Vector3f(0.18f, 0.22f, 0.55f), new Vector3f(0.07f, 0.05f, 0.55f), new ColorRGBA(0.08f,0.08f,0.08f,1f));
        } else if (kind == PickupKind.MINE) {
            addBox(ctx, n, "MineIcon", new Vector3f(0f, 0f, 0f), new Vector3f(0.60f, 0.12f, 0.60f), pickupColor(kind));
            addBox(ctx, n, "MineLamp", new Vector3f(0f, 0.18f, 0f), new Vector3f(0.18f, 0.06f, 0.18f), new ColorRGBA(1f, 0.05f, 0.03f, 1f));
        } else {
            Geometry g = new Geometry("PickupCore", new Sphere(12, 12, 0.55f));
            g.setMaterial(ctx.unshaded(pickupColor(kind))); n.attachChild(g);
        }
        n.setLocalTranslation(x, 0.55f, z); pickupRoot.attachChild(n);
        p.node = n; pickups.add(p);
    }

    private void placeMine(SnakeApp.MapContext ctx, Tank2D owner) {
        if (ctx == null || owner == null || !owner.alive) return;
        if (mineCharges <= 0 && owner == player) { showCenter("Мин нет", 0.8f, new ColorRGBA(1f,0.35f,0.15f,1f)); return; }
        Vector3f back = dirFromHeading(owner.heading).mult(-2.2f);
        float x = owner.x + back.x, z = owner.z + back.z;
        if (pointInWall(x, z, 0.8f)) { showCenter("Нельзя поставить мину в стену", 0.8f, new ColorRGBA(1f,0.35f,0.15f,1f)); return; }
        if (owner == player) mineCharges--;
        spawnMine(ctx, x, z, owner.ownerIndex);
        if (ctx != null && !ctx.solo && owner == player) broadcast(ctx, "TB2D_MINE|" + ctx.myIndex + "|" + fmt(x) + "|" + fmt(z));
    }

    private void spawnMine(SnakeApp.MapContext ctx, float x, float z, int ownerIndex) {
        Mine2D m = new Mine2D(); m.x = x; m.z = z; m.ownerIndex = ownerIndex; m.active = true; m.armTimer = 0.55f;
        Node n = new Node("Mine2D");
        addBox(ctx, n, "MineBody", new Vector3f(0f, 0f, 0f), new Vector3f(0.72f, 0.08f, 0.72f), new ColorRGBA(0.03f, 0.03f, 0.025f, 1f));
        addBox(ctx, n, "MineLamp", new Vector3f(0f, 0.14f, 0f), new Vector3f(0.16f, 0.05f, 0.16f), new ColorRGBA(1f, 0.04f, 0.02f, 1f));
        n.setLocalTranslation(x, 0.11f, z); mineRoot.attachChild(n); m.node = n; mines.add(m);
    }

    private void updateMines(SnakeApp.MapContext ctx, float tpf) {
        Iterator<Mine2D> it = mines.iterator();
        while (it.hasNext()) {
            Mine2D m = it.next();
            if (!m.active) { if (m.node != null) m.node.removeFromParent(); it.remove(); continue; }
            m.armTimer -= tpf;
            if (m.node != null) m.node.rotate(0f, tpf * 0.7f, 0f);
            if (m.armTimer > 0f) continue;
            if (player != null && player.alive && player.ownerIndex != m.ownerIndex && dist2(player.x, player.z, m.x, m.z) < 4.2f) { detonateMine(ctx, m); continue; }
            for (Tank2D t : enemyTanks) if (t.alive && t.ownerIndex != m.ownerIndex && dist2(t.x, t.z, m.x, m.z) < 4.2f) { detonateMine(ctx, m); break; }
            for (Worm2D w : worms) if (w.alive && dist2(w.headX, w.headZ, m.x, m.z) < 3.2f) { detonateMine(ctx, m); break; }
        }
    }

    private void detonateMine(SnakeApp.MapContext ctx, Mine2D m) {
        if (m == null || !m.active) return;
        m.active = false;
        spawnBigExplosion(m.x, m.z);
        splashDamage(ctx, m.x, m.z, 5.8f, 48, null);
        if (m.node != null) m.node.removeFromParent();
    }

    private void updateAirplanesAndDrops(SnakeApp.MapContext ctx, float tpf) {
        if (ctx != null && (ctx.solo || ctx.host)) {
            if (airDropTimer <= 0f) {
                PickupKind kind = randomAirDropKind();
                AirRoute2D route = randomAirRoute();
                spawnAirplane(ctx, kind, route.startX, route.startZ, route.endX, route.endZ, route.dropX, route.dropZ);
                if (!ctx.solo) {
                    broadcast(ctx, "TB2D_AIRDROP|" + kind.ordinal()
                            + "|" + fmt(route.startX) + "|" + fmt(route.startZ)
                            + "|" + fmt(route.endX) + "|" + fmt(route.endZ)
                            + "|" + fmt(route.dropX) + "|" + fmt(route.dropZ));
                }
                airDropTimer = 18f + rng.nextFloat() * 14f;
            }
        }

        Iterator<Airplane2D> ait = airplanes.iterator();
        while (ait.hasNext()) {
            Airplane2D a = ait.next();
            float step = a.speed * tpf;
            a.x += a.vx * step;
            a.z += a.vz * step;
            a.travel += step;

            if (a.node != null) {
                a.node.setLocalTranslation(a.x, 6.2f, a.z);
                a.node.setLocalRotation(new Quaternion().fromAngleAxis(a.heading, Vector3f.UNIT_Y));
            }
            setAirplaneLightsVisible(a, headlightsActive);
            if (headlightsActive) updateAirplaneHeadlights(a);

            if (!a.dropped && a.travel >= a.dropDistance) {
                a.dropped = true;
                spawnContainerDrop(lastCtx, a.kind, a.dropX, a.dropZ);
            }

            if (a.travel > a.totalDist + 8f) {
                removeAirplaneLights(a);
                if (a.node != null) a.node.removeFromParent();
                ait.remove();
            }
        }

        Iterator<ContainerDrop2D> dit = containers.iterator();
        while (dit.hasNext()) {
            ContainerDrop2D c = dit.next();
            c.y -= 5.8f * tpf;
            c.spin += tpf * 4f;
            if (c.node != null) {
                c.node.setLocalTranslation(c.x, Math.max(0.65f, c.y), c.z);
                c.node.setLocalRotation(new Quaternion().fromAngleAxis(c.spin, Vector3f.UNIT_Y));
            }
            if (c.y <= 0.65f) {
                spawnPickup(lastCtx, c.kind, c.x, c.z);
                spawnParticles(c.x, c.z, pickupColor(c.kind), 18, 1.3f);
                if (c.node != null) c.node.removeFromParent();
                dit.remove();
            }
        }
    }

    private AirRoute2D randomAirRoute() {
        AirRoute2D r = new AirRoute2D();
        int side = rng.nextInt(4);
        float drift = -13f + rng.nextFloat() * 26f;
        if (side == 0) { // запад -> восток
            r.startX = -MAP_HALF_X - 13f;
            r.startZ = -MAP_HALF_Z + 6f + rng.nextFloat() * (MAP_HALF_Z * 2f - 12f);
            r.endX = MAP_HALF_X + 13f;
            r.endZ = clamp(r.startZ + drift, -MAP_HALF_Z + 5f, MAP_HALF_Z - 5f);
        } else if (side == 1) { // восток -> запад
            r.startX = MAP_HALF_X + 13f;
            r.startZ = -MAP_HALF_Z + 6f + rng.nextFloat() * (MAP_HALF_Z * 2f - 12f);
            r.endX = -MAP_HALF_X - 13f;
            r.endZ = clamp(r.startZ + drift, -MAP_HALF_Z + 5f, MAP_HALF_Z - 5f);
        } else if (side == 2) { // север -> юг
            r.startX = -MAP_HALF_X + 6f + rng.nextFloat() * (MAP_HALF_X * 2f - 12f);
            r.startZ = MAP_HALF_Z + 13f;
            r.endX = clamp(r.startX + drift, -MAP_HALF_X + 5f, MAP_HALF_X - 5f);
            r.endZ = -MAP_HALF_Z - 13f;
        } else { // юг -> север
            r.startX = -MAP_HALF_X + 6f + rng.nextFloat() * (MAP_HALF_X * 2f - 12f);
            r.startZ = -MAP_HALF_Z - 13f;
            r.endX = clamp(r.startX + drift, -MAP_HALF_X + 5f, MAP_HALF_X - 5f);
            r.endZ = MAP_HALF_Z + 13f;
        }
        float dropT = 0.35f + rng.nextFloat() * 0.30f;
        r.dropX = r.startX + (r.endX - r.startX) * dropT;
        r.dropZ = r.startZ + (r.endZ - r.startZ) * dropT;
        r.dropX = clamp(r.dropX, -MAP_HALF_X + 6f, MAP_HALF_X - 6f);
        r.dropZ = clamp(r.dropZ, -MAP_HALF_Z + 6f, MAP_HALF_Z - 6f);
        return r;
    }

    private void spawnAirplane(SnakeApp.MapContext ctx, PickupKind kind, float z, float dropX) {
        // Старый сетевой формат: оставлен для совместимости.
        spawnAirplane(ctx, kind, -MAP_HALF_X - 13f, z, MAP_HALF_X + 13f, z, dropX, z);
    }

    private void spawnAirplane(SnakeApp.MapContext ctx, PickupKind kind,
                               float startX, float startZ, float endX, float endZ,
                               float dropX, float dropZ) {
        if (ctx == null || airplaneRoot == null) return;

        float dx = endX - startX;
        float dz = endZ - startZ;
        float dist = Math.max(0.001f, (float)Math.sqrt(dx * dx + dz * dz));
        float vx = dx / dist;
        float vz = dz / dist;

        Airplane2D a = new Airplane2D();
        a.kind = kind;
        a.x = startX;
        a.z = startZ;
        a.vx = vx;
        a.vz = vz;
        a.heading = (float)Math.atan2(vx, vz);
        a.speed = 15f + rng.nextFloat() * 5f;
        a.totalDist = dist;
        a.travel = 0f;
        a.dropX = dropX;
        a.dropZ = dropZ;
        a.dropDistance = clamp(distance2D(startX, startZ, dropX, dropZ), 3f, dist - 3f);
        a.dropped = false;

        Node n = createDetailedAirplane(ctx, kind, a);
        n.setLocalTranslation(a.x, 6.2f, a.z);
        n.setLocalRotation(new Quaternion().fromAngleAxis(a.heading, Vector3f.UNIT_Y));
        airplaneRoot.attachChild(n);
        a.node = n;
        airplanes.add(a);

        showCenter("Грузовой самолёт сбрасывает контейнер!", 1.5f, new ColorRGBA(0.75f, 0.95f, 1f, 1f));
    }

    private Node createDetailedAirplane(SnakeApp.MapContext ctx, PickupKind kind, Airplane2D airplane) {
        Node n = new Node("HeavyCargoPlane");
        ColorRGBA body = new ColorRGBA(0.50f, 0.56f, 0.58f, 1f);
        ColorRGBA wing = new ColorRGBA(0.36f, 0.42f, 0.45f, 1f);
        ColorRGBA dark = new ColorRGBA(0.07f, 0.08f, 0.085f, 1f);
        ColorRGBA glass = new ColorRGBA(0.18f, 0.48f, 0.68f, 1f);
        ColorRGBA cargo = pickupColor(kind);

        // Большой грузовой самолёт сверху: широкий фюзеляж, 4 двигателя, хвостовая рампа.
        addBox(ctx, n, "CargoFuselage", new Vector3f(0f, 0.06f, 0f), new Vector3f(0.62f, 0.15f, 2.85f), body);
        addBox(ctx, n, "CargoWideBelly", new Vector3f(0f, -0.02f, -0.18f), new Vector3f(0.82f, 0.10f, 1.35f), darken(body, 0.82f));
        addBox(ctx, n, "CargoNose", new Vector3f(0f, 0.07f, 2.95f), new Vector3f(0.46f, 0.13f, 0.36f), brighten(body, 1.16f));
        addBox(ctx, n, "CargoCockpit", new Vector3f(0f, 0.22f, 1.72f), new Vector3f(0.36f, 0.055f, 0.42f), glass);

        addBox(ctx, n, "CargoMainWing", new Vector3f(0f, 0.03f, 0.40f), new Vector3f(3.65f, 0.07f, 0.42f), wing);
        addBox(ctx, n, "CargoWingLeftTip", new Vector3f(-4.10f, 0.03f, 0.40f), new Vector3f(0.48f, 0.06f, 0.26f), brighten(wing, 1.16f));
        addBox(ctx, n, "CargoWingRightTip", new Vector3f(4.10f, 0.03f, 0.40f), new Vector3f(0.48f, 0.06f, 0.26f), brighten(wing, 1.16f));

        addBox(ctx, n, "CargoTailFin", new Vector3f(0f, 0.20f, -2.68f), new Vector3f(0.28f, 0.46f, 0.42f), wing);
        addBox(ctx, n, "CargoTailWing", new Vector3f(0f, 0.08f, -2.35f), new Vector3f(1.65f, 0.055f, 0.26f), wing);
        addBox(ctx, n, "CargoRamp", new Vector3f(0f, -0.01f, -2.96f), new Vector3f(0.56f, 0.06f, 0.22f), dark);

        addBox(ctx, n, "EngineL1", new Vector3f(-1.15f, -0.04f, 0.78f), new Vector3f(0.24f, 0.10f, 0.38f), dark);
        addBox(ctx, n, "EngineL2", new Vector3f(-2.35f, -0.04f, 0.78f), new Vector3f(0.24f, 0.10f, 0.38f), dark);
        addBox(ctx, n, "EngineR1", new Vector3f(1.15f, -0.04f, 0.78f), new Vector3f(0.24f, 0.10f, 0.38f), dark);
        addBox(ctx, n, "EngineR2", new Vector3f(2.35f, -0.04f, 0.78f), new Vector3f(0.24f, 0.10f, 0.38f), dark);

        addBox(ctx, n, "PropL1", new Vector3f(-1.15f, -0.03f, 1.18f), new Vector3f(0.52f, 0.025f, 0.055f), new ColorRGBA(0.03f, 0.03f, 0.03f, 1f));
        addBox(ctx, n, "PropL2", new Vector3f(-2.35f, -0.03f, 1.18f), new Vector3f(0.52f, 0.025f, 0.055f), new ColorRGBA(0.03f, 0.03f, 0.03f, 1f));
        addBox(ctx, n, "PropR1", new Vector3f(1.15f, -0.03f, 1.18f), new Vector3f(0.52f, 0.025f, 0.055f), new ColorRGBA(0.03f, 0.03f, 0.03f, 1f));
        addBox(ctx, n, "PropR2", new Vector3f(2.35f, -0.03f, 1.18f), new Vector3f(0.52f, 0.025f, 0.055f), new ColorRGBA(0.03f, 0.03f, 0.03f, 1f));

        // Фары самолёта и визуальные конусы света.
        airplane.lightLampL = addBox(ctx, n, "CargoPlaneLampL", new Vector3f(-0.30f, 0.20f, 3.10f), new Vector3f(0.12f, 0.045f, 0.08f), new ColorRGBA(1f, 0.96f, 0.62f, 1f));
        airplane.lightLampR = addBox(ctx, n, "CargoPlaneLampR", new Vector3f(0.30f, 0.20f, 3.10f), new Vector3f(0.12f, 0.045f, 0.08f), new ColorRGBA(1f, 0.96f, 0.62f, 1f));

        airplane.lightBeamL = null;
        airplane.lightBeamR = null;

        addBox(ctx, n, "CargoCrateUnderbelly", new Vector3f(0f, -0.16f, -0.62f), new Vector3f(0.42f, 0.08f, 0.34f), cargo);
        addBox(ctx, n, "CargoMarkLeft", new Vector3f(-0.36f, 0.19f, -0.15f), new Vector3f(0.10f, 0.035f, 0.55f), cargo);
        addBox(ctx, n, "CargoMarkRight", new Vector3f(0.36f, 0.19f, -0.15f), new Vector3f(0.10f, 0.035f, 0.55f), cargo);

        airplane.headSpotL = createAirplaneHeadlightSpot();
        airplane.headSpotR = createAirplaneHeadlightSpot();
        setAirplaneLightsVisible(airplane, headlightsActive);
        return n;
    }

    private SpotLight createAirplaneHeadlightSpot() {
        SpotLight light = new SpotLight();
        light.setColor(new ColorRGBA(1f, 0.94f, 0.62f, 4.6f));
				light.setSpotRange(52f);                          // как было
				light.setSpotInnerAngle(13f * FastMath.DEG_TO_RAD); // было 13°
				light.setSpotOuterAngle(38f * FastMath.DEG_TO_RAD); // было 38°
        return light;
    }

    private void setAirplaneLightsVisible(Airplane2D a, boolean visible) {
        if (a == null) return;

        com.jme3.scene.Spatial.CullHint hint = visible ? com.jme3.scene.Spatial.CullHint.Inherit : com.jme3.scene.Spatial.CullHint.Always;
        try { if (a.lightLampL != null) a.lightLampL.setCullHint(hint); } catch (Exception ignored) {}
        try { if (a.lightLampR != null) a.lightLampR.setCullHint(hint); } catch (Exception ignored) {}
        try { if (a.lightBeamL != null) a.lightBeamL.setCullHint(hint); } catch (Exception ignored) {}
        try { if (a.lightBeamR != null) a.lightBeamR.setCullHint(hint); } catch (Exception ignored) {}

        if (visible) {
            updateAirplaneHeadlights(a);
            if (!a.lightsAttached && world != null) {
                try { if (a.headSpotL != null) world.addLight(a.headSpotL); } catch (Exception ignored) {}
                try { if (a.headSpotR != null) world.addLight(a.headSpotR); } catch (Exception ignored) {}
                a.lightsAttached = true;
            }
        } else {
            removeAirplaneLights(a);
        }
    }

    private void updateAirplaneHeadlights(Airplane2D a) {
        if (a == null || a.headSpotL == null || a.headSpotR == null) return;

        Vector3f dir = new Vector3f(a.vx, 0f, a.vz);
        if (dir.lengthSquared() < 0.0001f) dir = dirFromHeading(a.heading);
        else dir.normalizeLocal();

        Vector3f side = new Vector3f(dir.z, 0f, -dir.x);
        Vector3f base = new Vector3f(a.x, 8.5f, a.z);
        Vector3f forward = dir.mult(3.2f);
        Vector3f left = side.mult(-0.85f);
        Vector3f right = side.mult(0.85f);
        Vector3f lightDir = new Vector3f(dir.x * 0.22f, -0.94f, dir.z * 0.22f).normalizeLocal();

        a.headSpotL.setPosition(base.add(forward).add(left));
        a.headSpotR.setPosition(base.add(forward).add(right));
        a.headSpotL.setDirection(lightDir);
        a.headSpotR.setDirection(lightDir);
    }

    private void removeAirplaneLights(Airplane2D a) {
        if (a == null || world == null) return;
        try { if (a.headSpotL != null) world.removeLight(a.headSpotL); } catch (Exception ignored) {}
        try { if (a.headSpotR != null) world.removeLight(a.headSpotR); } catch (Exception ignored) {}
        a.lightsAttached = false;
    }

    private void removeAllAirplaneLights() {
        for (Airplane2D a : airplanes) removeAirplaneLights(a);
    }

    private void spawnContainerDrop(SnakeApp.MapContext ctx, PickupKind kind, float x, float z) {
        if (ctx == null || airplaneRoot == null) return;
        Vector3f safe = safeSpawnPoint(x, z, 2.0f);
        x = safe.x; z = safe.z;
        ContainerDrop2D c = new ContainerDrop2D();
        c.kind = kind;
        c.x = x;
        c.z = z;
        c.y = 7.0f;
        Node n = new Node("BonusContainer");
        addBox(ctx, n, "ContainerBody", new Vector3f(0f, 0f, 0f), new Vector3f(0.70f, 0.30f, 0.70f), pickupColor(kind));
        addBox(ctx, n, "ContainerStripeA", new Vector3f(0f, 0.34f, 0f), new Vector3f(0.76f, 0.04f, 0.12f), new ColorRGBA(1f, 1f, 1f, 1f));
        addBox(ctx, n, "ContainerStripeB", new Vector3f(0f, 0.35f, 0f), new Vector3f(0.12f, 0.05f, 0.76f), new ColorRGBA(0.08f, 0.08f, 0.08f, 1f));
        n.setLocalTranslation(x, c.y, z);
        airplaneRoot.attachChild(n);
        c.node = n;
        containers.add(c);
    }

    private Vector3f safeSpawnPoint(float desiredX, float desiredZ, float radius) {
        if (!pointInWall(desiredX, desiredZ, radius)
                && Math.abs(desiredX) <= MAP_HALF_X - radius
                && Math.abs(desiredZ) <= MAP_HALF_Z - radius) {
            return new Vector3f(desiredX, 0f, desiredZ);
        }

        for (int ring = 1; ring <= 10; ring++) {
            float step = 2.4f * ring;
            for (int i = 0; i < 28; i++) {
                float a = FastMath.TWO_PI * i / 28f + ring * 0.37f;
                float x = clamp(desiredX + FastMath.sin(a) * step, -MAP_HALF_X + radius, MAP_HALF_X - radius);
                float z = clamp(desiredZ + FastMath.cos(a) * step, -MAP_HALF_Z + radius, MAP_HALF_Z - radius);
                if (!pointInWall(x, z, radius)) return new Vector3f(x, 0f, z);
            }
        }

        for (int i = 0; i < 220; i++) {
            float x = -MAP_HALF_X + radius + rng.nextFloat() * ((MAP_HALF_X - radius) * 2f);
            float z = -MAP_HALF_Z + radius + rng.nextFloat() * ((MAP_HALF_Z - radius) * 2f);
            if (!pointInWall(x, z, radius)) return new Vector3f(x, 0f, z);
        }

        return new Vector3f(0f, 0f, 0f);
    }

    private Vector3f randomSafeSpawnPoint(float radius) {
        for (int i = 0; i < 220; i++) {
            float x = -MAP_HALF_X + radius + rng.nextFloat() * ((MAP_HALF_X - radius) * 2f);
            float z = -MAP_HALF_Z + radius + rng.nextFloat() * ((MAP_HALF_Z - radius) * 2f);
            if (!pointInWall(x, z, radius)) return new Vector3f(x, 0f, z);
        }
        return safeSpawnPoint(0f, 0f, radius);
    }

    private PickupKind randomAirDropKind() {
        int r = rng.nextInt(5);
        if (r == 0) return PickupKind.REPAIR;
        if (r == 1) return PickupKind.DUAL_CANNON;
        if (r == 2) return PickupKind.MINE;
        if (r == 3) return PickupKind.SHIELD;
        return PickupKind.RAPID;
    }

    private void updateSpawns(SnakeApp.MapContext ctx) {
        if (!botsEnabled) return;
        int aliveTanks = 0;
        for (Tank2D t : enemyTanks) if (t.alive) aliveTanks++;

        wave = 1 + kills / 5;

        if (enemySpawnTimer <= 0f && aliveTanks < 2 + wave / 3) {
            float x = rng.nextBoolean() ? 32f : -32f;
            float z = -22f + rng.nextFloat() * 44f;
            Turret turret = rng.nextFloat() < 0.12f ? Turret.STURM : Turret.values()[rng.nextInt(4)];
            Vector3f safe = safeSpawnPoint(x, z, TANK_RADIUS + 0.7f);
            spawnEnemyTank(ctx, safe.x, safe.z, safe.x > 0f ? FastMath.PI : 0f, turret);
            enemySpawnTimer = Math.max(4.5f, 10f - wave * 0.4f);
        }

        // v13: змейки-враги не спавнятся вообще.
        wormSpawnTimer = 9999f;

        if (pickupSpawnTimer <= 0f) {
            Vector3f p = randomSafeSpawnPoint(2.5f);
            spawnPickup(ctx, randomAirDropKind(), p.x, p.z);
            pickupSpawnTimer = 15f + rng.nextFloat() * 8f;
        }
    }

    private void spawnEnemyTank(SnakeApp.MapContext ctx, float x, float z, float heading, Turret turret) {
        Vector3f safe = safeSpawnPoint(x, z, TANK_RADIUS + 0.7f);
        x = safe.x;
        z = safe.z;
        int model = turret == Turret.STURM ? 4 : turret == Turret.SNIPER ? 3 : turret == Turret.FIRE ? 0 : 1;
        ColorRGBA c = turret == Turret.STURM ? new ColorRGBA(0.30f, 0.34f, 0.20f, 1f)
                : turret == Turret.FIRE ? new ColorRGBA(0.85f, 0.25f, 0.08f, 1f)
                : turret == Turret.FREEZE ? new ColorRGBA(0.15f, 0.55f, 0.95f, 1f)
                : turret == Turret.SNIPER ? new ColorRGBA(0.75f, 0.75f, 0.18f, 1f)
                : new ColorRGBA(0.65f, 0.12f, 0.08f, 1f);
        Tank2D bot = createTank(ctx, "EnemyTank", x, z, heading, true, turret, c, model, -1000 - enemyTanks.size());
        pickNewBotPatrolTarget(bot);
    }

    private void spawnParticles(float x, float z, ColorRGBA color, int count, float power) {
        if (lastCtx == null || fxRoot == null) return;
        for (int i = 0; i < count; i++) {
            Particle2D p = new Particle2D();
            p.x = x; p.z = z;
            float a = rng.nextFloat() * FastMath.TWO_PI;
            float s = (1.0f + rng.nextFloat() * 6.0f) * power;
            p.vx = FastMath.sin(a) * s; p.vz = FastMath.cos(a) * s;
            p.life = 0.25f + rng.nextFloat() * 0.55f; p.maxLife = p.life;
            Geometry g = new Geometry("Particle2D", new Box(0.12f, 0.08f, 0.12f));
            g.setMaterial(lastCtx.unshaded(color)); g.setLocalTranslation(x, 0.7f, z);
            fxRoot.attachChild(g); p.geo = g; particles.add(p);
        }
    }

    private void spawnBulletExplosion(float x, float z, Turret turret) {
        if (turret == Turret.STURM) {
            spawnParticles(x, z, new ColorRGBA(1f, 0.46f, 0.08f, 1f), 70, 3.4f);
            spawnParticles(x, z, new ColorRGBA(1f, 0.90f, 0.25f, 1f), 42, 2.9f);
            spawnParticles(x, z, new ColorRGBA(0.10f, 0.10f, 0.10f, 1f), 42, 2.3f);
            createExplosionFlash(x, z, 3.6f, new ColorRGBA(1f, 0.55f, 0.10f, 0.55f), 0.32f);
        } else {
            spawnParticles(x, z, turretColor(turret), 18, turret == Turret.FIRE ? 1.45f : 1.05f);
            spawnParticles(x, z, new ColorRGBA(1f, 0.82f, 0.22f, 1f), 8, 0.8f);
            createExplosionFlash(x, z, turret == Turret.FIRE ? 1.45f : 1.0f, new ColorRGBA(1f, 0.65f, 0.18f, 0.42f), 0.18f);
        }
    }

    private void createExplosionFlash(float x, float z, float radius, ColorRGBA color, float life) {
        if (lastCtx == null || fxRoot == null) return;
        Geometry g = new Geometry("ExplosionFlash", new Box(radius, 0.018f, radius));
        Material m = lastCtx.unshaded(color);
        try { m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha); } catch (Exception ignored) {}
        g.setMaterial(m);
        g.setQueueBucket(RenderQueue.Bucket.Transparent);
        g.setLocalTranslation(x, 0.62f, z);
        fxRoot.attachChild(g);
        Particle2D p = new Particle2D();
        p.geo = g; p.x = x; p.z = z; p.vx = 0f; p.vz = 0f; p.life = life; p.maxLife = life;
        particles.add(p);
    }

    private void spawnBigExplosion(float x, float z) {
        spawnParticles(x, z, new ColorRGBA(1f, 0.35f, 0.05f, 1f), 48, 2.8f);
        spawnParticles(x, z, new ColorRGBA(1f, 0.85f, 0.20f, 1f), 28, 2.2f);
        spawnParticles(x, z, new ColorRGBA(0.08f, 0.08f, 0.08f, 1f), 24, 1.8f);
        createExplosionFlash(x, z, 3.0f, new ColorRGBA(1f, 0.42f, 0.10f, 0.45f), 0.28f);
    }

    private void updateParticles(float tpf) {
        Iterator<Particle2D> it = particles.iterator();
        while (it.hasNext()) {
            Particle2D p = it.next();
            p.life -= tpf;
            p.x += p.vx * tpf; p.z += p.vz * tpf;
            p.vx *= Math.max(0f, 1f - tpf * 4f); p.vz *= Math.max(0f, 1f - tpf * 4f);
            if (p.geo != null) { p.geo.setLocalTranslation(p.x, 0.7f, p.z); p.geo.rotate(0f, tpf * 8f, 0f); }
            if (p.life <= 0f) { if (p.geo != null) p.geo.removeFromParent(); it.remove(); }
        }
    }

    private void updateBeams(float tpf) {
        Iterator<Beam2D> it = beams.iterator();
        while (it.hasNext()) {
            Beam2D b = it.next(); b.life -= tpf;
            if (b.life <= 0f) { if (b.beam != null) b.beam.removeFromParent(); if (b.glow != null) b.glow.removeFromParent(); it.remove(); }
        }
    }

    private void createTankWreck(SnakeApp.MapContext ctx, Tank2D t) {
        if (ctx == null || wreckRoot == null || t == null) return;

        Node wreck = new Node("TankWreck");
        int model = t.tankModel;
        float bodyW = tankBodyHalfWidth(model);
        float bodyL = tankBodyHalfLength(model);
        float barrelL = tankBarrelHalfLength(model);
        float barrelW = tankBarrelHalfWidth(model);

        ColorRGBA hullColor = t.color != null ? t.color : tankColor(model);
        ColorRGBA trackColor = darken(hullColor, 0.28f);
        ColorRGBA panelColor = darken(hullColor, 0.72f);
        ColorRGBA ember = new ColorRGBA(0.86f, 0.24f, 0.06f, 1f);

        // Корпус остаётся того же размера и примерно того же цвета, что и живой танк.
        addBox(ctx, wreck, "Hull", new Vector3f(0f, 0.15f, 0f), new Vector3f(bodyW, 0.14f, bodyL), hullColor);
        addBox(ctx, wreck, "TrackL", new Vector3f(-bodyW + 0.15f, 0.08f, 0f), new Vector3f(0.24f, 0.12f, bodyL + 0.12f), trackColor);
        addBox(ctx, wreck, "TrackR", new Vector3f(bodyW - 0.15f, 0.08f, 0f), new Vector3f(0.24f, 0.12f, bodyL + 0.12f), trackColor);
        addBox(ctx, wreck, "BurningEngine", new Vector3f(0f, 0.33f, -bodyL * 0.35f), new Vector3f(bodyW * 0.45f, 0.045f, bodyL * 0.18f), ember);
        if (model == 4) {
            addBox(ctx, wreck, "SturmFront", new Vector3f(0f, 0.28f, bodyL * 0.55f), new Vector3f(bodyW * 0.45f, 0.10f, 0.24f), panelColor);
        }

        Node turret = new Node("FallenTurret");
        addBox(ctx, turret, "Turret", new Vector3f(0f, 0.12f, model == 4 ? 0.18f : 0f),
                new Vector3f(model == 4 ? 0.82f : 0.58f, 0.10f, model == 4 ? 0.70f : 0.58f), panelColor);

        if (t.dualVisual || t.turret == Turret.DUAL) {
            addBox(ctx, turret, "BarrelL", new Vector3f(-0.24f, 0.13f, bodyL * 0.78f), new Vector3f(0.12f, 0.06f, barrelL), trackColor);
            addBox(ctx, turret, "BarrelR", new Vector3f(0.24f, 0.13f, bodyL * 0.78f), new Vector3f(0.12f, 0.06f, barrelL), trackColor);
        } else {
            addBox(ctx, turret, "Barrel", new Vector3f(0f, 0.13f, bodyL * 0.78f), new Vector3f(barrelW, 0.06f, barrelL), trackColor);
        }
        if (model == 4) {
            addBox(ctx, turret, "FallenSturmMantlet", new Vector3f(0f, 0.17f, 1.16f), new Vector3f(0.55f, 0.10f, 0.22f), panelColor);
        }

        wreckRoot.attachChild(wreck);
        wreckRoot.attachChild(turret);

        float baseY = 0.18f;
        wreck.setLocalTranslation(t.x, baseY, t.z);
        wreck.setLocalRotation(new Quaternion().fromAngleAxis(t.heading, Vector3f.UNIT_Y));
        turret.setLocalTranslation(t.x, baseY + 0.42f, t.z);
        turret.setLocalRotation(new Quaternion().fromAngleAxis(t.heading, Vector3f.UNIT_Y));

        Vector3f dir = dirFromHeading(t.heading);
        Vector3f side = new Vector3f(dir.z, 0f, -dir.x);
        float launchDir = t.heading + (rng.nextFloat() - 0.5f) * 1.1f + (rng.nextBoolean() ? FastMath.HALF_PI * 0.18f : -FastMath.HALF_PI * 0.18f);
        float launchSpeed = 1.8f + rng.nextFloat() * 2.2f;
        Vector3f launch = dirFromHeading(launchDir).mult(launchSpeed).addLocal(side.mult((rng.nextFloat() - 0.5f) * 1.2f));

        BurningWreck2D bw = new BurningWreck2D();
        bw.hull = wreck;
        bw.turret = turret;
        bw.x = t.x;
        bw.z = t.z;
        bw.baseY = baseY;
        bw.hullAngle = t.heading;
        bw.hullSpin = 0f;
        bw.hullVY = 0.75f + rng.nextFloat() * 0.35f;
        bw.turretX = t.x;
        bw.turretY = baseY + 0.42f;
        bw.turretZ = t.z;
        bw.turretVX = launch.x;
        bw.turretVY = 3.2f + rng.nextFloat() * 1.6f;
        bw.turretVZ = launch.z;
        bw.turretSpin = (rng.nextFloat() - 0.5f) * 4.0f;
        bw.turretYaw = t.heading;
        bw.turretLanded = false;
        bw.life = 24f;
        bw.maxLife = 24f;
        bw.firePower = 1.0f;
        burningWrecks.add(bw);

        WreckCollider2D wc = new WreckCollider2D();
        wc.x = t.x;
        wc.z = t.z;
        wc.hx = bodyW + 0.35f;
        wc.hz = bodyL + 0.35f;
        wreckColliders.add(wc);
    }

    private void updateBurningWrecks(float tpf) {
        Iterator<BurningWreck2D> it = burningWrecks.iterator();
        while (it.hasNext()) {
            BurningWreck2D w = it.next();
            w.life -= tpf;
            float age = w.maxLife - w.life;

            if (w.hull != null) {
                w.hullVY -= 9.0f * tpf;
                w.baseY += w.hullVY * tpf;
                if (w.baseY < 0.18f) { w.baseY = 0.18f; w.hullVY *= -0.18f; }
                w.hull.setLocalTranslation(w.x, w.baseY, w.z);
                w.hull.setLocalRotation(new Quaternion().fromAngleAxis(w.hullAngle, Vector3f.UNIT_Y));
            }

            if (w.turret != null && !w.turretLanded) {
                w.turretVY -= 12.5f * tpf;
                w.turretX += w.turretVX * tpf;
                w.turretY += w.turretVY * tpf;
                w.turretZ += w.turretVZ * tpf;
                w.turretYaw += w.turretSpin * tpf;
                if (w.turretY <= 0.22f) {
                    w.turretY = 0.22f;
                    w.turretLanded = true;
                    w.turretVX *= 0.10f;
                    w.turretVZ *= 0.10f;
                    w.turretSpin *= 0.08f;
                    spawnParticles(w.turretX, w.turretZ, new ColorRGBA(0.30f, 0.30f, 0.30f, 1f), 10, 1.1f);
                }
                w.turret.setLocalTranslation(w.turretX, w.turretY, w.turretZ);
                w.turret.setLocalRotation(new Quaternion().fromAngleAxis(w.turretYaw, Vector3f.UNIT_Y));
            } else if (w.turret != null) {
                w.turret.setLocalTranslation(w.turretX, 0.22f, w.turretZ);
                w.turret.setLocalRotation(new Quaternion().fromAngleAxis(w.turretYaw, Vector3f.UNIT_Y));
            }

            float fireRate = age < 1.2f ? 18f : age < 4f ? 9f : 5f;
            if (w.life > 0f && rng.nextFloat() < tpf * fireRate) {
                float spread = age < 1.0f ? 2.2f : 1.5f;
                spawnParticles(w.x + (rng.nextFloat()-0.5f)*spread, w.z + (rng.nextFloat()-0.5f)*spread,
                        new ColorRGBA(1f, 0.24f, 0.04f, 1f), age < 1.0f ? 6 : 3, age < 1.0f ? 1.1f : 0.55f);
                if (rng.nextBoolean()) {
                    spawnParticles(w.x + (rng.nextFloat()-0.5f)*spread, w.z + (rng.nextFloat()-0.5f)*spread,
                            new ColorRGBA(0.18f, 0.18f, 0.18f, 1f), 2, 0.45f);
                }
            }

            if (w.life <= 0f) it.remove();
        }
    }


    private void spawnTrackMarks(Tank2D t) {
        if (t == null || trackRoot == null || lastCtx == null) return;
        t.trackTimer -= 0.05f;
        if (t.trackTimer > 0f) return;
        t.trackTimer = 0.11f + rng.nextFloat() * 0.06f;

        Vector3f back = dirFromHeading(t.heading).mult(-tankBodyHalfLength(t.tankModel) * 0.75f);
        Vector3f side = new Vector3f(back.z, 0f, -back.x);
        if (side.lengthSquared() > 0.0001f) side.normalizeLocal();
        side.multLocal(tankBodyHalfWidth(t.tankModel) * 0.72f);

        createTrackMark(t.x + back.x + side.x, t.z + back.z + side.z, t.heading);
        createTrackMark(t.x + back.x - side.x, t.z + back.z - side.z, t.heading);
    }

    private void createTrackMark(float x, float z, float heading) {
        Geometry g = new Geometry("TrackMark", new Box(0.10f, 0.012f, 0.48f));
        Material m = lastCtx.unshaded(new ColorRGBA(0.02f, 0.018f, 0.012f, 0.46f));
        try { m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha); } catch (Exception ignored) {}
        g.setMaterial(m);
        g.setQueueBucket(RenderQueue.Bucket.Transparent);
        g.setLocalTranslation(x, 0.035f, z);
        g.setLocalRotation(new Quaternion().fromAngleAxis(heading, Vector3f.UNIT_Y));
        trackRoot.attachChild(g);

        TrackMark2D tm = new TrackMark2D();
        tm.geo = g;
        tm.life = 8.0f;
        trackMarks.add(tm);
    }

    private void updateTrackMarks(float tpf) {
        Iterator<TrackMark2D> it = trackMarks.iterator();
        while (it.hasNext()) {
            TrackMark2D tm = it.next();
            tm.life -= tpf;
            if (tm.life <= 0f) {
                if (tm.geo != null) tm.geo.removeFromParent();
                it.remove();
            } else if (tm.geo != null && tm.geo.getMaterial() != null) {
                float alpha = Math.min(0.46f, tm.life / 8f * 0.46f);
                setMatColor(tm.geo.getMaterial(), new ColorRGBA(0.02f, 0.018f, 0.012f, alpha));
            }
        }
    }

    private void buildClouds(SnakeApp.MapContext ctx) {
        if (ctx == null || cloudRoot == null) return;
        clearNode(cloudRoot);
        cloudMaterial = ctx.unshaded(new ColorRGBA(0.90f, 0.92f, 0.96f, 0.42f));
        cloudShadowMaterial = ctx.unshaded(new ColorRGBA(0.02f, 0.025f, 0.03f, 0.18f));
        try {
            cloudMaterial.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            cloudMaterial.getAdditionalRenderState().setDepthWrite(false);
            cloudShadowMaterial.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            cloudShadowMaterial.getAdditionalRenderState().setDepthWrite(false);
        } catch (Exception ignored) {}

        Random cr = new Random(42);
        for (int i = 0; i < 25; i++) {
            Node cloud = new Node("Cloud" + i);
            float baseRadius = 2.2f + cr.nextFloat() * 3.8f;

            Geometry main = new Geometry("CloudMain", new Sphere(8, 10, baseRadius));
            main.setMaterial(cloudMaterial);
            main.setShadowMode(RenderQueue.ShadowMode.Off);
						main.setQueueBucket(RenderQueue.Bucket.Transparent);
            cloud.attachChild(main);

            Geometry second = new Geometry("CloudSecond", new Sphere(6, 8, baseRadius * 0.70f));
            second.setLocalTranslation(baseRadius * 0.8f, 0f, 0f);
            second.setMaterial(cloudMaterial);
            second.setShadowMode(RenderQueue.ShadowMode.Off);
						second.setQueueBucket(RenderQueue.Bucket.Transparent);
            cloud.attachChild(second);

            Geometry third = new Geometry("CloudThird", new Sphere(5, 7, baseRadius * 0.50f));
            third.setLocalTranslation(-baseRadius * 0.6f, baseRadius * 0.2f, 0f);
            third.setMaterial(cloudMaterial);
            third.setShadowMode(RenderQueue.ShadowMode.Off);
						third.setQueueBucket(RenderQueue.Bucket.Transparent);
            cloud.attachChild(third);

            Geometry shadow = new Geometry("CloudSoftShadow", new Box(baseRadius * 1.55f, 0.012f, baseRadius * 0.95f));
            shadow.setMaterial(cloudShadowMaterial);
            shadow.setQueueBucket(RenderQueue.Bucket.Transparent);
            shadow.setLocalTranslation(0f, -16.0f, 0f);
            cloud.attachChild(shadow);

            cloud.setLocalTranslation(
                    (cr.nextFloat() - 0.5f) * MAP_HALF_X * 3.0f,
                    15f + cr.nextFloat() * 9f,
                    (cr.nextFloat() - 0.5f) * MAP_HALF_Z * 3.0f
            );
            cloud.setUserData("speed", 0.25f + cr.nextFloat() * 0.35f);
            cloudRoot.attachChild(cloud);
        }
				// === ДИАГНОСТИКА ОБЛАКОВ ===
				/* System.out.println("=== CLOUD DIAGNOSTIC ===");
				int cloudNum = 0;
				for (Spatial child : cloudRoot.getChildren()) {
						if (child instanceof Node cloudNode) {
								System.out.println("Cloud " + cloudNum + " pos: " + child.getLocalTranslation());
								for (Spatial part : cloudNode.getChildren()) {
										if (part instanceof Geometry geom) {
												Material mat = geom.getMaterial();
												System.out.println("  " + geom.getName() +
														" bucket=" + geom.getLocalQueueBucket() +
														" shadow=" + geom.getShadowMode() +
														" matAlpha=" + (mat != null && mat.getParamValue("Color") instanceof ColorRGBA c ? c.a : "?"));
										}
								}
								cloudNum++;
						}
				}
				System.out.println("=== END CLOUD DIAGNOSTIC ==="); */
    }

    private void updateClouds(float tpf) {
        if (cloudRoot == null) return;
        for (Spatial s : new ArrayList<Spatial>(cloudRoot.getChildren())) {
            if (s == null) continue;
            Vector3f p = s.getLocalTranslation();
            Float sp = s.getUserData("speed");
            p.x += tpf * (sp != null ? sp : 0.35f);
            if (p.x > MAP_HALF_X * 2.0f) p.x = -MAP_HALF_X * 2.0f;
            s.setLocalTranslation(p);
        }
    }

    private void buildOutsideDecor(SnakeApp.MapContext ctx) {
        if (ctx == null || decorRoot == null) return;
        Random dr = new Random(arenaSeed ^ 0x55AA77);

        // Деревья за пределами арены.
        for (int i = 0; i < 42; i++) {
            float side = dr.nextInt(4);
            float x, z;
            if (side == 0) { x = -MAP_HALF_X - 6f - dr.nextFloat() * 25f; z = -MAP_HALF_Z - 20f + dr.nextFloat() * (MAP_HALF_Z * 2f + 40f); }
            else if (side == 1) { x = MAP_HALF_X + 6f + dr.nextFloat() * 25f; z = -MAP_HALF_Z - 20f + dr.nextFloat() * (MAP_HALF_Z * 2f + 40f); }
            else if (side == 2) { x = -MAP_HALF_X - 20f + dr.nextFloat() * (MAP_HALF_X * 2f + 40f); z = -MAP_HALF_Z - 6f - dr.nextFloat() * 22f; }
            else { x = -MAP_HALF_X - 20f + dr.nextFloat() * (MAP_HALF_X * 2f + 40f); z = MAP_HALF_Z + 6f + dr.nextFloat() * 22f; }

            float h = 1.3f + dr.nextFloat() * 1.3f;
            Geometry trunk = ctx.addVisualBox(decorRoot, "TreeTrunk", new Vector3f(x, h * 0.35f, z), new Vector3f(0.22f, h * 0.35f, 0.22f), new ColorRGBA(0.22f, 0.13f, 0.06f, 1f));
            if (trunk != null) trunk.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);

            Geometry crown = new Geometry("TreeCrown", new Sphere(8, 8, 0.9f + dr.nextFloat() * 0.7f));
            crown.setMaterial(ctx.lit(new ColorRGBA(0.07f, 0.28f + dr.nextFloat() * 0.15f, 0.08f, 1f)));
            crown.setLocalTranslation(x, h, z);
            crown.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            decorRoot.attachChild(crown);
        }

        // Горы и скалы по дальнему периметру.
        for (int i = 0; i < 26; i++) {
            float angle = FastMath.TWO_PI * i / 26f;
            float radiusX = MAP_HALF_X + 34f + dr.nextFloat() * 16f;
            float radiusZ = MAP_HALF_Z + 28f + dr.nextFloat() * 15f;
            float x = FastMath.sin(angle) * radiusX;
            float z = FastMath.cos(angle) * radiusZ;
            float h = 3.0f + dr.nextFloat() * 6.5f;
            Geometry rock = ctx.addVisualBox(decorRoot, "MountainRock", new Vector3f(x, h * 0.48f - 0.1f, z),
                    new Vector3f(2.5f + dr.nextFloat() * 3.5f, h * 0.50f, 2.0f + dr.nextFloat() * 3.0f),
                    new ColorRGBA(0.22f + dr.nextFloat() * 0.08f, 0.22f + dr.nextFloat() * 0.08f, 0.20f + dr.nextFloat() * 0.07f, 1f));
            if (rock != null) rock.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
        }
    }

    private void buildUi(SnakeApp.MapContext ctx) {
        if (ctx == null || uiRoot == null) return;
        clearNode(uiRoot);
        BitmapFont font = loadMapFont(ctx.assetManager);
        uiPanel = new Geometry("TB2D_UiBg", new Box(350f, 72f, 0.1f));
        Material m = ctx.unshaded(new ColorRGBA(0f, 0f, 0f, 0.70f));
        try { m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha); } catch (Exception ignored) {}
        uiPanel.setMaterial(m); uiPanel.setQueueBucket(RenderQueue.Bucket.Gui); uiRoot.attachChild(uiPanel);
        titleText = makeText(font, 24f, new ColorRGBA(0.25f, 0.95f, 0.35f, 1f));
        helpText = makeText(font, 15f, new ColorRGBA(0.85f, 0.85f, 0.78f, 1f));
        statsText = makeText(font, 17f, new ColorRGBA(1f, 0.86f, 0.24f, 1f));
        turretText = makeText(font, 17f, new ColorRGBA(0.3f, 0.85f, 1f, 1f));
        centerText = makeText(font, 42f, new ColorRGBA(1f, 1f, 1f, 1f));
        uiRoot.attachChild(titleText); uiRoot.attachChild(helpText); uiRoot.attachChild(statsText); uiRoot.attachChild(turretText); uiRoot.attachChild(centerText);
    }

    private void updateUiLayout(SnakeApp.MapContext ctx) {
        if (ctx == null || ctx.camera == null || titleText == null) return;
        float W = ctx.camera.getWidth(), H = ctx.camera.getHeight();
        if (uiPanel != null) uiPanel.setLocalTranslation(362f, H - 82f, -1f);
        titleText.setLocalTranslation(28f, H - 36f, 0f);
        helpText.setLocalTranslation(28f, H - 66f, 0f);
        statsText.setLocalTranslation(28f, H - 96f, 0f);
        turretText.setLocalTranslation(W - 520f, H - 38f, 0f);
        if (centerText != null && notifyTimer > 0f) centerText.setLocalTranslation(W * 0.5f - centerText.getLineWidth() * 0.5f, H * 0.5f + 45f, 0f);		
    }

    private void updateUiText() {
        if (titleText == null) return;
        if (mode == Mode.GARAGE) {
            titleText.setText("TANK BATTLE 2D / ВЫБОР ТАНКА");
            if (localReady) {
                int expected = lastCtx != null && lastCtx.players != null && !lastCtx.players.isEmpty() ? lastCtx.players.size() : (lastCtx != null && lastCtx.solo ? 1 : 2);
                helpText.setText("Танк выбран. Ожидание игроков: " + readyCount(lastCtx) + " / " + Math.max(1, expected));
            } else helpText.setText("A/D или Q/E — выбрать танк | ENTER/SPACE — готов");
            statsText.setText("Танк: " + TANK_NAMES[selectedTank] + " | HP: " + tankHp(selectedTank) + " | Скорость: " + Math.round(tankSpeed(selectedTank) * 10f));
            if (selectedTank == 4) turretText.setText("SturmTiger: SHIFT прицел, SPACE выстрел | X — мина");
            else turretText.setText("SPACE — огонь | X — мина | без режима прицела");
            return;
        }
        int aliveTanks = 0; for (Tank2D t : enemyTanks) if (t.alive) aliveTanks++;
        int hp = player == null ? 0 : Math.max(0, player.hp);
        titleText.setText("TANK BATTLE 2D");
        if (selectedTank == 4) helpText.setText("WASD/стрелки — ехать | SPACE — огонь | SHIFT — прицел | X — мина");
        else helpText.setText("WASD/стрелки — ехать | SPACE — огонь | X — мина");
        String enemyInfo = botsEnabled ? (aliveTanks + " танк") : (remoteTanks.size() + " игрок(ов)");
        statsText.setText("HP: " + hp + " | Очки: " + score + " | Волна: " + wave + " | Враги: " + enemyInfo + " | Мины: " + mineCharges);
        String sturm = selectedTank == 4 ? " | Наведение: " + Math.round(clamp(sturmAimCharge / 1.1f, 0f, 1f) * 100f) + "%" : "";
        turretText.setText("Танк: " + TANK_NAMES[selectedTank] + " | Башня: " + turretName(selectedTurret) + " | Щит: " + ceil0(playerShieldTimer) + " | 2 пушки: " + ceil0(dualTimer) + sturm);
    }

    private BitmapFont loadMapFont(com.jme3.asset.AssetManager am) {
        try { return am.loadFont("Fonts/bitmap.fnt"); }
        catch (Exception e) { return am.loadFont("Interface/Fonts/Default.fnt"); }
    }

    private BitmapText makeText(BitmapFont font, float size, ColorRGBA color) {
        BitmapText t = new BitmapText(font); t.setSize(size); t.setColor(color); t.setQueueBucket(RenderQueue.Bucket.Gui); return t;
    }

		private void showCenter(String text, float duration, ColorRGBA color) {
				if (centerText != null) {
						centerText.setText(text);
						centerText.setColor(color);
						notifyTimer = duration;
				}
		}

    private void killPlayerTank(SnakeApp.MapContext ctx, String reason) {
        if (deathTriggered) return;
        deathTriggered = true;
        mode = Mode.FINISHED;
        if (player != null) { player.alive = false; player.hp = 0; if (player.node != null) player.node.removeFromParent(); }
        showCenter("ТАНК УНИЧТОЖЕН", 3.0f, new ColorRGBA(1f, 0.18f, 0.10f, 1f));
        if (ctx != null) { broadcast(ctx, "TB2D_DEAD|" + ctx.myIndex); ctx.killSnake(ctx.myIndex, reason == null ? "танк уничтожен" : reason); }
    }

    private void syncMyTank(SnakeApp.MapContext ctx, float tpf) {
        if (ctx == null || ctx.solo || player == null || mode != Mode.PLAYING) return;
        netSyncTimer -= tpf; if (netSyncTimer > 0f) return; netSyncTimer = 0.05f;
        broadcast(ctx, "TB2D_STATE|" + ctx.myIndex + "|" + fmt(player.x) + "|" + fmt(player.z) + "|" + fmt(player.heading) + "|" + player.hp + "|" + selectedTank + "|" + selectedTurret.ordinal() + "|" + (player.alive ? 1 : 0) + "|" + (dualTimer > 0f ? 1 : 0));
    }

    @Override
    public void onMapNetMessage(SnakeApp.MapContext ctx, String payload) {
        if (ctx == null || payload == null || payload.isBlank()) return;
        try {
            String[] p = payload.split("\\|");
            if (p.length == 0 || !p[0].startsWith("TB2D_")) return;
            if ("TB2D_SEED".equals(p[0]) && p.length >= 2) {
                if (!ctx.host) { arenaSeed = parseInt(p[1], arenaSeed); seedReceived = true; buildArena(ctx, arenaSeed); }
            } else if ("TB2D_READY".equals(p[0]) && p.length >= 4) {
                int idx = parseInt(p[1], -1); if (idx == ctx.myIndex) return;
                int model = parseInt(p[2], 1); Turret turret = turretByOrdinal(parseInt(p[3], 0));
                readyPlayers.put(idx, true); readyTankModels.put(idx, model); readyTurrets.put(idx, turret);
                showCenter("Игрок " + (idx + 1) + " выбрал танк", 2f, new ColorRGBA(0.25f, 0.85f, 1f, 1f));
                if (localReady && allPlayersReady(ctx) && mode == Mode.GARAGE) startBattle(ctx);
            } else if ("TB2D_JOIN".equals(p[0]) && p.length >= 4) {
                int idx = parseInt(p[1], -1); if (idx >= 0 && idx != ctx.myIndex) getOrCreateRemoteTank(ctx, idx, parseInt(p[2], 1), turretByOrdinal(parseInt(p[3], 0)));
            } else if ("TB2D_STATE".equals(p[0]) && p.length >= 10) {
                int idx = parseInt(p[1], -1); if (idx < 0 || idx == ctx.myIndex) return;
                Tank2D rt = getOrCreateRemoteTank(ctx, idx, parseInt(p[6], 1), turretByOrdinal(parseInt(p[7], 0)));
                rt.x = parseFloat(p[2], rt.x); rt.z = parseFloat(p[3], rt.z); rt.heading = parseFloat(p[4], rt.heading); rt.hp = parseInt(p[5], rt.hp); rt.turret = turretByOrdinal(parseInt(p[7], rt.turret.ordinal())); rt.alive = parseInt(p[8], 1) == 1; rt.dualVisual = parseInt(p[9], 0) == 1;
                updateTankNode(rt);
            } else if ("TB2D_SHOT".equals(p[0]) && p.length >= 7) {
                int idx = parseInt(p[1], -1); if (idx == ctx.myIndex) return;
                Turret turret = turretByOrdinal(parseInt(p[6], 0));
                Tank2D owner = getOrCreateRemoteTank(ctx, idx, 1, turret);
                Bullet2D b = new Bullet2D(); b.x = parseFloat(p[2], owner.x); b.z = parseFloat(p[3], owner.z); b.vx = parseFloat(p[4], 0f); b.vz = parseFloat(p[5], 0f); b.turret = turret; b.owner = owner; b.ownerIndex = idx; b.damage = turretDamage(turret); b.life = turret == Turret.STURM ? 2.0f : 2.2f; b.maxBounces = turret == Turret.STURM ? 0 : 2;
                Geometry g = new Geometry("RemoteBullet", new Sphere(10, 10, turret == Turret.STURM ? 0.72f : 0.34f)); g.setMaterial(ctx.unshaded(turretColor(turret))); g.setLocalTranslation(b.x, 0.52f, b.z); bulletRoot.attachChild(g); b.geo = g; bullets.add(b);
            } else if ("TB2D_BEAM".equals(p[0]) && p.length >= 6) {
                int idx = parseInt(p[1], -1); if (idx == ctx.myIndex) return;
                spawnNetworkBeam(ctx, idx, parseFloat(p[2], 0f), parseFloat(p[3], 0f), parseFloat(p[4], 0f), parseFloat(p[5], 10f));
            } else if ("TB2D_MINE".equals(p[0]) && p.length >= 4) {
                int idx = parseInt(p[1], -1); if (idx != ctx.myIndex) spawnMine(ctx, parseFloat(p[2], 0f), parseFloat(p[3], 0f), idx);
            } else if ("TB2D_AIRDROP".equals(p[0])) {
                if (!ctx.host && p.length >= 8) {
                    spawnAirplane(ctx, pickupByOrdinal(parseInt(p[1], 0)),
                            parseFloat(p[2], -MAP_HALF_X - 13f), parseFloat(p[3], 0f),
                            parseFloat(p[4], MAP_HALF_X + 13f), parseFloat(p[5], 0f),
                            parseFloat(p[6], 0f), parseFloat(p[7], 0f));
                } else if (!ctx.host && p.length >= 4) {
                    spawnAirplane(ctx, pickupByOrdinal(parseInt(p[1], 0)), parseFloat(p[2], 0f), parseFloat(p[3], 0f));
                }
            } else if ("TB2D_DEAD".equals(p[0]) && p.length >= 2) {
                int idx = parseInt(p[1], -1); Tank2D rt = remoteTanks.get(idx); if (rt != null) destroyTank(ctx, rt, "игрок уничтожен");
            }
        } catch (Exception ignored) {}
    }

    private Tank2D getOrCreateRemoteTank(SnakeApp.MapContext ctx, int idx, int model, Turret turret) {
        Tank2D t = remoteTanks.get(idx);
        if (t != null) return t;
        float spawnX = (idx % 2 == 0) ? -31f : 31f;
        float spawnZ = idx <= 1 ? 0f : (-18f + (idx % 4) * 12f);
        t = createTank(ctx, "RemoteTank" + idx, spawnX, spawnZ, spawnX < 0f ? 0f : FastMath.PI, false, turret, tankColor(model), model, idx);
        remoteTanks.put(idx, t);
        return t;
    }

    private void createRemotePlaceholders(SnakeApp.MapContext ctx) {
        if (ctx == null || ctx.players == null) return;
        for (int i = 0; i < ctx.players.size(); i++) if (i != ctx.myIndex) getOrCreateRemoteTank(ctx, i, readyTankModels.getOrDefault(i, 1), readyTurrets.getOrDefault(i, Turret.DEFAULT));
    }

    private void spawnNetworkBeam(SnakeApp.MapContext ctx, int ownerIndex, float startX, float startZ, float heading, float length) {
        createSniperBeam(ctx, startX, startZ, heading, length);
        Vector3f dir = dirFromHeading(heading);
        spawnParticles(startX + dir.x * length, startZ + dir.z * length, new ColorRGBA(1f, 1f, 1f, 1f), 10, 1f);
        if (player != null && player.alive) {
            float endX = startX + dir.x * length, endZ = startZ + dir.z * length;
            if (distancePointSegment2D(player.x, player.z, startX, startZ, endX, endZ) <= TANK_RADIUS + 0.18f) damageOrShield(ctx, player, turretDamage(Turret.SNIPER), null, "Щит поглотил снайперский луч!");
        }
    }

    private float distancePointSegment2D(float px, float pz, float ax, float az, float bx, float bz) {
        float abx = bx - ax, abz = bz - az, apx = px - ax, apz = pz - az;
        float denom = Math.max(0.0001f, abx * abx + abz * abz);
        float t = clamp((apx * abx + apz * abz) / denom, 0f, 1f);
        float cx = ax + abx * t, cz = az + abz * t;
        float dx = px - cx, dz = pz - cz;
        return (float)Math.sqrt(dx * dx + dz * dz);
    }

		private void updateDayNight(SnakeApp.MapContext ctx, float tpf) {
				// В гараже всегда день, фары выключены, облака яркие
				if (mode != Mode.PLAYING && mode != Mode.FINISHED) {
						if (headlightsActive) {
								headlightsActive = false;
								setHeadlightsVisible(player, false);
								for (Tank2D t : enemyTanks) setHeadlightsVisible(t, false);
								for (Tank2D t : remoteTanks.values()) setHeadlightsVisible(t, false);
								for (Airplane2D a : airplanes) setAirplaneLightsVisible(a, false);
						}
						updateCloudBrightness(1f); // облака как днём
						return;
				}

				// Игровой режим — работаем от глобального времени
				float dayBrightness = ctx.getDayBrightness();
				float currentNight = 1f - dayBrightness;
				updateCloudBrightness(dayBrightness);

				if (!headlightsActive && currentNight >= 0.68f) headlightsActive = true;
				else if (headlightsActive && currentNight <= 0.50f) headlightsActive = false;

				setHeadlightsVisible(player, headlightsActive);
				for (Tank2D t : enemyTanks) setHeadlightsVisible(t, headlightsActive);
				for (Tank2D t : remoteTanks.values()) setHeadlightsVisible(t, headlightsActive);
				for (Airplane2D a : airplanes) setAirplaneLightsVisible(a, headlightsActive);
		}

    private void saveCameraState(SnakeApp.MapContext ctx) {
        if (cameraSaved || ctx == null || ctx.camera == null) return;
        try {
            oldParallelProjection = ctx.camera.isParallelProjection(); oldFrustumNear = ctx.camera.getFrustumNear(); oldFrustumFar = ctx.camera.getFrustumFar();
            oldFrustumLeft = ctx.camera.getFrustumLeft(); oldFrustumRight = ctx.camera.getFrustumRight(); oldFrustumTop = ctx.camera.getFrustumTop(); oldFrustumBottom = ctx.camera.getFrustumBottom(); cameraSaved = true;
        } catch (Exception ignored) { cameraSaved = false; }
    }

    private void restoreCameraState(SnakeApp.MapContext ctx) {
        if (ctx == null || ctx.camera == null) return;
        try {
            if (cameraSaved) { ctx.camera.setParallelProjection(oldParallelProjection); ctx.camera.setFrustum(oldFrustumNear, oldFrustumFar, oldFrustumLeft, oldFrustumRight, oldFrustumTop, oldFrustumBottom); }
            else { float aspect = ctx.camera.getWidth() / Math.max(1f, (float)ctx.camera.getHeight()); ctx.camera.setParallelProjection(false); ctx.camera.setFrustumPerspective(45f, aspect, 1f, 1000f); }
        } catch (Exception ignored) {
            try { float aspect = ctx.camera.getWidth() / Math.max(1f, (float)ctx.camera.getHeight()); ctx.camera.setParallelProjection(false); ctx.camera.setFrustumPerspective(45f, aspect, 1f, 1000f); } catch (Exception ignoredToo) {}
        }
        cameraSaved = false;
    }

    private void broadcast(SnakeApp.MapContext ctx, String payload) { if (ctx != null) try { ctx.sendMapEvent(payload); } catch (Exception ignored) {} }

    private boolean hasClearShot(float ax, float az, float bx, float bz, boolean ignoreWalls) {
        if (ignoreWalls) return true;
        for (Wall2D w : walls) {
            if (lineIntersectsAabb(ax, az, bx, bz, w.x, w.z, w.hx + 0.35f, w.hz + 0.35f)) return false;
        }
        return true;
    }

    private boolean lineIntersectsAabb(float ax, float az, float bx, float bz, float cx, float cz, float hx, float hz) {
        float dx = bx - ax;
        float dz = bz - az;
        float tMin = 0f;
        float tMax = 1f;

        if (Math.abs(dx) < 0.0001f) {
            if (ax < cx - hx || ax > cx + hx) return false;
        } else {
            float inv = 1f / dx;
            float t1 = (cx - hx - ax) * inv;
            float t2 = (cx + hx - ax) * inv;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return false;
        }

        if (Math.abs(dz) < 0.0001f) {
            if (az < cz - hz || az > cz + hz) return false;
        } else {
            float inv = 1f / dz;
            float t1 = (cz - hz - az) * inv;
            float t2 = (cz + hz - az) * inv;
            if (t1 > t2) { float tmp = t1; t1 = t2; t2 = tmp; }
            tMin = Math.max(tMin, t1);
            tMax = Math.min(tMax, t2);
            if (tMin > tMax) return false;
        }

        return tMax >= 0f && tMin <= 1f;
    }

    private boolean pointInWall(float x, float z, float r) { for (Wall2D w : walls) if (circleAabb(x, z, r, w.x, w.z, w.hx, w.hz)) return true; return false; }
    private boolean circleAabb(float cx, float cz, float r, float bx, float bz, float hx, float hz) { float nx = clamp(cx, bx - hx, bx + hx), nz = clamp(cz, bz - hz, bz + hz); float dx = cx - nx, dz = cz - nz; return dx * dx + dz * dz <= r * r; }
    private Vector3f dirFromHeading(float h) { return new Vector3f(FastMath.sin(h), 0f, FastMath.cos(h)); }
    private float angleDiff(float from, float to) { float d = to - from; while (d > FastMath.PI) d -= FastMath.TWO_PI; while (d < -FastMath.PI) d += FastMath.TWO_PI; return d; }
    private float distance2D(float ax, float az, float bx, float bz) {
        float dx = ax - bx;
        float dz = az - bz;
        return (float)Math.sqrt(dx * dx + dz * dz);
    }

    private float dist2(float ax, float az, float bx, float bz) { float dx = ax - bx, dz = az - bz; return dx * dx + dz * dz; }
    private float clamp(float v, float l, float h) { return Math.max(l, Math.min(h, v)); }
    private int wrap(int v, int c) { while (v < 0) v += c; while (v >= c) v -= c; return v; }
    private int ceil0(float v) { return Math.max(0, (int)Math.ceil(v)); }
    private ColorRGBA brighten(ColorRGBA c, float k) { return new ColorRGBA(clamp(c.r*k,0f,1f), clamp(c.g*k,0f,1f), clamp(c.b*k,0f,1f), c.a); }
    private ColorRGBA darken(ColorRGBA c, float k) { return new ColorRGBA(clamp(c.r*k,0f,1f), clamp(c.g*k,0f,1f), clamp(c.b*k,0f,1f), c.a); }
    private int tankHp(int model) { return TANK_HP[wrap(model, TANK_HP.length)]; }
    private float tankSpeed(int model) { return TANK_SPEED[wrap(model, TANK_SPEED.length)]; }
    private float tankTurn(int model) { return TANK_TURN[wrap(model, TANK_TURN.length)]; }
    private Turret defaultTurretForTank(int model) { if (model == 0) return Turret.FIRE; if (model == 3) return Turret.SNIPER; if (model == 4) return Turret.STURM; return Turret.DEFAULT; }
    private ColorRGBA tankColor(int model) {
        model = wrap(model, TANK_NAMES.length);
        ColorRGBA base = lobbySnakeColor();
        if (model == 0) return brighten(base, 1.22f);
        if (model == 2) return darken(base, 0.66f);
        if (model == 3) return mixColor(brighten(base, 1.08f), new ColorRGBA(1f, 0.92f, 0.20f, 1f), 0.18f);
        if (model == 4) return mixColor(darken(base, 0.58f), new ColorRGBA(0.30f, 0.34f, 0.20f, 1f), 0.24f);
        return base;
    }

    private ColorRGBA lobbySnakeColor() {
        String[] fieldNames = new String[] { "selectedSnakeColor", "snakeColor", "currentSnakeColor" };
        for (String fieldName : fieldNames) {
            try {
                Field f = SnakeApp.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object value = f.get(null);
                if (value instanceof ColorRGBA) return ((ColorRGBA)value).clone();
            } catch (Exception ignored) {}
        }
        return new ColorRGBA(0.15f, 0.90f, 0.30f, 1f);
    }

    private ColorRGBA mixColor(ColorRGBA a, ColorRGBA b, float t) {
        t = clamp(t, 0f, 1f);
        return new ColorRGBA(
                a.r + (b.r - a.r) * t,
                a.g + (b.g - a.g) * t,
                a.b + (b.b - a.b) * t,
                1f
        );
    }
    private Turret turretByOrdinal(int ordinal) { Turret[] values = Turret.values(); return values[wrap(ordinal, values.length)]; }
    private PickupKind pickupByOrdinal(int ordinal) { PickupKind[] values = PickupKind.values(); return values[wrap(ordinal, values.length)]; }
    private String fmt(float v) { return String.format(java.util.Locale.US, "%.3f", v); }
    private int parseInt(String v, int fallback) { try { return Integer.parseInt(v); } catch (Exception e) { return fallback; } }
    private float parseFloat(String v, float fallback) { try { return Float.parseFloat(v); } catch (Exception e) { return fallback; } }
    private int makeLocalSeed(SnakeApp.MapContext ctx) { return 73856093 ^ (ctx == null ? 0 : ctx.mapId.hashCode()) ^ (int)(System.nanoTime() & 0xffff); }

    private String turretName(Turret t) { if (t == Turret.FIRE) return "Огненная"; if (t == Turret.FREEZE) return "Заморозка"; if (t == Turret.SNIPER) return "Снайперка"; if (t == Turret.DUAL) return "Две пушки"; if (t == Turret.STURM) return "SturmTiger"; return "Обычная"; }
    private ColorRGBA turretColor(Turret t) { if (t == Turret.FIRE) return new ColorRGBA(1f,0.35f,0.05f,1f); if (t == Turret.FREEZE) return new ColorRGBA(0.25f,0.75f,1f,1f); if (t == Turret.SNIPER) return new ColorRGBA(1f,1f,0.18f,1f); if (t == Turret.DUAL) return new ColorRGBA(0.85f,0.35f,1f,1f); if (t == Turret.STURM) return new ColorRGBA(1f,0.60f,0.10f,1f); return new ColorRGBA(1f,1f,1f,1f); }
    private ColorRGBA pickupColor(PickupKind k) { if (k == PickupKind.SHIELD) return new ColorRGBA(0.2f,0.85f,1f,1f); if (k == PickupKind.REPAIR) return new ColorRGBA(0.25f,1f,0.25f,1f); if (k == PickupKind.DUAL_CANNON) return new ColorRGBA(0.85f,0.35f,1f,1f); if (k == PickupKind.MINE) return new ColorRGBA(1f,0.15f,0.12f,1f); return new ColorRGBA(1f,0.75f,0.2f,1f); }
    private float turretBulletSpeed(Turret t) { if (t == Turret.STURM) return 18f; if (t == Turret.SNIPER) return 42f; if (t == Turret.FIRE) return 20f; if (t == Turret.FREEZE) return 24f; return 32f; }
    private float turretCooldown(Turret t) { if (t == Turret.STURM) return 8.0f; if (t == Turret.SNIPER) return 1.15f; if (t == Turret.FIRE) return 0.30f; if (t == Turret.FREEZE) return 0.55f; if (t == Turret.DUAL) return 0.50f; return 0.42f; }
    private int turretDamage(Turret t) { if (t == Turret.STURM) return 120; if (t == Turret.SNIPER) return 45; if (t == Turret.FIRE) return 16; if (t == Turret.FREEZE) return 12; if (t == Turret.DUAL) return 20; return 24; }
    private void setMatColor(Material m, ColorRGBA c) { if (m == null || c == null) return; try { m.setColor("Diffuse", c); } catch (Exception ignored) {} try { m.setColor("Ambient", c.mult(0.25f)); } catch (Exception ignored) {} try { m.setColor("Color", c); } catch (Exception ignored) {} }
    
		private void dumpMaterialParams(Material mat) {
				if (mat == null) {
						System.out.println("Material is null");
						return;
				}
				System.out.println("Def name: " + (mat.getMaterialDef() != null ? mat.getMaterialDef().getName() : "null"));
				try {
						java.util.Collection<com.jme3.material.MatParam> params = mat.getMaterialDef().getMaterialParams();
						if (params != null) {
								for (com.jme3.material.MatParam mp : params) {
										String name = mp.getName();
										Object val = mp.getValue();
										if (val != null) {
												System.out.println("  Param " + name + " = " + val + " (type: " + mp.getVarType() + ")");
										}
								}
						}
				} catch (Exception e) {
						System.out.println("Error getting params: " + e.getMessage());
				}
				com.jme3.material.RenderState rs = mat.getAdditionalRenderState();
				if (rs != null) {
						System.out.println("  BlendMode: " + rs.getBlendMode());
						System.out.println("  FaceCull: " + rs.getFaceCullMode());
						System.out.println("  DepthWrite: " + rs.isDepthWrite());
						System.out.println("  DepthTest: " + rs.isDepthTest());
				}
		}
		
		private void clearNode(Node n) { if (n == null) return; try { n.detachAllChildren(); } catch (Exception ignored) {} }

    private static class Wall2D { float x, z, hx, hz; }
    private static class Tank2D {
        String name; Node node; Geometry hpBar, headlightL, headlightR, headlightBeamL, headlightBeamR; SpotLight headSpotL, headSpotR; PointLight headPointL, headPointR; float x, z, heading, aiTargetX, aiTargetZ, aiTimer, aiLastX, aiLastZ, aiStuckTimer, aiAvoidHeading, aiAvoidTimer, aiReverseTimer, fireCooldown, frozenTimer, moveSpeed, turnSpeed, trackTimer; int hp, maxHp, tankModel, ownerIndex; boolean bot, alive = true, dualVisual, headlightsAttached, aiInit; Turret turret; ColorRGBA color;
    }
    private static class Bullet2D { Geometry geo; float x, z, vx, vz, life; int bounces, maxBounces, damage, ownerIndex; Turret turret; Tank2D owner; }
    private static class Worm2D { Node node; final List<WormSeg> segments = new ArrayList<>(); float headX, headZ, heading; int hp; boolean alive = true; }
    private static class WormSeg { Geometry geo; float x, z; }
    private static class Particle2D { Geometry geo; float x, z, vx, vz, life, maxLife; }
    private static class Beam2D { Geometry beam, glow; float life; }
    private static class Pickup2D { Node node; PickupKind kind; float x, z, spin; boolean active = true; }
    private static class Mine2D { Node node; float x, z, armTimer; int ownerIndex; boolean active; }
    private static class AirRoute2D { float startX, startZ, endX, endZ, dropX, dropZ; }
    private static class Airplane2D { Node node; Geometry lightLampL, lightLampR, lightBeamL, lightBeamR; SpotLight headSpotL, headSpotR; PickupKind kind; float x, z, vx, vz, heading, speed, travel, totalDist, dropDistance, dropX, dropZ; boolean dropped, lightsAttached; }
    private static class ContainerDrop2D { Node node; PickupKind kind; float x, y, z, spin; }
    private static class BurningWreck2D { Node hull, turret; float x, z, life, maxLife, baseY, hullAngle, hullSpin, hullVY, turretX, turretY, turretZ, turretVX, turretVY, turretVZ, turretSpin, turretYaw, firePower; boolean turretLanded; }
    private static class WreckCollider2D { float x, z, hx, hz; }
    private static class TrackMark2D { Geometry geo; float life; }
}
