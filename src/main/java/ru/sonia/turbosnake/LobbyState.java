package ru.sonia.turbosnake;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.app.Application;
import com.jme3.asset.AssetManager;
import com.jme3.renderer.queue.RenderQueue;
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

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * LobbyState — экран лобби перед началом игры.
 *
 * Режимы работы:
 *   isHost=true  — хост: принимает UDP-подключения, отправляет START всем
 *   isHost=false — клиент: подключается к хосту и ждёт START
 *   isSolo=true  — одиночная игра: сеть не используется
 *
 * Функциональность:
 *  - Выбор карты (3 кнопки: Зелёная арена, Пустыня, Ямы с шипами)
 *  - Выбор цвета змейки из палитры + предпросмотр
 *  - Синхронизация цветов игроков через UDP (COLOR|nick|r,g,b)
 *  - UDP broadcast для обнаружения лобби (HOST_HERE пакеты)
 *  - Переключатель чёрных кубов-врагов
 *
 * Сетевые пакеты (UDP):
 *   JOIN|nick           — клиент входит в лобби
 *   JOIN_ACK|mapIdx     — хост подтверждает вход
 *   PLAYER_LIST|n1,n2   — обновление списка игроков
 *   COLOR|nick|r,g,b    — цвет игрока
 *   COLORS|nick=r,g,b;… — все цвета разом
 *   MAP|idx             — смена карты
 *   START|players|...   — начало игры
 *
 * Переходы:
 *   START → GameState
 *   Назад → MainMenuState
 */
public class LobbyState extends AbstractAppState {
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
    private volatile int hostPort = TurboSnake.GAME_PORT;
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
    private static final ColorRGBA[] MAP_COLORS = { TurboSnake.ACCENT, TurboSnake.ACCENT3, TurboSnake.DANGER };

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

        app.getViewPort().setBackgroundColor(TurboSnake.BG);
        app.getInputManager().setCursorVisible(true);

        if (!players.contains(myNick)) players.add(myNick);
						lobbyColors.put(myNick, TurboSnake.selectedSnakeColor.clone());

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
								TurboSnake.selectedSnakeColor = myColor.clone();
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
        BitmapFont font = TurboSnake.loadFont(assetManager);
        float W = cam.getWidth(), H = cam.getHeight();
        float leftX = 40f;

        BitmapText title = new BitmapText(font);
        title.setSize(42); title.setText(isSolo ? "ОДИНОЧНАЯ ИГРА" : "ИГРОВОЕ ЛОББИ");
        title.setColor(TurboSnake.ACCENT);
        title.setLocalTranslation(leftX, H - 40, 0);
        guiNode.attachChild(title);

        playersText = new BitmapText(font);
        playersText.setSize(20); playersText.setColor(TurboSnake.TEXT);
        playersText.setLocalTranslation(leftX, H - 120, 0);
        guiNode.attachChild(playersText);

        mapLabel = new BitmapText(font);
        mapLabel.setSize(20); mapLabel.setColor(TurboSnake.ACCENT3);
        mapLabel.setLocalTranslation(leftX, H - 300, 0);
        guiNode.attachChild(mapLabel);

        float btnW = 240f, btnH = 45f;
        float btnStartY = H - 360f;
        for (int i = 0; i < 3; i++) {
            mapButtons[i] = new MenuButton(MAP_NAMES[i],
                    leftX + btnW/2f, btnStartY - i * (btnH + 10f),
                    btnW, btnH, TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, MAP_COLORS[i], assetManager, guiNode, 0f);
        }
						refreshMapSelection();

