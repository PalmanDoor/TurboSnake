import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.app.Application;
import com.jme3.asset.AssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.audio.AudioNode;
import com.jme3.audio.AudioData.DataType;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.*;
import com.jme3.bullet.control.RigidBodyControl;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.InputManager;
import com.jme3.input.controls.*;
import com.jme3.audio.AudioSource;
import com.jme3.light.AmbientLight;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.*;
import com.jme3.scene.shape.*;
import com.jme3.system.AppSettings;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SnakeApp — 3D Snake (Multiplayer Edition, v4)
 *
 * ╔══════════════════════════════════════════════════════════╗
 * ║  АРХИТЕКТУРА:                                           ║
 * ║  Весь UI (меню, лобби, HUD) живёт в ui/index.html.     ║
 * ║  Этот класс содержит только игровую логику (3D, сеть,   ║
 * ║  физика, змейки, события).                              ║
 * ║                                                          ║
 * ║  Коммуникация с UI:                                      ║
 * ║    Java → JS: bridge.push*(...)                          ║
 * ║    JS → Java: JavaBridge.methodName() (см. JavaBridge)   ║
 * ╚══════════════════════════════════════════════════════════╝
 *
 * Запуск: GameLauncher (JavaFX-приложение).
 * НЕ содержит собственного main() — точка входа в GameLauncher.java.
 */
public class SnakeApp extends SimpleApplication {

    // ── Глобальные настройки (читаются/пишутся через JavaBridge) ──────────
    public static volatile float   effectVolume  = 1.0f;
    public static volatile float   musicVolume   = 0.5f;
    public static volatile int     selectedMap   = 0;
    public static volatile boolean developerMode = false;

    public static final int BROADCAST_PORT = 45678;
    public static final int GAME_PORT      = 45679;

    // ── Параметры игры (передаются из JavaBridge при старте) ──────────────
    public static class GameParams {
        public final String       myNick;
        public final List<String> allPlayers;
        public final boolean      isHost;
        public final boolean      isSolo;
        public final int          mapIndex;

        // Сетевые — заполняются лобби-потоком (null для solo)
        public DatagramSocket         socket;
        public String                 hostAddress;
        public int                    hostPort     = GAME_PORT;
        public List<InetSocketAddress> clients     = new CopyOnWriteArrayList<>();

        public GameParams(String myNick, List<String> allPlayers,
                          boolean isHost, boolean isSolo, int mapIndex) {
            this.myNick     = myNick;
            this.allPlayers = allPlayers;
            this.isHost     = isHost;
            this.isSolo     = isSolo;
            this.mapIndex   = mapIndex;
        }
    }

    // ── Ссылка на мост Java↔HTML ──────────────────────────────────────────
    private final JavaBridge  bridge;
    private final GameParams  params;
    private GameState         gameState;

    public SnakeApp(GameParams params, JavaBridge bridge) {
        this.params = params;
        this.bridge = bridge;
    }

    /** Настройки jME3-окна */
    public void applySettings() {
        AppSettings s = new AppSettings(true);
        
        // Основные параметры окна
        s.setTitle("3D Snake – Multiplayer [3D View]");
        s.setResolution(1280, 720);
        s.setFrameRate(60);      // Ограничение FPS
        s.setSamples(4);         // Сглаживание (MSAA)
        s.setVSync(true);        // Вертикальная синхронизация
        
        // Аудио (подхватываем громкость из настроек, если нужно)
        s.setAudioRenderer(AppSettings.LWJGL_OPENAL);

        // Применяем настройки к приложению
        setSettings(s);
        
        // Скрываем стандартный диалог выбора разрешений при старте
        setShowSettings(false);
        
        /* 
         * ВАЖНО: Отключаем паузу при потере фокуса. 
         * Без этого игра замирает, когда пользователь нажимает на кнопки в WebView.
         */
        setPauseOnLostFocus(false);
    }

    @Override
    public void simpleInitApp() {
        // Убираем ESC по умолчанию
        if (inputManager.hasMapping(SimpleApplication.INPUT_MAPPING_EXIT))
            inputManager.deleteMapping(SimpleApplication.INPUT_MAPPING_EXIT);

        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);
        assetManager.registerLocator(".", FileLocator.class);

        // Запускаем сразу GameState с параметрами из HTML-UI
        int myIdx = params.allPlayers.indexOf(params.myNick);
        gameState = new GameState(
            params.myNick, params.allPlayers,
            Math.max(0, myIdx),
            params.isSolo || params.allPlayers.size() == 1,
            params.isHost,
            params.socket, params.hostAddress, params.hostPort,
            params.clients, params.mapIndex,
            bridge
        );
        stateManager.attach(gameState);

