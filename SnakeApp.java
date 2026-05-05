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
import com.jme3.renderer.ViewPort;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.system.AppSettings;
import com.jme3.ui.Picture;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public class SnakeApp extends SimpleApplication {

    public static final int BROADCAST_PORT = 45678;
    public static final int GAME_PORT      = 45679;

    static float effectVolume = 1.0f;
    static float musicVolume  = 0.5f;
    static int selectedMap = 0;
    static ColorRGBA selectedSnakeColor = new ColorRGBA(0.15f, 0.9f, 0.3f, 1f);
    static String savedNickname = getSystemUsername();
    // Настройки графики
    static int graphicsShadowQuality = 0; // 0 Низкие, 1 Средние, 2 Высокие, 3 Ультра
    static boolean graphicsReflections = false; // тяжёлое — отключено по умолчанию

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
            graphicsShadowQuality = Integer.parseInt(p.getProperty("shadowQuality", "0"));
            if (graphicsShadowQuality < 0) graphicsShadowQuality = 0;
            if (graphicsShadowQuality > 3) graphicsShadowQuality = 3;
            graphicsReflections = Boolean.parseBoolean(p.getProperty("reflections", "false"));
        } catch (Exception e) { System.out.println("[Settings] Load error: " + e.getMessage()); }
    }

    static void saveSettings(String nickname) {
        try (java.io.FileOutputStream out = new java.io.FileOutputStream(getSettingsFile())) {
            java.util.Properties p = new java.util.Properties();
            p.setProperty("effectVolume",  String.valueOf(effectVolume));
            p.setProperty("musicVolume",   String.valueOf(musicVolume));
            p.setProperty("nickname",      nickname != null ? nickname : savedNickname);
            p.setProperty("shadowQuality", String.valueOf(graphicsShadowQuality));
            p.setProperty("reflections",   String.valueOf(graphicsReflections)); // Fix #5
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

    // Fix #7: Глобальный менеджер фоновой музыки — один трек на всё время игры
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
        SnakeApp app = new SnakeApp();
        AppSettings s = new AppSettings(true);
        s.setTitle("9999D Snake");
        s.setResolution(1280, 720);
        s.setFrameRate(60);
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

    // Fix #6: HSV→RGB компоненты для цветового пикера
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
        private final Geometry bgGeo, accentGeo;
        private final BitmapText label;
        private final float x, y, w, h;
        private boolean hovered = false, pressed = false;
        private ColorRGBA accentColor;
        private final ColorRGBA bgNormal, bgHover, bgPressed;

        MenuButton(String text, float cx, float cy, float w, float h,
                   ColorRGBA bgNormal, ColorRGBA bgHover, ColorRGBA bgPressed,
                   ColorRGBA textColor, AssetManager am, Node guiNode, float z) {
            this.w = w; this.h = h;
            this.x = cx - w/2f; this.y = cy - h/2f;
            this.accentColor = textColor;
            this.bgNormal = bgNormal;
            this.bgHover = bgHover;
            this.bgPressed = bgPressed;

            float tiltAmount = 25f;
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

            Mesh accentMesh = createSlantedQuad(6f, h - 2f, tiltAmount);
            accentGeo = new Geometry("BtnAccent_" + text, accentMesh);
            Material am2 = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
            ColorRGBA dimAccent = new ColorRGBA(textColor.r * 0.6f, textColor.g * 0.6f, textColor.b * 0.6f, 0.8f);
            am2.setColor("Color", dimAccent);
            am2.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
            accentGeo.setMaterial(am2);
            accentGeo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
            accentGeo.setLocalTranslation(cx - w/2f + 10f, cy, z + 0.3f);
            guiNode.attachChild(accentGeo);

            BitmapFont font = loadFont(am);
            label = new BitmapText(font);
            label.setSize(22);
            label.setText(text);
            label.setColor(textColor);
            guiNode.attachChild(label);
            centerLabel(cx, cy, z);
        }

        private void centerLabel(float cx, float cy, float z) {
            label.setLocalTranslation(cx - label.getLineWidth()/2f, cy + label.getLineHeight()*0.35f, z + 1f);
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
            float f = hovered ? 1f : 0.6f;
            float alpha = hovered ? 1f : 0.8f;
            accentGeo.getMaterial().setColor("Color",
                    new ColorRGBA(accentColor.r * f, accentColor.g * f, accentColor.b * f, alpha));
        }

        void detach(Node guiNode) {
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

        private void setupUI() {
            BitmapFont font = loadFont(assetManager);
            float W = cam.getWidth(), H = cam.getHeight(), cx = W/2f;
            float leftMargin = 180f;

            titleText = new BitmapText(font);
            titleText.setSize(72); titleText.setText("3D SNAKE");
            titleText.setColor(ACCENT);
            titleText.setLocalTranslation(cx - titleText.getLineWidth()/2, H - 45, 1f);
            guiNode.attachChild(titleText);

            subtitleText = new BitmapText(font);
            subtitleText.setSize(18); subtitleText.setText("MULTIPLAYER EDITION");
            subtitleText.setColor(TEXT_DIM);
            subtitleText.setLocalTranslation(cx - subtitleText.getLineWidth()/2, H - 115, 1f);
            guiNode.attachChild(subtitleText);

            float btnW = 300f, btnH = 60f, gap = 75f;
            float startY = H/2f + 50f;

            soloBtn = new MenuButton("ОДИНОЧНАЯ ИГРА", leftMargin, startY, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT3, assetManager, guiNode, 1f);
            // Кнопка «Сетевая игра»
            joinBtn = new MenuButton("СЕТЕВАЯ ИГРА", leftMargin, startY - gap, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT2, assetManager, guiNode, 1f);
            settingsBtn = new MenuButton("НАСТРОЙКИ", leftMargin, startY - gap*2, btnW, btnH,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, TEXT_DIM, assetManager, guiNode, 1f);

            infoText = new BitmapText(font);
            infoText.setSize(12); infoText.setText("W — движение  |  A/D — поворот  |  Ник — в Настройках");
            infoText.setColor(TEXT_DIM);
            infoText.setLocalTranslation(cx - infoText.getLineWidth()/2, 20, 1f);
            guiNode.attachChild(infoText);

            buildSettingsPanel();
        }

        private void buildSettingsPanel() {
            float W = cam.getWidth(), H = cam.getHeight(), cx = W/2f, cy = H/2f;
            BitmapFont font = loadFont(assetManager);
            settingsPanel = new Node("SettingsPanel");

            // затемнение
            Box dimBox = new Box(W/2f, H/2f, 0.1f);
            Geometry dimGeo = new Geometry("SettingsDim", dimBox);
            Material dimMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            dimMat.setColor("Color", new ColorRGBA(0f,0f,0f,0.72f));
            dimMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            dimGeo.setMaterial(dimMat); dimGeo.setLocalTranslation(cx, cy, 2f);
            settingsPanel.attachChild(dimGeo);

            Box panelBox = new Box(220f, 235f, 0.5f);
            Geometry panelGeo = new Geometry("PanelBg", panelBox);
            Material pm = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            pm.setColor("Color", BG_CARD);
            pm.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            panelGeo.setMaterial(pm); panelGeo.setLocalTranslation(cx, cy, 2.3f);
            settingsPanel.attachChild(panelGeo);

            final float Z = 3.2f;

            // заголовок
            Geometry headerLine = new Geometry("PanelHeaderLine", new Box(220f, 3f, 0.3f));
            headerLine.setMaterial(unshaded(assetManager, ACCENT));
            headerLine.setLocalTranslation(cx, cy+233f, Z);
            settingsPanel.attachChild(headerLine);
            BitmapText header = new BitmapText(font);
            header.setSize(26); header.setText("НАСТРОЙКИ"); header.setColor(ACCENT2);
            header.setLocalTranslation(cx - header.getLineWidth()/2, cy+223f, Z);
            settingsPanel.attachChild(header);

            BitmapText sfxLabel = new BitmapText(font);
            sfxLabel.setName("SfxLabel");
            sfxLabel.setSize(19); sfxLabel.setText("Звуки"); sfxLabel.setColor(TEXT);
            sfxLabel.setLocalTranslation(cx-200, cy+136f, Z);
            settingsPanel.attachChild(sfxLabel);
            sfxVal = new BitmapText(font);
            sfxVal.setSize(19); sfxVal.setColor(ACCENT2);
            sfxVal.setLocalTranslation(cx+148, cy+150f, Z);
            settingsPanel.attachChild(sfxVal);
            sfxSlider = new VolumeSlider(cx, cy+108f, 340f, effectVolume, assetManager, settingsPanel, Z);

            BitmapText musicLabel = new BitmapText(font);
            musicLabel.setName("MusicLabel");
            musicLabel.setSize(19); musicLabel.setText("Музыка"); musicLabel.setColor(TEXT);
            musicLabel.setLocalTranslation(cx-200, cy+64f, Z);
            settingsPanel.attachChild(musicLabel);
            musicVal = new BitmapText(font);
            musicVal.setSize(19); musicVal.setColor(ACCENT);
            musicVal.setLocalTranslation(cx+148, cy+79f, Z);
            settingsPanel.attachChild(musicVal);
            musicSlider = new VolumeSlider(cx, cy+36f, 340f, musicVolume, assetManager, settingsPanel, Z);

            // поле ввода ника
            BitmapText nickLabel = new BitmapText(font);
            nickLabel.setName("NickLabel");
            nickLabel.setSize(14); nickLabel.setText("ИМЯ ИГРОКА");
            nickLabel.setColor(TEXT_DIM);
            nickLabel.setLocalTranslation(cx-155, cy-16f, Z);
            settingsPanel.attachChild(nickLabel);
            Box nickBorder = new Box(155f, 20f, 0.2f);
            Geometry nickBorderGeo = new Geometry("NickBorder", nickBorder);
            nickBorderGeo.setMaterial(unshaded(assetManager, ACCENT2));
            nickBorderGeo.setLocalTranslation(cx, cy-55f, Z-0.1f);
            settingsPanel.attachChild(nickBorderGeo);
            Box nickBg = new Box(153f, 18f, 0.3f);
            Geometry nickBgGeo = new Geometry("NickBg", nickBg);
            nickBgGeo.setMaterial(unshaded(assetManager, new ColorRGBA(0.04f,0.06f,0.14f,1f)));
            nickBgGeo.setLocalTranslation(cx, cy-55f, Z);
            settingsPanel.attachChild(nickBgGeo);
            nicknameText = new BitmapText(font);
            nicknameText.setSize(22); nicknameText.setText(nickname.toString());
            nicknameText.setColor(ACCENT3);
            nicknameText.setLocalTranslation(cx-145, cy-43f, Z+0.5f);
            settingsPanel.attachChild(nicknameText);
            cursorBlink = new BitmapText(font);
            cursorBlink.setSize(22); cursorBlink.setText("|");
            cursorBlink.setColor(ACCENT3);
            settingsPanel.attachChild(cursorBlink);

            settingsClose = new MenuButton("ЗАКРЫТЬ", cx, cy-138f, 180, 44,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, DANGER, assetManager, settingsPanel, Z);

            BitmapText tabMain = new BitmapText(font);
            tabMain.setSize(14); tabMain.setName("SettingsTabMain");
            tabMain.setText("ОСНОВНОЕ");
            tabMain.setColor(ACCENT2);
            tabMain.setLocalTranslation(cx-155, cy+188f, Z);
            settingsPanel.attachChild(tabMain);
            BitmapText tabGfx = new BitmapText(font);
            tabGfx.setSize(14); tabGfx.setName("SettingsTabGfx");
            tabGfx.setText("ГРАФИКА");
            tabGfx.setColor(TEXT_DIM);
            tabGfx.setLocalTranslation(cx-10, cy+188f, Z);
            settingsPanel.attachChild(tabGfx);

            // Настройки графики (вкладка "Графика")
            BitmapText gfxLabel = new BitmapText(font);
            gfxLabel.setSize(13); gfxLabel.setName("GraphicsLabel"); gfxLabel.setText("ГРАФИКА:");
            gfxLabel.setColor(TEXT_DIM);
            gfxLabel.setLocalTranslation(cx-155, cy-152f, Z);
            settingsPanel.attachChild(gfxLabel);
            BitmapText shadowsBtn = new BitmapText(font);
            shadowsBtn.setSize(13); shadowsBtn.setName("ShadowsBtn");
            shadowsBtn.setText("Тени: " + shadowQualityName());
            shadowsBtn.setColor(ACCENT);
            shadowsBtn.setLocalTranslation(cx-155, cy-170f, Z);
            settingsPanel.attachChild(shadowsBtn);

            setSettingsTab(0);
            updateSettingsLabels();
            refreshNick();
            settingsPanel.setCullHint(Spatial.CullHint.Always);
            guiNode.attachChild(settingsPanel);
        }

        private void setSettingsTab(int tab) {
            activeSettingsTab = tab;
            Spatial tabMain = settingsPanel.getChild("SettingsTabMain");
            Spatial tabGfx = settingsPanel.getChild("SettingsTabGfx");
            if (tabMain instanceof BitmapText) ((BitmapText) tabMain).setColor(tab == 0 ? ACCENT2 : TEXT_DIM);
            if (tabGfx instanceof BitmapText) ((BitmapText) tabGfx).setColor(tab == 1 ? ACCENT2 : TEXT_DIM);

            String[] mainOnly = {"SfxLabel", "MusicLabel", "NickLabel", "NickBorder", "NickBg"};
            for (String name : mainOnly) {
                Spatial s = settingsPanel.getChild(name);
                if (s != null) s.setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            }
            String[] gfxOnly = {"GraphicsLabel", "ShadowsBtn"};
            for (String name : gfxOnly) {
                Spatial s = settingsPanel.getChild(name);
                if (s != null) s.setCullHint(tab == 1 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            }
            if (nicknameText != null) nicknameText.setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (cursorBlink != null) cursorBlink.setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (sfxVal != null) sfxVal.setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (musicVal != null) musicVal.setCullHint(tab == 0 ? Spatial.CullHint.Inherit : Spatial.CullHint.Always);
            if (sfxSlider != null) sfxSlider.setVisible(tab == 0);
            if (musicSlider != null) musicSlider.setVisible(tab == 0);
        }

        private void updateSettingsLabels() {
            if (sfxVal   != null) sfxVal.setText(Math.round(effectVolume * 100) + "%");
            if (musicVal != null) musicVal.setText(Math.round(musicVolume * 100) + "%");
            if (sfxSlider   != null) sfxSlider.setValue(effectVolume);
            if (musicSlider != null) musicSlider.setValue(musicVolume);
            MusicManager.setVolume(musicVolume); // Fix #7
        }
        private String shadowQualityName() {
            String[] q = {"Низкие", "Средние", "Высокие", "Ультра"};
            return q[graphicsShadowQuality];
        }

        private void startMenuMusic() {
            // Fix #7: использовать глобальный MusicManager — не пересоздавать звук при возврате в меню
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
                }
            }, "MMouseMove");

            inputManager.addMapping("MClick", new MouseButtonTrigger(MouseInput.BUTTON_LEFT));
            inputManager.addListener((ActionListener)(n,p,t) -> {
                com.jme3.math.Vector2f mp = inputManager.getCursorPosition();
                float mx = mp.x, my = mp.y;
                if (settingsOpen) {
                    if (p) { settingsClose.onPress(mx, my); }
                    else {
                        if (settingsClose.onRelease(mx, my)) {
                            settingsOpen = false; settingsPanel.setCullHint(Spatial.CullHint.Always);
                            saveSettings(nickname.toString());
                        } else if (clickedTab(mx, my)) {
                            // обработано
                        } else if (activeSettingsTab == 0 && sfxSlider.onClick(mx, my)) {
                            effectVolume = sfxSlider.getValue(); updateSettingsLabels();
                        } else if (activeSettingsTab == 0 && musicSlider.onClick(mx, my)) {
                            musicVolume = musicSlider.getValue(); updateSettingsLabels();
                        } else if (activeSettingsTab == 1) {
                            // Клик по качеству теней
                            Spatial shadowBtn = settingsPanel.getChild("ShadowsBtn");
                            if (shadowBtn instanceof BitmapText) {
                                float bx = shadowBtn.getLocalTranslation().x;
                                float by = shadowBtn.getLocalTranslation().y;
                                if (mx>=bx && mx<=bx+180f && my>=by-20f && my<=by+5f) {
                                    graphicsShadowQuality = (graphicsShadowQuality + 1) % 4;
                                    ((BitmapText)shadowBtn).setText("Тени: " + shadowQualityName());
                                    ((BitmapText)shadowBtn).setColor(ACCENT);
                                    saveSettings(nickname.toString());
                                }
                            }
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
            Spatial tabGfx = settingsPanel.getChild("SettingsTabGfx");
            if (tabMain instanceof BitmapText) {
                float x = tabMain.getLocalTranslation().x, y = tabMain.getLocalTranslation().y;
                if (mx >= x && mx <= x + 130f && my >= y - 20f && my <= y + 6f) { setSettingsTab(0); return true; }
            }
            if (tabGfx instanceof BitmapText) {
                float x = tabGfx.getLocalTranslation().x, y = tabGfx.getLocalTranslation().y;
                if (mx >= x && mx <= x + 120f && my >= y - 20f && my <= y + 6f) { setSettingsTab(1); return true; }
            }
            return false;
        }

        private void launch(boolean host, boolean solo) {
            String nick = nickname.length()==0 ? "Player" : nickname.toString();
            savedNickname = nick;
            saveSettings(nick);
            // Fix #7: НЕ останавливаем музыку — MusicManager обеспечивает непрерывное воспроизведение
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

        private BitmapText titleText, statusText;
        private final List<MenuButton> serverButtons = new ArrayList<>();
        private final List<String> serverAddresses = new ArrayList<>();
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
            startScan();
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

            setupInput();
        }

        private void updateServerListGUI() {
            for (MenuButton b : serverButtons) b.detach(guiNode);
            serverButtons.clear();

            float y = cam.getHeight() - 150f;
            for (String addr : serverAddresses) {
                MenuButton btn = new MenuButton(addr, 40f + 140f, y, 280f, 45f,
                        BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT2, assetManager, guiNode, 0f);
                serverButtons.add(btn);
                y -= 50f;
            }
            statusText.setText("Найдено лобби: " + serverAddresses.size());
        }

        private void startScan() {
            if (scanning) return;
            scanning = true;
            serverAddresses.clear();
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
                                String ip = rp.getAddress().getHostAddress() + ":" + resp.split("\\|")[1];
                                synchronized (serverAddresses) {
                                    if (!serverAddresses.contains(ip)) serverAddresses.add(ip);
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
        }

        private void setupInput() {
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
                        joinServer(serverAddresses.get(i));
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

        private DatagramSocket socket;
        private volatile String hostAddress;
        private volatile int hostPort = GAME_PORT;
        private final List<String> players = new CopyOnWriteArrayList<>();
        private final AtomicBoolean searching = new AtomicBoolean(true);
        private final AtomicBoolean gameStarted = new AtomicBoolean(false);
        private Thread netThread;

        private BitmapText notificationText;
        private float notificationAlpha = 0f;
        private float notificationTimer = 0f;

        private BitmapText playersText, startHint, searchAnim;
        private MenuButton[] mapButtons = new MenuButton[3];
        private BitmapText mapLabel;
        private float animTimer = 0f;
        private int animDot = 0;

        private final List<InetSocketAddress> clients = new CopyOnWriteArrayList<>();

        // Fix #13: флаг включения/отключения чёрных кубов
        private boolean cubesEnabled = true;
        private MenuButton cubesToggleBtn;

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

            buildUI();
            setupInput();
            refreshUI();

            if (!isSolo) startNetwork();
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

            // Fix #13: Кнопка включения/отключения чёрных кубов
            cubesToggleBtn = new MenuButton("⬛ КУБЫ: ВКЛ", leftX + 140f, H - 560f, 280f, 44f,
                    BTN_NORMAL, BTN_HOVER, BTN_PRESS, ACCENT, assetManager, guiNode, 0f);

            // Fix #11: HSV-пикер цвета змейки в лобби (совпадает с настройками)
            BitmapText colorTitle = new BitmapText(font);
            colorTitle.setSize(16); colorTitle.setText("ЦВЕТ ВАШЕЙ ЗМЕЙКИ:");
            colorTitle.setColor(TEXT_DIM);
            colorTitle.setLocalTranslation(W - 400f, H - 80f, 0);
            guiNode.attachChild(colorTitle);

            float swX0 = W - 390f, swY0 = H - 110f, swW = 22f, swH = 13f, swGap = 2f;
            float[] huesA = {0f,30f,60f,90f,120f,150f,180f,210f,240f,270f,300f,330f};
            for (int ci = 0; ci < huesA.length; ci++) {
                ColorRGBA sc = hsvToRGBA(huesA[ci]/360f, 0.85f, 0.9f);
                Box swatchBox = new Box(swW/2f, swH/2f, 0.5f);
                Geometry swatch = new Geometry("LobSwH"+ci, swatchBox);
                swatch.setMaterial(unshaded(assetManager, sc));
                swatch.setLocalTranslation(swX0 + ci*(swW+swGap), swY0, 0);
                guiNode.attachChild(swatch);
            }
            float[] huesB = {0f,60f,120f,180f,240f,300f,30f,90f,150f,210f,270f,330f};
            for (int ci = 0; ci < huesB.length; ci++) {
                ColorRGBA sc = hsvToRGBA(huesB[ci]/360f, 0.5f, 1.0f);
                Box swatchBox = new Box(swW/2f, swH/2f, 0.5f);
                Geometry swatch = new Geometry("LobSwP"+ci, swatchBox);
                swatch.setMaterial(unshaded(assetManager, sc));
                swatch.setLocalTranslation(swX0 + ci*(swW+swGap), swY0 - swH - swGap, 0);
                guiNode.attachChild(swatch);
            }
            // Образец текущего цвета
            Box previewBox = new Box(20f, 16f, 0.5f);
            Geometry colorPreview = new Geometry("LobbyColorPreview", previewBox);
            colorPreview.setMaterial(unshaded(assetManager, selectedSnakeColor));
            colorPreview.setLocalTranslation(W - 40f, swY0 - swH/2f, 0);
            guiNode.attachChild(colorPreview);
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

        private void setupInput() {
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
                        if (isHost) broadcastMapSelection();
                        refreshUI();
                    }
                }
                // Fix #13: переключаем чёрные кубы
                if (cubesToggleBtn != null && cubesToggleBtn.isHit(mp.x, mp.y)) {
                    cubesEnabled = !cubesEnabled;
                    cubesToggleBtn.setText("⬛ КУБЫ: " + (cubesEnabled ? "ВКЛ" : "ВЫКЛ"));
                    cubesToggleBtn.setAccentColor(cubesEnabled ? ACCENT : TEXT_DIM);
                }
                // Fix #11: выбор цвета змейки в лобби через HSV-пикер
                float swX0 = cam.getWidth() - 390f, swY0 = cam.getHeight() - 110f;
                float swW = 22f, swH = 13f, swGap = 2f;
                float[] huesA = {0f,30f,60f,90f,120f,150f,180f,210f,240f,270f,300f,330f};
                float[] huesB = {0f,60f,120f,180f,240f,300f,30f,90f,150f,210f,270f,330f};
                boolean lobColorHit = false;
                for (int ci = 0; ci < huesA.length; ci++) {
                    float sx = swX0 + ci*(swW+swGap);
                    if (mp.x>=sx-swW/2 && mp.x<=sx+swW/2 && mp.y>=swY0-swH/2 && mp.y<=swY0+swH/2) {
                        selectedSnakeColor = hsvToRGBA(huesA[ci]/360f, 0.85f, 0.9f);
                        lobColorHit = true;
                    }
                    float sy2 = swY0 - swH - swGap;
                    if (mp.x>=sx-swW/2 && mp.x<=sx+swW/2 && mp.y>=sy2-swH/2 && mp.y<=sy2+swH/2) {
                        selectedSnakeColor = hsvToRGBA(huesB[ci]/360f, 0.5f, 1.0f);
                        lobColorHit = true;
                    }
                }
                if (lobColorHit) {
                    Spatial lp = guiNode.getChild("LobbyColorPreview");
                    if (lp instanceof Geometry) ((Geometry)lp).getMaterial().setColor("Color", selectedSnakeColor);
                    saveSettings(null);
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
                            String reply = "HOST_HERE|" + GAME_PORT;
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
                        String nick = p.length>1 ? p[1] : "Player";
                        if (!players.contains(nick)) players.add(nick);
                        if (!clients.contains(from)) clients.add(from);
                        String notif = "Игрок " + nick + " присоединился";
                        app.enqueue(() -> showNotification(notif));
                        broadcastToAll("NOTIF|" + notif);
                        broadcastLobby();
                        sendToClient("MAP|" + selectedMap, from);
                        app.enqueue(this::refreshUI);
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
            sendToHost("JOIN|" + myNick);
            byte[] buf = new byte[2048];
            while (!gameStarted.get() && searching.get()) {
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
                    if (p.length>1) try { selectedMap = Integer.parseInt(p[1]); } catch (Exception ignore) {}
                    gameStarted.set(true);
                    app.enqueue(this::launchGame);
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
            broadcastToAll("START|" + selectedMap);
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
                    new GameState(myNick, playerList, myIdx, isSolo || playerList.size()==1,
                            isHost, gameSocket, hostAddress, hostPort, clients, selectedMap, cubesEnabled));
        }

        private void backToMenu() {
            searching.set(false);
            if (socket!=null) socket.close();
            guiNode.detachAllChildren();
            inputManager.clearMappings();
            app.getInputManager().setCursorVisible(true);  // Fix #1: was false — курсор пропадал
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
    // ЭКРАН ЗАГРУЗКИ (Fix #14: анимация "Загрузка..." вместо чёрного экрана)
    // =========================================================================
    static class LoadingState extends AbstractAppState {
        private SimpleApplication app;
        private Node guiNode;
        private AssetManager assetManager;
        private InputManager inputManager;
        private Camera cam;

        private final String myNick;
        private final List<String> allPlayers;
        private final int myIndex;
        private final boolean solo, isHost;
        private final DatagramSocket socket;
        private final String hostAddress;
        private final int hostPort;
        private final List<InetSocketAddress> clients;
        private final int mapIndex;
        private final boolean cubesEnabled;

        private BitmapText loadingLabel;
        private float dotTimer = 0f;
        private int dotCount = 0;
        private float startDelay = 0.1f; // небольшой кадр для отрисовки

        public LoadingState(String myNick, List<String> allPlayers, int myIndex, boolean solo,
                            boolean isHost, DatagramSocket socket, String hostAddress, int hostPort,
                            List<InetSocketAddress> clients, int mapIndex, boolean cubesEnabled) {
            this.myNick=myNick; this.allPlayers=allPlayers; this.myIndex=myIndex;
            this.solo=solo; this.isHost=isHost; this.socket=socket;
            this.hostAddress=hostAddress; this.hostPort=hostPort;
            this.clients=clients; this.mapIndex=mapIndex; this.cubesEnabled=cubesEnabled;
        }

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            app = (SimpleApplication) application;
            guiNode = app.getGuiNode(); assetManager = app.getAssetManager();
            inputManager = app.getInputManager(); cam = app.getCamera();
            app.getViewPort().setBackgroundColor(BG);
            app.getInputManager().setCursorVisible(false);
            buildUI();
        }

        private void buildUI() {
            BitmapFont font = loadFont(assetManager);
            float W = cam.getWidth(), H = cam.getHeight();

            // Затемнённый фон
            Box bg = new Box(W/2f, H/2f, 0.1f);
            Geometry bgGeo = new Geometry("LoadBg", bg);
            Material bgMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            bgMat.setColor("Color", BG); bgGeo.setMaterial(bgMat);
            bgGeo.setLocalTranslation(W/2f, H/2f, 0); guiNode.attachChild(bgGeo);

            // Вращающийся индикатор (анимированный пульс через масштаб)
            Geometry spinner = new Geometry("Spinner", new com.jme3.scene.shape.Sphere(8,8,18f));
            Material spMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            spMat.setColor("Color", ACCENT); spinner.setMaterial(spMat);
            spinner.setName("Spinner");
            spinner.setLocalTranslation(W/2f, H/2f + 50f, 0.5f);
            guiNode.attachChild(spinner);

            // Надпись «Загрузка...»
            loadingLabel = new BitmapText(font);
            loadingLabel.setSize(32); loadingLabel.setColor(ACCENT3);
            loadingLabel.setText("Загрузка");
            loadingLabel.setLocalTranslation(W/2f - loadingLabel.getLineWidth()/2f, H/2f - 30f, 1f);
            guiNode.attachChild(loadingLabel);
        }

        @Override
        public void update(float tpf) {
            startDelay -= tpf;
            dotTimer += tpf;

            // Анимация точек
            if (dotTimer > 0.4f) {
                dotTimer = 0f; dotCount = (dotCount + 1) % 4;
                String dots = ".".repeat(dotCount);
                if (loadingLabel != null) loadingLabel.setText("Загрузка" + dots);
            }

            // Анимация вращения spinner через вращение узла
            Spatial sp = guiNode.getChild("Spinner");
            if (sp != null) sp.rotate(0, 0, tpf * 2.5f);

            // После небольшой задержки запускаем игровое состояние
            if (startDelay <= 0f) {
                guiNode.detachAllChildren();
                app.getStateManager().detach(this);
                app.getStateManager().attach(new GameState(myNick, allPlayers, myIndex, solo,
                        isHost, socket, hostAddress, hostPort, clients, mapIndex, cubesEnabled));
            }
        }
    }

    // =========================================================================
    // ИГРОВОЕ СОСТОЯНИЕ (без изменений в части чата, т.к. его там и не было)
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

        // Пауза
        private boolean pauseActive = false;
        private Node pauseNode;
        private MenuButton pauseResumeBtn, pauseMenuBtn;

        // Рывок  (Fix #3: увеличена длительность с 0.18 до 0.55 — рывок теперь ощутимый)
        private float dashCooldown = 0f;
        private static final float DASH_COOLDOWN_MAX = 4f;
        private static final float DASH_SPEED_MULT   = 5.0f;   // немного увеличен множитель
        private static final float DASH_DURATION     = 0.55f;  // было 0.18f
        private float dashTimer = 0f;
        private BitmapText dashCooldownText;

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
        private final boolean cubesEnabled; // Fix #13

        private final List<SnakePlayer> snakes = new ArrayList<>();
        private Node foodNode, wallNode, cloudNode, worldNode, cubeNode;
        private Node pitNode; // ямы со шипами (карта 2)
        // Fix #2: Список кактусов для физики разрушения (карта 1)
        private final List<CactusData> cacti = new ArrayList<>();

        private final List<FoodItem> foodItems = new ArrayList<>();

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

        // ── ИВЕНТ 3: Ледяная арена (Fix #12) ────────────────────────────
        private boolean frozenArenaActive = false;
        private float frozenArenaTimer    = 0f;
        private static final float FROZEN_ARENA_DURATION = 20f;
        private float frozenSpeedMult = 1f; // множитель скорости (0.35 = очень скользко)

        // ── Общий планировщик случайных ивентов (хост/соло) ──────────────
        private float nextEventTimer = 0f;  // время до следующего ивента
        private final Random eventRng = new Random();
        private Node rainDropNode;
        private final List<RainDrop> rainDrops = new ArrayList<>();
        private DirectionalLight sunLight;
        private AmbientLight ambientLight;
        private Geometry sunBody;
        private com.jme3.shadow.DirectionalLightShadowRenderer shadowRenderer;
        private float dayNightTimer = 0f;
        private static final float DAY_DURATION = 300f;
        private static final float NIGHT_DURATION = 300f;
        // Вода на полу
        private final List<WaterPuddle> waterPuddles = new ArrayList<>();
        private Node waterNode;
        private float waterSpeedMultiplier = 1f; // замедление от воды



        // Константы карты
        static final float MAP_HALF    = 40f;
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
                         List<InetSocketAddress> clients, int mapIndex, boolean cubesEnabled) {
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
            this.cubesEnabled = cubesEnabled;
        }

        @Override
        public void initialize(AppStateManager sm, Application application) {
            super.initialize(sm, application);
            app = (SimpleApplication) application;
            rootNode = app.getRootNode(); guiNode = app.getGuiNode();
            assetManager = app.getAssetManager(); inputManager = app.getInputManager();
            cam = app.getCamera(); stateManager = sm;

            // Цвет фона по карте
            if (mapIndex == 1) app.getViewPort().setBackgroundColor(new ColorRGBA(0.75f,0.65f,0.45f,1f));
            else if (mapIndex == 2) app.getViewPort().setBackgroundColor(new ColorRGBA(0.15f,0.15f,0.25f,1f));
            else app.getViewPort().setBackgroundColor(new ColorRGBA(0.40f,0.65f,0.95f,1f));

            assetManager.registerLocator(".", FileLocator.class);

            // Первый ивент — быстро через 20–50 секунд
            nextEventTimer = 20f + new Random().nextFloat() * 30f;

            setupLights(); setupPhysics();
            buildOuterWorld();
            buildArena();
            buildClouds();
            applyShadowModes(rootNode);
            createSnakes();
            spawnFood(MAX_FOOD);
            if (cubesEnabled) spawnInitialCubes(); // Fix #13
            buildHUD();
            createGameoverUI();
            setupControls();
            loadSounds();
            if (!solo) initNetwork();

            rainBallNode = new Node("RainBalls"); rootNode.attachChild(rainBallNode);
            rainDropNode = new Node("RainDrops"); rootNode.attachChild(rainDropNode);
            waterNode    = new Node("Water");     rootNode.attachChild(waterNode);
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
            clearShadowRenderer();
            rootNode.detachAllChildren(); guiNode.detachAllChildren();
            inputManager.clearMappings();
            stateManager.detach(bulletAppState); stateManager.detach(this);
            stateManager.attach(new GameState(myNick, allPlayers, myIndex, solo, isHost,
                    null, hostAddress, hostPort, clients, mapIndex, cubesEnabled));
        }

        // ── Свет ──────────────────────────────────────────────────────────
        private void setupLights() {
            sunLight = new DirectionalLight();
            sunLight.setDirection(new Vector3f(-0.5f,-1f,-0.3f).normalizeLocal());
            sunLight.setColor(new ColorRGBA(1f,0.98f,0.9f,1f));
            rootNode.addLight(sunLight);

            ambientLight = new AmbientLight();
            ambientLight.setColor(new ColorRGBA(0.35f,0.37f,0.45f,1f));
            rootNode.addLight(ambientLight);

            // Fix #5: Солнце/луна как визуальный объект
            if (mapIndex != 2) {
                // Солнце
                sunBody = new Geometry("Sun", new com.jme3.scene.shape.Sphere(12,16,4.5f));
                Material sunMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                sunMat.setColor("Color", new ColorRGBA(1f,0.98f,0.75f,1f));
                sunBody.setMaterial(sunMat);
                sunBody.setLocalTranslation(-80f, 65f, -80f);
                rootNode.attachChild(sunBody);
                // Ореол вокруг солнца
                Geometry sunHalo = new Geometry("SunHalo", new com.jme3.scene.shape.Sphere(8,12,7f));
                Material haloMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                haloMat.setColor("Color", new ColorRGBA(1f,0.95f,0.6f,0.18f));
                haloMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
                sunHalo.setMaterial(haloMat);
                sunHalo.setLocalTranslation(-80f, 65f, -80f);
                rootNode.attachChild(sunHalo);
            } else {
                // Луна
                Geometry moonGeo = new Geometry("Moon", new com.jme3.scene.shape.Sphere(10,14,3f));
                Material moonMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
                moonMat.setColor("Color", new ColorRGBA(0.88f,0.90f,0.98f,1f));
                moonGeo.setMaterial(moonMat);
                moonGeo.setLocalTranslation(60f, 70f, -60f);
                rootNode.attachChild(moonGeo);
            }

            // Тени по качеству
            if (graphicsShadowQuality >= 0) {
                try {
                    int[] sizes = {512, 1024, 2048, 4096};
                    int[] splits = {1, 2, 3, 4};
                    com.jme3.shadow.DirectionalLightShadowRenderer dlsr =
                            new com.jme3.shadow.DirectionalLightShadowRenderer(assetManager, sizes[graphicsShadowQuality], splits[graphicsShadowQuality]);
                    dlsr.setLight(sunLight);
                    dlsr.setShadowIntensity(0.35f + graphicsShadowQuality * 0.12f);
                    app.getViewPort().addProcessor(dlsr);
                    shadowRenderer = dlsr;
                } catch (Exception e) {
                    System.out.println("[GFX] Shadows not available: " + e.getMessage());
                }
            }
        }

        private void clearShadowRenderer() {
            if (shadowRenderer != null) {
                app.getViewPort().removeProcessor(shadowRenderer);
                shadowRenderer = null;
            }
        }

        private void applyShadowModes(Spatial spatial) {
            if (spatial == null) return;
            spatial.setShadowMode(com.jme3.renderer.queue.RenderQueue.ShadowMode.CastAndReceive);
            if (spatial instanceof Node) {
                for (Spatial child : ((Node) spatial).getChildren()) applyShadowModes(child);
            }
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
            float ext = MAP_HALF * 3.5f;

            // Земля снаружи
            ColorRGBA groundColor = mapIndex==1
                    ? new ColorRGBA(0.72f,0.58f,0.32f,1f)
                    : new ColorRGBA(0.25f,0.48f,0.18f,1f);
            Box groundOut = new Box(ext, 0.3f, ext);
            Geometry gOut = new Geometry("OutGround", groundOut);
            gOut.setMaterial(unshaded(assetManager, groundColor));
            gOut.setLocalTranslation(0, -0.8f, 0);
            worldNode.attachChild(gOut);

            // Горы по периметру
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

            // Деревья / кактусы СНАРУЖИ арены (никогда внутри игровой зоны!)
            Material trunkMat = unshaded(assetManager, new ColorRGBA(0.42f,0.26f,0.12f,1f));
            Material leafMat  = unshaded(assetManager, new ColorRGBA(0.15f,0.6f,0.18f,1f));
            Material leafMat2 = unshaded(assetManager, new ColorRGBA(0.1f,0.5f,0.12f,1f));
            Material cactMat  = unshaded(assetManager, new ColorRGBA(0.2f,0.55f,0.18f,1f));

            int treeCount = 80;
            for (int i=0; i<treeCount; i++) {
                float angle = rng.nextFloat() * FastMath.TWO_PI;
                // Минимальная дистанция 1.45*MAP_HALF — ВСЕГДА за стенами
                float dist = MAP_HALF * 1.45f + rng.nextFloat() * MAP_HALF * 0.9f;
                float tx = FastMath.cos(angle)*dist, tz = FastMath.sin(angle)*dist;
                float treeH = 2.5f + rng.nextFloat()*3f;

                if (mapIndex == 1) {
                    // Кактусы для пустыни
                    Geometry trunk = new Geometry("Cactus"+i, new Box(0.25f, treeH/2f, 0.25f));
                    trunk.setMaterial(cactMat);
                    trunk.setLocalTranslation(tx, treeH/2f, tz);
                    worldNode.attachChild(trunk);
                    // Руки кактуса
                    Geometry arm = new Geometry("CactusArm"+i, new Box(0.6f, 0.2f, 0.2f));
                    arm.setMaterial(cactMat);
                    arm.setLocalTranslation(tx + 0.5f, treeH*0.6f, tz);
                    worldNode.attachChild(arm);
                } else {
                    // Обычные деревья
                    Geometry trunk = new Geometry("Trunk"+i, new Box(0.22f, treeH/2f, 0.22f));
                    trunk.setMaterial(trunkMat);
                    trunk.setLocalTranslation(tx, treeH/2f - 0.5f, tz);
                    worldNode.attachChild(trunk);
                    Material lm = rng.nextBoolean() ? leafMat : leafMat2;
                    Geometry leaves = new Geometry("Leaf"+i, new Sphere(5,6,0.9f+rng.nextFloat()*0.6f));
                    leaves.setMaterial(lm);
                    leaves.setLocalScale(1.4f+rng.nextFloat()*0.5f, 1.2f+rng.nextFloat()*0.5f, 1.4f+rng.nextFloat()*0.5f);
                    leaves.setLocalTranslation(tx, treeH+0.4f, tz);
                    worldNode.attachChild(leaves);
                }
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
            addBox(new Vector3f(0,-0.4f,0), new Vector3f(MAP_HALF,0.2f,MAP_HALF), unshaded(assetManager, floorColor), space);

            // Сетка пола
            ColorRGBA gridColor = mapIndex==1
                    ? new ColorRGBA(0.65f,0.52f,0.28f,1f)
                    : (mapIndex==2 ? new ColorRGBA(0.22f,0.20f,0.30f,1f)
                    : new ColorRGBA(0.20f,0.50f,0.20f,1f));
            Material gridMat = unshaded(assetManager, gridColor);
            for (float x=-MAP_HALF; x<=MAP_HALF; x+=5f)
                addBox(new Vector3f(x,-0.19f,0), new Vector3f(0.04f,0.01f,MAP_HALF), gridMat, null);
            for (float z=-MAP_HALF; z<=MAP_HALF; z+=5f)
                addBox(new Vector3f(0,-0.19f,z), new Vector3f(MAP_HALF,0.01f,0.04f), gridMat, null);

            // Стены
            buildMetalFence(space);

            // Угловые башни
            Material towerMat = unshaded(assetManager, new ColorRGBA(0.35f,0.40f,0.45f,1f));
            Material towerTop  = unshaded(assetManager, new ColorRGBA(0.25f,0.28f,0.35f,1f));
            float[] tcx = {-MAP_HALF, MAP_HALF,-MAP_HALF, MAP_HALF};
            float[] tcz = {-MAP_HALF,-MAP_HALF, MAP_HALF, MAP_HALF};
            for (int i=0;i<4;i++) {
                addBox(new Vector3f(tcx[i],2f,tcz[i]), new Vector3f(2f,2.5f,2f), towerMat, null);
                addBox(new Vector3f(tcx[i],5f,tcz[i]), new Vector3f(2.2f,0.5f,2.2f), towerTop, null);
                // Прожектор (шар)
                Geometry light = new Geometry("TowerLight"+i, new Sphere(6,8,0.4f));
                light.setMaterial(unshaded(assetManager, new ColorRGBA(1f,0.95f,0.7f,1f)));
                light.setLocalTranslation(tcx[i], 6.2f, tcz[i]);
                wallNode.attachChild(light);
            }

            // Ямы со шипами (карта 2)
            if (mapIndex == 2) buildPits(space);

            // Кактусы-преграды внутри арены (карта 1 — Пустыня)
            if (mapIndex == 1) buildDesertCacti(space);
        }

        /** Кактусы-препятствия внутри пустынной арены */
        private void buildDesertCacti(PhysicsSpace space) {
            Material cactMat = unshaded(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
            Material spineMat = unshaded(assetManager, new ColorRGBA(0.85f,0.82f,0.65f,1f));
            Random rng = new Random(77);
            int cactusCount = 12;
            for (int i = 0; i < cactusCount; i++) {
                float angle = rng.nextFloat() * FastMath.TWO_PI;
                float dist  = 8f + rng.nextFloat() * (MAP_HALF * 0.75f - 8f);
                float cx = FastMath.cos(angle) * dist;
                float cz = FastMath.sin(angle) * dist;
                // Не ставим прямо у старта
                if (Math.abs(cx) < 6f && Math.abs(cz) < 6f) continue;

                float cactH = 1.6f + rng.nextFloat() * 1.4f;
                // Fix #2: Кактус — динамический (масса > 0), может быть сбит змейкой
                Box trunkBox = new Box(0.28f, cactH/2f, 0.28f);
                Geometry trunk = new Geometry("CactI"+i, trunkBox);
                trunk.setMaterial(cactMat);
                trunk.setLocalTranslation(cx, cactH/2f, cz);
                wallNode.attachChild(trunk);
                RigidBodyControl trunkPhy = new RigidBodyControl(
                        new BoxCollisionShape(new Vector3f(0.28f, cactH/2f, 0.28f)), 3.0f); // динамический!
                trunkPhy.setFriction(1.2f); trunkPhy.setLinearDamping(0.5f); trunkPhy.setAngularDamping(0.7f);
                trunk.addControl(trunkPhy); space.add(trunkPhy);
                cacti.add(new CactusData(trunk, trunkPhy, cx, cz));

                // Рука кактуса
                if (rng.nextBoolean()) {
                    float armSide = rng.nextBoolean() ? 0.5f : -0.5f;
                    Box armBox = new Box(0.35f, 0.18f, 0.18f);
                    Geometry arm = new Geometry("CactA"+i, armBox);
                    arm.setMaterial(cactMat);
                    arm.setLocalTranslation(cx + armSide, cactH * 0.6f, cz);
                    wallNode.attachChild(arm);
                }

                // Иголки (торчащие шипы)
                int spineCount = 6 + rng.nextInt(5);
                for (int j = 0; j < spineCount; j++) {
                    float sa = rng.nextFloat() * FastMath.TWO_PI;
                    float sy = rng.nextFloat() * cactH;
                    float sd = 0.3f + rng.nextFloat() * 0.2f;
                    Box spineBox = new Box(0.04f, 0.04f, sd);
                    Geometry spine = new Geometry("CactS"+i+"_"+j, spineBox);
                    spine.setMaterial(spineMat);
                    spine.setLocalRotation(new Quaternion().fromAngleAxis(sa, Vector3f.UNIT_Y));
                    spine.setLocalTranslation(cx + FastMath.cos(sa)*0.3f, sy, cz + FastMath.sin(sa)*0.3f);
                    wallNode.attachChild(spine);
                }
            }
        }

        /** Металлические ограждения вместо кирпичей */
        private void buildMetalFence(PhysicsSpace space) {
            float wH = 3.5f;

            // Физические коллайдеры стен — точно по периметру
            Material colMat = unshaded(assetManager, new ColorRGBA(0.45f,0.48f,0.52f,1f));
            addBox(new Vector3f(0,     wH/2,  MAP_HALF-0.5f), new Vector3f(MAP_HALF,wH/2,0.5f), colMat, space);
            addBox(new Vector3f(0,     wH/2, -MAP_HALF+0.5f), new Vector3f(MAP_HALF,wH/2,0.5f), colMat, space);
            addBox(new Vector3f(-MAP_HALF+0.5f, wH/2, 0),      new Vector3f(0.5f,wH/2,MAP_HALF), colMat, space);
            addBox(new Vector3f( MAP_HALF-0.5f, wH/2, 0),      new Vector3f(0.5f,wH/2,MAP_HALF), colMat, space);

            // Визуальные металлические стойки и перемычки
            Material postMat = unshaded(assetManager, new ColorRGBA(0.55f,0.58f,0.62f,1f));
            Material railMat = unshaded(assetManager, new ColorRGBA(0.40f,0.43f,0.48f,1f));
            Material railHighMat = unshaded(assetManager, new ColorRGBA(0.70f,0.72f,0.78f,1f));

            float postSpacing = 4f;
            int postCount = (int)(MAP_HALF*2 / postSpacing) + 1;

            for (int i=0; i<postCount; i++) {
                float px = -MAP_HALF + i * postSpacing;
                if (px > MAP_HALF) px = MAP_HALF;

                // Стойки по всем 4 сторонам
                spawnPost(px, MAP_HALF-0.5f, postMat);
                spawnPost(px, -MAP_HALF+0.5f, postMat);
                spawnPost(MAP_HALF-0.5f, px, postMat);
                spawnPost(-MAP_HALF+0.5f, px, postMat);
            }

            // Рельсы (горизонтальные перекладины) — 3 уровня
            float[] railY = {0.7f, 1.6f, 2.8f};
            for (float ry : railY) {
                // Север
                addBox(new Vector3f(0, ry, MAP_HALF-0.5f), new Vector3f(MAP_HALF,0.06f,0.06f), railMat, null);
                // Юг
                addBox(new Vector3f(0, ry, -MAP_HALF+0.5f), new Vector3f(MAP_HALF,0.06f,0.06f), railMat, null);
                // Запад
                addBox(new Vector3f(-MAP_HALF+0.5f, ry, 0), new Vector3f(0.06f,0.06f,MAP_HALF), railMat, null);
                // Восток
                addBox(new Vector3f(MAP_HALF-0.5f, ry, 0), new Vector3f(0.06f,0.06f,MAP_HALF), railMat, null);
            }

            // Верхний рейл — яркий/светлый
            addBox(new Vector3f(0, wH, MAP_HALF-0.5f), new Vector3f(MAP_HALF,0.08f,0.08f), railHighMat, null);
            addBox(new Vector3f(0, wH, -MAP_HALF+0.5f), new Vector3f(MAP_HALF,0.08f,0.08f), railHighMat, null);
            addBox(new Vector3f(-MAP_HALF+0.5f, wH, 0), new Vector3f(0.08f,0.08f,MAP_HALF), railHighMat, null);
            addBox(new Vector3f(MAP_HALF-0.5f, wH, 0), new Vector3f(0.08f,0.08f,MAP_HALF), railHighMat, null);
        }

        private void spawnPost(float x, float z, Material mat) {
            addBox(new Vector3f(x, 1.75f, z), new Vector3f(0.12f, 1.75f, 0.12f), mat, null);
        }

        /** Ямы со шипами (карта 2) — шипы на уровне пола, выдвигаются/задвигаются */
        private void buildPits(PhysicsSpace space) {
            pitNode = new Node("Pits");
            rootNode.attachChild(pitNode);
            pits.clear();

            Material pitFloor  = unshaded(assetManager, new ColorRGBA(0.10f,0.08f,0.12f,1f));
            Material wallMat   = unshaded(assetManager, new ColorRGBA(0.12f,0.10f,0.16f,1f));
            Material warningMat= unshaded(assetManager, new ColorRGBA(0.90f,0.30f,0.10f,1f)); // оранжевое кольцо предупреждения
            // Чисто-белые шипы
            Material spikeMat  = unshaded(assetManager, new ColorRGBA(0.96f,0.97f,1.00f,1f));
            Material spikeTipMat= unshaded(assetManager, new ColorRGBA(1f,1f,1f,1f));

            for (float[] pp : PIT_POSITIONS) {
                float px = pp[0], pz = pp[1];
                PitData pit = new PitData();
                pit.position = new Vector3f(px, 0f, pz);
                pit.radius = PIT_RADIUS;
                // Fix #10: Разные начальные задержки — каждая яма работает в своём ритме
                pit.stateTimer = (float)(pits.size()) * (PIT_RETRACTED_DURATION / PIT_POSITIONS.length)
                        + FastMath.nextRandomFloat() * PIT_RETRACTED_DURATION * 0.5f;

                // Дно ямы (визуальный декор на уровне пола)
                Box floor = new Box(PIT_RADIUS * 0.9f, 0.15f, PIT_RADIUS * 0.9f);
                Geometry floorGeo = new Geometry("PitFloor", floor);
                floorGeo.setMaterial(pitFloor);
                floorGeo.setLocalTranslation(px, -0.22f, pz);
                pitNode.attachChild(floorGeo);

                // Fix #10: Рамка ямы опущена на уровень пола (было 0.05f → -0.18f)
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
                        // Задвинут: Y = -0.2 - spikeH (полностью под полом)
                        spike.setLocalTranslation(sx, -0.22f - spikeH, sz);
                        pitNode.attachChild(spike);

                        // Острие шипа (конус через масштабированную сферу)
                        Geometry tip = new Geometry("SpikeTip", new Sphere(4, 4, spikeBaseW * 1.5f));
                        tip.setMaterial(spikeTipMat);
                        tip.setLocalScale(1f, 4f, 1f);
                        tip.setLocalTranslation(sx, -0.22f - spikeH / 2f - spikeH, sz);
                        pitNode.attachChild(tip);

                        // Блестящий кончик
                        Geometry glint = new Geometry("SpikeGlint", new Sphere(3, 3, spikeBaseW * 0.7f));
                        glint.setMaterial(unshaded(assetManager, new ColorRGBA(0.8f, 0.9f, 1f, 1f)));
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
            if (mapIndex == 2) return; // Тёмная карта без облаков
            Material cm = unshaded(assetManager, new ColorRGBA(1f,1f,1f,0.88f));
            if (mapIndex == 1) cm = unshaded(assetManager, new ColorRGBA(0.95f,0.90f,0.75f,0.7f));
            for (int i=0;i<20;i++) {
                float r = 1.2f+FastMath.nextRandomFloat()*3f;
                Geometry g = new Geometry("Cloud", new Sphere(8,10,r));
                g.setMaterial(cm);
                g.setLocalTranslation(
                        (FastMath.nextRandomFloat()-0.5f)*MAP_HALF*3f, 14f+FastMath.nextRandomFloat()*10f,
                        (FastMath.nextRandomFloat()-0.5f)*MAP_HALF*3f);
                cloudNode.attachChild(g);
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
            for (int i=0;i<allPlayers.size();i++) {
                Node sn = new Node("Snake"+i); rootNode.attachChild(sn);
                // Локальный игрок получает выбранный цвет
                ColorRGBA snakeColor = (i == myIndex) ? selectedSnakeColor : SNAKE_COLORS[i%SNAKE_COLORS.length];
                Material mat = unshaded(assetManager, snakeColor);
                SnakePlayer sp = new SnakePlayer(
                        allPlayers.get(i), START_POS[i%START_POS.length].clone(),
                        START_ANGLES[i%START_ANGLES.length], mat, sn, assetManager, guiNode, cam,
                        bulletAppState.getPhysicsSpace());
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
            float x = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(MAP_HALF*2-6), -MAP_HALF+2f, MAP_HALF-2f);
            float z = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(MAP_HALF*2-6), -MAP_HALF+2f, MAP_HALF-2f);
            addFoodWithData(foodIdCounter++, x, z, bad, false);
        }

        private void addFoodWithData(int id, float x, float z, boolean bad, boolean isDebris) {
            x = FastMath.clamp(x, -MAP_HALF+1.5f, MAP_HALF-1.5f);
            z = FastMath.clamp(z, -MAP_HALF+1.5f, MAP_HALF-1.5f);
            Material mat;
            float radius;
            if (isDebris) { mat = unshaded(assetManager, new ColorRGBA(0.85f,0.85f,0.85f,1f)); radius = 0.32f; }
            else if (bad) { mat = unshaded(assetManager, new ColorRGBA(0.42f,0.26f,0.12f,1f)); radius = 0.50f; }
            else {
                Material[] goodMats = {
                        unshaded(assetManager, new ColorRGBA(0.95f,0.2f,0.2f,1f)),
                        unshaded(assetManager, new ColorRGBA(0.2f,0.4f,1f,1f)),
                        unshaded(assetManager, new ColorRGBA(1f,0.85f,0.1f,1f)),
                        unshaded(assetManager, new ColorRGBA(0.8f,0.2f,0.9f,1f)),
                        unshaded(assetManager, new ColorRGBA(0.1f,0.9f,0.7f,1f))
                };
                mat = goodMats[FastMath.nextRandomInt(0, goodMats.length-1)]; radius = 0.38f;
            }
            Geometry geo = new Geometry("Food"+(isDebris?"D":""), new Sphere(12,12,radius));
            geo.setMaterial(mat); geo.setLocalTranslation(x, isDebris?0.4f:1.8f, z);
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
            float x = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(MAP_HALF*2-6),-MAP_HALF+2f,MAP_HALF-2f);
            float z = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*(MAP_HALF*2-6),-MAP_HALF+2f,MAP_HALF-2f);
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
                float dx = FastMath.clamp(pos.x, -MAP_HALF+1.5f, MAP_HALF-1.5f);
                float dz = FastMath.clamp(pos.z, -MAP_HALF+1.5f, MAP_HALF-1.5f);
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
            if (!solo && !isHost) return; // мультиплеер: пауза только у хоста
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
                    if (p.length>=9) {
                        int idx=Integer.parseInt(p[1]);
                        if (idx>=0&&idx<snakes.size()&&idx!=myIndex) {
                            float x=Float.parseFloat(p[2]),y=Float.parseFloat(p[3]),
                                    z=Float.parseFloat(p[4]),angle=Float.parseFloat(p[5]);
                            int score=Integer.parseInt(p[6]),len=Integer.parseInt(p[7]);
                            boolean dead="1".equals(p[8]);
                            snakes.get(idx).applyNetState(x,y,z,angle,score,len,dead);
                        }
                    } break;
                case "DEAD":
                    if (p.length>=2) {
                        int idx=Integer.parseInt(p[1]);
                        if (idx>=0&&idx<snakes.size()&&!snakes.get(idx).isDead()) {
                            snakes.get(idx).triggerDeathRemote(rootNode); checkWinCondition();
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
                case "EVENT_FROZEN": // Fix #12
                    if (!frozenArenaActive) startFrozenArenaEvent(); break;
                case "CACT_HIT": // Fix #2: синхронизация разрушения кактуса по сети
                    if (p.length >= 3) {
                        float cx2 = Float.parseFloat(p[1]), cz2 = Float.parseFloat(p[2]);
                        for (CactusData cd : cacti) {
                            if (!cd.hit && Math.abs(cd.origX-cx2)<0.5f && Math.abs(cd.origZ-cz2)<0.5f) {
                                cd.hit = true;
                                cd.phy.applyImpulse(new Vector3f(FastMath.nextRandomFloat()*10f,5f,FastMath.nextRandomFloat()*10f), Vector3f.ZERO);
                                break;
                            }
                        }
                    } break;
                case "BALL_SPAWN":
                    if (p.length>=3) spawnRainBall(Float.parseFloat(p[1]), Float.parseFloat(p[2]));
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
                            float rx = (FastMath.nextRandomFloat()-0.5f) * MAP_HALF * 1.8f;
                            float rz = (FastMath.nextRandomFloat()-0.5f) * MAP_HALF * 1.8f;
                            spawnRainBall(rx, rz);
                            if (!solo) sendNet("BALL_SPAWN|"+rx+"|"+rz);
                        }
                    }
                }
            }

            for (int i=rainBalls.size()-1;i>=0;i--) {
                RainBall rb = rainBalls.get(i);

                if (rb.landed) {
                    rb.landedTimer -= tpf;
                    if (rb.landedTimer <= 0f) {
                        rainBallNode.detachChild(rb.geo);
                        rainBalls.remove(i);
                        continue;
                    }
                    rb.geo.rotate(0f, tpf * 0.8f, 0f);
                    if (myIndex < snakes.size() && !snakes.get(myIndex).isDead()) {
                        SnakePlayer me = snakes.get(myIndex);
                        Vector3f pos = rb.geo.getLocalTranslation();
                        if (me.getHeadPos().distance(pos) < 1.5f) {
                            me.grow(assetManager); me.addScore(3); playEatSound();
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

                    float floorY = 0.5f;
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
            ColorRGBA col = cols[FastMath.nextRandomInt(0,cols.length-1)];
            float r = 0.3f + FastMath.nextRandomFloat()*0.3f;
            Geometry geo = new Geometry("RainBall", new Sphere(8,8,r));
            geo.setMaterial(unshaded(assetManager, col));
            geo.setLocalTranslation(x, 20f + FastMath.nextRandomFloat()*10f, z);
            rainBallNode.attachChild(geo);
            rainBalls.add(new RainBall(geo, 4f + FastMath.nextRandomFloat()*3f));
        }

        // ── Ивент 2: Дождь ────────────────────────────────────────────────
        private void startWeatherRainEvent() {
            if (weatherRainActive) return;
            weatherRainActive = true; weatherRainTimer = WEATHER_RAIN_DURATION;
            showCenter("☔ ДОЖДЬ!", new ColorRGBA(0.5f,0.7f,1f,1f));
            if (!solo) sendNet("EVENT_RAIN");
            try {
                // Fix #9: DataType.Stream вместо Buffer — непрерывный loop без микро-паузы
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
                    for (int i=0;i<8;i++) {
                        float rx = (FastMath.nextRandomFloat()-0.5f)*MAP_HALF*2f;
                        float rz = (FastMath.nextRandomFloat()-0.5f)*MAP_HALF*2f;
                        spawnRainDrop(rx, rz);
                    }
                    if (solo || isHost) {
                        if ((int)(weatherRainTimer*10) % 30 == 0) {
                            float px = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*MAP_HALF*1.8f,-MAP_HALF+3f,MAP_HALF-3f);
                            float pz = FastMath.clamp((FastMath.nextRandomFloat()-0.5f)*MAP_HALF*1.8f,-MAP_HALF+3f,MAP_HALF-3f);
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
                if (Math.abs(wp.x-x)<2f && Math.abs(wp.z-z)<2f) return;
            }
            Geometry geo = new Geometry("Puddle", new Sphere(16,16,1f));
            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", new ColorRGBA(0.16f,0.20f,0.24f,0.58f));
            mat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
            geo.setMaterial(mat);
            geo.setLocalTranslation(x, -0.15f, z);
            geo.setLocalScale(0.12f, 0.008f, 0.09f);
            waterNode.attachChild(geo);
            waterPuddles.add(new WaterPuddle(geo, x, z, radius));
        }

        // ── ИВЕНТ 3: Ледяная арена (Fix #12) ─────────────────────────────
        private void startFrozenArenaEvent() {
            if (frozenArenaActive) return;
            frozenArenaActive = true; frozenArenaTimer = FROZEN_ARENA_DURATION;
            frozenSpeedMult = 1.0f; // скорость не снижаем: на льду только скольжение
            showCenter("❄️ ЛЕДЯНАЯ АРЕНА! Осторожно — лёд! ❄️", new ColorRGBA(0.6f,0.9f,1f,1f));
            if (!solo) sendNet("EVENT_FROZEN");
                        // Легкий голубой тинт только для пола (стены не трогаем)
            applyFrozenFloorTint(true);
        }

        private void updateFrozenArena(float tpf) {
            if (!frozenArenaActive) return;
            frozenArenaTimer -= tpf;
            if (frozenArenaTimer <= 0f) {
                frozenArenaActive = false;
                frozenSpeedMult = 1f;
                showCenter("❄️ Лёд растаял. Скольжение исчезло.", new ColorRGBA(0.6f,0.9f,1f,1f));
                applyFrozenFloorTint(false);
                if (solo || isHost) nextEventTimer = 25f + eventRng.nextFloat() * 45f;
            }
        }


        private void applyFrozenFloorTint(boolean enable) {
            if (wallNode == null) return;
            wallNode.depthFirstTraversal(spatial -> {
                if (!(spatial instanceof Geometry g)) return;
                if (!"Box".equals(g.getMesh().getClass().getSimpleName())) return;
                Vector3f pos = g.getLocalTranslation();
                if (Math.abs(pos.y + 0.4f) > 0.15f && Math.abs(pos.y + 0.19f) > 0.08f) return;
                Material m = g.getMaterial();
                if (m == null) return;
                Object colObj = m.getParamValue("Color");
                if (!(colObj instanceof ColorRGBA base)) return;
                if (enable) {
                    if (g.getUserData("FrozenBaseR") == null) {
                        g.setUserData("FrozenBaseR", base.r);
                        g.setUserData("FrozenBaseG", base.g);
                        g.setUserData("FrozenBaseB", base.b);
                        ColorRGBA tinted = base.interpolateLocal(new ColorRGBA(0.72f,0.88f,1f,1f), 0.35f);
                        tinted.a = 1f;
                        m.setColor("Color", tinted);
                    }
                } else {
                    Float r = g.getUserData("FrozenBaseR");
                    Float gg = g.getUserData("FrozenBaseG");
                    Float b = g.getUserData("FrozenBaseB");
                    if (r != null && gg != null && b != null) m.setColor("Color", new ColorRGBA(r, gg, b, 1f));
                }
            });
        }

        // ── Кубы-враги ────────────────────────────────────────────────────
        private void spawnInitialCubes() {
            cubeNode = new Node("BlackCubes"); rootNode.attachChild(cubeNode);
            if (solo || isHost) {
                float[] xs = {-20f,20f,0f,-15f,15f};
                float[] zs = {0f,0f,20f,15f,-15f};
                for (int i=0;i<Math.min(MAX_BLACK_CUBES,xs.length);i++) {
                    int id = cubeIdCounter++;
                    spawnBlackCube(id, xs[i], zs[i]);
                    if (!solo) sendNet("CUBE_SPAWN|"+id+"|"+xs[i]+"|"+zs[i]);
                }
            }
        }

        private void spawnBlackCube(int id, float x, float z) {
            for (BlackCube bc:blackCubes) if (bc.id==id) return;
            float side = 0.7f;
            Geometry geo = new Geometry("BlackCube_"+id, new Box(side,side,side));
            Material mat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
            mat.setColor("Color", ColorRGBA.Black);
            geo.setMaterial(mat);
            geo.setLocalTranslation(x, side+0.2f, z);
            cubeNode.attachChild(geo);

            RigidBodyControl phy = new RigidBodyControl(new BoxCollisionShape(new Vector3f(side,side,side)), 15f);
            phy.setFriction(1.5f); phy.setRestitution(0.25f);
            phy.setAngularDamping(0.60f); phy.setLinearDamping(0.80f);
            geo.addControl(phy); bulletAppState.getPhysicsSpace().add(phy);

            blackCubes.add(new BlackCube(id, geo, phy));
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

            final float hitThreshold = (0.7f * (1f + bc.biteCount * 0.12f)) + SnakePlayer.SEG_R; // Fix #4: учитываем рост куба
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
                    // Fix #4: уменьшен импульс отталкивания (было 55f → 18f), иначе куб улетал
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
            g.setMaterial(mat); g.setLocalTranslation(pos); wallNode.attachChild(g);
            if (space!=null) {
                RigidBodyControl phy = new RigidBodyControl(new BoxCollisionShape(half), 0);
                g.addControl(phy); space.add(phy);
            }
        }

        // ── Главный цикл ──────────────────────────────────────────────────
        @Override
        public void update(float tpf) {
            // Пауза — замораживаем всё
            if (pauseActive) return;

            updateDayNightCycle(tpf);
            moveClouds(tpf);
            gameTime += tpf;

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
            if ((solo || isHost) && !ballRainActive && !weatherRainActive && !frozenArenaActive && nextEventTimer > 0) {
                nextEventTimer -= tpf;
                if (nextEventTimer <= 0) {
                    int evtChoice = eventRng.nextInt(3);
                    if (evtChoice == 0) startBallRainEvent();
                    else if (evtChoice == 1) startWeatherRainEvent();
                    else startFrozenArenaEvent(); // Fix #12
                }
            }

            updateBallRain(tpf);
            updateWeatherRain(tpf);
            updateFrozenArena(tpf); // Fix #12

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
            float effectiveSpeed = SPEED * waterSpeedMultiplier * frozenSpeedMult; // Fix #12
            if (dashTimer > 0f && myIndex < snakes.size() && !snakes.get(myIndex).isDead()) {
                // Рывок применяется только к локальному игроку
                for (int i = 0; i < snakes.size(); i++) {
                    float spd = (i == myIndex) ? effectiveSpeed * DASH_SPEED_MULT : effectiveSpeed;
                    if (!snakes.get(i).isDead()) snakes.get(i).update(tpf, spd, TURN_SPEED, SEG_SPACING);
                }
            } else {
                for (SnakePlayer s : snakes) if (!s.isDead()) s.update(tpf, effectiveSpeed, TURN_SPEED, SEG_SPACING);
            }

            checkCollisions();

            // Проверка ям (карта 2)
            if (mapIndex==2) {
                if (solo || isHost) updatePits(tpf);
                if (solo || isHost) checkPits();
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
            updateBlackCubes(tpf);
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

        private void checkCollisions() {
            SnakePlayer me = myIndex<snakes.size() ? snakes.get(myIndex) : null;
            if (me==null||me.isDead()) return;
            Vector3f h = me.getHeadPos();
            float wallBound = MAP_HALF - 0.9f - SnakePlayer.SEG_R;
            boolean wallHit = !bordersRemoved && (Math.abs(h.x)>wallBound || Math.abs(h.z)>wallBound);
            boolean selfHit = me.selfCollides(SEG_SPACING*0.8f);
            if (wallHit||selfHit) { killSnake(myIndex, wallHit?"стена":"самопересечение"); return; }

            for (int i=0;i<snakes.size();i++) {
                if (i==myIndex||snakes.get(i).isDead()) continue;
                SnakePlayer other = snakes.get(i);
                if (other.bodyContains(h,SEG_SPACING*0.85f) || h.distance(other.getHeadPos())<SEG_SPACING) {
                    killSnake(myIndex, "столкновение с "+other.getName()); return;
                }
            }

            // Fix #2: Коллизия с кактусами на карте 1
            if (mapIndex == 1) checkCactusCollisions(me);

            checkFoodFor(me);
        }

        /** Fix #2: Проверка столкновения головы змеи с кактусами → разрушение + прилипание к телу */
        private void checkCactusCollisions(SnakePlayer me) {
            Vector3f h = me.getHeadPos();
            for (CactusData cd : cacti) {
                if (cd.hit) {
                    // Фрагменты уже есть — проверяем прилипание к телу
                    for (int fi = cd.fragments.size()-1; fi >= 0; fi--) {
                        Geometry frag = cd.fragments.get(fi);
                        if (me.bodyContains(frag.getWorldTranslation(), 1.2f)) {
                            // Прилипаем: убираем физику, прикрепляем к голове как сегмент
                            RigidBodyControl fp = frag.getControl(RigidBodyControl.class);
                            if (fp == null && frag.getParent() != null) fp = frag.getParent().getControl(RigidBodyControl.class);
                            if (fp != null) { bulletAppState.getPhysicsSpace().remove(fp); frag.removeControl(fp); }
                            frag.setLocalScale(1f, 1f, 1.2f);
                            if (frag.getParent() != null) frag.getParent().removeFromParent();
                            cd.fragments.remove(fi);
                            if (!solo) sendNet("CACT_STICK|" + cd.origX + "|" + cd.origZ);
                        }
                    }
                    continue;
                }
                // Ствол не сбит: проверяем удар головой
                Vector3f cpos = cd.geo.getWorldTranslation();
                if (h.distance(cpos) < 1.2f) {
                    cd.hit = true;
                    // Применяем импульс в направлении движения
                    Vector3f impulse = me.getDirection().mult(25f).addLocal(0, 5f, 0);
                    cd.phy.applyImpulse(impulse, Vector3f.ZERO);
                    // Создаём обломки кактуса с колючками
                    Material fragMat = unshaded(assetManager, new ColorRGBA(0.18f,0.52f,0.15f,1f));
                    Material spineMat = unshaded(assetManager, new ColorRGBA(0.86f,0.82f,0.66f,1f));
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
                        frag.addControl(fp); bulletAppState.getPhysicsSpace().add(fp);
                        for (int sj = 0; sj < 4; sj++) {
                            Geometry spike = new Geometry("FragSpike"+fi+"_"+sj, new Box(0.02f,0.02f,0.12f));
                            spike.setMaterial(spineMat);
                            float a = sj * FastMath.HALF_PI + FastMath.nextRandomFloat()*0.2f;
                            spike.setLocalRotation(new Quaternion().fromAngleAxis(a, Vector3f.UNIT_Y));
                            spike.setLocalTranslation(FastMath.cos(a)*fsize, 0f, FastMath.sin(a)*fsize);
                            frag.attachChild(spike);
                        }
                        cd.fragments.add(core);
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
                if (p.x>MAP_HALF*2f) p.x=-MAP_HALF*2f;
                s.setLocalTranslation(p);
            }
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
            clearShadowRenderer();
            rootNode.detachAllChildren(); guiNode.detachAllChildren();
            inputManager.clearMappings();
            inputManager.setCursorVisible(true);  // Fix #1: восстанавливаем курсор при выходе в меню
            stateManager.detach(bulletAppState); stateManager.detach(this);
            stateManager.attach(new MainMenuState());
        }

        @Override
        public void cleanup() {
            super.cleanup();
            netRunning.set(false);
            if (socket!=null&&!socket.isClosed()) socket.close();
            clearShadowRenderer();
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
            void updatePhy(RigidBodyControl newPhy) { this.phy = newPhy; } // Fix #4
        }

        // Fix #2: Данные кактуса
        static class CactusData {
            final Geometry geo; final RigidBodyControl phy;
            final float origX, origZ;
            boolean hit = false;
            final List<Geometry> fragments = new ArrayList<>();
            CactusData(Geometry g, RigidBodyControl p, float x, float z) {
                geo=g; phy=p; origX=x; origZ=z;
            }
        }

        static class FoodItem {
            final Geometry geo; final boolean bad; final int id; final boolean isDebris;
            FoodItem(Geometry g, boolean b, int id, boolean debris) { geo=g; bad=b; this.id=id; isDebris=debris; }
        }


        private void updateDayNightCycle(float tpf) {
            if (sunLight == null || ambientLight == null) return;
            dayNightTimer += tpf;
            float fullCycle = DAY_DURATION + NIGHT_DURATION;
            if (dayNightTimer > fullCycle) dayNightTimer -= fullCycle;
            float phase = dayNightTimer / fullCycle;
            float sunAngle = phase * FastMath.TWO_PI;
            Vector3f dir = new Vector3f(FastMath.cos(sunAngle), -0.15f - FastMath.sin(sunAngle), FastMath.sin(sunAngle)).normalizeLocal();
            sunLight.setDirection(dir);
            float daylight = FastMath.clamp((FastMath.sin(sunAngle) + 0.15f) * 0.9f, 0f, 1f);
            ambientLight.setColor(new ColorRGBA(0.10f,0.12f,0.20f,1f).interpolateLocal(new ColorRGBA(0.38f,0.40f,0.46f,1f), daylight));
            sunLight.setColor(new ColorRGBA(0.2f,0.25f,0.45f,1f).interpolateLocal(new ColorRGBA(1f,0.97f,0.86f,1f), daylight));
            if (sunBody != null) sunBody.setLocalTranslation(-dir.x * 120f, 40f - dir.y * 80f, -dir.z * 120f);
        }
        static class RainBall {
            final Geometry geo;
            float lifeTimer;
            boolean landed = false;
            float landedTimer = 0f;
            RainBall(Geometry g, float life) { geo=g; lifeTimer=life; }
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

        private float headingAngle;
        private Vector3f direction;
        private boolean turnLeft, turnRight;
        private boolean movingInput = false;
        private float currentSpeed = 0f;
        private static final float ACCEL = 18f;
        private static final float DECEL = 12f;

        private int score = 0;
        private boolean dead = false;
        public static final float SEG_R = 0.27f;

        private BitmapText nameTag;
        private final Node guiRef;
        private final Camera camRef;

        public SnakePlayer(String name, Vector3f startPos, float startAngle,
                           Material mat, Node parent, AssetManager am,
                           Node guiNode, Camera cam, com.jme3.bullet.PhysicsSpace space) {
            this.name=name; this.baseMat=mat; this.parentNode=parent;
            this.assetManager=am; this.guiRef=guiNode; this.camRef=cam;
            this.physicsSpace=space; this.headingAngle=startAngle;
            this.direction=calcDir(headingAngle);

            Vector3f back = direction.negate();
            for (int i=0;i<4;i++) addSegment(startPos.add(back.mult(i*0.55f)));

            BitmapFont font = loadFont(am);
            nameTag = new BitmapText(font);
            nameTag.setSize(16); nameTag.setText(name);
            nameTag.setColor(getColorFromMat(mat));
            guiNode.attachChild(nameTag);
        }

        private ColorRGBA getColorFromMat(Material m) {
            Object c = m.getParamValue("Color");
            return c instanceof ColorRGBA ? (ColorRGBA)c : ColorRGBA.White;
        }

        private void addSegment(Vector3f pos) {
            Geometry g = new Geometry("Seg"+segments.size(), new Sphere(12,12,SEG_R));
            Material sm = baseMat.clone();
            float factor = Math.max(0.5f, 1f - segments.size()*0.04f);
            Object cv = baseMat.getParamValue("Color");
            if (cv instanceof ColorRGBA c)
                sm.setColor("Color", new ColorRGBA(c.r*factor, c.g*factor, c.b*factor, 1f));
            g.setMaterial(sm); g.setLocalTranslation(pos.clone());
            parentNode.attachChild(g);

            if (physicsSpace!=null && segments.size()>0) {
                com.jme3.bullet.control.RigidBodyControl phy =
                        new com.jme3.bullet.control.RigidBodyControl(
                                new com.jme3.bullet.collision.shapes.SphereCollisionShape(SEG_R), 1f);
                phy.setKinematic(true); g.addControl(phy); physicsSpace.add(phy);
            }
            segments.add(g); segPos.add(pos.clone());
        }

        public void setMoving(boolean v) { this.movingInput=v; }
        public boolean isMoving() { return movingInput; }

        public void update(float tpf, float maxSpeed, float turnSpeed, float spacing) {
            if (dead) return;
            if (movingInput) currentSpeed=Math.min(maxSpeed, currentSpeed+ACCEL*tpf);
            else             currentSpeed=Math.max(0f,       currentSpeed-DECEL*tpf);

            float effectiveTurnSpeed = turnSpeed*(currentSpeed/maxSpeed);
            if (turnLeft)  headingAngle += effectiveTurnSpeed*tpf;
            if (turnRight) headingAngle -= effectiveTurnSpeed*tpf;
            direction=calcDir(headingAngle);

            if (currentSpeed<0.01f) return;
            segPos.get(0).addLocal(direction.mult(currentSpeed*tpf));
            segments.get(0).setLocalTranslation(segPos.get(0));

            for (int i=1;i<segPos.size();i++) {
                Vector3f prev=segPos.get(i-1), cur=segPos.get(i);
                Vector3f diff=prev.subtract(cur);
                float dist=diff.length();
                if (dist>spacing) { cur.addLocal(diff.normalize().mult(dist-spacing)); segments.get(i).setLocalTranslation(cur); }
            }
        }

        public void updateNameTag(Camera cam) {
            if (nameTag==null||dead||segPos.isEmpty()) return;
            Vector3f worldPos = segPos.get(0).add(0,1.4f,0);
            Vector3f screen = cam.getScreenCoordinates(worldPos);
            if (screen.z<0||screen.z>1) { nameTag.setLocalTranslation(-9999,0,0); return; }
            nameTag.setLocalTranslation(screen.x-nameTag.getLineWidth()/2, screen.y, 1);
        }

        public void applyNetState(float x, float y, float z, float angle, int sc, int remoteLen, boolean isDead) {
            if (!segPos.isEmpty()) {
                Vector3f newHead = new Vector3f(x,y,z);
                headingAngle=angle; direction=calcDir(headingAngle);
                segPos.get(0).set(newHead); segments.get(0).setLocalTranslation(newHead);
                while (segments.size()<remoteLen) grow(assetManager);
                while (segments.size()>remoteLen&&segments.size()>2) shrink();
                float spacing=0.55f;
                for (int i=1;i<segPos.size();i++) {
                    Vector3f prev=segPos.get(i-1), cur=segPos.get(i);
                    Vector3f diff=prev.subtract(cur); float dist=diff.length();
                    if (dist>spacing) { cur.addLocal(diff.normalize().mult(dist-spacing)); segments.get(i).setLocalTranslation(cur); }
                }
            }
            score=sc;
            if (isDead&&!dead) triggerDeathRemote(parentNode.getParent());
        }

        public void triggerDeath(Node scene) {
            if (dead) return; dead=true;
            if (nameTag!=null) { guiRef.detachChild(nameTag); nameTag=null; }
            for (Geometry seg:segments) {
                com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
                if (phy!=null&&physicsSpace!=null) { phy.setEnabled(false); physicsSpace.remove(phy); }
                parentNode.detachChild(seg);
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
            parentNode.detachChild(seg); segments.remove(idx); segPos.remove(idx);
        }


        public void removeSegmentsAtWorldPos(Vector3f worldPos, float radius) {
            if (dead || segments.size() <= 2) return;
            for (int i = segments.size() - 1; i >= 1; i--) {
                if (segPos.get(i).distance(worldPos) < radius) {
                    Geometry seg = segments.get(i);
                    com.jme3.bullet.control.RigidBodyControl phy = seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
                    if (phy != null && physicsSpace != null) { phy.setEnabled(false); physicsSpace.remove(phy); }
                    parentNode.detachChild(seg);
                    segments.remove(i);
                    segPos.remove(i);
                }
            }
        }

        public boolean selfCollides(float minDist) {
            if (segPos.size()<4) return false;
            Vector3f h=segPos.get(0);
            for (int i=3;i<segPos.size();i++) if (h.distance(segPos.get(i))<minDist) return true;
            return false;
        }

        public boolean bodyContains(Vector3f point, float radius) {
            for (int i=1;i<segPos.size();i++) if (point.distance(segPos.get(i))<radius) return true;
            return false;
        }

        public void cleanup(Node gui) {
            if (nameTag!=null) { gui.detachChild(nameTag); nameTag=null; }
            if (!dead&&physicsSpace!=null) {
                for (Geometry seg:segments) {
                    com.jme3.bullet.control.RigidBodyControl phy=seg.getControl(com.jme3.bullet.control.RigidBodyControl.class);
                    if (phy!=null) { phy.setEnabled(false); physicsSpace.remove(phy); }
                }
            }
        }

        private static Vector3f calcDir(float angle) { return new Vector3f(FastMath.sin(angle),0,FastMath.cos(angle)); }

        public void setTurnLeft(boolean v)  { turnLeft=v; }
        public void setTurnRight(boolean v) { turnRight=v; }
        public boolean isTurnLeft()  { return turnLeft; }
        public boolean isTurnRight() { return turnRight; }
        public Vector3f getHeadPos() { return segPos.isEmpty()?Vector3f.ZERO:segPos.get(0); }
        public Vector3f getDirection() { return direction; }
        public float getHeadingAngle() { return headingAngle; }
        public String getName()  { return name; }
        public int getScore()    { return score; }
        public int getLength()   { return segments.size(); }
        public boolean isDead()  { return dead; }
        public void addScore(int v) { score+=v; }
    }
}
