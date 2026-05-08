package ru.sonia.turbosnake;

import com.jme3.app.SimpleApplication;
import com.jme3.asset.AssetManager;
import com.jme3.asset.plugins.FileLocator;
import com.jme3.font.BitmapFont;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.system.AppSettings;

import java.util.*;

/**
 * SnakeApp — главный класс приложения (точка входа).
 *
 * Отвечает за:
 *  - Запуск jMonkeyEngine (метод main)
 *  - Первичную инициализацию движка (simpleInitApp)
 *  - Хранение всех ГЛОБАЛЬНЫХ настроек (громкость, карта, цвет змейки, ник)
 *  - Константы цветовой палитры UI (ACCENT, BG, BTN_NORMAL и т.д.)
 *  - Статические утилиты (unshaded, litMat, loadFont, HSV-конверсия…)
 *  - Сохранение/загрузка настроек в %APPDATA%/SSnake3D/settings.properties
 *
 * Для перехода между экранами используется паттерн AppState:
 *   stateManager.attach(new MainMenuState()) — главное меню
 *   stateManager.attach(new LobbyState(...)) — лобби
 *   stateManager.attach(new GameState(...))  — игровой процесс
 */
public class TurboSnake extends SimpleApplication {

    public static final int BROADCAST_PORT = 45678;
    public static final int GAME_PORT      = 45679;

    static float effectVolume = 1.0f;
    static float musicVolume  = 0.5f;
    static int selectedMap = 0;
    static ColorRGBA selectedSnakeColor = new ColorRGBA(0.15f, 0.9f, 0.3f, 1f);
    public static String savedNickname = getSystemUsername();
    // Настройки графики
    static boolean shadowsEnabled   = true;
    static boolean particlesEnabled = true;
    static boolean fogEnabled       = true;
    static boolean bloomEnabled     = true;

    // =========================================================================
    // НАСТРОЙКИ (сохранение/загрузка в %appdata%/SSnake3D/)
    // =========================================================================
    static java.io.File getSettingsFile() {
        String appdata = System.getenv("APPDATA");
        if (appdata == null || appdata.isBlank()) appdata = System.getProperty("user.home");
        java.io.File dir = new java.io.File(appdata, "SSnake3D");
        if (!dir.exists()) dir.mkdirs();
        return new java.io.File(dir, "settings.properties");
    }

    static void loadSettings() {
        java.io.File f = getSettingsFile();
        if (!f.exists()) return;
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.util.Properties p = new java.util.Properties();
            p.load(in);
            effectVolume       = Float.parseFloat(p.getProperty("effectVolume", "1.0"));
            musicVolume        = Float.parseFloat(p.getProperty("musicVolume",  "0.5"));
            savedNickname      = p.getProperty("nickname", getSystemUsername());
            shadowsEnabled     = Boolean.parseBoolean(p.getProperty("shadowsEnabled",   "true"));
            particlesEnabled   = Boolean.parseBoolean(p.getProperty("particlesEnabled", "true"));
            fogEnabled         = Boolean.parseBoolean(p.getProperty("fogEnabled",       "true"));
            bloomEnabled       = Boolean.parseBoolean(p.getProperty("bloomEnabled",     "true"));
        } catch (Exception e) { System.out.println("[Settings] Load error: " + e.getMessage()); }
    }

    static void saveSettings(String nickname) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(getSettingsFile())) {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("effectVolume",  String.valueOf(effectVolume));
            p.setProperty("musicVolume",   String.valueOf(musicVolume));
            p.setProperty("nickname",      nickname != null ? nickname : savedNickname);
            p.setProperty("shadowsEnabled",   String.valueOf(shadowsEnabled));
            p.setProperty("particlesEnabled", String.valueOf(particlesEnabled));
            p.setProperty("fogEnabled",       String.valueOf(fogEnabled));
            p.setProperty("bloomEnabled",     String.valueOf(bloomEnabled));
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


    // ====== ТОЧКА ВХОДА И ИНИЦИАЛИЗАЦИЯ (см. main/simpleInitApp) ======
		public static void main(String[] args) {

				TurboSnake app = new TurboSnake();

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
        loadSettings();
        if (inputManager.hasMapping(SimpleApplication.INPUT_MAPPING_EXIT)) {
            inputManager.deleteMapping(SimpleApplication.INPUT_MAPPING_EXIT);
        }
        flyCam.setEnabled(false);
        setDisplayStatView(false);
        setDisplayFps(false);
        assetManager.registerLocator(".", FileLocator.class);
        stateManager.attach(new MainMenuState());
    }

    // ---------- утилиты ----------
    static Material unshaded(AssetManager am, ColorRGBA c) {
        Material m = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        m.setColor("Color", c);
        return m;
    }

    /** Создаёт Lighting.j3md материал, реагирующий на день/ночь. */
    static Material litMat(AssetManager am, ColorRGBA diffuse) {
        Material m = new Material(am, "Common/MatDefs/Light/Lighting.j3md");
        m.setBoolean("UseMaterialColors", true);
        m.setColor("Diffuse",  diffuse);
        m.setColor("Ambient",  diffuse.mult(0.25f));
        m.setColor("Specular", new ColorRGBA(0.06f, 0.06f, 0.06f, 1f));
        m.setFloat("Shininess", 6f);
        return m;
    }

    static BitmapFont loadFont(AssetManager am) {
        try { return am.loadFont("Fonts/bitmap.fnt"); }
        catch (Exception e) { return am.loadFont("Interface/Fonts/Default.fnt"); }
    }

    public static String getSystemUsername() {
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


}
