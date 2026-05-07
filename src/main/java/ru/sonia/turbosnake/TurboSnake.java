package ru.sonia.turbosnake;

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
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.ui.Picture;
import ru.sonia.turbosnake.handlers.BlackCube;
import ru.sonia.turbosnake.handlers.CactusData;
import ru.sonia.turbosnake.handlers.FoodItem;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class TurboSnake extends SimpleApplication {

    // Hello from FTD

    public static final int BROADCAST_PORT = 45678;
    public static final int GAME_PORT      = 45679;

    static float effectVolume = 1.0f;
    static float musicVolume  = 0.5f;
    static int selectedMap = 0;
    static ColorRGBA selectedSnakeColor = new ColorRGBA(0.15f, 0.9f, 0.3f, 1f);
    static String savedNickname = getSystemUsername();
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

    // Глобальный менеджер фоновой музыки — один трек на всё время игры
    static class MusicManager {
        private static AudioNode currentBgMusic = null;
        private static String currentTrack = "";

        static void play(AssetManager am, Node root, String track, float volume) {
            // Если уже играет этот трек — просто обновляем громкость
            if (track.equals(currentTrack) && currentBgMusic != null
                    && currentBgMusic.getStatus() == AudioSource.Status.Playing) {
                currentBgMusic.setVolume(volume);
                return;
            }
            stop(root);
            try {
                currentBgMusic = new AudioNode(am, track, DataType.Stream);
                currentBgMusic.setPositional(false);
                currentBgMusic.setLooping(true);
                currentBgMusic.setVolume(volume);
                root.attachChild(currentBgMusic);
                currentBgMusic.play();
                currentTrack = track;
            } catch (Exception e) {
                System.out.println("[Music] " + track + " not found");
            }
        }

        static void setVolume(float v) {
            if (currentBgMusic != null) currentBgMusic.setVolume(v);
        }

        static void stop(Node root) {
            if (currentBgMusic != null) {
                currentBgMusic.stop();
                try { root.detachChild(currentBgMusic); } catch (Exception ignore) {}
                currentBgMusic = null;
                currentTrack = "";
            }
        }
    }

    public static void main(String[] args) {

        TurboSnake app = new TurboSnake();

        AppSettings s = new AppSettings(true);

        s.setTitle("Turbo Snake 3D");

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

    // ---------- кнопка ----------
    static class MenuButton {
        private final Geometry bgGeo, accentGeo, borderGeo;
        private final BitmapText label;
        private final float x, y, w, h;
        private boolean hovered = false, pressed = false;
        private ColorRGBA accentColor;
        private ColorRGBA bgNormal, bgHover, bgPressed;

        MenuButton(String text, float cx, float cy, float w, float h,
                   ColorRGBA bgNormal, ColorRGBA bgHover, ColorRGBA bgPressed,
                   ColorRGBA textColor, AssetManager am, Node guiNode, float z) {
            this.w = w; this.h = h;
            this.x = cx - w/2f; this.y = cy - h/2f;
            this.accentColor = textColor;
            this.bgNormal = bgNormal;
            this.bgHover = bgHover;
            this.bgPressed = bgPressed;

            float tiltAmount = 18f;  // уменьшен скос для более чистого вида

            // Граница (чуть шире фона) — создаёт эффект рамки с акцентным цветом
            Mesh borderMesh = createSlantedQuad(w + 4f, h + 4f, tiltAmount);
            borderGeo = new Geometry("BtnBorder_" + text, borderMesh);
            Material borderMat = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            ColorRGBA borderCol = new ColorRGBA(textColor.r*0.5f, textColor.g*0.5f, textColor.b*0.5f, 0.6f);
            borderMat.setColor("Color", borderCol);
            borderMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            borderMat.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
            borderGeo.setMaterial(borderMat);
            borderGeo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
            borderGeo.setLocalTranslation(cx, cy, z - 0.1f);
            guiNode.attachChild(borderGeo);

            // Основной фон
            Mesh slantedMesh = createSlantedQuad(w, h, tiltAmount);
            bgGeo = new Geometry("BtnBg_" + text, slantedMesh);
            Material mat = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", bgNormal);
            mat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            mat.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
            bgGeo.setMaterial(mat);
            bgGeo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
            bgGeo.setLocalTranslation(cx, cy, z);
            guiNode.attachChild(bgGeo);

            // Акцентная вертикальная полоса слева (толще и ярче)
            Mesh accentMesh = createSlantedQuad(8f, h - 4f, tiltAmount);
            accentGeo = new Geometry("BtnAccent_" + text, accentMesh);
            Material am2 = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            am2.setColor("Color", new ColorRGBA(textColor.r, textColor.g, textColor.b, 0.9f));
            am2.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
            accentGeo.setMaterial(am2);
            accentGeo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
            accentGeo.setLocalTranslation(cx - w/2f + 12f, cy, z + 0.3f);
            guiNode.attachChild(accentGeo);

            BitmapFont font = loadFont(am);
            label = new BitmapText(font);
            label.setSize(26);
            label.setText(text);
            label.setColor(textColor);
            guiNode.attachChild(label);
            centerLabel(cx, cy, z);
        }

        private void centerLabel(float cx, float cy, float z) {

            float textX = cx - label.getLineWidth() / 2f;

            float textY = cy + label.getLineHeight() / 3f;

            label.setLocalTranslation(
                    textX,
                    textY,
                    z + 1f
            );
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
            return hit;
        }
        public boolean isHit(float mx, float my) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }

        private void refreshColor() {
            ColorRGBA c = pressed ? bgPressed : (hovered ? bgHover : bgNormal);
            bgGeo.getMaterial().setColor("Color", c);
            // Граница ярче при наведении
            float bf = hovered ? 1.0f : 0.5f;
            float ba = hovered ? 0.95f : 0.55f;
            borderGeo.getMaterial().setColor("Color",
                    new ColorRGBA(accentColor.r*bf, accentColor.g*bf, accentColor.b*bf, ba));
            // Акцент
            float af = hovered ? 1f : 0.75f;
            accentGeo.getMaterial().setColor("Color",
                    new ColorRGBA(accentColor.r * af, accentColor.g * af, accentColor.b * af, hovered ? 1f : 0.85f));
            // Текст светлее при наведении
            label.setColor(hovered ? new ColorRGBA(
                    Math.min(1f, accentColor.r * 1.2f),
                    Math.min(1f, accentColor.g * 1.2f),
                    Math.min(1f, accentColor.b * 1.2f), 1f) : accentColor);
        }

        void detach(Node guiNode) {
            guiNode.detachChild(borderGeo);
            guiNode.detachChild(bgGeo);
            guiNode.detachChild(accentGeo);
            guiNode.detachChild(label);
        }

        void setText(String t) { label.setText(t); }
        void setAccentColor(ColorRGBA c) { accentColor = c; label.setColor(c); refreshColor(); }
    }

    // ---------- ползунок громкости ----------
    static class VolumeSlider {
        private static final float TRACK_H  = 10f, THUMB_W = 18f, THUMB_H = 28f;
        private final Geometry track, fill, thumb;
        private final float left, cy, trackW, z;
        private float value;
        private boolean hovered = false;

        VolumeSlider(float cx, float cy, float w, float initVal, AssetManager am, Node parent, float z) {
            this.trackW = w; this.left = cx - w/2f; this.cy = cy; this.z = z;
            this.value = Math.max(0f, Math.min(1f, initVal));

            Box trackBox = new Box(w/2f, TRACK_H/2f, 0.3f);
            track = new Geometry("VSlTrack", trackBox);
            Material tm = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            tm.setColor("Color", new ColorRGBA(0.10f, 0.18f, 0.38f, 1f)); track.setMaterial(tm);
            track.setLocalTranslation(cx, cy, z); parent.attachChild(track);

            Box fillBox = new Box(0.5f, TRACK_H/2f-1f, 0.4f);
            fill = new Geometry("VSlFill", fillBox);
            Material fm = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            fm.setColor("Color", ACCENT2); fill.setMaterial(fm); parent.attachChild(fill);

            Box thumbBox = new Box(THUMB_W/2f, THUMB_H/2f, 0.6f);
            thumb = new Geometry("VSlThumb", thumbBox);
            Material thm = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            thm.setColor("Color", ACCENT2); thumb.setMaterial(thm); parent.attachChild(thumb);
            refreshVisuals();
        }

        private void refreshVisuals() {
            float fillW = Math.max(0.001f, value * trackW);
            fill.setLocalScale(fillW, 1f, 1f);
            fill.setLocalTranslation(left + fillW/2f, cy, z+0.1f);
            thumb.setLocalTranslation(left + value * trackW, cy, z+0.2f);
        }

        void updateHover(float mx, float my) {
            float thumbX = left + value * trackW;
            hovered = mx >= thumbX-THUMB_W && mx <= thumbX+THUMB_W && my >= cy-THUMB_H && my <= cy+THUMB_H;
            thumb.getMaterial().setColor("Color", hovered ? ACCENT : ACCENT2);
        }

        boolean onClick(float mx, float my) {
            if (mx >= left-THUMB_W && mx <= left+trackW+THUMB_W && my >= cy-THUMB_H && my <= cy+THUMB_H) {
                value = Math.max(0f, Math.min(1f, (mx-left)/trackW));
                refreshVisuals(); return true;
            }
            return false;
        }

        void setValue(float v) { value = Math.max(0f, Math.min(1f, v)); refreshVisuals(); }
        float getValue() { return value; }
        void setVisible(boolean visible) {
            Spatial.CullHint hint = visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            track.setCullHint(hint);
            fill.setCullHint(hint);
            thumb.setCullHint(hint);
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

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            app = (SimpleApplication) application;
            rootNode = app.getRootNode(); guiNode = app.getGuiNode();
            assetManager = app.getAssetManager(); inputManager = app.getInputManager();
            cam = app.getCamera();
            app.getViewPort().setBackgroundColor(BG);
            app.getInputManager().setCursorVisible(true);
            setupBackground();
            setupDecorBalls();
            setupUI();
            setupInput();
            startMenuMusic();
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

            guiNode.detachAllChildren();

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

                float logoW = 620f;
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
            float btnW = 360f;
            float btnH = 78f;

            float startX = 250f;
            float startY = H / 2f + 10f;

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
                    startY - 95f,
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
                    startY - 190f,
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

            // =========================================================
            // BOTTOM BUTTONS
            // =========================================================
            float miniW = 160f;
            float miniH = 54f;

            float miniY = 65f;

            MenuButton creditsBtn = new MenuButton(
                    "CREDITS",
                    120f,
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

            MenuButton patreonBtn = new MenuButton(
                    "PATREON",
                    320f,
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

            MenuButton discordBtn = new MenuButton(
                    "DISCORD",
                    520f,
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

            // =========================================================
            // FOOTER TEXT
            // =========================================================
            infoText = new BitmapText(font);

            infoText.setSize(14);

            infoText.setText(
                    "TURBO SNAKE ENGINE  •  CYBER EDITION"
            );

            infoText.setColor(
                    new ColorRGBA(0.55f,0.65f,0.85f,1f)
            );

            infoText.setLocalTranslation(
                    W - infoText.getLineWidth() - 30f,
                    28f,
                    5f
            );

            guiNode.attachChild(infoText);

            // =========================================================
            // SETTINGS PANEL
            // =========================================================
            buildSettingsPanel();
        }

        private void buildSettingsPanel() {
            float W = cam.getWidth(), H = cam.getHeight(), cx = W/2f, cy = H/2f;
            BitmapFont font = loadFont(assetManager);
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
            float panelW = 460f, panelH = 500f;
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
            Geometry headerLine = new Geometry("PanelHeaderLine", new Box(panelW/2f, 3f, 0.3f));
            headerLine.setMaterial(unshaded(assetManager, ACCENT));
            headerLine.setLocalTranslation(cx, cy + panelH/2f - 2f, Z);
            settingsPanel.attachChild(headerLine);

            // ── Заголовок ──
            BitmapText header = new BitmapText(font);
            header.setSize(28); header.setText("НАСТРОЙКИ"); header.setColor(ACCENT2);
            header.setLocalTranslation(cx - header.getLineWidth()/2, cy + panelH/2f - 40f, Z);
            settingsPanel.attachChild(header);

            // ── Разделитель под заголовком ──
            Geometry divLine = new Geometry("DivLine", new Box(panelW/2f - 20f, 1.5f, 0.2f));
            divLine.setMaterial(unshaded(assetManager, BORDER));
            divLine.setLocalTranslation(cx, cy + panelH/2f - 64f, Z);
            settingsPanel.attachChild(divLine);

            // ── Вкладки (TAB BAR) ──
            float tabY = cy + panelH/2f - 90f;
            float tabW = 180f, tabH = 38f;
            float tabGap = 10f;

            // Фон полоски вкладок
            Geometry tabBg = new Geometry("TabBg", new Box(panelW/2f - 10f, tabH/2f + 4f, 0.3f));
            tabBg.setMaterial(unshaded(assetManager, new ColorRGBA(0.04f, 0.06f, 0.14f, 1f)));
            tabBg.setLocalTranslation(cx, tabY, Z - 0.1f);
            settingsPanel.attachChild(tabBg);

            tabMainBtn = new MenuButton("◆ ОСНОВНОЕ", cx - tabW/2f - tabGap/2f, tabY, tabW, tabH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT2, assetManager, settingsPanel, Z);
            tabGraphicsBtn = new MenuButton("◆ ГРАФИКА", cx + tabW/2f + tabGap/2f, tabY, tabW, tabH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, TEXT_DIM, assetManager, settingsPanel, Z);

            // ── ВКЛАДКА 0: ОСНОВНОЕ ──────────────────────────────────────────
            float contentTop = tabY - tabH/2f - 20f;

            BitmapText sfxLabel = new BitmapText(font);
            sfxLabel.setName("SfxLabel");
            sfxLabel.setSize(18); sfxLabel.setText("🔊  Звуки"); sfxLabel.setColor(TEXT);
            sfxLabel.setLocalTranslation(cx - panelW/2f + 25f, contentTop, Z);
            settingsPanel.attachChild(sfxLabel);
            sfxVal = new BitmapText(font);
            sfxVal.setSize(18); sfxVal.setColor(ACCENT2);
            sfxVal.setLocalTranslation(cx + panelW/2f - 70f, contentTop, Z);
            settingsPanel.attachChild(sfxVal);
            sfxSlider = new VolumeSlider(cx, contentTop - 28f, panelW - 60f, effectVolume, assetManager, settingsPanel, Z);

            BitmapText musicLabel = new BitmapText(font);
            musicLabel.setName("MusicLabel");
            musicLabel.setSize(18); musicLabel.setText("♪  Музыка"); musicLabel.setColor(TEXT);
            musicLabel.setLocalTranslation(cx - panelW/2f + 25f, contentTop - 72f, Z);
            settingsPanel.attachChild(musicLabel);
            musicVal = new BitmapText(font);
            musicVal.setSize(18); musicVal.setColor(ACCENT);
            musicVal.setLocalTranslation(cx + panelW/2f - 70f, contentTop - 72f, Z);
            settingsPanel.attachChild(musicVal);
            musicSlider = new VolumeSlider(cx, contentTop - 100f, panelW - 60f, musicVolume, assetManager, settingsPanel, Z);

            // Разделитель
            Geometry div2 = new Geometry("Div2", new Box(panelW/2f - 30f, 1f, 0.2f));
            div2.setName("NickDivider");
            div2.setMaterial(unshaded(assetManager, BORDER));
            div2.setLocalTranslation(cx, contentTop - 138f, Z);
            settingsPanel.attachChild(div2);

            BitmapText nickLabel = new BitmapText(font);
            nickLabel.setName("NickLabel");
            nickLabel.setSize(15); nickLabel.setText("ИМЯ ИГРОКА");
            nickLabel.setColor(TEXT_DIM);
            nickLabel.setLocalTranslation(cx - panelW/2f + 25f, contentTop - 155f, Z);
            settingsPanel.attachChild(nickLabel);

            Box nickBorder = new Box(panelW/2f - 25f, 22f, 0.2f);
            Geometry nickBorderGeo = new Geometry("NickBorder", nickBorder);
            nickBorderGeo.setMaterial(unshaded(assetManager, ACCENT2));
            nickBorderGeo.setLocalTranslation(cx, contentTop - 190f, Z - 0.1f);
            settingsPanel.attachChild(nickBorderGeo);
            Box nickBg = new Box(panelW/2f - 27f, 20f, 0.3f);
            Geometry nickBgGeo = new Geometry("NickBg", nickBg);
            nickBgGeo.setMaterial(unshaded(assetManager, new ColorRGBA(0.04f,0.06f,0.14f,1f)));
            nickBgGeo.setLocalTranslation(cx, contentTop - 190f, Z);
            settingsPanel.attachChild(nickBgGeo);
            nicknameText = new BitmapText(font);
            nicknameText.setSize(22); nicknameText.setText(nickname.toString());
            nicknameText.setColor(ACCENT3);
            nicknameText.setLocalTranslation(cx - panelW/2f + 32f, contentTop - 178f, Z + 0.5f);
            settingsPanel.attachChild(nicknameText);
            cursorBlink = new BitmapText(font);
            cursorBlink.setSize(22); cursorBlink.setText("|");
            cursorBlink.setColor(ACCENT3);
            settingsPanel.attachChild(cursorBlink);

            // ── ВКЛАДКА 1: ГРАФИКА ───────────────────────────────────────────
            float gTop = contentTop - 5f;
            float toggleW = panelW - 60f, toggleH = 44f, toggleGap = 14f;

            // Заголовок раздела (скрыт по умолчанию, только на вкладке Графика)
            BitmapText gfxHeader = new BitmapText(font);
            gfxHeader.setName("GfxHeader");
            gfxHeader.setSize(15); gfxHeader.setText("ПАРАМЕТРЫ ОТОБРАЖЕНИЯ");
            gfxHeader.setColor(TEXT_DIM);
            gfxHeader.setLocalTranslation(cx - gfxHeader.getLineWidth()/2f - 30f, gTop + 12f, Z);
            settingsPanel.attachChild(gfxHeader);

            btnShadows = new MenuButton(
                    shadowsEnabled   ? "✔  ТЕНИ — ВКЛ"   : "✘  ТЕНИ — ВЫКЛ",
                    cx, gTop - toggleH/2f, toggleW, toggleH,
                    shadowsEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL,
                    BTN_HOVER, BTN_PRESS,
                    shadowsEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);
            btnShadows.bgGeo.setName("BtnShadows");

            btnParticles = new MenuButton(
                    particlesEnabled ? "✔  ЧАСТИЦЫ — ВКЛ" : "✘  ЧАСТИЦЫ — ВЫКЛ",
                    cx, gTop - toggleH/2f - (toggleH + toggleGap), toggleW, toggleH,
                    particlesEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL,
                    BTN_HOVER, BTN_PRESS,
                    particlesEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);

            btnFog = new MenuButton(
                    fogEnabled       ? "✔  ТУМАН — ВКЛ"   : "✘  ТУМАН — ВЫКЛ",
                    cx, gTop - toggleH/2f - (toggleH + toggleGap)*2f, toggleW, toggleH,
                    fogEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL,
                    BTN_HOVER, BTN_PRESS,
                    fogEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);

            btnBloom = new MenuButton(
                    bloomEnabled     ? "✔  BLOOM — ВКЛ"   : "✘  BLOOM — ВЫКЛ",
                    cx, gTop - toggleH/2f - (toggleH + toggleGap)*3f, toggleW, toggleH,
                    bloomEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL,
                    BTN_HOVER, BTN_PRESS,
                    bloomEnabled ? ACCENT : TEXT_DIM, assetManager, settingsPanel, Z);

            // ── Кнопка ЗАКРЫТЬ (общая) ──
            float closeY = cy - panelH/2f + 40f;
            settingsClose = new MenuButton("✔  СОХРАНИТЬ И ЗАКРЫТЬ", cx, closeY, panelW - 60f, 46f,
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
            String[] gfxNames = {"GfxHeader"};
            for (String name : gfxNames) {
                Spatial s = settingsPanel.getChild(name);
                if (s != null) s.setCullHint(tab == 1 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            }
            Spatial.CullHint gfxHint = tab == 1 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
            if (btnShadows   != null) { btnShadows.bgGeo.setCullHint(gfxHint); btnShadows.accentGeo.setCullHint(gfxHint); btnShadows.borderGeo.setCullHint(gfxHint); btnShadows.label.setCullHint(gfxHint); }
            if (btnParticles != null) { btnParticles.bgGeo.setCullHint(gfxHint); btnParticles.accentGeo.setCullHint(gfxHint); btnParticles.borderGeo.setCullHint(gfxHint); btnParticles.label.setCullHint(gfxHint); }
            if (btnFog       != null) { btnFog.bgGeo.setCullHint(gfxHint); btnFog.accentGeo.setCullHint(gfxHint); btnFog.borderGeo.setCullHint(gfxHint); btnFog.label.setCullHint(gfxHint); }
            if (btnBloom     != null) { btnBloom.bgGeo.setCullHint(gfxHint); btnBloom.accentGeo.setCullHint(gfxHint); btnBloom.borderGeo.setCullHint(gfxHint); btnBloom.label.setCullHint(gfxHint); }
        }

        private void updateSettingsLabels() {
            if (sfxVal   != null) sfxVal.setText(Math.round(effectVolume * 100) + "%");
            if (musicVal != null) musicVal.setText(Math.round(musicVolume * 100) + "%");
            if (sfxSlider   != null) sfxSlider.setValue(effectVolume);
            if (musicSlider != null) musicSlider.setValue(musicVolume);
            MusicManager.setVolume(musicVolume);
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
                if (settingsOpen) { settingsOpen = false; settingsPanel.setCullHint(Spatial.CullHint.Always); }
                else app.stop();
            }, "MenuEsc");

            inputManager.addMapping("MMouseMove",
                    new MouseAxisTrigger(MouseInput.AXIS_X, false), new MouseAxisTrigger(MouseInput.AXIS_X, true),
                    new MouseAxisTrigger(MouseInput.AXIS_Y, false), new MouseAxisTrigger(MouseInput.AXIS_Y, true));
            inputManager.addListener((AnalogListener)(n,v,t) -> {
                com.jme3.math.Vector2f mp = inputManager.getCursorPosition();
                float mx = mp.x, my = mp.y;
                soloBtn.updateHover(mx, my); joinBtn.updateHover(mx, my);
                settingsBtn.updateHover(mx, my);

                if (settingsOpen) {
                    sfxSlider.updateHover(mx, my); musicSlider.updateHover(mx, my);
                    settingsClose.updateHover(mx, my);
                    tabMainBtn.updateHover(mx, my);
                    tabGraphicsBtn.updateHover(mx, my);

                    // новые строки ↓
                    if (activeSettingsTab == 1) {
                        if (btnShadows   != null) btnShadows.updateHover(mx, my);
                        if (btnParticles != null) btnParticles.updateHover(mx, my);
                        if (btnFog       != null) btnFog.updateHover(mx, my);
                        if (btnBloom     != null) btnBloom.updateHover(mx, my);
                    }
                }

            }, "MMouseMove");

            inputManager.addMapping("MClick", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                com.jme3.math.Vector2f mp = inputManager.getCursorPosition();
                float mx = mp.x, my = mp.y;
                if (settingsOpen) {
                    if (p) {
                        settingsClose.onPress(mx, my);
                        if (activeSettingsTab == 1) {
                            if (btnShadows   != null) btnShadows.onPress(mx, my);
                            if (btnParticles != null) btnParticles.onPress(mx, my);
                            if (btnFog       != null) btnFog.onPress(mx, my);
                            if (btnBloom     != null) btnBloom.onPress(mx, my);
                        }
                    } else {
                        if (settingsClose.onRelease(mx, my)) {
                            settingsOpen = false; settingsPanel.setCullHint(Spatial.CullHint.Always);
                            saveSettings(nickname.toString());
                        } else if (tabMainBtn.isHit(mx, my)) {
                            setSettingsTab(0);
                        } else if (tabGraphicsBtn.isHit(mx, my)) {
                            setSettingsTab(1);
                        } else if (activeSettingsTab == 1) {
                            // ---------- обработка кнопок графики ----------
                            if (btnShadows != null && btnShadows.onRelease(mx, my)) {
                                shadowsEnabled = !shadowsEnabled;
                                btnShadows.setText(shadowsEnabled ? "✔  ТЕНИ — ВКЛ" : "✘  ТЕНИ — ВЫКЛ");
                                btnShadows.setAccentColor(shadowsEnabled ? ACCENT : TEXT_DIM);
                                btnShadows.setBgNormal(shadowsEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL);
                                saveSettings(nickname.toString());
                            } else if (btnParticles != null && btnParticles.onRelease(mx, my)) {
                                particlesEnabled = !particlesEnabled;
                                btnParticles.setText(particlesEnabled ? "✔  ЧАСТИЦЫ — ВКЛ" : "✘  ЧАСТИЦЫ — ВЫКЛ");
                                btnParticles.setAccentColor(particlesEnabled ? ACCENT : TEXT_DIM);
                                btnParticles.setBgNormal(particlesEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL);
                                saveSettings(nickname.toString());
                            } else if (btnFog != null && btnFog.onRelease(mx, my)) {
                                fogEnabled = !fogEnabled;
                                btnFog.setText(fogEnabled ? "✔  ТУМАН — ВКЛ" : "✘  ТУМАН — ВЫКЛ");
                                btnFog.setAccentColor(fogEnabled ? ACCENT : TEXT_DIM);
                                btnFog.setBgNormal(fogEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL);
                                saveSettings(nickname.toString());
                            } else if (btnBloom != null && btnBloom.onRelease(mx, my)) {
                                bloomEnabled = !bloomEnabled;
                                btnBloom.setText(bloomEnabled ? "✔  BLOOM — ВКЛ" : "✘  BLOOM — ВЫКЛ");
                                btnBloom.setAccentColor(bloomEnabled ? ACCENT : TEXT_DIM);
                                btnBloom.setBgNormal(bloomEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : BTN_NORMAL);
                                saveSettings(nickname.toString());
                            }
                        } else if (activeSettingsTab == 0 && sfxSlider.onClick(mx, my)) {
                            effectVolume = sfxSlider.getValue(); updateSettingsLabels();
                        } else if (activeSettingsTab == 0 && musicSlider.onClick(mx, my)) {
                            musicVolume = musicSlider.getValue(); updateSettingsLabels();
                        }
                    }
                    return;
                }
                if (p) {
                    soloBtn.onPress(mx, my); joinBtn.onPress(mx, my);
                    settingsBtn.onPress(mx, my);
                } else {
                    if (soloBtn.onRelease(mx, my)) launch(false, true);
                    else if (joinBtn.onRelease(mx, my)) launch(false, false);
                    else if (settingsBtn.onRelease(mx, my)) {
                        settingsOpen = true;
                        settingsPanel.setCullHint(Spatial.CullHint.Inherit);
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
            app.getInputManager().setCursorVisible(false);
            rootNode.detachChild(decorNode);
            guiNode.detachAllChildren();
            inputManager.clearMappings();
            app.getStateManager().detach(this);
            if (solo) {
                app.getStateManager().attach(new LobbyState(nick, false, true, null, 0));
            } else {
                app.getStateManager().attach(new ServerListState(nick));
            }
        }

        @Override
        public void update(float tpf) {
            for (int i=0; i<ballAngles.length; i++) {
                ballAngles[i] += ballSpeeds[i]*tpf;
                decorNode.getChild(i).setLocalTranslation(
                        FastMath.sin(ballAngles[i])*ballRadii[i], ballY[i],
                        FastMath.cos(ballAngles[i])*ballRadii[i]);
            }
            blinkTimer += tpf;
            if (blinkTimer>0.5f) {
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
        private final List<ServerEntry> serverEntries = new ArrayList<>();
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
            app = (SimpleApplication) application;
            guiNode = app.getGuiNode();
            assetManager = app.getAssetManager();
            inputManager = app.getInputManager();
            cam = app.getCamera();
            app.getViewPort().setBackgroundColor(BG);
            app.getInputManager().setCursorVisible(true);
            buildUI();
            setupInput();
            startScan();
        }

        private void rebuildUIAfterResize() {

            guiNode.detachAllChildren();

            serverButtons.clear();

            buildUI();

            updateServerListGUI();
        }

        private void buildUI() {
            BitmapFont font = loadFont(assetManager);
            float W = cam.getWidth(), H = cam.getHeight();

            titleText = new BitmapText(font);
            titleText.setSize(42); titleText.setText("ДОСТУПНЫЕ ЛОББИ");
            titleText.setColor(ACCENT);
            titleText.setLocalTranslation(W/2f - titleText.getLineWidth()/2, H - 40, 0);
            guiNode.attachChild(titleText);

            statusText = new BitmapText(font);
            statusText.setSize(20); statusText.setColor(TEXT_DIM);
            statusText.setLocalTranslation(40, H - 100, 0);
            guiNode.attachChild(statusText);

            float btnW = 280f, btnH = 45f;
            float commonY = 40f; // Выносим общую высоту в переменную для удобства

            // Кнопка «Создать лобби» — теперь внизу (commonY)
            createBtn = new MenuButton("Создать лобби", 40f + btnW/2f, commonY, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT3, assetManager, guiNode, 0f);

            // Кнопка «Обновить список» — на той же высоте
            refreshBtn = new MenuButton("Обновить", 40f + btnW + 20f + btnW/2f, commonY, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT2, assetManager, guiNode, 0f);

            // Кнопка «Назад»
            backBtn = new MenuButton("Назад", W - 40f - 140f/2f, commonY, 140f, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, DANGER, assetManager, guiNode, 0f);
        }

        private void updateServerListGUI() {
            for (MenuButton b : serverButtons) b.detach(guiNode);
            serverButtons.clear();

            float y = cam.getHeight() - 150f;
            for (ServerEntry entry : serverEntries) {
                String label = entry.ip + " | " + entry.mapName + " | " + entry.playerCount + " | " + entry.pingMs + "мс";
                MenuButton btn = new MenuButton(label, 40f + 250f, y, 500f, 45f,
                        BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT2, assetManager, guiNode, 0f);
                serverButtons.add(btn);
                y -= 50f;
            }
            statusText.setText("Найдено лобби: " + serverEntries.size());
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
            if (scanning) {
                scanTimer -= tpf;
                if (scanTimer <= 0) {
                    scanning = false;
                    if (discoverSocket != null) discoverSocket.close();
                    statusText.setText("Сканирование завершено.");
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

                Vector2f mp = inputManager.getCursorPosition();

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
                Vector2f mp = inputManager.getCursorPosition();
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
            guiNode.detachAllChildren();
            inputManager.clearMappings();
            app.getStateManager().detach(this);
            app.getStateManager().attach(new LobbyState(myNick, true, false, null, 0));
        }

        private void joinServer(String addrStr) {
            stopScanning();
            String[] parts = addrStr.split(":");
            String host = parts[0];
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : GAME_PORT;
            guiNode.detachAllChildren();
            inputManager.clearMappings();
            app.getStateManager().detach(this);
            app.getStateManager().attach(new LobbyState(myNick, false, false, host, port));
        }

        private void backToMenu() {
            stopScanning();
            guiNode.detachAllChildren();
            inputManager.clearMappings();
            app.getStateManager().detach(this);
            app.getStateManager().attach(new MainMenuState());
        }

        private void stopScanning() {
            scanning = false;
            if (discoverSocket != null && !discoverSocket.isClosed()) discoverSocket.close();
        }

        static class ServerEntry {
            final String ip, mapName, playerCount;
            final long pingMs;
            ServerEntry(String ip, String mapName, String playerCount, long pingMs) {
                this.ip = ip;
                this.mapName = mapName;
                this.playerCount = playerCount;
                this.pingMs = pingMs;
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

        private final String myNick;
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
        private MenuButton[] mapButtons = new MenuButton[3];
        private BitmapText mapLabel;
        private float animTimer = 0f;
        private int animDot = 0;

        private final List<InetSocketAddress> clients = new CopyOnWriteArrayList<>();

        // флаг включения/отключения чёрных кубов
        private boolean cubesEnabled = true;
        private MenuButton cubesToggleBtn;

        private final Map<String, ColorRGBA> lobbyColors = new ConcurrentHashMap<>();

        private final List<Geometry> colorSwatches = new ArrayList<>();
        private final List<ColorRGBA> paletteColors = new ArrayList<>();

        private final List<Geometry> previewBalls = new ArrayList<>();
        private Node previewSnakeNode;
        private float previewAnimTime = 0f;

        private static final String[] MAP_NAMES = {"Зелёная арена", "Пустыня", "Ямы с шипами"};
        private static final ColorRGBA[] MAP_COLORS = { ACCENT, ACCENT3, DANGER };

        public LobbyState(String nick, boolean isHost, boolean isSolo, String connectHost, int connectPort) {
            this.myNick = nick; this.isHost = isHost; this.isSolo = isSolo;
            this.connectHost = connectHost; this.connectPort = connectPort;
        }

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            app = (SimpleApplication) application;
            guiNode = app.getGuiNode();
            assetManager = app.getAssetManager();
            inputManager = app.getInputManager();
            cam = app.getCamera();

            app.getViewPort().setBackgroundColor(BG);
            app.getInputManager().setCursorVisible(true);

            if (!players.contains(myNick)) players.add(myNick);
            lobbyColors.put(myNick, selectedSnakeColor.clone());

            buildUI();
            setupInput();
            refreshUI();

            if (!isSolo) startNetwork();
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
        }

        private void rebuildUIAfterResize() {

            guiNode.detachAllChildren();

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
            BitmapFont font = loadFont(assetManager);
            float W = cam.getWidth(), H = cam.getHeight();
            float leftX = 40f;

            BitmapText title = new BitmapText(font);
            title.setSize(42); title.setText(isSolo ? "ОДИНОЧНАЯ ИГРА" : "ИГРОВОЕ ЛОББИ");
            title.setColor(ACCENT);
            title.setLocalTranslation(leftX, H - 40, 0);
            guiNode.attachChild(title);

            playersText = new BitmapText(font);
            playersText.setSize(20); playersText.setColor(TEXT);
            playersText.setLocalTranslation(leftX, H - 120, 0);
            guiNode.attachChild(playersText);

            mapLabel = new BitmapText(font);
            mapLabel.setSize(20); mapLabel.setColor(ACCENT3);
            mapLabel.setLocalTranslation(leftX, H - 300, 0);
            guiNode.attachChild(mapLabel);

            float btnW = 240f, btnH = 45f;
            float btnStartY = H - 360f;
            for (int i = 0; i < 3; i++) {
                mapButtons[i] = new MenuButton(MAP_NAMES[i],
                        leftX + btnW/2f, btnStartY - i * (btnH + 10f),
                        btnW, btnH, BTN_NORMAL, BTN_HOVER, BTN_PRESS, MAP_COLORS[i], assetManager, guiNode, 0f);
            }
            refreshMapSelection();

            searchAnim = new BitmapText(font);
            searchAnim.setSize(16); searchAnim.setColor(ACCENT2);
            searchAnim.setLocalTranslation(leftX, 100, 0);
            guiNode.attachChild(searchAnim);

            startHint = new BitmapText(font);
            startHint.setSize(22);
            startHint.setLocalTranslation(leftX, 60, 0);
            guiNode.attachChild(startHint);

            notificationText = new BitmapText(font);
            notificationText.setSize(28);
            notificationText.setColor(new ColorRGBA(1f, 1f, 1f, 0f));
            notificationText.setText("");
            notificationText.setLocalTranslation(W/2f, H/2f + 60f, 0);
            guiNode.attachChild(notificationText);

            // Кнопка включения/отключения чёрных кубов
            cubesToggleBtn = new MenuButton("КУБЫ: ВКЛ", leftX + 140f, H - 560f, 280f, 44f,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT, assetManager, guiNode, 0f);

            // Цветовая палитра и превью змейки
            buildColorPalette(W, H);
            buildSnakePreview(W, H);
            refreshColorPaletteSelection();
            refreshSnakePreviewColor();
        }

        private void buildColorPalette(float W, float H) {

            BitmapFont font = loadFont(assetManager);

            BitmapText colorTitle = new BitmapText(font);
            colorTitle.setSize(18);
            colorTitle.setText("ЦВЕТ ЗМЕЙКИ");
            colorTitle.setColor(ACCENT2);
            colorTitle.setLocalTranslation(W - 420f, H - 80f, 3f);
            guiNode.attachChild(colorTitle);

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

            float startX = W - 400f;
            float startY = H - 125f;

            float size = 34f;
            float gap = 12f;

            for (int i = 0; i < paletteColors.size(); i++) {

                int row = i / 6;
                int col = i % 6;

                float x = startX + col * (size + gap);
                float y = startY - row * (size + gap);

                Geometry swatch = new Geometry(
                        "ColorSwatch" + i,
                        new Box(size / 2f, size / 2f, 0.4f)
                );

                Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                mat.setColor("Color", paletteColors.get(i));

                swatch.setMaterial(mat);
                swatch.setQueueBucket(RenderQueue.Bucket.Gui);
                swatch.setLocalTranslation(x, y, 2.2f);

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

                float size = 42f;

                if (mx >= p.x - size / 2f && mx <= p.x + size / 2f &&
                        my >= p.y - size / 2f && my <= p.y + size / 2f) {

                    selectedSnakeColor = paletteColors.get(i).clone();
                    lobbyColors.put(myNick, selectedSnakeColor.clone());

                    refreshColorPaletteSelection();
                    refreshSnakePreviewColor();

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

        private void showNotification(String msg) {
            notificationText.setText(msg);
            float W = cam.getWidth();
            notificationText.setLocalTranslation(W/2f - notificationText.getLineWidth()/2f,
                    notificationText.getLocalTranslation().y, 0);
            notificationAlpha = 1f;
            notificationTimer = 4.0f;
        }

        private void refreshUI() {
            StringBuilder sb = new StringBuilder("СПИСОК ИГРОКОВ:\n");
            for (int i=0; i<players.size(); i++) {
                sb.append(i+1).append(". ").append(players.get(i));
                if (players.get(i).equals(myNick)) sb.append(" (ВЫ)");
                sb.append("\n");
            }
            playersText.setText(sb.toString());

            mapLabel.setText("ВЫБРАННАЯ КАРТА: " + MAP_NAMES[selectedMap]);

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
        }

        private void refreshMapSelection() {

            for (int i = 0; i < mapButtons.length; i++) {

                if (mapButtons[i] == null)
                    continue;

                boolean selected = (i == selectedMap);

                if (selected) {

                    mapButtons[i].setBgNormal(
                            new ColorRGBA(0.12f, 0.22f, 0.50f, 1f)
                    );

                    mapButtons[i].setAccentColor(
                            new ColorRGBA(1f, 1f, 1f, 1f)
                    );

                } else {

                    mapButtons[i].setBgNormal(BTN_NORMAL);

                    mapButtons[i].setAccentColor(
                            MAP_COLORS[i]
                    );
                }
            }
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

                Vector2f mp = inputManager.getCursorPosition();

                float mx = mp.x;
                float my = mp.y;

                if (cubesToggleBtn != null) {
                    cubesToggleBtn.updateHover(mx, my);
                }

                for (MenuButton btn : mapButtons) {
                    if (btn != null) {
                        btn.updateHover(mx, my);
                    }
                }

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
                if ("LSend".equals(n)) {
                    if (isSolo || (isHost && players.size() >= 2)) startGame();
                }
                if ("LEsc".equals(n)) backToMenu();
            }, "LSend", "LEsc");

            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p) return;
                Vector2f mp = inputManager.getCursorPosition();
                for (int i=0; i<3; i++) {
                    if (mapButtons[i].isHit(mp.x, mp.y)) {
                        selectedMap = i;
                        refreshMapSelection();
                        if (isHost) broadcastMapSelection();
                        refreshUI();
                    }
                }
                // переключаем чёрные кубы
                if (cubesToggleBtn != null && cubesToggleBtn.isHit(mp.x, mp.y)) {
                    cubesEnabled = !cubesEnabled;
                    cubesToggleBtn.setText("КУБЫ: " + (cubesEnabled ? "ВКЛ" : "ВЫКЛ"));
                    cubesToggleBtn.setAccentColor(cubesEnabled ? ACCENT : TEXT_DIM);
                }

                // выбор цвета змейки
                if (handleColorPaletteClick(mp.x, mp.y)) {
                    return;
                }
            }, "LClick");
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
                            String reply = "HOST_HERE|" + GAME_PORT + "|" + MAP_NAMES[selectedMap] + "|" + players.size() + "/4";
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
            String[] p = msg.split("\\|", -1);
            switch (p[0]) {
                case "JOIN":
                    if (players.size() < 4 && !gameStarted.get()) {

                        String nick = p.length > 1 ? p[1] : "Player";

                        if (!players.contains(nick))
                            players.add(nick);

                        if (!clients.contains(from))
                            clients.add(from);

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
                        });
                    }
                    break;
                case "MAP_REQ":
                    if (p.length > 1) {
                        try { selectedMap = Integer.parseInt(p[1]); } catch (Exception ignore) {}
                        broadcastToAll("MAP|" + selectedMap);
                        app.enqueue(this::refreshUI);
                    }
                    break;
            }
        }

        private void broadcastLobby() { broadcastToAll("LOBBY|" + String.join("|", players)); }
        private void broadcastMapSelection() { broadcastToAll("MAP|" + selectedMap); }

        void broadcastToAll(String msg) {
            if (!isHost) return;
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            for (InetSocketAddress c : clients) {
                try { socket.send(new DatagramPacket(b, b.length, c)); } catch (Exception ignore) {}
            }
        }

        private void sendToClient(String msg, InetSocketAddress c) {
            if (!isHost || socket==null) return;
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            try { socket.send(new DatagramPacket(b, b.length, c)); } catch (Exception ignore) {}
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
            String[] p = msg.split("\\|", -1);
            switch (p[0]) {
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
                    if (p.length>1) { try { selectedMap = Integer.parseInt(p[1]); refreshUI(); } catch (Exception ignore) {} }
                    break;
                case "START":
                    if (p.length > 1) {
                        try {
                            selectedMap = Integer.parseInt(p[1]);
                        } catch (Exception ignore) {}
                    }

                    if (p.length > 2) {
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
                    break;
                case "COLORS":
                    if (p.length > 1) {
                        applyColorsPacket(p[1]);
                    }
                    break;
            }
        }

        private void sendToHost(String msg) {
            if (hostAddress == null) return;
            try {
                byte[] b = msg.getBytes(StandardCharsets.UTF_8);
                socket.send(new DatagramPacket(b, b.length, InetAddress.getByName(hostAddress), hostPort));
            } catch (Exception e) {}
        }

        private void startGame() {
            gameStarted.set(true);
            broadcastAllColors();
            broadcastToAll("START|" + selectedMap + "|" + encodeAllColors());
            launchGame();
        }

        private void launchGame() {
            searching.set(false);

            List<String> playerList = new ArrayList<>(players);
            int myIdx = playerList.indexOf(myNick);

            guiNode.detachAllChildren();
            inputManager.clearMappings();
            app.getInputManager().setCursorVisible(false);

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
                            new HashMap<>(lobbyColors)
                    )
            );
        }

        private void backToMenu() {
            searching.set(false);
            if (socket!=null) socket.close();
            guiNode.detachAllChildren();
            inputManager.clearMappings();
            app.getInputManager().setCursorVisible(true);  // was false — курсор пропадал
            app.getStateManager().detach(this);
            app.getStateManager().attach(new MainMenuState());
        }

        @Override
        public void update(float tpf) {
            animTimer += tpf;
            if (animTimer > 0.5f) {
                animTimer = 0; animDot = (animDot+1)%4;
                String dots = ".".repeat(animDot);
                if (!isSolo) {
                    if (!isHost && hostAddress == null) searchAnim.setText("Подключение" + dots);
                    else if (isHost) searchAnim.setText("IP: " + getLocalIP() + "  Ждём" + dots);
                    else searchAnim.setText("Подключено к " + hostAddress + dots);
                } else {
                    searchAnim.setText("Готово к игре" + dots);
                }
            }

            if (notificationTimer > 0f) {
                notificationTimer -= tpf;
                if (notificationTimer <= 0f) notificationAlpha = 0f;
                else notificationAlpha = Math.min(1f, notificationTimer / 1.0f);
                ColorRGBA c = new ColorRGBA(1f, 1f, 1f, notificationAlpha);
                notificationText.setColor(c);
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

            updateSnakePreview(tpf);
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
            if (socket!=null && !socket.isClosed()) socket.close();
        }
    }

    // =========================================================================
    // ИГРОВОЕ СОСТОЯНИЕ (без изменений в части чата, т.к. его там и не было)
    // =========================================================================
    public static class GameState extends AbstractAppState {

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
        private MenuButton pauseResumeBtn, pauseMenuBtn;

        // Рывок  (увеличена длительность с 0.18 до 0.55 — рывок теперь ощутимый)
        private float dashCooldown = 0f;
        private static final float DASH_COOLDOWN_MAX = 4f;
        private static final float DASH_SPEED_MULT   = 5.0f;   // немного увеличен множитель
        private static final float DASH_DURATION     = 0.55f;  // было 0.18f
        private float dashTimer = 0f;
        private BitmapText dashCooldownText;

        private ColorRGBA baseGridColor = new ColorRGBA(0f, 0.10f, 0f, 0.88f);  // из buildTerrainGrid

        // Читкод KOPRFDC
        private static final int[] CHEAT_CODE = { KeyInput.KEY_K, KeyInput.KEY_O, KeyInput.KEY_P,
                KeyInput.KEY_R, KeyInput.KEY_F, KeyInput.KEY_D, KeyInput.KEY_C };
        private int cheatCodeIndex = 0;
        private boolean bordersRemoved = false;

        private final String myNick;
        private final List<String> allPlayers;
        private final int myIndex;
        private final boolean solo;
        private final int mapIndex; // 0=Зелёная, 1=Пустыня, 2=Ямы
        private final boolean cubesEnabled;

        private int lastW = -1;
        private int lastH = -1;

        private final List<SnakePlayer> snakes = new ArrayList<>();
        private Node foodNode, wallNode, cloudNode, worldNode, cubeNode;
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

        // счётчик ID для шариков
        private int ballIdCounter = 0;

        // Враги-кубы
        private final List<BlackCube> blackCubes = new ArrayList<>();
        private int cubeIdCounter = 0;
        private float cubeNetTimer  = 0f;
        private static final float CUBE_NET_INTERVAL = 0.10f;
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
        private float netSendTimer = 0f;
        private static final float NET_SEND_INTERVAL = 0.033f;
        private int foodIdCounter = 0;

        // Таймер игры (для ивентов)
        private float gameTime = 0f;

        // Пустыня
        private float defaultFogDistance = 80f;
        private float defaultFogDensity = 0.004f;
        private ColorRGBA defaultFogColor = new ColorRGBA(0.55f, 0.72f, 0.88f, 1f);
        private ColorRGBA dayBgColor, nightBgColor;

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

        public GameState(String myNick, List<String> allPlayers, int myIndex, boolean solo,
                         boolean isHost, DatagramSocket socket, String hostAddress, int hostPort,
                         List<InetSocketAddress> clients, int mapIndex, boolean cubesEnabled,
                         Map<String, ColorRGBA> playerColors) {
            this.myNick     = myNick;
            this.allPlayers = allPlayers != null ? allPlayers : Collections.singletonList(myNick);
            this.myIndex    = myIndex < 0 ? 0 : myIndex;
            this.solo       = solo;
            this.isHost     = isHost;
            this.socket     = socket;
            this.hostAddress= hostAddress;
            this.hostPort   = hostPort;
            this.clients    = clients != null ? clients : new CopyOnWriteArrayList<>();
            this.mapIndex   = mapIndex;
            this.mapHalf = (mapIndex == 0) ? 60f : 40f;
            this.cubesEnabled = cubesEnabled;
            this.playerColors = playerColors != null ? playerColors : new HashMap<>();
        }

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            app = (SimpleApplication) application;
            rootNode = app.getRootNode(); guiNode = app.getGuiNode();
            assetManager = app.getAssetManager(); inputManager = app.getInputManager();
            cam = app.getCamera(); stateManager = sm;

            // Начальный цвет фона (день) — будет заменён initDayNightLighting()
            app.getViewPort().setBackgroundColor(new ColorRGBA(0.28f, 0.56f, 1.00f, 1f));
            dayBgColor = new ColorRGBA(0.28f, 0.56f, 1.00f, 1f);
            if (mapIndex == 1) nightBgColor = new ColorRGBA(0.05f, 0.04f, 0.08f, 1f);
            else if (mapIndex == 2) nightBgColor = new ColorRGBA(0.02f, 0.02f, 0.06f, 1f);
            else nightBgColor = new ColorRGBA(0.02f, 0.03f, 0.10f, 1f);

            assetManager.registerLocator(".", FileLocator.class);

            // Первый ивент — быстро через 20–50 секунд
            nextEventTimer = 20f + new Random().nextFloat() * 30f;

            setupPhysics();
            buildOuterWorld();
            buildArena();
            buildClouds();
            if (mapIndex == 0) {
                buildTerrainGrid();
            }
            createSnakes();
            spawnFood(MAX_FOOD);
            if (cubesEnabled) spawnInitialCubes();
            buildHUD();
            createGameoverUI();
            setupControls();
            loadSounds();
            if (!solo) initNetwork();
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
            BitmapFont font = loadFont(assetManager);
            gameoverNode = new Node("GameoverUI");
            gameoverNode.setCullHint(Spatial.CullHint.Always);
            guiNode.attachChild(gameoverNode);

            // Затемнение
            Box dimBox = new Box(W/2f, H/2f, 0.1f);
            Geometry dimGeo = new Geometry("GODim", dimBox);
            Material dimMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            dimMat.setColor("Color", new ColorRGBA(0,0,0,0.65f));
            dimMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            dimGeo.setMaterial(dimMat);
            dimGeo.setLocalTranslation(W/2f, H/2f, 5f);
            gameoverNode.attachChild(dimGeo);

            // Карточка — увеличена для размещения контента
            float cardW = 500f, cardH = 360f;
            Box cardBox = new Box(cardW/2f, cardH/2f, 0.5f);
            Geometry cardGeo = new Geometry("GOCard", cardBox);
            Material cardMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            cardMat.setColor("Color", BG_CARD);
            cardMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            cardGeo.setMaterial(cardMat);
            cardGeo.setLocalTranslation(W/2f, H/2f, 5.5f);
            gameoverNode.attachChild(cardGeo);

            // Акцентная линия сверху
            Box topLine = new Box(cardW/2f, 3f, 0.3f);
            Geometry topLineGeo = new Geometry("GOTopLine", topLine);
            topLineGeo.setMaterial(unshaded(assetManager, ACCENT));
            topLineGeo.setLocalTranslation(W/2f, H/2f + cardH/2f - 3f, 5.8f);
            gameoverNode.attachChild(topLineGeo);

            // Заголовок
            BitmapText goTitle = new BitmapText(font);
            goTitle.setSize(32); goTitle.setText("ИГРА ОКОНЧЕНА");
            goTitle.setColor(ACCENT);
            goTitle.setName("GOTitle");
            goTitle.setLocalTranslation(W/2f - goTitle.getLineWidth()/2f, H/2f + 148f, 6f);
            gameoverNode.attachChild(goTitle);

            // Победитель
            BitmapText winnerText = new BitmapText(font);
            winnerText.setSize(20); winnerText.setColor(ACCENT3);
            winnerText.setName("GOWinner");
            winnerText.setLocalTranslation(W/2f - 220f, H/2f + 105f, 6f);
            gameoverNode.attachChild(winnerText);

            // Таблица результатов
            BitmapText scoresText = new BitmapText(font);
            scoresText.setSize(15); scoresText.setColor(TEXT);
            scoresText.setName("GOScores");
            scoresText.setLocalTranslation(W/2f - 220f, H/2f + 65f, 6f);
            gameoverNode.attachChild(scoresText);

            // Кнопки — по центру, с достаточным отступом и размером
            float btnW = 200f, btnH = 48f;
            float btnSpacing = 120f;
            goPlayAgainBtn = new MenuButton("ИГРАТЬ СНОВА", W/2f - btnSpacing, H/2f - 120f, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT, assetManager, gameoverNode, 6f);
            goMenuBtn = new MenuButton("В МЕНЮ", W/2f + btnSpacing, H/2f - 120f, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, DANGER, assetManager, gameoverNode, 6f);
        }

        private void showGameoverOverlay(String winnerName) {
            gameoverNode.setCullHint(Spatial.CullHint.Inherit);
            gameoverUIActive = true;
            inputManager.setCursorVisible(true);

            // Заполняем данные
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

            // Подписываем мышь на кнопки через отдельный листенер
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
                com.jme3.math.Vector2f mp = inputManager.getCursorPosition();
                float mx = mp.x, my = mp.y;
                if (goPlayAgainBtn != null) goPlayAgainBtn.updateHover(mx, my);
                if (goMenuBtn != null) goMenuBtn.updateHover(mx, my);
            }, "GO_MouseMove");
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!gameoverUIActive) return;
                com.jme3.math.Vector2f mp = inputManager.getCursorPosition();
                float mx = mp.x, my = mp.y;
                if (p) {
                    if (goPlayAgainBtn != null) goPlayAgainBtn.onPress(mx, my);
                    if (goMenuBtn != null) goMenuBtn.onPress(mx, my);
                } else {
                    if (goPlayAgainBtn != null && goPlayAgainBtn.onRelease(mx, my)) {
                        // Играть снова: перезапустить с теми же параметрами
                        restartGame();
                    } else if (goMenuBtn != null && goMenuBtn.onRelease(mx, my)) {
                        backToMenu();
                    }
                }
            }, "GO_Mouse");
        }

        private void restartGame() {
            netRunning.set(false);
            if (socket != null) socket.close();
            if (rainSound != null) { rainSound.stop(); rootNode.detachChild(rainSound); rainSound = null; }
            for (SnakePlayer sp : snakes) sp.cleanup(guiNode);
            for (BlackCube bc : blackCubes) {
                if (bc.phy != null) { bc.phy.setEnabled(false); bulletAppState.getPhysicsSpace().remove(bc.phy); }
            }
            blackCubes.clear();
            rootNode.detachAllChildren(); guiNode.detachAllChildren();
            inputManager.clearMappings();
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
                    new HashMap<>()
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
                om.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
                sandstormOverlayGeo.setMaterial(om);
                sandstormOverlayGeo.setQueueBucket(RenderQueue.Bucket.Gui);
                sandstormOverlayGeo.setLocalTranslation(W/2f, H/2f, 15f);
                guiNode.attachChild(sandstormOverlayGeo);
            }
            sandSpawnTimer = 0f;
            if (!TurboSnake.particlesEnabled) return;
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
                if (TurboSnake.particlesEnabled) {
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
                    guiNode.detachChild(sandstormOverlayGeo);
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

        private void spawnCactusAt(PhysicsSpace space, Material cactMat, Material spineMat, float cx, float cz) {
            float cactH = 1.6f + FastMath.nextRandomFloat() * 1.4f;

            Box trunkBox = new Box(0.28f, cactH/2f, 0.28f);
            Geometry trunk = new Geometry("CactI"+cacti.size(), trunkBox);
            trunk.setMaterial(cactMat);
            trunk.setLocalTranslation(cx, cactH/2f, cz);
            trunk.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            wallNode.attachChild(trunk);

            RigidBodyControl trunkPhy = new RigidBodyControl(
                    new BoxCollisionShape(new Vector3f(0.28f, cactH/2f, 0.28f)), 3.0f);
            trunkPhy.setFriction(1.2f);
            trunkPhy.setLinearDamping(0.5f);
            trunkPhy.setAngularDamping(0.7f);
            trunk.addControl(trunkPhy);
            space.add(trunkPhy);

            CactusData cd = new CactusData(trunk, trunkPhy, cx, cz);
            cacti.add(cd);

            // рука (ветка) – случайно
            if (FastMath.nextRandomFloat() < 0.6f) {
                float armSide = FastMath.nextRandomFloat() < 0.5f ? 0.5f : -0.5f;
                Box armBox = new Box(0.35f, 0.18f, 0.18f);
                Geometry arm = new Geometry("CactA"+cacti.size(), armBox);
                arm.setMaterial(cactMat);
                arm.setLocalTranslation(cx + armSide, cactH * 0.6f, cz);
                arm.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
                wallNode.attachChild(arm);
                cd.armGeo = arm;
            }

            // иголки
            int spineCount = 6 + FastMath.nextRandomInt(0, 5);
            for (int j = 0; j < spineCount; j++) {
                float sa = FastMath.nextRandomFloat() * FastMath.TWO_PI;
                float sy = FastMath.nextRandomFloat() * cactH;
                float sd = 0.3f + FastMath.nextRandomFloat() * 0.2f;
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
                // случайная позиция внутри арены, с отступом от центра и стен
                float cx, cz;
                do {
                    cx = (FastMath.nextRandomFloat() - 0.5f) * (mapHalf * 2f - 8f);
                    cz = (FastMath.nextRandomFloat() - 0.5f) * (mapHalf * 2f - 8f);
                } while (Math.abs(cx) < 6f && Math.abs(cz) < 6f); // не слишком близко к центру
                spawnCactusAt(space, cactMat, spineMat, cx, cz);
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

            Material cloudMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            cloudMat.setColor("Color", new ColorRGBA(1f, 1f, 1f, 0.55f));
            cloudMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);

            Random rng = new Random(42);
            for (int i = 0; i < 25; i++) {
                Node cloud = new Node("Cloud" + i);
                float baseRadius = 4f + rng.nextFloat() * 8f;
                // основное тело
                Geometry main = new Geometry("CloudMain", new Sphere(8, 10, baseRadius));
                main.setMaterial(cloudMat);
                cloud.attachChild(main);
                // дополнительный шарик для объёма
                Geometry second = new Geometry("CloudSecond", new Sphere(6, 8, baseRadius * 0.7f));
                second.setLocalTranslation(baseRadius * 0.8f, 0, 0);
                second.setMaterial(cloudMat);
                cloud.attachChild(second);
                // ещё один
                Geometry third = new Geometry("CloudThird", new Sphere(5, 7, baseRadius * 0.5f));
                third.setLocalTranslation(-baseRadius * 0.6f, baseRadius * 0.2f, 0);
                third.setMaterial(cloudMat);
                cloud.attachChild(third);

                cloud.setLocalTranslation(
                        (rng.nextFloat() - 0.5f) * mapHalf * 3f,
                        14f + rng.nextFloat() * 10f,
                        (rng.nextFloat() - 0.5f) * mapHalf * 3f
                );
                cloudNode.attachChild(cloud);
            }
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

                ColorRGBA snakeColor = playerColors.getOrDefault(
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
            if (foodItems.size() >= MAX_FOOD) return;
            boolean bad = foodItems.stream().filter(f->f.bad&&!f.isDebris).count()<BAD_FOOD && FastMath.nextRandomFloat()<0.25f;
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
            if (foodItems.stream().filter(f->!f.isDebris).count()>=MAX_FOOD) return;
            boolean bad = foodItems.stream().filter(f->f.bad&&!f.isDebris).count()<BAD_FOOD && FastMath.nextRandomFloat()<0.25f;
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

            // Счета игроков – левый верхний угол
            huds.clear();
            for (int i = 0; i < allPlayers.size(); i++) {
                BitmapText hudLine = new BitmapText(font);
                hudLine.setSize(18);
                hudLine.setColor(SNAKE_COLORS[i % SNAKE_COLORS.length]);
                hudLine.setLocalTranslation(14, H - 38 - i * 30, 0);
                guiNode.attachChild(hudLine);
                huds.add(hudLine);
            }

            // Центральное сообщение (появляется при событиях)
            centerMsg = new BitmapText(font);
            centerMsg.setSize(38);
            centerMsg.setColor(new ColorRGBA(1f, 1f, 0.2f, 0f));
            guiNode.attachChild(centerMsg);

            // Индикатор рывка (снизу справа)
            dashCooldownText = new BitmapText(font);
            dashCooldownText.setSize(16);
            dashCooldownText.setColor(ACCENT2);
            dashCooldownText.setText("РЫВОК: ГОТОВ [SHIFT]");
            dashCooldownText.setLocalTranslation(W - 260f, 40f, 0);
            guiNode.attachChild(dashCooldownText);
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
                if (gameOver || myIndex>=snakes.size()) return;
                SnakePlayer me = snakes.get(myIndex);
                if ("Left".equals(n))  me.setTurnLeft(p);
                if ("Right".equals(n)) me.setTurnRight(p);
            }, "Left", "Right");

            inputManager.addMapping("Forward", new KeyTrigger(KeyInput.KEY_W));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (gameOver || myIndex>=snakes.size()) return;
                snakes.get(myIndex).setMoving(p);
            }, "Forward");

            // Рывок
            inputManager.addMapping("Dash", new KeyTrigger(KeyInput.KEY_LSHIFT), new KeyTrigger(KeyInput.KEY_RSHIFT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                if (!p || gameOver || myIndex>=snakes.size()) return;
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
                    if (!evt.isPressed()) return;
                    int code = evt.getKeyCode();

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

            // В мультиплеере (хосту и клиенту) только показать/скрыть меню, без настоящей паузы
            if (!solo) {
                boolean menuVisible = (pauseNode != null && pauseNode.getCullHint() != Spatial.CullHint.Always);
                if (menuVisible) {
                    pauseNode.setCullHint(Spatial.CullHint.Always);
                    inputManager.setCursorVisible(false);
                } else {
                    if (pauseNode == null) buildPauseUI();
                    pauseNode.setCullHint(Spatial.CullHint.Inherit);
                    inputManager.setCursorVisible(true);
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
                if (pauseNode != null) pauseNode.setCullHint(Spatial.CullHint.Always);
            }
        }

        private void buildPauseUI() {
            float W = cam.getWidth(), H = cam.getHeight();
            BitmapFont font = loadFont(assetManager);
            pauseNode = new Node("PauseUI");

            Box dimBox = new Box(W/2f, H/2f, 0.1f);
            Geometry dimGeo = new Geometry("PauseDim", dimBox);
            Material dimMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            dimMat.setColor("Color", new ColorRGBA(0,0,0,0.55f));
            dimMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            dimGeo.setMaterial(dimMat); dimGeo.setLocalTranslation(W/2f, H/2f, 7f);
            pauseNode.attachChild(dimGeo);

            Box cardBox = new Box(280f, 220f, 0.5f);
            Geometry cardGeo = new Geometry("PauseCard", cardBox);
            Material cardMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            cardMat.setColor("Color", BG_CARD);
            cardMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            cardGeo.setMaterial(cardMat); cardGeo.setLocalTranslation(W/2f, H/2f, 7.5f);
            pauseNode.attachChild(cardGeo);

            BitmapText pauseTitle = new BitmapText(font);
            pauseTitle.setSize(36); pauseTitle.setText("ПАУЗА"); pauseTitle.setColor(ACCENT2);
            pauseTitle.setLocalTranslation(W/2f - pauseTitle.getLineWidth()/2, H/2f + 170, 8f);
            pauseNode.attachChild(pauseTitle);

            pauseResumeBtn = new MenuButton("ПРОДОЛЖИТЬ", W/2f, H/2f + 80f, 240f, 48f,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT, assetManager, pauseNode, 8f);
            pauseMenuBtn = new MenuButton("В МЕНЮ", W/2f, H/2f - 10f, 240f, 48f,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, DANGER, assetManager, pauseNode, 8f);

            // Если хост мультиплеера — кнопка вернуть в лобби
            if (!solo && isHost) {
                MenuButton lobbyBtn = new MenuButton("ВЕРНУТЬ В ЛОББИ", W/2f, H/2f - 100f, 240f, 48f,
                        BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT3, assetManager, pauseNode, 8f);
                pauseNode.setUserData("hasLobbyBtn", true);
            }

            guiNode.attachChild(pauseNode);
            pauseNode.setCullHint(Spatial.CullHint.Always);

            // Мышь для паузы
            if (!inputManager.hasMapping("Pause_Mouse")) {
                inputManager.addMapping("Pause_Mouse", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
                inputManager.addMapping("Pause_MouseMove",
                        new MouseAxisTrigger(MouseInput.AXIS_X, false), new MouseAxisTrigger(MouseInput.AXIS_X, true),
                        new MouseAxisTrigger(MouseInput.AXIS_Y, false), new MouseAxisTrigger(MouseInput.AXIS_Y, true));
            }
            inputManager.addListener((AnalogListener)(n,v,t) -> {
                if (!pauseActive) return;
                com.jme3.math.Vector2f mp = inputManager.getCursorPosition();
                float mx = mp.x, my = mp.y;
                if (pauseResumeBtn != null) pauseResumeBtn.updateHover(mx, my);
                if (pauseMenuBtn != null)   pauseMenuBtn.updateHover(mx, my);
            }, "Pause_MouseMove");
            inputManager.addListener((ActionListener)(n2,p2,t2) -> {
                if (!pauseActive) return;
                com.jme3.math.Vector2f mp = inputManager.getCursorPosition();
                float mx = mp.x, my = mp.y;
                if (p2) {
                    if (pauseResumeBtn != null) pauseResumeBtn.onPress(mx, my);
                    if (pauseMenuBtn != null)   pauseMenuBtn.onPress(mx, my);
                } else {
                    if (pauseResumeBtn != null && pauseResumeBtn.onRelease(mx, my)) {
                        togglePause();
                    } else if (pauseMenuBtn != null && pauseMenuBtn.onRelease(mx, my)) {
                        backToMenu();
                    }
                }
            }, "Pause_Mouse");
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
                    if (isHost) {
                        InetSocketAddress sender = new InetSocketAddress(pkt.getAddress(), pkt.getPort());
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
                                // Хост корректирует только если расхождение > порога
                                if (sp.getHeadPos().distance(new Vector3f(x, y, z)) > 0.8f) {
                                    sp.applyNetState(x, y, z, angle, score, len, dead);
                                }
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
                case "FOOD":
                    if (p.length>=6) addFoodWithData(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),"1".equals(p[4]),"1".equals(p[5]));
                    else if (p.length>=5) addFoodWithData(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),"1".equals(p[4]),false);
                    break;
                case "DEBRIS":
                    if (p.length>=4) addFoodWithData(Integer.parseInt(p[1]),Float.parseFloat(p[2]),Float.parseFloat(p[3]),false,true);
                    break;
                case "EAT":
                    if (p.length>=2) removeFoodById(Integer.parseInt(p[1])); break;
                case "WIN":
                    if (p.length>=2) { showCenter(p[1]+" ПОБЕДИЛ!", new ColorRGBA(1f,0.85f,0.1f,1f)); gameOver=true; exitTimer=8f; } break;
                case "BACK_LOBBY":
                    app.enqueue(this::backToMenu); break;
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
                    if (p.length >= 5) {
                        int snakeIdx = Integer.parseInt(p[1]);
                        float fx = Float.parseFloat(p[2]);
                        float fy = Float.parseFloat(p[3]);
                        float fz = Float.parseFloat(p[4]);
                        if (snakeIdx >= 0 && snakeIdx < snakes.size() && !snakes.get(snakeIdx).isDead()) {
                            // Найдем фрагмент, который висит в данных кактуса с такими координатами
                            Vector3f fragPos = new Vector3f(fx, fy, fz);
                            for (CactusData cd : cacti) {
                                for (int fi = cd.fragments.size() - 1; fi >= 0; fi--) {
                                    Geometry fragGeo = cd.fragments.get(fi);
                                    if (fragGeo.getWorldTranslation().distance(fragPos) < 0.3f) {
                                        // Нашли, приклеиваем к удалённой змее
                                        Node fragNode = fragGeo.getParent();
                                        if (fragNode != null) {
                                            // убрать физику
                                            RigidBodyControl fp = fragNode.getControl(RigidBodyControl.class);
                                            if (fp != null && bulletAppState != null) {
                                                bulletAppState.getPhysicsSpace().remove(fp);
                                                fragNode.removeControl(fp);
                                            }
                                            fragNode.removeFromParent();
                                            snakes.get(snakeIdx).attachCactusFragment(fragNode, fragPos);
                                        }
                                        cd.fragments.remove(fi);
                                        cd.fragmentTimers.remove(fi);
                                        break;
                                    }
                                }
                            }
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
												inputManager.setCursorVisible(true);
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
                    if (p.length>=5) {
                        int cid=Integer.parseInt(p[1]);
                        float cx=Float.parseFloat(p[2]),cy=Float.parseFloat(p[3]),cz2=Float.parseFloat(p[4]);
                        for (BlackCube bc:blackCubes) {
                            if (bc.id==cid&&bc.active) {
                                if (bc.phy!=null) bc.phy.setPhysicsLocation(new Vector3f(cx,cy,cz2));
                                bc.geo.setLocalTranslation(cx,cy,cz2); break;
                            }
                        }
                    } break;
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

                                // Создаём обломки с колючками
                                Material fragMat = litMat(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
                                Material spineMat = litMat(assetManager, new ColorRGBA(0.86f,0.82f,0.66f,1f));
                                Vector3f cpos = cd.trunkGeo.getWorldTranslation(); // можно взять сохранённую позицию
                                for (int fi = 0; fi < 4; fi++) {
                                    float fsize = 0.15f + FastMath.nextRandomFloat() * 0.2f;
                                    Node frag = new Node("CactFragNode" + fi);
                                    Geometry core = new Geometry("CactFrag" + fi, new Box(fsize, fsize, fsize));
                                    core.setMaterial(fragMat);
                                    frag.attachChild(core);
                                    frag.setLocalTranslation(cpos.add(
                                            (FastMath.nextRandomFloat() - 0.5f) * 0.5f,
                                            FastMath.nextRandomFloat() * 0.8f,
                                            (FastMath.nextRandomFloat() - 0.5f) * 0.5f));
                                    wallNode.attachChild(frag);
                                    RigidBodyControl fp = new RigidBodyControl(
                                            new BoxCollisionShape(new Vector3f(fsize, fsize, fsize)), 0.5f);
                                    fp.setLinearVelocity(new Vector3f(
                                            (FastMath.nextRandomFloat() - 0.5f) * 6f,
                                            2f + FastMath.nextRandomFloat() * 3f,
                                            (FastMath.nextRandomFloat() - 0.5f) * 6f));
                                    frag.addControl(fp);
                                    bulletAppState.getPhysicsSpace().add(fp);

                                    for (int sj = 0; sj < 4; sj++) {
                                        Geometry spike = new Geometry("FragSpike" + fi + "_" + sj, new Box(0.02f, 0.02f, 0.12f));
                                        spike.setMaterial(spineMat);
                                        float a = sj * FastMath.HALF_PI + FastMath.nextRandomFloat() * 0.2f;
                                        spike.setLocalRotation(new Quaternion().fromAngleAxis(a, Vector3f.UNIT_Y));
                                        spike.setLocalTranslation(FastMath.cos(a) * fsize, 0f, FastMath.sin(a) * fsize);
                                        frag.attachChild(spike);
                                    }
                                    cd.fragments.add(core);
                                    cd.fragmentTimers.add(CactusData.FRAGMENT_LIFETIME);
                                }
                                // Иголки cd.spines остаются висеть в воздухе — их не трогаем
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
                case "WATER":
                    if (p.length>=4) {
                        float wx=Float.parseFloat(p[1]),wz=Float.parseFloat(p[2]),wr=Float.parseFloat(p[3]);
                        addWaterPuddle(wx, wz, wr);
                    } break;
            }
        }

        private void sendNet(String msg) {
            if (socket==null) return;
            byte[] b = msg.getBytes(StandardCharsets.UTF_8);
            if (isHost) {
                for (InetSocketAddress c:clients) {
                    try { socket.send(new DatagramPacket(b,b.length,c)); } catch (Exception ignore) {}
                }
            } else if (hostAddress!=null) {
                try { socket.send(new DatagramPacket(b,b.length,new InetSocketAddress(hostAddress,hostPort))); }
                catch (Exception ignore) {}
            }
        }

        // ── Ивент 1: Шариковый дождь ──────────────────────────────────────
        private void startBallRainEvent() {
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
            if (mapIndex == 1) return;
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
                    if (rainSound!=null) { rainSound.stop(); rootNode.detachChild(rainSound); rainSound=null; }
                    showCenter("Дождь закончился. Лужи медленно высыхают.", new ColorRGBA(0.7f,0.8f,1f,1f));
                    waterSpeedMultiplier = 1.0f;
                    if (solo || isHost) nextEventTimer = 25f + eventRng.nextFloat() * 45f;
                } else {
                    if (TurboSnake.particlesEnabled) {
                        for (int i=0;i<8;i++) {
                            float rx = (FastMath.nextRandomFloat()-0.5f)*mapHalf*2f;
                            float rz = (FastMath.nextRandomFloat()-0.5f)*mapHalf*2f;
                            spawnRainDrop(rx, rz);
                        }
                    }
                    if (solo || isHost) {
                        if ((int)(weatherRainTimer*10) % 30 == 0) {
                            float px = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*mapHalf*1.8f,-mapHalf+3f,mapHalf-3f);
                            float pz = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*mapHalf*1.8f,-mapHalf+3f,mapHalf-3f);
                            float pr = 1.5f + FastMath.nextRandomFloat()*2f;
                            addWaterPuddle(px, pz, pr);
                            if (!solo) sendNet("WATER|"+px+"|"+pz+"|"+pr);
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
                        wp.geo.setLocalScale(wp.size * 1.15f * ripple, 0.01f, wp.size * 0.85f / ripple);
                    }
                } else {
                    wp.size -= tpf * 0.12f;
                    if (wp.size <= 0f) {
                        waterNode.detachChild(wp.geo);
                        waterPuddles.remove(i);
                        continue;
                    }
                    wp.geo.setLocalScale(wp.size * 1.15f, 0.01f, wp.size * 0.85f);
                    float alpha = Math.min(0.7f, wp.size / wp.maxSize * 0.7f);
                    wp.geo.getMaterial().setColor("Color", new ColorRGBA(0.16f,0.20f,0.24f,alpha));
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
        }

        private void addWaterPuddle(float x, float z, float radius) {
            for (WaterPuddle wp : waterPuddles) {
                if (Math.abs(wp.x - x) < 2f && Math.abs(wp.z - z) < 2f) return;
            }
            Geometry geo = new Geometry("Puddle", new Sphere(16, 16, 1f));
            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", new ColorRGBA(0.16f, 0.20f, 0.24f, 0.58f));
            mat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            geo.setMaterial(mat);

            // Устанавливаем высоту в зависимости от карты
            float groundY = getSurfaceHeight(x, z);   // используем свой метод рельефа
            geo.setLocalTranslation(x, groundY + 0.1f, z);   // чуть выше земли

            geo.setLocalScale(0.12f, 0.008f, 0.09f);
            waterNode.attachChild(geo);
            waterPuddles.add(new WaterPuddle(geo, x, z, radius));
        }

        // ── ИВЕНТ 3: Ледяная арена ─────────────────────────────
        private void startFrozenArenaEvent() {
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

        private void spawnBlackCube(int id, float x, float z) {
            for (BlackCube bc:blackCubes) if (bc.id==id) return;
            float side = 0.7f;
            Geometry geo = new Geometry("BlackCube_"+id, new Box(side,side,side));
            Material mat = litMat(assetManager, new ColorRGBA(0.05f,0.05f,0.07f,1f));
            geo.setMaterial(mat);
            geo.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);
            geo.setLocalTranslation(x, side+0.2f, z);
            cubeNode.attachChild(geo);

            RigidBodyControl phy = new RigidBodyControl(new BoxCollisionShape(new Vector3f(side,side,side)), 15f);
            phy.setFriction(1.5f); phy.setRestitution(0.25f);
            phy.setAngularDamping(0.60f); phy.setLinearDamping(0.80f);
            geo.addControl(phy); bulletAppState.getPhysicsSpace().add(phy);

            blackCubes.add(new BlackCube(id, geo, phy));

            if (solo || isHost) {
                bulletAppState.getPhysicsSpace().add(phy);
            }
        }

        /** Кубы ВСЕГДА ищут ближайшую живую змейку и атакуют */
        private void updateBlackCubes(float tpf) {
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
            if (shrinkAmt <= 0) { playSound(chitSound); return; }
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
            playSound(chitSound);
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
            // Пауза — замораживаем всё
            if (pauseActive && solo) return;

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
            gameTimerText.setText(String.format("%d:%02d", mins, secs));

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

            if (centerMsgTimer>0) {
                centerMsgTimer -= tpf;
                if (centerMsgTimer<=0) centerMsg.setColor(new ColorRGBA(1f,1f,0.2f,0f));
                else {
                    float a = Math.min(1f, centerMsgTimer);
                    ColorRGBA c = centerMsg.getColor().clone(); c.a=a; centerMsg.setColor(c);
                }
            }

            if (gameOver && !gameoverUIActive) {
                // Определяем победителя
                String winner = "НИКТО";
                for (SnakePlayer s : snakes) if (!s.isDead()) { winner = s.getName(); break; }
                showGameoverOverlay(winner);
            }

            // Спавн еды
            long regularFood = foodItems.stream().filter(f->!f.isDebris).count();
            if (regularFood<MAX_FOOD) { if (solo) addOneFood(); else if (isHost) hostAddAndBroadcastFood(); }
            if (isHost) checkWinCondition();

            // Обновить змей (с замедлением от воды и рывком)
            float effectiveSpeed = SPEED * waterSpeedMultiplier * frozenSpeedMult;
            if (dashTimer > 0f && myIndex < snakes.size() && !snakes.get(myIndex).isDead()) {
                // Рывок применяется только к локальному игроку
                for (int i = 0; i < snakes.size(); i++) {
                    float spd = (i == myIndex) ? effectiveSpeed * DASH_SPEED_MULT : effectiveSpeed;
                    if (!snakes.get(i).isDead()) snakes.get(i).update(tpf, spd, TURN_SPEED, SEG_SPACING);
                }
            } else {
                for (SnakePlayer s : snakes) if (!s.isDead()) s.update(tpf, effectiveSpeed, TURN_SPEED, SEG_SPACING);
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
                netSendTimer += tpf;
                if (netSendTimer>=NET_SEND_INTERVAL && myIndex<snakes.size()) {
                    netSendTimer=0;
                    SnakePlayer me = snakes.get(myIndex);
                    Vector3f h = me.getHeadPos();
                    sendNet("INPUT|"+myIndex+"|"+(me.isTurnLeft()?1:0)+"|"+(me.isTurnRight()?1:0)+"|"+(me.isMoving()?1:0));
                    sendNet("STATE|"+myIndex+"|"+h.x+"|"+h.y+"|"+h.z+"|"+me.getHeadingAngle()
                            +"|"+me.getScore()+"|"+me.getLength()+"|"+(me.isDead()?1:0));
                }
                if (isHost) {
                    cubeNetTimer += tpf;
                    if (cubeNetTimer>=CUBE_NET_INTERVAL) {
                        cubeNetTimer=0f;
                        for (BlackCube bc:blackCubes) {
                            if (!bc.active) continue;
                            Vector3f cp = bc.geo.getWorldTranslation();
                            sendNet("CUBE_STATE|"+bc.id+"|"+cp.x+"|"+cp.y+"|"+cp.z);
                        }
                    }
                }
            }

            updateCamera(); updateHUD();
            for (SnakePlayer sp:snakes) sp.updateNameTag(cam);
            updateCactusRespawns(tpf);
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

        private void refreshGameGuiLayout() {

            float W = cam.getWidth();
            float H = cam.getHeight();

            // Таймер сверху по центру
            if (gameTimerText != null) {
                gameTimerText.setLocalTranslation(
                        W / 2f - gameTimerText.getLineWidth() / 2f,
                        H - 22f,
                        0f
                );
            }

            // Счёт игроков слева сверху
            if (huds != null) {
                for (int i = 0; i < huds.size(); i++) {
                    BitmapText hudLine = huds.get(i);

                    if (hudLine != null) {
                        hudLine.setLocalTranslation(
                                14f,
                                H - 38f - i * 30f,
                                0f
                        );
                    }
                }
            }

            // Центральное сообщение
            if (centerMsg != null) {
                centerMsg.setLocalTranslation(
                        W / 2f - centerMsg.getLineWidth() / 2f,
                        H / 2f,
                        0f
                );
            }

            // Индикатор рывка снизу справа
            if (dashCooldownText != null) {
                dashCooldownText.setLocalTranslation(
                        W - 260f,
                        40f,
                        0f
                );
            }
        }

        private void updatePitsVisualOnly(float tpf) {
            if (mapIndex != 2) return;
            for (PitData pit : pits) {
                pit.stateTimer -= tpf;
                float progress = 0f;
                switch (pit.state) {
                    case RETRACTED:  progress = 0f; break;
                    case EXTENDING:  progress = 1f - Math.max(0f, pit.stateTimer / PIT_EXTENDING_DURATION); break;
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
                checkFoodFor(s);
                if (mapIndex == 1) checkCactusCollisions(s, i, tpf);
                if (frozenArenaActive) checkIceSpikeCollisions();
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
                                Node fragNode = frag.getParent();
                                if (fragNode != null) {
                                    RigidBodyControl fp = fragNode.getControl(RigidBodyControl.class);
                                    if (fp != null) {
                                        bulletAppState.getPhysicsSpace().remove(fp);
                                        fragNode.removeControl(fp);
                                    }
                                    fragNode.removeFromParent();
                                    me.attachCactusFragment(fragNode, frag.getWorldTranslation());
                                }
                                // Удаляем из данных кактуса
                                cd.fragments.remove(fi);
                                cd.fragmentTimers.remove(fi);
                                // Рассылаем всем клиентам
                                if (!solo) {
                                    Vector3f fragWorld = frag.getWorldTranslation();
                                    sendNet("CACT_STICK|" + playerIndex + "|" + fragWorld.x + "|" + fragWorld.y + "|" + fragWorld.z);
                                }
                            }
                        }
                    }
                    continue;
                }

                // Кактус ещё стоит: проверка столкновения головой
                Vector3f cpos = cd.trunkGeo.getWorldTranslation();
                if (h.distance(cpos) < 1.2f) {
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
                    for (Geometry spine : cd.spines) {
                        if (spine.getParent() != null) {
                            spine.removeFromParent();
                        }
                    }
                    cd.spines.clear();

                    // Иголки cd.spines остаются на месте — ничего не делаем

                    // Создаём обломки с колючками
                    Material fragMat = litMat(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
                    Material fragSpineMat = litMat(assetManager, new ColorRGBA(0.86f,0.82f,0.66f,1f));
                    for (int fi = 0; fi < 4; fi++) {
                        float fsize = 0.15f + FastMath.nextRandomFloat() * 0.2f;
                        Node frag = new Node("CactFragNode"+fi);
                        Geometry core = new Geometry("CactFrag"+fi, new Box(fsize,fsize,fsize));
                        core.setMaterial(fragMat);
                        frag.attachChild(core);
                        frag.setLocalTranslation(cpos.add(
                                (FastMath.nextRandomFloat()-0.5f)*0.5f,
                                FastMath.nextRandomFloat()*0.8f,
                                (FastMath.nextRandomFloat()-0.5f)*0.5f));
                        wallNode.attachChild(frag);
                        RigidBodyControl fp = new RigidBodyControl(
                                new BoxCollisionShape(new Vector3f(fsize,fsize,fsize)), 0.5f);
                        fp.setLinearVelocity(new Vector3f(
                                (FastMath.nextRandomFloat()-0.5f)*6f, 2f+FastMath.nextRandomFloat()*3f,
                                (FastMath.nextRandomFloat()-0.5f)*6f));
                        frag.addControl(fp);
                        bulletAppState.getPhysicsSpace().add(fp);

                        // Колючки на обломке
                        for (int sj = 0; sj < 4; sj++) {
                            Geometry spike = new Geometry("FragSpike"+fi+"_"+sj, new Box(0.02f,0.02f,0.12f));
                            spike.setMaterial(fragSpineMat);
                            float a = sj * FastMath.HALF_PI + FastMath.nextRandomFloat()*0.2f;
                            spike.setLocalRotation(new Quaternion().fromAngleAxis(a, Vector3f.UNIT_Y));
                            spike.setLocalTranslation(FastMath.cos(a)*fsize, 0f, FastMath.sin(a)*fsize);
                            frag.attachChild(spike);
                        }
                        cd.fragments.add(core);
                        cd.fragmentTimers.add(CactusData.FRAGMENT_LIFETIME);
                    }

                    if (!solo) sendNet("CACT_HIT|" + cd.origX + "|" + cd.origZ);
                }
            }
        }

        private void checkFoodFor(SnakePlayer snake) {
            Vector3f h = snake.getHeadPos();
            for (int i=foodItems.size()-1;i>=0;i--) {
                FoodItem fi = foodItems.get(i);
                float dist = h.distance(fi.geo.getWorldTranslation());
                float eatRadius = SEG_SPACING*0.75f+(fi.isDebris?0.35f:0.5f);
                if (dist<eatRadius) {
                    sendNet("EAT|"+fi.id);
                    boolean bad=fi.bad, debris=fi.isDebris;
                    removeFood(fi);
                    if (bad) { snake.shrink(); playSound(mmmSound); }
                    else { snake.grow(assetManager); snake.addScore(debris?5:10); playEatSound(); }
                }
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
            for (SnakePlayer s:snakes) if (!s.isDead()) alive.add(s);
            if (alive.size()<=1) {
                gameOver=true; exitTimer=8f;
                String winner = alive.isEmpty() ? "НИКТО" : alive.get(0).getName();
                showCenter(winner+" ПОБЕДИЛ!", new ColorRGBA(1f,0.85f,0.1f,1f));
                if (isHost||solo) sendNet("WIN|"+winner);
            }
        }

        private void updateDashHUD() {
            if (dashCooldownText == null) return;
            if (dashTimer > 0f) {
                dashCooldownText.setText("⚡ РЫВОК АКТИВЕН ⚡");
                dashCooldownText.setColor(new ColorRGBA(1f,0.9f,0.1f,1f));
            } else if (dashCooldown > 0f) {
                int pct = (int)(dashCooldown / DASH_COOLDOWN_MAX * 100);
                dashCooldownText.setText("РЫВОК: " + pct + "% [SHIFT]");
                dashCooldownText.setColor(TEXT_DIM);
            } else {
                dashCooldownText.setText("РЫВОК: ГОТОВ [SHIFT]");
                dashCooldownText.setColor(ACCENT2);
            }
        }

        private void updateCamera() {
            SnakePlayer target;
            if (spectating) target = snakes.get(spectateTarget);
            else if (myIndex<snakes.size()) target = snakes.get(myIndex);
            else return;
            Vector3f head = target.getHeadPos();
            Vector3f dir  = target.getDirection();
            cam.setLocation(head.add(dir.negate().mult(12f)).add(0,6f,0));
            cam.lookAt(head.add(dir.mult(4f)), Vector3f.UNIT_Y);
        }

        private void moveClouds(float tpf) {
            for (Spatial s:cloudNode.getChildren()) {
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
            skyMat.getAdditionalRenderState().setFaceCullMode(RenderState.FaceCullMode.Front);
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
            if (snakes.shadowsEnabled) {
                gameShadowRenderer = new DirectionalLightShadowRenderer(assetManager, 2048, 3);
                gameShadowRenderer.setLight(sunLight);
                gameShadowRenderer.setShadowIntensity(0.72f);
                gameShadowRenderer.setEdgeFilteringMode(EdgeFilteringMode.PCFPOISSON);
                gameShadowRenderer.setShadowZExtend(120f);
                gameShadowRenderer.setShadowZFadeLength(15f);
                app.getViewPort().addProcessor(gameShadowRenderer);
            } else {
                gameShadowRenderer = null;
            }

            // ── 7. Post-processing ──
            boolean needFpp = snakes.bloomEnabled || snakes.fogEnabled;
            if (needFpp) {
                gameFpp = new FilterPostProcessor(assetManager);

                // Bloom
                if (snakes.bloomEnabled) {
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
                if (TurboSnake.fogEnabled) {
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
            dayNightTime = (dayNightTime + tpf) % TOTAL_CYCLE;
            if (dayNightTime < DAY_DURATION) {
                updateDayPhase(dayNightTime / DAY_DURATION);
            } else {
                updateNightPhase((dayNightTime - DAY_DURATION) / NIGHT_DURATION);
            }
            // Купол неба следует за камерой
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
            for (int i=0;i<snakes.size()&&i<huds.size();i++) {
                SnakePlayer s = snakes.get(i);
                String extra = (s == (myIndex<snakes.size()?snakes.get(myIndex):null) && waterSpeedMultiplier<1f) ? " 💧" : "";
                huds.get(i).setText(s.getName()+(s.isDead()?" ☠":"")+"  ★"+s.getScore()+"  L:"+s.getLength()+extra);
            }
        }

        private void backToMenu() {
            if (rainSound!=null) { rainSound.stop(); rootNode.detachChild(rainSound); }
            netRunning.set(false);
            if (socket!=null) socket.close();
            for (SnakePlayer sp:snakes) sp.cleanup(guiNode);
            for (BlackCube bc:blackCubes) {
                if (bc.phy!=null) { bc.phy.setEnabled(false); bulletAppState.getPhysicsSpace().remove(bc.phy); }
            }
            blackCubes.clear();
            // Очистка освещения и пост-обработки
            if (gameShadowRenderer != null) { app.getViewPort().removeProcessor(gameShadowRenderer); gameShadowRenderer = null; }
            if (gameFpp != null) { app.getViewPort().removeProcessor(gameFpp); gameFpp = null; }
            if (sunLight != null) { rootNode.removeLight(sunLight); sunLight = null; }
            if (ambientLight != null) { rootNode.removeLight(ambientLight); ambientLight = null; }
            rootNode.detachAllChildren(); guiNode.detachAllChildren();
            inputManager.clearMappings();
            inputManager.setCursorVisible(true);  // восстанавливаем курсор при выходе в меню
            stateManager.detach(bulletAppState); stateManager.detach(this);
            stateManager.attach(new MainMenuState());

            if (sandstormOverlayGeo != null) {
                guiNode.detachChild(sandstormOverlayGeo);
                sandstormOverlayGeo = null;
            }
        }

        @Override
        public void cleanup() {
            cleanupFrozenArena();
            super.cleanup();
            netRunning.set(false);
            if (socket!=null&&!socket.isClosed()) socket.close();
            if (gameShadowRenderer != null) { app.getViewPort().removeProcessor(gameShadowRenderer); gameShadowRenderer = null; }
            if (gameFpp != null) { app.getViewPort().removeProcessor(gameFpp); gameFpp = null; }
            if (sunLight != null) { rootNode.removeLight(sunLight); sunLight = null; }
            if (ambientLight != null) { rootNode.removeLight(ambientLight); ambientLight = null; }
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
}