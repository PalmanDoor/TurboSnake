import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.app.Application;
import com.jme3.asset.AssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.audio.AudioNode;
import com.jme3.post.FilterPostProcessor;
import com.jme3.post.filters.BloomFilter;
import com.jme3.post.filters.FogFilter;
import com.jme3.post.filters.LightScatteringFilter;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.shadow.DirectionalLightShadowRenderer;
import com.jme3.shadow.EdgeFilteringMode;
import com.jme3.scene.plugins.fbx.FbxLoader;
import com.jme3.audio.AudioData.DataType;
import com.jme3.material.RenderState;
import com.jme3.bullet.BulletAppState;
import com.jme3.bullet.PhysicsSpace;
import com.jme3.bullet.collision.shapes.*;
import com.jme3.util.BufferUtils;
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
import com.jme3.renderer.ViewPort;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.ui.Picture;

// Lemur UI — нормальные кнопки/панели/контейнеры для jMonkeyEngine.
// JAR нужно положить в lib/, иначе эти импорты не найдутся при javac.
import com.simsilica.lemur.Axis;
import com.simsilica.lemur.Button;
import com.simsilica.lemur.Container;
import com.simsilica.lemur.GuiGlobals;
import com.simsilica.lemur.Label;
import com.simsilica.lemur.Panel;
import com.simsilica.lemur.Slider;
import com.simsilica.lemur.DefaultRangedValueModel;
import com.simsilica.lemur.component.QuadBackgroundComponent;
import com.simsilica.lemur.style.BaseStyles;
import com.simsilica.lemur.style.ElementId;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SnakeApp extends SimpleApplication {

	// Hello from FTD

    public static final int BROADCAST_PORT = 45678;
    public static final int GAME_PORT      = 45679;

    // =========================================================================
    // NULL-SAFE / FAIL-SAFE УТИЛИТЫ
    // -------------------------------------------------------------------------
    // Эти методы не исправляют логику игры за счёт “прятания” ошибок, а делают
    // опасные места устойчивее: публичные update/cleanup, сеть, ассеты, GUI и
    // многопоточные коллекции не должны ронять игру из-за одного null.
    // =========================================================================
    private static final String NULL_SAFE_TAG = "NullSafe";

    static void logSafe(String area, String message) {
        System.out.println("[" + NULL_SAFE_TAG + "/" + area + "] " + message);
    }

    static void logSafe(String area, Throwable t) {
        if (t == null) {
            logSafe(area, "unknown error");
            return;
        }
        String msg = t.getMessage();
        logSafe(area, t.getClass().getSimpleName() + (msg == null || msg.isBlank() ? "" : ": " + msg));
    }

    static float safeTpf(float tpf) {
        if (Float.isNaN(tpf) || Float.isInfinite(tpf) || tpf <= 0f) return 0f;
        // Если окно зависло/перетащили — не даём физике получить огромный шаг.
        return FastMath.clamp(tpf, 0f, 0.25f);
    }

    static String safeString(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback == null ? "" : fallback;
        return value;
    }

    static ColorRGBA safeColor(ColorRGBA value, ColorRGBA fallback) {
        ColorRGBA c = value != null ? value : fallback;
        return c != null ? c.clone() : new ColorRGBA(1f, 1f, 1f, 1f);
    }

    static float parseFloatOrDefault(String value, float fallback, String area) {
        try {
            return value == null ? fallback : Float.parseFloat(value);
        } catch (Exception e) {
            logSafe(area, "bad float '" + value + "', fallback=" + fallback);
            return fallback;
        }
    }

    static int parseIntOrDefault(String value, int fallback, String area) {
        try {
            return value == null ? fallback : Integer.parseInt(value);
        } catch (Exception e) {
            logSafe(area, "bad int '" + value + "', fallback=" + fallback);
            return fallback;
        }
    }

    static boolean invalidApplication(Application application, String area) {
        if (!(application instanceof SimpleApplication)) {
            logSafe(area, "application is not SimpleApplication");
            return true;
        }
        return false;
    }

    static boolean missingRefs(String area, Object... refs) {
        if (refs == null) return false;
        for (Object ref : refs) {
            if (ref == null) {
                logSafe(area, "skip: required object is null");
                return true;
            }
        }
        return false;
    }

    static <T> List<T> snapshot(Collection<T> source) {
        if (source == null || source.isEmpty()) return Collections.emptyList();
        try { return new ArrayList<>(source); }
        catch (Exception e) { logSafe("snapshot", e); return Collections.emptyList(); }
    }

    static Optional<Spatial> childOptional(Node parent, String name) {
        if (parent == null || name == null) return Optional.empty();
        try { return Optional.ofNullable(parent.getChild(name)); }
        catch (Exception e) { logSafe("childOptional", e); return Optional.empty(); }
    }

    static <T extends com.jme3.scene.control.Control> Optional<T> controlOptional(Spatial spatial, Class<T> type) {
        if (spatial == null || type == null) return Optional.empty();
        try { return Optional.ofNullable(spatial.getControl(type)); }
        catch (Exception e) { logSafe("controlOptional", e); return Optional.empty(); }
    }

    static void closeQuietly(DatagramSocket s) {
        if (s == null) return;
        try { if (!s.isClosed()) s.close(); }
        catch (Exception e) { logSafe("socket.close", e); }
    }

    static void detachQuietly(Node parent, Spatial child) {
        if (parent == null || child == null) return;
        try { parent.detachChild(child); }
        catch (Exception e) { logSafe("detach", e); }
    }

    static void removeFromParentQuietly(Spatial spatial) {
        if (spatial == null) return;
        try { spatial.removeFromParent(); }
        catch (Exception e) { logSafe("removeFromParent", e); }
    }

    static void clearInputMappingsQuietly(InputManager im) {
        if (im == null) return;
        try { im.clearMappings(); }
        catch (Exception e) { logSafe("input.clearMappings", e); }
    }

    static PhysicsSpace physicsSpaceOrNull(BulletAppState bullet) {
        try { return bullet == null ? null : bullet.getPhysicsSpace(); }
        catch (Exception e) { logSafe("physicsSpace", e); return null; }
    }


    static float effectVolume = 1.0f;
    static float musicVolume  = 0.5f;
    static int selectedMap = 0;
    static ColorRGBA selectedSnakeColor = new ColorRGBA(0.15f, 0.9f, 0.3f, 1f);
    static String savedNickname = getSystemUsername();
    // Настройки графики
    static final int SHADOW_QUALITY_OFF    = -1;
    static final int SHADOW_QUALITY_LOW    = 0;
    static final int SHADOW_QUALITY_MEDIUM = 1;
    static final int SHADOW_QUALITY_HIGH   = 2;

    // shadowsEnabled оставлен для совместимости со старым settings.properties.
    // Реальный режим теперь хранится в shadowQuality:
    // ВЫКЛ -> НИЗКИЕ -> СРЕДНИЕ -> ВЫСОКИЕ.
    static boolean shadowsEnabled   = true;
    static int shadowQuality        = SHADOW_QUALITY_MEDIUM;
    static boolean particlesEnabled = true;
    static boolean fogEnabled       = true;
    static boolean bloomEnabled     = true;
    static boolean postProcessingEnabled = true;
    static boolean dynamicLightsEnabled  = true;
    static boolean waterEffectsEnabled   = true;
    static boolean terrainDetailsEnabled = true;
    static boolean lowPolyMode           = false;
    static boolean nameTagsEnabled       = true;

    // =========================================================================
    // НАСТРОЙКИ (сохранение/загрузка в %appdata%/SSnake3D/)
    // =========================================================================
    static java.io.File getSettingsFile() {
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isBlank()) appdata = System.getProperty("user.home");
        if (appdata == null || appdata.isBlank()) appdata = ".";
        java.io.File dir = new java.io.File(appdata, "SSnake3D");
        if (!dir.exists() && !dir.mkdirs()) {
            logSafe("Settings", "cannot create settings dir: " + dir.getAbsolutePath());
        }
        return new java.io.File(dir, "settings.properties");
    }

    static void loadSettings() {
        java.io.File f = getSettingsFile();
        if (!f.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            effectVolume       = parseFloatOrDefault(p.getProperty("effectVolume", "1.0"), 1.0f, "Settings.effectVolume");
            musicVolume        = parseFloatOrDefault(p.getProperty("musicVolume",  "0.5"), 0.5f, "Settings.musicVolume");
            savedNickname      = safeString(p.getProperty("nickname", getSystemUsername()), getSystemUsername());
            shadowsEnabled     = Boolean.parseBoolean(p.getProperty("shadowsEnabled",   "true"));
            shadowQuality      = clampShadowQuality(parseIntOrDefault(
                    p.getProperty("shadowQuality", shadowsEnabled ? String.valueOf(SHADOW_QUALITY_MEDIUM) : String.valueOf(SHADOW_QUALITY_OFF)),
                    SHADOW_QUALITY_MEDIUM,
                    "Settings.shadowQuality"));
            shadowsEnabled     = shadowQuality != SHADOW_QUALITY_OFF;
            particlesEnabled   = Boolean.parseBoolean(p.getProperty("particlesEnabled", "true"));
            fogEnabled         = Boolean.parseBoolean(p.getProperty("fogEnabled",       "true"));
            bloomEnabled       = Boolean.parseBoolean(p.getProperty("bloomEnabled",     "true"));
            postProcessingEnabled = Boolean.parseBoolean(p.getProperty("postProcessingEnabled", "true"));
            dynamicLightsEnabled  = Boolean.parseBoolean(p.getProperty("dynamicLightsEnabled",  "true"));
            waterEffectsEnabled   = Boolean.parseBoolean(p.getProperty("waterEffectsEnabled",   "true"));
            terrainDetailsEnabled = Boolean.parseBoolean(p.getProperty("terrainDetailsEnabled", "true"));
            lowPolyMode           = Boolean.parseBoolean(p.getProperty("lowPolyMode",           "false"));
            nameTagsEnabled       = Boolean.parseBoolean(p.getProperty("nameTagsEnabled",       "true"));
        } catch (Exception e) { System.out.println("[Settings] Load error: " + e.getMessage()); }
    }

    static void saveSettings(String nickname) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(getSettingsFile())) {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("effectVolume",  String.valueOf(effectVolume));
            p.setProperty("musicVolume",   String.valueOf(musicVolume));
            p.setProperty("nickname",      safeString(nickname, safeString(savedNickname, getSystemUsername())));
            shadowQuality = clampShadowQuality(shadowQuality);
            shadowsEnabled = shadowQuality != SHADOW_QUALITY_OFF;
            p.setProperty("shadowsEnabled",   String.valueOf(shadowsEnabled));
            p.setProperty("shadowQuality",    String.valueOf(shadowQuality));
            p.setProperty("particlesEnabled", String.valueOf(particlesEnabled));
            p.setProperty("fogEnabled",       String.valueOf(fogEnabled));
            p.setProperty("bloomEnabled",     String.valueOf(bloomEnabled));
            p.setProperty("postProcessingEnabled", String.valueOf(postProcessingEnabled));
            p.setProperty("dynamicLightsEnabled",  String.valueOf(dynamicLightsEnabled));
            p.setProperty("waterEffectsEnabled",   String.valueOf(waterEffectsEnabled));
            p.setProperty("terrainDetailsEnabled", String.valueOf(terrainDetailsEnabled));
            p.setProperty("lowPolyMode",           String.valueOf(lowPolyMode));
            p.setProperty("nameTagsEnabled",       String.valueOf(nameTagsEnabled));
            p.store(out, "SSnake3D Settings");
        } catch (Exception e) { System.out.println("[Settings] Save error: " + e.getMessage()); }
    }

    public static final ColorRGBA ACCENT      = new ColorRGBA(0.12f, 0.94f, 0.48f, 1f);
    public static final ColorRGBA ACCENT_DIM  = new ColorRGBA(0.04f, 0.48f, 0.24f, 1f);
    public static final ColorRGBA ACCENT2     = new ColorRGBA(0.13f, 0.76f, 1f, 1f);
    public static final ColorRGBA ACCENT3     = new ColorRGBA(1f, 0.78f, 0.29f, 1f);
    public static final ColorRGBA DANGER      = new ColorRGBA(1f, 0.27f, 0.27f, 1f);
    public static final ColorRGBA TEXT        = new ColorRGBA(0.91f, 0.92f, 0.96f, 1f);
    public static final ColorRGBA TEXT_DIM    = new ColorRGBA(0.42f, 0.48f, 0.64f, 1f);
    public static final ColorRGBA BORDER      = new ColorRGBA(0.11f, 0.16f, 0.33f, 1f);
    public static final ColorRGBA BG          = new ColorRGBA(0.027f, 0.043f, 0.102f, 1f);
    public static final ColorRGBA BG_CARD     = new ColorRGBA(0.05f, 0.07f, 0.15f, 0.93f);
    public static final ColorRGBA BTN_NORMAL  = new ColorRGBA(0.067f, 0.114f, 0.243f, 0.9f);
    public static final ColorRGBA BTN_HOVER   = new ColorRGBA(0.102f, 0.20f, 0.50f, 0.97f);
    public static final ColorRGBA BTN_PRESS   = new ColorRGBA(0.035f, 0.055f, 0.122f, 1f);

    static int clampShadowQuality(int q) {
        return Math.max(SHADOW_QUALITY_OFF, Math.min(q, SHADOW_QUALITY_HIGH));
    }

    static int nextShadowQuality(int q) {
        q = clampShadowQuality(q);
        return q >= SHADOW_QUALITY_HIGH ? SHADOW_QUALITY_OFF : q + 1;
    }

    static String shadowQualityButtonText() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:    return "ТЕНИ ВЫКЛ";
            case SHADOW_QUALITY_LOW:    return "ТЕНИ НИЗКИЕ";
            case SHADOW_QUALITY_MEDIUM: return "ТЕНИ СРЕДНИЕ";
            case SHADOW_QUALITY_HIGH:   return "ТЕНИ ВЫСОКИЕ";
            default:                    return "ТЕНИ СРЕДНИЕ";
        }
    }

    static ColorRGBA shadowQualityAccentColor() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:    return DANGER;
            case SHADOW_QUALITY_LOW:    return TEXT_DIM;
            case SHADOW_QUALITY_MEDIUM: return ACCENT2;
            case SHADOW_QUALITY_HIGH:   return ACCENT;
            default:                    return ACCENT2;
        }
    }

    static ColorRGBA shadowQualityBgColor() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:
                return new ColorRGBA(0.18f, 0.045f, 0.055f, 0.92f);
            case SHADOW_QUALITY_LOW:
                return BTN_NORMAL;
            case SHADOW_QUALITY_MEDIUM:
                return new ColorRGBA(0.035f, 0.10f, 0.16f, 0.92f);
            case SHADOW_QUALITY_HIGH:
                return new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f);
            default:
                return BTN_NORMAL;
        }
    }

    static int shadowMapSizeForQuality() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:    return 0;
            case SHADOW_QUALITY_LOW:    return 512;
            case SHADOW_QUALITY_MEDIUM: return 1024;
            case SHADOW_QUALITY_HIGH:   return 2048;
            default:                    return 1024;
        }
    }

    static int shadowSplitsForQuality() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:    return 0;
            case SHADOW_QUALITY_LOW:    return 1;
            case SHADOW_QUALITY_MEDIUM: return 2;
            case SHADOW_QUALITY_HIGH:   return 3;
            default:                    return 2;
        }
    }

    static EdgeFilteringMode shadowFilteringForQuality() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:    return EdgeFilteringMode.Nearest;
            case SHADOW_QUALITY_LOW:    return EdgeFilteringMode.Nearest;
            case SHADOW_QUALITY_MEDIUM: return EdgeFilteringMode.PCF4;
            case SHADOW_QUALITY_HIGH:   return EdgeFilteringMode.PCFPOISSON;
            default:                    return EdgeFilteringMode.PCF4;
        }
    }

    static float shadowIntensityForQuality() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:    return 0f;
            case SHADOW_QUALITY_LOW:    return 0.55f;
            case SHADOW_QUALITY_MEDIUM: return 0.65f;
            case SHADOW_QUALITY_HIGH:   return 0.72f;
            default:                    return 0.65f;
        }
    }

    static float shadowZExtendForQuality() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:    return 0f;
            case SHADOW_QUALITY_LOW:    return 65f;
            case SHADOW_QUALITY_MEDIUM: return 90f;
            case SHADOW_QUALITY_HIGH:   return 120f;
            default:                    return 90f;
        }
    }

    static float shadowZFadeForQuality() {
        switch (clampShadowQuality(shadowQuality)) {
            case SHADOW_QUALITY_OFF:    return 0f;
            case SHADOW_QUALITY_LOW:    return 8f;
            case SHADOW_QUALITY_MEDIUM: return 12f;
            case SHADOW_QUALITY_HIGH:   return 15f;
            default:                    return 12f;
        }
    }

    static DirectionalLightShadowRenderer createShadowRendererForCurrentQuality(AssetManager am, DirectionalLight light) {
        shadowQuality = clampShadowQuality(shadowQuality);
        shadowsEnabled = shadowQuality != SHADOW_QUALITY_OFF;
        if (!shadowsEnabled || shadowQuality == SHADOW_QUALITY_OFF || am == null || light == null) return null;
        try {
            DirectionalLightShadowRenderer renderer = new DirectionalLightShadowRenderer(
                    am,
                    shadowMapSizeForQuality(),
                    shadowSplitsForQuality()
            );
            renderer.setLight(light);
            renderer.setShadowIntensity(shadowIntensityForQuality());
            renderer.setEdgeFilteringMode(shadowFilteringForQuality());
            renderer.setShadowZExtend(shadowZExtendForQuality());
            renderer.setShadowZFadeLength(shadowZFadeForQuality());
            return renderer;
        } catch (Exception e) {
            logSafe("Graphics.shadow.create", e);
            return null;
        }
    }

    // Глобальный менеджер фоновой музыки — один трек на всё время игры
    static class MusicManager {
        private static AudioNode currentBgMusic = null;
        private static String currentTrack = "";

        static void play(AssetManager am, Node root, String track, float volume) {
            if (am == null || root == null || track == null || track.isBlank()) {
                logSafe("Music", "skip play: missing assetManager/root/track");
                return;
            }
            // Если уже играет этот трек — просто обновляем громкость
            if (track.equals(currentTrack) && currentBgMusic != null
                    && currentBgMusic.getStatus() == AudioSource.Status.Playing) {
                currentBgMusic.setVolume(FastMath.clamp(volume, 0f, 1f));
                return;
            }
            stop(root);
            try {
                currentBgMusic = new AudioNode(am, track, DataType.Stream);
                currentBgMusic.setPositional(false);
                currentBgMusic.setLooping(true);
                currentBgMusic.setVolume(FastMath.clamp(volume, 0f, 1f));
                root.attachChild(currentBgMusic);
                currentBgMusic.play();
                currentTrack = track;
            } catch (Exception e) {
                logSafe("Music", track + " not found or failed to play");
                currentBgMusic = null;
                currentTrack = "";
            }
        }

        static void setVolume(float v) {
            if (currentBgMusic != null) currentBgMusic.setVolume(FastMath.clamp(v, 0f, 1f));
        }

        static void stop(Node root) {
            if (currentBgMusic != null) {
                try { currentBgMusic.stop(); } catch (Exception e) { logSafe("Music.stop", e); }
                detachQuietly(root, currentBgMusic);
                currentBgMusic = null;
                currentTrack = "";
            }
        }
    }

		public static void main(String[] args) {

				SnakeApp app = new SnakeApp();

				AppSettings s = new AppSettings(true);

				s.setTitle("Turbo Snake");

				// стартовое разрешение
				s.setResolution(1600, 900);

				// разрешаем менять размер окна
				s.setResizable(true);

				// borderless fullscreen стиль
				s.setFullscreen(false);

				s.setVSync(true);

				s.setSamples(4);

				s.setFrameRate(144);

				s.setBitsPerPixel(32);

				s.setDepthBits(24);

				app.setSettings(s);

				app.setShowSettings(false);

				app.start();
		}

    @Override
    public void simpleInitApp() {
        try { loadSettings(); } catch (Exception e) { logSafe("simpleInitApp.loadSettings", e); }
        LemurUi.init(this);
        try {
            if (inputManager != null && inputManager.hasMapping(SimpleApplication.INPUT_MAPPING_EXIT)) {
                inputManager.deleteMapping(SimpleApplication.INPUT_MAPPING_EXIT);
            }
            if (flyCam != null) flyCam.setEnabled(false);
            setDisplayStatView(false);
            setDisplayFps(false);
            if (assetManager != null) assetManager.registerLocator(".", FileLocator.class);
            if (stateManager != null) stateManager.attach(new SplashState());
        } catch (Exception e) {
            logSafe("simpleInitApp", e);
        }
    }

    // ---------- утилиты ----------
    static Material unshaded(AssetManager am, ColorRGBA c) {
        if (am == null) { logSafe("Material", "AssetManager is null for Unshaded"); return null; }
        try {
            Material m = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            m.setColor("Color", safeColor(c, TEXT));
            return m;
        } catch (Exception e) {
            logSafe("Material.unshaded", e);
            return null;
        }
    }

    /** Создаёт Lighting.j3md материал, реагирующий на день/ночь. */
    static Material litMat(AssetManager am, ColorRGBA diffuse) {
        if (am == null) { logSafe("Material", "AssetManager is null for Lighting"); return null; }
        try {
            ColorRGBA base = safeColor(diffuse, TEXT);
            Material m = new Material(am, "Common/MatDefs/Light/Lighting.j3md");
            m.setBoolean("UseMaterialColors", true);
            m.setColor("Diffuse",  base);
            m.setColor("Ambient",  base.mult(0.25f));
            m.setColor("Specular", new ColorRGBA(0.06f, 0.06f, 0.06f, 1f));
            m.setFloat("Shininess", 6f);
            return m;
        } catch (Exception e) {
            logSafe("Material.litMat", e);
            return unshaded(am, diffuse);
        }
    }

    public static BitmapFont loadFont(AssetManager am) {
        if (am == null) { logSafe("Font", "AssetManager is null"); return null; }
        try { return am.loadFont("Fonts/bitmap.fnt"); }
        catch (Exception e) {
            logSafe("Font", "Fonts/bitmap.fnt not loaded, using default");
            try { return am.loadFont("Interface/Fonts/Default.fnt"); }
            catch (Exception ex) { logSafe("Font.default", ex); return null; }
        }
    }


    static void forceMenuCursor(SimpleApplication app) {
        if (app == null || app.getInputManager() == null) return;
        app.getInputManager().setCursorVisible(true);
        if (app.getFlyByCamera() != null) {
            app.getFlyByCamera().setEnabled(false);
        }
    }


    // Единая виртуальная сетка интерфейса: элементы масштабируются от 1600x900,
    // поэтому при разворачивании окна HUD и меню больше не становятся визуально мелкими.
    static final float UI_BASE_W = 1600f;
    static final float UI_BASE_H = 900f;

    static float uiScale(Camera cam) {
        if (cam == null) return 1f;
        float sx = cam.getWidth()  / UI_BASE_W;
        float sy = cam.getHeight() / UI_BASE_H;
        // При разворачивании окна GUI больше не выглядит мелким:
        // если экран больше базового 1600x900 — масштабируем по большей стороне.
        // Если окно меньше базового — уменьшаем только настолько, чтобы элементы не вылезали.
        float s = (cam.getWidth() >= UI_BASE_W && cam.getHeight() >= UI_BASE_H)
                ? Math.max(sx, sy)
                : Math.min(sx, sy);
        return FastMath.clamp(s, 0.78f, 1.70f);
    }

    static float grid(float start, float step, int index) {
        return start + step * index;
    }

    // =========================================================================
    // LEMUR UI
    // -------------------------------------------------------------------------
    // Новый основной UI-слой. Lemur сам даёт нормальные Spatial-элементы,
    // hover/focus, стили и контейнеры. Старый UiGrid оставлен как адаптер
    // для точного позиционирования и hit box-логики, чтобы не переписывать
    // всю игровую логику кликов за один раз.
    // =========================================================================
    static class LemurUi {
        private static boolean initialized = false;

        static void init(SimpleApplication app) {
            if (initialized || app == null) return;
            try {
                GuiGlobals.initialize(app);
            } catch (RuntimeException ex) {
                logSafe("Lemur.init", ex);
                return;
            }

            // BaseStyles.loadGlassStyle() загружает glass-styles.groovy.
            // Для этого в lib должен лежать Groovy JSR-223 engine
            // (groovy-jsr223 + groovy). Если его нет, раньше игра падала
            // при старте: RuntimeException: Groovy scripting engine not available.
            // Теперь игра не падает, а просто использует вручную заданные
            // фоны/цвета у кнопок и панелей.
            try {
                BaseStyles.loadGlassStyle();
                GuiGlobals.getInstance().getStyles().setDefaultStyle(BaseStyles.GLASS);
            } catch (RuntimeException ex) {
                System.out.println("[Lemur] Glass style skipped: " + ex.getMessage());
                System.out.println("[Lemur] Для полного glass-стиля скачай groovy-jsr223 через download_all_libs.bat");
            }

            initialized = true;
        }

        static Button button(String text, float x, float topY, float w, float h, float z,
                             ColorRGBA bg, ColorRGBA textColor, AssetManager am) {
            text = safeString(text, "");
            bg = safeColor(bg, new ColorRGBA(0f, 0f, 0f, 0f));
            textColor = safeColor(textColor, TEXT);
            Button b;
            try { b = new Button(text, BaseStyles.GLASS); }
            catch (RuntimeException ex) { b = new Button(text); }

            // Важно: стандартный Lemur/JME font часто не содержит кириллицу.
            // Поэтому явно ставим тот же font, который раньше использовал BitmapText.
            try { b.setFont(loadFont(am)); } catch (Exception ignored) {}

            b.setPreferredSize(new Vector3f(w, h, 0f));
            b.setLocalTranslation(x, topY, z);
            b.setColor(textColor);
						float fontSize = h * 0.43f;
						if (fontSize < 10f) fontSize = 10f;   // минимальный размер на случай очень маленького окна
						b.setFontSize(fontSize);
            b.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);
            b.setTextVAlignment(com.simsilica.lemur.VAlignment.Center);
            b.setInsets(new com.simsilica.lemur.Insets3f(0f, 0f, 0f, 0f));
            b.setBackground(new QuadBackgroundComponent(bg, 6f, 6f, 0.02f, false));
            b.setUserData("uiHitX", x);
            b.setUserData("uiHitY", topY - h);
            b.setUserData("uiHitW", w);
            b.setUserData("uiHitH", h);

            // Клики в проекте пока обрабатывает старый InputManager-код
            // через hitbox. Если оставить стандартный Lemur mouse-control,
            // он может consume-ить событие мыши до ActionListener, и кнопки
            // выглядят живыми, но старый код не получает клик. Поэтому у
            // совместимой кнопки отключаем только Lemur-поглощение мыши.
            disableLemurMouseConsume(b);
            return b;
        }


        static void disableLemurMouseConsume(Spatial spatial) {
            if (spatial == null) return;
            for (int i = spatial.getNumControls() - 1; i >= 0; i--) {
                com.jme3.scene.control.Control c = spatial.getControl(i);
                if (c == null) continue;
                String cn = c.getClass().getName();
                if (cn.contains("MouseEventControl") || cn.contains("CursorEventControl") || cn.contains("TouchEventControl")) {
                    spatial.removeControl(c);
                }
            }
        }

        static Label label(String text, float fontSize, ColorRGBA color) {
            Label l;
            try { l = new Label(text, BaseStyles.GLASS); }
            catch (RuntimeException ex) { l = new Label(text); }
            l.setFontSize(fontSize);
            l.setColor(safeColor(color, TEXT));
            l.setBackground(new QuadBackgroundComponent(new ColorRGBA(0f, 0f, 0f, 0f)));
            return l;
        }

        static Container panel(float x, float topY, float w, float h, float z, ColorRGBA bg) {
            Container c;
            try { c = new Container(BaseStyles.GLASS); }
            catch (RuntimeException ex) { c = new Container(); }
            c.setPreferredSize(new Vector3f(w, h, 0f));
            c.setLocalTranslation(x, topY, z);
            c.setBackground(new QuadBackgroundComponent(safeColor(bg, BG_CARD), 8f, 8f, 0.02f, false));
            return c;
        }

        static void playPageFade(Node guiNode, AssetManager am, Camera cam) {
            if (guiNode == null || am == null || cam == null) return;
            final float W = cam.getWidth();
            final float H = cam.getHeight();
            Geometry fade = new Geometry("UiTransitionFade", new com.jme3.scene.shape.Quad(W, H));
            Material fm = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            ColorRGBA base = new ColorRGBA(0f, 0f, 0f, 0.36f);
            fm.setColor("Color", base);
            fm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            fade.setMaterial(fm);
            fade.setQueueBucket(RenderQueue.Bucket.Gui);
            fade.setLocalTranslation(0f, 0f, 999f);
            fade.addControl(new com.jme3.scene.control.AbstractControl() {
                float time = 0f;
                final float duration = 0.22f;
                @Override protected void controlUpdate(float tpf) {
                    time += tpf;
                    float a = Math.max(0f, 0.36f * (1f - time / duration));
                    ((Geometry)spatial).getMaterial().setColor("Color", new ColorRGBA(0f, 0f, 0f, a));
                    if (time >= duration) spatial.removeFromParent();
                }
                @Override protected void controlRender(com.jme3.renderer.RenderManager rm, ViewPort vp) {}
            });
            guiNode.attachChild(fade);
        }

        static void playPanelPop(Spatial spatial) {
            if (spatial == null) return;
            spatial.setLocalScale(0.965f);
            spatial.addControl(new com.jme3.scene.control.AbstractControl() {
                float time = 0f;
                final float duration = 0.16f;
                @Override protected void controlUpdate(float tpf) {
                    time += tpf;
                    float k = Math.min(1f, time / duration);
                    float eased = 1f - (1f - k) * (1f - k);
                    spatial.setLocalScale(0.965f + 0.035f * eased);
                    if (k >= 1f) spatial.removeControl(this);
                }
                @Override protected void controlRender(com.jme3.renderer.RenderManager rm, ViewPort vp) {}
            });
        }

        static void clearPage(Node guiNode) {
            if (guiNode == null) return;
            try { guiNode.detachAllChildren(); } catch (Exception e) { logSafe("LemurUi.clearPage", e); }
            UiGrid.clear(guiNode);
        }
    }


    // =========================================================================
    // UI-СЕТКА И ХИТБОКСЫ
    // -------------------------------------------------------------------------
    // Все основные элементы интерфейса проходят через эту сетку:
    // 1) координаты и размеры притягиваются к ближайшей точке сетки;
    // 2) у каждого зарегистрированного элемента есть прямоугольный hit box;
    // 3) если hit box пересекается с уже занятым местом, элемент сдвигается
    //    в ближайшую свободную ячейку сетки.
    // Это сделано специально, чтобы кнопки/карточки/слайдеры не разъезжались
    // и не накладывались друг на друга при разных размерах окна.
    // =========================================================================
    static class UiGrid {
        static final float CELL = 8f;
        static final float GAP  = 6f;
        static boolean ENABLED = true;
        static boolean AVOID_OVERLAPS = true;
        static boolean LOG_FIXES = false;

        static class HitBox {
            final Node parent;
            final String name;
            final int layer;
            float x, y, w, h;

            HitBox(Node parent, String name, float x, float y, float w, float h, int layer) {
                this.parent = parent;
                this.name = name == null ? "ui" : name;
                this.x = x; this.y = y; this.w = w; this.h = h;
                this.layer = layer;
            }

            float cx() { return x + w / 2f; }
            float cy() { return y + h / 2f; }
        }

        private static final Map<Node, List<HitBox>> occupied = Collections.synchronizedMap(new WeakHashMap<>());

        static float snap(float v) {
            if (!ENABLED) return v;
            return Math.round(v / CELL) * CELL;
        }

        static float snapSize(float v) {
            if (!ENABLED) return v;
            return Math.max(CELL, Math.round(v / CELL) * CELL);
        }

        static void clear(Node parent) {
            if (parent != null) occupied.remove(parent);
        }

        static void clearAll() {
            occupied.clear();
        }

        static HitBox place(Node parent, String name, float cx, float cy, float w, float h, float z) {
            return place(parent, name, cx, cy, w, h, z, null);
        }

        static HitBox place(Node parent, String name, float cx, float cy, float w, float h, float z, Camera cam) {
            int layer = Math.round(z * 10f);
            float sw = snapSize(w);
            float sh = snapSize(h);
            float sx = snap(cx) - sw / 2f;
            float sy = snap(cy) - sh / 2f;
            HitBox wanted = new HitBox(parent, name, sx, sy, sw, sh, layer);
            HitBox resolved = resolve(parent, wanted, cam);
            register(parent, resolved);
            return resolved;
        }

        static HitBox placeExact(Node parent, String name, float cx, float cy, float w, float h, float z) {
            return placeExact(parent, name, cx, cy, w, h, z, null);
        }

        static HitBox placeExact(Node parent, String name, float cx, float cy, float w, float h, float z, Camera cam) {
            int layer = Math.round(z * 10f);
            float sw = snapSize(w);
            float sh = snapSize(h);
            HitBox hb = new HitBox(parent, name, snap(cx) - sw / 2f, snap(cy) - sh / 2f, sw, sh, layer);
            clampToScreen(hb, cam);
            register(parent, hb);
            return hb;
        }

        static void register(Node parent, HitBox hb) {
            if (parent == null || hb == null) return;
            occupied.computeIfAbsent(parent, k -> new ArrayList<>()).add(hb);
        }

        static void unregister(Node parent, HitBox hb) {
            if (parent == null || hb == null) return;
            List<HitBox> list = occupied.get(parent);
            if (list != null) list.remove(hb);
        }

        static boolean overlapsAny(Node parent, HitBox box) {
            List<HitBox> list = occupied.get(parent);
            if (list == null) return false;
            for (HitBox other : list) {
                if (other == box) continue;
                // Сравниваем только элементы одного UI-слоя: фоновые затемнения и карточки
                // не будут выталкивать текст/кнопки, но кнопки между собой не налезут.
                if (other.layer != box.layer) continue;
                if (intersects(box, other, GAP)) return true;
            }
            return false;
        }

        static boolean intersects(HitBox a, HitBox b, float gap) {
            return a.x < b.x + b.w + gap &&
                   a.x + a.w + gap > b.x &&
                   a.y < b.y + b.h + gap &&
                   a.y + a.h + gap > b.y;
        }

        static HitBox resolve(Node parent, HitBox wanted, Camera cam) {
            if (!ENABLED || !AVOID_OVERLAPS || parent == null || !overlapsAny(parent, wanted)) {
                clampToScreen(wanted, cam);
                return wanted;
            }

            HitBox best = null;
            float bestScore = Float.MAX_VALUE;
            float baseCx = wanted.cx();
            float baseCy = wanted.cy();

            // Ищем ближайшую свободную точку вокруг исходной позиции.
            // Сначала маленькие сдвиги, потом дальше. Это даёт “подтягивание” к
            // нормальной позиции, а не случайное разбрасывание элементов.
            for (int r = 1; r <= 24; r++) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dy = -r; dy <= r; dy++) {
                        if (Math.abs(dx) != r && Math.abs(dy) != r) continue;
                        HitBox c = new HitBox(parent, wanted.name,
                                snap(baseCx + dx * CELL) - wanted.w / 2f,
                                snap(baseCy + dy * CELL) - wanted.h / 2f,
                                wanted.w, wanted.h, wanted.layer);
                        clampToScreen(c, cam);
                        if (overlapsAny(parent, c)) continue;
                        float score = dx * dx + dy * dy;
                        // Чуть предпочитаем сдвиг вниз/вправо: меню читается естественнее,
                        // чем если элемент прыгнет вверх поверх заголовка.
                        if (dy > 0) score += 0.25f;
                        if (dx < 0) score += 0.10f;
                        if (score < bestScore) {
                            best = c;
                            bestScore = score;
                        }
                    }
                }
                if (best != null) break;
            }

            if (best == null) best = wanted;
            if (LOG_FIXES && (Math.abs(best.x - wanted.x) > 0.1f || Math.abs(best.y - wanted.y) > 0.1f)) {
                System.out.println("[UiGrid] moved " + wanted.name + " from " + wanted.x + "," + wanted.y + " to " + best.x + "," + best.y);
            }
            return best;
        }

        static void clampToScreen(HitBox b, Camera cam) {
            if (cam == null || b == null) return;
            float W = cam.getWidth();
            float H = cam.getHeight();
            b.x = Math.max(0f, Math.min(b.x, Math.max(0f, W - b.w)));
            b.y = Math.max(0f, Math.min(b.y, Math.max(0f, H - b.h)));
            b.x = snap(b.x);
            b.y = snap(b.y);
        }

        static boolean hit(HitBox hb, float mx, float my) {
            return hb != null && mx >= hb.x && mx <= hb.x + hb.w && my >= hb.y && my <= hb.y + hb.h;
        }

        static void snapText(BitmapText text, float x, float y, float z) {
            if (text == null) return;
            text.setLocalTranslation(snap(x), snap(y), z);
        }

        static HitBox placeText(Node parent, String name, BitmapText text, float x, float baselineY, float z) {
            if (text == null) return null;
            float tw = Math.max(CELL, text.getLineWidth());
            float th = Math.max(CELL, text.getLineHeight());
            HitBox hb = place(parent, name == null ? "Text" : name, x + tw / 2f, baselineY - th / 2f, tw, th, z);
            text.setLocalTranslation(hb.x, hb.y + hb.h, z);
            return hb;
        }

        static HitBox centerText(Node parent, String name, BitmapText text, float cx, float baselineY, float z) {
            if (text == null) return null;
            float tw = Math.max(CELL, text.getLineWidth());
            return placeText(parent, name, text, cx - tw / 2f, baselineY, z);
        }

        static void centerText(BitmapText text, float cx, float y, float z) {
            if (text == null) return;
            text.setLocalTranslation(snap(cx - text.getLineWidth() / 2f), snap(y), z);
        }
    }

    static Material guiMat(AssetManager am, ColorRGBA c, boolean alpha) {
        if (am == null) { logSafe("GuiMat", "AssetManager is null"); return null; }
        try {
            Material m = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            m.setColor("Color", safeColor(c, TEXT));
            if (alpha) m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            m.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);
            return m;
        } catch (Exception e) {
            logSafe("GuiMat", e);
            return null;
        }
    }

    static void buildDimmedMenuBackdrop(AssetManager am, Node gui, Camera cam, float z) {
        if (missingRefs("buildDimmedMenuBackdrop", am, gui, cam)) return;
        float W = cam.getWidth(), H = cam.getHeight();

        // ВАЖНО: раньше здесь рисовались 3 копии одного background.png со сдвигом.
        // На страницах серверов/лобби/карт это выглядело как “дублирование” фона.
        // Теперь фон один, без наложенных копий; затемнение и мягкость делаются
        // отдельными прозрачными слоями, а не повтором картинки.
        gui.detachChildNamed("DimMenuBackdropImage");
        gui.detachChildNamed("DimMenuBackdropSoft");
        gui.detachChildNamed("DimMenuBackdropOverlay");

        try {
            Picture bg = new Picture("DimMenuBackdropImage");
            bg.setImage(am, "Media/background.png", true);
            bg.setPosition(0f, 0f);
            bg.setWidth(W);
            bg.setHeight(H);
            bg.setQueueBucket(RenderQueue.Bucket.Gui);
            bg.setLocalTranslation(0f, 0f, z);
            gui.attachChild(bg);
        } catch (Exception e) {
            // Если картинки нет — просто останется цвет viewport + затемнение.
        }

        Geometry soft = new Geometry("DimMenuBackdropSoft", new com.jme3.scene.shape.Quad(W, H));
        soft.setMaterial(guiMat(am, new ColorRGBA(0.02f, 0.03f, 0.08f, 0.28f), true));
        soft.setQueueBucket(RenderQueue.Bucket.Gui);
        soft.setLocalTranslation(0f, 0f, z + 0.25f);
        gui.attachChild(soft);

        Geometry dim = new Geometry("DimMenuBackdropOverlay", new com.jme3.scene.shape.Quad(W, H));
        dim.setMaterial(guiMat(am, new ColorRGBA(0f, 0f, 0f, 0.58f), true));
        dim.setQueueBucket(RenderQueue.Bucket.Gui);
        dim.setLocalTranslation(0f, 0f, z + 0.5f);
        gui.attachChild(dim);
    }

    static String getSystemUsername() {
        try {
            String name = System.getProperty("user.name");
            if (name == null || name.isBlank()) name = System.getenv("USER");
            if (name != null && !name.isBlank()) {
                String clean = name.replaceAll("[^A-Za-z\u0400-\u04FF0-9_]", "");
                if (!clean.isEmpty()) return clean.length() > 16 ? clean.substring(0, 16) : clean;
            }
        } catch (Exception ignored) {}
        return "Player" + (100 + new Random().nextInt(900));
    }

    // HSV→RGB компоненты для цветового пикера
    static float hsvR(float h, float s, float v) { return hsvComponent(h, s, v, 5f); }
    static float hsvG(float h, float s, float v) { return hsvComponent(h, s, v, 3f); }
    static float hsvB(float h, float s, float v) { return hsvComponent(h, s, v, 1f); }
    static float hsvComponent(float h, float s, float v, float n) {
        float k = (n + h * 6f) % 6f;
        return v - v * s * Math.max(0f, Math.min(Math.min(k, 4f - k), 1f));
    }
    static ColorRGBA hsvToRGBA(float h, float s, float v) {
        return new ColorRGBA(hsvR(h,s,v), hsvG(h,s,v), hsvB(h,s,v), 1f);
    }

    // =========================================================================
    // ДИНАМИЧЕСКИЕ КАРТЫ ИЗ ПАПКИ maps/
    // Файлы карт лежат рядом с SnakeApp.java: maps/GreenArena.java, maps/DesertArena.java...
    // Карта должна реализовать SnakeApp.ExternalMapDef. Если папка пуста — включается SandBox.
    // =========================================================================
    public interface ExternalMapDef {
        String id();
        String displayName();
        String previewImage();
        ColorRGBA accentColor();
        MapRuntimeSettings settings();

        // true = карта сама строит пол, стены, препятствия и свою мини-игру.
        // false = SnakeApp строит стандартную арену как раньше.
        default boolean overridesArena() { return false; }

        // true = карта сама управляет камерой. Например, режим 2D сверху.
        default boolean overridesCamera() { return false; }

				// Выключение или включение цикла дня и ночи
				default boolean overridesDayNight() { return false; }

        // Вызывается вместо buildArena(), если overridesArena() == true.
        default void buildWorld(MapContext ctx) {}

        // Вызывается после создания змейки, еды, HUD и сети.
        default void onStart(MapContext ctx) {}

        // Каждый кадр на всех машинах: хост, клиент, соло.
        default void update(MapContext ctx, float tpf) {}

        // Авторитетная логика карты. Для мультиплеера лучше держать ловушки,
        // урон, победу, таймеры и спавн именно здесь.
        default void hostUpdate(MapContext ctx, float tpf) {}

        // Клиентская визуальная логика карты.
        default void clientUpdate(MapContext ctx, float tpf) {}

        // Камера карты, если overridesCamera() == true.
        default void updateCamera(MapContext ctx, float tpf) {}

        // Универсальные сетевые события карты.
        default void onMapNetMessage(MapContext ctx, String payload) {}

        // Очистка узлов/эффектов карты при выходе из матча.
        default void cleanup(MapContext ctx) {}
    }

    public static class MapContext {
        public final GameState game;
        public final SimpleApplication app;
        public final Node rootNode;
        public final Node guiNode;
        public final AssetManager assetManager;
        public final InputManager inputManager;
        public final Camera camera;
        public final PhysicsSpace physicsSpace;
        public final MapRuntimeSettings settings;
        public final String mapId;
        public final boolean solo;
        public final boolean host;
        public final int myIndex;
        public final List<String> players;
        private final List<RigidBodyControl> createdRigidBodies = new CopyOnWriteArrayList<>();

        public MapContext(GameState game,
                          SimpleApplication app,
                          Node rootNode,
                          Node guiNode,
                          AssetManager assetManager,
                          InputManager inputManager,
                          Camera camera,
                          PhysicsSpace physicsSpace,
                          MapRuntimeSettings settings,
                          String mapId,
                          boolean solo,
                          boolean host,
                          int myIndex,
                          List<String> players) {
            this.game = game;
            this.app = app;
            this.rootNode = rootNode;
            this.guiNode = guiNode;
            this.assetManager = assetManager;
            this.inputManager = inputManager;
            this.camera = camera;
            this.physicsSpace = physicsSpace;
            this.settings = settings == null ? new MapRuntimeSettings() : settings;
            this.mapId = safeString(mapId, "unknown");
            this.solo = solo;
            this.host = host;
            this.myIndex = myIndex;
            this.players = players != null ? players : Collections.emptyList();
        }

        public Material lit(ColorRGBA color) {
            return SnakeApp.litMat(assetManager, color);
        }

				public ColorRGBA getPlayerColor(int index) {
						return game != null ? game.getPlayerColor(index) : new ColorRGBA(0.15f, 0.9f, 0.3f, 1f);
				}

        public Material unshaded(ColorRGBA color) {
            return SnakeApp.unshaded(assetManager, color);
        }

				public void setMusic(String track, float volume) {
						SnakeApp.MusicManager.play(assetManager, rootNode, track, volume);
				}

        public Geometry addStaticBox(Node parent, String name, Vector3f pos, Vector3f half, ColorRGBA color) {
            Node target = parent != null ? parent : rootNode;
            if (target == null || half == null || pos == null) return null;

            Geometry g = new Geometry(safeString(name, "MapBox"), new Box(half.x, half.y, half.z));
            g.setMaterial(lit(color));
            g.setLocalTranslation(pos);
            g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            target.attachChild(g);

            if (physicsSpace != null) {
                RigidBodyControl phy = new RigidBodyControl(new BoxCollisionShape(half), 0);
                g.addControl(phy);
                physicsSpace.add(phy);
                createdRigidBodies.add(phy);
            }
            return g;
        }

        public Geometry addVisualBox(Node parent, String name, Vector3f pos, Vector3f half, ColorRGBA color) {
            Node target = parent != null ? parent : rootNode;
            if (target == null || half == null || pos == null) return null;
            Geometry g = new Geometry(safeString(name, "MapVisualBox"), new Box(half.x, half.y, half.z));
            g.setMaterial(lit(color));
            g.setLocalTranslation(pos);
            g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            target.attachChild(g);
            return g;
        }

				public float getDayBrightness() {
						return game != null ? game.getDayBrightness() : 1f;
				}

        public Geometry addStaticSphere(Node parent, String name, Vector3f pos, float radius, ColorRGBA color) {
            Node target = parent != null ? parent : rootNode;
            if (target == null || pos == null) return null;
            float r = Math.max(0.05f, radius);
            Geometry g = new Geometry(safeString(name, "MapSphere"), new Sphere(16, 16, r));
            g.setMaterial(lit(color));
            g.setLocalTranslation(pos);
            g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            target.attachChild(g);
            if (physicsSpace != null) {
                RigidBodyControl phy = new RigidBodyControl(new SphereCollisionShape(r), 0);
                g.addControl(phy);
                physicsSpace.add(phy);
                createdRigidBodies.add(phy);
            }
            return g;
        }

        public Vector3f getSnakeHead(int index) {
            return game != null ? game.getSnakeHeadPos(index) : Vector3f.ZERO.clone();
        }

        public Vector3f getMySnakeHead() {
            return getSnakeHead(myIndex);
        }

        public Vector3f getSnakeDirection(int index) {
            return game != null ? game.getSnakeDirection(index) : Vector3f.UNIT_Z.clone();
        }

        public void sendMapEvent(String payload) {
            if (game != null) game.sendMapEvent(payload);
        }

        public void killSnake(int index, String reason) {
            if (game != null) game.killSnakeFromMap(index, reason);
        }

        public float mapHalf() {
            return Math.max(25f, settings.mapHalf);
        }

        public boolean isHostLogic() {
            return solo || host;
        }

        public void setStandardSnakesVisible(boolean visible) {
            if (game != null) game.setStandardSnakesVisible(visible);
        }

        public void setCoreHudVisible(boolean visible) {
            if (game != null) game.setCoreHudVisible(visible);
        }

        void cleanupCreatedPhysics() {
            if (physicsSpace == null) {
                createdRigidBodies.clear();
                return;
            }
            for (RigidBodyControl body : snapshot(createdRigidBodies)) {
                try { physicsSpace.remove(body); } catch (Exception e) { logSafe("MapContext.physics.remove", e); }
            }
            createdRigidBodies.clear();
        }
    }

    public static class MapRuntimeSettings {
        public float mapHalf = 50f;
        public float snakeSpeed = 8f;
        public float turnSpeed = 2.8f;
        public int maxFood = 18;
        public boolean allowGrowth = true;
        public boolean enableRegularFood = true;
        public boolean enableBadFood = true;
        public boolean enableBallRain = true;
        public boolean enableRain = true;
        public boolean enableFrozenArena = true;
        public boolean enableSandstorm = false;
        public boolean enableBlackCubesDefault = true;
        public boolean forceSnakeColor = false;
        public ColorRGBA forcedSnakeColor = null;
        public float cameraDistance = 12f;
        public float cameraHeight = 6f;
        public float cameraLookAhead = 4f;
        public String mode = "classic";

        public MapRuntimeSettings copy() {
            MapRuntimeSettings m = new MapRuntimeSettings();
            m.mapHalf = mapHalf;
            m.snakeSpeed = snakeSpeed;
            m.turnSpeed = turnSpeed;
            m.maxFood = maxFood;
            m.allowGrowth = allowGrowth;
            m.enableRegularFood = enableRegularFood;
            m.enableBadFood = enableBadFood;
            m.enableBallRain = enableBallRain;
            m.enableRain = enableRain;
            m.enableFrozenArena = enableFrozenArena;
            m.enableSandstorm = enableSandstorm;
            m.enableBlackCubesDefault = enableBlackCubesDefault;
            m.forceSnakeColor = forceSnakeColor;
            m.forcedSnakeColor = forcedSnakeColor == null ? null : forcedSnakeColor.clone();
            m.cameraDistance = cameraDistance;
            m.cameraHeight = cameraHeight;
            m.cameraLookAhead = cameraLookAhead;
            m.mode = mode;
            return m;
        }
    }

    static class LoadedMapInfo {
        final String id;
        final String displayName;
        final String previewImage;
        final ColorRGBA accentColor;
        final MapRuntimeSettings settings;
        final boolean external;
        final ExternalMapDef def;

        LoadedMapInfo(ExternalMapDef def, boolean external) {
            this(
                    def == null ? "sandbox" : def.id(),
                    def == null ? "SandBox" : def.displayName(),
                    def == null ? "" : def.previewImage(),
                    def == null ? ACCENT : def.accentColor(),
                    def == null ? new MapRuntimeSettings() : def.settings(),
                    external,
                    def
            );
        }

        LoadedMapInfo(String id, String displayName, String previewImage, ColorRGBA accentColor, MapRuntimeSettings settings, boolean external) {
            this(id, displayName, previewImage, accentColor, settings, external, null);
        }

        private LoadedMapInfo(String id, String displayName, String previewImage,
                              ColorRGBA accentColor, MapRuntimeSettings settings,
                              boolean external, ExternalMapDef def) {
            this.id = (id == null || id.isBlank()) ? "sandbox" : id;
            this.displayName = (displayName == null || displayName.isBlank()) ? this.id : displayName;
            this.previewImage = previewImage == null ? "" : previewImage;
            this.accentColor = accentColor == null ? ACCENT : accentColor;
            this.settings = settings == null ? new MapRuntimeSettings() : settings.copy();
            this.external = external;
            this.def = def;
        }
    }

    static class MapRegistry {
        static final String MAP_DIR = "maps";

        static List<LoadedMapInfo> loadMaps() {
            List<LoadedMapInfo> result = new ArrayList<>();
            java.io.File dir = new java.io.File(MAP_DIR);
            if (!dir.exists()) dir.mkdirs();
            compileMapSources(dir);

            java.io.File[] classes = dir.listFiles((d, n) -> n.endsWith(".class") && !n.contains("$"));
            if (classes != null) {
                Arrays.sort(classes, Comparator.comparing(java.io.File::getName));
                try {
                    java.net.URLClassLoader loader = new java.net.URLClassLoader(
                            new java.net.URL[]{dir.toURI().toURL()}, SnakeApp.class.getClassLoader());
                    for (java.io.File f : classes) {
                        String clsName = f.getName().substring(0, f.getName().length() - 6);
                        try {
                            Class<?> cls = Class.forName(clsName, true, loader);
                            if (!ExternalMapDef.class.isAssignableFrom(cls)) continue;
                            ExternalMapDef def = (ExternalMapDef)cls.getDeclaredConstructor().newInstance();
                            result.add(new LoadedMapInfo(def, true));
                        } catch (Throwable t) {
                            System.out.println("[Maps] skip " + clsName + ": " + t.getMessage());
                        }
                    }
                } catch (Exception e) {
                    System.out.println("[Maps] load error: " + e.getMessage());
                }
            }

            if (!result.isEmpty()) {
                final List<String> preferredOrder = Arrays.asList("GreenArena", "DesertArena", "SpikePits", "Classic2DSnake", "TurboRace");
                result.sort(Comparator
                        .comparingInt((LoadedMapInfo m) -> {
                            int idx = preferredOrder.indexOf(m.id);
                            return idx >= 0 ? idx : 1000;
                        })
                        .thenComparing(m -> m.id));
            }

            if (result.isEmpty()) {
                MapRuntimeSettings sandbox = new MapRuntimeSettings();
                sandbox.mapHalf = 45f;
                sandbox.snakeSpeed = 8f;
                sandbox.turnSpeed = 2.8f;
                sandbox.maxFood = 18;
                sandbox.enableBallRain = true;
                sandbox.enableRain = true;
                sandbox.enableFrozenArena = true;
                sandbox.mode = "sandbox";
                result.add(new LoadedMapInfo("SandBox", "SandBox", "", new ColorRGBA(0.62f,0.62f,0.62f,1f), sandbox, false));
            }
            return result;
        }

        private static void compileMapSources(java.io.File dir) {
            java.io.File[] sources = dir.listFiles((d, n) -> n.endsWith(".java"));
            if (sources == null || sources.length == 0) return;
            javax.tools.JavaCompiler compiler = javax.tools.ToolProvider.getSystemJavaCompiler();
            if (compiler == null) {
                System.out.println("[Maps] JavaCompiler недоступен. Скомпилируй maps/*.java вручную.");
                return;
            }
            String cp = System.getProperty("java.class.path") + java.io.File.pathSeparator + ".";
            List<String> args = new ArrayList<>();
            args.add("-cp"); args.add(cp);
            args.add("-d");  args.add(dir.getPath());
            for (java.io.File src : sources) {
                java.io.File cls = new java.io.File(dir, src.getName().replace(".java", ".class"));
                if (!cls.exists() || cls.lastModified() < src.lastModified()) args.add(src.getPath());
            }
            if (args.size() <= 4) return;
            int code = compiler.run(null, null, null, args.toArray(new String[0]));
            if (code != 0) System.out.println("[Maps] javac maps/*.java вернул код " + code);
        }
    }

    static List<LoadedMapInfo> loadedMaps = MapRegistry.loadMaps();

    static int getLoadedMapCount() {
        if (loadedMaps == null || loadedMaps.isEmpty()) loadedMaps = MapRegistry.loadMaps();
        return Math.max(1, loadedMaps.size());
    }

    static LoadedMapInfo getMapInfo(int index) {
        if (loadedMaps == null || loadedMaps.isEmpty()) loadedMaps = MapRegistry.loadMaps();
        if (loadedMaps == null || loadedMaps.isEmpty()) return new LoadedMapInfo("SandBox", "SandBox", "", TEXT_DIM, new MapRuntimeSettings(), false);
        int i = Math.max(0, Math.min(index, loadedMaps.size() - 1));
        LoadedMapInfo info = loadedMaps.get(i);
        return info != null ? info : new LoadedMapInfo("SandBox", "SandBox", "", TEXT_DIM, new MapRuntimeSettings(), false);
    }

    static Optional<LoadedMapInfo> getMapInfoOptional(int index) {
        try { return Optional.ofNullable(getMapInfo(index)); }
        catch (Exception e) { logSafe("getMapInfoOptional", e); return Optional.empty(); }
    }

    static int clampMapIndex(int index) {
        return Math.max(0, Math.min(index, getLoadedMapCount() - 1));
    }

    static Mesh createSlantedQuad(float w, float h, float tilt) {
        Mesh mesh = new Mesh();
        float hw = w / 2f;
        float hh = h / 2f;
        Vector3f[] vertices = new Vector3f[] {
                new Vector3f(-hw + tilt,  hh, 0),
                new Vector3f( hw + tilt,  hh, 0),
                new Vector3f(-hw,        -hh, 0),
                new Vector3f( hw,        -hh, 0)
        };
        int[] indices = new int[] { 0, 2, 1, 1, 2, 3 };
        float[] texCoord = new float[] { 0, 1, 1, 1, 0, 0, 1, 0 };
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3,
                com.jme3.util.BufferUtils.createFloatBuffer(vertices));
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 3,
                com.jme3.util.BufferUtils.createIntBuffer(indices));
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.TexCoord, 2,
                com.jme3.util.BufferUtils.createFloatBuffer(texCoord));
        mesh.updateBound();
        return mesh;
    }

    static Mesh createCyberButtonMesh(float w, float h, float cut) {
        Mesh mesh = new Mesh();
        float hw = w / 2f;
        float hh = h / 2f;
        cut = FastMath.clamp(cut, 4f, Math.min(hw * 0.45f, hh * 0.82f));

        Vector3f[] vertices = new Vector3f[] {
                new Vector3f(0f, 0f, 0f),
                new Vector3f(-hw + cut,  hh,       0f),
                new Vector3f( hw - cut,  hh,       0f),
                new Vector3f( hw,        hh - cut, 0f),
                new Vector3f( hw,       -hh + cut, 0f),
                new Vector3f( hw - cut, -hh,       0f),
                new Vector3f(-hw + cut, -hh,       0f),
                new Vector3f(-hw,       -hh + cut, 0f),
                new Vector3f(-hw,        hh - cut, 0f)
        };
        int[] indices = new int[8 * 3];
        for (int i = 0; i < 8; i++) {
            indices[i * 3] = 0;
            indices[i * 3 + 1] = i + 1;
            indices[i * 3 + 2] = (i == 7) ? 1 : i + 2;
        }
        float[] texCoord = new float[vertices.length * 2];
        for (int i = 0; i < vertices.length; i++) {
            texCoord[i * 2] = (vertices[i].x + hw) / w;
            texCoord[i * 2 + 1] = (vertices[i].y + hh) / h;
        }
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3,
                com.jme3.util.BufferUtils.createFloatBuffer(vertices));
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 3,
                com.jme3.util.BufferUtils.createIntBuffer(indices));
        mesh.setBuffer(com.jme3.scene.VertexBuffer.Type.TexCoord, 2,
                com.jme3.util.BufferUtils.createFloatBuffer(texCoord));
        mesh.updateBound();
        return mesh;
    }

    // ---------- кнопка Lemur с совместимым API старого MenuButton ----------
    static class MenuButton {
        private final Button button;
        private final Geometry shapeGeo, edgeGeo;
        // Эти Spatial-поля оставлены специально: в настройках уже есть код, который
        // скрывает/показывает bgGeo/accentGeo/borderGeo/label. Теперь bg/border —
        // кастомная cyber-форма, а label — Lemur Button с прозрачным фоном.
        private final Spatial bgGeo, accentGeo, borderGeo, label;
        private final float x, y, w, h;
        private final Node ownerNode;
        private final UiGrid.HitBox hitBox;
        private boolean hovered = false, pressed = false;
        private float cornerRadius = 6f;
        private String currentText = "";
        private ColorRGBA accentColor;
        private ColorRGBA bgNormal, bgHover, bgPressed;

        MenuButton(String text, float cx, float cy, float w, float h,
                   ColorRGBA bgNormal, ColorRGBA bgHover, ColorRGBA bgPressed,
                   ColorRGBA textColor, AssetManager am, Node guiNode, float z) {
            this.ownerNode = guiNode != null ? guiNode : new Node("DetachedMenuButtonOwner");
            // Для кнопок используем точный hitbox. Авто-разруливание пересечений
            // оставлено для декоративных/карточных элементов, но кнопки не должны
            // прыгать из-за скрытых элементов другой вкладки.
            this.hitBox = UiGrid.placeExact(this.ownerNode, "LemurButton:" + safeString(text, "Button"), cx, cy, w, h, z);
            this.w = hitBox.w; this.h = hitBox.h;
            this.x = hitBox.x; this.y = hitBox.y;
            this.currentText = safeString(text, "");
            this.accentColor = safeColor(textColor, TEXT);
            this.bgNormal = safeColor(bgNormal, BTN_NORMAL);
            this.bgHover = safeColor(bgHover, BTN_HOVER);
            this.bgPressed = safeColor(bgPressed, BTN_PRESS);

            float cut = Math.min(hitBox.h * 0.34f, 24f);

            this.edgeGeo = new Geometry("BtnCyberEdge_" + safeString(text, "Button"), createCyberButtonMesh(hitBox.w + 6f, hitBox.h + 6f, cut + 3f));
            this.edgeGeo.setMaterial(guiMat(am, new ColorRGBA(this.accentColor.r * 0.40f, this.accentColor.g * 0.40f, this.accentColor.b * 0.40f, 0.72f), true));
            this.edgeGeo.setQueueBucket(RenderQueue.Bucket.Gui);
            this.edgeGeo.setLocalTranslation(hitBox.x + hitBox.w / 2f, hitBox.y + hitBox.h / 2f, z - 0.12f);
            if (guiNode != null) guiNode.attachChild(edgeGeo);

            this.shapeGeo = new Geometry("BtnCyberBg_" + safeString(text, "Button"), createCyberButtonMesh(hitBox.w, hitBox.h, cut));
            this.shapeGeo.setMaterial(guiMat(am, this.bgNormal, true));
            this.shapeGeo.setQueueBucket(RenderQueue.Bucket.Gui);
            this.shapeGeo.setLocalTranslation(hitBox.x + hitBox.w / 2f, hitBox.y + hitBox.h / 2f, z - 0.05f);
            if (guiNode != null) guiNode.attachChild(shapeGeo);

            // Lemur использует верхнюю левую точку панели, поэтому y + h = topY.
            // Фон самой Lemur-кнопки делаем прозрачным: реальная форма кнопки —
            // кастомный mesh со скошенными углами. Текст/выравнивание остаются Lemur.
            this.button = LemurUi.button(currentText, hitBox.x, hitBox.y + hitBox.h, hitBox.w, hitBox.h, z, new ColorRGBA(0f,0f,0f,0f), this.accentColor, am);
            this.button.setName("Btn_" + safeString(text, "Button"));
            this.button.setBackground(new QuadBackgroundComponent(new ColorRGBA(0f, 0f, 0f, 0f), 0f, 0f, 0.02f, false));
            fitTextToButton();
            if (guiNode != null) guiNode.attachChild(button);

            this.bgGeo = shapeGeo;
            this.accentGeo = edgeGeo;
            this.borderGeo = edgeGeo;
            this.label = button;
            refreshColor();
        }

        void setBgNormal(ColorRGBA c) {
            this.bgNormal = c;
            refreshColor();
        }

        void updateHover(float mx, float my) { hovered = isHit(mx, my); refreshColor(); }
        void onPress(float mx, float my)    { pressed = isHit(mx, my); refreshColor(); }
        boolean onRelease(float mx, float my) {
            boolean hit = pressed && isHit(mx, my);
            pressed = false;
            refreshColor();
            if (hit) {
                try { button.click(); } catch (Exception ignored) {}
            }
            return hit;
        }
        public boolean isHit(float mx, float my) {
            // Только один точный hitbox. Старый fallback проверял две зоны
            // вокруг Lemur-кнопки и из-за этого при наведении могли подсветиться
            // сразу две соседние кнопки.
            return UiGrid.hit(hitBox, mx, my);
        }

        private void refreshColor() {
            ColorRGBA bg = safeColor(pressed ? bgPressed : (hovered ? bgHover : bgNormal), BTN_NORMAL);
            ColorRGBA ac = safeColor(accentColor, TEXT);
            ColorRGBA text = hovered ? new ColorRGBA(
                    Math.min(1f, ac.r * 1.20f),
                    Math.min(1f, ac.g * 1.20f),
                    Math.min(1f, ac.b * 1.20f), 1f) : ac;
            if (button != null) button.setColor(text);
            if (button != null) button.setHighlightColor(text);
            if (button != null) button.setFocusColor(text);
            if (button != null) button.setBackground(new QuadBackgroundComponent(new ColorRGBA(0f, 0f, 0f, 0f), 0f, 0f, 0.02f, false));
            if (shapeGeo != null && shapeGeo.getMaterial() != null) shapeGeo.getMaterial().setColor("Color", bg);
            float edgeBoost = hovered || pressed ? 1.0f : 0.55f;
            if (edgeGeo != null && edgeGeo.getMaterial() != null) edgeGeo.getMaterial().setColor("Color", new ColorRGBA(
                    Math.min(1f, ac.r * edgeBoost),
                    Math.min(1f, ac.g * edgeBoost),
                    Math.min(1f, ac.b * edgeBoost),
                    hovered || pressed ? 0.95f : 0.62f));
        }

        void setCornerRadius(float r) {
            // После перехода на cyber-форму это значение управляет глубиной среза,
            // а не радиусом округления. Старые вызовы из главного меню продолжают
            // работать, но меняют именно форму mesh-кнопки.
            cornerRadius = Math.max(4f, r);
            float cut = Math.min(hitBox.h * 0.45f, Math.max(8f, cornerRadius));
            shapeGeo.setMesh(createCyberButtonMesh(hitBox.w, hitBox.h, cut));
            edgeGeo.setMesh(createCyberButtonMesh(hitBox.w + 6f, hitBox.h + 6f, cut + 3f));
            refreshColor();
        }

        void detach(Node guiNode) {
            UiGrid.unregister(ownerNode, hitBox);
            removeFromParentQuietly(shapeGeo);
            removeFromParentQuietly(edgeGeo);
            removeFromParentQuietly(button);
        }

        private void fitTextToButton() {
            if (button == null) return;
            String t = safeString(currentText, "");

            // Автоуменьшение текста по ширине кнопки: длинные варианты вроде
            // «ТЕНИ СРЕДНИЕ» и «ДЕТАЛИ КАРТ ВКЛ» больше не вылезают за края.
            float maxSize = Math.max(10f, h * 0.43f);
            float minSize = Math.max(8f, h * 0.27f);
            float usableW = Math.max(24f, w - 18f);
            float approxCharW = Math.max(0.52f, t.length() > 12 ? 0.50f : 0.56f);
            float sizeByWidth = usableW / Math.max(1f, t.length() * approxCharW);
            float fontSize = FastMath.clamp(Math.min(maxSize, sizeByWidth), minSize, maxSize);

            button.setFontSize(fontSize);
            button.setInsets(new com.simsilica.lemur.Insets3f(0f, 6f, 0f, 6f));
            button.setTextHAlignment(com.simsilica.lemur.HAlignment.Center);
            button.setTextVAlignment(com.simsilica.lemur.VAlignment.Center);
        }

        void setText(String t) {
            currentText = safeString(t, "");
            if (button != null) {
                button.setText(currentText);
                fitTextToButton();
            }
        }
        void nudgeText(float dx, float dy) {
            // Раньше двигался только BitmapText внутри старой кнопки.
            // У Lemur Button отдельный label скрыт внутри компонента, поэтому
            // нельзя двигать весь Button: из-за этого видимая кнопка уезжала
            // от своего hitbox и переставала нажиматься. Оставляем no-op.
        }
        void setAccentColor(ColorRGBA c) { accentColor = c; refreshColor(); }
    }

    static void updateShadowQualityButton(MenuButton b) {
        if (b == null) return;
        shadowQuality = clampShadowQuality(shadowQuality);
        shadowsEnabled = shadowQuality != SHADOW_QUALITY_OFF;
        b.setText(shadowQualityButtonText());
        b.setAccentColor(shadowQualityAccentColor());
        b.setBgNormal(shadowQualityBgColor());
    }

    // ---------- ползунок громкости: кастомный, без кривого drag у Lemur Slider ----------
    static class VolumeSlider {
        private static final float TRACK_H = 12f;
        private static final float THUMB_W = 20f;
        private static final float THUMB_H = 34f;
        private final Geometry track, fill, thumb, glow;
        private final float left, cy, trackW, z;
        private final Node ownerNode;
        private final UiGrid.HitBox hitBox;
        private float value;
        private boolean hovered = false;
        private boolean dragging = false;
        private final ColorRGBA accent;

        VolumeSlider(float cx, float cy, float w, float initVal, AssetManager am, Node parent, float z) {
            this.ownerNode = parent;
            this.hitBox = UiGrid.placeExact(parent, "VolumeSlider", cx, cy, w, THUMB_H + 12f, z);
            this.trackW = hitBox.w;
            this.left = hitBox.x;
            this.cy = hitBox.cy();
            this.z = z;
            this.value = Math.max(0f, Math.min(1f, initVal));
            this.accent = ACCENT2;

            glow = new Geometry("VSliderGlow", new Box(trackW/2f + 5f, TRACK_H/2f + 5f, 0.2f));
            glow.setMaterial(guiMat(am, new ColorRGBA(0.04f, 0.13f, 0.28f, 0.45f), true));
            glow.setLocalTranslation(left + trackW/2f, this.cy, z - 0.15f);
            parent.attachChild(glow);

            track = new Geometry("VSliderTrack", new Box(trackW/2f, TRACK_H/2f, 0.25f));
            track.setMaterial(guiMat(am, new ColorRGBA(0.035f, 0.055f, 0.12f, 0.96f), true));
            track.setLocalTranslation(left + trackW/2f, this.cy, z);
            parent.attachChild(track);

            fill = new Geometry("VSliderFill", new Box(0.5f, TRACK_H/2f - 1f, 0.32f));
            fill.setMaterial(guiMat(am, accent, true));
            parent.attachChild(fill);

            thumb = new Geometry("VSliderThumb", new Box(THUMB_W/2f, THUMB_H/2f, 0.45f));
            thumb.setMaterial(guiMat(am, new ColorRGBA(0.90f, 0.96f, 1f, 1f), false));
            parent.attachChild(thumb);
            refreshVisuals();
        }

        void updateHover(float mx, float my) {
            hovered = UiGrid.hit(hitBox, mx, my);
            if (!dragging) {
                thumb.getMaterial().setColor("Color", hovered ? ACCENT3 : new ColorRGBA(0.90f, 0.96f, 1f, 1f));
            }
        }

        boolean onPress(float mx, float my) {
            if (!UiGrid.hit(hitBox, mx, my)) return false;
            dragging = true;
            setFromMouse(mx);
            thumb.getMaterial().setColor("Color", ACCENT3);
            return true;
        }

        boolean onDrag(float mx, float my) {
            if (!dragging) return false;
            setFromMouse(mx);
            return true;
        }

        boolean onRelease(float mx, float my) {
            if (!dragging) return false;
            setFromMouse(mx);
            dragging = false;
            thumb.getMaterial().setColor("Color", hovered ? ACCENT3 : new ColorRGBA(0.90f, 0.96f, 1f, 1f));
            return true;
        }

        boolean onClick(float mx, float my) { return onPress(mx, my); }
        boolean isDragging() { return dragging; }

        private void setFromMouse(float mx) {
            value = Math.max(0f, Math.min(1f, (mx - left) / trackW));
            refreshVisuals();
        }

        private void refreshVisuals() {
            float fillW = Math.max(1f, value * trackW);
            fill.setLocalScale(fillW, 1f, 1f);
            fill.setLocalTranslation(left + fillW/2f, cy, z + 0.20f);
            thumb.setLocalTranslation(left + value * trackW, cy, z + 0.45f);
        }

        void setValue(float v) { value = Math.max(0f, Math.min(1f, v)); refreshVisuals(); }
        float getValue() { return value; }
        void setVisible(boolean visible) {
            Spatial.CullHint hint = visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            track.setCullHint(hint);
            fill.setCullHint(hint);
            thumb.setCullHint(hint);
            glow.setCullHint(hint);
        }
    }

		// =========================================================================
		// SPLASH SCREEN ПРИ СТАРТЕ ИГРЫ
		// -------------------------------------------------------------------------
		// Показывает Media/Splash/Sonny_lab_num1.png,
		// затем Media/Splash/jMonkeyEngine_num2.png.
		// Любая клавиша или кнопка мыши пропускает Splash.
		// =========================================================================
		static class SplashState extends AbstractAppState {
				private SimpleApplication app;
				private Node guiNode;
				private AssetManager assetManager;
				private InputManager inputManager;
				private Camera cam;

				private final String[] splashImages = {
								"Media/Splash/Sonny_lab_num1.png",
								"Media/Splash/jMonkeyEngine_num2.png"
				};

				private Geometry bg;
				private Geometry imageGeo;
				private Material imageMat;

				private int imageIndex = 0;
				private float timer = 0f;
				private boolean finished = false;

				// fade in -> hold -> fade out
				private static final float FADE_IN_TIME = 0.85f;
				private static final float HOLD_TIME    = 0.35f;
				private static final float FADE_OUT_TIME = 0.85f;

				private static final String SPLASH_SKIP = "SplashSkip";

				@Override
				public void initialize(AppStateManager sm, Application application) {
						super.initialize(sm, application);

						if (invalidApplication(application, "Splash.initialize")) {
								finishSplash();
								return;
						}

						app = (SimpleApplication) application;
						guiNode = app.getGuiNode();
						assetManager = app.getAssetManager();
						inputManager = app.getInputManager();
						cam = app.getCamera();

						if (missingRefs("Splash.initialize", guiNode, assetManager, inputManager, cam)) {
								finishSplash();
								return;
						}

						try {
								if (app.getViewPort() != null) {
										app.getViewPort().setBackgroundColor(ColorRGBA.Black);
								}

								if (app.getFlyByCamera() != null) {
										app.getFlyByCamera().setEnabled(false);
								}

								inputManager.setCursorVisible(false);

								buildSplashBackground();
								loadSplashImage(0);
								setupSkipInput();

						} catch (Exception e) {
								logSafe("Splash.initialize", e);
								finishSplash();
						}
				}

				private void buildSplashBackground() {
						float W = cam.getWidth();
						float H = cam.getHeight();

						bg = new Geometry("SplashBlackBg", new com.jme3.scene.shape.Quad(W, H));
						Material bgMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						bgMat.setColor("Color", ColorRGBA.Black);
						bg.setMaterial(bgMat);
						bg.setQueueBucket(RenderQueue.Bucket.Gui);
						bg.setLocalTranslation(0f, 0f, 1000f);
						guiNode.attachChild(bg);
				}

				private void loadSplashImage(int idx) {
						if (idx < 0 || idx >= splashImages.length) {
								finishSplash();
								return;
						}

						imageIndex = idx;
						timer = 0f;

						if (imageGeo != null) {
								imageGeo.removeFromParent();
								imageGeo = null;
						}

						try {
								String path = splashImages[idx];

								com.jme3.texture.Texture tex = assetManager.loadTexture(path);

								float screenW = cam.getWidth();
								float screenH = cam.getHeight();

								float imgW = screenW * 0.62f;
								float imgH = screenH * 0.62f;

								if (tex != null && tex.getImage() != null
												&& tex.getImage().getWidth() > 0
												&& tex.getImage().getHeight() > 0) {
										float aspect = (float) tex.getImage().getWidth() / (float) tex.getImage().getHeight();

										imgH = screenH * 2f;
										imgW = imgH * aspect;

										if (imgW > screenW * 1.0f) {
												imgW = screenW * 1.0f;
												imgH = imgW / aspect;
										}
								}

								imageGeo = new Geometry("SplashImage_" + idx, new com.jme3.scene.shape.Quad(imgW, imgH));

								imageMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
								imageMat.setTexture("ColorMap", tex);
								imageMat.setColor("Color", new ColorRGBA(1f, 1f, 1f, 0f));
								imageMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
								imageMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

								imageGeo.setMaterial(imageMat);
								imageGeo.setQueueBucket(RenderQueue.Bucket.Gui);

								float x = (screenW - imgW) / 2f;
								float y = (screenH - imgH) / 2f;

								imageGeo.setLocalTranslation(x, y, 1001f);
								guiNode.attachChild(imageGeo);

						} catch (Exception e) {
								logSafe("Splash.loadImage", splashImages[idx] + " не найден или не загружен");
								finishSplash();
						}
				}

				private void setupSkipInput() {
						if (inputManager == null) return;

						if (inputManager.hasMapping(SPLASH_SKIP)) {
								inputManager.deleteMapping(SPLASH_SKIP);
						}

						List<Trigger> triggers = new ArrayList<>();

						// Почти все клавиши клавиатуры.
						for (int key = 1; key < 256; key++) {
								triggers.add(new KeyTrigger(key));
						}

						// Кнопки мыши.
						triggers.add(new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
						triggers.add(new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
						triggers.add(new MouseButtonTrigger(MouseInput.BUTTON_MIDDLE));

						inputManager.addMapping(SPLASH_SKIP, triggers.toArray(new Trigger[0]));

						inputManager.addListener((ActionListener) (name, pressed, tpf) -> {
								if (!pressed) return;
								finishSplash();
						}, SPLASH_SKIP);
				}

				@Override
				public void update(float tpf) {
						if (finished) return;

						tpf = safeTpf(tpf);
						timer += tpf;

						float alpha;
						float phase1 = FADE_IN_TIME;
						float phase2 = FADE_IN_TIME + HOLD_TIME;
						float phase3 = FADE_IN_TIME + HOLD_TIME + FADE_OUT_TIME;

						if (timer <= phase1) {
								// 0% -> 100%
								alpha = timer / FADE_IN_TIME;
						} else if (timer <= phase2) {
								// удержание 100%
								alpha = 1f;
						} else if (timer <= phase3) {
								// 100% -> 0%
								alpha = 1f - ((timer - phase2) / FADE_OUT_TIME);
						} else {
								int next = imageIndex + 1;

								if (next >= splashImages.length) {
										finishSplash();
										return;
								}

								loadSplashImage(next);
								return;
						}

						alpha = FastMath.clamp(alpha, 0f, 1f);

						if (imageMat != null) {
								imageMat.setColor("Color", new ColorRGBA(1f, 1f, 1f, alpha));
						}
				}

				private void finishSplash() {
						if (finished) return;
						finished = true;

						try {
								if (inputManager != null && inputManager.hasMapping(SPLASH_SKIP)) {
										inputManager.deleteMapping(SPLASH_SKIP);
								}
						} catch (Exception e) {
								logSafe("Splash.skipCleanup", e);
						}

						try {
								if (imageGeo != null) imageGeo.removeFromParent();
								if (bg != null) bg.removeFromParent();
						} catch (Exception e) {
								logSafe("Splash.guiCleanup", e);
						}

						try {
								if (inputManager != null) {
										inputManager.setCursorVisible(true);
								}

								if (app != null && app.getStateManager() != null) {
										app.getStateManager().detach(this);
										app.getStateManager().attach(new MainMenuState());
								}
						} catch (Exception e) {
								logSafe("Splash.finish", e);
						}
				}

				@Override
				public void cleanup() {
						super.cleanup();

						try {
								if (inputManager != null && inputManager.hasMapping(SPLASH_SKIP)) {
										inputManager.deleteMapping(SPLASH_SKIP);
								}
						} catch (Exception e) {
								logSafe("Splash.cleanupInput", e);
						}

						removeFromParentQuietly(imageGeo);
						removeFromParentQuietly(bg);
				}
		}

    // =========================================================================
    // ГЛАВНОЕ МЕНЮ (изменено: кнопка «Сетевая игра» вместо «Найти лобби»)
    // =========================================================================
    static class MainMenuState extends AbstractAppState {
        private SimpleApplication app;
        private Node rootNode, guiNode;
        private AssetManager assetManager;
        private InputManager inputManager;
        private Camera cam;
        private Picture bgPicture;

        private BitmapText titleText, subtitleText, infoText;
        private BitmapText nicknameText, cursorBlink;
        private StringBuilder nickname = new StringBuilder(savedNickname.isEmpty() ? getSystemUsername() : savedNickname);
        private float blinkTimer = 0f;
        private boolean cursorVisible = true;

				private int lastW = -1;
				private int lastH = -1;

        private Node decorNode;
        private float[] ballAngles, ballRadii, ballSpeeds, ballY;
        private MenuButton soloBtn, joinBtn, settingsBtn;
        private MenuButton creditsBtn, patreonBtn, discordBtn;
        private boolean creditsOpen = false;
        private Node creditsPanel;
        private MenuButton creditsCloseBtn;
        private boolean settingsOpen = false;
        private Node settingsPanel;
        private BitmapText sfxVal, musicVal;
        private VolumeSlider sfxSlider, musicSlider;
        private MenuButton settingsClose;
        private AudioNode menuMusic;
        private int activeSettingsTab = 0; // 0 = Основное, 1 = Графика
        // Кнопки вкладок
        private MenuButton tabMainBtn, tabGraphicsBtn;
        // Кнопки-переключатели для графических настроек
        private MenuButton btnShadows, btnParticles, btnFog, btnBloom;
        private MenuButton btnPost, btnLights, btnWater, btnTerrain, btnLowPoly, btnNames;

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            if (invalidApplication(application, "MainMenu.initialize")) return;
            app = (SimpleApplication) application;
            rootNode = app.getRootNode(); guiNode = app.getGuiNode();
            assetManager = app.getAssetManager(); inputManager = app.getInputManager();
            cam = app.getCamera();
            if (missingRefs("MainMenu.initialize", rootNode, guiNode, assetManager, inputManager, cam)) return;
            try {
                if (app.getViewPort() != null) app.getViewPort().setBackgroundColor(BG);
                forceMenuCursor(app);
                setupBackground();
                setupDecorBalls();
                setupUI();
                setupInput();
                startMenuMusic();
            } catch (Exception e) {
                logSafe("MainMenu.initialize", e);
            }
        }

        private void setupBackground() {
            float W = cam.getWidth(), H = cam.getHeight();
            try {
                bgPicture = new Picture("MenuBG");
                bgPicture.setImage(assetManager, "Media/background.png", true);
                bgPicture.setPosition(0, 0); bgPicture.setWidth(W); bgPicture.setHeight(H);
                guiNode.attachChild(bgPicture);
            } catch (Exception e) {
                app.getViewPort().setBackgroundColor(BG);
            }
        }

        private void setupDecorBalls() {
            decorNode = new Node("Decor"); rootNode.attachChild(decorNode);
            int n = 12;
            ballAngles = new float[n]; ballRadii = new float[n];
            ballSpeeds = new float[n]; ballY = new float[n];
            ColorRGBA[] cols = { ACCENT, ACCENT2, ACCENT3, DANGER };
            for (int i=0;i<n;i++) {
                ballAngles[i] = (float)i/n*FastMath.TWO_PI;
                ballRadii[i] = 2f + FastMath.nextRandomFloat()*3f;
                ballSpeeds[i] = 0.3f + FastMath.nextRandomFloat()*0.5f;
                ballY[i] = FastMath.nextRandomFloat()*3f - 1f;
                float r = 0.08f + FastMath.nextRandomFloat()*0.12f;
                Geometry g = new Geometry("DB"+i, new Sphere(8,8,r));
                g.setMaterial(unshaded(assetManager, cols[i%cols.length]));
                decorNode.attachChild(g);
            }
        }

				private void rebuildUIAfterResize() {

						boolean wasSettingsOpen = settingsOpen;
						int oldTab = activeSettingsTab;

						LemurUi.clearPage(guiNode);

						setupBackground();
						setupUI();

						settingsOpen = wasSettingsOpen;
						activeSettingsTab = oldTab;

						if (settingsPanel != null) {
								settingsPanel.setCullHint(settingsOpen ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
								setSettingsTab(activeSettingsTab);
								updateSettingsLabels();
						}
				}

				private void setupUI() {
						BitmapFont font = loadFont(assetManager);

						float W = cam.getWidth();
						float H = cam.getHeight();
                        float S = uiScale(cam);

						// =========================================================
						// BACKGROUND OVERLAY
						// =========================================================
						Geometry darkOverlay = new Geometry(
										"DarkOverlay",
										new com.jme3.scene.shape.Quad(W, H)
						);

						Material ovMat = new Material(assetManager,
										"Common/MatDefs/Misc/Unshaded.j3md");

						ovMat.setColor("Color",
										new ColorRGBA(0f, 0f, 0f, 0.35f));

						ovMat.getAdditionalRenderState()
										.setBlendMode(RenderState.BlendMode.Alpha);

						darkOverlay.setMaterial(ovMat);
						darkOverlay.setQueueBucket(RenderQueue.Bucket.Gui);
						darkOverlay.setLocalTranslation(0, 0, -5);

						guiNode.attachChild(darkOverlay);

						// =========================================================
						// LOGO
						// =========================================================
						try {

								Picture logo = new Picture("TurboSnakeLogo");

								logo.setImage(assetManager,
												"Media/logo.png",
												true);

								float logoW = 620f * S;
								float logoH = logoW * (1024f / 1536f);

								logo.setWidth(logoW);
								logo.setHeight(logoH);

								logo.setPosition(
												40f,
												H - logoH - 40f
								);

								guiNode.attachChild(logo);

						} catch (Exception e) {
								System.out.println("Missing Media/logo.png");
						}

						// =========================================================
						// MAIN BUTTONS
						// =========================================================
						float btnW = 360f * S;
						float btnH = 78f * S;

						float startX = 250f * S;
						float startY = H / 2f + 10f * S;

						soloBtn = new MenuButton(
										"ОДИНОЧНАЯ ИГРА",
										startX,
										startY,
										btnW,
										btnH,

										new ColorRGBA(0.03f,0.07f,0.18f,0.92f),
										new ColorRGBA(0.10f,0.18f,0.40f,1f),
										new ColorRGBA(0.02f,0.04f,0.10f,1f),

										new ColorRGBA(1f,0.35f,0.85f,1f),

										assetManager,
										guiNode,
										5f
						);

						joinBtn = new MenuButton(
										"СЕТЕВАЯ ИГРА",
										startX,
										startY - 95f * S,
										btnW,
										btnH,

										new ColorRGBA(0.03f,0.07f,0.18f,0.92f),
										new ColorRGBA(0.10f,0.18f,0.40f,1f),
										new ColorRGBA(0.02f,0.04f,0.10f,1f),

										new ColorRGBA(0.35f,0.75f,1f,1f),

										assetManager,
										guiNode,
										5f
						);

						settingsBtn = new MenuButton(
										"НАСТРОЙКИ",
										startX,
										startY - 190f * S,
										btnW,
										btnH,

										new ColorRGBA(0.03f,0.07f,0.18f,0.92f),
										new ColorRGBA(0.10f,0.18f,0.40f,1f),
										new ColorRGBA(0.02f,0.04f,0.10f,1f),

										new ColorRGBA(0.80f,0.88f,1f,1f),

										assetManager,
										guiNode,
										5f
						);


                        // Только в главном меню: кнопки более округлые, “капсульные”.
                        if (soloBtn != null) soloBtn.setCornerRadius(22f * S);
                        if (joinBtn != null) joinBtn.setCornerRadius(22f * S);
                        if (settingsBtn != null) settingsBtn.setCornerRadius(22f * S);

						// =========================================================
						// BOTTOM BUTTONS
						// =========================================================
						float miniW = 160f * S;
						float miniH = 54f * S;

						float miniY = 65f * S;

						creditsBtn = new MenuButton(
										"CREDITS",
										120f * S,
										miniY,
										miniW,
										miniH,

										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,

										TEXT,

										assetManager,
										guiNode,
										5f
						);

						patreonBtn = new MenuButton(
										"PATREON",
										320f * S,
										miniY,
										miniW,
										miniH,

										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,

										new ColorRGBA(1f,0.55f,0.15f,1f),

										assetManager,
										guiNode,
										5f
						);

						discordBtn = new MenuButton(
										"DISCORD",
										520f * S,
										miniY,
										miniW,
										miniH,

										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,

										new ColorRGBA(0.70f,0.65f,1f,1f),

										assetManager,
										guiNode,
										5f
						);

                        if (creditsBtn != null) creditsBtn.setCornerRadius(18f * S);
                        if (patreonBtn != null) patreonBtn.setCornerRadius(18f * S);
                        if (discordBtn != null) discordBtn.setCornerRadius(18f * S);
                        if (creditsBtn != null) creditsBtn.nudgeText(8f, 0f);
                        if (patreonBtn != null) patreonBtn.nudgeText(8f, 0f);
                        if (discordBtn != null) discordBtn.nudgeText(8f, 0f);
                        buildCreditsPanel();

						// =========================================================
						// SETTINGS PANEL
						// =========================================================
						buildSettingsPanel();
				}

        private void buildCreditsPanel() {
            float W = cam.getWidth(), H = cam.getHeight();
            BitmapFont font = loadFont(assetManager);
            creditsPanel = new Node("CreditsPanel");

            Geometry dim = new Geometry("CreditsDim", new Box(W/2f, H/2f, 0.1f));
            Material dm = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            dm.setColor("Color", new ColorRGBA(0f,0f,0f,0.72f));
            dm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            dim.setMaterial(dm);
            dim.setLocalTranslation(W/2f, H/2f, 60f);
            creditsPanel.attachChild(dim);

            Geometry card = new Geometry("CreditsCard", new Box(310f, 210f, 0.4f));
            Material cm = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            cm.setColor("Color", BG_CARD);
            cm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            card.setMaterial(cm);
            card.setLocalTranslation(W/2f, H/2f, 61f);
            creditsPanel.attachChild(card);

            BitmapText title = new BitmapText(font);
            title.setSize(34); title.setText("CREDITS"); title.setColor(ACCENT2);
            title.setLocalTranslation(W/2f - title.getLineWidth()/2f, H/2f + 145f, 62f);
            creditsPanel.attachChild(title);

            BitmapText body = new BitmapText(font);
            body.setSize(18);
            body.setText("Turbo Snake\n\nGame / Code: Frank\nEngine: jMonkeyEngine + Bullet\nMusic / SFX: placeholder\n\nЭто окно-заглушка, позже можно заменить текст.");
            body.setColor(TEXT);
            body.setLocalTranslation(W/2f - 240f, H/2f + 80f, 62f);
            creditsPanel.attachChild(body);

            creditsCloseBtn = new MenuButton("ЗАКРЫТЬ", W/2f, H/2f - 145f, 250f, 48f,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT, assetManager, creditsPanel, 62f);
            creditsPanel.setCullHint(Spatial.CullHint.Always);
            guiNode.attachChild(creditsPanel);
        }

        private void openExternal(String url) {
            try {
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().browse(new java.net.URI(url));
                } else {
                    System.out.println("Open URL: " + url);
                }
            } catch (Exception e) {
                System.out.println("Cannot open URL: " + url + " | " + e.getMessage());
            }
        }

				private void buildSettingsPanel() {
						float W = cam.getWidth(), H = cam.getHeight(), cx = W/2f, cy = H/2f;
						BitmapFont font = loadFont(assetManager);
						float S = uiScale(cam);
						settingsPanel = new Node("SettingsPanel");

						// ── Затемнение ──
						Box dimBox = new Box(W/2f, H/2f, 0.1f);
						Geometry dimGeo = new Geometry("SettingsDim", dimBox);
						Material dimMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						dimMat.setColor("Color", new ColorRGBA(0f,0f,0f,0.75f));
						dimMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
						dimGeo.setMaterial(dimMat);
						dimGeo.setLocalTranslation(cx, cy, 45f);
						settingsPanel.attachChild(dimGeo);

						// ── Основная карточка ──
						float panelMaxW = 760f * S;
						float panelMaxH = 650f * S;
						float panelW = Math.min(panelMaxW, W - 90f * S);
						float panelH = Math.min(panelMaxH, H - 80f * S);
						Box panelBox = new Box(panelW/2f, panelH/2f, 0.5f);
						Geometry panelGeo = new Geometry("PanelBg", panelBox);
						Material pm = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						pm.setColor("Color", BG_CARD);
						pm.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
						panelGeo.setMaterial(pm);
						panelGeo.setLocalTranslation(cx, cy, 46f);
						settingsPanel.attachChild(panelGeo);

						final float Z = 50f;

						// ── Акцентная полоса сверху ──
						Geometry headerLine = new Geometry("PanelHeaderLine", new Box(panelW/2f, 3f * S, 0.3f));
						headerLine.setMaterial(unshaded(assetManager, ACCENT));
						headerLine.setLocalTranslation(cx, cy + panelH/2f - 2f * S, Z);
						settingsPanel.attachChild(headerLine);

						// ── Заголовок ──
						BitmapText header = new BitmapText(font);
						header.setSize(28f * S);
						header.setText("НАСТРОЙКИ");
						header.setColor(ACCENT2);
						header.setLocalTranslation(cx - header.getLineWidth()/2, cy + panelH/2f - 30f * S, Z);
						settingsPanel.attachChild(header);

						// ── Вкладки ──
						float tabY = cy + panelH/2f - 90f * S;
						float tabW = 180f * S;
						float tabH = 38f * S;
						float tabGap = 10f * S;

						tabMainBtn = new MenuButton("ОСНОВНОЕ", cx - tabW/2f - tabGap/2f, tabY, tabW, tabH,
										BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT2, assetManager, settingsPanel, Z);
						tabGraphicsBtn = new MenuButton("ГРАФИКА", cx + tabW/2f + tabGap/2f, tabY, tabW, tabH,
										BTN_NORMAL, BTN_HOVER, BTN_PRESS, TEXT_DIM, assetManager, settingsPanel, Z);

						// ── ВКЛАДКА 0: ОСНОВНОЕ ──
						float contentTop = tabY - tabH/2f - 20f * S;

						BitmapText sfxLabel = new BitmapText(font);
						sfxLabel.setName("SfxLabel");
						sfxLabel.setSize(18f * S);
						sfxLabel.setText("Звуки");
						sfxLabel.setColor(TEXT);
						sfxLabel.setLocalTranslation(cx - panelW/2f + 25f * S, contentTop, Z);
						settingsPanel.attachChild(sfxLabel);
						sfxVal = new BitmapText(font);
						sfxVal.setSize(18f * S);
						sfxVal.setColor(ACCENT2);
						sfxVal.setLocalTranslation(cx + panelW/2f - 70f * S, contentTop, Z);
						settingsPanel.attachChild(sfxVal);
						sfxSlider = new VolumeSlider(cx, contentTop - 28f * S, panelW - 60f * S, effectVolume, assetManager, settingsPanel, Z);

						BitmapText musicLabel = new BitmapText(font);
						musicLabel.setName("MusicLabel");
						musicLabel.setSize(18f * S);
						musicLabel.setText("Музыка");
						musicLabel.setColor(TEXT);
						musicLabel.setLocalTranslation(cx - panelW/2f + 25f * S, contentTop - 72f * S, Z);
						settingsPanel.attachChild(musicLabel);
						musicVal = new BitmapText(font);
						musicVal.setSize(18f * S);
						musicVal.setColor(ACCENT);
						musicVal.setLocalTranslation(cx + panelW/2f - 70f * S, contentTop - 72f * S, Z);
						settingsPanel.attachChild(musicVal);
						musicSlider = new VolumeSlider(cx, contentTop - 100f * S, panelW - 60f * S, musicVolume, assetManager, settingsPanel, Z);

						// Разделитель
						Geometry div2 = new Geometry("Div2", new Box(panelW/2f - 30f * S, 1f * S, 0.2f));
						div2.setName("NickDivider");
						div2.setMaterial(unshaded(assetManager, BORDER));
						div2.setLocalTranslation(cx, contentTop - 138f * S, Z);
						settingsPanel.attachChild(div2);

						BitmapText nickLabel = new BitmapText(font);
						nickLabel.setName("NickLabel");
						nickLabel.setSize(15f * S);
						nickLabel.setText("ИМЯ ИГРОКА");
						nickLabel.setColor(TEXT_DIM);
						nickLabel.setLocalTranslation(cx - panelW/2f + 25f * S, contentTop - 150f * S, Z);
						settingsPanel.attachChild(nickLabel);

						Box nickBorder = new Box(panelW/2f - 25f * S, 22f * S, 0.2f);
						Geometry nickBorderGeo = new Geometry("NickBorder", nickBorder);
						nickBorderGeo.setMaterial(unshaded(assetManager, ACCENT2));
						nickBorderGeo.setLocalTranslation(cx, contentTop - 195f * S, Z - 0.1f);
						settingsPanel.attachChild(nickBorderGeo);
						Box nickBg = new Box(panelW/2f - 27f * S, 20f * S, 0.3f);
						Geometry nickBgGeo = new Geometry("NickBg", nickBg);
						nickBgGeo.setMaterial(unshaded(assetManager, new ColorRGBA(0.04f,0.06f,0.14f,1f)));
						nickBgGeo.setLocalTranslation(cx, contentTop - 195f * S, Z);
						settingsPanel.attachChild(nickBgGeo);
						nicknameText = new BitmapText(font);
						nicknameText.setSize(22f * S);
						nicknameText.setText(nickname.toString());
						nicknameText.setColor(ACCENT3);
						nicknameText.setLocalTranslation(cx - panelW/2f + 32f * S, contentTop - 183f * S, Z + 0.5f);
						settingsPanel.attachChild(nicknameText);
						cursorBlink = new BitmapText(font);
						cursorBlink.setSize(22f * S);
						cursorBlink.setText("|");
						cursorBlink.setColor(ACCENT3);
						settingsPanel.attachChild(cursorBlink);

						// ── ВКЛАДКА 1: ГРАФИКА ───────────────────────────────────────────
						float gTop = contentTop - 28f * S;
						float toggleW = (panelW - 92f * S) / 2f;
						float toggleH = 36f * S;
						float toggleGapY = 12f * S;
						float leftColX = cx - toggleW/2f - 10f * S;
						float rightColX = cx + toggleW/2f + 10f * S;

						float row1Y = gTop - toggleH/2f;
						float row2Y = row1Y - (toggleH + toggleGapY);
						float row3Y = row2Y - (toggleH + toggleGapY);
						float row4Y = row3Y - (toggleH + toggleGapY);
						float row5Y = row4Y - (toggleH + toggleGapY);

						btnShadows = new MenuButton(shadowQualityButtonText(), leftColX, row1Y, toggleW, toggleH,
										shadowQualityBgColor(), BTN_HOVER, BTN_PRESS, shadowQualityAccentColor(), assetManager, settingsPanel, Z);
						btnParticles = new MenuButton(particlesEnabled ? "ЧАСТИЦЫ ВКЛ" : "ЧАСТИЦЫ ВЫКЛ", rightColX, row1Y, toggleW, toggleH,
										particlesEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, particlesEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
						btnFog = new MenuButton(fogEnabled ? "ТУМАН ВКЛ" : "ТУМАН ВЫКЛ", leftColX, row2Y, toggleW, toggleH,
										fogEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, fogEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
						btnBloom = new MenuButton(bloomEnabled ? "BLOOM ВКЛ" : "BLOOM ВЫКЛ", rightColX, row2Y, toggleW, toggleH,
										bloomEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, bloomEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
						btnPost = new MenuButton(postProcessingEnabled ? "ПОСТ ВКЛ" : "ПОСТ ВЫКЛ", leftColX, row3Y, toggleW, toggleH,
										postProcessingEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, postProcessingEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
						btnLights = new MenuButton(dynamicLightsEnabled ? "ДИН.СВЕТ ВКЛ" : "ДИН.СВЕТ ВЫКЛ", rightColX, row3Y, toggleW, toggleH,
										dynamicLightsEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, dynamicLightsEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
						btnWater = new MenuButton(waterEffectsEnabled ? "ВОДА ВКЛ" : "ВОДА ВЫКЛ", leftColX, row4Y, toggleW, toggleH,
										waterEffectsEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, waterEffectsEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
						btnTerrain = new MenuButton(terrainDetailsEnabled ? "ДЕТАЛИ КАРТ ВКЛ" : "ДЕТАЛИ КАРТ ВЫКЛ", rightColX, row4Y, toggleW, toggleH,
										terrainDetailsEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, terrainDetailsEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
						btnLowPoly = new MenuButton(lowPolyMode ? "LOW POLY ВКЛ" : "LOW POLY ВЫКЛ", leftColX, row5Y, toggleW, toggleH,
										lowPolyMode ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, lowPolyMode ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
						btnNames = new MenuButton(nameTagsEnabled ? "НИКИ ВКЛ" : "НИКИ ВЫКЛ", rightColX, row5Y, toggleW, toggleH,
										nameTagsEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL, BTN_HOVER, BTN_PRESS, nameTagsEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);

						// ── Кнопка ЗАКРЫТЬ ──
						float closeY = cy - panelH/2f + 40f * S;
						settingsClose = new MenuButton("СОХРАНИТЬ И ЗАКРЫТЬ", cx, closeY, panelW - 60f * S, 46f * S,
										BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT, assetManager, settingsPanel, Z);

						setSettingsTab(0);
						updateSettingsLabels();
						refreshNick();
						settingsPanel.setCullHint(Spatial.CullHint.Always);
						guiNode.attachChild(settingsPanel);
				}

        private void setSettingsTab(int tab) {
            activeSettingsTab = tab;

            // Обновляем внешний вид кнопок вкладок
            if (tabMainBtn != null) {
                tabMainBtn.setAccentColor(tab == 0 ? ACCENT2 : TEXT_DIM);
            }
            if (tabGraphicsBtn != null) {
                tabGraphicsBtn.setAccentColor(tab == 1 ? ACCENT2 : TEXT_DIM);
            }

            // Элементы вкладки «Основное»
            String[] mainNames = {"SfxLabel", "MusicLabel", "NickLabel", "NickBorder", "NickBg", "NickDivider"};
            for (String name : mainNames) {
                Spatial s = settingsPanel.getChild(name);
                if (s != null) s.setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            }
            if (nicknameText != null) nicknameText.setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (cursorBlink  != null) cursorBlink .setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (sfxVal   != null) sfxVal  .setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (musicVal != null) musicVal.setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (sfxSlider   != null) sfxSlider  .setVisible(tab == 0);
            if (musicSlider != null) musicSlider.setVisible(tab == 0);

            // Элементы вкладки «Графика»
            Spatial.CullHint gfxHint = tab == 1 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            if (btnShadows   != null) { btnShadows.bgGeo.setCullHint(gfxHint); btnShadows.accentGeo.setCullHint(gfxHint); btnShadows.borderGeo.setCullHint(gfxHint); btnShadows.label.setCullHint(gfxHint); }
            if (btnParticles != null) { btnParticles.bgGeo.setCullHint(gfxHint); btnParticles.accentGeo.setCullHint(gfxHint); btnParticles.borderGeo.setCullHint(gfxHint); btnParticles.label.setCullHint(gfxHint); }
            if (btnFog       != null) { btnFog.bgGeo.setCullHint(gfxHint); btnFog.accentGeo.setCullHint(gfxHint); btnFog.borderGeo.setCullHint(gfxHint); btnFog.label.setCullHint(gfxHint); }
            if (btnBloom     != null) { btnBloom.bgGeo.setCullHint(gfxHint); btnBloom.accentGeo.setCullHint(gfxHint); btnBloom.borderGeo.setCullHint(gfxHint); btnBloom.label.setCullHint(gfxHint); }
            if (btnPost      != null) { btnPost.bgGeo.setCullHint(gfxHint); btnPost.accentGeo.setCullHint(gfxHint); btnPost.borderGeo.setCullHint(gfxHint); btnPost.label.setCullHint(gfxHint); }
            if (btnLights    != null) { btnLights.bgGeo.setCullHint(gfxHint); btnLights.accentGeo.setCullHint(gfxHint); btnLights.borderGeo.setCullHint(gfxHint); btnLights.label.setCullHint(gfxHint); }
            if (btnWater     != null) { btnWater.bgGeo.setCullHint(gfxHint); btnWater.accentGeo.setCullHint(gfxHint); btnWater.borderGeo.setCullHint(gfxHint); btnWater.label.setCullHint(gfxHint); }
            if (btnTerrain   != null) { btnTerrain.bgGeo.setCullHint(gfxHint); btnTerrain.accentGeo.setCullHint(gfxHint); btnTerrain.borderGeo.setCullHint(gfxHint); btnTerrain.label.setCullHint(gfxHint); }
            if (btnLowPoly   != null) { btnLowPoly.bgGeo.setCullHint(gfxHint); btnLowPoly.accentGeo.setCullHint(gfxHint); btnLowPoly.borderGeo.setCullHint(gfxHint); btnLowPoly.label.setCullHint(gfxHint); }
            if (btnNames     != null) { btnNames.bgGeo.setCullHint(gfxHint); btnNames.accentGeo.setCullHint(gfxHint); btnNames.borderGeo.setCullHint(gfxHint); btnNames.label.setCullHint(gfxHint); }
        }

        private void updateSettingsLabels() {
            if (sfxVal   != null) sfxVal.setText(Math.round(effectVolume * 100) + "%");
            if (musicVal != null) musicVal.setText(Math.round(musicVolume * 100) + "%");
            if (sfxSlider   != null) sfxSlider.setValue(effectVolume);
            if (musicSlider != null) musicSlider.setValue(musicVolume);
            MusicManager.setVolume(musicVolume);
            updateShadowQualityButton(btnShadows);
        }

        private void startMenuMusic() {
            // использовать глобальный MusicManager — не пересоздавать звук при возврате в меню
            MusicManager.play(assetManager, rootNode, "Sounds/theme/main1.ogg", musicVolume);
        }

        private void refreshNick() {
            nicknameText.setText(nickname.toString());
            float x = nicknameText.getLocalTranslation().x;
            float y = nicknameText.getLocalTranslation().y;
            cursorBlink.setLocalTranslation(x + nicknameText.getLineWidth() + 2, y, 1f);
        }

				private void setToggleButton(MenuButton b, boolean value, String title) {
						if (b == null) return;
						b.setText(title + " " + (value ? "ВКЛ" : "ВЫКЛ"));
						b.setAccentColor(value ? ACCENT : TEXT_DIM);
						b.setBgNormal(value ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL);
				}

        private void setupInput() {
            inputManager.addRawInputListener(new com.jme3.input.RawInputListener() {
                @Override public void onKeyEvent(com.jme3.input.event.KeyInputEvent evt) {
                    if (!settingsOpen || activeSettingsTab != 0 || !evt.isPressed()) return;
                    int code = evt.getKeyCode();
                    if (code == KeyInput.KEY_BACK) {
                        if (nickname.length() > 0) {
                            nickname.deleteCharAt(nickname.length()-1);
                            app.enqueue(() -> refreshNick());
                        }
                        return;
                    }
                    char ch = evt.getKeyChar();
                    if (ch != 0 && !Character.isISOControl(ch) && nickname.length() < 16) {
                        nickname.append(ch);
                        app.enqueue(() -> refreshNick());
                    }
                }
                @Override public void beginInput() {}
                @Override public void endInput() {}
                @Override public void onMouseMotionEvent(com.jme3.input.event.MouseMotionEvent evt) {}
                @Override public void onMouseButtonEvent(com.jme3.input.event.MouseButtonEvent evt) {}
                @Override public void onJoyAxisEvent(com.jme3.input.event.JoyAxisEvent evt) {}
                @Override public void onJoyButtonEvent(com.jme3.input.event.JoyButtonEvent evt) {}
                @Override public void onTouchEvent(com.jme3.input.event.TouchEvent evt) {}
            });

            inputManager.addMapping("MenuEsc", new KeyTrigger(KeyInput.KEY_ESCAPE));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p) return;
                if (creditsOpen) { creditsOpen = false; if (creditsPanel != null) creditsPanel.setCullHint(Spatial.CullHint.Always); }
                else if (settingsOpen) { settingsOpen = false; settingsPanel.setCullHint(Spatial.CullHint.Always); }
                else app.stop();
            }, "MenuEsc");

            inputManager.addMapping("MMouseMove",
                    new MouseAxisTrigger(MouseInput.AXIS_X, false), new MouseAxisTrigger(MouseInput.AXIS_X, true),
                    new MouseAxisTrigger(MouseInput.AXIS_Y, false), new MouseAxisTrigger(MouseInput.AXIS_Y, true));
            inputManager.addListener((AnalogListener)(n,v,t) -> {
                com.jme3.math.Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
                if (mp == null) return;
                float mx = mp.x, my = mp.y;
                soloBtn.updateHover(mx, my); joinBtn.updateHover(mx, my);
                settingsBtn.updateHover(mx, my);
                if (creditsBtn != null) creditsBtn.updateHover(mx, my);
                if (patreonBtn != null) patreonBtn.updateHover(mx, my);
                if (discordBtn != null) discordBtn.updateHover(mx, my);
                if (creditsOpen && creditsCloseBtn != null) creditsCloseBtn.updateHover(mx, my);

								if (settingsOpen) {
										sfxSlider.updateHover(mx, my); musicSlider.updateHover(mx, my);
                                        if (activeSettingsTab == 0) {
                                                boolean changed = false;
                                                if (sfxSlider != null && sfxSlider.onDrag(mx, my)) { effectVolume = sfxSlider.getValue(); changed = true; }
                                                if (musicSlider != null && musicSlider.onDrag(mx, my)) { musicVolume = musicSlider.getValue(); changed = true; }
                                                if (changed) updateSettingsLabels();
                                        }
										settingsClose.updateHover(mx, my);
										tabMainBtn.updateHover(mx, my);
										tabGraphicsBtn.updateHover(mx, my);

										// новые строки ↓
										if (activeSettingsTab == 1) {
												if (btnShadows   != null) btnShadows.updateHover(mx, my);
												if (btnParticles != null) btnParticles.updateHover(mx, my);
												if (btnFog       != null) btnFog.updateHover(mx, my);
												if (btnBloom     != null) btnBloom.updateHover(mx, my);
                                                if (btnPost      != null) btnPost.updateHover(mx, my);
                                                if (btnLights    != null) btnLights.updateHover(mx, my);
                                                if (btnWater     != null) btnWater.updateHover(mx, my);
                                                if (btnTerrain   != null) btnTerrain.updateHover(mx, my);
                                                if (btnLowPoly   != null) btnLowPoly.updateHover(mx, my);
                                                if (btnNames     != null) btnNames.updateHover(mx, my);
										}
								}

            }, "MMouseMove");

            inputManager.addMapping("MClick", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                com.jme3.math.Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
                if (mp == null) return;
                float mx = mp.x, my = mp.y;
                                if (creditsOpen) {
                                    if (p) {
                                        if (creditsCloseBtn != null) creditsCloseBtn.onPress(mx, my);
                                    } else if (creditsCloseBtn != null && creditsCloseBtn.onRelease(mx, my)) {
                                        creditsOpen = false;
                                        if (creditsPanel != null) creditsPanel.setCullHint(Spatial.CullHint.Always);
                                    }
                                    return;
                                }
								if (settingsOpen) {
										if (p) {
												settingsClose.onPress(mx, my);
                                                if (activeSettingsTab == 0) {
                                                        boolean changed = false;
                                                        if (sfxSlider != null && sfxSlider.onPress(mx, my)) { effectVolume = sfxSlider.getValue(); changed = true; }
                                                        else if (musicSlider != null && musicSlider.onPress(mx, my)) { musicVolume = musicSlider.getValue(); changed = true; }
                                                        if (changed) updateSettingsLabels();
                                                }
												if (activeSettingsTab == 1) {
														if (btnShadows   != null) btnShadows.onPress(mx, my);
														if (btnParticles != null) btnParticles.onPress(mx, my);
														if (btnFog       != null) btnFog.onPress(mx, my);
														if (btnBloom     != null) btnBloom.onPress(mx, my);
                                                        if (btnPost      != null) btnPost.onPress(mx, my);
                                                        if (btnLights    != null) btnLights.onPress(mx, my);
                                                        if (btnWater     != null) btnWater.onPress(mx, my);
                                                        if (btnTerrain   != null) btnTerrain.onPress(mx, my);
                                                        if (btnLowPoly   != null) btnLowPoly.onPress(mx, my);
                                                        if (btnNames     != null) btnNames.onPress(mx, my);
												}
										} else {
                                                if (activeSettingsTab == 0 && sfxSlider != null && sfxSlider.onRelease(mx, my)) {
                                                        effectVolume = sfxSlider.getValue(); updateSettingsLabels();
                                                } else if (activeSettingsTab == 0 && musicSlider != null && musicSlider.onRelease(mx, my)) {
                                                        musicVolume = musicSlider.getValue(); updateSettingsLabels();
                                                } else if (settingsClose.onRelease(mx, my)) {
														settingsOpen = false; settingsPanel.setCullHint(Spatial.CullHint.Always);
														saveSettings(nickname.toString());
												} else if (tabMainBtn.isHit(mx, my)) {
														setSettingsTab(0);
												} else if (tabGraphicsBtn.isHit(mx, my)) {
														setSettingsTab(1);
												} else if (activeSettingsTab == 1) {
														if (btnShadows != null && btnShadows.onRelease(mx, my)) {
														shadowQuality = nextShadowQuality(shadowQuality);
														shadowsEnabled = shadowQuality != SHADOW_QUALITY_OFF;
														updateShadowQualityButton(btnShadows);
														saveSettings(nickname.toString());
														} else if (btnParticles != null && btnParticles.onRelease(mx, my)) {
																particlesEnabled = !particlesEnabled;
																setToggleButton(btnParticles, particlesEnabled, "ЧАСТИЦЫ");
																saveSettings(nickname.toString());
														} else if (btnFog != null && btnFog.onRelease(mx, my)) {
																fogEnabled = !fogEnabled;
																setToggleButton(btnFog, fogEnabled, "ТУМАН");
																saveSettings(nickname.toString());
														} else if (btnBloom != null && btnBloom.onRelease(mx, my)) {
																bloomEnabled = !bloomEnabled;
																setToggleButton(btnBloom, bloomEnabled, "BLOOM");
																saveSettings(nickname.toString());
														} else if (btnPost != null && btnPost.onRelease(mx, my)) {
																postProcessingEnabled = !postProcessingEnabled;
																setToggleButton(btnPost, postProcessingEnabled, "ПОСТ");
																saveSettings(nickname.toString());
														} else if (btnLights != null && btnLights.onRelease(mx, my)) {
																dynamicLightsEnabled = !dynamicLightsEnabled;
																setToggleButton(btnLights, dynamicLightsEnabled, "ДИН.СВЕТ");
																saveSettings(nickname.toString());
														} else if (btnWater != null && btnWater.onRelease(mx, my)) {
																waterEffectsEnabled = !waterEffectsEnabled;
																setToggleButton(btnWater, waterEffectsEnabled, "ВОДА");
																saveSettings(nickname.toString());
														} else if (btnTerrain != null && btnTerrain.onRelease(mx, my)) {
																terrainDetailsEnabled = !terrainDetailsEnabled;
																setToggleButton(btnTerrain, terrainDetailsEnabled, "ДЕТАЛИ КАРТ");
																saveSettings(nickname.toString());
														} else if (btnLowPoly != null && btnLowPoly.onRelease(mx, my)) {
																lowPolyMode = !lowPolyMode;
																setToggleButton(btnLowPoly, lowPolyMode, "LOW POLY");
																saveSettings(nickname.toString());
														} else if (btnNames != null && btnNames.onRelease(mx, my)) {
																nameTagsEnabled = !nameTagsEnabled;
																setToggleButton(btnNames, nameTagsEnabled, "НИКИ");
																saveSettings(nickname.toString());
														}
												}
										}
										return;
								}
                if (p) {
                    soloBtn.onPress(mx, my); joinBtn.onPress(mx, my);
                    settingsBtn.onPress(mx, my);
                    if (creditsBtn != null) creditsBtn.onPress(mx, my);
                    if (patreonBtn != null) patreonBtn.onPress(mx, my);
                    if (discordBtn != null) discordBtn.onPress(mx, my);
                } else {
                    if (soloBtn.onRelease(mx, my)) launch(false, true);
                    else if (joinBtn.onRelease(mx, my)) launch(false, false);
                    else if (creditsBtn != null && creditsBtn.onRelease(mx, my)) {
                        creditsOpen = true;
                        if (creditsPanel != null) { creditsPanel.setCullHint(Spatial.CullHint.Inherit); LemurUi.playPanelPop(creditsPanel); }
                    }
                    else if (patreonBtn != null && patreonBtn.onRelease(mx, my)) openExternal("https://www.patreon.com/c/sonny57");
                    else if (discordBtn != null && discordBtn.onRelease(mx, my)) openExternal("https://discord.gg/x7Un649G34");
                    else if (settingsBtn.onRelease(mx, my)) {
                        settingsOpen = true;
                        settingsPanel.setCullHint(Spatial.CullHint.Inherit);
                        LemurUi.playPanelPop(settingsPanel);
                        setSettingsTab(activeSettingsTab);
                        updateSettingsLabels();
                    }
                }
            }, "MClick");
        }

        private boolean clickedTab(float mx, float my) {
            Spatial tabMain = settingsPanel.getChild("SettingsTabMain");
            if (tabMain instanceof BitmapText) {
                float x = tabMain.getLocalTranslation().x, y = tabMain.getLocalTranslation().y;
                if (mx >= x && mx <= x + 130f && my >= y - 20f && my <= y + 6f) { setSettingsTab(0); return true; }
            }
            return false;
        }

        private void launch(boolean host, boolean solo) {
            String nick = nickname.length()==0 ? "Player" : nickname.toString();
            savedNickname = nick;
            saveSettings(nick);
            // НЕ останавливаем музыку — MusicManager обеспечивает непрерывное воспроизведение
            MusicManager.setVolume(musicVolume);
            if (app != null && app.getInputManager() != null) app.getInputManager().setCursorVisible(false);
            detachQuietly(rootNode, decorNode);
            LemurUi.clearPage(guiNode);
            clearInputMappingsQuietly(inputManager);
            app.getStateManager().detach(this);
            if (solo) {
                app.getStateManager().attach(new LobbyState(nick, false, true, null, 0));
            } else {
                app.getStateManager().attach(new ServerListState(nick));
            }
        }

        @Override
        public void update(float tpf) {
            tpf = safeTpf(tpf);
            if (tpf <= 0f || missingRefs("MainMenu.update", app, cam)) return;
            forceMenuCursor(app);
            if (ballAngles != null && ballSpeeds != null && ballRadii != null && ballY != null && decorNode != null) {
                int count = Math.min(ballAngles.length, Math.min(ballSpeeds.length, Math.min(ballRadii.length, ballY.length)));
                count = Math.min(count, decorNode.getQuantity());
                for (int i=0; i<count; i++) {
                    Spatial child = decorNode.getChild(i);
                    if (child == null) continue;
                    ballAngles[i] += ballSpeeds[i]*tpf;
                    child.setLocalTranslation(
                            FastMath.sin(ballAngles[i])*ballRadii[i], ballY[i],
                            FastMath.cos(ballAngles[i])*ballRadii[i]);
                }
            }
            blinkTimer += tpf;
            if (blinkTimer>0.5f && cursorBlink != null) {
                blinkTimer=0; cursorVisible=!cursorVisible;
                cursorBlink.setColor(cursorVisible ? ACCENT3 : new ColorRGBA(0,0,0,0));
            }
						
						float W = cam.getWidth();
						float H = cam.getHeight();
						
						if (lastW == -1 || lastH == -1) {
								lastW = cam.getWidth();
								lastH = cam.getHeight();
						}

						if (cam.getWidth() != lastW || cam.getHeight() != lastH) {

								lastW = cam.getWidth();
								lastH = cam.getHeight();

								rebuildUIAfterResize();
						}
        }
    }

    // =========================================================================
    // НОВЫЙ ЭКРАН: СПИСОК ДОСТУПНЫХ СЕРВЕРОВ
    // =========================================================================
    static class ServerListState extends AbstractAppState {
        private SimpleApplication app;
        private Node guiNode;
        private AssetManager assetManager;
        private InputManager inputManager;
        private Camera cam;
        private final String myNick;

				private int lastW = -1;
				private int lastH = -1;

        private BitmapText titleText, statusText;
        private final List<MenuButton> serverButtons = new ArrayList<>();
        private final List<ServerEntry> serverEntries = new CopyOnWriteArrayList<>();
        private MenuButton refreshBtn, createBtn, backBtn;
        private DatagramSocket discoverSocket;
        private Thread scanThread;
        private volatile boolean scanning = false;
        private float scanTimer = 0f;

        public ServerListState(String nick) {
            this.myNick = nick;
        }

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            if (invalidApplication(application, "ServerList.initialize")) return;
            app = (SimpleApplication) application;
            guiNode = app.getGuiNode();
            assetManager = app.getAssetManager();
            inputManager = app.getInputManager();
            cam = app.getCamera();
            if (missingRefs("ServerList.initialize", guiNode, assetManager, inputManager, cam)) return;
            try {
                if (app.getViewPort() != null) app.getViewPort().setBackgroundColor(BG);
                forceMenuCursor(app);
                MusicManager.play(assetManager, app.getRootNode(), "Sounds/theme/main1.ogg", musicVolume);
                buildUI();
                setupInput();
                startScan();
            } catch (Exception e) {
                logSafe("ServerList.initialize", e);
            }
        }

				private void rebuildUIAfterResize() {

						LemurUi.clearPage(guiNode);

						serverButtons.clear();

						buildUI();

						updateServerListGUI();
				}

				private void buildUI() {
						BitmapFont font = loadFont(assetManager);
						float W = cam.getWidth(), H = cam.getHeight();
						float S = uiScale(cam);
						buildDimmedMenuBackdrop(assetManager, guiNode, cam, -8f);

						titleText = new BitmapText(font);
						titleText.setSize(42 * S); titleText.setText("ДОСТУПНЫЕ ЛОББИ");
						titleText.setColor(ACCENT);
						UiGrid.centerText(guiNode, "Text:ServerTitle", titleText, W/2f, H - 40, 0);
						guiNode.attachChild(titleText);

						statusText = new BitmapText(font);
						statusText.setSize(20 * S); statusText.setColor(TEXT_DIM);
						UiGrid.placeText(guiNode, "Text:ServerStatus", statusText, 40, H - 100, 0);
						guiNode.attachChild(statusText);

						float btnW = 280f * S;
						float btnH = 45f * S;
						float commonY = 40f * S;

						createBtn = new MenuButton("Создать лобби", 40f * S + btnW/2f, commonY, btnW, btnH,
										BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT3, assetManager, guiNode, 0f);
						refreshBtn = new MenuButton("Обновить", 40f * S + btnW + 20f * S + btnW/2f, commonY, btnW, btnH,
										BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT2, assetManager, guiNode, 0f);
						backBtn = new MenuButton("Назад", W - 40f * S - 140f * S/2f, commonY, 140f * S, btnH,
										BTN_NORMAL, BTN_HOVER, BTN_PRESS, DANGER, assetManager, guiNode, 0f);
				}

        private void updateServerListGUI() {
            for (MenuButton b : snapshot(serverButtons)) if (b != null) b.detach(guiNode);
            serverButtons.clear();
            if (cam == null || guiNode == null || assetManager == null) return;

            float y = cam.getHeight() - 150f;
            for (ServerEntry entry : snapshot(serverEntries)) {
                if (entry == null) continue;
                String label = entry.ip + " | " + entry.mapName + " | " + entry.playerCount + " | " + entry.pingMs + "мс";
                MenuButton btn = new MenuButton(label, 40f + 250f, y, 500f, 45f,
                        BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT2, assetManager, guiNode, 0f);
                serverButtons.add(btn);
                y -= 50f;
            }
            if (statusText != null) statusText.setText("Найдено лобби: " + serverEntries.size());
        }

        private void startScan() {
            if (scanning) return;
            scanning = true;
            serverEntries.clear();
            updateServerListGUI();
            statusText.setText("Сканирование сети...");
            scanTimer = 3.0f;

            new Thread(() -> {
                try {
                    discoverSocket = new DatagramSocket();
                    discoverSocket.setSoTimeout(200);
                    byte[] db = "DISCOVER".getBytes(StandardCharsets.UTF_8);
                    InetAddress ba = InetAddress.getByName("255.255.255.255");
                    discoverSocket.send(new DatagramPacket(db, db.length, ba, BROADCAST_PORT));

                    byte[] buf = new byte[256];
                    while (scanning) {
                        try {
                            DatagramPacket rp = new DatagramPacket(buf, buf.length);
                            discoverSocket.receive(rp);

                            String resp = new String(rp.getData(), 0, rp.getLength(), StandardCharsets.UTF_8);
                            if (resp.startsWith("HOST_HERE|")) {
                                String[] parts = resp.split("\\|", -1);
                                if (parts.length >= 2) {
                                    String ip = rp.getAddress().getHostAddress() + ":" + parts[1];
                                    String mapName = parts.length > 2 ? parts[2] : "?";
                                    String playerCount = parts.length > 3 ? parts[3] : "?";
                                    long pingMs = 0L;
                                    synchronized (serverEntries) {
                                        boolean exists = serverEntries.stream().anyMatch(se -> se.ip.equals(ip));
                                        if (!exists) serverEntries.add(new ServerEntry(ip, mapName, playerCount, pingMs));
                                    }
                                }
                                app.enqueue(this::updateServerListGUI);
                            }
                        } catch (SocketTimeoutException ignore) {
                            // Это нормально, просто никто не ответил за 200мс
                        } catch (SocketException e) {
                            // Если сокет закрыт намеренно, выходим из цикла без вывода ошибки
                            if (!scanning) break;
                            e.printStackTrace();
                        }
                    }
                } catch (Exception e) {
                    // Ошибки инициализации или отправки (например, нет сети)
                    if (scanning) e.printStackTrace();
                } finally {
                    stopScanning();
                }
            }).start();
        }

        @Override
        public void update(float tpf) {
            tpf = safeTpf(tpf);
            if (tpf <= 0f || missingRefs("ServerList.update", app, cam)) return;
            forceMenuCursor(app);
            if (scanning) {
                scanTimer -= tpf;
                if (scanTimer <= 0) {
                    scanning = false;
                    closeQuietly(discoverSocket);
                    if (statusText != null) statusText.setText("Сканирование завершено.");
                }
            }
						
						if (lastW == -1 || lastH == -1) {
								lastW = cam.getWidth();
								lastH = cam.getHeight();
						}

						if (cam.getWidth() != lastW || cam.getHeight() != lastH) {

								lastW = cam.getWidth();
								lastH = cam.getHeight();

								rebuildUIAfterResize();
						}
						
        }

        private void setupInput() {
					
						inputManager.addMapping("MMove",
										new MouseAxisTrigger(MouseInput.AXIS_X, false),
										new MouseAxisTrigger(MouseInput.AXIS_X, true),
										new MouseAxisTrigger(MouseInput.AXIS_Y, false),
										new MouseAxisTrigger(MouseInput.AXIS_Y, true)
						);

						inputManager.addListener((AnalogListener)(n, v, t) -> {

								Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
                if (mp == null) return;

								float mx = mp.x;
								float my = mp.y;

								createBtn.updateHover(mx, my);
								refreshBtn.updateHover(mx, my);
								backBtn.updateHover(mx, my);

								for (MenuButton btn : serverButtons) {
										btn.updateHover(mx, my);
								}

						}, "MMove");
					
            inputManager.addMapping("MClick", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p) return;
                Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
                if (mp == null) return;
                if (createBtn.isHit(mp.x, mp.y)) {
                    createLobby();
                    return;
                }
                if (refreshBtn.isHit(mp.x, mp.y)) {
                    startScan();
                    return;
                }
                if (backBtn.isHit(mp.x, mp.y)) {
                    backToMenu();
                    return;
                }
                for (int i = 0; i < serverButtons.size(); i++) {
                    if (serverButtons.get(i).isHit(mp.x, mp.y)) {
                        joinServer(serverEntries.get(i).ip);
                        return;
                    }
                }
            }, "MClick");

            inputManager.addMapping("Esc", new KeyTrigger(KeyInput.KEY_ESCAPE));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p) return;
                backToMenu();
            }, "Esc");
        }

        private void createLobby() {
            stopScanning();
            LemurUi.clearPage(guiNode);
            clearInputMappingsQuietly(inputManager);
            app.getStateManager().detach(this);
            app.getStateManager().attach(new LobbyState(myNick, true, false, null, 0));
        }

        private void joinServer(String addrStr) {
            stopScanning();
            String[] parts = addrStr.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : GAME_PORT;
            LemurUi.clearPage(guiNode);
            clearInputMappingsQuietly(inputManager);
            app.getStateManager().detach(this);
            app.getStateManager().attach(new LobbyState(myNick, false, false, host, port));
        }

        private void backToMenu() {
            stopScanning();
            LemurUi.clearPage(guiNode);
            clearInputMappingsQuietly(inputManager);
            forceMenuCursor(app);
            app.getStateManager().detach(this);
            app.getStateManager().attach(new MainMenuState());
        }

        private void stopScanning() {
            scanning = false;
            closeQuietly(discoverSocket);
        }

        @Override
        public void cleanup() {
            super.cleanup();
            stopScanning();
            clearInputMappingsQuietly(inputManager);
        }

        static class ServerEntry {
            final String ip, mapName, playerCount;
            final long pingMs;
            ServerEntry(String ip, String mapName, String playerCount, long pingMs) {
                this.ip = safeString(ip, "?:" + GAME_PORT);
                this.mapName = safeString(mapName, "?");
                this.playerCount = safeString(playerCount, "?");
                this.pingMs = Math.max(0L, pingMs);
            }
        }
    }

    // =========================================================================
    // ЛОББИ (без чата, только уведомления; прямая адресация для клиента)
    // =========================================================================
    static class LobbyState extends AbstractAppState {
        private SimpleApplication app;
        private Node guiNode;
        private AssetManager assetManager;
        private InputManager inputManager;
        private Camera cam;

        private String myNick;
        private final boolean isHost;
        private final boolean isSolo;
        private final String connectHost;
        private final int connectPort;

				private int lastW = -1;
				private int lastH = -1;

        private DatagramSocket socket;
        private volatile String hostAddress;
        private volatile int hostPort = GAME_PORT;
        private final List<String> players = new CopyOnWriteArrayList<>();
        private final AtomicBoolean searching = new AtomicBoolean(true);
        private final AtomicBoolean gameStarted = new AtomicBoolean(false);
        private Thread netThread;
        private volatile boolean joinAcknowledged = false;

        private BitmapText notificationText;
        private float notificationAlpha = 0f;
        private float notificationTimer = 0f;

        private BitmapText playersText, startHint, searchAnim;
        private final List<BitmapText> playerLineTexts = new ArrayList<>();
        private MenuButton[] mapButtons = new MenuButton[getLoadedMapCount()];
        private BitmapText mapLabel;
        private boolean mapSelectMode = false;
        private MenuButton mapContinueBtn, mapBackBtn;
        private final List<Spatial> mapPreviewItems = new ArrayList<>();
        private Geometry[] mapCardGeos = new Geometry[0];
        private float[] mapCardCx = new float[0], mapCardCy = new float[0], mapCardW = new float[0], mapCardH = new float[0];
        private int hoveredMapIndex = -1;
        private boolean chatSubmitConsumed = false;
        private Node chatNode;
        private BitmapText chatLogText, chatInputText, chatHintText;
        private final List<String> chatMessages = new CopyOnWriteArrayList<>();
        private final StringBuilder chatInput = new StringBuilder();
        private boolean chatFocused = false;
        private float animTimer = 0f;
        private int animDot = 0;

        private final List<InetSocketAddress> clients = new CopyOnWriteArrayList<>();
        private final Map<InetSocketAddress, String> clientNames = new ConcurrentHashMap<>();

        // флаг включения/отключения чёрных кубов
        private boolean cubesEnabled = true;
        private MenuButton cubesToggleBtn;
				
				private final Map<String, ColorRGBA> lobbyColors = new ConcurrentHashMap<>();

				private final List<Geometry> colorSwatches = new ArrayList<>();
				private final List<ColorRGBA> paletteColors = new ArrayList<>();

				private final List<Geometry> previewBalls = new ArrayList<>();
				private Node previewSnakeNode;
				private float previewAnimTime = 0f;

        private static String mapName(int idx) { return getMapInfo(idx).displayName; }
        private static ColorRGBA mapColor(int idx) { return getMapInfo(idx).accentColor; }

        public LobbyState(String nick, boolean isHost, boolean isSolo, String connectHost, int connectPort) {
            this.myNick = nick; this.isHost = isHost; this.isSolo = isSolo;
            this.connectHost = connectHost; this.connectPort = connectPort;
        }

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            if (invalidApplication(application, "Lobby.initialize")) return;
            app = (SimpleApplication) application;
            guiNode = app.getGuiNode();
            assetManager = app.getAssetManager();
            inputManager = app.getInputManager();
            cam = app.getCamera();
            if (missingRefs("Lobby.initialize", guiNode, assetManager, inputManager, cam)) return;

            try {
                if (myNick == null || myNick.isBlank()) myNick = getSystemUsername();
                if (app.getViewPort() != null) app.getViewPort().setBackgroundColor(BG);
                forceMenuCursor(app);
                MusicManager.play(assetManager, app.getRootNode(), "Sounds/theme/main1.ogg", musicVolume);

                if (!players.contains(myNick)) players.add(myNick);
                lobbyColors.put(myNick, safeColor(selectedSnakeColor, ACCENT));

                buildUI();
                setupInput();
                refreshUI();

                if (!isSolo) startNetwork();
            } catch (Exception e) {
                logSafe("Lobby.initialize", e);
            }
        }

				private String encodeColor(ColorRGBA c) {
						return c.r + "," + c.g + "," + c.b;
				}

				private ColorRGBA decodeColor(String s) {
						try {
								String[] p = s.split(",", -1);

								return new ColorRGBA(
												Float.parseFloat(p[0]),
												Float.parseFloat(p[1]),
												Float.parseFloat(p[2]),
												1f
								);

						} catch (Exception e) {
								return new ColorRGBA(0.15f, 0.9f, 0.3f, 1f);
						}
				}

                private String makeUniqueNick(String requested) {
                    String base = (requested == null || requested.isBlank()) ? "Player" : requested.trim();
                    base = base.replaceAll("[^A-Za-z\u0400-\u04FF0-9_]", "");
                    if (base.isEmpty()) base = "Player";
                    if (base.length() > 12) base = base.substring(0, 12);

                    String nick = base;
                    int n = 2;
                    while (players.contains(nick) || clientNames.containsValue(nick)) {
                        nick = base + "_" + n++;
                    }
                    return nick;
                }

				private String encodeAllColors() {
						StringBuilder sb = new StringBuilder();

						for (String nick : players) {
								ColorRGBA c = lobbyColors.getOrDefault(
												nick,
												new ColorRGBA(0.15f, 0.9f, 0.3f, 1f)
								);

								if (sb.length() > 0) sb.append(";");

								sb.append(nick)
												.append("=")
												.append(encodeColor(c));
						}

						return sb.toString();
				}

				private void applyColorsPacket(String data) {
						if (data == null || data.isBlank())
								return;

						String[] entries = data.split(";", -1);

						for (String entry : entries) {
								String[] kv = entry.split("=", -1);

								if (kv.length != 2)
										continue;

								lobbyColors.put(kv[0], decodeColor(kv[1]));
						}

						ColorRGBA myColor = lobbyColors.get(myNick);

						if (myColor != null) {
								selectedSnakeColor = myColor.clone();
								refreshColorPaletteSelection();
								refreshSnakePreviewColor();
						}
                        refreshUI();
				}

				private void rebuildUIAfterResize() {

						LemurUi.clearPage(guiNode);

						colorSwatches.clear();
						paletteColors.clear();
						previewBalls.clear();

						buildUI();

						refreshUI();
						refreshMapSelection();
						refreshColorPaletteSelection();
						refreshSnakePreviewColor();
				}

				private void broadcastColor(String nick, ColorRGBA color) {
						if (!isHost)
								return;

						broadcastToAll(
										"COLOR|" + nick + "|" + encodeColor(color)
						);
				}

				private void broadcastAllColors() {
						if (!isHost)
								return;

						broadcastToAll(
										"COLORS|" + encodeAllColors()
						);
				}

        private void buildUI() {
            if (mapSelectMode) {
                buildMapSelectionUI();
                return;
            }

            BitmapFont font = loadFont(assetManager);
            float W = cam.getWidth(), H = cam.getHeight();
            float S = uiScale(cam);
            float leftX = 40f * S;
            buildDimmedMenuBackdrop(assetManager, guiNode, cam, -8f);
            cubesToggleBtn = null;

            BitmapText title = new BitmapText(font);
            title.setSize(42 * S); title.setText(isSolo ? "ОДИНОЧНАЯ ИГРА" : "ИГРОВОЕ ЛОББИ");
            title.setColor(ACCENT);
            UiGrid.placeText(guiNode, "Text:LobbyTitle", title, leftX, H - 40, 0);
            guiNode.attachChild(title);

            playersText = new BitmapText(font);
            playersText.setSize(20 * S); playersText.setColor(TEXT);
            UiGrid.placeText(guiNode, "Text:Players", playersText, leftX, H - 120, 0);
            guiNode.attachChild(playersText);

            searchAnim = new BitmapText(font);
            searchAnim.setSize(16 * S); searchAnim.setColor(ACCENT2);
            UiGrid.placeText(guiNode, "Text:SearchAnim", searchAnim, leftX, 100, 0);
            guiNode.attachChild(searchAnim);

            startHint = new BitmapText(font);
            startHint.setSize(22 * S);
            UiGrid.placeText(guiNode, "Text:StartHint", startHint, leftX, 60, 0);
            guiNode.attachChild(startHint);

            notificationText = new BitmapText(font);
            notificationText.setSize(28);
            notificationText.setColor(new ColorRGBA(1f, 1f, 1f, 0f));
            notificationText.setText("");
            notificationText.setLocalTranslation(W/2f, H/2f + 60f, 0);
            guiNode.attachChild(notificationText);

            // Переключатель кубов перенесён в окно выбора карт, чтобы не перегружать лобби.
            if (!isSolo) buildLobbyChat(W, H);
            buildColorPalette(W, H);
            refreshColorPaletteSelection();
        }        private void buildMapSelectionUI() {
            clearPlayerListLines();
            playersText = null;
            BitmapFont font = loadFont(assetManager);
            float W = cam.getWidth(), H = cam.getHeight();
            float S = uiScale(cam);
            float margin = 42f * S;
            buildDimmedMenuBackdrop(assetManager, guiNode, cam, -8f);

            BitmapText title = new BitmapText(font);
            title.setSize(38f * S);
            title.setText("ВЫБОР КАРТЫ");
            title.setColor(ACCENT);
            UiGrid.placeText(guiNode, "Text:MapSelectTitle", title, margin, H - 42f * S, 3f);
            guiNode.attachChild(title);

            mapLabel = new BitmapText(font);
            mapLabel.setSize(18f * S);
            mapLabel.setColor(ACCENT3);
            mapLabel.setText("ВЫБРАНО: " + mapName(selectedMap));
            UiGrid.placeText(guiNode, "Text:MapLabel", mapLabel, margin, H - 82f * S, 3f);
            guiNode.attachChild(mapLabel);

            cubesToggleBtn = new MenuButton("КУБЫ НА КАРТЕ: " + (cubesEnabled ? "ВКЛ" : "ВЫКЛ"),
                    W - margin - 170f * S, H - 62f * S, 340f * S, 44f * S,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, cubesEnabled ? ACCENT : TEXT_DIM, assetManager, guiNode, 4f);

            int count = getLoadedMapCount();
            mapButtons = new MenuButton[count];
            mapCardGeos = new Geometry[count];
            mapCardCx = new float[count];
            mapCardCy = new float[count];
            mapCardW = new float[count];
            mapCardH = new float[count];
            mapPreviewItems.clear();

            final float baseCardW = Math.max(260f * S, Math.min(330f * S, (W - margin * 2f - 56f * S) / 3f));
            final float baseCardH = 220f * S;
            float gap = 28f * S;
            int cols = Math.max(1, Math.min(3, (int)((W - margin * 2f + gap) / (baseCardW + gap))));
            float totalW = cols * baseCardW + (cols - 1) * gap;
            float startX = W / 2f - totalW / 2f + baseCardW / 2f;
            float startY = H - 225f * S;

            for (int i = 0; i < count; i++) {
                int col = i % cols;
                int row = i / cols;
                float x = startX + col * (baseCardW + gap);
                float y = startY - row * (baseCardH + 34f * S);
                UiGrid.HitBox cardHit = UiGrid.placeExact(guiNode, "MapCard:" + i, x, y, baseCardW, baseCardH, 1f, cam);
                x = cardHit.cx();
                y = cardHit.cy();
                float cardW = cardHit.w;
                float cardH = cardHit.h;
                mapCardCx[i] = x; mapCardCy[i] = y; mapCardW[i] = cardW; mapCardH[i] = cardH;

                Geometry card = new Geometry("MapCard" + i, new Box(cardW/2f, cardH/2f, 0.4f));
                card.setMaterial(guiMat(assetManager, new ColorRGBA(0.05f,0.07f,0.15f,0.92f), true));
                card.setQueueBucket(RenderQueue.Bucket.Gui);
                card.setLocalTranslation(x, y, 1f);
                guiNode.attachChild(card);
                mapCardGeos[i] = card;
                mapPreviewItems.add(card);

                String preview = getMapInfo(i).previewImage;
                boolean loadedImg = false;
                if (preview != null && !preview.isBlank()) {
                    try {
                        Picture pic = new Picture("MapPreview" + i);
                        pic.setImage(assetManager, preview, true);
                        float imgW = cardW - 28f * S;
                        float imgH = 112f * S;
                        pic.setWidth(imgW);
                        pic.setHeight(imgH);
                        // Picture.setPosition принимает нижний левый угол, поэтому
                        // ставим превью внутрь верхней части карточки, а не под неё.
                        pic.setLocalTranslation(x - imgW / 2f, y + cardH / 2f - imgH - 18f * S, 2.6f);
                        pic.setQueueBucket(RenderQueue.Bucket.Gui);
                        guiNode.attachChild(pic);
                        mapPreviewItems.add(pic);
                        loadedImg = true;
                    } catch (Exception ignored) {}
                }
                if (!loadedImg) {
                    Geometry noImg = new Geometry("NoImg" + i, new Box((cardW-28f*S)/2f, 56f*S, 0.2f));
                    noImg.setMaterial(guiMat(assetManager, new ColorRGBA(0.10f,0.12f,0.18f,0.96f), true));
                    noImg.setLocalTranslation(x, y + 68f * S, 2f);
                    guiNode.attachChild(noImg);
                    mapPreviewItems.add(noImg);

                    BitmapText no = new BitmapText(font);
                    no.setSize(21f * S);
                    no.setText("NO IMG");
                    no.setColor(TEXT_DIM);
                    no.setLocalTranslation(x - no.getLineWidth()/2f, y + 76f * S, 3f);
                    guiNode.attachChild(no);
                    mapPreviewItems.add(no);
                }

                BitmapText name = new BitmapText(font);
                name.setSize(20f * S);
                name.setText(getMapInfo(i).displayName);
                name.setColor(mapColor(i));
                name.setLocalTranslation(x - name.getLineWidth()/2f, y - 42f * S, 3f);
                guiNode.attachChild(name);
                mapPreviewItems.add(name);

                MapRuntimeSettings ms = getMapInfo(i).settings;
                BitmapText meta = new BitmapText(font);
                meta.setSize(12f * S);
                meta.setText("Скорость " + ms.snakeSpeed + "  •  Еда " + ms.maxFood + "  •  " + ms.mode);
                meta.setColor(TEXT_DIM);
                meta.setLocalTranslation(x - cardW/2f + 18f * S, y - 70f * S, 3f);
                guiNode.attachChild(meta);
                mapPreviewItems.add(meta);

                BitmapText clickHint = new BitmapText(font);
                clickHint.setSize(11f * S);
                clickHint.setText("Нажми на карточку, чтобы выбрать");
                clickHint.setColor(TEXT_DIM);
                clickHint.setLocalTranslation(x - cardW/2f + 18f * S, y - 93f * S, 3f);
                guiNode.attachChild(clickHint);
                mapPreviewItems.add(clickHint);
            }
            refreshMapSelection();

            mapContinueBtn = new MenuButton("НАЧАТЬ МАТЧ", W - margin - 150f * S, 56f * S, 300f * S, 52f * S,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT, assetManager, guiNode, 4f);
            mapBackBtn = new MenuButton("НАЗАД В ЛОББИ", margin + 150f * S, 56f * S, 300f * S, 52f * S,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, DANGER, assetManager, guiNode, 4f);

            startHint = new BitmapText(font);
            startHint.setSize(16f * S);
            startHint.setText("Карта выбирается кликом по карточке  •  Enter запускает матч");
            startHint.setColor(TEXT_DIM);
            UiGrid.centerText(guiNode, "Text:MapHint", startHint, W/2f, 62f * S, 4f);
            guiNode.attachChild(startHint);

            notificationText = new BitmapText(font);
            notificationText.setSize(28f * S);
            notificationText.setColor(new ColorRGBA(1f, 1f, 1f, 0f));
            notificationText.setText("");
            notificationText.setLocalTranslation(W/2f, H/2f + 60f * S, 0);
            guiNode.attachChild(notificationText);
        }


        private void buildLobbyChat(float W, float H) {
            if (isSolo) return;
            BitmapFont font = loadFont(assetManager);
            chatNode = new Node("LobbyChat");

            float S = uiScale(cam);
            float chatW = Math.min(620f * S, W * 0.42f);
            float chatH = 205f * S;
            float cx = W - chatW/2f - 32f * S;
            float cy = 122f * S;

            Geometry bg = new Geometry("ChatBg", new Box(chatW/2f, chatH/2f, 0.4f));
            Material bm = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            bm.setColor("Color", new ColorRGBA(0f,0f,0f,0.58f));
            bm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            bg.setMaterial(bm);
            bg.setLocalTranslation(cx, cy, 2f);
            chatNode.attachChild(bg);

            chatHintText = new BitmapText(font);
            chatHintText.setSize(14f * S);
            chatHintText.setColor(ACCENT2);
            chatHintText.setText("ЧАТ ЛОББИ  •  T — писать, Enter — отправить");
            chatHintText.setLocalTranslation(cx - chatW/2f + 14f * S, cy + chatH/2f - 16f * S, 3f);
            chatNode.attachChild(chatHintText);

            chatLogText = new BitmapText(font);
            chatLogText.setSize(14f * S);
            chatLogText.setColor(TEXT);
            chatLogText.setLocalTranslation(cx - chatW/2f + 14f * S, cy + chatH/2f - 43f * S, 3f);
            chatNode.attachChild(chatLogText);

            chatInputText = new BitmapText(font);
            chatInputText.setSize(15f * S);
            chatInputText.setColor(ACCENT3);
            chatInputText.setLocalTranslation(cx - chatW/2f + 14f * S, cy - chatH/2f + 25f * S, 3f);
            chatNode.attachChild(chatInputText);

            guiNode.attachChild(chatNode);
            refreshChatUI();
        }

				private void buildColorPalette(float W, float H) {

						BitmapFont font = loadFont(assetManager);
                        float S = uiScale(cam);

						BitmapText colorTitle = new BitmapText(font);
						colorTitle.setSize(18f * S);
						colorTitle.setText("ЦВЕТ ЗМЕЙКИ");
						colorTitle.setColor(ACCENT2);

						paletteColors.clear();
						colorSwatches.clear();

						ColorRGBA[] colors = {
										new ColorRGBA(0.15f, 0.90f, 0.30f, 1f),
										new ColorRGBA(0.10f, 0.75f, 1.00f, 1f),
										new ColorRGBA(0.95f, 0.25f, 1.00f, 1f),
										new ColorRGBA(1.00f, 0.78f, 0.18f, 1f),
										new ColorRGBA(1.00f, 0.25f, 0.20f, 1f),
										new ColorRGBA(0.55f, 0.35f, 1.00f, 1f),

										new ColorRGBA(0.95f, 0.95f, 0.95f, 1f),
										new ColorRGBA(0.20f, 0.45f, 1.00f, 1f),
										new ColorRGBA(1.00f, 0.45f, 0.10f, 1f),
										new ColorRGBA(0.55f, 1.00f, 0.20f, 1f),
										new ColorRGBA(0.05f, 1.00f, 0.75f, 1f),
										new ColorRGBA(1.00f, 0.15f, 0.55f, 1f)
						};

						Collections.addAll(paletteColors, colors);

                        float size = 34f * S;
                        float gap = 12f * S;
                        int cols = 6;
                        float paletteW = cols * size + (cols - 1) * gap;
                        float startX = W - 32f * S - paletteW + size / 2f;
                        float startY = H - 125f * S;

                        // Цветовая палитра теперь полностью привязана к размеру окна:
                        // title, swatches и hitbox масштабируются вместе с остальным UI.
						colorTitle.setLocalTranslation(startX - size / 2f, H - 80f * S, 3f);
						guiNode.attachChild(colorTitle);

						for (int i = 0; i < paletteColors.size(); i++) {

								int row = i / cols;
								int col = i % cols;

								float x = startX + col * (size + gap);
								float y = startY - row * (size + gap);
								UiGrid.HitBox swatchHit = UiGrid.placeExact(guiNode, "ColorSwatch:" + i, x, y, size, size, 2.2f, cam);
								x = swatchHit.cx();
								y = swatchHit.cy();

								Geometry swatch = new Geometry(
												"ColorSwatch" + i,
												new Box(size / 2f, size / 2f, 0.4f)
								);

								Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
								mat.setColor("Color", paletteColors.get(i));

								swatch.setMaterial(mat);
								swatch.setQueueBucket(RenderQueue.Bucket.Gui);
								swatch.setLocalTranslation(x, y, 2.2f);
                                swatch.setUserData("hitSize", size * 1.35f);

								guiNode.attachChild(swatch);
								colorSwatches.add(swatch);
						}
				}

				private void buildSnakePreview(float W, float H) {

						BitmapFont font = loadFont(assetManager);

						BitmapText previewTitle = new BitmapText(font);
						previewTitle.setSize(16);
						previewTitle.setText("ПРЕВЬЮ");
						previewTitle.setColor(TEXT_DIM);
						previewTitle.setLocalTranslation(W - 420f, H - 245f, 3f);
						guiNode.attachChild(previewTitle);

						previewSnakeNode = new Node("PreviewSnakeNode");
						guiNode.attachChild(previewSnakeNode);

						previewBalls.clear();

						float baseX = W - 290f;
						float baseY = H - 310f;

						for (int i = 0; i < 5; i++) {

								Geometry ball = new Geometry(
												"PreviewSnakeBall" + i,
												new Sphere(18, 18, 16f - i * 1.2f)
								);

								Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
								mat.setColor("Color", selectedSnakeColor);

								ball.setMaterial(mat);
								ball.setQueueBucket(RenderQueue.Bucket.Gui);

								ball.setLocalTranslation(
												baseX - i * 36f,
												baseY,
												3f + i * 0.05f
								);

								previewSnakeNode.attachChild(ball);
								previewBalls.add(ball);
						}

						BitmapText hint = new BitmapText(font);
						hint.setSize(13);
						hint.setText("Цвет видят все игроки");
						hint.setColor(TEXT_DIM);
						hint.setLocalTranslation(W - 420f, H - 360f, 3f);
						guiNode.attachChild(hint);
				}

				private void refreshSnakePreviewColor() {

						ColorRGBA c = lobbyColors.getOrDefault(myNick, selectedSnakeColor);

						for (int i = 0; i < previewBalls.size(); i++) {

								Geometry ball = previewBalls.get(i);

								float factor = Math.max(0.55f, 1f - i * 0.08f);

								ColorRGBA partColor = new ColorRGBA(
												c.r * factor,
												c.g * factor,
												c.b * factor,
												1f
								);

								ball.getMaterial().setColor("Color", partColor);
						}
				}

				private void refreshColorPaletteSelection() {

						for (int i = 0; i < colorSwatches.size(); i++) {

								Geometry swatch = colorSwatches.get(i);
								ColorRGBA pc = paletteColors.get(i);

								boolean selected =
												Math.abs(pc.r - selectedSnakeColor.r) < 0.02f &&
												Math.abs(pc.g - selectedSnakeColor.g) < 0.02f &&
												Math.abs(pc.b - selectedSnakeColor.b) < 0.02f;

								if (selected) {
										swatch.setLocalScale(1.22f, 1.22f, 1f);
								} else {
										swatch.setLocalScale(1f, 1f, 1f);
								}
						}
				}

				private boolean handleColorPaletteClick(float mx, float my) {

						for (int i = 0; i < colorSwatches.size(); i++) {

								Geometry swatch = colorSwatches.get(i);
								Vector3f p = swatch.getLocalTranslation();

								Float hit = swatch.getUserData("hitSize");
								float size = hit != null ? hit : 42f * uiScale(cam);

								if (mx >= p.x - size / 2f && mx <= p.x + size / 2f &&
										my >= p.y - size / 2f && my <= p.y + size / 2f) {

										selectedSnakeColor = paletteColors.get(i).clone();
										lobbyColors.put(myNick, selectedSnakeColor.clone());

										refreshColorPaletteSelection();
										refreshSnakePreviewColor();
                                        refreshUI();

										if (isHost) {
												broadcastColor(myNick, selectedSnakeColor);
										} else if (!isSolo) {
												sendToHost("COLOR_REQ|" + myNick + "|" + encodeColor(selectedSnakeColor));
										}

										saveSettings(null);
										return true;
								}
						}

						return false;
				}

        private void clearPlayerListLines() {
            for (BitmapText line : playerLineTexts) {
                if (line != null) line.removeFromParent();
            }
            playerLineTexts.clear();
        }

        private void rebuildColoredPlayerList() {
            clearPlayerListLines();
            if (mapSelectMode) return;
            if (playersText == null || guiNode == null) return;

            playersText.setText("СПИСОК ИГРОКОВ:");
            BitmapFont font = loadFont(assetManager);
            float x = playersText.getLocalTranslation().x;
            float S = uiScale(cam);
            // Строки игроков опущены ниже от заголовка, чтобы список не слипался
            // с надписью "СПИСОК ИГРОКОВ" при любом масштабе окна.
            float y = playersText.getLocalTranslation().y - 42f * S;
            for (int i = 0; i < players.size(); i++) {
                String nick = players.get(i);
                BitmapText line = new BitmapText(font);
                line.setSize(18f * S);
                line.setText((i + 1) + ". " + nick + (nick.equals(myNick) ? " (ВЫ)" : ""));
                ColorRGBA c = lobbyColors.get(nick);
                line.setColor(c != null ? c : TEXT);
                line.setLocalTranslation(x, y - i * 24f * S, playersText.getLocalTranslation().z);
                guiNode.attachChild(line);
                playerLineTexts.add(line);
            }
        }

        private void showNotification(String msg) {
            notificationText.setText(msg);
            float W = cam.getWidth();
            notificationText.setLocalTranslation(W/2f - notificationText.getLineWidth()/2f,
                    notificationText.getLocalTranslation().y, 0);
            notificationAlpha = 1f;
            notificationTimer = 4.0f;
        }

        private void refreshUI() {
            if (mapLabel != null) mapLabel.setText((mapSelectMode ? "ВЫБРАНО: " : "ВЫБРАННАЯ КАРТА: ") + mapName(selectedMap));
            if (mapSelectMode) {
                clearPlayerListLines();
                return;
            }
            rebuildColoredPlayerList();

            if (isSolo) {
                startHint.setText("[ ENTER ] НАЧАТЬ ИГРУ");
                startHint.setColor(ACCENT3);
            } else if (isHost) {
                if (players.size() >= 2) {
                    startHint.setText("[ ENTER ] ЗАПУСТИТЬ МАТЧ");
                    startHint.setColor(ACCENT);
                } else {
                    startHint.setText("ОЖИДАНИЕ ИГРОКОВ...");
                    startHint.setColor(TEXT_DIM);
                }
            } else {
                startHint.setText("ОЖИДАНИЕ ХОСТА...");
                startHint.setColor(TEXT_DIM);
            }
        }        private void refreshMapSelection() {
            for (int i = 0; i < mapCardGeos.length; i++) {
                Geometry card = mapCardGeos[i];
                if (card == null) continue;
                boolean selected = (i == selectedMap);
                boolean hover = (i == hoveredMapIndex);
                ColorRGBA c;
                if (selected) c = new ColorRGBA(0.12f, 0.24f, 0.52f, 0.98f);
                else if (hover) c = new ColorRGBA(0.10f, 0.16f, 0.34f, 0.96f);
                else c = new ColorRGBA(0.05f, 0.07f, 0.15f, 0.92f);
                card.getMaterial().setColor("Color", c);
            }
            for (int i = 0; i < mapButtons.length; i++) {
                if (mapButtons[i] == null) continue;
                boolean selected = (i == selectedMap);
                if (selected) {
                    mapButtons[i].setBgNormal(new ColorRGBA(0.12f, 0.22f, 0.50f, 1f));
                    mapButtons[i].setAccentColor(new ColorRGBA(1f, 1f, 1f, 1f));
                } else {
                    mapButtons[i].setBgNormal(BTN_NORMAL);
                    mapButtons[i].setAccentColor(mapColor(i));
                }
            }
            if (mapLabel != null) mapLabel.setText("ВЫБРАНО: " + mapName(selectedMap));
        }

        private int findMapCardAt(float mx, float my) {
            for (int i = 0; i < mapCardCx.length; i++) {
                if (mx >= mapCardCx[i] - mapCardW[i] / 2f && mx <= mapCardCx[i] + mapCardW[i] / 2f &&
                    my >= mapCardCy[i] - mapCardH[i] / 2f && my <= mapCardCy[i] + mapCardH[i] / 2f) return i;
            }
            return -1;
        }


        private String encChat(String s) {
            return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));
        }

        private String decChat(String s) {
            try { return new String(Base64.getDecoder().decode(s), StandardCharsets.UTF_8); }
            catch (Exception e) { return ""; }
        }

        private void addChatLine(String nick, String msg) {
            if (msg == null || msg.isBlank()) return;
            chatMessages.add(nick + ": " + msg);
            while (chatMessages.size() > 7) chatMessages.remove(0);
            refreshChatUI();
        }

        private void refreshChatUI() {
            if (isSolo || chatLogText == null || chatInputText == null) return;
            StringBuilder log = new StringBuilder();
            for (String line : chatMessages) log.append(line).append("\n");
            chatLogText.setText(log.toString());
            chatInputText.setText((chatFocused ? "> " : "T: ") + chatInput.toString() + (chatFocused ? "_" : ""));
            if (chatHintText != null) chatHintText.setColor(chatFocused ? ACCENT3 : ACCENT2);
        }

        private void sendChatMessage() {
            if (isSolo) return;
            String msg = chatInput.toString().trim();
            chatInput.setLength(0);
            chatFocused = false;
            refreshChatUI();
            if (msg.isBlank()) return;
            addChatLine(myNick, msg);
            String packet = "CHAT|" + encChat(myNick) + "|" + encChat(msg);
            if (isHost) broadcastToAll(packet);
            else if (!isSolo) sendToHost(packet);
        }

        private void openMapSelection() {
            if (!isSolo && !isHost) return;
            mapSelectMode = true;
            chatFocused = false;
            LemurUi.clearPage(guiNode);
            clearPlayerListLines();
            playersText = null;
            colorSwatches.clear();
            paletteColors.clear();
            previewBalls.clear();
            hoveredMapIndex = -1;
            buildUI();
            LemurUi.playPageFade(guiNode, assetManager, cam);
        }

        private void closeMapSelection() {
            mapSelectMode = false;
            LemurUi.clearPage(guiNode);
            clearPlayerListLines();
            playersText = null;
            colorSwatches.clear();
            paletteColors.clear();
            previewBalls.clear();
            hoveredMapIndex = -1;
            buildUI();
            LemurUi.playPageFade(guiNode, assetManager, cam);
            refreshUI();
            refreshColorPaletteSelection();
            refreshSnakePreviewColor();
        }

        private void setupInput() {
						inputManager.addMapping("LMove",
										new MouseAxisTrigger(MouseInput.AXIS_X, false),
										new MouseAxisTrigger(MouseInput.AXIS_X, true),
										new MouseAxisTrigger(MouseInput.AXIS_Y, false),
										new MouseAxisTrigger(MouseInput.AXIS_Y, true)
						);

						refreshColorPaletteSelection();

						inputManager.addListener((AnalogListener)(n, v, t) -> {

								Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
                if (mp == null) return;

								float mx = mp.x;
								float my = mp.y;

                                int newHover = mapSelectMode ? findMapCardAt(mx, my) : -1;
                                if (newHover != hoveredMapIndex) {
                                    hoveredMapIndex = newHover;
                                    refreshMapSelection();
                                }

								if (cubesToggleBtn != null) {
										cubesToggleBtn.updateHover(mx, my);
								}

								for (MenuButton btn : mapButtons) {
										if (btn != null) btn.updateHover(mx, my);
								}
                                if (mapContinueBtn != null) mapContinueBtn.updateHover(mx, my);
                                if (mapBackBtn != null) mapBackBtn.updateHover(mx, my);

								for (Geometry swatch : colorSwatches) {
										Vector3f p = swatch.getLocalTranslation();

										boolean hover =
														mx >= p.x - 22f && mx <= p.x + 22f &&
														my >= p.y - 22f && my <= p.y + 22f;

										if (hover) {
												swatch.setLocalScale(1.15f, 1.15f, 1f);
										}
								}

								refreshColorPaletteSelection();

						}, "LMove");
					
            inputManager.addMapping("LClick", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
            inputManager.addMapping("LSend", new KeyTrigger(KeyInput.KEY_RETURN));
            inputManager.addMapping("LEsc", new KeyTrigger(KeyInput.KEY_ESCAPE));

            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p) return;
                if (chatSubmitConsumed) { chatSubmitConsumed = false; return; }
                if (chatFocused) return;
                if ("LSend".equals(n)) {
                    if (mapSelectMode) startGame();
                    else if (isSolo || (isHost && players.size() >= 2)) openMapSelection();
                }
                if ("LEsc".equals(n)) {
                    if (mapSelectMode) closeMapSelection();
                    else backToMenu();
                }
            }, "LSend", "LEsc");

            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p) return;
                Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
                if (mp == null) return;
                if (mapSelectMode) {
                    if (mapContinueBtn != null && mapContinueBtn.isHit(mp.x, mp.y)) { startGame(); return; }
                    if (mapBackBtn != null && mapBackBtn.isHit(mp.x, mp.y)) { closeMapSelection(); return; }
                    if (cubesToggleBtn != null && cubesToggleBtn.isHit(mp.x, mp.y)) {
                        cubesEnabled = !cubesEnabled;
                        cubesToggleBtn.setText("КУБЫ НА КАРТЕ: " + (cubesEnabled ? "ВКЛ" : "ВЫКЛ"));
                        cubesToggleBtn.setAccentColor(cubesEnabled ? ACCENT : TEXT_DIM);
                        return;
                    }
                    int clickedMap = findMapCardAt(mp.x, mp.y);
                    if (clickedMap >= 0) {
                        if (isHost || isSolo) {
                            selectedMap = clampMapIndex(clickedMap);
                            refreshMapSelection();
                            if (isHost) broadcastMapSelection();
                            refreshUI();
                        } else {
                            sendToHost("MAP_REQ|" + clickedMap);
                        }
                        return;
                    }
                }
								
								// выбор цвета змейки
								if (handleColorPaletteClick(mp.x, mp.y)) {
										return;
								}
            }, "LClick");

            inputManager.addRawInputListener(new com.jme3.input.RawInputListener() {
                @Override public void onKeyEvent(com.jme3.input.event.KeyInputEvent evt) {
                    if (!evt.isPressed() || mapSelectMode || isSolo) return;
                    int code = evt.getKeyCode();
                    if (!chatFocused && code == KeyInput.KEY_T) {
                        chatFocused = true;
                        refreshChatUI();
                        return;
                    }
                    if (!chatFocused) return;
                    if (code == KeyInput.KEY_RETURN) { chatSubmitConsumed = true; sendChatMessage(); return; }
                    if (code == KeyInput.KEY_ESCAPE) { chatFocused = false; refreshChatUI(); return; }
                    if (code == KeyInput.KEY_BACK) {
                        if (chatInput.length() > 0) chatInput.deleteCharAt(chatInput.length() - 1);
                        refreshChatUI();
                        return;
                    }
                    char ch = evt.getKeyChar();
                    if (ch != 0 && !Character.isISOControl(ch) && chatInput.length() < 80) {
                        chatInput.append(ch);
                        refreshChatUI();
                    }
                }
                @Override public void beginInput() {}
                @Override public void endInput() {}
                @Override public void onMouseMotionEvent(com.jme3.input.event.MouseMotionEvent evt) {}
                @Override public void onMouseButtonEvent(com.jme3.input.event.MouseButtonEvent evt) {}
                @Override public void onJoyAxisEvent(com.jme3.input.event.JoyAxisEvent evt) {}
                @Override public void onJoyButtonEvent(com.jme3.input.event.JoyButtonEvent evt) {}
                @Override public void onTouchEvent(com.jme3.input.event.TouchEvent evt) {}
            });
        }

        private void startNetwork() {
            try { socket = new DatagramSocket(isHost ? GAME_PORT : 0); socket.setSoTimeout(200); }
            catch (Exception e) { app.enqueue(() -> showNotification("Ошибка сокета")); return; }
            netThread = new Thread(isHost ? this::hostLoop :
                    (connectHost != null && !connectHost.isEmpty() ? this::clientDirectLoop : this::clientDiscoverLoop),
                    isHost ? "HostNet" : "ClientNet");
            netThread.setDaemon(true);
            netThread.start();
        }

        private void hostLoop() {
            byte[] buf = new byte[1024];
            DatagramSocket broadcastSock = null;
            try { broadcastSock = new DatagramSocket(BROADCAST_PORT); broadcastSock.setSoTimeout(200); }
            catch (Exception e) { /* */ }
            while (!gameStarted.get() && searching.get()) {
                if (broadcastSock != null) {
                    try {
                        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                        broadcastSock.receive(pkt);
                        String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                        if ("DISCOVER".equals(msg.trim())) {
                            String reply = "HOST_HERE|" + GAME_PORT + "|" + mapName(selectedMap) + "|" + players.size() + "/4";
                            byte[] rb = reply.getBytes(StandardCharsets.UTF_8);
                            broadcastSock.send(new DatagramPacket(rb, rb.length, pkt.getAddress(), pkt.getPort()));
                        }
                    } catch (SocketTimeoutException ignore) {}
                    catch (Exception e) {}
                }
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    handleHostMessage(msg, new InetSocketAddress(pkt.getAddress(), pkt.getPort()));
                } catch (SocketTimeoutException ignore) {}
                catch (Exception e) {}
            }
            if (broadcastSock != null) broadcastSock.close();
        }

        private void handleHostMessage(String msg, InetSocketAddress from) {
            if (msg == null || msg.isBlank() || from == null) return;
            String[] p = msg.split("\\|", -1);
            switch (p[0]) {
								case "JOIN":
										if (players.size() < 4 && !gameStarted.get()) {

												String requestedNick = p.length > 1 ? p[1] : "Player";
                                                    String nick = clientNames.get(from);
                                                    if (nick == null) {
                                                        nick = makeUniqueNick(requestedNick);
                                                        clientNames.put(from, nick);
                                                    }

                                                    if (!players.contains(nick))
                                                            players.add(nick);

                                                    if (!clients.contains(from))
                                                            clients.add(from);

                                                    sendToClient("NICK|" + nick, from);

												if (p.length > 2) {
														lobbyColors.put(nick, decodeColor(p[2]));
												} else {
														lobbyColors.putIfAbsent(nick, new ColorRGBA(0.15f, 0.9f, 0.3f, 1f));
												}

												lobbyColors.putIfAbsent(myNick, selectedSnakeColor.clone());

												String notif = "Игрок " + nick + " присоединился";

												app.enqueue(() -> showNotification(notif));

												broadcastToAll("NOTIF|" + notif);
												broadcastLobby();
												broadcastAllColors();

												sendToClient("MAP|" + selectedMap, from);
												sendToClient("COLORS|" + encodeAllColors(), from);

												app.enqueue(this::refreshUI);
										}
										break;
								case "COLOR_REQ":
										if (p.length > 2) {

												String nick = p[1];
												ColorRGBA color = decodeColor(p[2]);

												lobbyColors.put(nick, color);

												broadcastColor(nick, color);
												broadcastAllColors();

												app.enqueue(() -> {
														if (nick.equals(myNick)) {
																selectedSnakeColor = color.clone();
																refreshColorPaletteSelection();
																refreshSnakePreviewColor();
														}
                                                        refreshUI();
												});
										}
										break;
                case "MAP_REQ":
                    if (p.length > 1) {
                        try { selectedMap = clampMapIndex(Integer.parseInt(p[1])); } catch (Exception ignore) {}
                        broadcastToAll("MAP|" + selectedMap);
                        app.enqueue(this::refreshUI);
                    }
                    break;
                case "CHAT":
                    if (p.length >= 3) {
                        String nick = decChat(p[1]);
                        String text = decChat(p[2]);
                        String packet = "CHAT|" + encChat(nick) + "|" + encChat(text);
                        app.enqueue(() -> addChatLine(nick, text));
                        broadcastToAll(packet);
                    }
                    break;
            }
        }

        private void broadcastLobby() { broadcastToAll("LOBBY|" + String.join("|", players)); }
        private void broadcastMapSelection() { broadcastToAll("MAP|" + selectedMap); }

        void broadcastToAll(String msg) {
            if (!isHost || socket == null || msg == null) return;
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            for (InetSocketAddress c : snapshot(clients)) {
                if (c == null) continue;
                try { socket.send(new DatagramPacket(b, b.length, c)); }
                catch (Exception e) { logSafe("Lobby.broadcastToAll", e); }
            }
        }

        private void sendToClient(String msg, InetSocketAddress c) {
            if (!isHost || socket == null || msg == null || c == null) return;
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            try { socket.send(new DatagramPacket(b, b.length, c)); }
            catch (Exception e) { logSafe("Lobby.sendToClient", e); }
        }

        private void clientDirectLoop() {
            hostAddress = connectHost;
            hostPort = connectPort;
            float retryTimer = 0f;
            final float retryInterval = 0.8f;
            byte[] buf = new byte[2048];
            while (!gameStarted.get() && searching.get()) {
                retryTimer -= 0.05f;
                if (!joinAcknowledged && retryTimer <= 0f) {
                    sendToHost("JOIN|" + myNick + "|" + encodeColor(selectedSnakeColor));
                    retryTimer = retryInterval;
                }
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    app.enqueue(() -> handleClientMessage(msg));
                } catch (SocketTimeoutException ignore) {}
                catch (Exception e) {}
            }
        }

        private void clientDiscoverLoop() {
            // запасной вариант (не используется, т.к. адрес всегда передаётся)
            try { Thread.sleep(500); } catch (InterruptedException e) {}
            // если адрес не получен, возврат
            app.enqueue(() -> showNotification("Не удалось подключиться"));
        }

        private void handleClientMessage(String msg) {
            if (msg == null || msg.isBlank()) return;
            String[] p = msg.split("\\|", -1);
            switch (p[0]) {
                    case "NICK":
                        if (p.length > 1 && !p[1].isBlank()) {
                            String oldNick = myNick;
                            myNick = p[1];
                            if (!oldNick.equals(myNick)) {
                                ColorRGBA c = lobbyColors.remove(oldNick);
                                lobbyColors.put(myNick, c != null ? c : selectedSnakeColor.clone());
                            }
                        }
                        break;
                case "LOBBY":
                    joinAcknowledged = true;
                    players.clear();
                    for (int i=1;i<p.length;i++) if (!p[i].isEmpty()) players.add(p[i]);
                    refreshUI();
                    if (players.contains(myNick) && hostAddress != null) showNotification("Подключено к хосту");
                    break;
                case "NOTIF":
                    if (p.length > 1) showNotification(p[1]);
                    break;
                case "MAP":
                    if (p.length>1) { try { selectedMap = clampMapIndex(Integer.parseInt(p[1])); refreshUI(); refreshMapSelection(); } catch (Exception ignore) {} }
                    break;
								case "START":
										if (p.length > 1) {
												try {
														selectedMap = clampMapIndex(Integer.parseInt(p[1]));
												} catch (Exception ignore) {}
										}

										if (p.length > 3) {
												cubesEnabled = "1".equals(p[2]);
												applyColorsPacket(p[3]);
										} else if (p.length > 2) {
												// Совместимость со старым форматом START|map|colors
												applyColorsPacket(p[2]);
										}

										gameStarted.set(true);
										app.enqueue(this::launchGame);
										break;
								case "COLOR":
										if (p.length > 2) {

												String nick = p[1];
												ColorRGBA color = decodeColor(p[2]);

												lobbyColors.put(nick, color);

												if (nick.equals(myNick)) {
														selectedSnakeColor = color.clone();
														refreshColorPaletteSelection();
														refreshSnakePreviewColor();
												}
										}
                                                refreshUI();
										break;
								case "COLORS":
										if (p.length > 1) {
												applyColorsPacket(p[1]);
										}
										break;
                case "CHAT":
                    if (p.length >= 3) addChatLine(decChat(p[1]), decChat(p[2]));
                    break;
            }
        }

        private void sendToHost(String msg) {
            if (hostAddress == null || socket == null || msg == null) return;
            try {
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(b, b.length, InetAddress.getByName(hostAddress), hostPort));
            } catch (Exception e) { logSafe("Lobby.sendToHost", e); }
        }

        private void startGame() {
            gameStarted.set(true);
						broadcastAllColors();
						broadcastToAll("START|" + selectedMap + "|" + (cubesEnabled ? 1 : 0) + "|" + encodeAllColors());
            launchGame();
        }

				private void launchGame() {
						searching.set(false);

						List<String> playerList = new ArrayList<>(players);
						int myIdx = playerList.indexOf(myNick);

						LemurUi.clearPage(guiNode);
						clearInputMappingsQuietly(inputManager);
						if (app != null && app.getInputManager() != null) app.getInputManager().setCursorVisible(false);

						DatagramSocket gameSocket = this.socket;
						this.socket = null;

						app.getStateManager().detach(this);

						app.getStateManager().attach(
										new GameState(
														myNick,
														playerList,
														myIdx,
														isSolo || playerList.size() == 1,
														isHost,
														gameSocket,
														hostAddress,
														hostPort,
														clients,
														selectedMap,
														cubesEnabled,
														new HashMap<>(lobbyColors),
														clientNames
										)
						);
				}

        private void backToMenu() {
            searching.set(false);
            closeQuietly(socket);
            LemurUi.clearPage(guiNode);
            clearInputMappingsQuietly(inputManager);
            forceMenuCursor(app);
            app.getStateManager().detach(this);
            app.getStateManager().attach(new MainMenuState());
        }

        @Override
        public void update(float tpf) {
            tpf = safeTpf(tpf);
            if (tpf <= 0f || missingRefs("Lobby.update", app, cam)) return;
            forceMenuCursor(app);
            animTimer += tpf;
            if (animTimer > 0.5f) {
                animTimer = 0; animDot = (animDot+1)%4;
                String dots = ".".repeat(animDot);
                if (!isSolo) {
                    if (searchAnim != null) {
                        if (!isHost && hostAddress == null) searchAnim.setText("Подключение" + dots);
                        else if (isHost) searchAnim.setText("IP: " + getLocalIP() + "  Ждём" + dots);
                        else searchAnim.setText("Подключено к " + hostAddress + dots);
                    }
                } else {
                    if (searchAnim != null) searchAnim.setText("Готово к игре" + dots);
                }
            }

            if (notificationTimer > 0f) {
                notificationTimer -= tpf;
                if (notificationTimer <= 0f) notificationAlpha = 0f;
                else notificationAlpha = Math.min(1f, notificationTimer / 1.0f);
                ColorRGBA c = new ColorRGBA(1f, 1f, 1f, notificationAlpha);
                if (notificationText != null) notificationText.setColor(c);
            }
						
						if (lastW == -1 || lastH == -1) {
								lastW = cam.getWidth();
								lastH = cam.getHeight();
						}

						if (cam.getWidth() != lastW || cam.getHeight() != lastH) {

								lastW = cam.getWidth();
								lastH = cam.getHeight();

								rebuildUIAfterResize();
						}
						
                        // Превью змейки отключено по запросу: в лобби остаётся только выбор цвета.
        }

				private void updateSnakePreview(float tpf) {

						if (previewBalls.isEmpty())
								return;

						previewAnimTime += tpf;

						float W = cam.getWidth();
						float H = cam.getHeight();

						float baseX = W - 290f;
						float baseY = H - 310f;

						for (int i = 0; i < previewBalls.size(); i++) {

								Geometry ball = previewBalls.get(i);

								float wave = FastMath.sin(previewAnimTime * 4f - i * 0.55f) * 8f;
								float bob  = FastMath.sin(previewAnimTime * 3f + i * 0.7f) * 4f;

								ball.setLocalTranslation(
												baseX - i * 36f,
												baseY + wave + bob,
												3f + i * 0.05f
								);

								float scale = 1f + FastMath.sin(previewAnimTime * 3f - i * 0.4f) * 0.05f;
								ball.setLocalScale(scale, scale, 1f);
						}
				}

        private String getLocalIP() {
            try { return InetAddress.getLocalHost().getHostAddress(); } catch (Exception e) { return "?"; }
        }

        @Override
        public void cleanup() {
            super.cleanup();
            searching.set(false);
            closeQuietly(socket);
        }
    }

    // =========================================================================
    // ИГРОВОЕ СОСТОЯНИЕ (без изменений в части чата, т.к. его там и не было) класс GameState
    // =========================================================================
    static class GameState extends AbstractAppState {

        private SimpleApplication app;
        private Node rootNode, guiNode;
        private AssetManager assetManager;
        private InputManager inputManager;
        private Camera cam;
        private AppStateManager stateManager;
        private BulletAppState bulletAppState;
        private float exitTimer = 8f;
        private Node gameoverNode;
        private boolean gameoverUIActive = false;
        private MenuButton goPlayAgainBtn, goMenuBtn;

				private final Map<String, ColorRGBA> playerColors;

        // Пауза
        private boolean pauseActive = false;
        private Node pauseNode;
				private MenuButton pauseResumeBtn, pauseSettingsBtn, pauseMenuBtn, pauseLobbyBtn;

				private boolean pauseSettingsOpen = false;
				private Node pauseSettingsNode;
				private MenuButton pauseSettingsCloseBtn;

				private int pauseActiveSettingsTab = 0;

				private BitmapText pauseSfxVal, pauseMusicVal;
				private VolumeSlider pauseSfxSlider, pauseMusicSlider;

				private MenuButton pauseTabMainBtn, pauseTabGraphicsBtn;

				private MenuButton pauseBtnShadows, pauseBtnParticles, pauseBtnFog, pauseBtnBloom;
				private MenuButton pauseBtnPost, pauseBtnLights, pauseBtnWater, pauseBtnTerrain;
				private MenuButton pauseBtnLowPoly, pauseBtnNames;
        private boolean pauseInputRegistered = false;
        private boolean gameoverInputRegistered = false;
        private boolean rebuildingGuiForResize = false;

        // Рывок  (увеличена длительность с 0.18 до 0.55 — рывок теперь ощутимый)
        private float dashCooldown = 0f;
        private static final float DASH_COOLDOWN_MAX = 4f;
        private static final float DASH_SPEED_MULT   = 5.0f;   // немного увеличен множитель
        private static final float DASH_DURATION     = 0.55f;  // было 0.18f
        private float dashTimer = 0f;
        private BitmapText dashCooldownText;
        private Geometry dashCircleBg, dashCircleFill;
        private BitmapText dashShiftText;

				private ColorRGBA baseGridColor = new ColorRGBA(0f, 0.10f, 0f, 0.88f);  // из buildTerrainGrid

        // Читкод KOPRFDC
        private static final int[] CHEAT_CODE = { KeyInput.KEY_K, KeyInput.KEY_O, KeyInput.KEY_P,
                KeyInput.KEY_R, KeyInput.KEY_F, KeyInput.KEY_D, KeyInput.KEY_C };
        private int cheatCodeIndex = 0;
        private boolean bordersRemoved = false;
        private static final int[] FREE_CAM_CHEAT_CODE = { KeyInput.KEY_K, KeyInput.KEY_I, KeyInput.KEY_O,
                KeyInput.KEY_R, KeyInput.KEY_T, KeyInput.KEY_D, KeyInput.KEY_C };
        private int freeCamCheatIndex = 0;
        private boolean freeCameraMode = false;

        private final String myNick;
        private final List<String> allPlayers;
        private final int myIndex;
        private final boolean solo;
        private final int mapIndex; // 0=Зелёная, 1=Пустыня, 2=Ямы
        private final boolean cubesEnabled;
        private final MapRuntimeSettings mapSettings;
        private final LoadedMapInfo loadedMapInfo;
        private ExternalMapDef mapDef;
        private MapContext mapCtx;
        private float effectiveSnakeSpeed = 8f;
        private float effectiveTurnSpeed = 2.8f;
        private int effectiveMaxFood = 18;

				private int lastW = -1;
				private int lastH = -1;

        private final List<SnakePlayer> snakes = new ArrayList<>();
        private Node foodNode, wallNode, cloudNode, worldNode, cubeNode;
        private Material cloudMaterial;
				private Node gridNode;
				private ColorRGBA originalFloorColor = new ColorRGBA(0.25f, 0.48f, 0.18f, 1f); // по умолчанию зелёный (карта 0)
				private Geometry terrainFloorGeo;   // пол карты 0 (рельеф)
				private Geometry flatFloorGeo;      // пол карт 1,2 (плоский box)
				private final List<Geometry> gridLines = new ArrayList<>();
				// private Material gridMaterial;
				// private ColorRGBA originalGridColor = null;
        private Node pitNode; // ямы со шипами (карта 2 Ямы с шипами)
        // Список кактусов для физики разрушения (карта 1 Пустыня)
        private final List<CactusData> cacti = new ArrayList<>();

        private final List<FoodItem> foodItems = new ArrayList<>();
        private final Set<Integer> eatenFoodIds = ConcurrentHashMap.newKeySet();

				// счётчик ID для шариков
				private int ballIdCounter = 0;

        // Враги-кубы
        private final List<BlackCube> blackCubes = new ArrayList<>();
        private int cubeIdCounter = 0;
        private float cubeNetTimer  = 0f;
        private static final float CUBE_NET_INTERVAL = 0.05f; // (20 Hz) 5 раз в секунду
        private static final int   MAX_BLACK_CUBES   = 5;
        private static final int   CUBE_SHRINK_AMOUNT = 6;

        // Игровые треки отключены: используется только меню-музыка через MusicManager

        // Звуки
        private final AudioNode[] eatSounds = new AudioNode[4];
        private int eatSoundIndex = 0;
        private AudioNode mmmSound, startSound, deathSound, chitSound, cubeRollSound;
        private AudioNode rainSound; // Sounds/inv/Rain1.ogg

        // HUD
        private final List<BitmapText> huds = new ArrayList<>();
        private BitmapText centerMsg, gameTimerText;
        private Node playerTabNode;
        private BitmapText playerTabText;
        private boolean playerTabVisible = false;
        private Geometry netStatsBg;
        private BitmapText netStatsText;
        private float fpsSmooth = 0f;
        private float fpsSum = 0f;
        private int fpsFrames = 0;
        private float fpsHudTimer = 0f;
        private long serverPingMs = 0L;
        private long pingSeq = 0L;
        private float pingTimer = 0f;
        private final Map<Long, Long> pendingPings = new ConcurrentHashMap<>();
        private float centerMsgTimer;
        private boolean gameOver = false;

        // Спектатор
        private boolean spectating = false;
        private int spectateTarget = 0;

        // Сеть
        private DatagramSocket socket;
        private final boolean isHost;
        private final String hostAddress;
        private final int hostPort;
        private final List<InetSocketAddress> clients;
        private Thread netRecvThread;
        private final AtomicBoolean netRunning = new AtomicBoolean(true);
        private volatile long lastNetPacketMs = System.currentTimeMillis();
        private float netSendTimer = 0f;
        private static final float NET_SEND_INTERVAL = 0.05f; // 20Hz: меньше трафик и стабильнее при высоком пинге
        private int lastSentInputMask = -1;
        private int foodIdCounter = 0;

				private final ConcurrentHashMap<InetSocketAddress, Long> clientLastSeen = new ConcurrentHashMap<>();
				private static final float CLIENT_TIMEOUT = 5.0f; // секунд неактивности
				private float clientTimeoutCheckTimer = 0f;
				private final Map<InetSocketAddress, Integer> clientIndexMap = new ConcurrentHashMap<>();

				private final Map<InetSocketAddress, String> clientNames;

        // Таймер игры (для ивентов)
        private float gameTime = 0f;

        // Пустыня
				private float defaultFogDistance = 80f;
				private float defaultFogDensity = 0.004f;
				private ColorRGBA defaultFogColor = new ColorRGBA(0.55f, 0.72f, 0.88f, 1f);
				private ColorRGBA dayBgColor, nightBgColor;
				
				private final Random cactusRng;

        // ── ЦИКЛ ДНЯ И НОЧИ ────────────────────────────────────────────────────
        private static final float DAY_DURATION   = 180f;  // 3 минуты
        private static final float NIGHT_DURATION = 120f;  // 2 минуты
        private static final float TOTAL_CYCLE    = DAY_DURATION + NIGHT_DURATION;
        private static final float ORBIT_RADIUS   = 85f;
        private float dayNightTime = 120f;          // позиция в цикле [0..TOTAL_CYCLE)
        private DirectionalLight sunLight;
        private AmbientLight     ambientLight;
        private Geometry         skyDome, sunGeom, moonGeom;
        private Material         skyMat, sunMat;
        private FilterPostProcessor             gameFpp;
        private FogFilter                       gameFogFilter;
        private BloomFilter                     gameBloomFilter;
        private LightScatteringFilter           gameLightScattering;
        private DirectionalLightShadowRenderer  gameShadowRenderer;
        // сетевая синхронизация времени суток
        private float dayNetTimer = 0f;
        private static final float DAY_NET_INTERVAL = 5f; // каждые 5 сек хост отсылает время

				private boolean sandstormActive = false;
				private float sandstormTimer = 0f;
				private static final float SANDSTORM_DURATION = 30f;

				private Node sandParticleNode;
				private final java.util.List<SandParticle> sandParticles = new ArrayList<>();
				private float sandSpawnTimer = 0f;
				private Geometry sandstormOverlayGeo;

        // ── ИВЕНТ 1: Шариковый дождь ─────────────────────────────────────
        private boolean ballRainActive = false;
        private float ballRainTimer = 0f;
        private static final float BALL_RAIN_DURATION = 10f;
        private float ballRainSpawnTimer = 0f;
        private final List<RainBall> rainBalls = new ArrayList<>();
        private Node rainBallNode;

        // ── ИВЕНТ 2: Дождь ───────────────────────────────────────────────
        private boolean weatherRainActive = false;
        private float weatherRainTimer = 0f;
        private static final float WEATHER_RAIN_DURATION = 30f;

				// ── ИВЕНТ 3: Ледяная арена (полная переработка) ────────────────────────
				private boolean frozenArenaActive  = false;
				private float   frozenArenaTimer   = 0f;
				private float   frozenSpeedMult    = 1f;    // используется enableIceMode - множитель скорости (0.35 = очень скользко)

				// Ледяные пики
				private float   iceSpikeSpawnTimer = 0f;
				private float   iceWarnDuration    = 0.85f;
				private Node    iceSpikeNode;
				private Node    iceParticleNode;
				private final List<IceSpike>    iceSpikes    = new ArrayList<>();
				private final List<IceParticle> iceParticles = new ArrayList<>();
        private static final float FROZEN_ARENA_DURATION = 36f;

				// ═══════════════════════════════════════════════════════════════════════════
				//  КОНСТАНТЫ ЛЕДЯНОГО СОБЫТИЯ (ice arena event)
				// ═══════════════════════════════════════════════════════════════════════════

				// ── Время предупреждения (telegraph) ─────────────────────────────────────
				/** Длительность предупреждающего свечения в начале события, сек */
				private static final float ICE_WARN_START  = 0.85f;
				/** Минимальная длительность предупреждения в конце события (пики появляются быстрее), сек */
				private static final float ICE_WARN_MIN    = 0.35f;

				// ── Длительности фаз одного пика ────────────────────────────────────────
				/** Время резкого подъёма пика из-под земли, сек */
				private static final float ICE_RISE_DUR   = 0.18f;
				/** Время, которое пик стоит полностью выдвинутым и наносит урон, сек */
				private static final float ICE_HOLD_DUR   = 1.40f;
				/** Время плавного погружения пика обратно под землю, сек */
				private static final float ICE_SINK_DUR   = 0.45f;

				// ── Интервалы между спавном новых пиков ─────────────────────────────────
				/** Начальный интервал между появлениями пиков, сек */
				private static final float ICE_SPAWN_START = 2.80f;
				/** Минимальный интервал между появлениями пиков в конце события, сек */
				private static final float ICE_SPAWN_MIN   = 0.85f;

				// ── Максимальное количество одновременно существующих пиков ─────────────
				/** Лимит активных пиков в начале события */
				private static final int   ICE_MAX_START = 9;   // было 3, увеличено для большей плотности
				/** Лимит активных пиков в конце события */
				private static final int   ICE_MAX_END   = 27;  // было 9, увеличено для большей плотности

				// ── Геометрия одного пика ────────────────────────────────────────────────
				/** Полная высота ледяного стержня (Box по Y), единиц */
				private static final float ICE_SPIKE_H    = 1.70f;
				/** Полуширина стержня (Box half-extent по X и Z), единиц */
				private static final float ICE_SPIKE_BW   = 0.10f;
				/** Высота острия-пирамиды над стержнем, единиц */
				private static final float ICE_TIP_H      = 0.55f;
				/** Позиция центра стержня по Y, когда пик полностью спрятан под землёй */
				private static final float ICE_BODY_HIDDEN   = -ICE_SPIKE_H;          // -1.70
				/** Позиция центра стержня по Y, когда пик полностью выдвинут наружу */
				private static final float ICE_BODY_EXTENDED = ICE_SPIKE_H * 0.5f;   // 0.85

				// ── Радиусы коллизии с игроками ─────────────────────────────────────────
				/** Расстояние от центра пика, на котором голова змеи мгновенно погибает */
				private static final float ICE_HIT_RADIUS    = 0.45f;
				/** Расстояние от центра пика, на котором сегменты тела отсекаются */
				private static final float ICE_BODY_RADIUS   = 0.65f;

				// ── Безопасные дистанции при спавне ─────────────────────────────────────
				/** Минимальное расстояние от пика до любой живой змеи при создании */
				private static final float ICE_SAFE_FROM_SNAKE = 5.0f;
				/** Минимальное расстояние между новым пиком и уже существующими */
				private static final float ICE_SAFE_FROM_SPIKE = 1.8f;

				// ── Ледяные частицы (снежная пыль) ──────────────────────────────────────
				/** Максимальное количество одновременно отображаемых частиц */
				private static final int   ICE_PARTICLE_CAP  = 30;
				/** Базовая продолжительность жизни одной частицы, сек */
				private static final float ICE_PARTICLE_LIFE = 1.2f;

				// Перечисление и классы данных (IceSpikePhase, IceSpike, IceParticle)
				private enum IceSpikePhase { TELEGRAPHING, RISING, EXTENDED, RETRACTING }

				private static final class IceSpike {
						float x, z, floorY;
						IceSpikePhase phase = IceSpikePhase.TELEGRAPHING;
						float phaseTimer, warnDuration;
						boolean done;
						Node root;
						Geometry body, tip, glow;
						Geometry warnDisc, warnRing;
						Node cracks;
						float warnPulse;

						IceSpike(float x, float z, float floorY, float warnDuration) {
								this.x = x; this.z = z; this.floorY = floorY;
								this.warnDuration = warnDuration;
								this.phaseTimer = warnDuration;
						}
				}

				private static final class IceParticle {
						Geometry geo;
						float vx, vy, vz, life, maxLife;
						IceParticle(Geometry geo, float vx, float vy, float vz, float life) {
								this.geo = geo; this.vx = vx; this.vy = vy; this.vz = vz;
								this.life = life; this.maxLife = life;
						}
				}

				private static Mesh createDiskMesh(float radius, int segments) {
						Vector3f[] verts = new Vector3f[segments + 1];
						int[] idx = new int[segments * 3];
						verts[0] = new Vector3f(0, 0, 0);
						for (int i = 0; i < segments; i++) {
								float a = (float)(Math.PI * 2.0 * i / segments);
								verts[i + 1] = new Vector3f(FastMath.cos(a) * radius, 0, FastMath.sin(a) * radius);
						}
						for (int i = 0; i < segments; i++) {
								idx[i*3]=0; idx[i*3+1]=i+1; idx[i*3+2]=(i+1)%segments+1;
						}
						Mesh m = new Mesh();
						m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(verts));
						m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
						m.updateBound();
						return m;
				}

				private static Mesh createRingMesh(float innerR, float outerR, int segments) {
						Vector3f[] verts = new Vector3f[segments * 2];
						int[] idx = new int[segments * 6];
						for (int i = 0; i < segments; i++) {
								float a = (float)(Math.PI * 2.0 * i / segments);
								float c = FastMath.cos(a), s = FastMath.sin(a);
								verts[i*2]   = new Vector3f(c * innerR, 0, s * innerR);
								verts[i*2+1] = new Vector3f(c * outerR, 0, s * outerR);
								int n = (i+1)%segments, base = i*6;
								idx[base]=i*2; idx[base+1]=i*2+1; idx[base+2]=n*2+1;
								idx[base+3]=i*2; idx[base+4]=n*2+1; idx[base+5]=n*2;
						}
						Mesh m = new Mesh();
						m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(verts));
						m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
						m.updateBound();
						return m;
				}

				private static Mesh createPyramidMesh(float halfBase, float height) {
						float hb = halfBase;
						Vector3f[] v = {
								new Vector3f(-hb,0,hb), new Vector3f(hb,0,hb), new Vector3f(hb,0,-hb),
								new Vector3f(-hb,0,-hb), new Vector3f(0,height,0)
						};
						int[] idx = {0,1,4, 1,2,4, 2,3,4, 3,0,4, 0,3,1, 1,3,2};
						Mesh m = new Mesh();
						m.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(v));
						m.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
						m.updateBound();
						return m;
				}

				private static float lerp(float t, float a, float b) {
						return a + (b - a) * FastMath.clamp(t, 0, 1);
				}

        // ── Общий планировщик случайных ивентов (хост/соло) ──────────────
        private float nextEventTimer = 0f;  // время до следующего ивента
        private final Random eventRng = new Random();
        private Node rainDropNode;
        private final List<RainDrop> rainDrops = new ArrayList<>();

        // Вода на полу
        private final List<WaterPuddle> waterPuddles = new ArrayList<>();
        private Node waterNode;
        private float waterSpeedMultiplier = 1f; // замедление от воды

				// Параметры рельефа (только для карты 0)
				private static final int TERRAIN_SEED = 12345;
				private float[] terrainNoise; // предрассчитанный шум для быстрого доступа
				private static final float TERRAIN_SCALE = 0.02f; // масштаб шума
				private static final float TERRAIN_AMPLITUDE = 1.2f; // максимальная высота холмов
				private static final float TERRAIN_GRID_RESOLUTION = 0.5f; // разрешение сетки

				// Простой 2D-шум для рельефа (можно заменить на Perlin-шум)
				private float simpleNoise(float x, float z, int seed) {
						int ix = (int)(x * TERRAIN_SCALE * 1000);
						int iz = (int)(z * TERRAIN_SCALE * 1000);
						int hash = ix * 374761393 + iz * 668265263 + seed * 174440041;
						hash = (hash ^ (hash >> 13)) * 1274126177;
						return (hash ^ (hash >> 16)) / (float)Integer.MAX_VALUE;
				}

				// Сглаженный шум
				private float smoothNoise(float x, float z, int seed) {
						float corners = (simpleNoise(x - 1, z - 1, seed) + simpleNoise(x + 1, z - 1, seed) +
														 simpleNoise(x - 1, z + 1, seed) + simpleNoise(x + 1, z + 1, seed)) / 16f;
						float sides = (simpleNoise(x - 1, z, seed) + simpleNoise(x + 1, z, seed) +
													 simpleNoise(x, z - 1, seed) + simpleNoise(x, z + 1, seed)) / 8f;
						float center = simpleNoise(x, z, seed) / 4f;
						return corners + sides + center;
				}

				// Основная функция высоты поверхности
				public float getSurfaceHeight(float x, float z) {
						if (mapIndex != 0) return -0.2f;   // для карт 1 и 2 пол находится на y = -0.4

						// Центр горы
						float centerX = 0f;
						float centerZ = 0f;
						float mountainRadius = 30f;   // радиус основания горы
						float mountainHeight = 8f;    // максимальная высота

						// Расстояние от центра
						float dist = (float) Math.sqrt((x - centerX) * (x - centerX) + (z - centerZ) * (z - centerZ));

						// Плавный профиль горы (косинус)
						if (dist < mountainRadius) {
								float factor = (float) Math.cos(dist / mountainRadius * Math.PI * 0.5);
								return mountainHeight * factor;
						} else {
								return 0f;
						}
				}

        // Константы карты
        float mapHalf;
        static final float SEG_SPACING = 0.55f;
        static final float SPEED       = 8f;
        static final float TURN_SPEED  = 2.8f;
        static final int   MAX_FOOD    = 18;
        static final int   BAD_FOOD    = 3;

        // Ямы со шипами (карта 2)
        private static final float[][] PIT_POSITIONS = {
                {-15f, -15f}, {15f, -15f}, {-15f, 15f}, {15f, 15f},
                {0f, -20f},   {0f, 20f},   {-20f, 0f},  {20f, 0f}
        };
        private static final float PIT_RADIUS = 3.5f;
        private static final float PIT_DEPTH  = 8f;
        private static final float PIT_RETRACTED_DURATION = 2.0f;
        private static final float PIT_EXTENDING_DURATION = 0.35f;
        private static final float PIT_EXTENDED_DURATION  = 1.5f;
        private static final float PIT_RETRACTING_DURATION= 0.35f;
        private static final float PIT_WARNING_TIME       = 0.4f;

        private enum PitState { RETRACTED, EXTENDING, EXTENDED, RETRACTING }

        private static class PitData {
            Vector3f position;
            float radius;
            PitState state = PitState.RETRACTED;
            float stateTimer = 0f;
            float warnFlashTimer = 0f;
            final List<Geometry> spikes    = new ArrayList<>();
            final List<Geometry> spikeTips = new ArrayList<>();
            final List<Geometry> spikeGlints = new ArrayList<>();
            Geometry decal;
            Geometry warningRing;
            boolean warningPlayed = false;
            // Высота шипа (полная)
            static final float SPIKE_H = 0.9f;
            static final float FLOOR_Y = -0.22f;
        }

        private final List<PitData> pits = new ArrayList<>();
        // Конструктор
				public GameState(String myNick, List<String> allPlayers, int myIndex, boolean solo,
												 boolean isHost, DatagramSocket socket, String hostAddress, int hostPort,
												 List<InetSocketAddress> clients, int mapIndex, boolean cubesEnabled,
												 Map<String, ColorRGBA> playerColors,
												 Map<InetSocketAddress, String> clientNames) {
            this.myNick     = safeString(myNick, getSystemUsername());
            this.allPlayers = allPlayers != null && !allPlayers.isEmpty() ? new CopyOnWriteArrayList<>(allPlayers) : new CopyOnWriteArrayList<>(Collections.singletonList(this.myNick));
            this.myIndex    = myIndex < 0 ? 0 : myIndex;
            this.solo       = solo;
            this.isHost     = isHost;
            this.socket     = socket;
            this.hostAddress= hostAddress;
            this.hostPort   = hostPort > 0 ? hostPort : GAME_PORT;
            this.clients    = clients != null ? new CopyOnWriteArrayList<>(clients) : new CopyOnWriteArrayList<>();
            this.mapIndex   = clampMapIndex(mapIndex);
            this.loadedMapInfo = getMapInfo(this.mapIndex);
            this.mapSettings = (loadedMapInfo != null && loadedMapInfo.settings != null) ? loadedMapInfo.settings.copy() : new MapRuntimeSettings();
            this.mapDef = loadedMapInfo != null ? loadedMapInfo.def : null;
            this.mapHalf = Math.max(25f, mapSettings.mapHalf);
            this.effectiveSnakeSpeed = Math.max(2f, mapSettings.snakeSpeed);
            this.effectiveTurnSpeed = Math.max(0.5f, mapSettings.turnSpeed);
            this.effectiveMaxFood = Math.max(1, mapSettings.maxFood);
            this.cubesEnabled = cubesEnabled && mapSettings.enableBlackCubesDefault;
            this.playerColors = playerColors != null ? new ConcurrentHashMap<>(playerColors) : new ConcurrentHashMap<>();
						this.clientNames = clientNames != null ? new ConcurrentHashMap<>(clientNames) : new ConcurrentHashMap<>();
						this.cactusRng = new Random(mapIndex * 1000L + 12345L);
        }

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            if (invalidApplication(application, "Game.initialize")) return;
            app = (SimpleApplication) application;
            rootNode = app.getRootNode(); guiNode = app.getGuiNode();
            assetManager = app.getAssetManager(); inputManager = app.getInputManager();
            cam = app.getCamera(); stateManager = sm;
            if (missingRefs("Game.initialize", rootNode, guiNode, assetManager, inputManager, cam, stateManager)) return;
            String gameTrack = new Random().nextBoolean() ? "Sounds/theme/main2.ogg" : "Sounds/theme/main3.ogg";
            MusicManager.play(assetManager, rootNode, gameTrack, musicVolume);

            // Начальный цвет фона (день) — будет заменён initDayNightLighting()
            if (app.getViewPort() != null) app.getViewPort().setBackgroundColor(new ColorRGBA(0.28f, 0.56f, 1.00f, 1f));
						dayBgColor = new ColorRGBA(0.28f, 0.56f, 1.00f, 1f);
						if (mapIndex == 1) nightBgColor = new ColorRGBA(0.05f, 0.04f, 0.08f, 1f);
						else if (mapIndex == 2) nightBgColor = new ColorRGBA(0.02f, 0.02f, 0.06f, 1f);
						else nightBgColor = new ColorRGBA(0.02f, 0.03f, 0.10f, 1f);

            assetManager.registerLocator(".", FileLocator.class);

            // Первый ивент — быстро через 20–50 секунд
            nextEventTimer = 20f + new Random().nextFloat() * 30f;

            setupPhysics();
            mapCtx = new MapContext(
                    this,
                    app,
                    rootNode,
                    guiNode,
                    assetManager,
                    inputManager,
                    cam,
                    physicsSpaceOrNull(bulletAppState),
                    mapSettings,
                    loadedMapInfo != null ? loadedMapInfo.id : "unknown",
                    solo,
                    isHost,
                    myIndex,
                    allPlayers
            );
            buildOuterWorld();
            if (mapDef != null && mapDef.overridesArena()) {
                // Даже если карта строит арену сама, старые системы SnakeApp
                // всё ещё используют wallNode для обломков, смерти и некоторых эффектов.
                wallNode = new Node("Walls");
                rootNode.attachChild(wallNode);
                try { mapDef.buildWorld(mapCtx); }
                catch (Exception e) { logSafe("Map.buildWorld", e); buildArena(); }
            } else {
                buildArena();
            }
            buildClouds();
						if (mapIndex == 0 && (mapDef == null || !mapDef.overridesArena())) {
								buildTerrainGrid();
						}
            createSnakes();
						
						if (!solo && isHost) {
								clientIndexMap.clear();
								for (Map.Entry<InetSocketAddress, String> entry : clientNames.entrySet()) {
										int idx = allPlayers.indexOf(entry.getValue());
										if (idx >= 0) {
												clientIndexMap.put(entry.getKey(), idx);
										}
								}
						}
						
						if (!solo && isHost) {
								long now = System.currentTimeMillis();
								for (InetSocketAddress addr : clients) {
										clientLastSeen.put(addr, now);
								}
						}
						
            spawnFood(effectiveMaxFood);
            if (cubesEnabled) spawnInitialCubes();
            buildHUD();
            createGameoverUI();
            setupControls();
            loadSounds();
            if (!solo) initNetwork();
            if (mapDef != null && mapCtx != null) {
                try { mapDef.onStart(mapCtx); }
                catch (Exception e) { logSafe("Map.onStart", e); }
            }
            // ── День/Ночь: инициализируем после всей сцены ──
            initDayNightLighting();

						// Синхронизация ям на карте 2
						if (mapIndex == 2 && isHost) {
								broadcastPitsState();
						}

            rainBallNode = new Node("RainBalls"); rootNode.attachChild(rainBallNode);
            rainDropNode = new Node("RainDrops"); rootNode.attachChild(rainDropNode);
            waterNode    = new Node("Water");     rootNode.attachChild(waterNode);

						sandParticleNode = new Node("SandParticles");
						rootNode.attachChild(sandParticleNode);
        }

        private void createGameoverUI() {
            float W = cam.getWidth(), H = cam.getHeight();
            float S = uiScale(cam);
            BitmapFont font = loadFont(assetManager);
            gameoverNode = new Node("GameoverUI");
            gameoverNode.setCullHint(Spatial.CullHint.Always);
            guiNode.attachChild(gameoverNode);

            // Затемнение всегда растягивается на текущий размер окна.
            Box dimBox = new Box(W/2f, H/2f, 0.1f);
            Geometry dimGeo = new Geometry("GODim", dimBox);
            Material dimMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            dimMat.setColor("Color", new ColorRGBA(0,0,0,0.65f));
            dimMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            dimGeo.setMaterial(dimMat);
            dimGeo.setLocalTranslation(W/2f, H/2f, 5f);
            gameoverNode.attachChild(dimGeo);

            // Окно Game Over теперь масштабируется так же, как пауза/HUD.
            float cardW = 500f * S;
            float cardH = 360f * S;
            Box cardBox = new Box(cardW/2f, cardH/2f, 0.5f);
            Geometry cardGeo = new Geometry("GOCard", cardBox);
            Material cardMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            cardMat.setColor("Color", BG_CARD);
            cardMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            cardGeo.setMaterial(cardMat);
            cardGeo.setLocalTranslation(W/2f, H/2f, 5.5f);
            gameoverNode.attachChild(cardGeo);

            Box topLine = new Box(cardW/2f, 3f * S, 0.3f);
            Geometry topLineGeo = new Geometry("GOTopLine", topLine);
            topLineGeo.setMaterial(unshaded(assetManager, ACCENT));
            topLineGeo.setLocalTranslation(W/2f, H/2f + cardH/2f - 3f * S, 5.8f);
            gameoverNode.attachChild(topLineGeo);

            BitmapText goTitle = new BitmapText(font);
            goTitle.setSize(32f * S);
            goTitle.setText("ИГРА ОКОНЧЕНА");
            goTitle.setColor(ACCENT);
            goTitle.setName("GOTitle");
            goTitle.setLocalTranslation(W/2f - goTitle.getLineWidth()/2f, H/2f + 148f * S, 6f);
            gameoverNode.attachChild(goTitle);

            BitmapText winnerText = new BitmapText(font);
            winnerText.setSize(20f * S);
            winnerText.setColor(ACCENT3);
            winnerText.setName("GOWinner");
            winnerText.setLocalTranslation(W/2f - 220f * S, H/2f + 105f * S, 6f);
            gameoverNode.attachChild(winnerText);

            BitmapText scoresText = new BitmapText(font);
            scoresText.setSize(15f * S);
            scoresText.setColor(TEXT);
            scoresText.setName("GOScores");
            scoresText.setLocalTranslation(W/2f - 220f * S, H/2f + 65f * S, 6f);
            gameoverNode.attachChild(scoresText);

            float btnW = 200f * S, btnH = 48f * S;
            float btnSpacing = 120f * S;
            goPlayAgainBtn = new MenuButton("ИГРАТЬ СНОВА", W/2f - btnSpacing, H/2f - 120f * S, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT, assetManager, gameoverNode, 6f);
            goMenuBtn = new MenuButton("В МЕНЮ", W/2f + btnSpacing, H/2f - 120f * S, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, DANGER, assetManager, gameoverNode, 6f);
        }

        private void populateGameoverContent(String winnerName) {
            if (gameoverNode == null) return;
            if (!solo && goPlayAgainBtn != null) goPlayAgainBtn.setText("В МЕНЮ");
            Spatial ws = gameoverNode.getChild("GOWinner");
            if (ws instanceof BitmapText) ((BitmapText)ws).setText("Победитель: " + winnerName);

            StringBuilder sb = new StringBuilder();
            for (int i=0; i<snakes.size(); i++) {
                SnakePlayer s = snakes.get(i);
                sb.append(i+1).append(". ").append(s.getName())
                        .append("  Очки: ").append(s.getScore()).append("\n");
            }
            Spatial ss = gameoverNode.getChild("GOScores");
            if (ss instanceof BitmapText) ((BitmapText)ss).setText(sb.toString());
        }

				public ColorRGBA getPlayerColor(int index) {
						if (index >= 0 && index < allPlayers.size()) {
								String name = allPlayers.get(index);
								ColorRGBA c = playerColors.get(name);
								if (c != null) return c;
						}
						// fallback – цвета по умолчанию
						return index == 0 ? new ColorRGBA(0.15f, 0.9f, 0.3f, 1f)
															: SNAKE_COLORS[index % SNAKE_COLORS.length].clone();
				}

        private void showGameoverOverlay(String winnerName) {
            gameoverNode.setCullHint(Spatial.CullHint.Inherit);
            gameoverUIActive = true;
            if (inputManager != null) inputManager.setCursorVisible(true);
            populateGameoverContent(winnerName);

            // Подписываем мышь только один раз. Иначе после resize Game Over
            // получал несколько одинаковых listener-ов и кнопки могли срабатывать несколько раз.
            if (!gameoverInputRegistered) {
                if (!inputManager.hasMapping("GO_Mouse")) {
                    inputManager.addMapping("GO_Mouse", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
                }
                if (!inputManager.hasMapping("GO_MouseMove")) {
                    inputManager.addMapping("GO_MouseMove",
                            new MouseAxisTrigger(MouseInput.AXIS_X, false), new MouseAxisTrigger(MouseInput.AXIS_X, true),
                            new MouseAxisTrigger(MouseInput.AXIS_Y, false), new MouseAxisTrigger(MouseInput.AXIS_Y, true));
                }
                inputManager.addListener((AnalogListener)(n,v,t) -> {
                    if (!gameoverUIActive) return;
                    com.jme3.math.Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
                if (mp == null) return;
                    float mx = mp.x, my = mp.y;
                    if (goPlayAgainBtn != null) goPlayAgainBtn.updateHover(mx, my);
                    if (goMenuBtn != null) goMenuBtn.updateHover(mx, my);
                }, "GO_MouseMove");
                inputManager.addListener((ActionListener)(n,p,t) -> {
                    if (!gameoverUIActive) return;
                    com.jme3.math.Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
                if (mp == null) return;
                    float mx = mp.x, my = mp.y;
                    if (p) {
                        if (goPlayAgainBtn != null) goPlayAgainBtn.onPress(mx, my);
                        if (goMenuBtn != null) goMenuBtn.onPress(mx, my);
                    } else {
                        if (goPlayAgainBtn != null && goPlayAgainBtn.onRelease(mx, my)) {
                                if (solo) restartGame();
                                else backToMenu();
                        } else if (goMenuBtn != null && goMenuBtn.onRelease(mx, my)) {
                            backToMenu();
                        }
                    }
                }, "GO_Mouse");
                gameoverInputRegistered = true;
            }
        }

        private void restartGame() {
            if (!solo) {
                backToMenu();
                return;
            }
            netRunning.set(false);
            closeQuietly(socket);
            if (rainSound != null) { rainSound.stop(); detachQuietly(rootNode, rainSound); rainSound = null; }
            for (SnakePlayer sp : snakes) sp.cleanup(guiNode);
            for (BlackCube bc : blackCubes) {
                if (bc.phy != null) { bc.phy.setEnabled(false); bulletAppState.getPhysicsSpace().remove(bc.phy); }
            }
            blackCubes.clear();
            rootNode.detachAllChildren(); LemurUi.clearPage(guiNode);
            UiGrid.clear(guiNode);
            clearInputMappingsQuietly(inputManager);
            stateManager.detach(bulletAppState); stateManager.detach(this);
						stateManager.attach(new GameState(
										myNick,
										allPlayers,
										myIndex,
										solo,
										isHost,
										null,
										hostAddress,
										hostPort,
										clients,
										selectedMap,
										cubesEnabled,
										new HashMap<>(),
										this.clientNames
						));
        }

        // ── Физика ────────────────────────────────────────────────────────
        private void setupPhysics() {
            bulletAppState = new BulletAppState();
            stateManager.attach(bulletAppState);
            bulletAppState.getPhysicsSpace().setGravity(new Vector3f(0,-9.81f,0));
        }

        // ── Внешний мир ───────────────────────────────────────────────────
        private void buildOuterWorld() {
            worldNode = new Node("World");
            rootNode.attachChild(worldNode);
            float ext = mapHalf * 3.5f;

            // Земля снаружи
            ColorRGBA groundColor = mapIndex==1
                    ? new ColorRGBA(0.72f,0.58f,0.32f,1f)
                    : new ColorRGBA(0.25f,0.48f,0.18f,1f);
            Box groundOut = new Box(ext, 0.3f, ext);
            Geometry gOut = new Geometry("OutGround", groundOut);
            gOut.setMaterial(litMat(assetManager, groundColor));
            gOut.setShadowMode(RenderQueue.ShadowMode.Receive);
            gOut.setLocalTranslation(0, -0.8f, 0);
            worldNode.attachChild(gOut);

            // Горы по периметру — многослойные скальные образования
            Material rockMat   = litMat(assetManager, new ColorRGBA(0.48f,0.42f,0.38f,1f));
            Material rockDark  = litMat(assetManager, new ColorRGBA(0.32f,0.28f,0.26f,1f));
            Material rockLight = litMat(assetManager, new ColorRGBA(0.62f,0.57f,0.52f,1f));
            Material snowMat   = litMat(assetManager, new ColorRGBA(0.90f,0.92f,0.96f,1f));
            Material snowSide  = litMat(assetManager, new ColorRGBA(0.78f,0.82f,0.88f,1f));
            Random rng = new Random(42);
            int mountainCount = 32;
            for (int i=0; i<mountainCount; i++) {
                float angle = (float)i/mountainCount * FastMath.TWO_PI + (rng.nextFloat()-0.5f)*0.18f;
                float dist  = mapHalf * 1.6f + rng.nextFloat() * mapHalf;
                float h     = 9f  + rng.nextFloat() * 20f;
                float r     = 5f  + rng.nextFloat() * 11f;
                float cx_m  = FastMath.cos(angle) * dist;
                float cz_m  = FastMath.sin(angle) * dist;

                // Основание — широкая приземистая плита
                Geometry base = new Geometry("MtnBase"+i, new Box(r*0.95f, h*0.18f, r*0.95f));
                base.setMaterial(rockDark);
                base.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                base.setLocalTranslation(cx_m, h*0.18f - 0.8f, cz_m);
                worldNode.attachChild(base);

                // Средний блок — чуть уже и повёрнут
                float midRot = (rng.nextFloat()-0.5f)*0.55f;
                Geometry mid = new Geometry("MtnMid"+i, new Box(r*0.65f, h*0.30f, r*0.60f));
                mid.setMaterial(rockMat);
                mid.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                mid.setLocalRotation(new Quaternion().fromAngleAxis(midRot, Vector3f.UNIT_Y));
                mid.setLocalTranslation(cx_m + (rng.nextFloat()-0.5f)*r*0.3f, h*0.30f + h*0.18f - 0.5f, cz_m + (rng.nextFloat()-0.5f)*r*0.3f);
                worldNode.attachChild(mid);

                // Верхний пик — узкий, повёрнут ещё раз
                float topRot = midRot + (rng.nextFloat()-0.5f)*0.7f;
                Geometry top = new Geometry("MtnTop"+i, new Box(r*0.32f, h*0.35f, r*0.28f));
                top.setMaterial(rockLight);
                top.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                top.setLocalRotation(new Quaternion().fromAngleAxis(topRot, Vector3f.UNIT_Y));
                top.setLocalTranslation(cx_m + (rng.nextFloat()-0.5f)*r*0.15f, h*0.35f + h*0.48f - 0.4f, cz_m + (rng.nextFloat()-0.5f)*r*0.15f);
                worldNode.attachChild(top);

                // Скальные выступы — 2-3 боковых плиты
                int slabCount = 2 + rng.nextInt(2);
                for (int s = 0; s < slabCount; s++) {
                    float sa = rng.nextFloat() * FastMath.TWO_PI;
                    float sd = r * (0.35f + rng.nextFloat() * 0.45f);
                    float sh = h * (0.12f + rng.nextFloat() * 0.20f);
                    float sw = r * (0.18f + rng.nextFloat() * 0.25f);
                    Geometry slab = new Geometry("MtnSlab"+i+"_"+s, new Box(sw, sh, sw*0.55f));
                    slab.setMaterial(s%2==0 ? rockDark : rockMat);
                    slab.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                    slab.setLocalRotation(new Quaternion().fromAngleAxis(sa, Vector3f.UNIT_Y));
                    float slabY = h * (0.15f + rng.nextFloat() * 0.30f);
                    slab.setLocalTranslation(cx_m + FastMath.cos(sa)*sd, slabY, cz_m + FastMath.sin(sa)*sd);
                    worldNode.attachChild(slab);
                }

                // Снеговая шапка (только для высоких гор)
                if (h > 15f) {
                    // Основной снежный купол
                    Geometry snowCap = new Geometry("Snow"+i, new Box(r*0.28f, h*0.13f, r*0.24f));
                    snowCap.setMaterial(snowMat);
                    snowCap.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                    snowCap.setLocalTranslation(cx_m, h*0.86f, cz_m);
                    worldNode.attachChild(snowCap);
                    // Снег на скале ниже
                    Geometry snowSlab = new Geometry("SnowSlab"+i, new Box(r*0.42f, h*0.05f, r*0.38f));
                    snowSlab.setMaterial(snowSide);
                    snowSlab.setShadowMode(RenderQueue.ShadowMode.Receive);
                    snowSlab.setLocalTranslation(cx_m, h*0.66f, cz_m);
                    worldNode.attachChild(snowSlab);
                }
            }

            // Деревья / кактусы СНАРУЖИ арены (никогда внутри игровой зоны!)
            Material trunkMat = litMat(assetManager, new ColorRGBA(0.42f,0.26f,0.12f,1f));
            Material leafMat  = litMat(assetManager, new ColorRGBA(0.15f,0.6f,0.18f,1f));
            Material leafMat2 = litMat(assetManager, new ColorRGBA(0.1f,0.5f,0.12f,1f));
            Material cactMat  = litMat(assetManager, new ColorRGBA(0.2f,0.55f,0.18f,1f));

            int treeCount = 80;
            for (int i=0; i<treeCount; i++) {
                float angle = rng.nextFloat() * FastMath.TWO_PI;
                // Минимальная дистанция 1.45*mapHalf — ВСЕГДА за стенами
                float dist = mapHalf * 1.45f + rng.nextFloat() * mapHalf * 0.9f;
                float tx = FastMath.cos(angle)*dist, tz = FastMath.sin(angle)*dist;
                float treeH = 2.5f + rng.nextFloat()*3f;

                if (mapIndex == 1) {
                    // Кактусы для пустыни
                    Geometry trunk = new Geometry("Cactus"+i, new Box(0.25f, treeH/2f, 0.25f));
                    trunk.setMaterial(cactMat);
                    trunk.setLocalTranslation(tx, treeH/2f, tz);
                    trunk.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                    worldNode.attachChild(trunk);
                    // Руки кактуса
                    Geometry arm = new Geometry("CactusArm"+i, new Box(0.6f, 0.2f, 0.2f));
                    arm.setMaterial(cactMat);
                    arm.setLocalTranslation(tx + 0.5f, treeH*0.6f, tz);
                    arm.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                    worldNode.attachChild(arm);
                } else {
                    // Обычные деревья
                    Geometry trunk = new Geometry("Trunk"+i, new Box(0.22f, treeH/2f, 0.22f));
                    trunk.setMaterial(trunkMat);
                    trunk.setLocalTranslation(tx, treeH/2f - 0.5f, tz);
                    trunk.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                    worldNode.attachChild(trunk);
                    Material lm = rng.nextBoolean() ? leafMat : leafMat2;
                    Geometry leaves = new Geometry("Leaf"+i, new Sphere(5,6,0.9f+rng.nextFloat()*0.6f));
                    leaves.setMaterial(lm);
                    leaves.setLocalScale(1.4f+rng.nextFloat()*0.5f, 1.2f+rng.nextFloat()*0.5f, 1.4f+rng.nextFloat()*0.5f);
                    leaves.setLocalTranslation(tx, treeH+0.4f, tz);
                    leaves.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                    worldNode.attachChild(leaves);
                }
            }
        }

				// GameState наст-щик доп сюда новые методы
				// private void sendPause(boolean pause) {
						// if (!solo && isHost) {
								// sendNet(pause ? "PAUSE" : "UNPAUSE");
						// }
				// }

				private void broadcastPitsState() {
						if (!isHost || solo || pits.isEmpty()) return;
						StringBuilder sb = new StringBuilder("PITS_INIT");
						for (int i = 0; i < pits.size(); i++) {
								PitData p = pits.get(i);
								sb.append("|").append(i).append("=").append(p.state.ordinal()).append("=").append(p.stateTimer);
						}
						sendNet(sb.toString());
				}

				private void updateCactusRespawns(float tpf) {
						if (mapIndex != 1) return;
						if (!solo && !isHost) return;   // только хост управляет респавном
						for (CactusData cd : new ArrayList<>(cacti)) {
								if (cd.queuedForRespawn) {
										cd.respawnTimer -= tpf;
										if (cd.respawnTimer <= 0f) {
												// Удаляем старые обломки и колючки (если ещё остались)
												for (Geometry frag : cd.fragments) {
														Spatial parent = frag.getParent();
														if (parent != null) parent.removeFromParent();
												}
												cd.fragments.clear();
												cd.fragmentTimers.clear();
												for (Geometry spine : cd.spines) {
														if (spine.getParent() != null) spine.removeFromParent();
												}
												cd.spines.clear();
												// Создаём новый кактус на случайном месте
												float cx, cz;
												do {
														cx = (FastMath.nextRandomFloat() - 0.5f) * (mapHalf * 2f - 8f);
														cz = (FastMath.nextRandomFloat() - 0.5f) * (mapHalf * 2f - 8f);
												} while (Math.abs(cx) < 6f && Math.abs(cz) < 6f);
												Material cactMat  = litMat(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
												Material spineMat = litMat(assetManager, new ColorRGBA(0.85f,0.82f,0.65f,1f));
												spawnCactusAt(bulletAppState.getPhysicsSpace(), cactMat, spineMat, cx, cz);
												cd.queuedForRespawn = false;
												if (!solo) sendNet("CACT_RESPAWN|" + cx + "|" + cz);
										}
								}
						}
				}

				private void startSandstormEvent() {
						if (!mapSettings.enableSandstorm) return;
						if (sandstormActive) return;
						sandstormActive = true;
						sandstormTimer = SANDSTORM_DURATION;
						showCenter("🌪 ПЕСЧАНАЯ БУРЯ! Видимость упала!", new ColorRGBA(0.9f, 0.8f, 0.4f, 1f));
						if (!solo) sendNet("EVENT_SANDSTORM");

						// GUI-оверлей затемнения (песчаная пелена)
						if (sandstormOverlayGeo == null) {
								float W = cam.getWidth(), H = cam.getHeight();
								Box ob = new Box(W/2f, H/2f, 0.1f);
								sandstormOverlayGeo = new Geometry("SandstormOverlay", ob);
								Material om = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
								om.setColor("Color", new ColorRGBA(0.55f, 0.42f, 0.10f, 0f));
								om.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
								sandstormOverlayGeo.setMaterial(om);
								sandstormOverlayGeo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
								sandstormOverlayGeo.setLocalTranslation(W/2f, H/2f, 15f);
								guiNode.attachChild(sandstormOverlayGeo);
						}
						sandSpawnTimer = 0f;
						if (!SnakeApp.particlesEnabled) return;
				}

				private void updateSandstorm(float tpf) {
						if (!sandstormActive && sandParticles.isEmpty() && sandstormOverlayGeo == null) return;

						float progress = sandstormActive ? (sandstormTimer / SANDSTORM_DURATION) : 0f;

						if (sandstormActive) {
								sandstormTimer -= tpf;
								if (sandstormTimer <= 0f) {
										sandstormActive = false;
										showCenter("Песчаная буря утихла.", new ColorRGBA(0.7f, 0.8f, 1f, 1f));
										if (solo || isHost) nextEventTimer = 25f + eventRng.nextFloat() * 45f;
								} else {
										// Частицы песка (всегда, без учёта настроек)
										sandSpawnTimer -= tpf;
										if (sandSpawnTimer <= 0f) {
												sandSpawnTimer = 0.02f;   // очень часто
												for (int i = 0; i < 12; i++) spawnSandParticle();  // много частиц
										}
								}
										// Частицы песка – только если разрешены
										if (SnakeApp.particlesEnabled) {
												sandSpawnTimer -= tpf;
												if (sandSpawnTimer <= 0f) {
														sandSpawnTimer = 0.02f;
														for (int i = 0; i < 12; i++) spawnSandParticle();
												}
										}
						}

						// Обновляем GUI-оверлей
						if (sandstormOverlayGeo != null) {
								float targetAlpha;
								if (sandstormActive) {
										float fadeIn = Math.min(1f, (SANDSTORM_DURATION - sandstormTimer) / 2f);
										targetAlpha = 0.65f * fadeIn;   // сильнее затемнение
								} else {
										targetAlpha = 0f;
								}
								ColorRGBA oc = (ColorRGBA) sandstormOverlayGeo.getMaterial().getParamValue("Color");
								float newA = oc.a + (targetAlpha - oc.a) * Math.min(1f, tpf * 3f);
								sandstormOverlayGeo.getMaterial().setColor("Color", new ColorRGBA(0.55f, 0.42f, 0.10f, newA));
								if (!sandstormActive && newA < 0.005f) {
										detachQuietly(guiNode, sandstormOverlayGeo);
										sandstormOverlayGeo = null;
								}
						}

						// Обновляем частицы
						for (int i = sandParticles.size() - 1; i >= 0; i--) {
								SandParticle sp = sandParticles.get(i);
								sp.life -= tpf;
								if (sp.life <= 0f) {
										sandParticleNode.detachChild(sp.geo);
										sandParticles.remove(i);
										continue;
								}
								Vector3f p = sp.geo.getLocalTranslation();
								p.x += sp.vx * tpf;
								p.y += sp.vy * tpf;
								p.z += sp.vz * tpf;
								sp.geo.setLocalTranslation(p);
								float alpha = Math.min(0.9f, sp.life * 1.2f);
								sp.geo.getMaterial().setColor("Color", new ColorRGBA(0.85f, 0.72f, 0.35f, alpha));
						}
				}

        // ── Арена ─────────────────────────────────────────────────────────
        private void buildArena() {
            wallNode = new Node("Walls");
            rootNode.attachChild(wallNode);
            PhysicsSpace space = bulletAppState.getPhysicsSpace();
            // Пол
            ColorRGBA floorColor = mapIndex==1
                    ? new ColorRGBA(0.75f,0.62f,0.38f,1f)
                    : (mapIndex==2 ? new ColorRGBA(0.18f,0.16f,0.22f,1f)
                    : new ColorRGBA(0.25f,0.58f,0.25f,1f));
						this.originalFloorColor = floorColor;
						// Сетка пола убрана: unshaded линии выше поверхности пола давали эффект "свечения".

            // Стены
            buildMetalFence(space);

            // Угловые башни
            Material towerMat = litMat(assetManager, new ColorRGBA(0.35f,0.40f,0.45f,1f));
            Material towerTop  = litMat(assetManager, new ColorRGBA(0.25f,0.28f,0.35f,1f));
            float[] tcx = {-mapHalf, mapHalf,-mapHalf, mapHalf};
            float[] tcz = {-mapHalf,-mapHalf, mapHalf, mapHalf};
            for (int i=0;i<4;i++) {
                addBox(new Vector3f(tcx[i],2f,tcz[i]), new Vector3f(2f,2.5f,2f), towerMat, null);
                addBox(new Vector3f(tcx[i],5f,tcz[i]), new Vector3f(2.2f,0.5f,2.2f), towerTop, null);
                // Прожектор (шар)
                Geometry light = new Geometry("TowerLight"+i, new Sphere(6,8,0.4f));
                light.setMaterial(unshaded(assetManager, new ColorRGBA(1f,0.95f,0.7f,1f)));
                light.setLocalTranslation(tcx[i], 6.2f, tcz[i]);
                wallNode.attachChild(light);
            }

						// Вместо простого Box для пола:
						if (mapIndex == 0) {
								buildTerrainFloor(space);
						} else {
								// старая логика для других карт
								// addBox(new Vector3f(0, -0.4f, 0), new Vector3f(mapHalf, 0.2f, mapHalf),
											 // unshaded(assetManager, floorColor), space);
								Geometry floorGeo = new Geometry("Floor", new Box(mapHalf, 0.2f, mapHalf));
								this.flatFloorGeo = floorGeo;
								floorGeo.setMaterial(litMat(assetManager, floorColor));
								floorGeo.setLocalTranslation(0, -0.4f, 0);
								floorGeo.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Receive);
								wallNode.attachChild(floorGeo);
								RigidBodyControl floorPhy = new RigidBodyControl(new BoxCollisionShape(new Vector3f(mapHalf, 0.2f, mapHalf)), 0);
								floorGeo.addControl(floorPhy);
								space.add(floorPhy);
						}

            // Ямы со шипами (карта 2)
            if (mapIndex == 2) buildPits(space);

            // Кактусы-преграды внутри арены (карта 1 — Пустыня)
            if (mapIndex == 1) buildDesertCacti(space);
        }

				private void buildTerrainFloor(PhysicsSpace space) {
						float gridSize = mapHalf * 2f;   // покрывает всю игровую зону
						int resolution = 80; // количество вершин

						Mesh terrainMesh = new Mesh();
						Vector3f[] vertices = new Vector3f[(resolution + 1) * (resolution + 1)];
						int[] indices = new int[resolution * resolution * 6];

						// Генерируем вершины
						for (int iz = 0; iz <= resolution; iz++) {
								for (int ix = 0; ix <= resolution; ix++) {
										float x = (ix / (float)resolution - 0.5f) * gridSize;
										float z = (iz / (float)resolution - 0.5f) * gridSize;
										float y = getSurfaceHeight(x, z);
										vertices[iz * (resolution + 1) + ix] = new Vector3f(x, y, z);
								}
						}

						// Генерируем индексы
						int idx = 0;
						for (int iz = 0; iz < resolution; iz++) {
								for (int ix = 0; ix < resolution; ix++) {
										int v0 = iz * (resolution + 1) + ix;
										int v1 = v0 + 1;
										int v2 = v0 + (resolution + 1);
										int v3 = v2 + 1;

										indices[idx++] = v0;
										indices[idx++] = v2;
										indices[idx++] = v1;

										indices[idx++] = v1;
										indices[idx++] = v2;
										indices[idx++] = v3;
								}
						}

						float[] colorArray = new float[(resolution + 1) * (resolution + 1) * 4];
						for (int iz = 0; iz <= resolution; iz++) {
								for (int ix = 0; ix <= resolution; ix++) {
										float h = vertices[iz * (resolution + 1) + ix].y; // верни к абсолютной высоте
										ColorRGBA col;
										if (h < 0.5f)       col = new ColorRGBA(0.3f, 0.6f, 0.2f, 1f);
										else if (h < 1.5f)  col = new ColorRGBA(0.5f, 0.7f, 0.3f, 1f);
										else if (h < 2.5f)  col = new ColorRGBA(0.7f, 0.6f, 0.4f, 1f); // каменистый
										else                col = new ColorRGBA(0.8f, 0.8f, 0.7f, 1f); // светлый пик
										int colorIdx = (iz * (resolution + 1) + ix) * 4;
										colorArray[colorIdx]   = col.r;
										colorArray[colorIdx+1] = col.g;
										colorArray[colorIdx+2] = col.b;
										colorArray[colorIdx+3] = col.a;
								}
						}
						// Вычисляем нормали вершин для корректного освещения (без них Lighting.j3md = только ambient = тёмно)
						float[] normals = new float[(resolution + 1) * (resolution + 1) * 3];
						// Сначала обнуляем
						java.util.Arrays.fill(normals, 0f);
						// Накапливаем нормали граней на вершины
						for (int iz = 0; iz < resolution; iz++) {
								for (int ix = 0; ix < resolution; ix++) {
										int v0 = iz * (resolution + 1) + ix;
										int v1 = v0 + 1;
										int v2 = v0 + (resolution + 1);
										int v3 = v2 + 1;
										// Треугольник 1: v0, v2, v1
										Vector3f p0 = vertices[v0], p1 = vertices[v1], p2 = vertices[v2];
										Vector3f e1 = p2.subtract(p0), e2 = p1.subtract(p0);
										Vector3f n = e1.cross(e2).normalizeLocal();
										for (int vi : new int[]{v0, v2, v1}) {
												normals[vi*3]   += n.x; normals[vi*3+1] += n.y; normals[vi*3+2] += n.z;
										}
										// Треугольник 2: v1, v2, v3
										p0 = vertices[v1]; p1 = vertices[v3]; p2 = vertices[v2];
										e1 = p1.subtract(p0); e2 = p2.subtract(p0);
										n = e1.cross(e2).normalizeLocal();
										for (int vi : new int[]{v1, v2, v3}) {
												normals[vi*3]   += n.x; normals[vi*3+1] += n.y; normals[vi*3+2] += n.z;
										}
								}
						}
						// Нормализуем
						for (int i = 0; i < (resolution + 1) * (resolution + 1); i++) {
								float nx = normals[i*3], ny = normals[i*3+1], nz = normals[i*3+2];
								float len = (float)Math.sqrt(nx*nx + ny*ny + nz*nz);
								if (len > 0.0001f) { normals[i*3] /= len; normals[i*3+1] /= len; normals[i*3+2] /= len; }
								else { normals[i*3] = 0; normals[i*3+1] = 1; normals[i*3+2] = 0; }
						}
						terrainMesh.setBuffer(com.jme3.scene.VertexBuffer.Type.Normal, 3,
								BufferUtils.createFloatBuffer(normals));

						terrainMesh.setBuffer(VertexBuffer.Type.Color, 4, BufferUtils.createFloatBuffer(colorArray));

						terrainMesh.setBuffer(VertexBuffer.Type.Position, 3,
																 BufferUtils.createFloatBuffer(vertices));
						terrainMesh.setBuffer(VertexBuffer.Type.Index, 3,
																 BufferUtils.createIntBuffer(indices));
						terrainMesh.updateBound();

						Geometry terrain = new Geometry("TerrainFloor", terrainMesh);
						this.terrainFloorGeo = terrain;
						// Используем UnshaderVertexColor чтобы цвет вершин совпадал с внешней землёй (без зависимости от угла освещения)
						Material terrainMat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
						terrainMat.setBoolean("UseMaterialColors", true);
						// Цвет совпадает с внешней землёй buildOuterWorld: 0.25, 0.48, 0.18
						ColorRGBA floorColor = new ColorRGBA(0.25f, 0.48f, 0.18f, 1f);
						terrainMat.setColor("Diffuse",  floorColor);
						// Повышаем ambient — чтобы тёмные склоны не были чёрными
						terrainMat.setColor("Ambient",  floorColor.mult(0.55f));
						terrainMat.setColor("Specular", new ColorRGBA(0.04f, 0.06f, 0.04f, 1f));
						terrainMat.setFloat("Shininess", 4f);
						terrain.setMaterial(terrainMat);
						terrain.setShadowMode(RenderQueue.ShadowMode.Receive);
						wallNode.attachChild(terrain);

						// Физический коллайдер для пола (упрощённый)
						RigidBodyControl floorPhy = new RigidBodyControl(
								new MeshCollisionShape(terrainMesh), 0f);
						terrain.addControl(floorPhy);
						space.add(floorPhy);
				}

				private void buildTerrainGrid() {
						gridNode = new Node("TerrainGrid");
						wallNode.attachChild(gridNode);

						float step = 5f;                    // шаг сетки
						float yOffset = 0.03f;              // лёгкое приподнятие над рельефом
						ColorRGBA gridColor = new ColorRGBA(0f, 0.10f, 0f, 0.88f);  // тёмно-зелёный полупрозрачный

						// базовый материал, от которого будем клонировать
						Material baseMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						baseMat.setColor("Color", gridColor);
						baseMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						baseMat.getAdditionalRenderState().setWireframe(false);

						// Горизонтальные линии (вдоль X)
						for (float z = -mapHalf; z <= mapHalf; z += step) {
								Mesh lineMesh = new Mesh();
								lineMesh.setMode(Mesh.Mode.Lines);

								List<Vector3f> vertices = new ArrayList<>();
								List<Integer> indices = new ArrayList<>();
								int idx = 0;
								for (float x = -mapHalf; x <= mapHalf; x += 0.5f) {  // частота точек на линии
										float y = getSurfaceHeight(x, z) + yOffset;
										vertices.add(new Vector3f(x, y, z));
										if (idx > 0) {
												indices.add(idx - 1);
												indices.add(idx);
										}
										idx++;
								}
								lineMesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(vertices.toArray(new Vector3f[0])));
								lineMesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(indices.stream().mapToInt(i->i).toArray()));
								lineMesh.updateBound();

								Geometry lineGeo = new Geometry("GridLineZ" + z, lineMesh);
								lineGeo.setMaterial(baseMat.clone());
								gridLines.add(lineGeo);
								lineGeo.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
								gridNode.attachChild(lineGeo);
						}

						// Вертикальные линии (вдоль Z)
						for (float x = -mapHalf; x <= mapHalf; x += step) {
								Mesh lineMesh = new Mesh();
								lineMesh.setMode(Mesh.Mode.Lines);

								List<Vector3f> vertices = new ArrayList<>();
								List<Integer> indices = new ArrayList<>();
								int idx = 0;
								for (float z = -mapHalf; z <= mapHalf; z += 0.5f) {
										float y = getSurfaceHeight(x, z) + yOffset;
										vertices.add(new Vector3f(x, y, z));
										if (idx > 0) {
												indices.add(idx - 1);
												indices.add(idx);
										}
										idx++;
								}
								lineMesh.setBuffer(VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(vertices.toArray(new Vector3f[0])));
								lineMesh.setBuffer(VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(indices.stream().mapToInt(i->i).toArray()));
								lineMesh.updateBound();

								Geometry lineGeo = new Geometry("GridLineX" + x, lineMesh);
								lineGeo.setMaterial(baseMat.clone());
								gridLines.add(lineGeo);
								lineGeo.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Off);
								gridNode.attachChild(lineGeo);
						}
				}

				// Перегрузка для вызовов без Random (сетевые события и респавн)
				private void spawnCactusAt(PhysicsSpace space, Material cactMat, Material spineMat, float cx, float cz) {
						spawnCactusAt(space, cactMat, spineMat, cx, cz, null);
				}

				// Основной метод с детерминированным Random (если rng != null)
				private void spawnCactusAt(PhysicsSpace space, Material cactMat, Material spineMat, float cx, float cz, Random rng) {
						float cactH;
						if (rng != null) {
								cactH = 1.6f + rng.nextFloat() * 1.4f;
						} else {
								cactH = 1.6f + FastMath.nextRandomFloat() * 1.4f;
						}

						Box trunkBox = new Box(0.28f, cactH/2f, 0.28f);
						Geometry trunk = new Geometry("CactI"+cacti.size(), trunkBox);
						trunk.setMaterial(cactMat);
						trunk.setLocalTranslation(cx, cactH/2f, cz);
						trunk.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
						wallNode.attachChild(trunk);

						RigidBodyControl trunkPhy = new RigidBodyControl(
										new BoxCollisionShape(new Vector3f(0.28f, cactH/2f, 0.28f)), 0f);   // масса 0 – статика
						trunk.addControl(trunkPhy);
						space.add(trunkPhy);

						CactusData cd = new CactusData(trunk, trunkPhy, cx, cz);
						cacti.add(cd);

						// рука
						if ((rng != null ? rng.nextFloat() : FastMath.nextRandomFloat()) < 0.6f) {
								float armSide = (rng != null ? rng.nextFloat() : FastMath.nextRandomFloat()) < 0.5f ? 0.5f : -0.5f;
								Box armBox = new Box(0.35f, 0.18f, 0.18f);
								Geometry arm = new Geometry("CactA"+cacti.size(), armBox);
								arm.setMaterial(cactMat);
								arm.setLocalTranslation(cx + armSide, cactH * 0.6f, cz);
								arm.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
								wallNode.attachChild(arm);
								cd.armGeo = arm;
						}

						// иголки
						int spineCount = 6 + (rng != null ? rng.nextInt(5) : FastMath.nextRandomInt(0, 4));
						for (int j = 0; j < spineCount; j++) {
								float sa = (rng != null ? rng.nextFloat() : FastMath.nextRandomFloat()) * FastMath.TWO_PI;
								float sy = (rng != null ? rng.nextFloat() : FastMath.nextRandomFloat()) * cactH;
								float sd = 0.3f + (rng != null ? rng.nextFloat() : FastMath.nextRandomFloat()) * 0.2f;
								Box spineBox = new Box(0.04f, 0.04f, sd);
								Geometry spine = new Geometry("CactS"+cacti.size()+"_"+j, spineBox);
								spine.setMaterial(spineMat);
								spine.setLocalRotation(new Quaternion().fromAngleAxis(sa, Vector3f.UNIT_Y));
								spine.setLocalTranslation(cx + FastMath.cos(sa)*0.3f, sy, cz + FastMath.sin(sa)*0.3f);
								wallNode.attachChild(spine);
								cd.spines.add(spine);
						}
				}

        /** Кактусы-препятствия внутри пустынной арены */
				private void buildDesertCacti(PhysicsSpace space) {
						Material cactMat  = litMat(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
						Material spineMat = litMat(assetManager, new ColorRGBA(0.85f,0.82f,0.65f,1f));
						int cactusCount = 12;
						for (int i = 0; i < cactusCount; i++) {
								float cx, cz;
								do {
										cx = (cactusRng.nextFloat() - 0.5f) * (mapHalf * 2f - 8f);
										cz = (cactusRng.nextFloat() - 0.5f) * (mapHalf * 2f - 8f);
								} while (Math.abs(cx) < 6f && Math.abs(cz) < 6f);
								spawnCactusAt(space, cactMat, spineMat, cx, cz, cactusRng);
						}
				}

				private boolean bodyContainsWithHeight(SnakePlayer snake, Vector3f point, float radius) {
						for (int i = 1; i < snake.getSegmentPositions().size(); i++) {
								Vector3f segPos = snake.getSegmentPositions().get(i);
								float dx = point.x - segPos.x;
								float dz = point.z - segPos.z;
								float dy = point.y - segPos.y;
								float dist = (float)Math.sqrt(dx * dx + dz * dz + dy * dy * 0.3f); // меньше по Y
								if (dist < radius) return true;
						}
						return false;
				}

        /** Металлические ограждения вместо кирпичей */
        private void buildMetalFence(PhysicsSpace space) {
            float wH = 3.5f;

            // Физические коллайдеры стен — точно по периметру
            Material colMat = litMat(assetManager, new ColorRGBA(0.45f,0.48f,0.52f,1f));
            addBox(new Vector3f(0,     wH/2,  mapHalf-0.5f), new Vector3f(mapHalf,wH/2,0.5f), colMat, space);
            addBox(new Vector3f(0,     wH/2, -mapHalf+0.5f), new Vector3f(mapHalf,wH/2,0.5f), colMat, space);
            addBox(new Vector3f(-mapHalf+0.5f, wH/2, 0),      new Vector3f(0.5f,wH/2,mapHalf), colMat, space);
            addBox(new Vector3f( mapHalf-0.5f, wH/2, 0),      new Vector3f(0.5f,wH/2,mapHalf), colMat, space);

            // Визуальные металлические стойки и перемычки
            Material postMat = litMat(assetManager, new ColorRGBA(0.55f,0.58f,0.62f,1f));
            Material railMat = litMat(assetManager, new ColorRGBA(0.40f,0.43f,0.48f,1f));
            Material railHighMat = litMat(assetManager, new ColorRGBA(0.70f,0.72f,0.78f,1f));

            float postSpacing = 4f;
            int postCount = (int)(mapHalf*2 / postSpacing) + 1;

            for (int i=0; i<postCount; i++) {
                float px = -mapHalf + i * postSpacing;
                if (px > mapHalf) px = mapHalf;

                // Стойки по всем 4 сторонам
                spawnPost(px, mapHalf-0.5f, postMat);
                spawnPost(px, -mapHalf+0.5f, postMat);
                spawnPost(mapHalf-0.5f, px, postMat);
                spawnPost(-mapHalf+0.5f, px, postMat);
            }

            // Рельсы (горизонтальные перекладины) — 3 уровня
            float[] railY = {0.7f, 1.6f, 2.8f};
            for (float ry : railY) {
                // Север
                addBox(new Vector3f(0, ry, mapHalf-0.5f), new Vector3f(mapHalf,0.06f,0.06f), railMat, null);
                // Юг
                addBox(new Vector3f(0, ry, -mapHalf+0.5f), new Vector3f(mapHalf,0.06f,0.06f), railMat, null);
                // Запад
                addBox(new Vector3f(-mapHalf+0.5f, ry, 0), new Vector3f(0.06f,0.06f,mapHalf), railMat, null);
                // Восток
                addBox(new Vector3f(mapHalf-0.5f, ry, 0), new Vector3f(0.06f,0.06f,mapHalf), railMat, null);
            }

            // Верхний рейл — яркий/светлый
            addBox(new Vector3f(0, wH, mapHalf-0.5f), new Vector3f(mapHalf,0.08f,0.08f), railHighMat, null);
            addBox(new Vector3f(0, wH, -mapHalf+0.5f), new Vector3f(mapHalf,0.08f,0.08f), railHighMat, null);
            addBox(new Vector3f(-mapHalf+0.5f, wH, 0), new Vector3f(0.08f,0.08f,mapHalf), railHighMat, null);
            addBox(new Vector3f(mapHalf-0.5f, wH, 0), new Vector3f(0.08f,0.08f,mapHalf), railHighMat, null);
        }

        private void spawnPost(float x, float z, Material mat) {
            addBox(new Vector3f(x, 1.75f, z), new Vector3f(0.12f, 1.75f, 0.12f), mat, null);
        }

        /** Ямы со шипами (карта 2) — шипы на уровне пола, выдвигаются/задвигаются */
        private void buildPits(PhysicsSpace space) {
            pitNode = new Node("Pits");
            rootNode.attachChild(pitNode);
            pits.clear();

            Material pitFloor  = litMat(assetManager, new ColorRGBA(0.10f,0.08f,0.12f,1f));
            Material wallMat   = litMat(assetManager, new ColorRGBA(0.14f,0.12f,0.18f,1f));
            Material warningMat= unshaded(assetManager, new ColorRGBA(0.90f,0.30f,0.10f,1f)); // оранжевое кольцо предупреждения
            // Металлические шипы с освещением и тенями
            Material spikeMat  = litMat(assetManager, new ColorRGBA(0.82f,0.84f,0.88f,1f));
            spikeMat.setColor("Specular", new ColorRGBA(0.95f, 0.96f, 1.00f, 1f));
            spikeMat.setFloat("Shininess", 64f);
            Material spikeTipMat = litMat(assetManager, new ColorRGBA(0.96f,0.97f,1.00f,1f));
            spikeTipMat.setColor("Specular", new ColorRGBA(1f, 1f, 1f, 1f));
            spikeTipMat.setFloat("Shininess", 128f);

            for (float[] pp : PIT_POSITIONS) {
                float px = pp[0], pz = pp[1];
                PitData pit = new PitData();
                pit.position = new Vector3f(px, 0f, pz);
                pit.radius = PIT_RADIUS;
                // Разные начальные задержки — каждая яма работает в своём ритме
                pit.stateTimer = (float)(pits.size()) * (PIT_RETRACTED_DURATION / PIT_POSITIONS.length)
                        + FastMath.nextRandomFloat() * PIT_RETRACTED_DURATION * 0.5f;

                // Дно ямы (визуальный декор на уровне пола)
                Box floor = new Box(PIT_RADIUS * 0.9f, 0.15f, PIT_RADIUS * 0.9f);
                Geometry floorGeo = new Geometry("PitFloor", floor);
                floorGeo.setMaterial(pitFloor);
								floorGeo.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.Receive);
                floorGeo.setLocalTranslation(px, -0.22f, pz);
                pitNode.attachChild(floorGeo);

                // Рамка ямы опущена на уровень пола (было 0.05f → -0.18f)
                addBox(new Vector3f(px, -0.18f, pz + PIT_RADIUS), new Vector3f(PIT_RADIUS + 0.15f, 0.04f, 0.15f), wallMat, null);
                addBox(new Vector3f(px, -0.18f, pz - PIT_RADIUS), new Vector3f(PIT_RADIUS + 0.15f, 0.04f, 0.15f), wallMat, null);
                addBox(new Vector3f(px + PIT_RADIUS, -0.18f, pz), new Vector3f(0.15f, 0.04f, PIT_RADIUS), wallMat, null);
                addBox(new Vector3f(px - PIT_RADIUS, -0.18f, pz), new Vector3f(0.15f, 0.04f, PIT_RADIUS), wallMat, null);

                // Предупреждающий декал (тёмный круг) на полу
                Geometry holeDecal = new Geometry("HoleDecal", new Box(PIT_RADIUS - 0.2f, 0.01f, PIT_RADIUS - 0.2f));
                holeDecal.setMaterial(unshaded(assetManager, new ColorRGBA(0.06f,0.05f,0.09f,1f)));
                holeDecal.setLocalTranslation(px, -0.19f, pz);
                pitNode.attachChild(holeDecal);
                pit.decal = holeDecal;

                // Предупреждающее кольцо (мигает перед выдвижением)
                Geometry warnRing = new Geometry("WarnRing_"+px+"_"+pz, new Box(PIT_RADIUS - 0.1f, 0.02f, PIT_RADIUS - 0.1f));
                warnRing.setMaterial(unshaded(assetManager, new ColorRGBA(0.9f,0.3f,0.1f,0f)));
                Material warnMat2 = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                warnMat2.setColor("Color", new ColorRGBA(0.9f,0.3f,0.1f,0f));
                warnMat2.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
                warnRing.setMaterial(warnMat2);
                warnRing.setLocalTranslation(px, -0.17f, pz);
                pitNode.attachChild(warnRing);
                pit.warningRing = warnRing;

                // Шипы — стартуют НИЖЕ пола (задвинуты), выдвигаются наверх
                int spikeRows = 4;
                float spikeH = 0.9f;   // полная высота шипа
                float spikeBaseW = 0.09f;
                for (int si = 0; si < spikeRows; si++) {
                    for (int sj = 0; sj < spikeRows; sj++) {
                        float sx = px - PIT_RADIUS * 0.68f + si * (PIT_RADIUS * 1.36f / (spikeRows - 1));
                        float sz = pz - PIT_RADIUS * 0.68f + sj * (PIT_RADIUS * 1.36f / (spikeRows - 1));

                        // Основание (ствол) шипа
                        Geometry spike = new Geometry("SpikeBody", new Box(spikeBaseW, spikeH / 2f, spikeBaseW));
                        spike.setMaterial(spikeMat);
                        spike.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                        // Задвинут: Y = -0.2 - spikeH (полностью под полом)
                        spike.setLocalTranslation(sx, -0.22f - spikeH, sz);
                        pitNode.attachChild(spike);

                        // Острие шипа (конус через масштабированную сферу)
                        Geometry tip = new Geometry("SpikeTip", new Sphere(4, 4, spikeBaseW * 1.5f));
                        tip.setMaterial(spikeTipMat);
                        tip.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                        tip.setLocalScale(1f, 4f, 1f);
                        tip.setLocalTranslation(sx, -0.22f - spikeH / 2f - spikeH, sz);
                        pitNode.attachChild(tip);

                        // Блестящий кончик
                        Geometry glint = new Geometry("SpikeGlint", new Sphere(3, 3, spikeBaseW * 0.7f));
                        glint.setMaterial(litMat(assetManager, new ColorRGBA(0.9f, 0.95f, 1f, 1f)));
                        glint.setShadowMode(RenderQueue.ShadowMode.Cast);
                        glint.setLocalTranslation(sx, -0.22f - spikeH, sz);
                        pitNode.attachChild(glint);

                        pit.spikes.add(spike);
                        pit.spikeTips.add(tip);
                        pit.spikeGlints.add(glint);
                    }
                }

                pits.add(pit);
            }
        }

        // ── Облака ────────────────────────────────────────────────────────
				private void buildClouds() {
						cloudNode = new Node("Clouds");
						rootNode.attachChild(cloudNode);
						if (mapIndex == 2) return;

						cloudMaterial = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						cloudMaterial.setColor("Color", new ColorRGBA(0.90f, 0.92f, 0.96f, 0.42f));
						cloudMaterial.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                        cloudMaterial.getAdditionalRenderState().setDepthWrite(false);

						Random rng = new Random(42);
						for (int i = 0; i < 25; i++) {
								Node cloud = new Node("Cloud" + i);
								float baseRadius = 4f + rng.nextFloat() * 8f;
								// основное тело
								Geometry main = new Geometry("CloudMain", new Sphere(8, 10, baseRadius));
								main.setMaterial(cloudMaterial);
								cloud.attachChild(main);
								// дополнительный шарик для объёма
								Geometry second = new Geometry("CloudSecond", new Sphere(6, 8, baseRadius * 0.7f));
								second.setLocalTranslation(baseRadius * 0.8f, 0, 0);
								second.setMaterial(cloudMaterial);
								cloud.attachChild(second);
								// ещё один
								Geometry third = new Geometry("CloudThird", new Sphere(5, 7, baseRadius * 0.5f));
								third.setLocalTranslation(-baseRadius * 0.6f, baseRadius * 0.2f, 0);
								third.setMaterial(cloudMaterial);
								cloud.attachChild(third);

								cloud.setLocalTranslation(
										(rng.nextFloat() - 0.5f) * mapHalf * 3f,
										14f + rng.nextFloat() * 10f,
										(rng.nextFloat() - 0.5f) * mapHalf * 3f
								);
								cloudNode.attachChild(cloud);
						}
				}

        private void updateCloudBrightness(float brightness) {
            if (cloudMaterial == null) return;
            float b = FastMath.clamp(brightness, 0f, 1f);
            // Облака остаются видимыми, но ночью больше не светятся как лампы.
            cloudMaterial.setColor("Color", new ColorRGBA(
                    0.20f + 0.70f * b,
                    0.22f + 0.70f * b,
                    0.28f + 0.68f * b,
                    0.28f + 0.14f * b));
        }

        // ── Змеи ──────────────────────────────────────────────────────────
        private static final ColorRGBA[] SNAKE_COLORS = {
                new ColorRGBA(0.15f,0.9f,0.3f,1f), new ColorRGBA(0.9f,0.3f,0.1f,1f),
                new ColorRGBA(0.2f,0.5f,1.0f,1f),  new ColorRGBA(0.9f,0.8f,0.1f,1f)
        };
        private static final Vector3f[] START_POS = {
                new Vector3f(-8,0.3f,0), new Vector3f(8,0.3f,0),
                new Vector3f(0,0.3f,-8), new Vector3f(0,0.3f,8)
        };
        private static final float[] START_ANGLES = {FastMath.PI, 0f, FastMath.HALF_PI, -FastMath.HALF_PI};

				private void createSnakes() {

						for (int i = 0; i < allPlayers.size(); i++) {

								Node sn = new Node("Snake" + i);
								rootNode.attachChild(sn);

								String nick = allPlayers.get(i);

								ColorRGBA snakeColor = mapSettings.forceSnakeColor && mapSettings.forcedSnakeColor != null
                                                ? mapSettings.forcedSnakeColor.clone()
                                                : playerColors.getOrDefault(
												nick,
												SNAKE_COLORS[i % SNAKE_COLORS.length]
								);

								Material mat = litMat(assetManager, snakeColor);

								SnakePlayer sp = new SnakePlayer(
												nick,
												START_POS[i % START_POS.length].clone(),
												START_ANGLES[i % START_ANGLES.length],
												mat,
												sn,
												assetManager,
												guiNode,
												cam,
												bulletAppState.getPhysicsSpace(),
												this
								);

								snakes.add(sp);
						}
				}

        // ── Еда ───────────────────────────────────────────────────────────
        private void spawnFood(int count) {
            foodNode = new Node("Food"); rootNode.attachChild(foodNode);
            if (solo) { for (int i=0;i<count;i++) addOneFood(); }
        }

        private void addOneFood() {
            if (foodItems.size() >= effectiveMaxFood) return;
            boolean bad = mapSettings.enableBadFood && foodItems.stream().filter(f->f.bad&&!f.isDebris).count()<BAD_FOOD && FastMath.nextRandomFloat()<0.25f;
            float x = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(mapHalf*2-6), -mapHalf+2f, mapHalf-2f);
            float z = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(mapHalf*2-6), -mapHalf+2f, mapHalf-2f);
            addFoodWithData(foodIdCounter++, x, z, bad, false);
        }

        private void addFoodWithData(int id, float x, float z, boolean bad, boolean isDebris) {
            x = FastMath.clamp(x, -mapHalf+1.5f, mapHalf-1.5f);
            z = FastMath.clamp(z, -mapHalf+1.5f, mapHalf-1.5f);
            Material mat;
            float radius;
            if (isDebris) { mat = litMat(assetManager, new ColorRGBA(0.85f,0.85f,0.85f,1f)); radius = 0.32f; }
            else if (bad) { mat = litMat(assetManager, new ColorRGBA(0.42f,0.26f,0.12f,1f)); radius = 0.50f; }
            else {
                Material[] goodMats = {
                        litMat(assetManager, new ColorRGBA(0.95f,0.2f,0.2f,1f)),
                        litMat(assetManager, new ColorRGBA(0.2f,0.4f,1f,1f)),
                        litMat(assetManager, new ColorRGBA(1f,0.85f,0.1f,1f)),
                        litMat(assetManager, new ColorRGBA(0.8f,0.2f,0.9f,1f)),
                        litMat(assetManager, new ColorRGBA(0.1f,0.9f,0.7f,1f))
                };
                mat = goodMats[FastMath.nextRandomInt(0, goodMats.length-1)]; radius = 0.38f;
            }
            Geometry geo = new Geometry("Food"+(isDebris?"D":""), new Sphere(12,12,radius));
            geo.setMaterial(mat);
            geo.setShadowMode(RenderQueue.ShadowMode.Cast);
            geo.setLocalTranslation(x, isDebris?0.4f:1.8f, z);
            RigidBodyControl phy = new RigidBodyControl(new SphereCollisionShape(radius), 1f);
            geo.addControl(phy); bulletAppState.getPhysicsSpace().add(phy);
            if (isDebris) {
                phy.setLinearVelocity(new Vector3f((FastMath.nextRandomFloat()-0.5f)*4f, 1.5f, (FastMath.nextRandomFloat()-0.5f)*4f));
            }
            foodNode.attachChild(geo);
            foodItems.add(new FoodItem(geo, bad, id, isDebris));
        }

        private void hostAddAndBroadcastFood() {
            if (foodItems.stream().filter(f->!f.isDebris).count()>=effectiveMaxFood) return;
            boolean bad = mapSettings.enableBadFood && foodItems.stream().filter(f->f.bad&&!f.isDebris).count()<BAD_FOOD && FastMath.nextRandomFloat()<0.25f;
            float x = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(mapHalf*2-6),-mapHalf+2f,mapHalf-2f);
            float z = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(mapHalf*2-6),-mapHalf+2f,mapHalf-2f);
            int id = foodIdCounter++;
            addFoodWithData(id, x, z, bad, false);
            sendNet("FOOD|"+id+"|"+x+"|"+z+"|"+(bad?1:0)+"|0");
        }

        private void removeFood(FoodItem fi) {
            RigidBodyControl phy = fi.geo.getControl(RigidBodyControl.class);
            if (phy!=null) { phy.setEnabled(false); bulletAppState.getPhysicsSpace().remove(phy); }
            foodNode.detachChild(fi.geo); foodItems.remove(fi);
        }

        private void removeFoodById(int id) {
            foodItems.stream().filter(f->f.id==id).findFirst().ifPresent(this::removeFood);
        }

        private void spawnDebrisFromDeath(List<Vector3f> positions, int playerIndex) {
            for (Vector3f pos : positions) {
                int id = foodIdCounter++;
                float dx = FastMath.clamp(pos.x, -mapHalf+1.5f, mapHalf-1.5f);
                float dz = FastMath.clamp(pos.z, -mapHalf+1.5f, mapHalf-1.5f);
                addFoodWithData(id, dx, dz, false, true);
                if (!solo) sendNet("DEBRIS|"+id+"|"+dx+"|"+dz);
            }
        }

        // ── HUD ───────────────────────────────────────────────────────────
        private void buildHUD() {
            BitmapFont font = loadFont(assetManager);
            float W = cam.getWidth(), H = cam.getHeight();

            // Таймер – по центру сверху
            gameTimerText = new BitmapText(font);
            gameTimerText.setSize(18);
            gameTimerText.setColor(new ColorRGBA(0.9f,0.9f,0.9f,0.9f));
            gameTimerText.setLocalTranslation(W/2f - gameTimerText.getLineWidth()/2, H - 22, 0);
            guiNode.attachChild(gameTimerText);

            // Список игроков больше не висит сверху слева: показывается только по TAB.
            huds.clear();
            buildPlayerTabOverlay(font, W, H);
            buildNetStatsHUD(font, W, H);

            // Центральное сообщение (появляется при событиях)
            centerMsg = new BitmapText(font);
            centerMsg.setSize(38);
            centerMsg.setColor(new ColorRGBA(1f, 1f, 0.2f, 0f));
            guiNode.attachChild(centerMsg);

            buildDashCircleHUD(font, W, H);
            updatePlayerTabText();
            updateStatsHUD(0f);
            updateDashHUD();
        }

        private Mesh createDiscMesh(float radius, float fill01) {
            int seg = 48;
            fill01 = FastMath.clamp(fill01, 0f, 1f);
            int used = Math.max(1, Math.round(seg * fill01));
            Vector3f[] verts = new Vector3f[used + 2];
            int[] idx = new int[used * 3];
            verts[0] = new Vector3f(0,0,0);
            float start = FastMath.HALF_PI;
            for (int i=0; i<=used; i++) {
                float a = start - FastMath.TWO_PI * (i / (float)seg);
                verts[i+1] = new Vector3f(FastMath.cos(a)*radius, FastMath.sin(a)*radius, 0);
            }
            for (int i=0; i<used; i++) {
                idx[i*3] = 0;
                idx[i*3+1] = i+1;
                idx[i*3+2] = i+2;
            }
            Mesh m = new Mesh();
            m.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(verts));
            m.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
            m.updateBound();
            return m;
        }

        private Mesh createRingMesh(float innerRadius, float outerRadius, float fill01) {
            int seg = 64;
            fill01 = FastMath.clamp(fill01, 0f, 1f);
            int used = Math.max(1, Math.round(seg * fill01));
            Vector3f[] verts = new Vector3f[(used + 1) * 2];
            int[] idx = new int[used * 6];
            float start = FastMath.HALF_PI;
            for (int i = 0; i <= used; i++) {
                float a = start - FastMath.TWO_PI * (i / (float)seg);
                verts[i * 2]     = new Vector3f(FastMath.cos(a) * innerRadius, FastMath.sin(a) * innerRadius, 0);
                verts[i * 2 + 1] = new Vector3f(FastMath.cos(a) * outerRadius, FastMath.sin(a) * outerRadius, 0);
            }
            for (int i = 0; i < used; i++) {
                int v = i * 2;
                idx[i * 6]     = v;
                idx[i * 6 + 1] = v + 1;
                idx[i * 6 + 2] = v + 2;
                idx[i * 6 + 3] = v + 1;
                idx[i * 6 + 4] = v + 3;
                idx[i * 6 + 5] = v + 2;
            }
            Mesh m = new Mesh();
            m.setBuffer(com.jme3.scene.VertexBuffer.Type.Position, 3, BufferUtils.createFloatBuffer(verts));
            m.setBuffer(com.jme3.scene.VertexBuffer.Type.Index, 3, BufferUtils.createIntBuffer(idx));
            m.updateBound();
            return m;
        }


        private void buildDashCircleHUD(BitmapFont font, float W, float H) {
            float S = uiScale(cam);
            float cx = W - 76f * S;
            float cy = 74f * S;
            Material bgMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            bgMat.setColor("Color", new ColorRGBA(0f,0f,0f,0.62f));
            bgMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            dashCircleBg = new Geometry("DashCircleBg", createRingMesh(30f * S, 42f * S, 1f));
            dashCircleBg.setMaterial(bgMat);
            dashCircleBg.setLocalTranslation(cx, cy, 2f);
            guiNode.attachChild(dashCircleBg);

            Material fillMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            fillMat.setColor("Color", new ColorRGBA(0.13f,0.76f,1f,0.82f));
            fillMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            dashCircleFill = new Geometry("DashCircleFill", createRingMesh(30f * S, 42f * S, 1f));
            dashCircleFill.setMaterial(fillMat);
            dashCircleFill.setLocalTranslation(cx, cy, 3f);
            guiNode.attachChild(dashCircleFill);

            dashShiftText = new BitmapText(font);
            dashShiftText.setSize(16f * S);
            dashShiftText.setText("Shift");
            dashShiftText.setColor(TEXT);
            dashShiftText.setLocalTranslation(cx - dashShiftText.getLineWidth()/2f, cy + 6f, 4f);
            guiNode.attachChild(dashShiftText);

            dashCooldownText = new BitmapText(font);
            dashCooldownText.setSize(12f * S);
            dashCooldownText.setColor(ACCENT2);
            dashCooldownText.setText("ГОТОВ");
            dashCooldownText.setLocalTranslation(cx - dashCooldownText.getLineWidth()/2f, cy - 18f * S, 4f);
            guiNode.attachChild(dashCooldownText);
        }        private void buildNetStatsHUD(BitmapFont font, float W, float H) {
            float S = uiScale(cam);
            netStatsBg = new Geometry("NetStatsBg", new Box(190f * S, 18f * S, 0.2f));
            Material m = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            m.setColor("Color", new ColorRGBA(0f,0f,0f,0.58f));
            m.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            netStatsBg.setMaterial(m);
            netStatsBg.setLocalTranslation(206f * S, H - 28f * S, 2f);
            guiNode.attachChild(netStatsBg);

            netStatsText = new BitmapText(font);
            netStatsText.setSize(13f * S);
            netStatsText.setColor(TEXT);
            netStatsText.setLocalTranslation(22f * S, H - 23f * S, 3f);
            guiNode.attachChild(netStatsText);
        }


        private void buildPlayerTabOverlay(BitmapFont font, float W, float H) {
            playerTabNode = new Node("PlayerTabOverlay");
            Geometry bg = new Geometry("PlayerTabBg", new Box(250f, 160f, 0.4f));
            Material bm = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            bm.setColor("Color", new ColorRGBA(0f,0f,0f,0.72f));
            bm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
            bg.setMaterial(bm);
            bg.setLocalTranslation(W/2f, H/2f + 80f, 6f);
            playerTabNode.attachChild(bg);

            BitmapText title = new BitmapText(font);
            title.setSize(20);
            title.setText("ИГРОКИ");
            title.setColor(ACCENT2);
            title.setLocalTranslation(W/2f - 42f, H/2f + 225f, 7f);
            playerTabNode.attachChild(title);

            playerTabText = new BitmapText(font);
            playerTabText.setSize(16);
            playerTabText.setColor(TEXT);
            playerTabText.setLocalTranslation(W/2f - 210f, H/2f + 185f, 7f);
            playerTabNode.attachChild(playerTabText);

            guiNode.attachChild(playerTabNode);
            playerTabNode.setCullHint(Spatial.CullHint.Always);
        }

        private void setPlayerTabVisible(boolean visible) {
            playerTabVisible = visible;
            if (playerTabNode != null) playerTabNode.setCullHint(visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (visible) updatePlayerTabText();
        }

				public float getDayBrightness() {
						// dayNightTime в GameState — позиция в цикле [0, TOTAL_CYCLE)
						float dayFactor = dayNightTime < DAY_DURATION
								? FastMath.sin((dayNightTime / DAY_DURATION) * FastMath.PI)
								: 0f;
						return FastMath.clamp(dayFactor, 0f, 1f);
				}

        private void updatePlayerTabText() {
            if (playerTabText == null) return;
            StringBuilder sb = new StringBuilder();
            for (int i=0; i<snakes.size(); i++) {
                SnakePlayer s = snakes.get(i);
                sb.append(i+1).append(". ").append(s.getName())
                  .append(i == myIndex ? "  (ВЫ)" : "")
                  .append(s.isDead() ? "  ✖" : "  ●")
                  .append("  ").append(s.getScore()).append(" очк.\n");
            }
            playerTabText.setText(sb.toString());
        }

        private void updateStatsHUD(float tpf) {
            if (netStatsText == null) return;
            if (tpf > 0f) {
                float fps = 1f / Math.max(0.0001f, tpf);
                fpsSmooth = fpsSmooth <= 0f ? fps : fpsSmooth * 0.92f + fps * 0.08f;
                fpsSum += fps;
                fpsFrames++;
            }
            fpsHudTimer -= tpf;
            if (fpsHudTimer <= 0f) {
                fpsHudTimer = 0.25f;
                int avg = fpsFrames == 0 ? Math.round(fpsSmooth) : Math.round(fpsSum / fpsFrames);
                long ping = (solo || isHost) ? 0L : serverPingMs;
                netStatsText.setText("FPS: " + Math.round(fpsSmooth) + "   AVG: " + avg + "   PING: " + ping + " ms");
            }
        }

        private void showCenter(String msg, ColorRGBA c) {
            centerMsg.setText(msg); centerMsg.setColor(c);
            float W=cam.getWidth(), H=cam.getHeight();
            centerMsg.setLocalTranslation(W/2-centerMsg.getLineWidth()/2, H/2, 0);
            centerMsgTimer = 4f;
        }

        // ── Управление ────────────────────────────────────────────────────
        private void setupControls() {
            inputManager.addMapping("Left",  new KeyTrigger(KeyInput.KEY_A), new KeyTrigger(KeyInput.KEY_LEFT));
            inputManager.addMapping("Right", new KeyTrigger(KeyInput.KEY_D), new KeyTrigger(KeyInput.KEY_RIGHT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (freeCameraMode || gameOver || myIndex>=snakes.size()) return;
                SnakePlayer me = snakes.get(myIndex);
                if ("Left".equals(n))  me.setTurnLeft(p);
                if ("Right".equals(n)) me.setTurnRight(p);
            }, "Left", "Right");

            inputManager.addMapping("Forward", new KeyTrigger(KeyInput.KEY_W));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (freeCameraMode || gameOver || myIndex>=snakes.size()) return;
                snakes.get(myIndex).setMoving(p);
            }, "Forward");

            // Рывок
            inputManager.addMapping("Dash", new KeyTrigger(KeyInput.KEY_LSHIFT), new KeyTrigger(KeyInput.KEY_RSHIFT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (freeCameraMode || !p || gameOver || myIndex>=snakes.size()) return;
                if (dashCooldown <= 0f) {
                    dashTimer = DASH_DURATION;
                    dashCooldown = DASH_COOLDOWN_MAX;
                }
            }, "Dash");

            inputManager.addMapping("SpecPrev", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
            inputManager.addMapping("SpecNext", new MouseButtonTrigger(MouseInput.BUTTON_RIGHT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p || !spectating || gameoverUIActive) return;
                if ("SpecPrev".equals(n)) spectateTarget=(spectateTarget-1+snakes.size())%snakes.size();
                if ("SpecNext".equals(n)) spectateTarget=(spectateTarget+1)%snakes.size();
                for (int i=0;i<snakes.size();i++) if (!snakes.get(spectateTarget).isDead()) break;
                else spectateTarget=(spectateTarget+1)%snakes.size();
            }, "SpecPrev", "SpecNext");

            // Пауза и чит-код через RawInputListener
            inputManager.addRawInputListener(new com.jme3.input.RawInputListener() {
                @Override public void onKeyEvent(com.jme3.input.event.KeyInputEvent evt) {
                    int code = evt.getKeyCode();
                    if (code == KeyInput.KEY_TAB) {
                        app.enqueue(() -> setPlayerTabVisible(evt.isPressed()));
                        return;
                    }
                    if (!evt.isPressed()) return;

                    // Пауза ESC
                    if (code == KeyInput.KEY_ESCAPE) {
                        app.enqueue(() -> togglePause());
                        return;
                    }

                    // Чит-код KOPRFDC (только соло или хост)
                    if (solo || isHost) {
                        if (code == CHEAT_CODE[cheatCodeIndex]) {
                            cheatCodeIndex++;
                            if (cheatCodeIndex >= CHEAT_CODE.length) {
                                cheatCodeIndex = 0;
                                app.enqueue(() -> activateCheatCode());
                            }
                        } else {
                            cheatCodeIndex = (code == CHEAT_CODE[0]) ? 1 : 0;
                        }

                        if (code == FREE_CAM_CHEAT_CODE[freeCamCheatIndex]) {
                            freeCamCheatIndex++;
                            if (freeCamCheatIndex >= FREE_CAM_CHEAT_CODE.length) {
                                freeCamCheatIndex = 0;
                                app.enqueue(() -> toggleFreeCameraCheat());
                            }
                        } else {
                            freeCamCheatIndex = (code == FREE_CAM_CHEAT_CODE[0]) ? 1 : 0;
                        }
                    }
                }
                @Override public void beginInput() {}
                @Override public void endInput() {}
                @Override public void onMouseMotionEvent(com.jme3.input.event.MouseMotionEvent evt) {}
                @Override public void onMouseButtonEvent(com.jme3.input.event.MouseButtonEvent evt) {}
                @Override public void onJoyAxisEvent(com.jme3.input.event.JoyAxisEvent evt) {}
                @Override public void onJoyButtonEvent(com.jme3.input.event.JoyButtonEvent evt) {}
                @Override public void onTouchEvent(com.jme3.input.event.TouchEvent evt) {}
            });
        }

				private void togglePause() {
						if (gameOver || gameoverUIActive) return;

						// В мультиплеере меню ESC не стопорит мир, но pauseActive нужен мышиным кнопкам.
							if (!solo) {
									pauseActive = !pauseActive;
									if (pauseActive) {
											if (pauseNode == null) buildPauseUI();
											pauseNode.setCullHint(Spatial.CullHint.Inherit);
											if (inputManager != null) inputManager.setCursorVisible(true);
									} else {
											pauseSettingsOpen = false;

											if (pauseSettingsNode != null) {
													pauseSettingsNode.setCullHint(Spatial.CullHint.Always);
											}

											if (pauseNode != null) pauseNode.setCullHint(Spatial.CullHint.Always);
											inputManager.setCursorVisible(false);
									}
									return;
							}

						// Одиночная игра: обычная пауза (можно оставить или тоже убрать)
						pauseActive = !pauseActive;
						inputManager.setCursorVisible(pauseActive);
						if (pauseActive) {
								if (pauseNode == null) buildPauseUI();
								pauseNode.setCullHint(Spatial.CullHint.Inherit);
						} else {
								pauseSettingsOpen = false;

								if (pauseSettingsNode != null) {
										pauseSettingsNode.setCullHint(Spatial.CullHint.Always);
								}

								if (pauseNode != null) pauseNode.setCullHint(Spatial.CullHint.Always);
						}
				}        
				private void buildPauseUI() {
            float W = cam.getWidth(), H = cam.getHeight();
            float S = uiScale(cam);
            BitmapFont font = loadFont(assetManager);
            pauseNode = new Node("PauseUI");

            Geometry dimGeo = new Geometry("PauseDim", new Box(W/2f, H/2f, 0.1f));
            Material dimMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            dimMat.setColor("Color", new ColorRGBA(0,0,0,0.68f));
            dimMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            dimGeo.setMaterial(dimMat);
            dimGeo.setLocalTranslation(W/2f, H/2f, 7f);
            pauseNode.attachChild(dimGeo);

            float cardW = 520f * S;
            float cardH = (!solo && isHost) ? 430f * S : 370f * S;
            Geometry cardGeo = new Geometry("PauseCard", new Box(cardW/2f, cardH/2f, 0.5f));
            Material cardMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            cardMat.setColor("Color", new ColorRGBA(0.035f,0.045f,0.10f,0.94f));
            cardMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            cardGeo.setMaterial(cardMat);
            cardGeo.setLocalTranslation(W/2f, H/2f, 7.5f);
            pauseNode.attachChild(cardGeo);

            Geometry line = new Geometry("PauseLine", new Box(cardW/2f - 22f * S, 2f * S, 0.3f));
            line.setMaterial(unshaded(assetManager, ACCENT2));
            line.setLocalTranslation(W/2f, H/2f + cardH/2f - 24f * S, 8f);
            pauseNode.attachChild(line);

            BitmapText pauseTitle = new BitmapText(font);
            pauseTitle.setSize(38f * S);
            pauseTitle.setText("ПАУЗА");
            pauseTitle.setColor(ACCENT2);
            pauseTitle.setLocalTranslation(W/2f - pauseTitle.getLineWidth()/2, H/2f + cardH/2f - 38f * S, 12f);
            pauseNode.attachChild(pauseTitle);

            // Подсказка Esc/free camera удалена из окна паузы, чтобы не мешала кнопкам.

						float pauseBtnW = 330f * S;
						float pauseBtnH = 54f * S;
						float pauseGap = 10f * S;
						float pauseStep = pauseBtnH + pauseGap;

						// Опускаем кнопки ниже заголовка и синей полоски.
						float firstBtnY = H / 2f + 26f * S;

						pauseResumeBtn = new MenuButton(
										"ПРОДОЛЖИТЬ",
										W / 2f,
										firstBtnY,
										pauseBtnW,
										pauseBtnH,
										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										ACCENT,
										assetManager,
										pauseNode,
										9f
						);

						pauseSettingsBtn = new MenuButton(
										"НАСТРОЙКИ",
										W / 2f,
										firstBtnY - pauseStep,
										pauseBtnW,
										pauseBtnH,
										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										ACCENT2,
										assetManager,
										pauseNode,
										9f
						);

						pauseMenuBtn = new MenuButton(
										"В ГЛАВНОЕ МЕНЮ",
										W / 2f,
										firstBtnY - pauseStep * 2f,
										pauseBtnW,
										pauseBtnH,
										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										DANGER,
										assetManager,
										pauseNode,
										9f
						);

						if (!solo && isHost) {
								pauseLobbyBtn = new MenuButton(
												"ВЕРНУТЬ В ЛОББИ",
												W / 2f,
												firstBtnY - pauseStep * 3f,
												pauseBtnW,
												pauseBtnH,
												BTN_NORMAL,
												BTN_HOVER,
												BTN_PRESS,
												ACCENT3,
												assetManager,
												pauseNode,
												9f
								);
								pauseNode.setUserData("hasLobbyBtn", true);
						}

            guiNode.attachChild(pauseNode);
            if (!rebuildingGuiForResize) LemurUi.playPanelPop(pauseNode);
            pauseNode.setCullHint(Spatial.CullHint.Always);

            // Подписываем pause input только один раз. При resize окно пересобирается,
            // но listener-ы не должны дублироваться.
            if (!pauseInputRegistered) {
                if (!inputManager.hasMapping("Pause_Mouse")) {
                    inputManager.addMapping("Pause_Mouse", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
                    inputManager.addMapping("Pause_MouseMove",
                            new MouseAxisTrigger(MouseInput.AXIS_X, false), new MouseAxisTrigger(MouseInput.AXIS_X, true),
                            new MouseAxisTrigger(MouseInput.AXIS_Y, false), new MouseAxisTrigger(MouseInput.AXIS_Y, true));
                }
								inputManager.addListener((AnalogListener)(n,v,t) -> {
										if (!pauseActive) return;

										com.jme3.math.Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
										if (mp == null) return;

										float mx = mp.x;
										float my = mp.y;

										if (pauseSettingsOpen) {
												handlePauseSettingsHover(mx, my);
												return;
										}

										if (pauseResumeBtn != null) pauseResumeBtn.updateHover(mx, my);
										if (pauseSettingsBtn != null) pauseSettingsBtn.updateHover(mx, my);
										if (pauseMenuBtn != null) pauseMenuBtn.updateHover(mx, my);
										if (pauseLobbyBtn != null) pauseLobbyBtn.updateHover(mx, my);
								}, "Pause_MouseMove");
								inputManager.addListener((ActionListener)(n2,p2,t2) -> {
										if (!pauseActive) return;

										com.jme3.math.Vector2f mp = inputManager != null ? inputManager.getCursorPosition() : null;
										if (mp == null) return;

										float mx = mp.x;
										float my = mp.y;

										// Если открыто окно настроек внутри паузы — обрабатываем только его.
										if (pauseSettingsOpen) {
												handlePauseSettingsClick(p2, mx, my);
												return;
										}

										if (p2) {
												if (pauseResumeBtn != null) pauseResumeBtn.onPress(mx, my);
												if (pauseSettingsBtn != null) pauseSettingsBtn.onPress(mx, my);
												if (pauseMenuBtn != null) pauseMenuBtn.onPress(mx, my);
												if (pauseLobbyBtn != null) pauseLobbyBtn.onPress(mx, my);
										} else {
												if (pauseResumeBtn != null && pauseResumeBtn.onRelease(mx, my)) {
														togglePause();
												} else if (pauseSettingsBtn != null && pauseSettingsBtn.onRelease(mx, my)) {
														openPauseSettings();
												} else if (pauseLobbyBtn != null && pauseLobbyBtn.onRelease(mx, my)) {
														backToLobbyFromHost();
												} else if (pauseMenuBtn != null && pauseMenuBtn.onRelease(mx, my)) {
														backToMenu();
												}
										}
								}, "Pause_Mouse");
                pauseInputRegistered = true;
            }
        }

				private void openPauseSettings() {
						pauseSettingsOpen = true;

						if (pauseSettingsNode == null) {
								buildPauseSettingsUI();
						}

						if (pauseSettingsNode != null) {
								pauseSettingsNode.setCullHint(Spatial.CullHint.Inherit);
								LemurUi.playPanelPop(pauseSettingsNode);
						}

						setPauseSettingsTab(pauseActiveSettingsTab);
						updatePauseSettingsLabels();
				}

				private void closePauseSettings() {
						pauseSettingsOpen = false;

						if (pauseSettingsNode != null) {
								pauseSettingsNode.setCullHint(Spatial.CullHint.Always);
						}

						// ВАЖНО: ник не меняем. Сохраняем текущий myNick.
						saveSettings(myNick);
				}

				private void handlePauseSettingsHover(float mx, float my) {
						if (pauseSettingsCloseBtn != null) pauseSettingsCloseBtn.updateHover(mx, my);
						if (pauseTabMainBtn != null) pauseTabMainBtn.updateHover(mx, my);
						if (pauseTabGraphicsBtn != null) pauseTabGraphicsBtn.updateHover(mx, my);

						if (pauseActiveSettingsTab == 0) {
								boolean changed = false;

								if (pauseSfxSlider != null) {
										pauseSfxSlider.updateHover(mx, my);
										if (pauseSfxSlider.onDrag(mx, my)) {
												effectVolume = pauseSfxSlider.getValue();
												changed = true;
										}
								}

								if (pauseMusicSlider != null) {
										pauseMusicSlider.updateHover(mx, my);
										if (pauseMusicSlider.onDrag(mx, my)) {
												musicVolume = pauseMusicSlider.getValue();
												changed = true;
										}
								}

								if (changed) {
										updatePauseSettingsLabels();
								}
						}

						if (pauseActiveSettingsTab == 1) {
								if (pauseBtnShadows != null) pauseBtnShadows.updateHover(mx, my);
								if (pauseBtnParticles != null) pauseBtnParticles.updateHover(mx, my);
								if (pauseBtnFog != null) pauseBtnFog.updateHover(mx, my);
								if (pauseBtnBloom != null) pauseBtnBloom.updateHover(mx, my);
								if (pauseBtnPost != null) pauseBtnPost.updateHover(mx, my);
								if (pauseBtnLights != null) pauseBtnLights.updateHover(mx, my);
								if (pauseBtnWater != null) pauseBtnWater.updateHover(mx, my);
								if (pauseBtnTerrain != null) pauseBtnTerrain.updateHover(mx, my);
								if (pauseBtnLowPoly != null) pauseBtnLowPoly.updateHover(mx, my);
								if (pauseBtnNames != null) pauseBtnNames.updateHover(mx, my);
						}
				}

				private void applyGraphicsSettingsNow() {
						if (app == null || app.getViewPort() == null || assetManager == null) return;

						ViewPort vp = app.getViewPort();

						// =========================================================
						// 1. ТЕНИ
						// =========================================================
						if (gameShadowRenderer != null) {
								try {
										vp.removeProcessor(gameShadowRenderer);
								} catch (Exception e) {
										logSafe("Graphics.shadow.remove", e);
								}
								gameShadowRenderer = null;
						}

						if (SnakeApp.shadowsEnabled && SnakeApp.dynamicLightsEnabled && sunLight != null) {
								gameShadowRenderer = SnakeApp.createShadowRendererForCurrentQuality(assetManager, sunLight);
								if (gameShadowRenderer != null) {
										vp.addProcessor(gameShadowRenderer);
								}
						}

						// =========================================================
						// 2. POST / BLOOM / FOG / GOD RAYS
						// =========================================================
						if (gameFpp != null) {
								try {
										vp.removeProcessor(gameFpp);
								} catch (Exception e) {
										logSafe("Graphics.fpp.remove", e);
								}
								gameFpp = null;
						}

						gameBloomFilter = null;
						gameFogFilter = null;
						gameLightScattering = null;

						boolean needFpp = SnakeApp.postProcessingEnabled && (SnakeApp.bloomEnabled || SnakeApp.fogEnabled);

						if (needFpp) {
								try {
										gameFpp = new FilterPostProcessor(assetManager);

										if (SnakeApp.bloomEnabled) {
												gameBloomFilter = new BloomFilter(BloomFilter.GlowMode.Objects);
												gameBloomFilter.setBloomIntensity(2.0f);
												gameBloomFilter.setExposurePower(5.0f);
												gameBloomFilter.setBlurScale(1.5f);
												gameFpp.addFilter(gameBloomFilter);

												gameLightScattering = new LightScatteringFilter(new Vector3f(ORBIT_RADIUS, 30f, -10f));
												gameLightScattering.setLightDensity(0.8f);
												gameLightScattering.setBlurStart(0.6f);
												gameLightScattering.setBlurWidth(0.5f);
												gameLightScattering.setNbSamples(150);
												gameLightScattering.setEnabled(true);
												gameFpp.addFilter(gameLightScattering);
										}

										if (SnakeApp.fogEnabled) {
												gameFogFilter = new FogFilter();
												gameFogFilter.setFogColor(new ColorRGBA(0.65f, 0.70f, 0.82f, 1f));
												gameFogFilter.setFogDensity(0.30f);
												gameFogFilter.setFogDistance(140f);
												gameFpp.addFilter(gameFogFilter);
										}

										vp.addProcessor(gameFpp);
								} catch (Exception e) {
										logSafe("Graphics.fpp.create", e);
										gameFpp = null;
										gameBloomFilter = null;
										gameFogFilter = null;
										gameLightScattering = null;
								}
						}

						// =========================================================
						// 3. ДИНАМИЧЕСКИЙ СВЕТ
						// =========================================================
						if (sunLight != null) {
								sunLight.setEnabled(SnakeApp.dynamicLightsEnabled);
						}

						if (ambientLight != null) {
								ambientLight.setEnabled(true);
						}

						// =========================================================
						// 4. НИКИ
						// =========================================================
						// Не нуждается в настройке
						
						// =========================================================
						// 5. СОХРАНЕНИЕ
						// =========================================================
						saveSettings(myNick);
				}

				private void handlePauseSettingsClick(boolean pressed, float mx, float my) {
						if (pressed) {
								if (pauseSettingsCloseBtn != null) pauseSettingsCloseBtn.onPress(mx, my);
								if (pauseTabMainBtn != null) pauseTabMainBtn.onPress(mx, my);
								if (pauseTabGraphicsBtn != null) pauseTabGraphicsBtn.onPress(mx, my);

								if (pauseActiveSettingsTab == 0) {
										boolean changed = false;

										if (pauseSfxSlider != null && pauseSfxSlider.onPress(mx, my)) {
												effectVolume = pauseSfxSlider.getValue();
												changed = true;
										} else if (pauseMusicSlider != null && pauseMusicSlider.onPress(mx, my)) {
												musicVolume = pauseMusicSlider.getValue();
												changed = true;
										}

										if (changed) {
												updatePauseSettingsLabels();
												saveSettings(myNick);
										}
								}

								if (pauseActiveSettingsTab == 1) {
										if (pauseBtnShadows != null) pauseBtnShadows.onPress(mx, my);
										if (pauseBtnParticles != null) pauseBtnParticles.onPress(mx, my);
										if (pauseBtnFog != null) pauseBtnFog.onPress(mx, my);
										if (pauseBtnBloom != null) pauseBtnBloom.onPress(mx, my);
										if (pauseBtnPost != null) pauseBtnPost.onPress(mx, my);
										if (pauseBtnLights != null) pauseBtnLights.onPress(mx, my);
										if (pauseBtnWater != null) pauseBtnWater.onPress(mx, my);
										if (pauseBtnTerrain != null) pauseBtnTerrain.onPress(mx, my);
										if (pauseBtnLowPoly != null) pauseBtnLowPoly.onPress(mx, my);
										if (pauseBtnNames != null) pauseBtnNames.onPress(mx, my);
								}

								return;
						}

						// ── Ползунки ──
						if (pauseActiveSettingsTab == 0 && pauseSfxSlider != null && pauseSfxSlider.onRelease(mx, my)) {
								effectVolume = pauseSfxSlider.getValue();
								updatePauseSettingsLabels();
								saveSettings(myNick);
								return;
						}

						if (pauseActiveSettingsTab == 0 && pauseMusicSlider != null && pauseMusicSlider.onRelease(mx, my)) {
								musicVolume = pauseMusicSlider.getValue();
								updatePauseSettingsLabels();
								saveSettings(myNick);
								return;
						}

						// ── Закрытие ──
						if (pauseSettingsCloseBtn != null && pauseSettingsCloseBtn.onRelease(mx, my)) {
								closePauseSettings();
								return;
						}

						// ── Вкладки ──
						if (pauseTabMainBtn != null && pauseTabMainBtn.onRelease(mx, my)) {
								setPauseSettingsTab(0);
								return;
						}

						if (pauseTabGraphicsBtn != null && pauseTabGraphicsBtn.onRelease(mx, my)) {
								setPauseSettingsTab(1);
								return;
						}

						// ── Графика ──
						if (pauseActiveSettingsTab == 1) {
								if (pauseBtnShadows != null && pauseBtnShadows.onRelease(mx, my)) {
										SnakeApp.shadowQuality = SnakeApp.nextShadowQuality(SnakeApp.shadowQuality);
										SnakeApp.shadowsEnabled = SnakeApp.shadowQuality != SnakeApp.SHADOW_QUALITY_OFF;
										SnakeApp.updateShadowQualityButton(pauseBtnShadows);
										applyGraphicsSettingsNow();

								} else if (pauseBtnParticles != null && pauseBtnParticles.onRelease(mx, my)) {
										SnakeApp.particlesEnabled = !SnakeApp.particlesEnabled;
										setPauseToggleButton(pauseBtnParticles, SnakeApp.particlesEnabled, "ЧАСТИЦЫ");
										applyGraphicsSettingsNow();

								} else if (pauseBtnFog != null && pauseBtnFog.onRelease(mx, my)) {
										SnakeApp.fogEnabled = !SnakeApp.fogEnabled;
										setPauseToggleButton(pauseBtnFog, SnakeApp.fogEnabled, "ТУМАН");
										applyGraphicsSettingsNow();

								} else if (pauseBtnBloom != null && pauseBtnBloom.onRelease(mx, my)) {
										SnakeApp.bloomEnabled = !SnakeApp.bloomEnabled;
										setPauseToggleButton(pauseBtnBloom, SnakeApp.bloomEnabled, "BLOOM");
										applyGraphicsSettingsNow();

								} else if (pauseBtnPost != null && pauseBtnPost.onRelease(mx, my)) {
										SnakeApp.postProcessingEnabled = !SnakeApp.postProcessingEnabled;
										setPauseToggleButton(pauseBtnPost, SnakeApp.postProcessingEnabled, "ПОСТ");
										applyGraphicsSettingsNow();

								} else if (pauseBtnLights != null && pauseBtnLights.onRelease(mx, my)) {
										SnakeApp.dynamicLightsEnabled = !SnakeApp.dynamicLightsEnabled;
										setPauseToggleButton(pauseBtnLights, SnakeApp.dynamicLightsEnabled, "ДИН.СВЕТ");
										applyGraphicsSettingsNow();

								} else if (pauseBtnWater != null && pauseBtnWater.onRelease(mx, my)) {
										SnakeApp.waterEffectsEnabled = !SnakeApp.waterEffectsEnabled;
										setPauseToggleButton(pauseBtnWater, SnakeApp.waterEffectsEnabled, "ВОДА");
										applyGraphicsSettingsNow();

								} else if (pauseBtnTerrain != null && pauseBtnTerrain.onRelease(mx, my)) {
										SnakeApp.terrainDetailsEnabled = !SnakeApp.terrainDetailsEnabled;
										setPauseToggleButton(pauseBtnTerrain, SnakeApp.terrainDetailsEnabled, "ДЕТАЛИ КАРТ");
										applyGraphicsSettingsNow();

								} else if (pauseBtnLowPoly != null && pauseBtnLowPoly.onRelease(mx, my)) {
										SnakeApp.lowPolyMode = !SnakeApp.lowPolyMode;
										setPauseToggleButton(pauseBtnLowPoly, SnakeApp.lowPolyMode, "LOW POLY");
										applyGraphicsSettingsNow();

								} else if (pauseBtnNames != null && pauseBtnNames.onRelease(mx, my)) {
										SnakeApp.nameTagsEnabled = !SnakeApp.nameTagsEnabled;
										setPauseToggleButton(pauseBtnNames, SnakeApp.nameTagsEnabled, "НИКИ");
										applyGraphicsSettingsNow();
								}
						}
				}

				private void buildPauseSettingsUI() {
						float W = cam.getWidth();
						float H = cam.getHeight();
						float cx = W / 2f;
						float cy = H / 2f;
						float S = uiScale(cam);

						BitmapFont font = loadFont(assetManager);

						pauseSettingsNode = new Node("PauseSettingsUI");

						// ── Затемнение поверх окна паузы ──
						Geometry dimGeo = new Geometry("PauseSettingsDim", new Box(W / 2f, H / 2f, 0.1f));
						Material dimMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						dimMat.setColor("Color", new ColorRGBA(0f, 0f, 0f, 0.76f));
						dimMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						dimGeo.setMaterial(dimMat);
						dimGeo.setLocalTranslation(cx, cy, 45f);
						pauseSettingsNode.attachChild(dimGeo);

						// ── Основная карточка, почти как в главном меню ──
						float panelMaxW = 760f * S;
						float panelMaxH = 650f * S;
						float panelW = Math.min(panelMaxW, W - 90f * S);
						float panelH = Math.min(panelMaxH, H - 80f * S);

						Geometry panelGeo = new Geometry("PauseSettingsPanelBg", new Box(panelW / 2f, panelH / 2f, 0.5f));
						Material panelMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						panelMat.setColor("Color", BG_CARD);
						panelMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						panelGeo.setMaterial(panelMat);
						panelGeo.setLocalTranslation(cx, cy, 46f);
						pauseSettingsNode.attachChild(panelGeo);

						final float Z = 50f;

						// ── Акцентная полоса сверху ──
						Geometry headerLine = new Geometry("PauseSettingsHeaderLine", new Box(panelW / 2f, 3f * S, 0.3f));
						headerLine.setMaterial(unshaded(assetManager, ACCENT));
						headerLine.setLocalTranslation(cx, cy + panelH / 2f - 2f * S, Z);
						pauseSettingsNode.attachChild(headerLine);

						// ── Заголовок ──
						BitmapText header = new BitmapText(font);
						header.setSize(28f * S);
						header.setText("НАСТРОЙКИ");
						header.setColor(ACCENT2);
						header.setLocalTranslation(cx - header.getLineWidth() / 2f, cy + panelH / 2f - 30f * S, Z);
						pauseSettingsNode.attachChild(header);

						// ── Вкладки ──
						float tabY = cy + panelH / 2f - 90f * S;
						float tabW = 180f * S;
						float tabH = 38f * S;
						float tabGap = 10f * S;

						pauseTabMainBtn = new MenuButton(
										"ОСНОВНОЕ",
										cx - tabW / 2f - tabGap / 2f,
										tabY,
										tabW,
										tabH,
										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										ACCENT2,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseTabGraphicsBtn = new MenuButton(
										"ГРАФИКА",
										cx + tabW / 2f + tabGap / 2f,
										tabY,
										tabW,
										tabH,
										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						// ── ВКЛАДКА 0: ОСНОВНОЕ ──
						float contentTop = tabY - tabH / 2f - 20f * S;

						BitmapText sfxLabel = new BitmapText(font);
						sfxLabel.setName("PauseSfxLabel");
						sfxLabel.setSize(18f * S);
						sfxLabel.setText("Звуки");
						sfxLabel.setColor(TEXT);
						sfxLabel.setLocalTranslation(cx - panelW / 2f + 25f * S, contentTop, Z);
						pauseSettingsNode.attachChild(sfxLabel);

						pauseSfxVal = new BitmapText(font);
						pauseSfxVal.setSize(18f * S);
						pauseSfxVal.setColor(ACCENT2);
						pauseSfxVal.setLocalTranslation(cx + panelW / 2f - 70f * S, contentTop, Z);
						pauseSettingsNode.attachChild(pauseSfxVal);

						pauseSfxSlider = new VolumeSlider(
										cx,
										contentTop - 28f * S,
										panelW - 60f * S,
										effectVolume,
										assetManager,
										pauseSettingsNode,
										Z
						);

						BitmapText musicLabel = new BitmapText(font);
						musicLabel.setName("PauseMusicLabel");
						musicLabel.setSize(18f * S);
						musicLabel.setText("Музыка");
						musicLabel.setColor(TEXT);
						musicLabel.setLocalTranslation(cx - panelW / 2f + 25f * S, contentTop - 72f * S, Z);
						pauseSettingsNode.attachChild(musicLabel);

						pauseMusicVal = new BitmapText(font);
						pauseMusicVal.setSize(18f * S);
						pauseMusicVal.setColor(ACCENT);
						pauseMusicVal.setLocalTranslation(cx + panelW / 2f - 70f * S, contentTop - 72f * S, Z);
						pauseSettingsNode.attachChild(pauseMusicVal);

						pauseMusicSlider = new VolumeSlider(
										cx,
										contentTop - 100f * S,
										panelW - 60f * S,
										musicVolume,
										assetManager,
										pauseSettingsNode,
										Z
						);

						// Пункт смены ника специально удалён.
						// Во время игры ник не редактируется вообще.

						// ── ВКЛАДКА 1: ГРАФИКА ──
						float gTop = contentTop - 28f * S;
						float toggleW = (panelW - 92f * S) / 2f;
						float toggleH = 36f * S;
						float toggleGapY = 12f * S;
						float leftColX = cx - toggleW / 2f - 10f * S;
						float rightColX = cx + toggleW / 2f + 10f * S;

						float row1Y = gTop - toggleH / 2f;
						float row2Y = row1Y - (toggleH + toggleGapY);
						float row3Y = row2Y - (toggleH + toggleGapY);
						float row4Y = row3Y - (toggleH + toggleGapY);
						float row5Y = row4Y - (toggleH + toggleGapY);

						pauseBtnShadows = new MenuButton(
										shadowQualityButtonText(),
										leftColX,
										row1Y,
										toggleW,
										toggleH,
										shadowQualityBgColor(),
										BTN_HOVER,
										BTN_PRESS,
										shadowQualityAccentColor(),
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnParticles = new MenuButton(
										particlesEnabled ? "ЧАСТИЦЫ ВКЛ" : "ЧАСТИЦЫ ВЫКЛ",
										rightColX,
										row1Y,
										toggleW,
										toggleH,
										particlesEnabled ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										particlesEnabled ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnFog = new MenuButton(
										fogEnabled ? "ТУМАН ВКЛ" : "ТУМАН ВЫКЛ",
										leftColX,
										row2Y,
										toggleW,
										toggleH,
										fogEnabled ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										fogEnabled ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnBloom = new MenuButton(
										bloomEnabled ? "BLOOM ВКЛ" : "BLOOM ВЫКЛ",
										rightColX,
										row2Y,
										toggleW,
										toggleH,
										bloomEnabled ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										bloomEnabled ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnPost = new MenuButton(
										postProcessingEnabled ? "ПОСТ ВКЛ" : "ПОСТ ВЫКЛ",
										leftColX,
										row3Y,
										toggleW,
										toggleH,
										postProcessingEnabled ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										postProcessingEnabled ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnLights = new MenuButton(
										dynamicLightsEnabled ? "ДИН.СВЕТ ВКЛ" : "ДИН.СВЕТ ВЫКЛ",
										rightColX,
										row3Y,
										toggleW,
										toggleH,
										dynamicLightsEnabled ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										dynamicLightsEnabled ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnWater = new MenuButton(
										waterEffectsEnabled ? "ВОДА ВКЛ" : "ВОДА ВЫКЛ",
										leftColX,
										row4Y,
										toggleW,
										toggleH,
										waterEffectsEnabled ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										waterEffectsEnabled ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnTerrain = new MenuButton(
										terrainDetailsEnabled ? "ДЕТАЛИ КАРТ ВКЛ" : "ДЕТАЛИ КАРТ ВЫКЛ",
										rightColX,
										row4Y,
										toggleW,
										toggleH,
										terrainDetailsEnabled ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										terrainDetailsEnabled ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnLowPoly = new MenuButton(
										lowPolyMode ? "LOW POLY ВКЛ" : "LOW POLY ВЫКЛ",
										leftColX,
										row5Y,
										toggleW,
										toggleH,
										lowPolyMode ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										lowPolyMode ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						pauseBtnNames = new MenuButton(
										nameTagsEnabled ? "НИКИ ВКЛ" : "НИКИ ВЫКЛ",
										rightColX,
										row5Y,
										toggleW,
										toggleH,
										nameTagsEnabled ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										nameTagsEnabled ? ACCENT : TEXT_DIM,
										assetManager,
										pauseSettingsNode,
										Z
						);

						// ── Кнопка закрытия ──
						float closeY = cy - panelH / 2f + 40f * S;

						pauseSettingsCloseBtn = new MenuButton(
										"СОХРАНИТЬ И НАЗАД",
										cx,
										closeY,
										panelW - 60f * S,
										46f * S,
										BTN_NORMAL,
										BTN_HOVER,
										BTN_PRESS,
										ACCENT,
										assetManager,
										pauseSettingsNode,
										Z
						);

						setPauseSettingsTab(0);
						updatePauseSettingsLabels();

						pauseSettingsNode.setCullHint(Spatial.CullHint.Always);
						pauseNode.attachChild(pauseSettingsNode);
				}

				private void setPauseSettingsTab(int tab) {
						pauseActiveSettingsTab = tab;

						if (pauseTabMainBtn != null) {
								pauseTabMainBtn.setAccentColor(tab == 0 ? ACCENT2 : TEXT_DIM);
						}

						if (pauseTabGraphicsBtn != null) {
								pauseTabGraphicsBtn.setAccentColor(tab == 1 ? ACCENT2 : TEXT_DIM);
						}

						Spatial.CullHint mainHint = tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
						Spatial.CullHint gfxHint = tab == 1 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;

						String[] mainNames = {
										"PauseSfxLabel",
										"PauseMusicLabel"
						};

						for (String name : mainNames) {
								Spatial s = pauseSettingsNode != null ? pauseSettingsNode.getChild(name) : null;
								if (s != null) {
										s.setCullHint(mainHint);
								}
						}

						if (pauseSfxVal != null) pauseSfxVal.setCullHint(mainHint);
						if (pauseMusicVal != null) pauseMusicVal.setCullHint(mainHint);

						if (pauseSfxSlider != null) pauseSfxSlider.setVisible(tab == 0);
						if (pauseMusicSlider != null) pauseMusicSlider.setVisible(tab == 0);

						setMenuButtonVisible(pauseBtnShadows, gfxHint);
						setMenuButtonVisible(pauseBtnParticles, gfxHint);
						setMenuButtonVisible(pauseBtnFog, gfxHint);
						setMenuButtonVisible(pauseBtnBloom, gfxHint);
						setMenuButtonVisible(pauseBtnPost, gfxHint);
						setMenuButtonVisible(pauseBtnLights, gfxHint);
						setMenuButtonVisible(pauseBtnWater, gfxHint);
						setMenuButtonVisible(pauseBtnTerrain, gfxHint);
						setMenuButtonVisible(pauseBtnLowPoly, gfxHint);
						setMenuButtonVisible(pauseBtnNames, gfxHint);
				}

				private void setMenuButtonVisible(MenuButton b, Spatial.CullHint hint) {
						if (b == null) return;

						if (b.bgGeo != null) b.bgGeo.setCullHint(hint);
						if (b.accentGeo != null) b.accentGeo.setCullHint(hint);
						if (b.borderGeo != null) b.borderGeo.setCullHint(hint);
						if (b.label != null) b.label.setCullHint(hint);
				}

				private void updatePauseSettingsLabels() {
						if (pauseSfxVal != null) {
								pauseSfxVal.setText(Math.round(effectVolume * 100f) + "%");
						}

						if (pauseMusicVal != null) {
								pauseMusicVal.setText(Math.round(musicVolume * 100f) + "%");
						}

						if (pauseSfxSlider != null) {
								pauseSfxSlider.setValue(effectVolume);
						}

						if (pauseMusicSlider != null) {
								pauseMusicSlider.setValue(musicVolume);
						}

						MusicManager.setVolume(musicVolume);
						updateShadowQualityButton(pauseBtnShadows);
				}

				private void setPauseToggleButton(MenuButton b, boolean value, String title) {
						if (b == null) return;

						b.setText(title + " " + (value ? "ВКЛ" : "ВЫКЛ"));
						b.setAccentColor(value ? ACCENT : TEXT_DIM);
						b.setBgNormal(value ? new ColorRGBA(0.04f, 0.14f, 0.08f, 0.9f) : BTN_NORMAL);
				}

        private void backToLobbyFromHost() {
            if (!isHost || solo) { backToMenu(); return; }
            sendNet("HOST_BACK_TO_LOBBY");
            if (rainSound!=null) { rainSound.stop(); detachQuietly(rootNode, rainSound); }
            netRunning.set(false);
            closeQuietly(socket);
            for (SnakePlayer sp : snapshot(snakes)) if (sp != null) sp.cleanup(guiNode);
            for (BlackCube bc : snapshot(blackCubes)) {
                if (bc.phy!=null) { bc.phy.setEnabled(false); bulletAppState.getPhysicsSpace().remove(bc.phy); }
            }
            blackCubes.clear();
            if (gameShadowRenderer != null) { app.getViewPort().removeProcessor(gameShadowRenderer); gameShadowRenderer = null; }
            if (gameFpp != null) { app.getViewPort().removeProcessor(gameFpp); gameFpp = null; }
            if (sunLight != null) { rootNode.removeLight(sunLight); sunLight = null; }
            if (ambientLight != null) { rootNode.removeLight(ambientLight); ambientLight = null; }
            rootNode.detachAllChildren(); LemurUi.clearPage(guiNode);
            UiGrid.clear(guiNode);
            clearInputMappingsQuietly(inputManager);
            if (inputManager != null) inputManager.setCursorVisible(true);
            stateManager.detach(bulletAppState); stateManager.detach(this);
            stateManager.attach(new LobbyState(myNick, true, false, null, 0));
        }

        private void activateCheatCode() {
            if (bordersRemoved) return;
            bordersRemoved = true;
            showCenter("⚡ ГРАНИЦЫ СНЯТЫ ⚡", new ColorRGBA(1f,1f,0.1f,1f));
            // Снимаем физические коллайдеры стен — змейка может уходить за карту
            PhysicsSpace space = bulletAppState.getPhysicsSpace();
            wallNode.getChildren().forEach(child -> {
                if (child instanceof Geometry) {
                    RigidBodyControl rb = ((Geometry)child).getControl(RigidBodyControl.class);
                    if (rb != null) space.remove(rb);
                }
            });
            if (!solo) sendNet("CHEAT_BORDERS");
        }


        private void stopLocalSnakeInputForFreeCamera() {
            if (myIndex >= 0 && myIndex < snakes.size()) {
                SnakePlayer me = snakes.get(myIndex);
                me.setTurnLeft(false);
                me.setTurnRight(false);
                me.setMoving(false);
            }
        }

        private void toggleFreeCameraCheat() {
            freeCameraMode = !freeCameraMode;
            stopLocalSnakeInputForFreeCamera();
            if (app.getFlyByCamera() != null) {
                app.getFlyByCamera().setDragToRotate(false);
                app.getFlyByCamera().setEnabled(freeCameraMode);
                app.getFlyByCamera().setMoveSpeed(28f);
                app.getFlyByCamera().setRotationSpeed(3f);
            }
            inputManager.setCursorVisible(!freeCameraMode && pauseActive);
            showCenter(freeCameraMode ? "🎥 FREE CAMERA ВКЛ" : "🎥 FREE CAMERA ВЫКЛ", freeCameraMode ? ACCENT2 : TEXT_DIM);
        }

        // ── Звуки ─────────────────────────────────────────────────────────
        private void loadSounds() {
            eatSounds[0] = tryAudio("Sounds/Eat1.ogg");
            eatSounds[1] = tryAudio("Sounds/Eat2.ogg");
            eatSounds[2] = tryAudio("Sounds/Eat3.ogg");
            eatSounds[3] = tryAudio("Sounds/Burp.ogg");
            mmmSound    = tryAudio("Sounds/mmm.ogg");
            startSound  = tryAudio("Sounds/start.ogg");
            deathSound  = tryAudio("Sounds/death.ogg");
            chitSound   = tryAudio("Sounds/chit.ogg");
            cubeRollSound = tryAudio("Sounds/cube_move.ogg");
            if (startSound!=null) startSound.play();
            // Игровые фоновые треки отключены, чтобы музыка не накладывалась.
        }

        private AudioNode tryAudio(String path) {
            try {
                AudioNode an = new AudioNode(assetManager, path, DataType.Buffer);
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

        // ── Сеть ──────────────────────────────────────────────────────────
        private void initNetwork() {
            if (socket==null) {
                try { socket=new DatagramSocket(); socket.setSoTimeout(50); }
                catch (Exception e) { System.out.println("[GAME NET] socket err: "+e.getMessage()); return; }
            } else { try { socket.setSoTimeout(50); } catch (Exception ignore) {} }
            netRecvThread = new Thread(this::netRecvLoop, "GameNet");
            netRecvThread.setDaemon(true); netRecvThread.start();
        }

        private void netRecvLoop() {
            byte[] buf = new byte[8192];
            while (netRunning.get()) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    socket.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    lastNetPacketMs = System.currentTimeMillis();
                    if (isHost) {
                        InetSocketAddress sender = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
												clientLastSeen.put(sender, System.currentTimeMillis());
                        byte[] rebroadcastBuf = Arrays.copyOf(pkt.getData(), pkt.getLength());
                        for (InetSocketAddress c : clients) {
                            if (!c.equals(sender)) {
                                try { socket.send(new DatagramPacket(rebroadcastBuf, rebroadcastBuf.length, c)); }
                                catch (Exception ignore) {}
                            }
                        }
                    }
                    app.enqueue(() -> handleNetMsg(msg));
                } catch (SocketTimeoutException ignore) {}
                catch (Exception e) {}
            }
        }

        private void handleNetMsg(String msg) {
            String[] p = msg.split("\\|", -1);
            switch (p[0]) {
                case "INPUT":
                    if (p.length>=4) {
                        int idx=Integer.parseInt(p[1]);
                        if (idx>=0&&idx<snakes.size()) {
                            snakes.get(idx).setTurnLeft("1".equals(p[2]));
                            snakes.get(idx).setTurnRight("1".equals(p[3]));
                            if (p.length>=5) snakes.get(idx).setMoving("1".equals(p[4]));
                        }
                    } break;
								case "STATE":
										if (p.length >= 9) {
												int idx = Integer.parseInt(p[1]);
												if (idx >= 0 && idx < snakes.size() && idx != myIndex) {
														float x = Float.parseFloat(p[2]), y = Float.parseFloat(p[3]),
																	z = Float.parseFloat(p[4]), angle = Float.parseFloat(p[5]);
														int score = Integer.parseInt(p[6]), len = Integer.parseInt(p[7]);
														boolean dead = "1".equals(p[8]);
														SnakePlayer sp = snakes.get(idx);
														if (isHost) {
                                                                // Хост берёт у клиента только позицию/угол. Очки и длина остаются авторитетными у хоста.
															sp.applyNetState(x, y, z, angle, sp.getScore(), sp.getLength(), dead);
													} else {
															sp.applyNetState(x, y, z, angle, score, len, dead);
													}
												}
										}
										break;
                case "DEAD":
                    if (p.length>=2) {
                        int idx=Integer.parseInt(p[1]);
                        if (idx>=0&&idx<snakes.size()&&!snakes.get(idx).isDead()) {
                            snakes.get(idx).triggerDeathRemote(rootNode); // checkWinCondition();
                        }
                    } break;
								case "LEAVE":
										if (isHost) {
												int idx = Integer.parseInt(p[1]);
												killSnake(idx, "покинул игру");
												// удалить из clients, clientLastSeen, clientIndexMap по адресу, если нужно.
												// Для удаления по индексу нужно найти адрес по clientIndexMap.
												for (Map.Entry<InetSocketAddress, Integer> e : clientIndexMap.entrySet()) {
														if (e.getValue() == idx) {
																clientLastSeen.remove(e.getKey());
																clients.remove(e.getKey());
																clientIndexMap.remove(e.getKey());
																break;
														}
												}
												sendNet("PLAYER_LEFT|" + idx);
										}
										break;
                case "FOOD":
                    if (p.length>=6) addFoodWithData(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),"1".equals(p[4]),"1".equals(p[5]));
                    else if (p.length>=5) addFoodWithData(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),"1".equals(p[4]),false);
                    break;
                case "DEBRIS":
                    if (p.length>=4) addFoodWithData(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),false,true);
                    break;
                case "EAT":
                    if (p.length>=2) {
                            int foodId = Integer.parseInt(p[1]);
                            if (eatenFoodIds.add(foodId)) {
                                removeFoodById(foodId);
                                if (p.length >= 6) {
                                    int snakeIdx = Integer.parseInt(p[2]);
                                    boolean bad = "1".equals(p[3]);
                                    boolean debris = "1".equals(p[4]);
                                    int scoreDelta = Integer.parseInt(p[5]);
                                    applyFoodEatResult(snakeIdx, bad, debris, scoreDelta, true);
                                }
                            }
                        }
                        break;
                case "WIN":
                    if (p.length>=2) { showCenter(p[1]+" ПОБЕДИЛ!", new ColorRGBA(1f,0.85f,0.1f,1f)); gameOver=true; exitTimer=8f; } break;
                case "PING":
                    if (isHost && p.length >= 3) sendNet("PONG|" + p[1] + "|" + p[2]);
                    break;
                case "PONG":
                    if (!isHost && p.length >= 3) {
                        try {
                            long seq = Long.parseLong(p[1]);
                            Long sent = pendingPings.remove(seq);
                            if (sent != null) serverPingMs = Math.max(0L, System.currentTimeMillis() - sent);
                        } catch (Exception ignored) {}
                    }
                    break;
								case "PLAYER_LEFT":
										if (p.length >= 2) {
												int idx = Integer.parseInt(p[1]);
												if (idx >= 0 && idx < snakes.size() && !snakes.get(idx).isDead()) {
														snakes.get(idx).triggerDeathRemote(rootNode); // смерть без отбрасывания обломков (или с ними)
														if (idx == myIndex) {
																spectating = true;
																spectateTarget = (myIndex + 1) % snakes.size();
														}
														// опционально пересчитать win condition
														checkWinCondition();
												}
										}
										break;
                case "BACK_LOBBY":
                case "HOST_BACK_TO_LOBBY":
                    app.enqueue(this::backToMenu); break;
                    case "HOST_LEFT":
                        showCenter("Хост вышел из игры", new ColorRGBA(1f,0.35f,0.25f,1f));
                        app.enqueue(this::backToMenu);
                        break;
                case "SPIKE_STATE":
                    // SPIKE_STATE|pitIdx|stateOrd|timer
                    if (p.length >= 4 && !isHost) {
                        int pitIdx = Integer.parseInt(p[1]);
                        int stateOrd = Integer.parseInt(p[2]);
                        float timer = Float.parseFloat(p[3]);
                        if (pitIdx >= 0 && pitIdx < pits.size()) {
                            PitData pd = pits.get(pitIdx);
                            pd.state = PitState.values()[stateOrd];
                            pd.stateTimer = timer;
                        }
                    } break;
								case "CACT_RESPAWN":
										if (p.length >= 3) {
												float cx = Float.parseFloat(p[1]);
												float cz = Float.parseFloat(p[2]);
												// Создаём кактус на клиенте
												Material cactMat  = litMat(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
												Material spineMat = litMat(assetManager, new ColorRGBA(0.85f,0.82f,0.65f,1f));
												spawnCactusAt(bulletAppState.getPhysicsSpace(), cactMat, spineMat, cx, cz);
										}
										break;
								case "CACT_STICK":
										// Новый формат:
										// CACT_STICK|snakeIdx|cactusX|cactusZ|fragIndex|contactX|contactY|contactZ
										if (p.length >= 8) {
												int snakeIdx = Integer.parseInt(p[1]);
												float cx = Float.parseFloat(p[2]);
												float cz = Float.parseFloat(p[3]);
												int fragIndex = Integer.parseInt(p[4]);

												Vector3f contactWorld = new Vector3f(
																Float.parseFloat(p[5]),
																Float.parseFloat(p[6]),
																Float.parseFloat(p[7])
												);

												CactusData cd = findCactusByPos(cx, cz);
												if (cd != null) {
														int listIndex = findCactusFragmentListIndex(cd, fragIndex);
														attachCactusFragmentNetworked(cd, listIndex, snakeIdx, contactWorld, false);
												}
										}
										break;
								case "EVENT_SANDSTORM":
										if (!sandstormActive) startSandstormEvent();
										break;
								case "PITS_INIT":
										if (p.length > 1) {
												for (int i = 1; i < p.length; i++) {
														String[] parts = p[i].split("=");
														if (parts.length >= 3) {
																int idx = Integer.parseInt(parts[0]);
																int stateOrd = Integer.parseInt(parts[1]);
																float timer = Float.parseFloat(parts[2]);
																if (idx >= 0 && idx < pits.size()) {
																		PitData pit = pits.get(idx);
																		pit.state = PitState.values()[stateOrd];
																		pit.stateTimer = timer;
																}
														}
												}
										}
										break;
								/*case "PAUSE":
										if (!isHost) {
												pauseActive = true;
												if (inputManager != null) inputManager.setCursorVisible(true);
												if (pauseNode == null) buildPauseUI();
												pauseNode.setCullHint(Spatial.CullHint.Inherit);
										}
										break;
								case "UNPAUSE":
										if (!isHost) {
												pauseActive = false;
												inputManager.setCursorVisible(false);
												if (pauseNode != null) pauseNode.setCullHint(Spatial.CullHint.Always);
										}
										break; */
                case "CHEAT_BORDERS":
                    if (!bordersRemoved) activateCheatCode();
                    break;
                case "DAYTIME":
                    // Синхронизация времени суток от хоста к клиентам
                    if (!isHost && p.length >= 2) {
                        try { dayNightTime = Float.parseFloat(p[1]); } catch (Exception ignored) {}
                    }
                    break;
                case "CUBE_SPAWN":
                    if (p.length>=4) spawnBlackCube(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]));
                    break;
								case "CUBE_STATE":
										if (p.length >= 9) {
												int cid = Integer.parseInt(p[1]);
												float cx = Float.parseFloat(p[2]);
												float cy = Float.parseFloat(p[3]);
												float cz = Float.parseFloat(p[4]);
												float rx = Float.parseFloat(p[5]);
												float ry = Float.parseFloat(p[6]);
												float rz = Float.parseFloat(p[7]);
												float rw = Float.parseFloat(p[8]);
												BlackCube bc = findBlackCube(cid);
												if (bc == null) {
														bc = spawnBlackCube(cid, cx, cz); // только геометрия, без физики
												}
												if (bc != null && bc.active) {
														bc.targetPos = new Vector3f(cx, cy, cz);
														bc.targetRot = new Quaternion(rx, ry, rz, rw);
														// если куб только что создан, сразу установить позицию, чтобы не дёргался
														if (bc.geo.getLocalTranslation().distance(bc.targetPos) > 3f) {
																bc.geo.setLocalTranslation(bc.targetPos);
																bc.geo.setLocalRotation(bc.targetRot);
														}
												}
										}
										break;
                case "CUBE_HIT":
                    if (p.length>=3) applyCubeHitLocal(Integer.parseInt(p[1]),Integer.parseInt(p[2]));
                    break;
                case "EVENT_BALLRAIN":
                    if (!ballRainActive) startBallRainEvent(); break;
                case "EVENT_RAIN":
                    if (!weatherRainActive) startWeatherRainEvent(); break;
                case "EVENT_FROZEN":
                    if (!frozenArenaActive) startFrozenArenaEvent(); break;
								case "CACT_HIT":
										if (p.length >= 3) {
												float cx = Float.parseFloat(p[1]), cz = Float.parseFloat(p[2]);
												for (CactusData cd : cacti) {
														if (!cd.hit && Math.abs(cd.origX - cx) < 0.5f && Math.abs(cd.origZ - cz) < 0.5f) {
																cd.hit = true;

																// Убираем ствол и руку
																if (cd.trunkPhy != null && bulletAppState != null) {
																		cd.trunkPhy.setEnabled(false);
																		bulletAppState.getPhysicsSpace().remove(cd.trunkPhy);
																}
																if (cd.trunkGeo.getParent() != null) cd.trunkGeo.removeFromParent();
																if (cd.armGeo != null && cd.armGeo.getParent() != null) {
																		cd.armGeo.removeFromParent();
																}

																// ВАЖНО: клиент тоже должен убрать белые палочки кактуса
																removeCactusSpines(cd);

																// Создаём обломки с колючками
																Material fragMat = litMat(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
																Material spineMat = litMat(assetManager, new ColorRGBA(0.86f,0.82f,0.66f,1f));
																Vector3f cpos = cd.trunkGeo.getWorldTranslation();
																// ─── Детерминированный рандом для обломков ───
																Random fragRng = new Random(Float.floatToIntBits(cd.origX) ^ Float.floatToIntBits(cd.origZ));

																for (int fi = 0; fi < 4; fi++) {
																		float fsize = 0.15f + fragRng.nextFloat() * 0.2f;
																		Node frag = new Node("CactFragNode" + fi);
																		Geometry core = new Geometry("CactFrag" + fi, new Box(fsize, fsize, fsize));
																		
																		frag.setUserData("fragIndex", fi);
																		core.setUserData("fragIndex", fi);
																		core.setUserData("stickRequested", false);
																		
																		core.setMaterial(fragMat);
																		frag.attachChild(core);
																		// используем fragRng вместо FastMath.nextRandomFloat()
																		frag.setLocalTranslation(cpos.add(
																						(fragRng.nextFloat() - 0.5f) * 0.5f,
																						fragRng.nextFloat() * 0.8f,
																						(fragRng.nextFloat() - 0.5f) * 0.5f));
																		wallNode.attachChild(frag);
																		RigidBodyControl fp = new RigidBodyControl(
																						new BoxCollisionShape(new Vector3f(fsize, fsize, fsize)), 0.5f);
																		fp.setLinearVelocity(new Vector3f(
																						(fragRng.nextFloat() - 0.5f) * 6f,
																						2f + fragRng.nextFloat() * 3f,
																						(fragRng.nextFloat() - 0.5f) * 6f));
																		frag.addControl(fp);
																		bulletAppState.getPhysicsSpace().add(fp);

																		for (int sj = 0; sj < 4; sj++) {
																				Geometry spike = new Geometry("FragSpike" + fi + "_" + sj, new Box(0.02f, 0.02f, 0.12f));
																				spike.setMaterial(spineMat);
																				float a = sj * FastMath.HALF_PI + fragRng.nextFloat() * 0.2f;
																				spike.setLocalRotation(new Quaternion().fromAngleAxis(a, Vector3f.UNIT_Y));
																				spike.setLocalTranslation(FastMath.cos(a) * fsize, 0f, FastMath.sin(a) * fsize);
																				frag.attachChild(spike);
																		}
																		cd.fragments.add(core);
																		cd.fragmentTimers.add(CactusData.FRAGMENT_LIFETIME);
																}
																break;
														}
												}
										}
										break;
								case "BALL_SPAWN":
										if (p.length >= 4) {
												int ballId = Integer.parseInt(p[1]);
												float bx = Float.parseFloat(p[2]);
												float bz = Float.parseFloat(p[3]);

												// Цвета такие же, как в spawnRainBall
												ColorRGBA[] cols = {
														new ColorRGBA(1f,0.2f,0.8f,1f), new ColorRGBA(0.2f,0.8f,1f,1f),
														new ColorRGBA(1f,0.9f,0.1f,1f), new ColorRGBA(0.4f,1f,0.3f,1f)
												};
												ColorRGBA col = cols[FastMath.nextRandomInt(0, cols.length - 1)];
												float r = 0.3f + FastMath.nextRandomFloat() * 0.3f;
												Geometry geo = new Geometry("RainBall", new Sphere(8, 8, r));
												geo.setMaterial(unshaded(assetManager, col));
												geo.setLocalTranslation(bx, 20f + FastMath.nextRandomFloat() * 10f, bz);
												rainBallNode.attachChild(geo);
												rainBalls.add(new RainBall(geo, ballId, 4f + FastMath.nextRandomFloat() * 3f, r));
										}
										break;
								case "BALL_COLLECT":
										if (p.length >= 2) {
												int ballId = Integer.parseInt(p[1]);
												for (int i = rainBalls.size() - 1; i >= 0; i--) {
														RainBall rb = rainBalls.get(i);
														if (rb.id == ballId) {
																rainBallNode.detachChild(rb.geo);
																rainBalls.remove(i);
																break;
														}
												}
										}
										break;
                case "MAP_EVT":
                    if (p.length >= 3 && mapDef != null && mapCtx != null) {
                        String eventMapId = p[1];
                        if (loadedMapInfo != null && !loadedMapInfo.id.equals(eventMapId)) break;
                        try {
                            byte[] data = Base64.getUrlDecoder().decode(p[2]);
                            String payload = new String(data, StandardCharsets.UTF_8);
                            mapDef.onMapNetMessage(mapCtx, payload);
                        } catch (Exception e) {
                            logSafe("Map.netEvent", e);
                        }
                    }
                    break;
                case "WATER":
                    if (p.length>=4) {
                        float wx=Float.parseFloat(p[1]),wz=Float.parseFloat(p[2]),wr=Float.parseFloat(p[3]);
                        addWaterPuddle(wx, wz, wr);
                    } break;
            }
        }

        public Vector3f getSnakeHeadPos(int index) {
            if (index < 0 || index >= snakes.size()) return Vector3f.ZERO.clone();
            SnakePlayer sp = snakes.get(index);
            if (sp == null) return Vector3f.ZERO.clone();
            return sp.getHeadPos().clone();
        }

        public Vector3f getSnakeDirection(int index) {
            if (index < 0 || index >= snakes.size()) return Vector3f.UNIT_Z.clone();
            SnakePlayer sp = snakes.get(index);
            if (sp == null) return Vector3f.UNIT_Z.clone();
            Vector3f dir = sp.getDirection();
            return dir == null ? Vector3f.UNIT_Z.clone() : dir.clone();
        }

        public void setStandardSnakesVisible(boolean visible) {
            Spatial.CullHint hint = visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            for (SnakePlayer sp : snapshot(snakes)) {
                if (sp != null) sp.setVisible(visible);
            }
        }

        public void setCoreHudVisible(boolean visible) {
            Spatial.CullHint hint = visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            for (BitmapText h : snapshot(huds)) if (h != null) h.setCullHint(hint);
            if (gameTimerText != null) gameTimerText.setCullHint(hint);
            if (dashCircleBg != null) dashCircleBg.setCullHint(hint);
            if (dashCircleFill != null) dashCircleFill.setCullHint(hint);
            if (dashShiftText != null) dashShiftText.setCullHint(hint);
            if (dashCooldownText != null) dashCooldownText.setCullHint(hint);
            if (netStatsText != null) netStatsText.setCullHint(hint);
        }

        public void sendMapEvent(String payload) {
            if (payload == null || payload.isBlank() || mapDef == null || mapCtx == null) return;
            try {
                mapDef.onMapNetMessage(mapCtx, payload);
            } catch (Exception e) {
                logSafe("Map.localEvent", e);
            }
            if (solo) return;
            String safePayload = Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
            sendNet("MAP_EVT|" + (loadedMapInfo != null ? loadedMapInfo.id : "unknown") + "|" + safePayload);
        }

        public void killSnakeFromMap(int index, String reason) {
            if (index < 0 || index >= snakes.size()) return;
            killSnake(index, reason == null ? "погиб на карте" : reason);
        }

        private void sendNet(String msg) {
            if (socket == null || msg == null) return;
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            if (isHost) {
                for (InetSocketAddress c : snapshot(clients)) {
                    if (c == null) continue;
                    try { socket.send(new DatagramPacket(b,b.length,c)); }
                    catch (Exception e) { logSafe("Game.sendNet.host", e); }
                }
            } else if (hostAddress != null) {
                try { socket.send(new DatagramPacket(b,b.length,new InetSocketAddress(hostAddress,hostPort))); }
                catch (Exception e) { logSafe("Game.sendNet.client", e); }
            }
        }

        // ── Ивент 1: Шариковый дождь ──────────────────────────────────────
        private void startBallRainEvent() {
            if (!mapSettings.enableBallRain) return;
            if (ballRainActive) return;
            ballRainActive = true; ballRainTimer = BALL_RAIN_DURATION;
            ballRainSpawnTimer = 0f;
            showCenter("★ ШАРИКОВЫЙ ДОЖДЬ! ★", new ColorRGBA(1f,0.4f,1f,1f));
            if (!solo) sendNet("EVENT_BALLRAIN");
        }

        private void updateBallRain(float tpf) {
            if (ballRainActive) {
                ballRainTimer -= tpf;
                if (ballRainTimer <= 0f) {
                    ballRainActive = false;
                    showCenter("★ Шариковый дождь закончился. Собирай шарики! ★", new ColorRGBA(0.8f,0.5f,1f,1f));
                    if (solo || isHost) nextEventTimer = 25f + eventRng.nextFloat() * 45f;
                } else {
                    ballRainSpawnTimer -= tpf;
                    if (ballRainSpawnTimer <= 0f && (solo || isHost)) {
                        ballRainSpawnTimer = 0.15f;
                        for (int i=0;i<3;i++) {
                            float rx = (FastMath.nextRandomFloat()-0.5f) * mapHalf * 1.8f;
                            float rz = (FastMath.nextRandomFloat()-0.5f) * mapHalf * 1.8f;
                            spawnRainBall(rx, rz);
                            // if (!solo) sendNet("BALL_SPAWN|"+rx+"|"+rz);
                        }
                    }
                }
            }

            for (int i=rainBalls.size()-1;i>=0;i--) {
                RainBall rb = rainBalls.get(i);

								// Внутри цикла for (RainBall rb : rainBalls)
								if (rb.landed) {
										rb.landedTimer -= tpf;
										if (rb.landedTimer <= 0f) {
												rainBallNode.detachChild(rb.geo);
												rainBalls.remove(i);
												continue;
										}

										// Если шарик только что приземлился, включаем режим скольжения
										if (!rb.sliding) {
												rb.sliding = true;
												// Начальная скорость – остаток от падения плюс небольшая случайная
												rb.velocity.set((FastMath.nextRandomFloat()-0.5f)*2f, 0f, (FastMath.nextRandomFloat()-0.5f)*2f);
										}

										Vector3f pos = rb.geo.getLocalTranslation();
										float groundY = getSurfaceHeight(pos.x, pos.z) + rb.radius; // чуть выше поверхности
										float slopeX = getSurfaceHeight(pos.x + 0.3f, pos.z) - getSurfaceHeight(pos.x - 0.3f, pos.z);
										float slopeZ = getSurfaceHeight(pos.x, pos.z + 0.3f) - getSurfaceHeight(pos.x, pos.z - 0.3f);
										Vector3f gravityForce = new Vector3f(-slopeX, 0f, -slopeZ).multLocal(5f); // сила тяжести вдоль склона

										// Применяем силу + трение
										rb.velocity.addLocal(gravityForce.mult(tpf));
										rb.velocity.multLocal(1f - 0.6f * tpf); // трение (коэфф. 0.6)

										pos.addLocal(rb.velocity.mult(tpf));
										pos.y = getSurfaceHeight(pos.x, pos.z) + rb.radius; // прилипание к поверхности
										rb.geo.setLocalTranslation(pos);

										// Если скорость очень мала, можно остановить физику, но шарик всё равно исчезнет по таймеру
										if (rb.velocity.length() < 0.1f) {
												rb.velocity.set(0,0,0);
										}

										rb.geo.rotate(0f, tpf * 0.8f, 0f);
										if (myIndex < snakes.size() && !snakes.get(myIndex).isDead()) {
												SnakePlayer me = snakes.get(myIndex);
												if (me.getHeadPos().distance(pos) < 1.5f) {
														me.grow(assetManager); me.addScore(3); playEatSound();
														if (!solo) sendNet("BALL_COLLECT|" + rb.id);
														rainBallNode.detachChild(rb.geo); rainBalls.remove(i);
												}
										}
								} else {
                    rb.lifeTimer -= tpf;
                    if (rb.lifeTimer <= 0f) {
                        rainBallNode.detachChild(rb.geo); rainBalls.remove(i);
                        continue;
                    }
										Vector3f pos = rb.geo.getLocalTranslation();
                    pos.y -= 15f * tpf;
                    rb.geo.setLocalTranslation(pos);
                    rb.geo.rotate(0.05f, 0.1f, 0.05f);

                    float floorY = getSurfaceHeight(pos.x, pos.z) + rb.radius;
                    if (pos.y <= floorY) {
                        pos.y = floorY;
                        rb.geo.setLocalTranslation(pos);
                        rb.landed = true;
                        rb.landedTimer = 12f;
                        if (myIndex < snakes.size() && !snakes.get(myIndex).isDead()) {
                            SnakePlayer me = snakes.get(myIndex);
                            if (me.getHeadPos().distance(pos) < 1.5f) {
                                me.grow(assetManager); me.addScore(3); playEatSound();
                                rainBallNode.detachChild(rb.geo); rainBalls.remove(i);
                                continue;
                            }
                        }
                    }
                }
            }
        }

				private void spawnRainBall(float x, float z) {
						ColorRGBA[] cols = {
								new ColorRGBA(1f,0.2f,0.8f,1f), new ColorRGBA(0.2f,0.8f,1f,1f),
								new ColorRGBA(1f,0.9f,0.1f,1f), new ColorRGBA(0.4f,1f,0.3f,1f)
						};
						ColorRGBA col = cols[FastMath.nextRandomInt(0, cols.length - 1)];
						float r = 0.3f + FastMath.nextRandomFloat() * 0.3f;
						Geometry geo = new Geometry("RainBall", new Sphere(8, 8, r));
						geo.setMaterial(unshaded(assetManager, col));
						geo.setLocalTranslation(x, 20f + FastMath.nextRandomFloat() * 10f, z);
						rainBallNode.attachChild(geo);
						int id = ballIdCounter++;
						rainBalls.add(new RainBall(geo, id, 4f + FastMath.nextRandomFloat() * 3f, r));
						if (!solo) sendNet("BALL_SPAWN|" + id + "|" + x + "|" + z);
				}

        // ── Ивент 2: Дождь ────────────────────────────────────────────────
        private void startWeatherRainEvent() {
					  if (!mapSettings.enableRain || mapIndex == 1 || !SnakeApp.waterEffectsEnabled) return;
            if (weatherRainActive) return;
            weatherRainActive = true; weatherRainTimer = WEATHER_RAIN_DURATION;
            showCenter("☔ ДОЖДЬ!", new ColorRGBA(0.5f,0.7f,1f,1f));
            if (!solo) sendNet("EVENT_RAIN");
            try {
                // DataType.Stream вместо Buffer — непрерывный loop без микро-паузы
                rainSound = new AudioNode(assetManager, "Sounds/inv/Rain1.ogg", DataType.Stream);
                rainSound.setPositional(false); rainSound.setLooping(true);
                rainSound.setVolume(effectVolume * 0.8f);
                rootNode.attachChild(rainSound); rainSound.play();
            } catch (Exception e) { System.out.println("[GAME] Rain sound not found"); }
        }

        private void updateWeatherRain(float tpf) {
            if (weatherRainActive) {
                weatherRainTimer -= tpf;
                if (weatherRainTimer <= 0f) {
                    weatherRainActive = false;
                    if (rainSound!=null) { rainSound.stop(); detachQuietly(rootNode, rainSound); rainSound=null; }
                    showCenter("Дождь закончился. Лужи медленно высыхают.", new ColorRGBA(0.7f,0.8f,1f,1f));
                    waterSpeedMultiplier = 1.0f;
                    if (solo || isHost) nextEventTimer = 25f + eventRng.nextFloat() * 45f;
                } else {
										if (SnakeApp.particlesEnabled) {
												for (int i=0;i<8;i++) {
														float rx = (FastMath.nextRandomFloat()-0.5f)*mapHalf*2f;
														float rz = (FastMath.nextRandomFloat()-0.5f)*mapHalf*2f;
														spawnRainDrop(rx, rz);
												}
										}
                    if (solo || isHost) {
                        if ((int)(weatherRainTimer*10) % 30 == 0) {
                            for (int attempt = 0; attempt < 10; attempt++) {
                                float px = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*mapHalf*1.8f,-mapHalf+3f,mapHalf-3f);
                                float pz = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*mapHalf*1.8f,-mapHalf+3f,mapHalf-3f);
                                float pr = 1.5f + FastMath.nextRandomFloat()*2f;
                                if (!isPuddlePlaceValid(px, pz, pr)) continue;
                                addWaterPuddle(px, pz, pr);
                                if (!solo) sendNet("WATER|"+px+"|"+pz+"|"+pr);
                                break;
                            }
                        }
                    }
                }
            }

            for (int i=rainDrops.size()-1;i>=0;i--) {
                RainDrop rd = rainDrops.get(i);
                rd.lifeTimer -= tpf;
                if (rd.lifeTimer<=0f) { rainDropNode.detachChild(rd.geo); rainDrops.remove(i); }
                else {
                    Vector3f pos = rd.geo.getLocalTranslation();
                    pos.y -= 20f * tpf;
                    if (pos.y < -1f) { rainDropNode.detachChild(rd.geo); rainDrops.remove(i); }
                    else rd.geo.setLocalTranslation(pos);
                }
            }

            waterSpeedMultiplier = 1.0f;
            for (int i=waterPuddles.size()-1;i>=0;i--) {
                WaterPuddle wp = waterPuddles.get(i);
                if (weatherRainActive) {
                    if (wp.size < wp.maxSize) {
                        wp.size = Math.min(wp.maxSize, wp.size + tpf * 0.3f);
                        float ripple = 1f + FastMath.sin((weatherRainTimer + wp.x * 0.4f + wp.z * 0.5f) * 4f) * 0.03f;
                        wp.geo.setLocalScale(wp.size * 1.15f * ripple, wp.size * 0.85f / ripple, 1f);
                    }
                } else {
                    wp.size -= tpf * 0.12f;
                    if (wp.size <= 0f) {
                        waterNode.detachChild(wp.geo);
                        waterPuddles.remove(i);
                        continue;
                    }
                    wp.geo.setLocalScale(wp.size * 1.15f, wp.size * 0.85f, 1f);
                    float alpha = Math.min(0.7f, wp.size / wp.maxSize * 0.7f);
                    wp.geo.getMaterial().setColor("Color", new ColorRGBA(0.025f,0.055f,0.075f,Math.min(0.42f, alpha * 0.65f)));
                }

                if (myIndex < snakes.size() && !snakes.get(myIndex).isDead()) {
                    Vector3f head = snakes.get(myIndex).getHeadPos();
                    float dist = new Vector3f(head.x, 0, head.z).distance(new Vector3f(wp.x, 0, wp.z));
                    if (dist < wp.size) waterSpeedMultiplier = 0.55f;
                }
            }
        }

        private void spawnRainDrop(float x, float z) {
            if (rainDrops.size() > 200) return;
            Geometry geo = new Geometry("Drop", new Box(0.02f, 0.15f, 0.02f));
            geo.setMaterial(unshaded(assetManager, new ColorRGBA(0.5f,0.7f,1f,0.7f)));
            geo.setLocalTranslation(x, 12f + FastMath.nextRandomFloat()*5f, z);
            rainDropNode.attachChild(geo);
            rainDrops.add(new RainDrop(geo, 1f + FastMath.nextRandomFloat()*0.5f));
        }        private boolean isPuddlePlaceValid(float x, float z, float radius) {
            if (Math.abs(x) > mapHalf - radius - 2f || Math.abs(z) > mapHalf - radius - 2f) return false;
            float c = getSurfaceHeight(x, z);
            float h1 = getSurfaceHeight(x + radius * 0.45f, z);
            float h2 = getSurfaceHeight(x - radius * 0.45f, z);
            float h3 = getSurfaceHeight(x, z + radius * 0.45f);
            float h4 = getSurfaceHeight(x, z - radius * 0.45f);
            float max = Math.max(Math.max(h1, h2), Math.max(h3, h4));
            float min = Math.min(Math.min(h1, h2), Math.min(h3, h4));
            return Math.abs(c - h1) < 0.16f && Math.abs(c - h2) < 0.16f &&
                   Math.abs(c - h3) < 0.16f && Math.abs(c - h4) < 0.16f &&
                   (max - min) < 0.22f;
        }

        private void addWaterPuddle(float x, float z, float radius) {
            if (!isPuddlePlaceValid(x, z, radius)) return;
            for (WaterPuddle wp : waterPuddles) {
                if (Math.abs(wp.x - x) < 2f && Math.abs(wp.z - z) < 2f) return;
            }
            Geometry geo = new Geometry("Puddle", createDiscMesh(1f, 1f));
            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", new ColorRGBA(0.025f, 0.055f, 0.075f, 0.42f));
            mat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            mat.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
            mat.getAdditionalRenderState().setDepthWrite(false);
            geo.setMaterial(mat);
            geo.setQueueBucket(RenderQueue.Bucket.Transparent);
            float groundY = getSurfaceHeight(x, z);
            geo.setLocalTranslation(x, groundY + 0.025f, z);
            geo.setLocalRotation(new Quaternion().fromAngleAxis(-FastMath.HALF_PI, Vector3f.UNIT_X));
            geo.setLocalScale(0.12f, 0.09f, 1f);
            waterNode.attachChild(geo);
            waterPuddles.add(new WaterPuddle(geo, x, z, radius));
        }


        // ── ИВЕНТ 3: Ледяная арена ─────────────────────────────
				private void startFrozenArenaEvent() {
						if (!mapSettings.enableFrozenArena) return;
						if (frozenArenaActive) return;
						frozenArenaActive = true;
						frozenArenaTimer = FROZEN_ARENA_DURATION;
						frozenSpeedMult = 1.0f;
						iceSpikeSpawnTimer = 1.5f;
						iceWarnDuration = ICE_WARN_START;
						if (iceSpikeNode == null) {
								iceSpikeNode = new Node("IceSpikes");
								rootNode.attachChild(iceSpikeNode);
						}
						if (iceParticleNode == null) {
								iceParticleNode = new Node("IceParticles");
								rootNode.attachChild(iceParticleNode);
						}
						applyFrozenFloorTint(true);
						for (SnakePlayer sp : snakes) sp.enableIceMode(true);
						showCenter("❄ ЛЕДЯНАЯ АРЕНА! Шипы вырываются из земли! ❄",
											 new ColorRGBA(0.6f, 0.9f, 1f, 1f));
						if (!solo) sendNet("EVENT_FROZEN");
				}

				private void updateFrozenArena(float tpf) {
						if (!frozenArenaActive) return;
						frozenArenaTimer -= tpf;
						if (frozenArenaTimer <= 0f) {
								stopFrozenArenaEvent();
								return;
						}
						float progress = 1f - frozenArenaTimer / FROZEN_ARENA_DURATION;
						iceWarnDuration = lerp(progress, ICE_WARN_START, ICE_WARN_MIN);
						float spawnInterval = lerp(progress, ICE_SPAWN_START, ICE_SPAWN_MIN);
						int maxSpikes = ICE_MAX_START + Math.round((ICE_MAX_END - ICE_MAX_START) * progress);
						if (solo || isHost) {
								iceSpikeSpawnTimer -= tpf;
								if (iceSpikeSpawnTimer <= 0f && iceSpikes.size() < maxSpikes) {
										iceSpikeSpawnTimer = spawnInterval;
										trySpawnIceSpike();
								}
						}
						for (int i = iceSpikes.size() - 1; i >= 0; i--) {
								IceSpike spike = iceSpikes.get(i);
								updateIceSpikeVisual(spike, tpf);
								spike.phaseTimer -= tpf;
								if (spike.phaseTimer <= 0f) advanceIceSpikePhase(spike);
								if (spike.done) {
										spike.root.removeFromParent();
										iceSpikes.remove(i);
								}
						}
						if (solo || isHost) checkIceSpikeCollisions();
						emitIceParticlesFromActiveSpikes(tpf);
						updateIceParticles(tpf);
				}

				/**
				 * Переключает шип в следующую фазу.
				 * Вызывается когда phaseTimer истёк.
				 */
				private void advanceIceSpikePhase(IceSpike spike) {
						switch (spike.phase) {

								case TELEGRAPHING -> {
										spike.phase = IceSpikePhase.RISING;
										spike.phaseTimer = ICE_RISE_DUR;
										spike.warnPulse  = 0f;
								}

								case RISING -> {
										spike.phase = IceSpikePhase.EXTENDED;
										spike.phaseTimer = ICE_HOLD_DUR;

										// Фиксируем финальные позиции без дрейфа
										float halfH = ICE_SPIKE_H * 0.5f;
										spike.body.setLocalTranslation(0f, ICE_BODY_EXTENDED, 0f);
										spike.glow.setLocalTranslation(0f, ICE_BODY_EXTENDED, 0f);
										spike.tip.setLocalTranslation(0f,  ICE_BODY_EXTENDED + halfH, 0f);

										// Скрываем предупреждение полностью
										hideSpikeWarning(spike);

										// Вспышка частиц при появлении
										burstIceParticles(spike.x, spike.floorY + ICE_SPIKE_H * 0.4f, spike.z, 8);
								}

								case EXTENDED -> {
										spike.phase = IceSpikePhase.RETRACTING;
										spike.phaseTimer = ICE_SINK_DUR;
										spike.warnPulse  = 0f;

										// Возвращаем исходные цвета Lighting-материалов (Diffuse + Ambient)
										spike.body.getMaterial().setColor("Diffuse", new ColorRGBA(0.45f, 0.75f, 0.95f, 0.78f));
										spike.body.getMaterial().setColor("Ambient", new ColorRGBA(0.10f, 0.22f, 0.35f, 0.78f));

										spike.tip.getMaterial().setColor("Diffuse", new ColorRGBA(0.80f, 0.93f, 1.00f, 0.90f));
										spike.tip.getMaterial().setColor("Ambient", new ColorRGBA(0.18f, 0.30f, 0.40f, 0.90f));
								}

								case RETRACTING -> {
										spike.done = true; // помечаем к удалению в главном цикле
								}
						}
				}

				/** Скрывает все элементы предупреждения на полу (после появления шипа). */
				private void hideSpikeWarning(IceSpike spike) {
						ColorRGBA transparent = new ColorRGBA(0f, 0f, 0f, 0f);
						spike.warnDisc.getMaterial().setColor("Color", transparent);
						spike.warnRing.getMaterial().setColor("Color", transparent);
						setChildrenAlpha(spike.cracks, 0f);
				}

				/**
				 * Устанавливает одинаковую альфу всем прямым дочерним Geometry узла.
				 * Не рекурсивный обход — только верхний уровень (оптимально для cracks).
				 */
				private static void setChildrenAlpha(Node node, float alpha) {
						for (Spatial child : node.getChildren()) {
								if (child instanceof Geometry g && g.getMaterial() != null) {
										Object col = g.getMaterial().getParamValue("Color");
										if (col instanceof ColorRGBA c) {
												g.getMaterial().setColor("Color",
																new ColorRGBA(c.r, c.g, c.b, FastMath.clamp(alpha, 0f, 1f)));
										}
								}
						}
				}

				/**
				 * Проверяет коллизии ледяных пиков со всеми живыми змеями.
				 *
				 * Добавить вызов в checkCollisions() рядом с:
				 *     if (mapIndex==1) checkCactusCollisions(s, i, tpf);
				 *
				 * Вызов:
				 *     if (frozenArenaActive) checkIceSpikeCollisions();
				 */
				private void checkIceSpikeCollisions() {
						for (IceSpike spike : iceSpikes) {
								// Урон только пока шип снаружи
								if (spike.phase != IceSpikePhase.RISING
								 && spike.phase != IceSpikePhase.EXTENDED) continue;

								float sx = spike.x, sz = spike.z;

								for (int si = 0; si < snakes.size(); si++) {
										SnakePlayer s = snakes.get(si);
										if (s.isDead()) continue;

										Vector3f head = s.getHeadPos();
										float dx = head.x - sx, dz = head.z - sz;
										float dist2 = dx * dx + dz * dz;

										// Голова попала на шип — мгновенная смерть
										if (dist2 < ICE_HIT_RADIUS * ICE_HIT_RADIUS) {
												killSnake(si, "пронзён ледяным шипом!");
												break; // змея мертва, переходим к следующей
										}

										// Тело (хвост) зацепило шип — срезаем ближайшие сегменты
										s.removeSegmentsAtWorldPos(
														new Vector3f(sx, head.y, sz), ICE_BODY_RADIUS);
								}
						}
				}

				/** Вспышка частиц при выходе шипа из земли. */
				private void burstIceParticles(float x, float y, float z, int count) {
						for (int i = 0; i < count; i++) {
								float vx = (eventRng.nextFloat() - 0.5f) * 2.8f;
								float vy =  1.8f + eventRng.nextFloat() * 2.2f;
								float vz = (eventRng.nextFloat() - 0.5f) * 2.8f;
								spawnIceParticle(x, y, z, vx, vy, vz);
						}
				}

				/** Слабое постоянное просыпание с активных шипов (пока они стоят). */
				private void emitIceParticlesFromActiveSpikes(float tpf) {
						if (iceParticles.size() >= ICE_PARTICLE_CAP) return;

						for (IceSpike spike : iceSpikes) {
								if (spike.phase != IceSpikePhase.EXTENDED) continue;
								// ~2 частицы в секунду на шип, без гарантии каждый кадр
								if (eventRng.nextFloat() > tpf * 2f) continue;

								float vx = (eventRng.nextFloat() - 0.5f) * 0.8f;
								float vy =  0.6f + eventRng.nextFloat() * 1.0f;
								float vz = (eventRng.nextFloat() - 0.5f) * 0.8f;
								// Рандомная высота вдоль стержня
								float yOff = spike.floorY + ICE_SPIKE_H * (0.2f + eventRng.nextFloat() * 0.8f);
								spawnIceParticle(spike.x, yOff, spike.z, vx, vy, vz);
						}
				}

				private void applyNameTagsVisibility() {
						for (SnakePlayer sp : snapshot(snakes)) {
								if (sp == null) continue;

								try {
										if (sp.nameTag != null) {
												sp.nameTag.setCullHint(nameTagsEnabled ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
										}
								} catch (Exception ignored) {}
						}
				}

				private void applyRuntimeGraphicsSettings() {
						if (app == null || app.getViewPort() == null || assetManager == null) return;

						// ── Тени ──
						if (gameShadowRenderer != null) {
								app.getViewPort().removeProcessor(gameShadowRenderer);
								gameShadowRenderer = null;
						}

						if (shadowsEnabled && dynamicLightsEnabled && sunLight != null) {
								gameShadowRenderer = SnakeApp.createShadowRendererForCurrentQuality(assetManager, sunLight);
								if (gameShadowRenderer != null) {
										app.getViewPort().addProcessor(gameShadowRenderer);
								}
						}

						// ── Постобработка: Bloom / Fog / God Rays ──
						if (gameFpp != null) {
								app.getViewPort().removeProcessor(gameFpp);
								gameFpp = null;
						}

						gameBloomFilter = null;
						gameFogFilter = null;
						gameLightScattering = null;

						boolean needFpp = postProcessingEnabled && (bloomEnabled || fogEnabled);

						if (needFpp) {
								gameFpp = new FilterPostProcessor(assetManager);

								if (bloomEnabled) {
										gameBloomFilter = new BloomFilter(BloomFilter.GlowMode.Objects);
										gameBloomFilter.setBloomIntensity(2.0f);
										gameBloomFilter.setExposurePower(5.0f);
										gameBloomFilter.setBlurScale(1.5f);
										gameFpp.addFilter(gameBloomFilter);

										gameLightScattering = new LightScatteringFilter(new Vector3f(ORBIT_RADIUS, 30f, -10f));
										gameLightScattering.setLightDensity(0.8f);
										gameLightScattering.setBlurStart(0.6f);
										gameLightScattering.setBlurWidth(0.5f);
										gameLightScattering.setNbSamples(150);
										gameLightScattering.setEnabled(true);
										gameFpp.addFilter(gameLightScattering);
								}

								if (fogEnabled) {
										gameFogFilter = new FogFilter();
										gameFogFilter.setFogColor(new ColorRGBA(0.65f, 0.70f, 0.82f, 1f));
										gameFogFilter.setFogDensity(0.30f);
										gameFogFilter.setFogDistance(140f);
										gameFpp.addFilter(gameFogFilter);
								}

								app.getViewPort().addProcessor(gameFpp);
						}

						// ── Ники игроков ──
						applyNameTagsVisibility();

						// ── Сохраняем без изменения ника ──
						saveSettings(myNick);
				}

				private void spawnIceParticle(float x, float y, float z,
																			 float vx, float vy, float vz) {
						if (iceParticles.size() >= ICE_PARTICLE_CAP) return;

						float r = 0.025f + eventRng.nextFloat() * 0.035f;
						Geometry geo = new Geometry("IcePart", new Sphere(4, 4, r));
						Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						mat.setColor("Color", new ColorRGBA(0.82f, 0.95f, 1.00f, 0.90f));
						mat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						geo.setMaterial(mat);
						geo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
						geo.setLocalTranslation(x, y, z);
						iceParticleNode.attachChild(geo);

						float life = ICE_PARTICLE_LIFE * (0.55f + eventRng.nextFloat() * 0.45f);
						iceParticles.add(new IceParticle(geo, vx, vy, vz, life));
				}

				/** Обновляет позиции и альфу всех частиц. */
				private void updateIceParticles(float tpf) {
						for (int i = iceParticles.size() - 1; i >= 0; i--) {
								IceParticle p = iceParticles.get(i);
								p.life -= tpf;

								if (p.life <= 0f) {
										p.geo.removeFromParent();
										iceParticles.remove(i);
										continue;
								}

								// Позиция
								Vector3f pos = p.geo.getLocalTranslation();
								pos.x += p.vx * tpf;
								pos.y += p.vy * tpf;
								pos.z += p.vz * tpf;
								p.geo.setLocalTranslation(pos);

								// Лёгкая гравитация и торможение по горизонтали
								p.vy  -= 2.0f * tpf;
								p.vx  *= (1f - tpf * 1.5f);
								p.vz  *= (1f - tpf * 1.5f);

								// Затухание альфы
								float alpha = FastMath.clamp(p.life / p.maxLife, 0f, 1f);
								p.geo.getMaterial().setColor("Color",
												new ColorRGBA(0.82f, 0.95f, 1.00f, alpha * 0.90f));
						}
				}

				/** Завершение события (сброс состояния и очистка). */
				private void stopFrozenArenaEvent() {
						frozenArenaActive = false;
						cleanupIceSpikes();
						cleanupIceParticles();
						applyFrozenFloorTint(false);
						for (SnakePlayer sp : snakes) sp.enableIceMode(false);
						showCenter("❄ Лёд растаял. Шипы ушли под землю.", new ColorRGBA(0.6f, 0.9f, 1f, 1f));
						if (solo || isHost) nextEventTimer = 25f + eventRng.nextFloat() * 45f;
				}

				/**
				 * Вызывается из cleanup() класса GameState для гарантированной очистки
				 * при любом выходе (ESC, смерть, завершение матча).
				 *
				 * Добавить в GameState.cleanup():
				 *     cleanupFrozenArena();
				 */
				private void cleanupFrozenArena() {
						cleanupIceSpikes();
						cleanupIceParticles();
						if (iceSpikeNode != null)    { rootNode.detachChild(iceSpikeNode);    iceSpikeNode    = null; }
						if (iceParticleNode != null) { rootNode.detachChild(iceParticleNode); iceParticleNode = null; }
				}

				private void cleanupIceSpikes() {
						for (IceSpike spike : iceSpikes) {
								if (spike.root != null && spike.root.getParent() != null)
										spike.root.removeFromParent();
						}
						iceSpikes.clear();
				}

				private void cleanupIceParticles() {
						for (IceParticle p : iceParticles) {
								if (p.geo.getParent() != null) p.geo.removeFromParent();
						}
						iceParticles.clear();
				}

				private IceSpike buildIceSpike(float x, float z, float floorY, float warnDuration) {
						IceSpike spike = new IceSpike(x, z, floorY, warnDuration);

						// Корневой узел позиционируем на поверхности пола
						spike.root = new Node("IceSpikeRoot");
						spike.root.setLocalTranslation(x, floorY, z);
						iceSpikeNode.attachChild(spike.root);

						// ── Материалы ───────────────────────────────────────────────────────────

						// Ледяной материал на основе Lighting — реагирует на свет, блики
						Material iceMat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
						iceMat.setBoolean("UseMaterialColors", true);
						iceMat.setColor("Diffuse",  new ColorRGBA(0.45f, 0.75f, 0.95f, 0.78f));
						iceMat.setColor("Ambient",  new ColorRGBA(0.10f, 0.22f, 0.35f, 0.78f));
						iceMat.setColor("Specular", new ColorRGBA(0.92f, 0.97f, 1.00f, 1f));
						iceMat.setFloat("Shininess", 96f);   // стекловидный блеск
						iceMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						iceMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

						// Острие — чуть ярче
						Material tipMat = new Material(assetManager, "Common/MatDefs/Light/Lighting.j3md");
						tipMat.setBoolean("UseMaterialColors", true);
						tipMat.setColor("Diffuse",  new ColorRGBA(0.80f, 0.93f, 1.00f, 0.90f));
						tipMat.setColor("Ambient",  new ColorRGBA(0.18f, 0.30f, 0.40f, 0.90f));
						tipMat.setColor("Specular", new ColorRGBA(1.00f, 1.00f, 1.00f, 1f));
						tipMat.setFloat("Shininess", 128f);
						tipMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						tipMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

						// Широкое свечение (оставляем Unshaded для Additive-эффекта)
						Material glowMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						glowMat.setColor("Color", new ColorRGBA(0.40f, 0.75f, 1.00f, 0.15f));
						glowMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						glowMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

						// Предупреждающий диск
						Material warnDiscMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						warnDiscMat.setColor("Color", new ColorRGBA(0.50f, 0.85f, 1.00f, 0.40f));
						warnDiscMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						warnDiscMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

						// Кольцо
						Material warnRingMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						warnRingMat.setColor("Color", new ColorRGBA(0.30f, 0.72f, 1.00f, 0.65f));
						warnRingMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						warnRingMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

						// ── Геометрия ─────────────────────────────────────────────────────────

						float halfH = ICE_SPIKE_H * 0.5f;

						// Стержень — Box, начальное положение: полностью под землёй
						spike.body = new Geometry("IceSpikeBody",
										new Box(ICE_SPIKE_BW, halfH, ICE_SPIKE_BW));
						spike.body.setMaterial(iceMat);
						spike.body.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
						spike.body.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
						spike.body.setLocalTranslation(0f, ICE_BODY_HIDDEN, 0f);
						spike.root.attachChild(spike.body);

						// Острие-пирамида: основание сидит на верхушке стержня
						spike.tip = new Geometry("IceSpikeTip",
										createPyramidMesh(ICE_SPIKE_BW * 1.35f, ICE_TIP_H));
						spike.tip.setMaterial(tipMat);
						spike.tip.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
						spike.tip.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
						// tipY = bodyCenter + halfH (верхушка стержня)
						spike.tip.setLocalTranslation(0f, ICE_BODY_HIDDEN + halfH, 0f);
						spike.root.attachChild(spike.tip);

						// Свечение — Box чуть шире, та же Y, что и у стержня
						float gw = ICE_SPIKE_BW * 2.2f;
						spike.glow = new Geometry("IceSpikeGlow",
										new Box(gw, halfH + 0.05f, gw));
						spike.glow.setMaterial(glowMat);
						spike.glow.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
						spike.glow.setLocalTranslation(0f, ICE_BODY_HIDDEN, 0f);
						spike.root.attachChild(spike.glow);

						// Предупреждающий диск на уровне пола
						spike.warnDisc = new Geometry("IceSpikeWarnDisc", createDiskMesh(0.55f, 20));
						spike.warnDisc.setMaterial(warnDiscMat);
						spike.warnDisc.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
						spike.warnDisc.setLocalTranslation(0f, 0.02f, 0f);
						spike.root.attachChild(spike.warnDisc);

						// Пульсирующее кольцо вокруг диска
						spike.warnRing = new Geometry("IceSpikeWarnRing", createRingMesh(0.50f, 0.70f, 24));
						spike.warnRing.setMaterial(warnRingMat);
						spike.warnRing.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);
						spike.warnRing.setLocalTranslation(0f, 0.03f, 0f);
						spike.root.attachChild(spike.warnRing);

						// Ледяные трещины (узор из тонких Box-ов)
						spike.cracks = buildCrackPattern();
						spike.cracks.setLocalTranslation(0f, 0.01f, 0f);
						spike.root.attachChild(spike.cracks);

						return spike;
				}

				private Node buildCrackPattern() {
						Node crackNode = new Node("Cracks");
						Material crackMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						crackMat.setColor("Color", new ColorRGBA(0.70f, 0.92f, 1.00f, 0.0f)); // начинаем прозрачными
						crackMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
						crackMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Off);

						int   crackCount = 6;
						float innerGap   = ICE_SPIKE_BW * 1.5f; // отступ от центра
						float maxLen     = 0.65f;

						for (int i = 0; i < crackCount; i++) {
								// Немного случайный угол для органичности
								float angle   = (float)(Math.PI * 2.0 * i / crackCount)
															+ (eventRng.nextFloat() - 0.5f) * 0.35f;
								float crackLen = maxLen * (0.65f + eventRng.nextFloat() * 0.35f);
								float halfLen  = crackLen * 0.5f;
								float offsetR  = innerGap + halfLen; // расстояние от центра до центра crack-Box'а

								// Box тонкий по X, почти плоский по Y, длинный по Z
								Geometry crack = new Geometry("Crack" + i,
												new Box(0.014f, 0.001f, halfLen));
								// Каждая трещина — собственная копия материала для независимой альфы
								Material cm = crackMat.clone();
								crack.setMaterial(cm);
								crack.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Transparent);

								// Позиционируем центр crack-а вдоль луча
								crack.setLocalTranslation(
												FastMath.cos(angle) * offsetR,
												0f,
												FastMath.sin(angle) * offsetR);
								// Разворачиваем вдоль того же луча (ось Z Box-а совпадает с лучом)
								crack.setLocalRotation(
												new Quaternion().fromAngleAxis(angle, Vector3f.UNIT_Y));
								crackNode.attachChild(crack);
						}
						return crackNode;
				}

				private void updateIceSpikeVisual(IceSpike spike, float tpf) {
						switch (spike.phase) {
								case TELEGRAPHING -> animateSpikeWarning(spike, tpf);
								case RISING       -> animateSpikeRising(spike);
								case EXTENDED     -> animateSpikeExtended(spike, tpf);
								case RETRACTING   -> animateSpikeRetracting(spike);
						}
				}

				/**
				 * RETRACTING: плавное погружение обратно под землю.
				 * Ease-in: медленный старт, быстрый финал.
				 */
				private void animateSpikeRetracting(IceSpike spike) {
						float t    = FastMath.clamp(1f - spike.phaseTimer / ICE_SINK_DUR, 0f, 1f);
						// Quadratic ease-in: f(t) = t^2
						float ease = t * t;

						float bodyY = lerp(ease, ICE_BODY_EXTENDED, ICE_BODY_HIDDEN);
						float halfH = ICE_SPIKE_H * 0.5f;

						spike.body.setLocalTranslation(0f, bodyY, 0f);
						spike.glow.setLocalTranslation(0f, bodyY, 0f);
						spike.tip.setLocalTranslation(0f, bodyY + halfH, 0f);

						// Тело становится прозрачнее по мере погружения
						float alpha = FastMath.clamp(1f - ease, 0f, 1f);
						spike.body.getMaterial().setColor("Diffuse",
										new ColorRGBA(0.45f, 0.75f, 0.95f, 0.78f * alpha));
						spike.body.getMaterial().setColor("Ambient",
										new ColorRGBA(0.10f, 0.22f, 0.35f, 0.78f * alpha));
						spike.tip.getMaterial().setColor("Diffuse",
										new ColorRGBA(0.80f, 0.93f, 1.00f, 0.90f * alpha));
						spike.tip.getMaterial().setColor("Ambient",
										new ColorRGBA(0.18f, 0.30f, 0.40f, 0.90f * alpha));
						spike.glow.getMaterial().setColor("Color",
										new ColorRGBA(0.40f, 0.75f, 1.00f, 0.15f * alpha));
				}

				/**
				 * TELEGRAPHING: пульсирующее предупреждение на полу.
				 * Нарастающая яркость по мере приближения появления.
				 */
				private void animateSpikeWarning(IceSpike spike, float tpf) {
						spike.warnPulse += tpf * 8f;

						// pulse: [0..1], синусоидально
						float pulse   = (FastMath.sin(spike.warnPulse) + 1f) * 0.5f;
						// urgency: 0 = только появился, 1 = вот-вот выйдет
						float urgency = 1f - spike.phaseTimer / spike.warnDuration;

						// Диск: пульсирует по альфе и масштабу
						float discAlpha = FastMath.clamp(0.20f + pulse * 0.40f + urgency * 0.25f, 0f, 0.85f);
						float discScale = 1.0f + urgency * 0.15f;
						spike.warnDisc.getMaterial().setColor("Color",
										new ColorRGBA(0.50f, 0.85f, 1.00f, discAlpha));
						spike.warnDisc.setLocalScale(discScale, 1f, discScale);

						// Кольцо: противофаза диска + растёт с urgency
						float ringAlpha = FastMath.clamp(0.30f + (1f - pulse) * 0.40f + urgency * 0.20f, 0f, 0.90f);
						float ringScale = 1.0f + urgency * 0.30f;
						spike.warnRing.getMaterial().setColor("Color",
										new ColorRGBA(0.25f, 0.72f, 1.00f, ringAlpha));
						spike.warnRing.setLocalScale(ringScale, 1f, ringScale);

						// Трещины: постепенно проявляются
						setChildrenAlpha(spike.cracks, FastMath.clamp(urgency * 0.80f, 0f, 0.70f));

						// Шип ещё под землёй — не трогаем его Y
				}

				/**
				 * RISING: резкое появление снизу вверх за ICE_RISE_DUR секунд.
				 * Ease-out: быстрый старт, плавное замедление перед остановкой.
				 */
				private void animateSpikeRising(IceSpike spike) {
						float t    = FastMath.clamp(1f - spike.phaseTimer / ICE_RISE_DUR, 0f, 1f);
						// Cubic ease-out: f(t) = 1 - (1-t)^3
						float ease = 1f - (1f - t) * (1f - t) * (1f - t);

						float bodyY = lerp(ease, ICE_BODY_HIDDEN, ICE_BODY_EXTENDED);
						float halfH = ICE_SPIKE_H * 0.5f;

						spike.body.setLocalTranslation(0f, bodyY, 0f);
						spike.glow.setLocalTranslation(0f, bodyY, 0f);
						spike.tip.setLocalTranslation(0f, bodyY + halfH, 0f);

						// Предупреждение исчезает по мере выхода шипа
						float warnFade = FastMath.clamp(1f - t, 0f, 1f);
						spike.warnDisc.getMaterial().setColor("Color",
										new ColorRGBA(0.50f, 0.85f, 1.00f, warnFade * 0.45f));
						spike.warnRing.getMaterial().setColor("Color",
										new ColorRGBA(0.25f, 0.72f, 1.00f, warnFade * 0.65f));
						setChildrenAlpha(spike.cracks, warnFade * 0.70f);
				}

				/**
				 * EXTENDED: шип полностью снаружи. Лёгкое покачивание свечения.
				 */
				private void animateSpikeExtended(IceSpike spike, float tpf) {
						spike.warnPulse += tpf * 3f;
						float glowA = 0.12f + FastMath.sin(spike.warnPulse) * 0.06f;
						spike.glow.getMaterial().setColor("Color",
										new ColorRGBA(0.40f, 0.75f, 1.00f, glowA));
				}

				/** Пытается найти безопасную позицию и создать пик. До 12 попыток. */
				private void trySpawnIceSpike() {
						for (int attempt = 0; attempt < 12; attempt++) {
								float x = (eventRng.nextFloat() - 0.5f) * (mapHalf * 2f - 4f);
								float z = (eventRng.nextFloat() - 0.5f) * (mapHalf * 2f - 4f);

								if (isTooCloseToSnakes(x, z, ICE_SAFE_FROM_SNAKE))  continue;
								if (isTooCloseToExistingSpikes(x, z, ICE_SAFE_FROM_SPIKE)) continue;

								float floorY = getSurfaceHeight(x, z);
								IceSpike spike = buildIceSpike(x, z, floorY, iceWarnDuration);
								iceSpikes.add(spike);
								return;
						}
						// Если не нашли свободное место — не спавним (без штрафа, попробуем в след. кадре)
				}

				private boolean isTooCloseToSnakes(float x, float z, float minDist) {
						float md2 = minDist * minDist;
						for (SnakePlayer sp : snakes) {
								if (sp.isDead()) continue;
								Vector3f h = sp.getHeadPos();
								float dx = h.x - x, dz = h.z - z;
								if (dx * dx + dz * dz < md2) return true;
						}
						return false;
				}

				private boolean isTooCloseToExistingSpikes(float x, float z, float minDist) {
						float md2 = minDist * minDist;
						for (IceSpike s : iceSpikes) {
								float dx = s.x - x, dz = s.z - z;
								if (dx * dx + dz * dz < md2) return true;
						}
						return false;
				}

				private void applyFrozenFloorTint(boolean enable) {
						final ColorRGBA ICE_FLOOR  = new ColorRGBA(0.55f, 0.78f, 0.98f, 1f);  // ледяной синеватый
						final ColorRGBA ICE_AMBIENT = new ColorRGBA(0.22f, 0.38f, 0.55f, 1f); // насыщенный синий ambient
						// Пол карты 0
						if (terrainFloorGeo != null) {
								Material m = terrainFloorGeo.getMaterial();
								if (enable) {
										m.setColor("Diffuse", ICE_FLOOR);
										m.setColor("Ambient", ICE_AMBIENT);
								} else {
										ColorRGBA orig = new ColorRGBA(0.25f, 0.48f, 0.18f, 1f);
										m.setColor("Diffuse", orig);
										m.setColor("Ambient", orig.mult(0.55f));
								}
						}
						// Пол карт 1/2
						if (flatFloorGeo != null) {
								Material m = flatFloorGeo.getMaterial();
								if (enable) {
										m.setColor("Diffuse", ICE_FLOOR);
										m.setColor("Ambient", ICE_AMBIENT);
								} else {
										m.setColor("Diffuse", originalFloorColor);
										m.setColor("Ambient", originalFloorColor.mult(0.35f));
								}
						}
						// Дополнительно: тонируем ячейки сетки terrain (карта 0)
						if (gridNode != null) {
								for (Geometry gl : gridLines) {
										Material m = gl.getMaterial();
										if (m == null) continue;
										if (enable) m.setColor("Color", new ColorRGBA(0.10f, 0.25f, 0.55f, 0.70f));
										else m.setColor("Color", baseGridColor);
								}
						}
				}

				/*private void applyTintToGridMaterial(boolean enable) {
						if (gridMaterial == null) return;
						if (enable) {
								if (originalGridColor == null) {
										Object colObj = gridMaterial.getParamValue("Color");
										if (colObj instanceof ColorRGBA) {
												originalGridColor = ((ColorRGBA) colObj).clone();
										}
								}
								if (originalGridColor != null) {
										ColorRGBA tinted = originalGridColor.clone().interpolateLocal(
														new ColorRGBA(0.72f, 0.88f, 1f, 1f), 0.35f);
										tinted.a = originalGridColor.a; // сохраняем исходную прозрачность
										gridMaterial.setColor("Color", tinted);
								}
						} else {
								if (originalGridColor != null) {
										gridMaterial.setColor("Color", originalGridColor.clone());
										originalGridColor = null;
								}
						}
				}*/

				private void applyTintToGeometry(Geometry g, boolean enable) {
						Material m = g.getMaterial();
						if (m == null) return;

						// Определяем основной параметр цвета материала
						String colorParam = null;
						if (m.getMaterialDef().getMaterialParam("Color") != null) {
								colorParam = "Color";
						} else if (m.getMaterialDef().getMaterialParam("Diffuse") != null) {
								colorParam = "Diffuse";
						}
						if (colorParam == null) return; // материал не поддерживает цвет, пропускаем

						if (enable) {
								// Сохраняем исходный цвет, если ещё не сохранён
								if (g.getUserData("FrozenBaseR") == null) {
										Object colObj = m.getParamValue(colorParam);
										if (colObj instanceof ColorRGBA base) {
												g.setUserData("FrozenBaseR", base.r);
												g.setUserData("FrozenBaseG", base.g);
												g.setUserData("FrozenBaseB", base.b);
												g.setUserData("FrozenBaseA", base.a);
										}
										g.setUserData("FrozenParam", colorParam); // запоминаем параметр
								}
								// Применяем ледяной оттенок
								m.setColor(colorParam, new ColorRGBA(0.72f, 0.88f, 1f, 1f));
						} else {
								// Восстанавливаем исходный цвет
								Float r = g.getUserData("FrozenBaseR");
								Float gg = g.getUserData("FrozenBaseG");
								Float b = g.getUserData("FrozenBaseB");
								Float a = g.getUserData("FrozenBaseA");
								String savedParam = g.getUserData("FrozenParam");
								if (r != null && savedParam != null) {
										m.setColor(savedParam, new ColorRGBA(r, gg, b, a != null ? a : 1f));
								}
								// Очищаем сохранённые данные
								g.setUserData("FrozenBaseR", null);
								g.setUserData("FrozenBaseG", null);
								g.setUserData("FrozenBaseB", null);
								g.setUserData("FrozenBaseA", null);
								g.setUserData("FrozenParam", null);
						}
				}

        // ── Кубы-враги ────────────────────────────────────────────────────
				private void spawnInitialCubes() {
						cubeNode = new Node("BlackCubes");
						rootNode.attachChild(cubeNode);
						if (solo || isHost) {
								float cornerOffset = 5f;  // отступ от стен, чтобы куб не застрял в текстурах
								float cx1 = -mapHalf + cornerOffset;   // левый
								float cx2 =  mapHalf - cornerOffset;   // правый
								float cz1 = -mapHalf + cornerOffset;   // ближний
								float cz2 =  mapHalf - cornerOffset;   // дальний

								float[][] cornerPositions = {
										{cx1, cz1}, {cx2, cz1}, {cx1, cz2}, {cx2, cz2}
								};

								// Перемешиваем, чтобы кубы были в случайных углах
								List<float[]> cornersList = new ArrayList<>(Arrays.asList(cornerPositions));
								Collections.shuffle(cornersList, new Random());

								int cubesToSpawn = Math.min(MAX_BLACK_CUBES, cornersList.size());
								for (int i = 0; i < cubesToSpawn; i++) {
										float[] pos = cornersList.get(i % cornersList.size());
										int id = cubeIdCounter++;
										spawnBlackCube(id, pos[0], pos[1]);
										if (!solo) sendNet("CUBE_SPAWN|" + id + "|" + pos[0] + "|" + pos[1]);
								}
						}
				}

				private BlackCube findBlackCube(int id) {
						for (BlackCube bc : blackCubes) {
								if (bc.id == id) return bc;
						}
						return null;
				}

				private BlackCube spawnBlackCube(int id, float x, float z) {
						BlackCube existing = findBlackCube(id);
						if (existing != null) return existing;

						if (cubeNode == null) {
								cubeNode = new Node("BlackCubes");
								rootNode.attachChild(cubeNode);
						}

						float side = 0.7f;

						Geometry geo = new Geometry("BlackCube_" + id, new Box(side, side, side));
						Material mat = litMat(assetManager, new ColorRGBA(0.05f, 0.05f, 0.07f, 1f));
						geo.setMaterial(mat);
						geo.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
						geo.setLocalTranslation(x, side + 0.2f, z);
						geo.setCullHint(Spatial.CullHint.Inherit);
						cubeNode.attachChild(geo);

						RigidBodyControl phy = null;

						// Физика куба нужна только хосту или одиночной игре.
						// Клиенту достаточно Geometry, позиция приходит через CUBE_STATE.
						if (solo || isHost) {
								phy = new RigidBodyControl(
												new BoxCollisionShape(new Vector3f(side, side, side)),
												15f
								);

								phy.setFriction(1.5f);
								phy.setRestitution(0.25f);
								phy.setAngularDamping(0.60f);
								phy.setLinearDamping(0.80f);

								geo.addControl(phy);
								bulletAppState.getPhysicsSpace().add(phy);
								phy.setPhysicsLocation(new Vector3f(x, side + 0.2f, z));
						}

						BlackCube bc = new BlackCube(id, geo, phy);
						blackCubes.add(bc);

						return bc;
				}

        /** Кубы ВСЕГДА ищут ближайшую живую змейку и атакуют */
        private void updateBlackCubes(float tpf) {
						// В сетевой игре кубы считает только хост.
						// Клиенты только получают CUBE_STATE и двигают визуал.
						if (!solo && !isHost) return;
            for (BlackCube bc : blackCubes) {
                if (!bc.active) continue;
                bc.glitchTimer       -= tpf;
                bc.impulseTimer      -= tpf;
                bc.patrolChangeTimer -= tpf;
                bc.rollSoundTimer    -= tpf;

                // Глюч-эффект
                if (bc.glitchTimer<=0f) {
                    bc.glitchTimer = 0.04f + FastMath.nextRandomFloat()*0.10f;
                    if (bc.phy!=null) {
                        Vector3f phyPos = bc.phy.getPhysicsLocation(null);
                        if (FastMath.nextRandomFloat()<0.45f) {
                            float jx=(FastMath.nextRandomFloat()-0.5f)*0.22f;
                            float jy=FastMath.nextRandomFloat()*0.10f;
                            float jz=(FastMath.nextRandomFloat()-0.5f)*0.22f;
                            bc.geo.setLocalTranslation(phyPos.x+jx, phyPos.y+jy, phyPos.z+jz);
                        }
                        if (FastMath.nextRandomFloat()<0.07f) {
                            float bigJx=(FastMath.nextRandomFloat()-0.5f)*0.55f;
                            float bigJz=(FastMath.nextRandomFloat()-0.5f)*0.55f;
                            bc.geo.setLocalTranslation(phyPos.x+bigJx, phyPos.y, phyPos.z+bigJz);
                        }
                        if (FastMath.nextRandomFloat()<0.30f) {
                            Vector3f curAng=bc.phy.getAngularVelocity();
                            float kick=(FastMath.nextRandomFloat()-0.5f)*0.9f;
                            bc.phy.setAngularVelocity(curAng.add(kick,kick*0.4f,kick));
                        }
                    }
                }

                // ИИ: преследуем только если змейка близко, иначе патруль
                if (bc.impulseTimer<=0f && bc.phy!=null) {
                    Vector3f cubePos = bc.phy.getPhysicsLocation(null);
                    final float CHASE_RANGE = 18f; // дистанция начала преследования

                    // Найти ближайшую живую змейку
                    SnakePlayer nearest = null;
                    float minDist = Float.MAX_VALUE;
                    Vector3f nearestTarget = null;
                    for (SnakePlayer s : snakes) {
                        if (s.isDead()) continue;
                        for (Vector3f seg : s.getSegmentPositions()) {
                            float d = cubePos.distance(seg);
                            if (d < minDist) { minDist = d; nearest = s; nearestTarget = seg; }
                        }
                    }

                    if (nearest != null && minDist < CHASE_RANGE) {
                        // Преследуем только если игрок достаточно близко
                        bc.chasing = true;
                        Vector3f toSnake = (nearestTarget != null ? nearestTarget : nearest.getHeadPos()).subtract(cubePos).setY(0);
                        if (toSnake.lengthSquared() > 0.001f) toSnake.normalizeLocal();

                        // Сила зависит от числа укусов (куб тяжелеет): bc.biteCount замедляет
                        float massFactor = 1f / (1f + bc.biteCount * 0.15f);
                        float urgency = Math.min(1f, minDist / CHASE_RANGE);
                        float force = (80f + urgency * 80f) * massFactor;

                        bc.phy.applyCentralForce(toSnake.mult(force));
                        Vector3f rollAxis = new Vector3f(toSnake.z, 0f, -toSnake.x).normalizeLocal();
                        bc.phy.applyTorque(rollAxis.mult(force*0.4f));
                    } else {
                        // Патруль или нет живых
                        bc.chasing = false;
                        if (bc.patrolChangeTimer<=0f) {
                            bc.patrolAngle += (FastMath.nextRandomFloat()-0.5f)*FastMath.PI;
                            bc.patrolChangeTimer = 2.5f + FastMath.nextRandomFloat()*3f;
                        }
                        float px=FastMath.sin(bc.patrolAngle), pz=FastMath.cos(bc.patrolAngle);
                        float patrolForce = 45f + FastMath.nextRandomFloat()*15f;
                        bc.phy.applyCentralForce(new Vector3f(px*patrolForce, 0, pz*patrolForce));
                    }
                }

                // Синхронизация визуальной позиции с физикой
                if (bc.phy!=null) {
                    Vector3f physPos = bc.phy.getPhysicsLocation(null);
                    if (bc.glitchTimer>0.02f) bc.geo.setLocalTranslation(physPos.x, physPos.y, physPos.z);
                    bc.geo.setLocalRotation(bc.phy.getPhysicsRotation(null));

                    if (bc.rollSoundTimer<=0f && cubeRollSound!=null) {
                        float speed = bc.phy.getLinearVelocity().length();
                        if (speed>1.2f) {
                            cubeRollSound.setVolume(Math.min(1f, speed/10f)*effectVolume);
                            cubeRollSound.playInstance();
                            bc.rollSoundTimer = 0.35f - Math.min(0.20f, speed*0.02f);
                        }
                    }
                }

                // Коллизия (авторитетно: хост/соло)
                if (solo || isHost) checkCubeVsSnakes(bc, tpf);
            }
        }

				private void interpolateClientCubes(float tpf) {
						if (solo || isHost) return; // хост управляет физикой напрямую
						for (BlackCube bc : blackCubes) {
								if (!bc.active) continue;
								bc.interpolateTransform(tpf);
						}
				}

        private void checkCubeVsSnakes(BlackCube bc, float tpf) {
            if (!bc.active||bc.phy==null) return;
            Vector3f cubePos = bc.phy.getPhysicsLocation(null);

            for (Integer key : new ArrayList<>(bc.hitCooldowns.keySet())) {
                float v = bc.hitCooldowns.get(key)-tpf;
                if (v<=0f) bc.hitCooldowns.remove(key); else bc.hitCooldowns.put(key,v);
            }

            final float hitThreshold = (0.7f * (1f + bc.biteCount * 0.12f)) + SnakePlayer.SEG_R; // учитываем рост куба
            for (int si=0;si<snakes.size();si++) {
                SnakePlayer s = snakes.get(si);
                if (s.isDead()) continue;
                if (bc.hitCooldowns.getOrDefault(si,0f)>0f) continue;

                Vector3f closestSeg = null;
                float minDist = Float.MAX_VALUE;
                for (Vector3f segPos : s.getSegmentPositions()) {
                    float d = cubePos.distance(segPos);
                    if (d<minDist) { minDist=d; closestSeg=segPos; }
                }

                if (closestSeg!=null && minDist<hitThreshold) {
                    bc.hitCooldowns.put(si, 2.5f);
                    Vector3f fromHit = cubePos.subtract(closestSeg).setY(0);
                    if (fromHit.lengthSquared()<0.001f)
                        fromHit = new Vector3f(FastMath.nextRandomFloat()-0.5f,0,FastMath.nextRandomFloat()-0.5f);
                    fromHit.normalizeLocal();
                    // уменьшен импульс отталкивания (было 55f → 18f), иначе куб улетал
                    float impulseMag = 18f / (1f + bc.biteCount * 0.2f);
                    bc.phy.applyImpulse(fromHit.mult(impulseMag).addLocal(0,2f,0), Vector3f.ZERO);
                    float spin=(FastMath.nextRandomFloat()-0.5f)*8f;
                    bc.phy.setAngularVelocity(new Vector3f(spin,spin*0.3f,spin));
                    bc.impulseTimer = 0.8f;
                    if (!solo) sendNet("CUBE_HIT|"+bc.id+"|"+si);
                    applyCubeHitLocal(bc.id, si);
                    return;
                }
            }
        }

        private void applyCubeHitLocal(int cubeId, int snakeIdx) {
            if (snakeIdx<0||snakeIdx>=snakes.size()) return;
            SnakePlayer s = snakes.get(snakeIdx);
            if (s.isDead()) return;
            int canShrink = Math.max(0, s.getLength()-2);
            int shrinkAmt = Math.min(CUBE_SHRINK_AMOUNT, canShrink);
            if (shrinkAmt>0) {
                for (int i=0;i<shrinkAmt;i++) s.shrink();
                if (snakeIdx==myIndex) showCenter("Куб атаковал! -"+shrinkAmt+" сегментов", new ColorRGBA(0.8f,0.1f,0.9f,1f));
            } else {
                if (snakeIdx==myIndex) showCenter("Куб атаковал!", new ColorRGBA(1f,0.2f,0.2f,1f));
            }
            // Куб растёт только если действительно откусил массу
            if (shrinkAmt <= 0) { if (snakeIdx == myIndex) playSound(chitSound); return; }
            for (BlackCube bc : blackCubes) {
                if (bc.id == cubeId) {
                    bc.biteCount++;
                    float growScale = 1f + bc.biteCount * 0.12f;
                    bc.geo.setLocalScale(growScale, growScale, growScale);
                    // Пересоздаём физику с увеличенным коллайдером (нет клиппинга в текстуры)
                    if (bc.phy != null && bulletAppState != null) {
                        Vector3f currentPos = bc.phy.getPhysicsLocation(null);
                        Quaternion currentRot = bc.phy.getPhysicsRotation(null);
                        float newMass = 5f + bc.biteCount * 2.5f;  // тяжелее → медленнее
                        float newSide = 0.7f * growScale;
                        bc.phy.setEnabled(false);
                        bulletAppState.getPhysicsSpace().remove(bc.phy);
                        bc.geo.removeControl(bc.phy);
                        RigidBodyControl newPhy = new RigidBodyControl(
                                new BoxCollisionShape(new Vector3f(newSide, newSide, newSide)), newMass);
                        newPhy.setFriction(1.5f); newPhy.setRestitution(0.25f);
                        newPhy.setAngularDamping(0.70f); newPhy.setLinearDamping(0.90f);
                        bc.geo.addControl(newPhy);
                        bulletAppState.getPhysicsSpace().add(newPhy);
                        newPhy.setPhysicsLocation(new Vector3f(currentPos.x, Math.max(newSide, currentPos.y), currentPos.z));
                        newPhy.setPhysicsRotation(currentRot);
                        // Обновляем ссылку (через отражение недоступно, пересоздаём объект)
                        bc.updatePhy(newPhy);
                    }
                    break;
                }
            }
            if (snakeIdx == myIndex) playSound(chitSound);
        }

				private void checkClientTimeouts() {
						long now = System.currentTimeMillis();
						for (Map.Entry<InetSocketAddress, Long> entry : clientLastSeen.entrySet()) {
								if (now - entry.getValue() > CLIENT_TIMEOUT * 1000L) {
										InetSocketAddress addr = entry.getKey();
										// Найти индекс змеи этого клиента по его адресу
										int idx = -1;
										for (int i = 0; i < snakes.size(); i++) {
												// Игрок-клиент всегда имеет индекс i, соответствующий порядку allPlayers,
												// но нам нужно сопоставить адрес с индексом. Можно сохранять map: addr -> playerIndex.
												// Проще всего – при подключении запомнить. Добавим поле addrToPlayerMap.
												if (clientIndexMap.get(addr) == i) {
														idx = i;
														break;
												}
										}
										if (idx >= 0) {
												// Убиваем змею
												killSnake(idx, "потеря связи");
												// Удаляем клиента из списков
												clients.remove(addr);
												clientLastSeen.remove(addr);
												clientIndexMap.remove(addr);
												// Оповестить остальных (можно послать DEAD или отдельное сообщение)
												sendNet("PLAYER_LEFT|" + idx);
										} else {
												// Клиент уже не в игре, просто забыть его
												clientLastSeen.remove(addr);
												clients.remove(addr);
												clientIndexMap.remove(addr);
										}
								}
						}
				}

        private void addBox(Vector3f pos, Vector3f half, Material mat, PhysicsSpace space) {
            Geometry g = new Geometry("Box", new Box(half.x,half.y,half.z));
            g.setMaterial(mat); g.setLocalTranslation(pos);
            // Тени: Lighting-материалы участвуют в тенях, Unshaded — нет
            if (mat != null && mat.getMaterialDef() != null &&
                    mat.getMaterialDef().getName().contains("Lighting")) {
                g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            }
            wallNode.attachChild(g);
            if (space!=null) {
                RigidBodyControl phy = new RigidBodyControl(new BoxCollisionShape(half), 0);
                g.addControl(phy); space.add(phy);
            }
        }

        // ── Главный цикл ──────────────────────────────────────────────────
        @Override
        public void update(float tpf) {
            tpf = safeTpf(tpf);
            if (tpf <= 0f || missingRefs("Game.update", app, rootNode, guiNode, assetManager, inputManager, cam, stateManager)) return;
            // Resize должен обрабатываться в самом начале кадра. Иначе пауза и
            // Game Over возвращали управление раньше, чем GUI успевал подогнаться.
            handleGameResizeIfNeeded();
            updateStatsHUD(tpf);
            // Пауза — замораживаем всё, но resize уже обработан выше.
            if (pauseActive && solo) return;
            if (!solo && !isHost && System.currentTimeMillis() - lastNetPacketMs > 6000L) {
                showCenter("Связь с хостом потеряна", new ColorRGBA(1f,0.35f,0.25f,1f));
                backToMenu();
                return;
            }

            moveClouds(tpf);
            updateDayNightCycle(tpf);
            gameTime += tpf;

            // Сетевая синхронизация времени суток (только хост отсылает)
            if (!solo && isHost) {
                dayNetTimer += tpf;
                if (dayNetTimer >= DAY_NET_INTERVAL) {
                    dayNetTimer = 0f;
                    sendNet("DAYTIME|" + dayNightTime);
                }
            }

            // Обновление рывка
            if (dashTimer > 0f) dashTimer -= tpf;
            if (dashCooldown > 0f) {
                dashCooldown -= tpf;
                if (dashCooldown < 0f) dashCooldown = 0f;
            }
            updateDashHUD();

            // Таймер игры
            int mins = (int)(gameTime/60); int secs = (int)(gameTime%60);
            if (gameTimerText != null) gameTimerText.setText(String.format("%d:%02d", mins, secs));

            // Планировщик случайных ивентов (хост/соло) — повторяются каждые 2–5 мин
						if ((solo || isHost) && !ballRainActive && !weatherRainActive && !frozenArenaActive && !sandstormActive && nextEventTimer > 0) {
								nextEventTimer -= tpf;
								if (nextEventTimer <= 0) {
										if (mapIndex == 1) {
												// На пустыне: только шариковый дождь, ледяная арена и песчаная буря
												int evt = eventRng.nextInt(3);
												if (evt == 0) startBallRainEvent();
												else if (evt == 1) startFrozenArenaEvent();
												else startSandstormEvent();
										} else {
												int evtChoice = eventRng.nextInt(3);
												if (evtChoice == 0) startBallRainEvent();
												else if (evtChoice == 1) startWeatherRainEvent();
												else startFrozenArenaEvent();
										}
								}
						}

            updateBallRain(tpf);
            updateWeatherRain(tpf);
            updateFrozenArena(tpf);
						updateSandstorm(tpf);

            updateExternalMap(tpf);

            if (centerMsgTimer>0) {
                centerMsgTimer -= tpf;
                if (centerMsgTimer<=0 && centerMsg != null) centerMsg.setColor(new ColorRGBA(1f,1f,0.2f,0f));
                else {
                    float a = Math.min(1f, centerMsgTimer);
                    if (centerMsg != null) { ColorRGBA c = centerMsg.getColor().clone(); c.a=a; centerMsg.setColor(c); }
                }
            }

            if (gameOver) {
                if (!gameoverUIActive) {
                    String winner = "НИКТО";
                    for (SnakePlayer s : snapshot(snakes)) if (s != null && !s.isDead()) { winner = s.getName(); break; }
                    showGameoverOverlay(winner);
                }
                updateActiveCamera(tpf);
                updateHUD(); updatePlayerTabText();
                for (SnakePlayer sp:snakes) sp.updateNameTag(cam);
                return;
            }

            // Спавн еды
            long regularFood = foodItems.stream().filter(f->!f.isDebris).count();
            if (mapSettings.enableRegularFood && regularFood<effectiveMaxFood) { if (solo) addOneFood(); else if (isHost) hostAddAndBroadcastFood(); }
            if (isHost) checkWinCondition();

            // Обновить змей (с замедлением от воды и рывком)
            float effectiveSpeed = effectiveSnakeSpeed * waterSpeedMultiplier * frozenSpeedMult;
            if (dashTimer > 0f && myIndex < snakes.size() && !snakes.get(myIndex).isDead()) {
                // Рывок применяется только к локальному игроку
                for (int i = 0; i < snakes.size(); i++) {
                    float spd = (i == myIndex) ? effectiveSpeed * DASH_SPEED_MULT : effectiveSpeed;
                    if (!snakes.get(i).isDead()) snakes.get(i).update(tpf, spd, effectiveTurnSpeed, SEG_SPACING);
                }
            } else {
                for (SnakePlayer s : snakes) if (!s.isDead()) s.update(tpf, effectiveSpeed, effectiveTurnSpeed, SEG_SPACING);
            }

						if (!solo && isHost) {
								clientTimeoutCheckTimer += tpf;
								if (clientTimeoutCheckTimer >= 1.0f) {
										clientTimeoutCheckTimer -= 1.0f;
										checkClientTimeouts();
								}
						}

            checkCollisions(tpf);

            // Проверка ям (карта 2)
            if (mapIndex==2) {
                if (solo || isHost) {
                    updatePits(tpf);
                    checkPits();
                } else {
                    updatePitsVisualOnly(tpf);
                }
            }

            // Сетевые отправки
            if (!solo) {
                if (!isHost) {
                    pingTimer -= tpf;
                    if (pingTimer <= 0f) {
                        pingTimer = 1.0f;
                        long seq = ++pingSeq;
                        pendingPings.put(seq, System.currentTimeMillis());
                        sendNet("PING|" + seq + "|" + pendingPings.get(seq));
                    }
                }
                netSendTimer += tpf;
                if (netSendTimer>=NET_SEND_INTERVAL && myIndex<snakes.size()) {
                    netSendTimer=0;
                    SnakePlayer me = snakes.get(myIndex);
                    Vector3f h = me.getHeadPos();
                    int inputMask = (me.isTurnLeft()?1:0) | (me.isTurnRight()?2:0) | (me.isMoving()?4:0);
                    if (inputMask != lastSentInputMask) {
                        lastSentInputMask = inputMask;
                        sendNet("INPUT|"+myIndex+"|"+(me.isTurnLeft()?1:0)+"|"+(me.isTurnRight()?1:0)+"|"+(me.isMoving()?1:0));
                    }
                    sendNet("STATE|"+myIndex+"|"+h.x+"|"+h.y+"|"+h.z+"|"+me.getHeadingAngle()
                            +"|"+me.getScore()+"|"+me.getLength()+"|"+(me.isDead()?1:0));
                }
                if (isHost) {
                    cubeNetTimer += tpf;
										if (cubeNetTimer >= CUBE_NET_INTERVAL) {
												cubeNetTimer = 0f;
												for (BlackCube bc : snapshot(blackCubes)) {
														if (!bc.active || bc.phy == null) continue;
														Vector3f pos = bc.phy.getPhysicsLocation(null);
														Quaternion rot = bc.phy.getPhysicsRotation(null);
														sendNet("CUBE_STATE|" + bc.id + "|" + pos.x + "|" + pos.y + "|" + pos.z 
																		+ "|" + rot.getX() + "|" + rot.getY() + "|" + rot.getZ() + "|" + rot.getW());
												}
										}
                }
            }

            updateActiveCamera(tpf); updateHUD(); updatePlayerTabText();
            for (SnakePlayer sp:snakes) sp.updateNameTag(cam);
						updateCactusRespawns(tpf);
						updateClientCactusStickRequests(tpf);
						interpolateClientCubes(tpf);
            updateBlackCubes(tpf);
						
						if (lastW == -1 || lastH == -1) {
								lastW = cam.getWidth();
								lastH = cam.getHeight();
						}

						if (cam.getWidth() != lastW || cam.getHeight() != lastH) {

								lastW = cam.getWidth();
								lastH = cam.getHeight();

								refreshGameGuiLayout();
						}
						
        }

        /** Обновление анимации шипов (карта 2) — вызывается каждый кадр */
        private void updatePits(float tpf) {
            if (mapIndex != 2) return;
            for (int pi = 0; pi < pits.size(); pi++) {
                PitData pit = pits.get(pi);
                pit.stateTimer -= tpf;
                float progress; // 0=задвинуто, 1=выдвинуто

                switch (pit.state) {
                    case RETRACTED:
                        // Мигание предупреждения за PIT_WARNING_TIME до выдвижения
                        if (pit.stateTimer <= PIT_WARNING_TIME) {
                            pit.warnFlashTimer += tpf;
                            float alpha = (FastMath.sin(pit.warnFlashTimer * 20f) * 0.5f + 0.5f) * 0.8f;
                            if (pit.warningRing != null)
                                pit.warningRing.getMaterial().setColor("Color", new ColorRGBA(0.9f,0.3f,0.1f,alpha));
                        }
                        if (pit.stateTimer <= 0f) {
                            pit.state = PitState.EXTENDING;
                            pit.stateTimer = PIT_EXTENDING_DURATION;
                            pit.warnFlashTimer = 0f;
                            if (pit.warningRing != null)
                                pit.warningRing.getMaterial().setColor("Color", new ColorRGBA(0.9f,0.3f,0.1f,0f));
                            // Синхронизация по сети
                            if (!solo) sendNet("SPIKE_STATE|" + pi + "|1|" + PIT_EXTENDING_DURATION);
                        }
                        setSpikeProgress(pit, 0f);
                        break;

                    case EXTENDING:
                        progress = 1f - Math.max(0f, pit.stateTimer / PIT_EXTENDING_DURATION);
                        setSpikeProgress(pit, progress);
                        if (pit.stateTimer <= 0f) {
                            pit.state = PitState.EXTENDED;
                            pit.stateTimer = PIT_EXTENDED_DURATION;
                            if (!solo) sendNet("SPIKE_STATE|" + pi + "|2|" + PIT_EXTENDED_DURATION);
                        }
                        break;

                    case EXTENDED:
                        setSpikeProgress(pit, 1f);
                        if (pit.stateTimer <= 0f) {
                            pit.state = PitState.RETRACTING;
                            pit.stateTimer = PIT_RETRACTING_DURATION;
                            if (!solo) sendNet("SPIKE_STATE|" + pi + "|3|" + PIT_RETRACTING_DURATION);
                        }
                        break;

                    case RETRACTING:
                        progress = Math.max(0f, pit.stateTimer / PIT_RETRACTING_DURATION);
                        setSpikeProgress(pit, progress);
                        if (pit.stateTimer <= 0f) {
                            pit.state = PitState.RETRACTED;
                            pit.stateTimer = PIT_RETRACTED_DURATION;
                            if (!solo) sendNet("SPIKE_STATE|" + pi + "|0|" + PIT_RETRACTED_DURATION);
                        }
                        break;
                }
            }
        }
                private void handleGameResizeIfNeeded() {
                    if (cam == null) return;
                    if (lastW == -1 || lastH == -1) {
                        lastW = cam.getWidth();
                        lastH = cam.getHeight();
                        refreshGameGuiLayout();
                        return;
                    }
                    if (cam.getWidth() != lastW || cam.getHeight() != lastH) {
                        lastW = cam.getWidth();
                        lastH = cam.getHeight();
                        refreshGameGuiLayout();
                    }
                }

                private void refreshGameGuiLayout() {
                    float W = cam.getWidth();
                    float H = cam.getHeight();
                    float S = uiScale(cam);

                    if (gameTimerText != null) {
                        gameTimerText.setSize(18f * S);
                        gameTimerText.setLocalTranslation(W / 2f - gameTimerText.getLineWidth() / 2f, H - 22f * S, 0f);
                    }

                    if (centerMsg != null) {
                        centerMsg.setSize(38f * S);
                        centerMsg.setLocalTranslation(W / 2f - centerMsg.getLineWidth() / 2f, H / 2f, 0f);
                    }

                    // FPS / AVG / PING — одна ровная полоска слева сверху.
                    if (netStatsBg != null) {
                        netStatsBg.setMesh(new Box(190f * S, 18f * S, 0.2f));
                        netStatsBg.setLocalTranslation(206f * S, H - 28f * S, 2f);
                    }
                    if (netStatsText != null) {
                        netStatsText.setSize(13f * S);
                        netStatsText.setLocalTranslation(22f * S, H - 23f * S, 3f);
                    }

                    float cx = W - 76f * S;
                    float cy = 74f * S;
                    if (dashCircleBg != null) {
                        dashCircleBg.setMesh(createRingMesh(30f * S, 42f * S, 1f));
                        dashCircleBg.setLocalTranslation(cx, cy, 2f);
                    }
                    if (dashCircleFill != null) dashCircleFill.setLocalTranslation(cx, cy, 3f);
                    if (dashShiftText != null) {
                        dashShiftText.setSize(16f * S);
                        dashShiftText.setLocalTranslation(cx - dashShiftText.getLineWidth()/2f, cy + 6f * S, 4f);
                    }
                    if (dashCooldownText != null) {
                        dashCooldownText.setSize(12f * S);
                        dashCooldownText.setLocalTranslation(cx - dashCooldownText.getLineWidth()/2f, cy - 18f * S, 4f);
                    }

                    // Пауза должна подстраиваться прямо во время resize, даже если игра стоит на паузе.
                    if (pauseNode != null) {
                        boolean wasVisible = pauseActive && pauseNode.getCullHint() != Spatial.CullHint.Always;
                        UiGrid.clear(pauseNode);
                        guiNode.detachChild(pauseNode);
                        pauseNode = null;
                        pauseResumeBtn = null;
												pauseSettingsBtn = null;
                        pauseMenuBtn = null;
                        pauseLobbyBtn = null;
												pauseSettingsNode = null;
												pauseSettingsOpen = false;
												pauseSettingsCloseBtn = null;
												pauseSfxVal = null;
												pauseMusicVal = null;
												pauseSfxSlider = null;
												pauseMusicSlider = null;

												pauseTabMainBtn = null;
												pauseTabGraphicsBtn = null;

												pauseBtnShadows = null;
												pauseBtnParticles = null;
												pauseBtnFog = null;
												pauseBtnBloom = null;
												pauseBtnPost = null;
												pauseBtnLights = null;
												pauseBtnWater = null;
												pauseBtnTerrain = null;
												pauseBtnLowPoly = null;
												pauseBtnNames = null;
                        rebuildingGuiForResize = true;
                        buildPauseUI();
                        rebuildingGuiForResize = false;
                        if (pauseNode != null && wasVisible) pauseNode.setCullHint(Spatial.CullHint.Inherit);
                    }

                    // Game Over раньше создавался один раз с фиксированными размерами.
                    // Теперь пересоздаём его под текущее окно и заново заполняем результатами.
                    if (gameoverNode != null) {
                        boolean wasVisible = gameoverUIActive && gameoverNode.getCullHint() != Spatial.CullHint.Always;
                        String winner = "НИКТО";
                        for (SnakePlayer s : snakes) {
                            if (!s.isDead()) { winner = s.getName(); break; }
                        }
                        UiGrid.clear(gameoverNode);
                        guiNode.detachChild(gameoverNode);
                        gameoverNode = null;
                        goPlayAgainBtn = null;
                        goMenuBtn = null;
                        boolean active = gameoverUIActive;
                        gameoverUIActive = false;
                        createGameoverUI();
                        if (active || wasVisible) {
                            gameoverUIActive = true;
                            if (gameoverNode != null) gameoverNode.setCullHint(Spatial.CullHint.Inherit);
                            if (inputManager != null) inputManager.setCursorVisible(true);
                            populateGameoverContent(winner);
                        }
                    }
                }

        private void updatePitsVisualOnly(float tpf) {
	            if (mapIndex != 2) return;
	            for (PitData pit : pits) {
	                pit.stateTimer -= tpf;
	                float progress = 0f;
	                switch (pit.state) {
	                    case RETRACTED:
                            progress = 0f;
                            if (pit.stateTimer <= PIT_WARNING_TIME) {
                                pit.warnFlashTimer += tpf;
                                float alpha = (FastMath.sin(pit.warnFlashTimer * 20f) * 0.5f + 0.5f) * 0.8f;
                                if (pit.warningRing != null) {
                                    pit.warningRing.getMaterial().setColor("Color", new ColorRGBA(0.9f,0.3f,0.1f,alpha));
                                }
                            }
                            break;
	                    case EXTENDING:
                            progress = 1f - Math.max(0f, pit.stateTimer / PIT_EXTENDING_DURATION);
                            if (pit.warningRing != null) pit.warningRing.getMaterial().setColor("Color", new ColorRGBA(0.9f,0.3f,0.1f,0f));
                            break;
	                    case EXTENDED:   progress = 1f; break;
	                    case RETRACTING: progress = Math.max(0f, pit.stateTimer / PIT_RETRACTING_DURATION); break;
	                }
	                setSpikeProgress(pit, progress);
	                if (pit.stateTimer <= 0f) {
	                    switch (pit.state) {
	                        case RETRACTED:  pit.state = PitState.EXTENDING;  pit.stateTimer = PIT_EXTENDING_DURATION; break;
	                        case EXTENDING:  pit.state = PitState.EXTENDED;   pit.stateTimer = PIT_EXTENDED_DURATION; break;
	                        case EXTENDED:   pit.state = PitState.RETRACTING; pit.stateTimer = PIT_RETRACTING_DURATION; break;
	                        case RETRACTING: pit.state = PitState.RETRACTED;  pit.stateTimer = PIT_RETRACTED_DURATION; break;
	                    }
	                }
	            }
	        }

	        /** Устанавливает высоту шипов (0=под полом, 1=полностью выдвинуты) */
        private void setSpikeProgress(PitData pit, float p) {
            float floorY = PitData.FLOOR_Y;
            float spikeH = PitData.SPIKE_H;
            // Задвинуто: тело шипа центр = floorY - spikeH (полностью под полом)
            // Выдвинуто: тело шипа центр = floorY + spikeH/2 (торчит наружу)
            float bodyY  = floorY - spikeH + p * (spikeH + spikeH / 2f);
            float tipY   = bodyY + spikeH / 2f + spikeH * 0.3f;
            float glintY = bodyY + spikeH / 2f;
            for (int i = 0; i < pit.spikes.size(); i++) {
                Geometry s = pit.spikes.get(i);
                Vector3f st = s.getLocalTranslation();
                s.setLocalTranslation(st.x, bodyY, st.z);
                if (i < pit.spikeTips.size()) {
                    Geometry t = pit.spikeTips.get(i);
                    Vector3f tt = t.getLocalTranslation();
                    t.setLocalTranslation(tt.x, tipY, tt.z);
                }
                if (i < pit.spikeGlints.size()) {
                    Geometry g = pit.spikeGlints.get(i);
                    Vector3f gt = g.getLocalTranslation();
                    g.setLocalTranslation(gt.x, glintY, gt.z);
                }
            }
        }

        /** Проверка ям — голова = смерть, тело = удаление сегмента (только при EXTENDED) */
        private void checkPits() {
            for (int si = 0; si < snakes.size(); si++) {
                SnakePlayer s = snakes.get(si);
                if (s.isDead()) continue;
                Vector3f head = s.getHeadPos();

                for (PitData pit : pits) {
                    float dx = head.x - pit.position.x;
                    float dz = head.z - pit.position.z;
                    float distHead = (float)Math.sqrt(dx*dx + dz*dz);

                    // Урон только когда шипы выдвинуты (EXTENDED)
                    if (pit.state != PitState.EXTENDED) continue;

                    // Голова попала в зону шипов — мгновенная смерть
                    if (distHead < pit.radius * 0.80f) {
                        killSnake(si, "попал на шипы!");
                        break;
                    }

                    // Тело (хвост) попало на шипы — удаляем затронутый сегмент
                    s.removeSegmentsAtWorldPos(new Vector3f(pit.position.x, head.y, pit.position.z), pit.radius * 0.75f);
                }
            }
        }

				// Проверка столкновений
				private void checkCollisions(float tpf) {
						if (!solo && !isHost) return; // клиенты не проверяют коллизии локально

						for (int i = 0; i < snakes.size(); i++) {
								SnakePlayer s = snakes.get(i);
								if (s.isDead()) continue;
								Vector3f h = s.getHeadPos();
								float wallBound = mapHalf - 0.9f - SnakePlayer.SEG_R;
								if (!bordersRemoved && (Math.abs(h.x) > wallBound || Math.abs(h.z) > wallBound)) {
										killSnake(i, "стена");
										continue;
								}
								if (s.selfCollides(SEG_SPACING * 0.8f)) {
										killSnake(i, "самопересечение");
										continue;
								}
								for (int j = 0; j < snakes.size(); j++) {
										if (i == j || snakes.get(j).isDead()) continue;
										SnakePlayer other = snakes.get(j);
										if (other.bodyContains(h, SEG_SPACING * 0.85f) || h.distance(other.getHeadPos()) < SEG_SPACING) {
												killSnake(i, "столкновение с " + other.getName());
												break;
										}
								}
								// Check food for this snake
								checkFoodFor(s, i);
								if (mapIndex == 1) checkCactusCollisions(s, i, tpf);
								if (frozenArenaActive) checkIceSpikeCollisions();
						}
				}

				// helper-методы
				private void removeCactusSpines(CactusData cd) {
						if (cd == null) return;

						for (Geometry spine : new ArrayList<>(cd.spines)) {
								if (spine != null && spine.getParent() != null) {
										spine.removeFromParent();
								}
						}
						cd.spines.clear();
				}

				private CactusData findCactusByPos(float cx, float cz) {
						for (CactusData cd : cacti) {
								if (cd == null) continue;
								if (Math.abs(cd.origX - cx) < 0.5f && Math.abs(cd.origZ - cz) < 0.5f) {
										return cd;
								}
						}
						return null;
				}

				private int getCactusFragIndex(Geometry frag, int fallback) {
						if (frag == null) return fallback;

						Integer idx = frag.getUserData("fragIndex");
						return idx != null ? idx : fallback;
				}

				private int findCactusFragmentListIndex(CactusData cd, int fragIndex) {
						if (cd == null) return -1;

						for (int i = 0; i < cd.fragments.size(); i++) {
								Geometry frag = cd.fragments.get(i);
								if (frag == null) continue;

								Integer idx = frag.getUserData("fragIndex");
								if (idx != null && idx == fragIndex) {
										return i;
								}
						}

						// fallback для старых фрагментов без userData
						if (fragIndex >= 0 && fragIndex < cd.fragments.size()) {
								return fragIndex;
						}

						return -1;
				}

				private boolean attachCactusFragmentNetworked(
								CactusData cd,
								int listIndex,
								int snakeIdx,
								Vector3f contactWorld,
								boolean broadcast
				) {
						if (cd == null) return false;
						if (snakeIdx < 0 || snakeIdx >= snakes.size()) return false;
						if (listIndex < 0 || listIndex >= cd.fragments.size()) return false;
						if (contactWorld == null) return false;

						SnakePlayer snake = snakes.get(snakeIdx);
						if (snake == null || snake.isDead()) return false;

						Geometry fragGeo = cd.fragments.get(listIndex);
						if (fragGeo == null) return false;

						int fragIndex = getCactusFragIndex(fragGeo, listIndex);

						Node fragNode = fragGeo.getParent();
						if (fragNode == null) {
								cd.fragments.remove(listIndex);
								if (listIndex < cd.fragmentTimers.size()) cd.fragmentTimers.remove(listIndex);
								return false;
						}

						RigidBodyControl fp = fragNode.getControl(RigidBodyControl.class);
						if (fp != null && bulletAppState != null && bulletAppState.getPhysicsSpace() != null) {
								bulletAppState.getPhysicsSpace().remove(fp);
								fragNode.removeControl(fp);
						}

						fragNode.removeFromParent();
						snake.attachCactusFragment(fragNode, contactWorld);

						cd.fragments.remove(listIndex);
						if (listIndex < cd.fragmentTimers.size()) cd.fragmentTimers.remove(listIndex);

						if (broadcast && !solo) {
								sendNet("CACT_STICK|" + snakeIdx
												+ "|" + cd.origX
												+ "|" + cd.origZ
												+ "|" + fragIndex
												+ "|" + contactWorld.x
												+ "|" + contactWorld.y
												+ "|" + contactWorld.z);
						}

						return true;
				}

				private void updateClientCactusStickRequests(float tpf) {
						if (solo || isHost) return;
						if (mapIndex != 1) return;
						if (myIndex < 0 || myIndex >= snakes.size()) return;

						SnakePlayer me = snakes.get(myIndex);
						if (me == null || me.isDead()) return;

						for (CactusData cd : cacti) {
								if (cd == null || !cd.hit) continue;

								for (int fi = cd.fragments.size() - 1; fi >= 0; fi--) {
										Geometry frag = cd.fragments.get(fi);
										if (frag == null) continue;

										// Локально чистим старые визуальные обломки у клиента
										if (fi < cd.fragmentTimers.size()) {
												float time = cd.fragmentTimers.get(fi) - tpf;
												if (time <= 0f) {
														Spatial parent = frag.getParent();
														if (parent != null) {
																RigidBodyControl fp = parent.getControl(RigidBodyControl.class);
																if (fp != null && bulletAppState != null && bulletAppState.getPhysicsSpace() != null) {
																		bulletAppState.getPhysicsSpace().remove(fp);
																}
																parent.removeFromParent();
														}

														cd.fragments.remove(fi);
														cd.fragmentTimers.remove(fi);
														continue;
												} else {
														cd.fragmentTimers.set(fi, time);
												}
										}

										Boolean requested = frag.getUserData("stickRequested");
										if (Boolean.TRUE.equals(requested)) continue;

										Vector3f fragPos = frag.getWorldTranslation().clone();

										if (me.bodyContains(fragPos, 1.2f)) {
												frag.setUserData("stickRequested", true);

												int fragIndex = getCactusFragIndex(frag, fi);

												sendNet("CACT_STICK_REQ|" + myIndex
																+ "|" + cd.origX
																+ "|" + cd.origZ
																+ "|" + fragIndex
																+ "|" + fragPos.x
																+ "|" + fragPos.y
																+ "|" + fragPos.z);
										}
								}
						}
				}

        /** Проверка столкновения головы змеи с кактусами → разрушение + прилипание к телу */
				private void checkCactusCollisions(SnakePlayer me, int playerIndex, float tpf) {
						Vector3f h = me.getHeadPos();
						for (CactusData cd : cacti) {
								if (cd.hit) {
										// Фрагменты уже созданы — проверяем прилипание к телу
										for (int fi = cd.fragments.size()-1; fi >= 0; fi--) {
												Geometry frag = cd.fragments.get(fi);
												float t = cd.fragmentTimers.get(fi) - tpf;
												if (t <= 0f) {
														Spatial fragNode = frag.getParent();
														if (fragNode != null) {
																RigidBodyControl fp = fragNode.getControl(RigidBodyControl.class);
																if (fp != null) bulletAppState.getPhysicsSpace().remove(fp);
																fragNode.removeFromParent();
														}
														cd.fragments.remove(fi);
														cd.fragmentTimers.remove(fi);
														continue;
												} else {
														cd.fragmentTimers.set(fi, t);
												}
												if (me.bodyContains(frag.getWorldTranslation(), 1.2f)) {
														// Только хост (или соло) выполняет прилипание
														if (solo || isHost) {
																Vector3f contactWorld = frag.getWorldTranslation().clone();
																attachCactusFragmentNetworked(cd, fi, playerIndex, contactWorld, !solo);
														}
												}
										}
										continue;
								}

								// Кактус ещё стоит: проверка столкновения головой
								Vector3f cpos = cd.trunkGeo.getWorldTranslation();
								float dx = h.x - cpos.x;
								float dz = h.z - cpos.z;
								if (dx * dx + dz * dz < 1.2f * 1.2f) {
										cd.hit = true;

										cd.respawnTimer = 60f;   // 15 секунд до восстановления
										cd.queuedForRespawn = true;

										// Убираем ствол и руку (физику тоже)
										// Убираем ствол и руку (физику тоже)
										if (cd.trunkPhy != null && bulletAppState != null) {
												cd.trunkPhy.setEnabled(false);
												bulletAppState.getPhysicsSpace().remove(cd.trunkPhy);
										}
										if (cd.trunkGeo.getParent() != null) cd.trunkGeo.removeFromParent();
										if (cd.armGeo != null && cd.armGeo.getParent() != null) {
												cd.armGeo.removeFromParent();
										}

										// Удаляем старые колючки, висевшие на кактусе
										removeCactusSpines(cd);

										// Иголки cd.spines остаются на месте — ничего не делаем

										// Создаём обломки с колючками
										Material fragMat = litMat(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
										Material spineMat = litMat(assetManager, new ColorRGBA(0.86f,0.82f,0.66f,1f));
										// ─── Детерминированный рандом для обломков ───
										Random fragRng = new Random(Float.floatToIntBits(cd.origX) ^ Float.floatToIntBits(cd.origZ));

										for (int fi = 0; fi < 4; fi++) {
												float fsize = 0.15f + fragRng.nextFloat() * 0.2f;
												Node frag = new Node("CactFragNode" + fi);
												Geometry core = new Geometry("CactFrag" + fi, new Box(fsize, fsize, fsize));
												core.setMaterial(fragMat);
												frag.attachChild(core);
												// используем fragRng вместо FastMath.nextRandomFloat()
												frag.setLocalTranslation(cpos.add(
																(fragRng.nextFloat() - 0.5f) * 0.5f,
																fragRng.nextFloat() * 0.8f,
																(fragRng.nextFloat() - 0.5f) * 0.5f));
												wallNode.attachChild(frag);
												RigidBodyControl fp = new RigidBodyControl(
																new BoxCollisionShape(new Vector3f(fsize, fsize, fsize)), 0.5f);
												fp.setLinearVelocity(new Vector3f(
																(fragRng.nextFloat() - 0.5f) * 6f,
																2f + fragRng.nextFloat() * 3f,
																(fragRng.nextFloat() - 0.5f) * 6f));
												frag.addControl(fp);
												bulletAppState.getPhysicsSpace().add(fp);

												for (int sj = 0; sj < 4; sj++) {
														Geometry spike = new Geometry("FragSpike" + fi + "_" + sj, new Box(0.02f, 0.02f, 0.12f));
														spike.setMaterial(spineMat);
														float a = sj * FastMath.HALF_PI + fragRng.nextFloat() * 0.2f;
														spike.setLocalRotation(new Quaternion().fromAngleAxis(a, Vector3f.UNIT_Y));
														spike.setLocalTranslation(FastMath.cos(a) * fsize, 0f, FastMath.sin(a) * fsize);
														frag.attachChild(spike);
												}
												cd.fragments.add(core);
												cd.fragmentTimers.add(CactusData.FRAGMENT_LIFETIME);
										}

										if (!solo) sendNet("CACT_HIT|" + cd.origX + "|" + cd.origZ);
								}
						}
				}

        private void checkFoodFor(SnakePlayer snake, int snakeIndex) {
	            Vector3f h = snake.getHeadPos();
	            for (int i=foodItems.size()-1;i>=0;i--) {
	                FoodItem fi = foodItems.get(i);
	                float dist = h.distance(fi.geo.getWorldTranslation());
	                float eatRadius = SEG_SPACING*0.75f+(fi.isDebris?0.35f:0.5f);
	                if (dist<eatRadius) {
                            if (!eatenFoodIds.add(fi.id)) continue;
	                    boolean bad=fi.bad, debris=fi.isDebris;
                            int scoreDelta = bad ? 0 : (debris ? 5 : 10);
	                    removeFood(fi);
                            applyFoodEatResult(snakeIndex, bad, debris, scoreDelta, true);
	                    if (!solo) {
                                sendNet("EAT|"+fi.id+"|"+snakeIndex+"|"+(bad?1:0)+"|"+(debris?1:0)+"|"+scoreDelta);
                            }
	                }
	            }
	        }

        private void applyFoodEatResult(int snakeIndex, boolean bad, boolean debris, int scoreDelta, boolean playLocalSound) {
            if (snakeIndex < 0 || snakeIndex >= snakes.size()) return;
            SnakePlayer snake = snakes.get(snakeIndex);
            if (snake.isDead()) return;
            if (bad) {
                snake.shrink();
                if (playLocalSound && snakeIndex == myIndex) playSound(mmmSound);
            } else {
                if (mapSettings.allowGrowth) snake.grow(assetManager);
                snake.addScore(scoreDelta);
                if (playLocalSound && snakeIndex == myIndex) playEatSound();
            }
        }

	        private void killSnake(int idx, String reason) {
            if (idx>=snakes.size()) return;
            SnakePlayer s = snakes.get(idx);
            if (s.isDead()) return;
            List<Vector3f> debrisPositions = s.getSegmentPositions();
            s.triggerDeath(rootNode);
            System.out.println("[GAME] "+s.getName()+" умер: "+reason);
            if (!solo) sendNet("DEAD|"+idx);
            if (solo||isHost) spawnDebrisFromDeath(debrisPositions, idx);
            if (idx==myIndex) {
                spectating=true; spectateTarget=(myIndex+1)%snakes.size();
                for (int i=0;i<snakes.size();i++) if (!snakes.get(i).isDead()) { spectateTarget=i; break; }
                showCenter("Вы погибли! [ЛКМ/ПКМ - смена наблюдения за игроком]", new ColorRGBA(1f,0.3f,0.3f,1f));
            }
            playSound(deathSound); checkWinCondition();
        }

        private void checkWinCondition() {
            if (gameOver) return;
            List<SnakePlayer> alive = new ArrayList<>();
            for (SnakePlayer s : snapshot(snakes)) if (s != null && !s.isDead()) alive.add(s);
            if (alive.size()<=1) {
                gameOver=true; exitTimer=8f;
                String winner = alive.isEmpty() ? "НИКТО" : alive.get(0).getName();
                showCenter(winner+" ПОБЕДИЛ!", new ColorRGBA(1f,0.85f,0.1f,1f));
                if (isHost||solo) sendNet("WIN|"+winner);
            }
        }

        private void updateDashHUD() {
            if (dashCooldownText == null) return;
            float fill = dashCooldown <= 0f ? 1f : 1f - (dashCooldown / DASH_COOLDOWN_MAX);
            if (dashTimer > 0f) fill = 1f;
            if (dashCircleFill != null) {
                float S = uiScale(cam);
                dashCircleFill.setMesh(createRingMesh(30f * S, 42f * S, fill));
            }
            if (dashTimer > 0f) {
                dashCooldownText.setText("АКТИВЕН");
                dashCooldownText.setColor(new ColorRGBA(1f,0.9f,0.1f,1f));
                if (dashShiftText != null) dashShiftText.setColor(new ColorRGBA(1f,0.95f,0.35f,1f));
            } else if (dashCooldown > 0f) {
                int pct = Math.round(fill * 100f);
                dashCooldownText.setText(pct + "%");
                dashCooldownText.setColor(TEXT_DIM);
                if (dashShiftText != null) dashShiftText.setColor(TEXT_DIM);
            } else {
                dashCooldownText.setText("ГОТОВ");
                dashCooldownText.setColor(ACCENT2);
                if (dashShiftText != null) dashShiftText.setColor(TEXT);
            }
        }

        private void updateExternalMap(float tpf) {
            if (mapDef == null || mapCtx == null) return;
            try { mapDef.update(mapCtx, tpf); }
            catch (Exception e) { logSafe("Map.update", e); }

            if (solo || isHost) {
                try { mapDef.hostUpdate(mapCtx, tpf); }
                catch (Exception e) { logSafe("Map.hostUpdate", e); }
            } else {
                try { mapDef.clientUpdate(mapCtx, tpf); }
                catch (Exception e) { logSafe("Map.clientUpdate", e); }
            }
        }

        private void updateActiveCamera(float tpf) {
            if (mapDef != null && mapCtx != null && mapDef.overridesCamera()) {
                try { mapDef.updateCamera(mapCtx, tpf); }
                catch (Exception e) { logSafe("Map.camera", e); updateCamera(); }
            } else {
                updateCamera();
            }
        }

        private void updateCamera() {
            if (freeCameraMode || cam == null || snakes == null || snakes.isEmpty()) return;
            SnakePlayer target;
            if (spectating) target = snakes.get(spectateTarget);
            else if (myIndex<snakes.size()) target = snakes.get(myIndex);
            else return;
            Vector3f head = target.getHeadPos();
            Vector3f dir  = target.getDirection();
            cam.setLocation(head.add(dir.negate().mult(mapSettings.cameraDistance)).add(0,mapSettings.cameraHeight,0));
            cam.lookAt(head.add(dir.mult(mapSettings.cameraLookAhead)), Vector3f.UNIT_Y);
        }

        private void moveClouds(float tpf) {
            if (cloudNode == null) return;
            for (Spatial s : snapshot(cloudNode.getChildren())) {
                if (s == null) continue;
                Vector3f p = s.getLocalTranslation();
                p.x += tpf*0.4f;
                if (p.x>mapHalf*2f) p.x=-mapHalf*2f;
                s.setLocalTranslation(p);
                // Обновляем теневой режим облаков (отбрасывают тени на сцену)
                if (s instanceof Node n) {
                    for (Spatial c : n.getChildren()) {
                        if (c instanceof Geometry gg)
                            gg.setShadowMode(RenderQueue.ShadowMode.Cast);
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        //  ЦИКЛ ДНЯ И НОЧИ
        // ═══════════════════════════════════════════════════════════════════════

        private void initDayNightLighting() {
            // ── 1. Направленный свет (Солнце/Луна) ──
            sunLight = new DirectionalLight();
            sunLight.setDirection(new Vector3f(-0.5f, -1f, -0.5f).normalizeLocal());
            sunLight.setColor(new ColorRGBA(1f, 0.97f, 0.88f, 1f).mult(1.8f));
            rootNode.addLight(sunLight);

            // ── 2. Ambient (фоновый) свет ──
            ambientLight = new AmbientLight();
            ambientLight.setColor(new ColorRGBA(0.28f, 0.30f, 0.35f, 1f));
            rootNode.addLight(ambientLight);

            // ── 3. Купол неба ──
            Sphere skyShape = new Sphere(32, 32, 180f, false, true); // interior — нормали внутрь
            skyDome = new Geometry("SkyDome", skyShape);
            skyMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            skyMat.setColor("Color", new ColorRGBA(0.28f, 0.56f, 1.0f, 1f));
            skyMat.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Front);
            skyDome.setMaterial(skyMat);
            skyDome.setQueueBucket(RenderQueue.Bucket.Sky);
            skyDome.setCullHint(Spatial.CullHint.Never);
            rootNode.attachChild(skyDome);

            // ── 4. Солнце ──
            sunGeom = new Geometry("Sun", new Sphere(20, 20, 3.5f));
            sunMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            sunMat.setColor("Color", new ColorRGBA(1f, 0.97f, 0.65f, 1f));
            sunGeom.setMaterial(sunMat);
            sunGeom.setQueueBucket(RenderQueue.Bucket.Sky);
            sunGeom.setCullHint(Spatial.CullHint.Never);
            rootNode.attachChild(sunGeom);

            // ── 5. Луна ──
            moonGeom = new Geometry("Moon", new Sphere(20, 20, 2.5f));
            Material moonMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            moonMat.setColor("Color", new ColorRGBA(0.92f, 0.92f, 1.0f, 1f));
            moonGeom.setMaterial(moonMat);
            moonGeom.setQueueBucket(RenderQueue.Bucket.Sky);
            moonGeom.setCullHint(Spatial.CullHint.Never);
            rootNode.attachChild(moonGeom);

            // ── 6. Тени (DirectionalLightShadowRenderer) ──
						if (SnakeApp.shadowsEnabled && SnakeApp.dynamicLightsEnabled) {
								gameShadowRenderer = SnakeApp.createShadowRendererForCurrentQuality(assetManager, sunLight);
								if (gameShadowRenderer != null) {
										app.getViewPort().addProcessor(gameShadowRenderer);
								}
						} else {
								gameShadowRenderer = null;
						}

            // ── 7. Post-processing ──
						boolean needFpp = SnakeApp.postProcessingEnabled && (SnakeApp.bloomEnabled || SnakeApp.fogEnabled);
						if (needFpp) {
								gameFpp = new FilterPostProcessor(assetManager);

								// Bloom
								if (SnakeApp.bloomEnabled) {
										gameBloomFilter = new BloomFilter(BloomFilter.GlowMode.Objects);
										gameBloomFilter.setBloomIntensity(2.0f);
										gameBloomFilter.setExposurePower(5.0f);
										gameBloomFilter.setBlurScale(1.5f);
										gameFpp.addFilter(gameBloomFilter);

										// God Rays только вместе с bloom
										gameLightScattering = new LightScatteringFilter(new Vector3f(ORBIT_RADIUS, 30f, -10f));
										gameLightScattering.setLightDensity(0.8f);
										gameLightScattering.setBlurStart(0.6f);
										gameLightScattering.setBlurWidth(0.5f);
										gameLightScattering.setNbSamples(150);
										gameLightScattering.setEnabled(true);
										gameFpp.addFilter(gameLightScattering);
								} else {
										gameBloomFilter = null;
										gameLightScattering = null;
								}

								// Fog
								if (SnakeApp.fogEnabled) {
										gameFogFilter = new FogFilter();
										gameFogFilter.setFogColor(new ColorRGBA(0.65f, 0.70f, 0.82f, 1f));
										gameFogFilter.setFogDensity(0.30f);
										gameFogFilter.setFogDistance(140f);
										gameFpp.addFilter(gameFogFilter);
								} else {
										gameFogFilter = null;
								}

								app.getViewPort().addProcessor(gameFpp);
						} else {
								gameFpp = null;
								gameBloomFilter = null;
								gameFogFilter = null;
								gameLightScattering = null;
						}
				}

				private void updateGridAppearance() {
						if (gridNode == null) return;
						// Вычисляем коэффициент яркости: 1 – день, ~0.15 – ночь
						float t = dayNightTime / DAY_DURATION;
						float brightness;
						if (dayNightTime < DAY_DURATION) {
								brightness = 0.2f + 0.8f * FastMath.sin(t * FastMath.PI);  // плавно по синусоиде дня
						} else {
								brightness = 0.15f;  // ночью очень тускло
						}
						ColorRGBA dimmed = new ColorRGBA(
								baseGridColor.r * brightness,
								baseGridColor.g * brightness,
								baseGridColor.b * brightness,
								baseGridColor.a * 0.5f  // ночью ещё прозрачнее
						);
						for (Geometry g : gridLines) {
								g.getMaterial().setColor("Color", dimmed);
						}
				}

        /** Обновляет цикл дня/ночи каждый кадр */
				private void updateDayNightCycle(float tpf) {
						if (mapDef != null && mapDef.overridesDayNight()) return;
						dayNightTime = (dayNightTime + tpf) % TOTAL_CYCLE;
						if (dayNightTime < DAY_DURATION) {
								updateDayPhase(dayNightTime / DAY_DURATION);
						} else {
								updateNightPhase((dayNightTime - DAY_DURATION) / NIGHT_DURATION);
						}
						if (skyDome != null) skyDome.setLocalTranslation(cam.getLocation());
						updateGridAppearance();
				}

				private void updateDayPhase(float t) {
						// Позиция солнца (без изменений)
						float sunAngle = t * FastMath.PI;
						float sx = FastMath.cos(sunAngle) * ORBIT_RADIUS;
						float sy = FastMath.sin(sunAngle) * ORBIT_RADIUS;
						Vector3f sunWorldPos = new Vector3f(sx, sy, -8f);
						if (sunGeom != null) sunGeom.setLocalTranslation(sunWorldPos);
						if (moonGeom != null) moonGeom.setLocalTranslation(new Vector3f(-sx, -sy - 10f, -8f));
						if (sunLight != null) sunLight.setDirection(sunWorldPos.negate().normalizeLocal());

						// Плавный фактор дня: 0 на рассвете/закате, 1 в полдень
						float rawFactor = FastMath.sin(t * FastMath.PI);
						rawFactor = FastMath.clamp(rawFactor, 0f, 1f);
						float dayFactor = rawFactor * rawFactor * (3f - 2f * rawFactor); // smoothstep
                        updateCloudBrightness(dayFactor);

						// Цвета
						ColorRGBA nightAmbient = new ColorRGBA(0.02f, 0.02f, 0.06f, 1f);
						ColorRGBA dayAmbient   = new ColorRGBA(0.28f, 0.30f, 0.35f, 1f);
						ColorRGBA nightSun     = new ColorRGBA(0.20f, 0.04f, 0.01f, 1f);
						ColorRGBA daySun       = new ColorRGBA(1.0f, 0.97f, 0.88f, 1f);

						// Солнечный свет
						ColorRGBA sunCol = lerpColorDN(nightSun, daySun, dayFactor);
						float sunIntensity = 0.05f + dayFactor * 1.75f;
						if (sunLight != null) sunLight.setColor(sunCol.mult(sunIntensity));

						// Ambient
						if (ambientLight != null) ambientLight.setColor(lerpColorDN(nightAmbient, dayAmbient, dayFactor));

						// Небо
						ColorRGBA nightSky   = new ColorRGBA(0.04f, 0.03f, 0.08f, 1f);
						ColorRGBA sunsetSky  = new ColorRGBA(0.88f, 0.40f, 0.12f, 1f);
						ColorRGBA daySky     = new ColorRGBA(0.22f, 0.50f, 1.00f, 1f);

						ColorRGBA skyColor;
						if (dayFactor < 0.5f) {
								skyColor = lerpColorDN(nightSky, sunsetSky, dayFactor * 2f);
						} else {
								skyColor = lerpColorDN(sunsetSky, daySky, (dayFactor - 0.5f) * 2f);
						}
						applySkyColor(skyColor);

						// Туман
						if (gameFogFilter != null) {
								gameFogFilter.setFogColor(skyColor);
								float fogDens = 0.50f - dayFactor * 0.28f;
								gameFogFilter.setFogDensity(fogDens);
								gameFogFilter.setFogDistance(90f + dayFactor * 70f);
						}

						// Тени
						if (gameShadowRenderer != null) {
								float shadowInt = 0.18f + dayFactor * 0.54f;
								gameShadowRenderer.setShadowIntensity(shadowInt);
						}

						// Bloom
						if (gameBloomFilter != null) {
								gameBloomFilter.setBloomIntensity(0.5f + dayFactor * 1.5f);
						}

						// God Rays
						if (gameLightScattering != null) {
								gameLightScattering.setLightPosition(sunWorldPos);
								boolean enabled = sy > 0f && dayFactor > 0.2f;
								gameLightScattering.setEnabled(enabled);
								if (enabled) {
										float rayPower = (1f - sy / ORBIT_RADIUS) * dayFactor;
										gameLightScattering.setLightDensity(rayPower * 1.2f);
								}
						}

						// Цвет объекта Солнца
						if (sunMat != null) {
								sunMat.setColor("Color", lerpColorDN(new ColorRGBA(1f,0.42f,0.08f,1f),
																										new ColorRGBA(1f,0.97f,0.62f,1f), dayFactor));
						}
				}

				private void updateNightPhase(float t) {
						float moonAngle = t * FastMath.PI;
						float mx = FastMath.cos(moonAngle) * ORBIT_RADIUS;
						float my = FastMath.sin(moonAngle) * ORBIT_RADIUS;
						Vector3f moonWorldPos = new Vector3f(mx, my, -8f);
						if (moonGeom != null) moonGeom.setLocalTranslation(moonWorldPos);
						if (sunGeom  != null) sunGeom.setLocalTranslation(new Vector3f(-mx, -my - 10f, -8f));
						if (sunLight != null) sunLight.setDirection(moonWorldPos.negate().normalizeLocal());

						// Плавный фактор ночи: 0 на закате/рассвете, 1 в полночь
						float rawFactor = FastMath.sin(t * FastMath.PI);
						rawFactor = FastMath.clamp(rawFactor, 0f, 1f);
						float nightFactor = rawFactor * rawFactor * (3f - 2f * rawFactor); // smoothstep
                        updateCloudBrightness(0.10f * (1f - nightFactor));

						// Эталонные значения на границе день/ночь (совпадают с концом дня)
						ColorRGBA nightStartAmbient = new ColorRGBA(0.04f, 0.03f, 0.08f, 1f);
						ColorRGBA midnightAmbient   = new ColorRGBA(0.010f, 0.010f, 0.035f, 1f);
						ColorRGBA nightStartSun     = new ColorRGBA(0.20f, 0.04f, 0.01f, 1f);
						ColorRGBA midnightSun       = new ColorRGBA(0.010f, 0.010f, 0.040f, 1f);

						// Солнечный (лунный) свет
						if (sunLight != null) {
								ColorRGBA sunCol = lerpColorDN(nightStartSun, midnightSun, nightFactor);
								float moonIntensity = 0.05f + nightFactor * 0.07f;
								sunLight.setColor(sunCol.mult(moonIntensity));
						}

						// Ambient
						if (ambientLight != null) {
								ambientLight.setColor(lerpColorDN(nightStartAmbient, midnightAmbient, nightFactor));
						}

						// Небо
						ColorRGBA nightStartSky = new ColorRGBA(0.04f, 0.03f, 0.08f, 1f);
						ColorRGBA midnightSky   = new ColorRGBA(0.010f, 0.010f, 0.035f, 1f);
						ColorRGBA skyColor = lerpColorDN(nightStartSky, midnightSky, nightFactor);
						applySkyColor(skyColor);

						// Туман
						if (gameFogFilter != null) {
								gameFogFilter.setFogColor(skyColor);
								gameFogFilter.setFogDensity(0.50f);
								gameFogFilter.setFogDistance(90f);
						}

						// Тени
						if (gameShadowRenderer != null) {
								gameShadowRenderer.setShadowIntensity(0.18f - nightFactor * 0.05f);
						}

						// Bloom
						if (gameBloomFilter != null) {
								gameBloomFilter.setBloomIntensity(0.5f - nightFactor * 0.3f);
						}

						// God Rays выключены
						if (gameLightScattering != null) gameLightScattering.setEnabled(false);
				}

        private void applySkyColor(ColorRGBA color) {
            if (skyMat != null) skyMat.setColor("Color", color);
            app.getViewPort().setBackgroundColor(color);
        }

        private static float lerpDN(float a, float b, float t) {
            return a + (b - a) * FastMath.clamp(t, 0f, 1f);
        }

        private static ColorRGBA lerpColorDN(ColorRGBA a, ColorRGBA b, float t) {
            t = FastMath.clamp(t, 0f, 1f);
            return new ColorRGBA(
                lerpDN(a.r, b.r, t), lerpDN(a.g, b.g, t),
                lerpDN(a.b, b.b, t), lerpDN(a.a, b.a, t));
        }

        private void updateHUD() {
            if (snakes == null || huds == null) return;
            for (int i=0;i<snakes.size()&&i<huds.size();i++) {
                SnakePlayer s = snakes.get(i);
                if (s == null || huds.get(i) == null) continue;
                String extra = (s == (myIndex<snakes.size()?snakes.get(myIndex):null) && waterSpeedMultiplier<1f) ? " 💧" : "";
                huds.get(i).setText(s.getName()+(s.isDead()?" ☠":"")+"  ★"+s.getScore()+"  L:"+s.getLength()+extra);
            }
        }

				// backToMenu класс GameState
        private void backToMenu() {
            if (rainSound!=null) { rainSound.stop(); detachQuietly(rootNode, rainSound); }
            if (!solo && isHost) sendNet("HOST_LEFT");
            netRunning.set(false);
            closeQuietly(socket);
            for (SnakePlayer sp : snapshot(snakes)) if (sp != null) sp.cleanup(guiNode);
            for (BlackCube bc : snapshot(blackCubes)) {
                if (bc.phy!=null) { bc.phy.setEnabled(false); bulletAppState.getPhysicsSpace().remove(bc.phy); }
            }
            blackCubes.clear();
            // Очистка освещения и пост-обработки
            if (gameShadowRenderer != null) { app.getViewPort().removeProcessor(gameShadowRenderer); gameShadowRenderer = null; }
            if (gameFpp != null) { app.getViewPort().removeProcessor(gameFpp); gameFpp = null; }
            if (sunLight != null) { rootNode.removeLight(sunLight); sunLight = null; }
            if (ambientLight != null) { rootNode.removeLight(ambientLight); ambientLight = null; }
            rootNode.detachAllChildren(); LemurUi.clearPage(guiNode);
            UiGrid.clear(guiNode);
            clearInputMappingsQuietly(inputManager);
            if (inputManager != null) inputManager.setCursorVisible(true);  // восстанавливаем курсор при выходе в меню
            stateManager.detach(bulletAppState); stateManager.detach(this);
            stateManager.attach(new MainMenuState());

						if (sandstormOverlayGeo != null) {
								detachQuietly(guiNode, sandstormOverlayGeo);
								sandstormOverlayGeo = null;
						}
						
						if (!solo && !isHost && socket != null && !socket.isClosed()) {
								sendNet("LEAVE|" + myIndex);   // короткий пакет, хост поймёт
						}
        }

        @Override
        public void cleanup() {
            if (mapDef != null && mapCtx != null) {
                try { mapDef.cleanup(mapCtx); }
                catch (Exception e) { logSafe("Map.cleanup", e); }
                try { mapCtx.cleanupCreatedPhysics(); }
                catch (Exception e) { logSafe("Map.cleanupPhysics", e); }
            }
						cleanupFrozenArena();
            super.cleanup();
            netRunning.set(false);
            closeQuietly(socket);
            if (gameShadowRenderer != null) { app.getViewPort().removeProcessor(gameShadowRenderer); gameShadowRenderer = null; }
            if (gameFpp != null) { app.getViewPort().removeProcessor(gameFpp); gameFpp = null; }
            if (sunLight != null) { rootNode.removeLight(sunLight); sunLight = null; }
            if (ambientLight != null) { rootNode.removeLight(ambientLight); ambientLight = null; }
						clientLastSeen.clear();
						clientIndexMap.clear();
        }

        // ── Вспомогательные классы ────────────────────────────────────────
        static class BlackCube {
            final int id; final Geometry geo; RigidBodyControl phy;
            boolean active = true;
            float glitchTimer=0f, impulseTimer=0.5f, rollSoundTimer=0f;
            boolean chasing = false;
            float patrolAngle=(float)(Math.random()*Math.PI*2), patrolChangeTimer=0f;
            final Map<Integer,Float> hitCooldowns = new HashMap<>();
            int biteCount = 0; // количество укусов — куб растёт и замедляется
            BlackCube(int id, Geometry geo, RigidBodyControl phy) { this.id=id; this.geo=geo; this.phy=phy; }
            void updatePhy(RigidBodyControl newPhy) { this.phy = newPhy; }
						// целевое состояние для гладкой интерполяции (только на клиенте)
						Vector3f targetPos = null;
						Quaternion targetRot = null;
						void interpolateTransform(float tpf) {
								if (targetPos == null || targetRot == null) return;
								float alpha = 1.0f - (float)Math.pow(0.1, tpf / 0.1); // или фиксированный шаг
								Vector3f current = geo.getLocalTranslation();
								current.interpolateLocal(targetPos, alpha);
								geo.setLocalTranslation(current);
								Quaternion currentRot = geo.getLocalRotation();
								currentRot.slerp(targetRot, alpha);
								geo.setLocalRotation(currentRot);
						}
        }

        // Данные кактуса
				static class CactusData {
						final Geometry trunkGeo;
						final RigidBodyControl trunkPhy;
						Geometry armGeo;                   // может быть null
						final List<Geometry> spines = new ArrayList<>(); // колючки на месте
						final float origX, origZ;
						float respawnTimer = 0f;
						boolean queuedForRespawn = false;
						boolean hit = false;
						final List<Geometry> fragments = new ArrayList<>();
						final List<Float> fragmentTimers = new ArrayList<>();
						static final float FRAGMENT_LIFETIME = 8f;

						CactusData(Geometry trunk, RigidBodyControl phy, float x, float z) {
								trunkGeo = trunk;
								trunkPhy = phy;
								origX = x;
								origZ = z;
						}
				}

        static class FoodItem {
            final Geometry geo; final boolean bad; final int id; final boolean isDebris;
            FoodItem(Geometry g, boolean b, int id, boolean debris) { geo=g; bad=b; this.id=id; isDebris=debris; }
        }


				private void spawnSandParticle() {
						float startX = (FastMath.nextRandomFloat() - 0.5f) * mapHalf * 2.2f;
						float startY = 0.2f + FastMath.nextRandomFloat() * 5f;
						float startZ = (FastMath.nextRandomFloat() - 0.5f) * mapHalf * 2.2f;

						float size = 0.03f + FastMath.nextRandomFloat() * 0.09f;
						Geometry geo = new Geometry("Sand", new Box(size, size * 0.3f, size));
						Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
						mat.setColor("Color", new ColorRGBA(0.9f, 0.75f, 0.35f, 0.8f));
						mat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
						geo.setMaterial(mat);
						geo.setLocalTranslation(startX, startY, startZ);
						sandParticleNode.attachChild(geo);

						float speed = 12f + FastMath.nextRandomFloat() * 18f;
						float angle = FastMath.nextRandomFloat() * FastMath.TWO_PI;
						float vx = FastMath.cos(angle) * speed;
						float vz = FastMath.sin(angle) * speed;
						float vy = (FastMath.nextRandomFloat() - 0.5f) * 3f;
						sandParticles.add(new SandParticle(geo, vx, vy, vz, 0.3f + FastMath.nextRandomFloat() * 0.7f));
				}

				static class SandParticle {
						Geometry geo;
						float vx, vy, vz;
						float life;
						SandParticle(Geometry g, float vx, float vy, float vz, float life) {
								this.geo = g; this.vx = vx; this.vy = vy; this.vz = vz; this.life = life;
						}
				}

				static class RainBall {
						final Geometry geo;
						final int id;
						float lifeTimer;
						boolean landed = false;
						boolean sliding = false;
						float landedTimer = 0f;
						Vector3f velocity = new Vector3f();
						float radius;   // <-- добавить

						RainBall(Geometry g, int id, float life, float radius) {
								this.geo = g;
								this.id = id;
								this.lifeTimer = life;
								this.radius = radius;
						}
				}

        static class RainDrop {
            final Geometry geo; float lifeTimer;
            RainDrop(Geometry g, float life) { geo=g; lifeTimer=life; }
        }

        static class WaterPuddle {
            final Geometry geo; final float x, z; float size; final float maxSize;
            WaterPuddle(Geometry g, float x, float z, float maxSize) {
                geo=g; this.x=x; this.z=z; size=0.1f; this.maxSize=maxSize;
            }
        }
    }

    // =========================================================================
    // ИГРОК-ЗМЕЯ
    // =========================================================================
    static class SnakePlayer {
        private final String name;
        private final Material baseMat;
        private final Node parentNode;
        private final AssetManager assetManager;
        private final List<Geometry> segments = new ArrayList<>();
        private final List<Vector3f> segPos   = new ArrayList<>();
        private final com.jme3.bullet.PhysicsSpace physicsSpace;

				private float currentHeadY = 0.3f;
				private final List<Float> tailCurrentY = new ArrayList<>();  // плавные высоты хвоста
				private final GameState gameState;
        private float headingAngle;
        private Vector3f direction;
        private boolean turnLeft, turnRight;
        private boolean movingInput = false;
        private float currentSpeed = 0f;
        private float selfCollisionGraceTimer = 2.0f;
        private static final float SELF_COLLISION_GRACE_AFTER_CORRECTION = 0.45f;
        private static final float NET_SMOOTH_STRONG = 0.55f;
        private static final float NET_SMOOTH_LIGHT = 0.25f;
        private static final float ACCEL = 18f;
        private static final float DECEL = 12f;

				private float accelMult = 1.0f;
				private float decelMult = 1.0f;
				private float turnMult  = 1.0f;
				// Новое: прилипшие куски кактусов
				private final List<CactusAttachment> cactusAttachments = new ArrayList<>();

        private int score = 0;
        private boolean dead = false;
        public static final float SEG_R = 0.27f;

				private boolean iceMode = false;          // активен ли лёд
				private Vector3f iceVelocity = new Vector3f(); // инерция скольжения
				private static final float ICE_FRICTION = 0.03f; // трение о лёд (очень низкое)
				private static final float GRAVITY_ICE = 4.5f;   // ускорение вдоль склона

        private BitmapText nameTag;
        private BitmapText faceText;
        private final Node guiRef;
        private final Camera camRef;

					public SnakePlayer(String name, Vector3f startPos, float startAngle,
														 Material mat, Node parent, AssetManager am,
														 Node guiNode, Camera cam, com.jme3.bullet.PhysicsSpace space,
														 GameState gameState) {
            this.name=name; this.baseMat=mat; this.parentNode=parent;
            this.assetManager=am; this.guiRef=guiNode; this.camRef=cam;
            this.physicsSpace=space; this.headingAngle=startAngle;
            this.direction=calcDir(headingAngle);
						this.gameState = gameState;

            Vector3f back = direction.negate();
            for (int i=0;i<4;i++) addSegment(startPos.add(back.mult(i*0.55f)));

						for (int i = 0; i < segPos.size(); i++) {
								Vector3f pos = segPos.get(i);
								float terrainHeight = gameState.getSurfaceHeight(pos.x, pos.z);
								pos.y = terrainHeight + SEG_R; // SEG_R = 0.27f, можно + 0.3f
								segments.get(i).setLocalTranslation(pos);
						}
						currentHeadY = segPos.get(0).y;

            BitmapFont font = loadFont(am);
            nameTag = new BitmapText(font);
            nameTag.setSize(16); nameTag.setText(name);
            nameTag.setColor(getColorFromMat(mat));
            guiNode.attachChild(nameTag);

            // Лицо больше не GUI-спрайт. Это 3D BitmapText на поверхности головы,
            // поэтому оно не просвечивает через стены/объекты как overlay.
            faceText = new BitmapText(font);
            faceText.setSize(0.20f);
            faceText.setText(":3");
            faceText.setColor(new ColorRGBA(0.01f, 0.01f, 0.012f, 1f));
            parentNode.attachChild(faceText);
        }

        private ColorRGBA getColorFromMat(Material m) {
            Object c = m.getParamValue("Color");
            if (c instanceof ColorRGBA) return (ColorRGBA)c;
            Object d = m.getParamValue("Diffuse");
            return d instanceof ColorRGBA ? (ColorRGBA)d : ColorRGBA.White;
        }

        private void addSegment(Vector3f pos) {
            Geometry g = new Geometry("Seg"+segments.size(), new Sphere(12,12,SEG_R));
            Material sm = baseMat.clone();
            float factor = Math.max(0.5f, 1f - segments.size()*0.04f);
            // Поддержка Unshaded ("Color") и Lighting ("Diffuse")
            Object cv = baseMat.getParamValue("Color");
            if (cv instanceof ColorRGBA c) {
                sm.setColor("Color", new ColorRGBA(c.r*factor, c.g*factor, c.b*factor, 1f));
            } else {
                Object dv = baseMat.getParamValue("Diffuse");
                if (dv instanceof ColorRGBA d) {
                    sm.setColor("Diffuse", new ColorRGBA(d.r*factor, d.g*factor, d.b*factor, 1f));
                    sm.setColor("Ambient", new ColorRGBA(d.r*factor*0.25f, d.g*factor*0.25f, d.b*factor*0.25f, 1f));
                }
            }
            g.setMaterial(sm); g.setLocalTranslation(pos.clone());
						g.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
            parentNode.attachChild(g);

            if (physicsSpace!=null && segments.size()>0) {
                com.jme3.bullet.control.RigidBodyControl phy =
                        new com.jme3.bullet.control.RigidBodyControl(
                                new com.jme3.bullet.collision.shapes.SphereCollisionShape(SEG_R), 1f);
                phy.setKinematic(true); g.addControl(phy); physicsSpace.add(phy);
            }
            segments.add(g); segPos.add(pos.clone());
						tailCurrentY.add(pos.y);
        }

        public void setMoving(boolean v) { this.movingInput=v; }
        public boolean isMoving() { return movingInput; }

        public void setVisible(boolean visible) {
            Spatial.CullHint hint = visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            for (Geometry g : segments) if (g != null) g.setCullHint(hint);
            if (nameTag != null) nameTag.setCullHint(hint);
            if (faceText != null) faceText.setCullHint(hint);
        }

				public void update(float tpf, float maxSpeed, float turnSpeed, float spacing) {
						tpf = safeTpf(tpf);
						if (dead || tpf <= 0f || segments.isEmpty() || segPos.isEmpty()) return;
						if (selfCollisionGraceTimer > 0f) selfCollisionGraceTimer = Math.max(0f, selfCollisionGraceTimer - tpf);
						if (movingInput) currentSpeed = Math.min(maxSpeed, currentSpeed + ACCEL * accelMult * tpf);
						else             currentSpeed = Math.max(0f,         currentSpeed - DECEL * decelMult * tpf);

						if (iceMode) {
								// === ЛЕДЯНОЙ РЕЖИМ ===
								// 1. Инерция сохраняется: скорость отдельно от ввода
								Vector3f headPos = segPos.get(0);

								// Вычисляем наклон поверхности под головой
								float slopeX = gameState.getSurfaceHeight(headPos.x + 0.5f, headPos.z)
														 - gameState.getSurfaceHeight(headPos.x - 0.5f, headPos.z);
								float slopeZ = gameState.getSurfaceHeight(headPos.x, headPos.z + 0.5f)
														 - gameState.getSurfaceHeight(headPos.x, headPos.z - 0.5f);
								Vector3f gravitySlope = new Vector3f(-slopeX, 0f, -slopeZ).normalizeLocal()
																				 .multLocal(GRAVITY_ICE * tpf);

								// Влияние вперёд (направление взгляда) добавляет тягу
								if (movingInput) {
										iceVelocity.addLocal(direction.mult(ACCEL * tpf));
								}

								// Гравитационное ускорение вдоль склона
								iceVelocity.addLocal(gravitySlope);

								// Трение (очень слабое): замедляем, но только если скорость не нулевая
								float friction = ICE_FRICTION * tpf;
								// синхронизируем направление взгляда с фактическим движением
								if (iceVelocity.lengthSquared() > 0.01f) {
										headingAngle = (float) Math.atan2(iceVelocity.x, iceVelocity.z);
										direction = calcDir(headingAngle);
								} else {
										iceVelocity.set(0,0,0);
								}

								// Ограничение максимальной скорости (чтобы не разгонялась бесконечно)
								float iceMaxSpeed = GameState.SPEED * 1.8f;
								if (iceVelocity.length() > iceMaxSpeed) {
										iceVelocity.normalizeLocal().multLocal(iceMaxSpeed);
								}

								// Применяем поворот плавно (с инерцией): 'direction' плавно доворачиваем к желаемому
								if (turnLeft || turnRight) {
										float turnAngle = (turnLeft ? GameState.TURN_SPEED : -GameState.TURN_SPEED) * tpf * 0.6f; // очень медленный поворот
										Quaternion rot = new Quaternion().fromAngleAxis(turnAngle, Vector3f.UNIT_Y);
										iceVelocity = rot.mult(iceVelocity);
										headingAngle += turnAngle;
								}

								// Перемещаем голову согласно iceVelocity
								Vector3f newHead = headPos.add(iceVelocity.mult(tpf));
								float targetY = gameState.getSurfaceHeight(newHead.x, newHead.z) + SEG_R + 0.1f;
								currentHeadY += (targetY - currentHeadY) * Math.min(1f, 8f * tpf); // плавное изменение высоты
								newHead.y = currentHeadY;
								segPos.get(0).set(newHead);
								segments.get(0).setLocalTranslation(newHead);
								tailCurrentY.set(0, currentHeadY);

								// Обновляем хвост как обычно (с прилипанием к поверхности)
								for (int i = 1; i < segPos.size(); i++) {
										Vector3f prev = segPos.get(i - 1), cur = segPos.get(i);
										Vector3f diff = prev.subtract(cur);
										float dist = diff.length();
										if (dist > GameState.SEG_SPACING) {
												cur.addLocal(diff.normalize().mult(dist - GameState.SEG_SPACING));
												float curTargetY = gameState.getSurfaceHeight(cur.x, cur.z) + SEG_R;
												float curCurrentY = tailCurrentY.get(i);
												curCurrentY += (curTargetY - curCurrentY) * Math.min(1f, 8f * tpf);
												cur.y = curCurrentY;
												tailCurrentY.set(i, curCurrentY);
												segments.get(i).setLocalTranslation(cur);
										}
								}

								updateCactusAttachments(tpf);
								return; // выходим, не выполняя старую логику движения
						}

						float effectiveTurnSpeed = turnSpeed * (currentSpeed / maxSpeed);
						if (turnLeft)  headingAngle += effectiveTurnSpeed * tpf;
						if (turnRight) headingAngle -= effectiveTurnSpeed * tpf;
						direction = calcDir(headingAngle);

						if (currentSpeed < 0.01f) return;

						// === Голова с плавной высотой ===
						Vector3f newHead = segPos.get(0).add(direction.mult(currentSpeed * tpf));
						float targetY = gameState.getSurfaceHeight(newHead.x, newHead.z) + SEG_R + 0.1f;
						float maxDy = 30f * tpf;                           // макс. вертикальная скорость
						if (Math.abs(targetY - currentHeadY) < maxDy) {
								currentHeadY = targetY;
						} else {
								currentHeadY += Math.signum(targetY - currentHeadY) * maxDy;
						}
						newHead.y = currentHeadY;
						segPos.get(0).set(newHead);
						segments.get(0).setLocalTranslation(newHead);
						tailCurrentY.set(0, currentHeadY);

						// === Хвост с плавной высотой ===
						for (int i = 1; i < segPos.size(); i++) {
								Vector3f prev = segPos.get(i - 1), cur = segPos.get(i);
								Vector3f diff = prev.subtract(cur);
								float dist = diff.length();
								if (dist > spacing) {
										cur.addLocal(diff.normalize().mult(dist - spacing));
										// плавная высота для этого сегмента
										float curTargetY = gameState.getSurfaceHeight(cur.x, cur.z) + SEG_R;
										float curCurrentY = tailCurrentY.get(i);
										float curMaxDy = 10f * tpf;
										if (Math.abs(curTargetY - curCurrentY) < curMaxDy) {
												curCurrentY = curTargetY;
										} else {
												curCurrentY += Math.signum(curTargetY - curCurrentY) * curMaxDy;
										}
										cur.y = curCurrentY;
										tailCurrentY.set(i, curCurrentY);
										segments.get(i).setLocalTranslation(cur);
								}
						}

						updateCactusAttachments(tpf);
				}

				// В SnakePlayer.update добавить расчёт pitch
				private float calculatePitch(Vector3f headPos) {
						float dx = direction.x * 0.5f;
						float dz = direction.z * 0.5f;
						float yAhead = gameState.getSurfaceHeight(headPos.x + dx, headPos.z + dz);
						float dy = yAhead - headPos.y;
						return (float) Math.atan2(dy, 0.5f) * 0.5f;
				}

				public void setPhysicsMult(float accel, float decel, float turn) {
						this.accelMult = accel;
						this.decelMult = decel;
						this.turnMult  = turn;
				}

				public void enableIceMode(boolean enable) {
						this.iceMode = enable;
						if (enable) {
								// начинаем скользить с текущей скоростью и направлением
								iceVelocity.set(direction.mult(currentSpeed));
						} else {
								iceVelocity.set(0,0,0);
						}
				}        public void updateNameTag(Camera cam) {
            if (dead || segPos.isEmpty()) return;
            Vector3f head = segPos.get(0);
            Vector3f nameWorldPos = head.add(0,1.4f,0);
            Vector3f nameScreen = cam.getScreenCoordinates(nameWorldPos);
            boolean visible = !(nameScreen.z < 0 || nameScreen.z > 1);
            if (!visible) {
                if (nameTag != null) nameTag.setLocalTranslation(-9999,0,0);
                if (faceText != null) faceText.setCullHint(Spatial.CullHint.Always);
                return;
            }
            if (nameTag != null) {
                if (SnakeApp.nameTagsEnabled) nameTag.setLocalTranslation(nameScreen.x-nameTag.getLineWidth()/2, nameScreen.y, 1);
                else nameTag.setLocalTranslation(-9999,0,0);
            }
            if (faceText != null) {
                // 3D-смайлик лежит на передней части первого шарика.
                // Так он является частью мира, а не экранным overlay, и нормально
                // скрывается стенами/объектами по depth-test.
                Vector3f faceDir = (direction == null ? Vector3f.UNIT_Z : direction.clone());
                faceDir.y = 0f;
                if (faceDir.lengthSquared() < 0.0001f) faceDir.set(Vector3f.UNIT_Z);
                faceDir.normalizeLocal();

                Vector3f up = Vector3f.UNIT_Y.clone();
                Vector3f right = up.cross(faceDir);
                if (right.lengthSquared() < 0.0001f) right.set(Vector3f.UNIT_X);
                right.normalizeLocal();
                up = faceDir.cross(right).normalizeLocal();

                Quaternion rot = new Quaternion();
                rot.fromAxes(right, up, faceDir);
                faceText.setLocalRotation(rot);

                float tw = Math.max(0.01f, faceText.getLineWidth());
                float th = Math.max(0.01f, faceText.getLineHeight());
                Vector3f facePos = head.add(faceDir.mult(SEG_R * 1.06f))
                        .add(up.mult(SEG_R * 0.20f))
                        .subtract(right.mult(tw * 0.50f))
                        .subtract(up.mult(th * 0.45f));
                faceText.setLocalTranslation(facePos);
                faceText.setCullHint(Spatial.CullHint.Inherit);
            }
        }


        public void applyNetState(float x, float y, float z, float angle, int sc, int remoteLen, boolean isDead) {
            if (!segPos.isEmpty()) {
                Vector3f netHead = new Vector3f(x, y, z);
                Vector3f localHead = segPos.get(0);
                float delta = localHead.distance(netHead);

                float angleAlpha = delta > 1.6f ? NET_SMOOTH_STRONG : NET_SMOOTH_LIGHT;
                headingAngle = FastMath.interpolateLinear(angleAlpha, headingAngle, angle);
                direction = calcDir(headingAngle);

                float posAlpha = delta > 1.6f ? NET_SMOOTH_STRONG : NET_SMOOTH_LIGHT;
                Vector3f correctedHead = localHead.interpolateLocal(netHead, posAlpha);
                segPos.get(0).set(correctedHead);
                segments.get(0).setLocalTranslation(correctedHead);

                if (delta > 1.0f) selfCollisionGraceTimer = SELF_COLLISION_GRACE_AFTER_CORRECTION;

                while (segments.size() < remoteLen) grow(assetManager);
                while (segments.size() > remoteLen && segments.size() > 2) shrink();

                float spacing = 0.55f;
                for (int i = 1; i < segPos.size(); i++) {
                    Vector3f prev = segPos.get(i - 1), cur = segPos.get(i);
                    Vector3f diff = prev.subtract(cur);
                    float dist = diff.length();
                    if (dist > spacing) {
                        cur.addLocal(diff.normalize().mult(dist - spacing));
                        segments.get(i).setLocalTranslation(cur);
                    }
                }
            }
            score = sc;
            if (isDead && !dead) triggerDeathRemote(parentNode.getParent());
        }

				public int getClosestSegmentIndex(Vector3f worldPos) {
						if (segPos.isEmpty()) return -1;
						int closest = 0;
						float minDist = worldPos.distance(segPos.get(0));
						for (int i = 1; i < segPos.size(); i++) {
								float d = worldPos.distance(segPos.get(i));
								if (d < minDist) {
										minDist = d;
										closest = i;
								}
						}
						return closest;
				}

				// Приклеить фрагмент кактуса к змее
				public void attachCactusFragment(Node fragNode, Vector3f worldPos) {
						RigidBodyControl phy = fragNode.getControl(RigidBodyControl.class);
						if (phy != null && physicsSpace != null) {
								physicsSpace.remove(phy);
								fragNode.removeControl(phy);
						}
						fragNode.removeFromParent();
						parentNode.attachChild(fragNode);

						int segIdx = getClosestSegmentIndex(worldPos);
						if (segIdx < 0) segIdx = 0;
						Vector3f segCenter = segPos.get(segIdx);

						// Локальная система координат сегмента
						Vector3f bodyDir;
						if (segIdx < segPos.size() - 1) {
								bodyDir = segPos.get(segIdx + 1).subtract(segPos.get(segIdx)).normalizeLocal();
						} else if (segIdx > 0) {
								bodyDir = segPos.get(segIdx).subtract(segPos.get(segIdx - 1)).normalizeLocal();
						} else {
								bodyDir = direction;
						}
						Vector3f right = new Vector3f(bodyDir.z, 0, -bodyDir.x).normalizeLocal();

						// Проецируем точку касания на локальные оси
						Vector3f toFrag = worldPos.subtract(segCenter);
						float along = toFrag.dot(bodyDir);
						float side  = toFrag.dot(right);
						float up    = toFrag.y * 0.15f;

						// Радиус сегмента + небольшой контактный зазор
						float placementDist = SEG_R + 0.12f;

						// Нормализуем направление от центра сегмента к точке контакта
						Vector3f dirToFrag = toFrag.clone();
						float len = dirToFrag.length();
						if (len < 0.001f) {
								dirToFrag = Vector3f.UNIT_Y;
						} else {
								dirToFrag.divideLocal(len);
						}

						// Вычисляем смещение строго по направлению от центра на нужное расстояние
						Vector3f offset = dirToFrag.mult(placementDist);
						// Перепроецируем на локальные оси? Нет, мы уже используем готовый offset.
						// Но чтобы локальные координаты (along, side, up) соответствовали этому offset,
						// пересчитаем их заново относительно локальных осей:
						// (этот шаг избыточен, можно оставить как есть, но тогда старые along/side не будут
						// точно совпадать с новым offset – однако это не критично, так как фрагмент ставится
						// по offset, а along/side теперь не используются? Нет, они используются в update.
						// Значит их нужно обновить.)
						along = offset.dot(bodyDir);
						side  = offset.dot(right);
						up    = offset.y;

						// ---- НОВОЕ: фиксируем локальное вращение ----
						// Берём текущую мировую ориентацию фрагмента
						Quaternion worldRot = fragNode.getWorldRotation().clone();
						// Получаем мировую ориентацию сегмента (ось Z по bodyDir, ось X по right, ось Y вверх)
						Quaternion segWorldRot = new Quaternion();
						segWorldRot.lookAt(bodyDir, Vector3f.UNIT_Y);
						// Вычисляем относительное вращение: localRot = inv(segWorldRot) * worldRot
						Quaternion localRot = segWorldRot.inverse().mult(worldRot);
						// ------------------------------------------

						cactusAttachments.add(new CactusAttachment(fragNode, segIdx, along, side, up,
										localRot, 8.0f));
				}

				// обновление прилипших фрагментов (вызывать в update)
				private void updateCactusAttachments(float tpf) {
						for (int i = cactusAttachments.size() - 1; i >= 0; i--) {
								CactusAttachment att = cactusAttachments.get(i);
								att.life -= tpf;
								if (att.life <= 0f || att.segmentIndex >= segPos.size()) {
										att.node.removeFromParent();
										cactusAttachments.remove(i);
										continue;
								}

								Vector3f segCenter = segPos.get(att.segmentIndex);
								Vector3f bodyDir;
								if (att.segmentIndex < segPos.size() - 1) {
										bodyDir = segPos.get(att.segmentIndex + 1).subtract(segCenter).normalizeLocal();
								} else if (att.segmentIndex > 0) {
										bodyDir = segCenter.subtract(segPos.get(att.segmentIndex - 1)).normalizeLocal();
								} else {
										bodyDir = direction;
								}
								if (bodyDir.lengthSquared() < 0.0001f) continue;
								Vector3f right = new Vector3f(bodyDir.z, 0, -bodyDir.x).normalizeLocal();

								// Позиция
								Vector3f worldOffset = bodyDir.mult(att.alongBody)
												.addLocal(right.mult(att.sideBody))
												.addLocal(0, att.up, 0);
								att.node.setLocalTranslation(segCenter.add(worldOffset));

								// Вращение: восстанавливаем мировое вращение = segWorldRot * localRot
								Quaternion segWorldRot = new Quaternion();
								segWorldRot.lookAt(bodyDir, Vector3f.UNIT_Y);
								att.node.setLocalRotation(segWorldRot.mult(att.localRotation));
						}
				}

				// очистка прилипших фрагментов (при смерти/выходе)
				private void cleanupCactusAttachments() {
						for (CactusAttachment att : cactusAttachments) {
								att.node.removeFromParent();
						}
						cactusAttachments.clear();
				}

				// Внутри SnakePlayer, класс CactusAttachment
				private static class CactusAttachment {
						Node node;
						int segmentIndex;
						float alongBody, sideBody, up;
						Quaternion localRotation;          // новое поле
						float life;

						// Обновлённый конструктор — 7 параметров
						CactusAttachment(Node node, int idx, float along, float side, float up,
														 Quaternion localRot, float life) {
								this.node = node;
								this.segmentIndex = idx;
								this.alongBody = along;
								this.sideBody = side;
								this.up = up;
								this.localRotation = localRot;
								this.life = life;
						}
				}

        public void triggerDeath(Node scene) {
            if (dead) return; dead=true;
            if (nameTag!=null) { detachQuietly(guiRef, nameTag); nameTag=null; }
            if (faceText!=null) { faceText.removeFromParent(); faceText=null; }
            for (Geometry seg:segments) {
                com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
                if (phy!=null&&physicsSpace!=null) { phy.setEnabled(false); physicsSpace.remove(phy); }
                detachQuietly(parentNode, seg);
            }
            segments.clear(); segPos.clear();
        }

        public void triggerDeathRemote(Node scene) { triggerDeath(scene); }

        public List<Vector3f> getSegmentPositions() {
            List<Vector3f> result = new ArrayList<>();
            for (Vector3f p:segPos) result.add(p.clone());
            return result;
        }

        public void grow(AssetManager am) {
            if (segPos.isEmpty()) return;
            addSegment(segPos.get(segPos.size()-1).clone());
        }

        public void shrink() {
            if (segments.size()<=2) return;
            int idx=segments.size()-1;
            Geometry seg=segments.get(idx);
            com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
            if (phy!=null&&physicsSpace!=null) { phy.setEnabled(false); physicsSpace.remove(phy); }
            detachQuietly(parentNode, seg); segments.remove(idx); segPos.remove(idx);
        }


				public void removeSegmentsAtWorldPos(Vector3f worldPos, float radius) {
						if (dead || segments.size() <= 2) return;
						float r2 = radius * radius;
						for (int i = segments.size() - 1; i >= 1; i--) {
								Vector3f seg = segPos.get(i);
								float dx = seg.x - worldPos.x;
								float dz = seg.z - worldPos.z;
								if (dx * dx + dz * dz < r2) {   // ← теперь только 2D
										Geometry geom = segments.get(i);
										RigidBodyControl phy = geom.getControl(RigidBodyControl.class);
										if (phy != null && physicsSpace != null) {
												phy.setEnabled(false);
												physicsSpace.remove(phy);
										}
										detachQuietly(parentNode, geom);
										segments.remove(i);
										segPos.remove(i);
								}
						}
				}

        public boolean selfCollides(float minDist) {
            if (selfCollisionGraceTimer > 0f) return false;
            if (segPos == null || segPos.size()<4) return false;
            Vector3f h=segPos.get(0);
            if (h == null) return false;
            for (int i=3;i<segPos.size();i++) {
                Vector3f p = segPos.get(i);
                if (p != null && h.distance(p)<minDist) return true;
            }
            return false;
        }

        public boolean bodyContains(Vector3f point, float radius) {
            if (point == null || segPos == null) return false;
            for (int i=1;i<segPos.size();i++) {
                Vector3f p = segPos.get(i);
                if (p != null && point.distance(p)<radius) return true;
            }
            return false;
        }

        public void cleanup(Node gui) {
            cleanupCactusAttachments(); // <-- сначала удаляем прилипшие фрагменты
            if (nameTag!=null) { detachQuietly(gui, nameTag); nameTag=null; }
            if (faceText!=null) { faceText.removeFromParent(); faceText=null; }
            if (!dead&&physicsSpace!=null) {
                for (Geometry seg : snapshot(segments)) {
                    if (seg == null) continue;
                    com.jme3.bullet.control.RigidBodyControl phy = seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
                    if (phy != null) { phy.setEnabled(false); physicsSpace.remove(phy); }
                }
            }
        }

        private static Vector3f calcDir(float angle) { return new Vector3f(FastMath.sin(angle),0,FastMath.cos(angle)); }

        public void setTurnLeft(boolean v)  { turnLeft=v; }
        public void setTurnRight(boolean v) { turnRight=v; }
        public boolean isTurnLeft()  { return turnLeft; }
        public boolean isTurnRight() { return turnRight; }
        public Vector3f getHeadPos() { return segPos == null || segPos.isEmpty() || segPos.get(0) == null ? Vector3f.ZERO : segPos.get(0); }
        public Vector3f getDirection() { return direction != null ? direction : Vector3f.UNIT_Z; }
        public float getHeadingAngle() { return headingAngle; }
        public String getName()  { return name; }
        public int getScore()    { return score; }
        public int getLength()   { return segments == null ? 0 : segments.size(); }
        public boolean isDead()  { return dead; }
        public void addScore(int v) { score+=v; }
    }
}