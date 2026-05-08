package ru.sonia.turbosnake;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.app.Application;
import com.jme3.asset.AssetManager;
import com.jme3.audio.AudioNode;
import com.jme3.input.RawInputListener;
import com.jme3.input.event.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.material.RenderState;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.InputManager;
import com.jme3.input.controls.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;
import com.jme3.scene.shape.Sphere;
import com.jme3.ui.Picture;

/**
 * MainMenuState — главный экран игры.
 *
 * Что делает:
 *  - Показывает фоновое изображение и логотип
 *  - Позволяет ввести никнейм (поле ввода с курсором-миганием)
 *  - Кнопки: Одиночная игра, Сетевая игра, выход
 *  - Панель настроек (2 вкладки: Основное и Графика)
 *     * Ползунки громкости SFX / Music
 *     * Переключатели теней, частиц, тумана, блума
 *  - Декоративные вращающиеся шарики на фоне (3D сцена)
 *  - Перестраивает UI при изменении размера окна
 *
 * Переходы:
 *   Одиночная → LobbyState(solo=true)
 *   Сетевая   → ServerListState
 */
public class MainMenuState extends AbstractAppState {
    private SimpleApplication app;
    private Node rootNode, guiNode;
    private AssetManager assetManager;
    private InputManager inputManager;
    private Camera cam;
    private Picture bgPicture;