        searchAnim = new BitmapText(font);
        searchAnim.setSize(16); searchAnim.setColor(TurboSnake.ACCENT2);
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
                TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, TurboSnake.ACCENT, assetManager, guiNode, 0f);
										
						// Цветовая палитра и превью змейки
						buildColorPalette(W, H);
						buildSnakePreview(W, H);
						refreshColorPaletteSelection();
						refreshSnakePreviewColor();
    }

				private void buildColorPalette(float W, float H) {

						BitmapFont font = TurboSnake.loadFont(assetManager);

						BitmapText colorTitle = new BitmapText(font);
						colorTitle.setSize(18);
						colorTitle.setText("ЦВЕТ ЗМЕЙКИ");
						colorTitle.setColor(TurboSnake.ACCENT2);
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

						BitmapFont font = TurboSnake.loadFont(assetManager);

						BitmapText previewTitle = new BitmapText(font);
						previewTitle.setSize(16);
						previewTitle.setText("ПРЕВЬЮ");
						previewTitle.setColor(TurboSnake.TEXT_DIM);
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
								mat.setColor("Color", TurboSnake.selectedSnakeColor);

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
						hint.setColor(TurboSnake.TEXT_DIM);
						hint.setLocalTranslation(W - 420f, H - 360f, 3f);
						guiNode.attachChild(hint);
				}

				private void refreshSnakePreviewColor() {

						ColorRGBA c = lobbyColors.getOrDefault(myNick, TurboSnake.selectedSnakeColor);

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
												Math.abs(pc.r - TurboSnake.selectedSnakeColor.r) < 0.02f &&
												Math.abs(pc.g - TurboSnake.selectedSnakeColor.g) < 0.02f &&
												Math.abs(pc.b - TurboSnake.selectedSnakeColor.b) < 0.02f;

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

										TurboSnake.selectedSnakeColor = paletteColors.get(i).clone();
										lobbyColors.put(myNick, TurboSnake.selectedSnakeColor.clone());

										refreshColorPaletteSelection();
										refreshSnakePreviewColor();

										if (isHost) {
												broadcastColor(myNick, TurboSnake.selectedSnakeColor);
										} else if (!isSolo) {
												sendToHost("COLOR_REQ|" + myNick + "|" + encodeColor(TurboSnake.selectedSnakeColor));
										}

										TurboSnake.saveSettings(null);
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

        mapLabel.setText("ВЫБРАННАЯ КАРТА: " + MAP_NAMES[TurboSnake.selectedMap]);

        if (isSolo) {
            startHint.setText("[ ENTER ] НАЧАТЬ ИГРУ");
            startHint.setColor(TurboSnake.ACCENT3);
        } else if (isHost) {
            if (players.size() >= 2) {
                startHint.setText("[ ENTER ] ЗАПУСТИТЬ МАТЧ");
                startHint.setColor(TurboSnake.ACCENT);
            } else {
                startHint.setText("ОЖИДАНИЕ ИГРОКОВ...");
                startHint.setColor(TurboSnake.TEXT_DIM);
            }
        } else {
            startHint.setText("ОЖИДАНИЕ ХОСТА...");
            startHint.setColor(TurboSnake.TEXT_DIM);
        }
    }

				private void refreshMapSelection() {

							for (int i = 0; i < mapButtons.length; i++) {

											if (mapButtons[i] == null)
															continue;

											boolean selected = (i == TurboSnake.selectedMap);

											if (selected) {

															mapButtons[i].setBgNormal(
																							new ColorRGBA(0.12f, 0.22f, 0.50f, 1f)
															);

															mapButtons[i].setAccentColor(
																							new ColorRGBA(1f, 1f, 1f, 1f)
															);

											} else {

															mapButtons[i].setBgNormal(TurboSnake.BTN_NORMAL);

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

        inputManager.addListener((ActionListener)(n,p,t) -> { // 123
            if (!p) return;
            if ("LSend".equals(n)) {
                if (isSolo || (isHost && players.size() >= 2)) startGame();
            }
            if ("LEsc".equals(n)) backToMenu();
        }, "LSend", "LEsc");

        inputManager.addListener((ActionListener)(n,p,t) -> {
            if (!p) return;
            Vector2f mp = inputManager.getCursorPosition();
								for (int i = 0; i < mapButtons.length; i++) {
										if (mapButtons[i] != null && mapButtons[i].isHit(mp.x, mp.y)) {
												if (isSolo || isHost) {
														TurboSnake.selectedMap = i;
														refreshUI();
														refreshMapSelection();
														if (isHost) {
																broadcastMapSelection();
														}
												} else {
														showNotification("Карту выбирает только хост");
												}
												return;
										}
								}
            // переключаем чёрные кубы
            if (cubesToggleBtn != null && cubesToggleBtn.isHit(mp.x, mp.y)) {
                cubesEnabled = !cubesEnabled;
                cubesToggleBtn.setText("КУБЫ: " + (cubesEnabled ? "ВКЛ" : "ВЫКЛ"));
                cubesToggleBtn.setAccentColor(cubesEnabled ? TurboSnake.ACCENT : TurboSnake.TEXT_DIM);
            }
								
								// выбор цвета змейки
								if (handleColorPaletteClick(mp.x, mp.y)) {
										return;
								}
        }, "LClick");
    }

    private void startNetwork() {
        try { socket = new DatagramSocket(isHost ? TurboSnake.GAME_PORT : 0); socket.setSoTimeout(200); }
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
        try { broadcastSock = new DatagramSocket(TurboSnake.BROADCAST_PORT); broadcastSock.setSoTimeout(200); }
        catch (Exception e) { /* */ }
        while (!gameStarted.get() && searching.get()) {
            if (broadcastSock != null) {
                try {
                    DatagramPacket pkt = new DatagramPacket(buf, buf.length);
                    broadcastSock.receive(pkt);
                    String msg = new String(pkt.getData(), 0, pkt.getLength(), StandardCharsets.UTF_8);
                    if ("DISCOVER".equals(msg.trim())) {
                        String reply = "HOST_HERE|" + TurboSnake.GAME_PORT + "|" + MAP_NAMES[TurboSnake.selectedMap] + "|" + players.size() + "/4";
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
								case "JOIN": {
										if (gameStarted.get()) {
												sendToClient("JOIN_DENIED|Матч уже запущен", from);
												return;
										}

										String nick = p.length > 1 ? p[1].trim() : "Player";

										if (nick.isEmpty()) {
												nick = "Player";
										}

										// Если это повторный JOIN от того же клиента,
										// не отклоняем его, а просто повторно отправляем состояние лобби.
										if (players.contains(nick)) {

												if (clients.contains(from)) {
														sendToClient("LOBBY|" + String.join("|", players), from);
														sendToClient("MAP|" + TurboSnake.selectedMap, from);
														sendToClient("COLORS|" + encodeAllColors(), from);
														return;
												}

												sendToClient("JOIN_DENIED|Ник уже занят", from);
												return;
										}

										if (players.size() >= 4) {
												sendToClient("JOIN_DENIED|Лобби заполнено", from);
												return;
										}

										players.add(nick);

										if (!clients.contains(from)) {
												clients.add(from);
										}

										if (p.length > 2) {
												lobbyColors.put(nick, decodeColor(p[2]));
										} else {
												lobbyColors.putIfAbsent(
																nick,
																new ColorRGBA(0.15f, 0.9f, 0.3f, 1f)
												);
										}

										lobbyColors.putIfAbsent(myNick, TurboSnake.selectedSnakeColor.clone());

										String notif = "Игрок " + nick + " присоединился";

										app.enqueue(() -> showNotification(notif));

										broadcastToAll("NOTIF|" + notif);
										broadcastLobby();
										broadcastAllColors();

										sendToClient("MAP|" + TurboSnake.selectedMap, from);
										sendToClient("COLORS|" + encodeAllColors(), from);

										app.enqueue(this::refreshUI);
										break;
								}
								case "COLOR_REQ": {
										if (p.length > 2) {

												String colorNick = p[1];
												ColorRGBA color = decodeColor(p[2]);

												lobbyColors.put(colorNick, color);

												broadcastColor(colorNick, color);
												broadcastAllColors();

												app.enqueue(() -> {
														if (colorNick.equals(myNick)) {
																TurboSnake.selectedSnakeColor = color.clone();
																refreshColorPaletteSelection();
																refreshSnakePreviewColor();
														}
												});
										}
										break;
								}
        }
    }

    private void broadcastLobby() { broadcastToAll("LOBBY|" + String.join("|", players)); }
    private void broadcastMapSelection() { broadcastToAll("MAP|" + TurboSnake.selectedMap); }

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
                sendToHost("JOIN|" + myNick + "|" + encodeColor(TurboSnake.selectedSnakeColor));
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
								case "JOIN_DENIED":
										app.enqueue(() -> {
												showNotification(p.length > 1 ? p[1] : "Подключение отклонено");
												searching.set(false);
												if (socket != null) socket.close();

												guiNode.detachAllChildren();
												inputManager.clearMappings();
												app.getInputManager().setCursorVisible(true);

												app.getStateManager().detach(this);
												app.getStateManager().attach(new MainMenuState());
										});
										break;
            case "NOTIF":
                if (p.length > 1) showNotification(p[1]);
                break;
            case "MAP":
                if (p.length>1) { try { TurboSnake.selectedMap = Integer.parseInt(p[1]); refreshUI(); } catch (Exception ignore) {} }
                break;
								case "START":
										if (p.length > 1) {
												try {
														TurboSnake.selectedMap = Integer.parseInt(p[1]);
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
														TurboSnake.selectedSnakeColor = color.clone();
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
						broadcastToAll("START|" + TurboSnake.selectedMap + "|" + encodeAllColors());
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
														TurboSnake.selectedMap,
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