        // Сказать HTML-UI перейти на HUD
        String playersJson = buildPlayersJson(params.allPlayers);
        bridge.pushStartHUD(playersJson);
    }

    private static String buildPlayersJson(List<String> players) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < players.size(); i++) {
            if (i > 0) sb.append(",");
            sb.append("\"").append(escapeJson(players.get(i))).append("\"");
        }
        return sb.append("]").toString();
    }

    // ── Утилиты ───────────────────────────────────────────────────────────
    static Material unshaded(AssetManager am, ColorRGBA c) {
        Material m = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", c);
        return m;
    }

    static BitmapFont loadFont(AssetManager am) {
        try { return am.loadFont("Fonts/main.otf"); }
        catch (Exception e) { return am.loadFont("Interface/Fonts/Default.fnt"); }
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    // ═════════════════════════════════════════════════════════════════════
    // GAME STATE — вся игровая логика
    // (UI-вызовы: вместо BitmapText → bridge.push*(..))
    // ═════════════════════════════════════════════════════════════════════
    static class GameState extends AbstractAppState {

        private SimpleApplication app;
        private Node rootNode, guiNode;
        private AssetManager assetManager;
        private InputManager inputManager;
        private Camera cam;
        private AppStateManager stateManager;
        private BulletAppState bulletAppState;

        // Параметры
        private final String       myNick;
        private final List<String> allPlayers;
        private final int          myIndex;
        private final boolean      solo;
        private final boolean      isHost;
        private final int          mapIndex;

        // Мост к HTML-UI
        private final JavaBridge bridge;

        // Игровая сцена
        private final List<SnakePlayer> snakes    = new ArrayList<>();
        private Node foodNode, wallNode, cloudNode, worldNode, cubeNode;
        private Node pitNode;

        private final List<FoodItem>   foodItems  = new ArrayList<>();
        private final List<BlackCube>  blackCubes = new ArrayList<>();
        private int cubeIdCounter = 0;
        private float cubeNetTimer = 0f;
        private static final float CUBE_NET_INTERVAL = 0.10f;
        private static final int   MAX_BLACK_CUBES   = 5;
        private static final int   CUBE_SHRINK_AMOUNT = 6;

        // Музыка и звуки
        private final AudioNode[] themeMusic  = new AudioNode[4];
        private int   currentTrack  = 0;
        private float trackTimer    = 0f;
        private static final float TRACK_CHECK_INTERVAL = 1f;
        private final AudioNode[] eatSounds = new AudioNode[4];
        private int eatSoundIndex = 0;
        private AudioNode mmmSound, startSound, deathSound, chitSound,
                          cubeRollSound, rainSound;

        // Состояние игры
        private boolean gameOver   = false;
        private float   exitTimer  = 8f;
        private float   gameTime   = 0f;
        private boolean spectating = false;
        private int     spectateTarget = 0;

        // ИСПРАВЛЕНИЕ: защита от повторного вызова backToMenu() до детача стейта
        private boolean isCleaningUp = false;

        // HUD — таймер (обновляем JS через bridge раз в секунду)
        private float hudTimerAccum = 0f;
        // ИСПРАВЛЕНИЕ: таймер обновления очков (заменяет нестабильное условие на gameTime)
        private float scoreUpdateTimer = 0f;

        // Ивент 1: шариковый дождь
        private boolean ballRainActive    = false;
        private float   ballRainTimer     = 0f;
        private static final float BALL_RAIN_DURATION = 10f;
        private float   ballRainSpawnTimer   = 0f;
        private boolean ballRainTriggered    = false;
        private float   ballRainTriggerTime  = 0f;
        private final List<RainBall> rainBalls = new ArrayList<>();
        private Node rainBallNode;

        // Ивент 2: дождь
        private boolean weatherRainActive    = false;
        private float   weatherRainTimer     = 0f;
        private static final float WEATHER_RAIN_DURATION = 30f;
        private boolean weatherRainTriggered = false;
        private float   weatherRainTriggerTime = 0f;
        private Node rainDropNode;
        private final List<RainDrop>    rainDrops   = new ArrayList<>();
        private final List<WaterPuddle> waterPuddles = new ArrayList<>();
        private Node waterNode;
        private float waterSpeedMultiplier = 1f;

        // Внутриигровой чат (теперь полностью в HTML-UI)
        private boolean       chatFocused = false;
        private final StringBuilder chatInput = new StringBuilder();
        private final List<String> chatMessages = new CopyOnWriteArrayList<>();

        // Константы карты
        static final float MAP_HALF    = 40f;
        static final float SEG_SPACING = 0.55f;
        static final float SPEED       = 8f;
        static final float TURN_SPEED  = 2.8f;
        static final int   MAX_FOOD    = 18;
        static final int   BAD_FOOD    = 3;

        // Ямы
        private static final float[][] PIT_POSITIONS = {
            {-15f,-15f},{15f,-15f},{-15f,15f},{15f,15f},
            {0f,-20f},{0f,20f},{-20f,0f},{20f,0f}
        };
        private static final float PIT_RADIUS = 3.5f;
        private static final float PIT_DEPTH  = 8f;

        // Сеть
        private DatagramSocket socket;
        private final String              hostAddress;
        private final int                 hostPort;
        private final List<InetSocketAddress> clients;
        private Thread netRecvThread;
        private final AtomicBoolean netRunning = new AtomicBoolean(true);
        private float  netSendTimer  = 0f;
        private int    foodIdCounter = 0;
        private static final float NET_SEND_INTERVAL = 0.033f;

        private static final ColorRGBA[] SNAKE_COLORS = {
            new ColorRGBA(0.15f,0.9f,0.3f,1f), new ColorRGBA(0.9f,0.3f,0.1f,1f),
            new ColorRGBA(0.2f,0.5f,1.0f,1f),  new ColorRGBA(0.9f,0.8f,0.1f,1f)
        };
        private static final Vector3f[] START_POS = {
            new Vector3f(-8,0.3f,0), new Vector3f(8,0.3f,0),
            new Vector3f(0,0.3f,-8), new Vector3f(0,0.3f,8)
        };
        private static final float[] START_ANGLES = {
            FastMath.PI, 0f, FastMath.HALF_PI, -FastMath.HALF_PI
        };

        public GameState(String myNick, List<String> allPlayers, int myIndex,
                         boolean solo, boolean isHost,
                         DatagramSocket socket, String hostAddress, int hostPort,
                         List<InetSocketAddress> clients, int mapIndex,
                         JavaBridge bridge) {
            this.myNick      = myNick;
            this.allPlayers  = allPlayers != null ? allPlayers : Collections.singletonList(myNick);
            this.myIndex     = myIndex < 0 ? 0 : myIndex;
            this.solo        = solo;
            this.isHost      = isHost;
            this.socket      = socket;
            this.hostAddress = hostAddress;
            this.hostPort    = hostPort;
            this.clients     = clients != null ? clients : new CopyOnWriteArrayList<>();
            this.mapIndex    = mapIndex;
            this.bridge      = bridge;
        }

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            app          = (SimpleApplication) application;
            rootNode     = app.getRootNode();
            guiNode      = app.getGuiNode();
            assetManager = app.getAssetManager();
            inputManager = app.getInputManager();
            cam          = app.getCamera();
            stateManager = sm;

            // Фон по карте
            if (mapIndex == 1) app.getViewPort().setBackgroundColor(new ColorRGBA(0.75f,0.65f,0.45f,1f));
            else if (mapIndex == 2) app.getViewPort().setBackgroundColor(new ColorRGBA(0.15f,0.15f,0.25f,1f));
            else app.getViewPort().setBackgroundColor(new ColorRGBA(0.40f,0.65f,0.95f,1f));

            assetManager.registerLocator(".", FileLocator.class);

            Random rng = new Random();
            ballRainTriggerTime    = 180f + rng.nextFloat() * 180f;
            weatherRainTriggerTime = 180f + rng.nextFloat() * 180f;
            if (Math.abs(ballRainTriggerTime - weatherRainTriggerTime) < 30f)
                weatherRainTriggerTime += 60f;

            setupLights();
            setupPhysics();
            buildOuterWorld();
            buildArena();
            buildClouds();
            createSnakes();
            spawnFood(MAX_FOOD);
            spawnInitialCubes();
            setupControls();
            loadSounds();
            if (!solo) initNetwork();

            rainBallNode = new Node("RainBalls"); rootNode.attachChild(rainBallNode);
            rainDropNode = new Node("RainDrops"); rootNode.attachChild(rainDropNode);
            waterNode    = new Node("Water");     rootNode.attachChild(waterNode);

            // Нет больше buildHUD() — HUD живёт в HTML!
            // bridge.pushStartHUD() уже вызван в SnakeApp.simpleInitApp()
        }

        // ══ Свет ════════════════════════════════════════════════════════════
        private void setupLights() {
            DirectionalLight sun = new DirectionalLight();
            sun.setDirection(new Vector3f(-0.5f,-1f,-0.3f).normalizeLocal());
            sun.setColor(mapIndex==2
                ? new ColorRGBA(0.6f,0.5f,0.8f,1f)
                : new ColorRGBA(1f,0.98f,0.9f,1f));
            rootNode.addLight(sun);
            AmbientLight amb = new AmbientLight();
            amb.setColor(new ColorRGBA(0.4f,0.42f,0.5f,1f));
            rootNode.addLight(amb);
        }

        // ══ Физика ══════════════════════════════════════════════════════════
        private void setupPhysics() {
            bulletAppState = new BulletAppState();
            stateManager.attach(bulletAppState);
            bulletAppState.getPhysicsSpace().setGravity(new Vector3f(0,-9.81f,0));
        }

        // ══ Сцена ═══════════════════════════════════════════════════════════
        // (buildOuterWorld, buildArena, buildClouds, buildPits, buildMetalFence —
        //  без изменений по сравнению с оригиналом; код идентичен v3)

        private void buildOuterWorld() {
            worldNode = new Node("World"); rootNode.attachChild(worldNode);
            float ext = MAP_HALF * 3.5f;
            // Y = -0.8 → верх = -0.5, что НИЖЕ пола арены (-0.2) → нет z-fighting
            ColorRGBA ground = mapIndex==1
                ? new ColorRGBA(0.72f,0.58f,0.32f,1f)
                : (mapIndex==2 ? new ColorRGBA(0.18f,0.16f,0.22f,1f)
                               : new ColorRGBA(0.25f,0.48f,0.18f,1f));
            addBox(new Vector3f(0,-0.8f,0), new Vector3f(ext,0.3f,ext),
                   unshaded(assetManager,ground), null);

            // Горы по периметру (только для карт 0 и 1)
            if (mapIndex != 2) {
                Material rockMat = unshaded(assetManager, new ColorRGBA(0.5f,0.45f,0.4f,1f));
                Material snowMat = unshaded(assetManager, new ColorRGBA(0.9f,0.9f,0.95f,1f));
                Random rng = new Random(42);
                int mountainCount = 32;
                for (int i=0; i<mountainCount; i++) {
                    float angle = (float)i/mountainCount * FastMath.TWO_PI + (rng.nextFloat()-0.5f)*0.15f;
                    float dist = MAP_HALF * 1.6f + rng.nextFloat() * MAP_HALF;
                    float h = 8f + rng.nextFloat() * 22f;
                    float r = 5f + rng.nextFloat() * 12f;
                    Geometry mtn = new Geometry("Mtn"+i, new Sphere(6,8,1f));
                    mtn.setMaterial(rockMat);
                    mtn.setLocalScale(r, h, r);
                    mtn.setLocalTranslation(FastMath.cos(angle)*dist, -0.5f, FastMath.sin(angle)*dist);
                    worldNode.attachChild(mtn);
                    if (h > 14f) {
                        Geometry snow = new Geometry("Snow"+i, new Sphere(5,7,0.38f));
                        snow.setMaterial(snowMat);
                        snow.setLocalScale(r*0.4f, h*0.25f, r*0.4f);
                        snow.setLocalTranslation(FastMath.cos(angle)*dist, h*0.65f, FastMath.sin(angle)*dist);
                        worldNode.attachChild(snow);
                    }
                }
            }

            // Деревья (карта 0) / Кактусы (карта 1) СНАРУЖИ арены
            Material trunkMat = unshaded(assetManager, new ColorRGBA(0.42f,0.26f,0.12f,1f));
            Material leafMat  = unshaded(assetManager, new ColorRGBA(0.15f,0.6f,0.18f,1f));
            Material leafMat2 = unshaded(assetManager, new ColorRGBA(0.1f,0.5f,0.12f,1f));
            Material cactMat  = unshaded(assetManager, new ColorRGBA(0.2f,0.55f,0.18f,1f));
            Random rng2 = new Random(99);
            int treeCount = 80;
            for (int i=0; i<treeCount; i++) {
                float angle = rng2.nextFloat() * FastMath.TWO_PI;
                float dist  = MAP_HALF * 1.45f + rng2.nextFloat() * MAP_HALF * 0.9f;
                float tx = FastMath.cos(angle)*dist, tz = FastMath.sin(angle)*dist;
                float treeH = 2.5f + rng2.nextFloat()*3f;
                if (mapIndex == 1) {
                    Geometry trunk = new Geometry("Cactus"+i, new Box(0.25f, treeH/2f, 0.25f));
                    trunk.setMaterial(cactMat);
                    trunk.setLocalTranslation(tx, treeH/2f, tz);
                    worldNode.attachChild(trunk);
                    Geometry arm = new Geometry("CactusArm"+i, new Box(0.6f, 0.2f, 0.2f));
                    arm.setMaterial(cactMat);
                    arm.setLocalTranslation(tx + 0.5f, treeH*0.6f, tz);
                    worldNode.attachChild(arm);
                } else if (mapIndex == 0) {
                    Geometry trunk = new Geometry("Trunk"+i, new Box(0.22f, treeH/2f, 0.22f));
                    trunk.setMaterial(trunkMat);
                    trunk.setLocalTranslation(tx, treeH/2f - 0.5f, tz);
                    worldNode.attachChild(trunk);
                    Material lm = rng2.nextBoolean() ? leafMat : leafMat2;
                    Geometry leaves = new Geometry("Leaf"+i, new Sphere(5,6,0.9f+rng2.nextFloat()*0.6f));
                    leaves.setMaterial(lm);
                    leaves.setLocalScale(1.4f+rng2.nextFloat()*0.5f, 1.2f+rng2.nextFloat()*0.5f, 1.4f+rng2.nextFloat()*0.5f);
                    leaves.setLocalTranslation(tx, treeH+0.4f, tz);
                    worldNode.attachChild(leaves);
                }
            }
        }

        private void buildArena() {
            wallNode = new Node("Walls"); rootNode.attachChild(wallNode);
            PhysicsSpace space = bulletAppState.getPhysicsSpace();
            ColorRGBA floorColor = mapIndex==1
                ? new ColorRGBA(0.70f,0.58f,0.32f,1f)
                : (mapIndex==2 ? new ColorRGBA(0.20f,0.18f,0.26f,1f)
                               : new ColorRGBA(0.25f,0.58f,0.25f,1f));
            addBox(new Vector3f(0,-0.4f,0), new Vector3f(MAP_HALF,0.2f,MAP_HALF),
                   unshaded(assetManager,floorColor), space);
            buildGridLines();
            buildMetalFence(space);
            buildCornerTowers();
            if (mapIndex == 2) buildPits(space);
        }

        private void buildGridLines() {
            ColorRGBA gridColor = mapIndex==1
                ? new ColorRGBA(0.65f,0.52f,0.28f,1f)
                : (mapIndex==2 ? new ColorRGBA(0.22f,0.20f,0.30f,1f)
                               : new ColorRGBA(0.20f,0.50f,0.20f,1f));
            Material gridMat = unshaded(assetManager, gridColor);
            for (float x=-MAP_HALF; x<=MAP_HALF; x+=5f)
                addBox(new Vector3f(x,-0.19f,0), new Vector3f(0.04f,0.01f,MAP_HALF), gridMat, null);
            for (float z=-MAP_HALF; z<=MAP_HALF; z+=5f)
                addBox(new Vector3f(0,-0.19f,z), new Vector3f(MAP_HALF,0.01f,0.04f), gridMat, null);
        }

        private void buildCornerTowers() {
            Material towerMat = unshaded(assetManager, new ColorRGBA(0.35f,0.40f,0.45f,1f));
            Material towerTop  = unshaded(assetManager, new ColorRGBA(0.25f,0.28f,0.35f,1f));
            float[] tcx = {-MAP_HALF, MAP_HALF,-MAP_HALF, MAP_HALF};
            float[] tcz = {-MAP_HALF,-MAP_HALF, MAP_HALF, MAP_HALF};
            for (int i=0;i<4;i++) {
                addBox(new Vector3f(tcx[i],2f,tcz[i]),  new Vector3f(2f,2.5f,2f),   towerMat, null);
                addBox(new Vector3f(tcx[i],5f,tcz[i]),  new Vector3f(2.2f,0.5f,2.2f),towerTop, null);
                Geometry light = new Geometry("TowerLight"+i, new Sphere(6,8,0.4f));
                light.setMaterial(unshaded(assetManager, new ColorRGBA(1f,0.95f,0.7f,1f)));
                light.setLocalTranslation(tcx[i], 6.2f, tcz[i]);
                wallNode.attachChild(light);
            }
        }

        private void buildMetalFence(PhysicsSpace space) {
            float wH = 3.5f;
            Material colMat = unshaded(assetManager, new ColorRGBA(0.45f,0.48f,0.52f,1f));
            addBox(new Vector3f(0,wH/2, MAP_HALF-0.5f), new Vector3f(MAP_HALF,wH/2,0.5f), colMat, space);
            addBox(new Vector3f(0,wH/2,-MAP_HALF+0.5f), new Vector3f(MAP_HALF,wH/2,0.5f), colMat, space);
            addBox(new Vector3f(-MAP_HALF+0.5f,wH/2,0), new Vector3f(0.5f,wH/2,MAP_HALF), colMat, space);
            addBox(new Vector3f( MAP_HALF-0.5f,wH/2,0), new Vector3f(0.5f,wH/2,MAP_HALF), colMat, space);

            Material postMat = unshaded(assetManager, new ColorRGBA(0.55f,0.58f,0.62f,1f));
            Material railMat = unshaded(assetManager, new ColorRGBA(0.40f,0.43f,0.48f,1f));
            Material railHi  = unshaded(assetManager, new ColorRGBA(0.70f,0.72f,0.78f,1f));
            float postSpacing = 4f;
            int postCount = (int)(MAP_HALF*2 / postSpacing) + 1;
            for (int i=0; i<postCount; i++) {
                float px = Math.min(-MAP_HALF + i*postSpacing, MAP_HALF);
                spawnPost(px, MAP_HALF-0.5f, postMat);
                spawnPost(px,-MAP_HALF+0.5f, postMat);
                spawnPost( MAP_HALF-0.5f, px, postMat);
                spawnPost(-MAP_HALF+0.5f, px, postMat);
            }
            float[] railY = {0.7f, 1.6f, 2.8f};
            for (float ry : railY) {
                addBox(new Vector3f(0,ry, MAP_HALF-0.5f), new Vector3f(MAP_HALF,0.06f,0.06f), railMat, null);
                addBox(new Vector3f(0,ry,-MAP_HALF+0.5f), new Vector3f(MAP_HALF,0.06f,0.06f), railMat, null);
                addBox(new Vector3f(-MAP_HALF+0.5f,ry,0), new Vector3f(0.06f,0.06f,MAP_HALF), railMat, null);
                addBox(new Vector3f( MAP_HALF-0.5f,ry,0), new Vector3f(0.06f,0.06f,MAP_HALF), railMat, null);
            }
            addBox(new Vector3f(0,wH, MAP_HALF-0.5f), new Vector3f(MAP_HALF,0.08f,0.08f), railHi, null);
            addBox(new Vector3f(0,wH,-MAP_HALF+0.5f), new Vector3f(MAP_HALF,0.08f,0.08f), railHi, null);
            addBox(new Vector3f(-MAP_HALF+0.5f,wH,0), new Vector3f(0.08f,0.08f,MAP_HALF), railHi, null);
            addBox(new Vector3f( MAP_HALF-0.5f,wH,0), new Vector3f(0.08f,0.08f,MAP_HALF), railHi, null);
        }

        private void spawnPost(float x, float z, Material mat) {
            addBox(new Vector3f(x,1.75f,z), new Vector3f(0.12f,1.75f,0.12f), mat, null);
        }

        private void buildPits(PhysicsSpace space) {
            pitNode = new Node("Pits"); rootNode.attachChild(pitNode);
            Material pitFloor = unshaded(assetManager, new ColorRGBA(0.10f,0.08f,0.12f,1f));
            Material spikeMat = unshaded(assetManager, new ColorRGBA(0.75f,0.75f,0.82f,1f));
            for (float[] pp : PIT_POSITIONS) {
                float px=pp[0], pz=pp[1];
                Box floor = new Box(PIT_RADIUS,0.2f,PIT_RADIUS);
                Geometry fg = new Geometry("PitFloor", floor);
                fg.setMaterial(pitFloor); fg.setLocalTranslation(px,-PIT_DEPTH,pz);
                pitNode.attachChild(fg);
                Material wallMat = unshaded(assetManager, new ColorRGBA(0.12f,0.10f,0.16f,1f));
                addBox(new Vector3f(px,-PIT_DEPTH/2f, pz+PIT_RADIUS), new Vector3f(PIT_RADIUS,PIT_DEPTH/2f,0.3f), wallMat, null);
                addBox(new Vector3f(px,-PIT_DEPTH/2f, pz-PIT_RADIUS), new Vector3f(PIT_RADIUS,PIT_DEPTH/2f,0.3f), wallMat, null);
                addBox(new Vector3f(px+PIT_RADIUS,-PIT_DEPTH/2f,pz), new Vector3f(0.3f,PIT_DEPTH/2f,PIT_RADIUS), wallMat, null);
                addBox(new Vector3f(px-PIT_RADIUS,-PIT_DEPTH/2f,pz), new Vector3f(0.3f,PIT_DEPTH/2f,PIT_RADIUS), wallMat, null);
                int sr=4;
                for (int si=0;si<sr;si++) for (int sj=0;sj<sr;sj++) {
                    float sx=px-PIT_RADIUS*0.7f+si*(PIT_RADIUS*1.4f/(sr-1));
                    float sz=pz-PIT_RADIUS*0.7f+sj*(PIT_RADIUS*1.4f/(sr-1));
                    Geometry spike = new Geometry("Spike", new Box(0.08f,0.5f,0.08f));
                    spike.setMaterial(spikeMat); spike.setLocalTranslation(sx,-PIT_DEPTH+0.5f,sz);
                    pitNode.attachChild(spike);
                    Geometry tip = new Geometry("SpikeTip", new Sphere(4,4,0.15f));
                    tip.setMaterial(spikeMat); tip.setLocalTranslation(sx,-PIT_DEPTH+1.1f,sz);
                    pitNode.attachChild(tip);
                }
                Geometry decal = new Geometry("Hole", new Box(PIT_RADIUS-0.3f,0.01f,PIT_RADIUS-0.3f));
                decal.setMaterial(unshaded(assetManager, new ColorRGBA(0.05f,0.04f,0.08f,1f)));
                decal.setLocalTranslation(px,-0.18f,pz); pitNode.attachChild(decal);
            }
        }

        private void buildClouds() {
            cloudNode = new Node("Clouds"); rootNode.attachChild(cloudNode);
            if (mapIndex == 2) return;
            Material cm = mapIndex==1
                ? unshaded(assetManager, new ColorRGBA(0.95f,0.90f,0.75f,0.7f))
                : unshaded(assetManager, new ColorRGBA(1f,1f,1f,0.88f));
            for (int i=0;i<20;i++) {
                float r = 1.2f + FastMath.nextRandomFloat()*3f;
                Geometry g = new Geometry("Cloud", new Sphere(8,10,r));
                g.setMaterial(cm);
                g.setLocalTranslation(
                    (FastMath.nextRandomFloat()-0.5f)*MAP_HALF*3f,
                    14f+FastMath.nextRandomFloat()*10f,
                    (FastMath.nextRandomFloat()-0.5f)*MAP_HALF*3f);
                cloudNode.attachChild(g);
            }
        }

        // ══ Змейки ══════════════════════════════════════════════════════════
        private void createSnakes() {
            for (int i=0;i<allPlayers.size();i++) {
                Node sn = new Node("Snake"+i); rootNode.attachChild(sn);
                Material mat = unshaded(assetManager, SNAKE_COLORS[i % SNAKE_COLORS.length]);
                SnakePlayer sp = new SnakePlayer(
                    allPlayers.get(i),
                    START_POS[i % START_POS.length].clone(),
                    START_ANGLES[i % START_ANGLES.length],
                    mat, sn, assetManager, cam,
                    bulletAppState.getPhysicsSpace());
                snakes.add(sp);
            }
        }

        // ══ Еда ═════════════════════════════════════════════════════════════
        private void spawnFood(int count) {
            foodNode = new Node("Food"); rootNode.attachChild(foodNode);
            if (solo) for (int i=0;i<count;i++) addOneFood();
        }

        private void addOneFood() {
            if (foodItems.size() >= MAX_FOOD) return;
            boolean bad = foodItems.stream().filter(f->f.bad&&!f.isDebris).count()<BAD_FOOD
                          && FastMath.nextRandomFloat()<0.25f;
            float x = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(MAP_HALF*2-6),-MAP_HALF+2f,MAP_HALF-2f);
            float z = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(MAP_HALF*2-6),-MAP_HALF+2f,MAP_HALF-2f);
            addFoodWithData(foodIdCounter++, x, z, bad, false);
        }

        private void addFoodWithData(int id, float x, float z, boolean bad, boolean isDebris) {
            x = FastMath.clamp(x, -MAP_HALF+1.5f, MAP_HALF-1.5f);
            z = FastMath.clamp(z, -MAP_HALF+1.5f, MAP_HALF-1.5f);
            Material mat; float radius;
            if (isDebris) { mat=unshaded(assetManager,new ColorRGBA(0.85f,0.85f,0.85f,1f)); radius=0.32f; }
            else if (bad) { mat=unshaded(assetManager,new ColorRGBA(0.42f,0.26f,0.12f,1f)); radius=0.50f; }
            else {
                Material[] gm = {
                    unshaded(assetManager,new ColorRGBA(0.95f,0.2f,0.2f,1f)),
                    unshaded(assetManager,new ColorRGBA(0.2f,0.4f,1f,1f)),
                    unshaded(assetManager,new ColorRGBA(1f,0.85f,0.1f,1f)),
                    unshaded(assetManager,new ColorRGBA(0.8f,0.2f,0.9f,1f)),
                    unshaded(assetManager,new ColorRGBA(0.1f,0.9f,0.7f,1f))
                };
                mat=gm[FastMath.nextRandomInt(0,gm.length-1)]; radius=0.38f;
            }
            Geometry geo = new Geometry("Food"+(isDebris?"D":""), new Sphere(12,12,radius));
            geo.setMaterial(mat);
            geo.setLocalTranslation(x, isDebris?0.4f:1.8f, z);
            RigidBodyControl phy = new RigidBodyControl(new SphereCollisionShape(radius), 1f);
            geo.addControl(phy); bulletAppState.getPhysicsSpace().add(phy);
            if (isDebris) {
                phy.setLinearVelocity(new Vector3f(
                    (FastMath.nextRandomFloat()-0.5f)*4f, 1.5f,
                    (FastMath.nextRandomFloat()-0.5f)*4f));
            }
            foodNode.attachChild(geo);
            foodItems.add(new FoodItem(geo, bad, id, isDebris));
        }

        private void removeFood(FoodItem fi) {
            RigidBodyControl phy = fi.geo.getControl(RigidBodyControl.class);
            if (phy!=null) { phy.setEnabled(false); bulletAppState.getPhysicsSpace().remove(phy); }
            foodNode.detachChild(fi.geo); foodItems.remove(fi);
        }
        private void removeFoodById(int id) {
            foodItems.stream().filter(f->f.id==id).findFirst().ifPresent(this::removeFood);
        }

        // ══ HUD → HTML-UI ═══════════════════════════════════════════════════
        /**
         * Все методы buildHUD() / buildInGameChat() / updateChatDisplay()
         * удалены. Вместо BitmapText вызываем bridge.push*().
         */

        /** Отправить все очки в HTML-HUD */
        private void pushScoresToUI() {
            StringBuilder sb = new StringBuilder("[");
            for (int i=0; i<snakes.size(); i++) {
                SnakePlayer sp = snakes.get(i);
                if (i>0) sb.append(",");
                sb.append("{\"name\":\"").append(escapeJson(sp.getName())).append("\"")
                  .append(",\"score\":").append(sp.getScore())
                  .append(",\"dead\":").append(sp.isDead())
                  .append("}");
            }
            sb.append("]");
            bridge.pushScoreUpdate(sb.toString());
        }

        /** Центральное сообщение (смерть, победа, событие) */
        private void showCenter(String msg, String hexColor) {
            bridge.pushCenterMessage(msg, hexColor, 4000);
        }

        // ══ Управление ══════════════════════════════════════════════════════
        private void setupControls() {
            inputManager.addMapping("Left",    new KeyTrigger(KeyInput.KEY_A), new KeyTrigger(KeyInput.KEY_LEFT));
            inputManager.addMapping("Right",   new KeyTrigger(KeyInput.KEY_D), new KeyTrigger(KeyInput.KEY_RIGHT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (gameOver || chatFocused || myIndex>=snakes.size()) return;
                SnakePlayer me = snakes.get(myIndex);
                if ("Left".equals(n))  me.setTurnLeft(p);
                if ("Right".equals(n)) me.setTurnRight(p);
            }, "Left","Right");

            inputManager.addMapping("Forward", new KeyTrigger(KeyInput.KEY_W));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (gameOver || chatFocused || myIndex>=snakes.size()) return;
                snakes.get(myIndex).setMoving(p);
            },"Forward");

            // Спектатор
            inputManager.addMapping("SpecPrev", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
            inputManager.addMapping("SpecNext", new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p || !spectating) return;
                if ("SpecPrev".equals(n)) spectateTarget=(spectateTarget-1+snakes.size())%snakes.size();
                if ("SpecNext".equals(n)) spectateTarget=(spectateTarget+1)%snakes.size();
                for (int i=0;i<snakes.size();i++) {
                    if (!snakes.get(spectateTarget).isDead()) break;
                    spectateTarget=(spectateTarget+1)%snakes.size();
                }
                bridge.pushSpectator(true, snakes.get(spectateTarget).getName());
            },"SpecPrev","SpecNext");

            // ESC
            inputManager.addMapping("Escape", new KeyTrigger(KeyInput.KEY_ESCAPE));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p) return;
                if (chatFocused) {
                    chatFocused=false;
                    chatInput.setLength(0);
                    bridge.pushCloseChat();
                } else backToMenu();
            },"Escape");

            // T — открыть чат
            inputManager.addMapping("ChatOpen", new KeyTrigger(KeyInput.KEY_T));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p) return;
                if (!chatFocused) {
                    chatFocused=true;
                    bridge.pushOpenChat();
                    if (myIndex<snakes.size()) snakes.get(myIndex).setMoving(false);
                } else {
                    chatInput.append('T');
                    bridge.pushChatChar('T');
                }
            },"ChatOpen");

            // Символы чата
            int[] chatKeys = {
                KeyInput.KEY_A,KeyInput.KEY_B,KeyInput.KEY_C,KeyInput.KEY_D,KeyInput.KEY_E,
                KeyInput.KEY_F,KeyInput.KEY_G,KeyInput.KEY_H,KeyInput.KEY_I,KeyInput.KEY_J,
                KeyInput.KEY_K,KeyInput.KEY_L,KeyInput.KEY_M,KeyInput.KEY_N,KeyInput.KEY_O,
                KeyInput.KEY_P,KeyInput.KEY_Q,KeyInput.KEY_R,KeyInput.KEY_S,
                KeyInput.KEY_U,KeyInput.KEY_V,KeyInput.KEY_X,KeyInput.KEY_Y,KeyInput.KEY_Z,
                KeyInput.KEY_SPACE,KeyInput.KEY_0,KeyInput.KEY_1,KeyInput.KEY_2,KeyInput.KEY_3,
                KeyInput.KEY_4,KeyInput.KEY_5,KeyInput.KEY_6,KeyInput.KEY_7,KeyInput.KEY_8,
                KeyInput.KEY_9
            };
            char[] chatChars = {
                'A','B','C','D','E','F','G','H','I','J','K','L','M','N','O','P','Q','R','S',
                'U','V','X','Y','Z',' ','0','1','2','3','4','5','6','7','8','9'
            };
            for (int i=0; i<chatKeys.length; i++) {
                final char ch = chatChars[i];
                String mn = "Chat_"+ch;
                inputManager.addMapping(mn, new KeyTrigger(chatKeys[i]));
                inputManager.addListener((ActionListener)(nm,p2,t2) -> {
                    if (!p2 || !chatFocused) return;
                    if (chatInput.length()<80) {
                        chatInput.append(ch);
                        bridge.pushChatChar(ch);
                    }
                }, mn);
            }

            inputManager.addMapping("ChatBack", new KeyTrigger(KeyInput.KEY_BACK));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p || !chatFocused || chatInput.length()==0) return;
                chatInput.deleteCharAt(chatInput.length()-1);
                bridge.pushChatBackspace();
            },"ChatBack");

            inputManager.addMapping("ChatSend", new KeyTrigger(KeyInput.KEY_RETURN));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p || !chatFocused) return;
                if (chatInput.length()>0) {
                    String msg = myNick+": "+chatInput;
                    chatMessages.add(msg);
                    sendNet("GCHAT|"+myNick+"|"+chatInput);
                    bridge.pushHudChat(myNick, chatInput.toString());
                    chatInput.setLength(0);
                }
                chatFocused=false;
                bridge.pushCloseChat();
            },"ChatSend");
        }

        // ══ Главный игровой цикл ════════════════════════════════════════════
        @Override
        public void update(float tpf) {
            if (!isInitialized() || isCleaningUp) return;
            gameTime += tpf;

            // ИСПРАВЛЕНИЕ: пока идёт завершение — только обратный отсчёт,
            // никакой игровой логики (иначе backToMenu вызывался каждый кадр
            // пока stateManager.detach не сработал)
            if (gameOver) {
                exitTimer -= tpf;
                if (exitTimer <= 0) backToMenu();
                return;
            }

            // Таймер в UI — раз в секунду
            hudTimerAccum += tpf;
            if (hudTimerAccum >= 1.0f) {
                hudTimerAccum -= 1.0f;
                bridge.pushTimerUpdate((int) gameTime);
            }

            // Очки — раз в 250 мс (стабильнее чем условие на gameTime)
            scoreUpdateTimer += tpf;
            if (scoreUpdateTimer >= 0.25f) {
                scoreUpdateTimer = 0f;
                pushScoresToUI();
            }

            updateSnakes(tpf);
            updateCamera();
            if (solo || isHost) checkFoodCollisions();
            updateCubes(tpf);
            updateEvents(tpf);
            updateMusic(tpf);

            if (!solo) {
                netSendTimer += tpf;
                if (netSendTimer >= NET_SEND_INTERVAL) {
                    netSendTimer = 0f;
                    sendMyState();
                    sendCubeStates();
                }
            }

            // Ивенты
            if (!ballRainTriggered && gameTime >= ballRainTriggerTime)    startBallRainEvent();
            if (!weatherRainTriggered && gameTime >= weatherRainTriggerTime) startWeatherRainEvent();
        }

        private void updateSnakes(float tpf) {
            float effectiveSpeed = SPEED * waterSpeedMultiplier;
            for (SnakePlayer sp : snakes) {
                sp.update(tpf, effectiveSpeed, TURN_SPEED, SEG_SPACING);
            }
            // Очки пушатся через scoreUpdateTimer в update() — убрано отсюда
        }

        private void updateCamera() {
            if (myIndex >= snakes.size()) return;
            SnakePlayer target = spectating
                ? (spectateTarget < snakes.size() ? snakes.get(spectateTarget) : snakes.get(0))
                : snakes.get(myIndex);
            if (!target.isDead() || spectating) {
                Vector3f hp = target.getHeadPos();
                Vector3f dir = target.getDirection();
                Vector3f camPos = hp.add(dir.negate().mult(14f)).add(0,9f,0);
                cam.setLocation(camPos);
                cam.lookAt(hp.add(dir.mult(3f)), Vector3f.UNIT_Y);
            }
        }

        private void checkFoodCollisions() {
            if (myIndex >= snakes.size()) return;
            SnakePlayer me = snakes.get(myIndex);
            if (me.isDead()) return;
            Vector3f head = me.getHeadPos();

            // Стены
            if (Math.abs(head.x)>MAP_HALF||Math.abs(head.z)>MAP_HALF||
                (mapIndex==2 && head.y < -3f)) {
                triggerPlayerDeath(myIndex, true);
                return;
            }
            // Самостолкновение
            if (me.selfCollides(SnakePlayer.SEG_R * 1.8f)) {
                triggerPlayerDeath(myIndex, true);
                return;
            }
            // Кубы
            for (BlackCube bc : blackCubes) {
                if (!bc.active) continue;
                if (head.distance(bc.geo.getLocalTranslation()) < 1.0f) {
                    applyCubeHitLocal(bc.id, myIndex); return;
                }
            }
            // Еда
            for (int i=foodItems.size()-1;i>=0;i--) {
                FoodItem fi = foodItems.get(i);
                if (head.distance(fi.geo.getLocalTranslation()) < 1.2f) {
                    if (fi.bad) {
                        // Коричневый шарик: отнимает 1 сегмент, играет mmm.ogg
                        me.shrink();
                        playSound(mmmSound);
                        showCenter("☠ Ядовитая еда!", "#ff4444");
                    } else {
                        me.grow(assetManager); me.addScore(fi.isDebris?3:1);
                        playEatSound(); // только eat1-eat3 / burp, без mmm
                    }
                    sendNet("EAT|"+fi.id);
                    removeFood(fi);
                    if (solo) addOneFood();
                    else if (isHost) hostAddAndBroadcastFood();
                }
            }
        }

        private void hostAddAndBroadcastFood() {
            if (foodItems.stream().filter(f->!f.isDebris).count()>=MAX_FOOD) return;
            boolean bad = foodItems.stream().filter(f->f.bad&&!f.isDebris).count()<BAD_FOOD
                          && FastMath.nextRandomFloat()<0.25f;
            float x = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(MAP_HALF*2-6),-MAP_HALF+2f,MAP_HALF-2f);
            float z = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(MAP_HALF*2-6),-MAP_HALF+2f,MAP_HALF-2f);
            int id = foodIdCounter++;
            addFoodWithData(id, x, z, bad, false);
            sendNet("FOOD|"+id+"|"+x+"|"+z+"|"+(bad?1:0)+"|0");
        }

        // ══ Победа / Смерть ═════════════════════════════════════════════════
        private void triggerPlayerDeath(int idx, boolean sendNet) {
            SnakePlayer sp = snakes.get(idx);
            if (sp.isDead()) return;
            List<Vector3f> segs = sp.getSegmentPositions();
            sp.triggerDeath(rootNode);
            playSound(deathSound);
            showCenter("☠ " + sp.getName() + " погиб!", "#ff4444");
            bridge.pushHudChat(null, "☠ " + sp.getName() + " погиб!");
            spawnDebrisFromDeath(segs, idx);
            if (sendNet) sendNet("DEAD|"+idx);
            if (idx == myIndex) {
                spectating = true;
                bridge.pushSpectator(true, findLiveTarget());
            }
            checkWinCondition();
        }

        private String findLiveTarget() {
            for (SnakePlayer sp : snakes) if (!sp.isDead()) return sp.getName();
            return "—";
        }

        private void checkWinCondition() {
            long alive = snakes.stream().filter(s->!s.isDead()).count();
            if (alive <= 1) {
                SnakePlayer winner = snakes.stream().filter(s->!s.isDead()).findFirst().orElse(null);
                String wName = winner != null ? winner.getName() : "Ничья";
                if (!solo && (isHost)) sendNet("WIN|"+wName);
                showCenter("🏆 " + wName + " ПОБЕДИЛ!", "#ffc84a");
                // Пушим финальный экран
                pushScoresToUI();
                bridge.pushGameOver(wName, buildScoresJson());
                gameOver=true; exitTimer=10f;
            }
        }

        private String buildScoresJson() {
            StringBuilder sb = new StringBuilder("[");
            for (int i=0;i<snakes.size();i++) {
                SnakePlayer sp = snakes.get(i);
                if (i>0) sb.append(",");
                sb.append("{\"name\":\"").append(escapeJson(sp.getName())).append("\"")
                  .append(",\"score\":").append(sp.getScore()).append("}");
            }
            return sb.append("]").toString();
        }

        private void spawnDebrisFromDeath(List<Vector3f> positions, int playerIndex) {
            for (Vector3f pos : positions) {
                int id = foodIdCounter++;
                float dx = FastMath.clamp(pos.x, -MAP_HALF+1.5f, MAP_HALF-1.5f);
                float dz = FastMath.clamp(pos.z, -MAP_HALF+1.5f, MAP_HALF-1.5f);
                addFoodWithData(id, dx, dz, false, true);
                if (!solo) sendNet("DEBRIS|"+id+"|"+dx+"|"+dz);
            }
        }

        private void backToMenu() {
            // ИСПРАВЛЕНИЕ: защита от повторного входа (stateManager.detach не мгновенный)
            if (isCleaningUp) return;
            isCleaningUp = true;
            gameOver = true;

            netRunning.set(false);
            if (socket != null && !socket.isClosed()) socket.close();

            // ИСПРАВЛЕНИЕ: сначала убираем физику — иначе BulletAppState продолжает
            // симулировать пустой мир и грузить CPU даже после детача GameState.
            // При следующем старте без этого накапливались дубликаты BulletAppState.
            if (bulletAppState != null) {
                stateManager.detach(bulletAppState);
                bulletAppState = null;
            }

            rootNode.detachAllChildren();
            guiNode.detachAllChildren();
            inputManager.clearMappings();
            stateManager.detach(this);

            bridge.pushScoreUpdate("[]");
            // ИСПРАВЛЕНИЕ: явно говорим JS перейти в меню (было закомментировано)
            bridge.pushShowMenu();
        }

        // ══ Кубы-враги ══════════════════════════════════════════════════════
        private void spawnInitialCubes() {
            cubeNode = new Node("Cubes"); rootNode.attachChild(cubeNode);
            if (solo || isHost) {
                for (int i=0;i<3;i++) {
                    float x=(FastMath.nextRandomFloat()-0.5f)*MAP_HALF*1.5f;
                    float z=(FastMath.nextRandomFloat()-0.5f)*MAP_HALF*1.5f;
                    spawnBlackCube(cubeIdCounter++, x, z);
                }
            }
        }

        private void spawnBlackCube(int id, float x, float z) {
            if (blackCubes.stream().anyMatch(c->c.id==id)) return;
            Material m = unshaded(assetManager, new ColorRGBA(0.1f,0.1f,0.12f,1f));
            Geometry geo = new Geometry("Cube"+id, new Box(0.7f,0.7f,0.7f));
            geo.setMaterial(m);
            geo.setLocalTranslation(x, 0.7f, z);
            RigidBodyControl phy = new RigidBodyControl(new BoxCollisionShape(new Vector3f(0.7f,0.7f,0.7f)), 8f);
            geo.addControl(phy); bulletAppState.getPhysicsSpace().add(phy);
            cubeNode.attachChild(geo);
            blackCubes.add(new BlackCube(id, geo, phy));
            if (!solo) sendNet("CUBE_SPAWN|"+id+"|"+x+"|"+z);
        }

        private void updateCubes(float tpf) {
            cubeNetTimer += tpf;
            boolean doNet = !solo && (cubeNetTimer >= CUBE_NET_INTERVAL);
            if (doNet) cubeNetTimer = 0f;

            for (BlackCube bc : blackCubes) {
                if (!bc.active) continue;
                // Ищем ближайшую живую змею
                SnakePlayer target = null; float minD = Float.MAX_VALUE;
                for (SnakePlayer sp : snakes) {
                    if (sp.isDead()) continue;
                    float d = bc.geo.getLocalTranslation().distance(sp.getHeadPos());
                    if (d < minD) { minD=d; target=sp; }
                }
                if (target==null) continue;
                Vector3f dir = target.getHeadPos().subtract(bc.geo.getLocalTranslation()).normalizeLocal();
                if (bc.phy!=null) {
                    bc.phy.setLinearVelocity(dir.mult(5f));
                    // Угловая скорость = dir × UP, чтобы куб катился, а не скользил
                    Vector3f rollAxis = dir.cross(Vector3f.UNIT_Y).normalizeLocal();
                    bc.phy.setAngularVelocity(rollAxis.mult(5f / 0.7f));
                }
            }
        }

        private void sendCubeStates() {
            for (BlackCube bc : blackCubes) {
                if (!bc.active) continue;
                Vector3f p = bc.geo.getLocalTranslation();
                sendNet("CUBE_STATE|"+bc.id+"|"+fmt(p.x)+"|"+fmt(p.y)+"|"+fmt(p.z));
            }
        }

        private void applyCubeHitLocal(int cubeId, int snakeIdx) {
            if (snakeIdx<0||snakeIdx>=snakes.size()) return;
            SnakePlayer sp = snakes.get(snakeIdx);
            if (sp.isDead()) return;
            for (int i=0;i<CUBE_SHRINK_AMOUNT;i++) sp.shrink();
            playSound(cubeRollSound);
            showCenter("💥 " + sp.getName() + " атакован кубом!", "#ff8844");
            bridge.pushHudChat(null, "💥 " + sp.getName() + " атакован кубом!");
            if (sp.getLength() <= 2) triggerPlayerDeath(snakeIdx, true);
            if (!solo) sendNet("CUBE_HIT|"+cubeId+"|"+snakeIdx);
        }

        // ══ Ивенты ══════════════════════════════════════════════════════════
        private void updateEvents(float tpf) {
            updateBallRain(tpf);
            updateWeatherRain(tpf);
        }

        private void startBallRainEvent() {
            if (ballRainTriggered) return;
            ballRainTriggered=true; ballRainActive=true; ballRainTimer=BALL_RAIN_DURATION;
            showCenter("★ ШАРИКОВЫЙ ДОЖДЬ! ★", "#dd44ff");
            bridge.pushEvent("🎈", "Шариковый дождь!");
            if (isHost||solo) sendNet("EVENT_BALLRAIN");
        }

        private void updateBallRain(float tpf) {
            if (ballRainActive) {
                ballRainTimer -= tpf;
                if (ballRainTimer<=0f) {
                    ballRainActive=false;
                    bridge.pushHideEvent();
                    showCenter("Шарики на земле — собирай!", "#cc88ff");
                } else {
                    ballRainSpawnTimer -= tpf;
                    if (ballRainSpawnTimer<=0f && (solo||isHost)) {
                        ballRainSpawnTimer=0.15f;
                        for (int i=0;i<3;i++) {
                            float rx=(FastMath.nextRandomFloat()-0.5f)*MAP_HALF*1.8f;
                            float rz=(FastMath.nextRandomFloat()-0.5f)*MAP_HALF*1.8f;
                            spawnRainBall(rx, rz);
                        }
                    }
                }
            }
            // Обновление шариков — идентично оригиналу
            for (int i=rainBalls.size()-1;i>=0;i--) {
                RainBall rb = rainBalls.get(i);
                if (rb.landed) {
                    rb.landedTimer-=tpf;
                    if (rb.landedTimer<=0f) { rainBallNode.detachChild(rb.geo); rainBalls.remove(i); continue; }
                    rb.geo.rotate(0f,tpf*0.8f,0f);
                    if (myIndex<snakes.size()&&!snakes.get(myIndex).isDead()) {
                        SnakePlayer me=snakes.get(myIndex);
                        if (me.getHeadPos().distance(rb.geo.getLocalTranslation())<1.5f) {
                            me.grow(assetManager); me.addScore(3); playEatSound();
                            rainBallNode.detachChild(rb.geo); rainBalls.remove(i);
                        }
                    }
                } else {
                    rb.lifeTimer-=tpf;
                    if (rb.lifeTimer<=0f) { rainBallNode.detachChild(rb.geo); rainBalls.remove(i); continue; }
                    Vector3f pos=rb.geo.getLocalTranslation(); pos.y-=15f*tpf;
                    rb.geo.setLocalTranslation(pos); rb.geo.rotate(0.05f,0.1f,0.05f);
                    if (pos.y<=0.5f) {
                        pos.y=0.5f; rb.geo.setLocalTranslation(pos);
                        rb.landed=true; rb.landedTimer=12f;
                    }
                }
            }
        }

        private void spawnRainBall(float x, float z) {
            ColorRGBA[] cols={
                new ColorRGBA(1f,0.2f,0.8f,1f),new ColorRGBA(0.2f,0.8f,1f,1f),
                new ColorRGBA(1f,0.9f,0.1f,1f),new ColorRGBA(0.4f,1f,0.3f,1f)
            };
            float r=0.3f+FastMath.nextRandomFloat()*0.3f;
            Geometry geo=new Geometry("RainBall",new Sphere(8,8,r));
            geo.setMaterial(unshaded(assetManager,cols[FastMath.nextRandomInt(0,cols.length-1)]));
            geo.setLocalTranslation(x,20f+FastMath.nextRandomFloat()*10f,z);
            rainBallNode.attachChild(geo);
            rainBalls.add(new RainBall(geo,4f+FastMath.nextRandomFloat()*3f));
        }

        private void startWeatherRainEvent() {
            if (weatherRainTriggered) return;
            weatherRainTriggered=true; weatherRainActive=true; weatherRainTimer=WEATHER_RAIN_DURATION;
            waterSpeedMultiplier=0.55f;
            showCenter("🌧 ДОЖДЬ! Скорость снижена!", "#4488ff");
            bridge.pushEvent("🌧", "Дождь — замедление!");
            try {
                rainSound=new AudioNode(assetManager,"Sounds/inv/Rain1.ogg",DataType.Buffer);
                rainSound.setPositional(false); rainSound.setLooping(true);
                rainSound.setVolume(effectVolume); rootNode.attachChild(rainSound);
                rainSound.play();
            } catch (Exception ignore) {}
            if (isHost||solo) sendNet("EVENT_RAIN");
        }

        private void updateWeatherRain(float tpf) {
            if (!weatherRainActive) return;
            weatherRainTimer-=tpf;
            if (weatherRainTimer<=0f) {
                weatherRainActive=false; waterSpeedMultiplier=1f;
                if (rainSound!=null) rainSound.stop();
                bridge.pushHideEvent();
                showCenter("Дождь закончился", "#88bbff");
            }
            // Спавн капель дождя
            if ((solo||isHost) && FastMath.nextRandomFloat()<0.3f) {
                float rx=(FastMath.nextRandomFloat()-0.5f)*MAP_HALF*2f;
                float rz=(FastMath.nextRandomFloat()-0.5f)*MAP_HALF*2f;
                spawnRainDrop(rx,rz);
                addWaterPuddle(rx,rz,0.8f+FastMath.nextRandomFloat());
                if (!solo) sendNet("WATER|"+fmt(rx)+"|"+fmt(rz)+"|1.0");
            }
            for (int i=rainDrops.size()-1;i>=0;i--) {
                RainDrop rd=rainDrops.get(i);
                rd.timer-=tpf;
                if (rd.timer<=0f) { rainDropNode.detachChild(rd.geo); rainDrops.remove(i); continue; }
                Vector3f p=rd.geo.getLocalTranslation(); p.y-=20f*tpf;
                rd.geo.setLocalTranslation(p);
                if (p.y<0) { rainDropNode.detachChild(rd.geo); rainDrops.remove(i); }
            }
        }

        private void spawnRainDrop(float x,float z) {
            Geometry g=new Geometry("RD",new Box(0.03f,0.3f,0.03f));
            g.setMaterial(unshaded(assetManager,new ColorRGBA(0.4f,0.7f,1f,0.7f)));
            g.setLocalTranslation(x,15f+FastMath.nextRandomFloat()*5f,z);
            rainDropNode.attachChild(g);
            rainDrops.add(new RainDrop(g,1.2f));
        }

        private void addWaterPuddle(float x,float z,float r) {
            Geometry g=new Geometry("WP",new Box(r,0.02f,r));
            g.setMaterial(unshaded(assetManager,new ColorRGBA(0.3f,0.55f,0.9f,0.5f)));
            g.setLocalTranslation(x,-0.17f,z);
            waterNode.attachChild(g);
            waterPuddles.add(new WaterPuddle(g,x,z,r));
        }

        // ══ Музыка ══════════════════════════════════════════════════════════
        private void loadSounds() {
            eatSounds[0]=tryAudio("Sounds/Eat1.ogg");
            eatSounds[1]=tryAudio("Sounds/Eat2.ogg");
            eatSounds[2]=tryAudio("Sounds/Eat3.ogg");
            eatSounds[3]=tryAudio("Sounds/Burp.ogg");
            mmmSound    =tryAudio("Sounds/mmm.ogg");
            startSound  =tryAudio("Sounds/start.ogg");
            deathSound  =tryAudio("Sounds/death.ogg");
            chitSound   =tryAudio("Sounds/chit.ogg");
            cubeRollSound=tryAudio("Sounds/cube_move.ogg");
            if (startSound!=null) startSound.play();
            loadThemeMusic();
        }

        private void loadThemeMusic() {
            String[] tracks={"Sounds/theme/main1.ogg","Sounds/theme/main2.ogg",
                             "Sounds/theme/main3.ogg","Sounds/theme/main4.ogg"};
            for (int i=0;i<tracks.length;i++) {
                try {
                    themeMusic[i]=new AudioNode(assetManager,tracks[i],DataType.Buffer);
                    themeMusic[i].setPositional(false); themeMusic[i].setLooping(false);
                    themeMusic[i].setVolume(musicVolume); rootNode.attachChild(themeMusic[i]);
                } catch (Exception ignore) {}
            }
            playCurrentTrack();
        }

        private void playCurrentTrack() {
            if (themeMusic[currentTrack]!=null) {
                themeMusic[currentTrack].setVolume(musicVolume);
                themeMusic[currentTrack].play(); trackTimer=0f;
            }
        }

        private void updateMusic(float tpf) {
            trackTimer+=tpf;
            if (trackTimer>TRACK_CHECK_INTERVAL) {
                trackTimer=0f;
                AudioNode cur=themeMusic[currentTrack];
                if (cur!=null&&cur.getStatus()==AudioSource.Status.Stopped) {
                    currentTrack=(currentTrack+1)%themeMusic.length; playCurrentTrack();
                }
            }
        }

        private AudioNode tryAudio(String path) {
            try {
                AudioNode an=new AudioNode(assetManager,path,DataType.Buffer);
                an.setPositional(false); an.setLooping(false); an.setVolume(effectVolume*1.2f);
                rootNode.attachChild(an); return an;
            } catch (Exception e) { return null; }
        }

        private void playSound(AudioNode n) {
            if (n!=null) { n.setVolume(effectVolume*1.2f); n.playInstance(); }
        }

        private void playEatSound() {
            playSound(eatSounds[eatSoundIndex]);
            eatSoundIndex=(eatSoundIndex+1)%eatSounds.length;
        }

        // ══ Сеть ════════════════════════════════════════════════════════════
        private void initNetwork() {
            if (socket==null) {
                try { socket=new DatagramSocket(); socket.setSoTimeout(50); }
                catch (Exception e) { return; }
            } else { try { socket.setSoTimeout(50); } catch (Exception ignore) {} }
            netRecvThread=new Thread(this::netRecvLoop,"GameNet");
            netRecvThread.setDaemon(true); netRecvThread.start();
        }

        private void netRecvLoop() {
            byte[] buf=new byte[8192];
            while (netRunning.get()) {
                try {
                    DatagramPacket pkt=new DatagramPacket(buf,buf.length);
                    socket.receive(pkt);
                    String msg=new String(pkt.getData(),0,pkt.getLength(),StandardCharsets.UTF_8);
                    if (isHost) {
                        InetSocketAddress sender=new InetSocketAddress(pkt.getAddress(),pkt.getPort());
                        byte[] rb=Arrays.copyOf(pkt.getData(),pkt.getLength());
                        for (InetSocketAddress c:clients) {
                            if (!c.equals(sender)) try { socket.send(new DatagramPacket(rb,rb.length,c)); } catch (Exception ignore) {}
                        }
                    }
                    app.enqueue(()->handleNetMsg(msg));
                } catch (SocketTimeoutException ignore) {}
                catch (Exception ignore) {}
            }
        }

        private void sendMyState() {
            if (myIndex>=snakes.size()) return;
            SnakePlayer me=snakes.get(myIndex);
            Vector3f h=me.getHeadPos();
            sendNet("STATE|"+myIndex+"|"+fmt(h.x)+"|"+fmt(h.y)+"|"+fmt(h.z)+
                    "|"+fmt(me.getHeadingAngle())+"|"+me.getScore()+"|"+me.getLength()+
                    "|"+(me.isDead()?1:0));
            sendNet("INPUT|"+myIndex+"|"+(me.isTurnLeft()?1:0)+"|"+(me.isTurnRight()?1:0)+
                    "|"+(me.isMoving()?1:0));
        }

        private void handleNetMsg(String msg) {
            String[] p=msg.split("\\|",-1);
            switch(p[0]) {
                case "INPUT": if(p.length>=4){int i=Integer.parseInt(p[1]);if(i>=0&&i<snakes.size()){snakes.get(i).setTurnLeft("1".equals(p[2]));snakes.get(i).setTurnRight("1".equals(p[3]));if(p.length>=5)snakes.get(i).setMoving("1".equals(p[4]));}break;}
                case "STATE": if(p.length>=9){int i=Integer.parseInt(p[1]);if(i>=0&&i<snakes.size()&&i!=myIndex){snakes.get(i).applyNetState(Float.parseFloat(p[2]),Float.parseFloat(p[3]),Float.parseFloat(p[4]),Float.parseFloat(p[5]),Integer.parseInt(p[6]),Integer.parseInt(p[7]),"1".equals(p[8]));}break;}
                case "DEAD": if(p.length>=2){int i=Integer.parseInt(p[1]);if(i>=0&&i<snakes.size()&&!snakes.get(i).isDead()){snakes.get(i).triggerDeathRemote(rootNode);checkWinCondition();}break;}
                case "FOOD": if(p.length>=5)addFoodWithData(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),"1".equals(p[4]),p.length>5&&"1".equals(p[5]));break;
                case "DEBRIS":if(p.length>=4)addFoodWithData(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),false,true);break;
                case "EAT": if(p.length>=2)removeFoodById(Integer.parseInt(p[1]));break;
                case "WIN": if(p.length>=2){showCenter("🏆 "+p[1]+" ПОБЕДИЛ!","#ffc84a");gameOver=true;exitTimer=8f;}break;
                case "CUBE_SPAWN":if(p.length>=4)spawnBlackCube(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]));break;
                case "CUBE_HIT":if(p.length>=3)applyCubeHitLocal(Integer.parseInt(p[1]),Integer.parseInt(p[2]));break;
                case "GCHAT":if(p.length>=3){chatMessages.add(p[1]+": "+p[2]);bridge.pushHudChat(p[1],p[2]);}break;
                case "EVENT_BALLRAIN":if(!ballRainTriggered)startBallRainEvent();break;
                case "EVENT_RAIN":if(!weatherRainTriggered)startWeatherRainEvent();break;
                case "WATER":if(p.length>=4)addWaterPuddle(Float.parseFloat(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]));break;
            }
        }

        private void sendNet(String msg) {
            if (socket==null) return;
            byte[] b=msg.getBytes(StandardCharsets.UTF_8);
            if (isHost) {
                for (InetSocketAddress c:clients) try{socket.send(new DatagramPacket(b,b.length,c));}catch(Exception ignore){}
            } else if (hostAddress!=null) {
                try{socket.send(new DatagramPacket(b,b.length,new InetSocketAddress(hostAddress,hostPort)));}catch(Exception ignore){}
            }
        }

        @Override
        public void cleanup() {
            super.cleanup();
            netRunning.set(false);
            if (socket != null && !socket.isClosed()) socket.close();
            // BulletAppState должен быть уже детачен из backToMenu().
            // Если cleanup вызван напрямую (через detach без backToMenu) — подчищаем здесь.
            if (bulletAppState != null) {
                try { stateManager.detach(bulletAppState); } catch (Exception ignore) {}
                bulletAppState = null;
            }
        }

        // ── Вспомогательные геометрия / форматирование ──────────────────────
        private void addBox(Vector3f pos, Vector3f half, Material mat, PhysicsSpace space) {
            Box box=new Box(half.x,half.y,half.z);
            Geometry geo=new Geometry("Box",box); geo.setMaterial(mat);
            geo.setLocalTranslation(pos);
            if (space!=null) {
                RigidBodyControl phy=new RigidBodyControl(new BoxCollisionShape(half),0f);
                geo.addControl(phy); space.add(phy);
            }
            (wallNode!=null?wallNode:rootNode).attachChild(geo);
        }

        private static String fmt(float v) { return String.format("%.3f",v); }
        private static String escapeJson(String s) { return SnakeApp.escapeJson(s); }
    }

    // ═════════════════════════════════════════════════════════════════════
    // DATA CLASSES (без изменений по сравнению с оригиналом)
    // ═════════════════════════════════════════════════════════════════════

    static class FoodItem {
        Geometry geo; boolean bad; int id; boolean isDebris;
        FoodItem(Geometry g,boolean bad,int id,boolean d){ geo=g;this.bad=bad;this.id=id;isDebris=d; }
    }

    static class BlackCube {
        int id; Geometry geo; RigidBodyControl phy; boolean active=true;
        BlackCube(int id,Geometry g,RigidBodyControl p){ this.id=id;geo=g;phy=p; }
    }

    static class RainBall {
        Geometry geo; float lifeTimer; boolean landed=false; float landedTimer=0f;
        RainBall(Geometry g,float t){ geo=g;lifeTimer=t; }
    }

    static class RainDrop {
        Geometry geo; float timer;
        RainDrop(Geometry g,float t){ geo=g;timer=t; }
    }

    static class WaterPuddle {
        Geometry geo; float x,z,radius;
        WaterPuddle(Geometry g,float x,float z,float r){ geo=g;this.x=x;this.z=z;radius=r; }
    }

    // ═════════════════════════════════════════════════════════════════════
    // SNAKE PLAYER (без изменений — логика движения/роста/коллизий)
    // ═════════════════════════════════════════════════════════════════════
    static class SnakePlayer {
        private final String   name;
        private final Material baseMat;
        private final Node     parentNode;
        private final AssetManager assetManager;
        private final com.jme3.bullet.PhysicsSpace physicsSpace;

        private final List<Geometry>  segments = new ArrayList<>();
        private final List<Vector3f>  segPos   = new ArrayList<>();

        private float   headingAngle;
        private Vector3f direction;
        private boolean turnLeft, turnRight, movingInput;
        private float   currentSpeed = 0f;
        private static final float ACCEL=18f, DECEL=12f;

        private int     score = 0;
        private boolean dead  = false;
        public static final float SEG_R = 0.27f;

        // Тэг имени теперь отображается в HTML (не jME BitmapText)
        private final Camera camRef;

        public SnakePlayer(String name, Vector3f startPos, float startAngle,
                           Material mat, Node parent, AssetManager am,
                           Camera cam, com.jme3.bullet.PhysicsSpace space) {
            this.name=name; this.baseMat=mat; this.parentNode=parent;
            this.assetManager=am; this.camRef=cam;
            this.physicsSpace=space; this.headingAngle=startAngle;
            this.direction=calcDir(headingAngle);
            Vector3f back=direction.negate();
            for (int i=0;i<4;i++) addSegment(startPos.add(back.mult(i*0.55f)));
        }

        private void addSegment(Vector3f pos) {
            Geometry g=new Geometry("Seg"+segments.size(),new Sphere(12,12,SEG_R));
            Material sm=baseMat.clone();
            float factor=Math.max(0.5f,1f-segments.size()*0.04f);
            Object cv=baseMat.getParamValue("Color");
            if (cv instanceof ColorRGBA c)
                sm.setColor("Color",new ColorRGBA(c.r*factor,c.g*factor,c.b*factor,1f));
            g.setMaterial(sm); g.setLocalTranslation(pos.clone());
            parentNode.attachChild(g);
            if (physicsSpace!=null&&segments.size()>0) {
                com.jme3.bullet.control.RigidBodyControl phy=
                    new com.jme3.bullet.control.RigidBodyControl(
                        new com.jme3.bullet.collision.shapes.SphereCollisionShape(SEG_R),1f);
                phy.setKinematic(true); g.addControl(phy); physicsSpace.add(phy);
            }
            segments.add(g); segPos.add(pos.clone());
        }

        public void setMoving(boolean v) { movingInput=v; }
        public boolean isMoving()        { return movingInput; }

        public void update(float tpf, float maxSpeed, float turnSpeed, float spacing) {
            if (dead) return;
            if (movingInput) currentSpeed=Math.min(maxSpeed,currentSpeed+ACCEL*tpf);
            else             currentSpeed=Math.max(0f,currentSpeed-DECEL*tpf);
            float ets=turnSpeed*(currentSpeed/maxSpeed);
            if (turnLeft)  headingAngle+=ets*tpf;
            if (turnRight) headingAngle-=ets*tpf;
            direction=calcDir(headingAngle);
            if (currentSpeed<0.01f) return;
            segPos.get(0).addLocal(direction.mult(currentSpeed*tpf));
            segments.get(0).setLocalTranslation(segPos.get(0));
            for (int i=1;i<segPos.size();i++) {
                Vector3f prev=segPos.get(i-1),cur=segPos.get(i);
                Vector3f diff=prev.subtract(cur); float dist=diff.length();
                if (dist>spacing){cur.addLocal(diff.normalize().mult(dist-spacing));segments.get(i).setLocalTranslation(cur);}
            }
        }

        public void applyNetState(float x,float y,float z,float angle,int sc,int remLen,boolean isDead) {
            if (!segPos.isEmpty()) {
                Vector3f nh=new Vector3f(x,y,z);
                headingAngle=angle; direction=calcDir(headingAngle);
                segPos.get(0).set(nh); segments.get(0).setLocalTranslation(nh);
                while(segments.size()<remLen) grow(assetManager);
                while(segments.size()>remLen&&segments.size()>2) shrink();
                for (int i=1;i<segPos.size();i++){
                    Vector3f prev=segPos.get(i-1),cur=segPos.get(i);
                    Vector3f diff=prev.subtract(cur);float dist=diff.length();
                    if(dist>0.55f){cur.addLocal(diff.normalize().mult(dist-0.55f));segments.get(i).setLocalTranslation(cur);}
                }
            }
            score=sc;
            if (isDead&&!dead) triggerDeathRemote(parentNode.getParent());
        }

        public void triggerDeath(Node scene) {
            if (dead) return; dead=true;
            for (Geometry seg:segments) {
                com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
                if (phy!=null&&physicsSpace!=null){phy.setEnabled(false);physicsSpace.remove(phy);}
                parentNode.detachChild(seg);
            }
            segments.clear(); segPos.clear();
        }

        public void triggerDeathRemote(Node scene) { triggerDeath(scene); }

        public List<Vector3f> getSegmentPositions() {
            List<Vector3f> r=new ArrayList<>();
            for(Vector3f p:segPos) r.add(p.clone()); return r;
        }

        public void grow(AssetManager am) {
            if (!segPos.isEmpty()) addSegment(segPos.get(segPos.size()-1).clone());
        }

        public void shrink() {
            if (segments.size()<=2) return;
            int idx=segments.size()-1;
            Geometry seg=segments.get(idx);
            com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
            if (phy!=null&&physicsSpace!=null){phy.setEnabled(false);physicsSpace.remove(phy);}
            parentNode.detachChild(seg); segments.remove(idx); segPos.remove(idx);
        }

        public boolean selfCollides(float minDist) {
            if (segPos.size()<4) return false;
            Vector3f h=segPos.get(0);
            for (int i=3;i<segPos.size();i++) if(h.distance(segPos.get(i))<minDist) return true;
            return false;
        }

        public boolean bodyContains(Vector3f point,float radius) {
            for (int i=1;i<segPos.size();i++) if(point.distance(segPos.get(i))<radius) return true;
            return false;
        }

        private static Vector3f calcDir(float angle){return new Vector3f(FastMath.sin(angle),0,FastMath.cos(angle));}

        public void setTurnLeft(boolean v)  { turnLeft=v; }
        public void setTurnRight(boolean v) { turnRight=v; }
        public boolean isTurnLeft()  { return turnLeft; }
        public boolean isTurnRight() { return turnRight; }
        public Vector3f getHeadPos() { return segPos.isEmpty()?Vector3f.ZERO:segPos.get(0); }
        public Vector3f getDirection(){ return direction; }
        public float getHeadingAngle(){ return headingAngle; }
        public String getName()  { return name; }
        public int getScore()    { return score; }
        public int getLength()   { return segments.size(); }
        public boolean isDead()  { return dead; }
        public void addScore(int v){ score+=v; }
    }
}