    private BitmapText titleText, subtitleText, infoText;
    private BitmapText nicknameText, cursorBlink;
    private StringBuilder nickname = new StringBuilder(TurboSnake.savedNickname.isEmpty() ? TurboSnake.getSystemUsername() : TurboSnake.savedNickname);
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
        app.getViewPort().setBackgroundColor(TurboSnake.BG);
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
            app.getViewPort().setBackgroundColor(TurboSnake.BG);
        }
    }

    private void setupDecorBalls() {
        decorNode = new Node("Decor"); rootNode.attachChild(decorNode);
        int n = 12;
        ballAngles = new float[n]; ballRadii = new float[n];
        ballSpeeds = new float[n]; ballY = new float[n];
        ColorRGBA[] cols = { TurboSnake.ACCENT, TurboSnake.ACCENT2, TurboSnake.ACCENT3, TurboSnake.DANGER };
        for (int i=0;i<n;i++) {
            ballAngles[i] = (float)i/n*FastMath.TWO_PI;
            ballRadii[i] = 2f + FastMath.nextRandomFloat()*3f;
            ballSpeeds[i] = 0.3f + FastMath.nextRandomFloat()*0.5f;
            ballY[i] = FastMath.nextRandomFloat()*3f - 1f;
            float r = 0.08f + FastMath.nextRandomFloat()*0.12f;
            Geometry g = new Geometry("DB"+i, new Sphere(8,8,r));
            g.setMaterial(TurboSnake.unshaded(assetManager, cols[i%cols.length]));
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
						BitmapFont font = TurboSnake.loadFont(assetManager);

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

                                TurboSnake.BTN_NORMAL,
                                TurboSnake.BTN_HOVER,
                                TurboSnake.BTN_PRESS,

                                TurboSnake.TEXT,

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

                                TurboSnake.BTN_NORMAL,
                                TurboSnake.BTN_HOVER,
                                TurboSnake.BTN_PRESS,

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

                                TurboSnake.BTN_NORMAL,
                                TurboSnake.BTN_HOVER,
                                TurboSnake.BTN_PRESS,

										new ColorRGBA(0.70f,0.65f,1f,1f),

										assetManager,
										guiNode,
										5f
						);

						// =========================================================
						// FOOTER SnakeApp.TEXT
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
        BitmapFont font = TurboSnake.loadFont(assetManager);
        settingsPanel = new Node("SettingsPanel");

        // ── Затемнение ──
        Box dimBox = new Box(W/2f, H/2f, 0.1f);
        Geometry dimGeo = new Geometry("SettingsDim", dimBox);
        Material dimMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        dimMat.setColor("Color", new ColorRGBA(0f,0f,0f,0.75f));
        dimMat.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        dimGeo.setMaterial(dimMat); 
						dimGeo.setLocalTranslation(cx, cy, 45f);
        settingsPanel.attachChild(dimGeo);

        // ── Основная карточка ──
        float panelW = 460f, panelH = 500f;
        Box panelBox = new Box(panelW/2f, panelH/2f, 0.5f);
        Geometry panelGeo = new Geometry("PanelBg", panelBox);
        Material pm = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        pm.setColor("Color", TurboSnake.BG_CARD);
        pm.getAdditionalRenderState().setBlendMode(RenderState.BlendMode.Alpha);
        panelGeo.setMaterial(pm); 
						panelGeo.setLocalTranslation(cx, cy, 46f);
        settingsPanel.attachChild(panelGeo);

        final float Z = 50f;

        // ── Акцентная полоса сверху ──
        Geometry headerLine = new Geometry("PanelHeaderLine", new Box(panelW/2f, 3f, 0.3f));
        headerLine.setMaterial(TurboSnake.unshaded(assetManager, TurboSnake.ACCENT));
        headerLine.setLocalTranslation(cx, cy + panelH/2f - 2f, Z);
        settingsPanel.attachChild(headerLine);

        // ── Заголовок ──
        BitmapText header = new BitmapText(font);
        header.setSize(28); header.setText("НАСТРОЙКИ"); header.setColor(TurboSnake.ACCENT2);
        header.setLocalTranslation(cx - header.getLineWidth()/2, cy + panelH/2f - 40f, Z);
        settingsPanel.attachChild(header);

        // ── Разделитель под заголовком ──
        Geometry divLine = new Geometry("DivLine", new Box(panelW/2f - 20f, 1.5f, 0.2f));
        divLine.setMaterial(TurboSnake.unshaded(assetManager, TurboSnake.BORDER));
        divLine.setLocalTranslation(cx, cy + panelH/2f - 64f, Z);
        settingsPanel.attachChild(divLine);

        // ── Вкладки (TAB BAR) ──
        float tabY = cy + panelH/2f - 90f;
        float tabW = 180f, tabH = 38f;
        float tabGap = 10f;

        // Фон полоски вкладок
        Geometry tabBg = new Geometry("TabBg", new Box(panelW/2f - 10f, tabH/2f + 4f, 0.3f));
        tabBg.setMaterial(TurboSnake.unshaded(assetManager, new ColorRGBA(0.04f, 0.06f, 0.14f, 1f)));
        tabBg.setLocalTranslation(cx, tabY, Z - 0.1f);
        settingsPanel.attachChild(tabBg);

        tabMainBtn = new MenuButton("◆ ОСНОВНОЕ", cx - tabW/2f - tabGap/2f, tabY, tabW, tabH,
                TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, TurboSnake.ACCENT2, assetManager, settingsPanel, Z);
        tabGraphicsBtn = new MenuButton("◆ ГРАФИКА", cx + tabW/2f + tabGap/2f, tabY, tabW, tabH,
                TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, TurboSnake.TEXT_DIM, assetManager, settingsPanel, Z);

        // ── ВКЛАДКА 0: ОСНОВНОЕ ──────────────────────────────────────────
        float contentTop = tabY - tabH/2f - 20f;

        BitmapText sfxLabel = new BitmapText(font);
        sfxLabel.setName("SfxLabel");
        sfxLabel.setSize(18); sfxLabel.setText("🔊  Звуки"); sfxLabel.setColor(TurboSnake.TEXT);
        sfxLabel.setLocalTranslation(cx - panelW/2f + 25f, contentTop, Z);
        settingsPanel.attachChild(sfxLabel);
        sfxVal = new BitmapText(font);
        sfxVal.setSize(18); sfxVal.setColor(TurboSnake.ACCENT2);
        sfxVal.setLocalTranslation(cx + panelW/2f - 70f, contentTop, Z);
        settingsPanel.attachChild(sfxVal);
        sfxSlider = new VolumeSlider(cx, contentTop - 28f, panelW - 60f, TurboSnake.effectVolume, assetManager, settingsPanel, Z);

        BitmapText musicLabel = new BitmapText(font);
        musicLabel.setName("MusicLabel");
        musicLabel.setSize(18); musicLabel.setText("♪  Музыка"); musicLabel.setColor(TurboSnake.TEXT);
        musicLabel.setLocalTranslation(cx - panelW/2f + 25f, contentTop - 72f, Z);
        settingsPanel.attachChild(musicLabel);
        musicVal = new BitmapText(font);
        musicVal.setSize(18); musicVal.setColor(TurboSnake.ACCENT);
        musicVal.setLocalTranslation(cx + panelW/2f - 70f, contentTop - 72f, Z);
        settingsPanel.attachChild(musicVal);
        musicSlider = new VolumeSlider(cx, contentTop - 100f, panelW - 60f, TurboSnake.musicVolume, assetManager, settingsPanel, Z);

        // Разделитель
        Geometry div2 = new Geometry("Div2", new Box(panelW/2f - 30f, 1f, 0.2f));
        div2.setName("NickDivider");
        div2.setMaterial(TurboSnake.unshaded(assetManager, TurboSnake.BORDER));
        div2.setLocalTranslation(cx, contentTop - 138f, Z);
        settingsPanel.attachChild(div2);

        BitmapText nickLabel = new BitmapText(font);
        nickLabel.setName("NickLabel");
        nickLabel.setSize(15); nickLabel.setText("ИМЯ ИГРОКА");
        nickLabel.setColor(TurboSnake.TEXT_DIM);
        nickLabel.setLocalTranslation(cx - panelW/2f + 25f, contentTop - 155f, Z);
        settingsPanel.attachChild(nickLabel);

        Box nickBorder = new Box(panelW/2f - 25f, 22f, 0.2f);
        Geometry nickBorderGeo = new Geometry("NickBorder", nickBorder);
        nickBorderGeo.setMaterial(TurboSnake.unshaded(assetManager, TurboSnake.ACCENT2));
        nickBorderGeo.setLocalTranslation(cx, contentTop - 190f, Z - 0.1f);
        settingsPanel.attachChild(nickBorderGeo);
        Box nickBg = new Box(panelW/2f - 27f, 20f, 0.3f);
        Geometry nickBgGeo = new Geometry("NickBg", nickBg);
        nickBgGeo.setMaterial(TurboSnake.unshaded(assetManager, new ColorRGBA(0.04f,0.06f,0.14f,1f)));
        nickBgGeo.setLocalTranslation(cx, contentTop - 190f, Z);
        settingsPanel.attachChild(nickBgGeo);
        nicknameText = new BitmapText(font);
        nicknameText.setSize(22); nicknameText.setText(nickname.toString());
        nicknameText.setColor(TurboSnake.ACCENT3);
        nicknameText.setLocalTranslation(cx - panelW/2f + 32f, contentTop - 178f, Z + 0.5f);
        settingsPanel.attachChild(nicknameText);
        cursorBlink = new BitmapText(font);
        cursorBlink.setSize(22); cursorBlink.setText("|");
        cursorBlink.setColor(TurboSnake.ACCENT3);
        settingsPanel.attachChild(cursorBlink);

        // ── ВКЛАДКА 1: ГРАФИКА ───────────────────────────────────────────
        float gTop = contentTop - 5f;
        float toggleW = panelW - 60f, toggleH = 44f, toggleGap = 14f;

        // Заголовок раздела (скрыт по умолчанию, только на вкладке Графика)
        BitmapText gfxHeader = new BitmapText(font);
        gfxHeader.setName("GfxHeader");
        gfxHeader.setSize(15); gfxHeader.setText("ПАРАМЕТРЫ ОТОБРАЖЕНИЯ");
        gfxHeader.setColor(TurboSnake.TEXT_DIM);
        gfxHeader.setLocalTranslation(cx - gfxHeader.getLineWidth()/2f - 30f, gTop + 12f, Z);
        settingsPanel.attachChild(gfxHeader);

        btnShadows = new MenuButton(
                TurboSnake.shadowsEnabled   ? "✔  ТЕНИ — ВКЛ"   : "✘  ТЕНИ — ВЫКЛ",
                cx, gTop - toggleH/2f, toggleW, toggleH,
                TurboSnake.shadowsEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : TurboSnake.BTN_NORMAL,
                TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS,
                TurboSnake.shadowsEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM, assetManager, settingsPanel, Z);
        btnShadows.bgGeo.setName("BtnShadows");

        btnParticles = new MenuButton(
                TurboSnake.particlesEnabled ? "✔  ЧАСТИЦЫ — ВКЛ" : "✘  ЧАСТИЦЫ — ВЫКЛ",
                cx, gTop - toggleH/2f - (toggleH + toggleGap), toggleW, toggleH,
                TurboSnake.particlesEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : TurboSnake.BTN_NORMAL,
                TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS,
                TurboSnake.particlesEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM, assetManager, settingsPanel, Z);

        btnFog = new MenuButton(
                TurboSnake.fogEnabled       ? "✔  ТУМАН — ВКЛ"   : "✘  ТУМАН — ВЫКЛ",
                cx, gTop - toggleH/2f - (toggleH + toggleGap)*2f, toggleW, toggleH,
                TurboSnake.fogEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : TurboSnake.BTN_NORMAL,
                TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS,
                TurboSnake.fogEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM, assetManager, settingsPanel, Z);

        btnBloom = new MenuButton(
                TurboSnake.bloomEnabled     ? "✔  BLOOM — ВКЛ"   : "✘  BLOOM — ВЫКЛ",
                cx, gTop - toggleH/2f - (toggleH + toggleGap)*3f, toggleW, toggleH,
                TurboSnake.bloomEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : TurboSnake.BTN_NORMAL,
                TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS,
                TurboSnake.bloomEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM, assetManager, settingsPanel, Z);

        // ── Кнопка ЗАКРЫТЬ (общая) ──
        float closeY = cy - panelH/2f + 40f;
        settingsClose = new MenuButton("✔  СОХРАНИТЬ И ЗАКРЫТЬ", cx, closeY, panelW - 60f, 46f,
                TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, TurboSnake.ACCENT, assetManager, settingsPanel, Z);

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
            tabMainBtn.setAccentColor(tab == 0 ? TurboSnake.ACCENT2 : TurboSnake.TEXT_DIM);
        }
        if (tabGraphicsBtn != null) {
            tabGraphicsBtn.setAccentColor(tab == 1 ? TurboSnake.ACCENT2 : TurboSnake.TEXT_DIM);
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
        if (sfxVal   != null) sfxVal.setText(Math.round(TurboSnake.effectVolume * 100) + "%");
        if (musicVal != null) musicVal.setText(Math.round(TurboSnake.musicVolume * 100) + "%");
        if (sfxSlider   != null) sfxSlider.setValue(TurboSnake.effectVolume);
        if (musicSlider != null) musicSlider.setValue(TurboSnake.musicVolume);
        MusicManager.setVolume(TurboSnake.musicVolume);
    }

    private void startMenuMusic() {
        // использовать глобальный MusicManager — не пересоздавать звук при возврате в меню
        MusicManager.play(assetManager, rootNode, "Sounds/theme/main1.ogg", TurboSnake.musicVolume);
    }

    private void refreshNick() {
        nicknameText.setText(nickname.toString());
        float x = nicknameText.getLocalTranslation().x;
        float y = nicknameText.getLocalTranslation().y;
        cursorBlink.setLocalTranslation(x + nicknameText.getLineWidth() + 2, y, 1f);
    }

    private void setupInput() {
        inputManager.addRawInputListener(new RawInputListener() {
            @Override public void onKeyEvent(KeyInputEvent evt) {
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
            @Override public void onMouseMotionEvent(MouseMotionEvent evt) {}
            @Override public void onMouseButtonEvent(MouseButtonEvent evt) {}
            @Override public void onJoyAxisEvent(JoyAxisEvent evt) {}
            @Override public void onJoyButtonEvent(JoyButtonEvent evt) {}
            @Override public void onTouchEvent(TouchEvent evt) {}
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
            Vector2f mp = inputManager.getCursorPosition();
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
            Vector2f mp = inputManager.getCursorPosition();
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
														TurboSnake.saveSettings(nickname.toString());
												} else if (tabMainBtn.isHit(mx, my)) {
														setSettingsTab(0);
												} else if (tabGraphicsBtn.isHit(mx, my)) {
														setSettingsTab(1);
												} else if (activeSettingsTab == 1) {
														// ---------- обработка кнопок графики ----------
														if (btnShadows != null && btnShadows.onRelease(mx, my)) {
																TurboSnake.shadowsEnabled = !TurboSnake.shadowsEnabled;
																btnShadows.setText(TurboSnake.shadowsEnabled ? "✔  ТЕНИ — ВКЛ" : "✘  ТЕНИ — ВЫКЛ");
																btnShadows.setAccentColor(TurboSnake.shadowsEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM);
																btnShadows.setBgNormal(TurboSnake.shadowsEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : TurboSnake.BTN_NORMAL);
                                                            TurboSnake.saveSettings(nickname.toString());
														} else if (btnParticles != null && btnParticles.onRelease(mx, my)) {
																TurboSnake.particlesEnabled = !TurboSnake.particlesEnabled;
																btnParticles.setText(TurboSnake.particlesEnabled ? "✔  ЧАСТИЦЫ — ВКЛ" : "✘  ЧАСТИЦЫ — ВЫКЛ");
																btnParticles.setAccentColor(TurboSnake.particlesEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM);
																btnParticles.setBgNormal(TurboSnake.particlesEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : TurboSnake.BTN_NORMAL);
																TurboSnake.saveSettings(nickname.toString());
														} else if (btnFog != null && btnFog.onRelease(mx, my)) {
																TurboSnake.fogEnabled = !TurboSnake.fogEnabled;
																btnFog.setText(TurboSnake.fogEnabled ? "✔  ТУМАН — ВКЛ" : "✘  ТУМАН — ВЫКЛ");
																btnFog.setAccentColor(TurboSnake.fogEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM);
																btnFog.setBgNormal(TurboSnake.fogEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : TurboSnake.BTN_NORMAL);
																TurboSnake.saveSettings(nickname.toString());
														} else if (btnBloom != null && btnBloom.onRelease(mx, my)) {
																TurboSnake.bloomEnabled = !TurboSnake.bloomEnabled;
																btnBloom.setText(TurboSnake.bloomEnabled ? "✔  BLOOM — ВКЛ" : "✘  BLOOM — ВЫКЛ");
																btnBloom.setAccentColor(TurboSnake.bloomEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM);
																btnBloom.setBgNormal(TurboSnake.bloomEnabled ? new ColorRGBA(0.04f,0.14f,0.08f,0.9f) : TurboSnake.BTN_NORMAL);
																TurboSnake.saveSettings(nickname.toString());
														}
												} else if (activeSettingsTab == 0 && sfxSlider.onClick(mx, my)) {
														TurboSnake.effectVolume = sfxSlider.getValue(); updateSettingsLabels();
												} else if (activeSettingsTab == 0 && musicSlider.onClick(mx, my)) {
														TurboSnake.musicVolume = musicSlider.getValue(); updateSettingsLabels();
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
        TurboSnake.savedNickname = nick;
        TurboSnake.saveSettings(nick);
        // НЕ останавливаем музыку — MusicManager обеспечивает непрерывное воспроизведение
        MusicManager.setVolume(TurboSnake.musicVolume);
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
            cursorBlink.setColor(cursorVisible ? TurboSnake.ACCENT3 : new ColorRGBA(0,0,0,0));
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
