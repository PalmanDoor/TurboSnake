package ru.sonia.turbosnake;

import com.jme3.app.SimpleApplication;
import com.jme3.app.state.AbstractAppState;
import com.jme3.app.state.AppStateManager;
import com.jme3.app.Application;
import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.input.KeyInput;
import com.jme3.input.MouseInput;
import com.jme3.input.InputManager;
import com.jme3.input.controls.*;
import com.jme3.math.*;
import com.jme3.renderer.Camera;
import com.jme3.scene.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * ServerListState — экран выбора сервера для сетевой игры.
 *
 * Алгоритм обнаружения серверов:
 *  1. Отправляем UDP broadcast "DISCOVER" на порт BROADCAST_PORT (45678)
 *  2. Ждём ответы "HOST_HERE|port|mapName|playerCount" 200 мс каждый
 *  3. Добавляем найденные серверы в список кнопок
 *
 * Внутренний класс:
 *   ServerEntry — данные одного найденного сервера (IP, карта, кол-во игроков, пинг)
 *
 * Переходы:
 *   Выбор сервера → LobbyState(isHost=false, адрес сервера)
 *   Создать лобби → LobbyState(isHost=true)
 *   Назад         → MainMenuState
 */
public class ServerListState extends AbstractAppState {
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
        app.getViewPort().setBackgroundColor(TurboSnake.BG);
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
        BitmapFont font = TurboSnake.loadFont(assetManager);
        float W = cam.getWidth(), H = cam.getHeight();

        titleText = new BitmapText(font);
        titleText.setSize(42); titleText.setText("ДОСТУПНЫЕ ЛОББИ");
        titleText.setColor(TurboSnake.ACCENT);
        titleText.setLocalTranslation(W/2f - titleText.getLineWidth()/2, H - 40, 0);
        guiNode.attachChild(titleText);

        statusText = new BitmapText(font);
        statusText.setSize(20); statusText.setColor(TurboSnake.TEXT_DIM);
        statusText.setLocalTranslation(40, H - 100, 0);
        guiNode.attachChild(statusText);

        float btnW = 280f, btnH = 45f;
        float commonY = 40f; // Выносим общую высоту в переменную для удобства

        // Кнопка «Создать лобби» — теперь внизу (commonY)
        createBtn = new MenuButton("Создать лобби", 40f + btnW/2f, commonY, btnW, btnH,
                TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, TurboSnake.ACCENT3, assetManager, guiNode, 0f);

        // Кнопка «Обновить список» — на той же высоте
        refreshBtn = new MenuButton("Обновить", 40f + btnW + 20f + btnW/2f, commonY, btnW, btnH,
                TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, TurboSnake.ACCENT2, assetManager, guiNode, 0f);

        // Кнопка «Назад»
        backBtn = new MenuButton("Назад", W - 40f - 140f/2f, commonY, 140f, btnH,
                TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, TurboSnake.DANGER, assetManager, guiNode, 0f);
    }

    private void updateServerListGUI() {
        for (MenuButton b : serverButtons) b.detach(guiNode);
        serverButtons.clear();

        float y = cam.getHeight() - 150f;
        for (ServerEntry entry : serverEntries) {
            String label = entry.ip + " | " + entry.mapName + " | " + entry.playerCount + " | " + entry.pingMs + "мс";
            MenuButton btn = new MenuButton(label, 40f + 250f, y, 500f, 45f,
                    TurboSnake.BTN_NORMAL, TurboSnake.BTN_HOVER, TurboSnake.BTN_PRESS, TurboSnake.ACCENT2, assetManager, guiNode, 0f);
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
                discoverSocket.send(new DatagramPacket(db, db.length, ba, TurboSnake.BROADCAST_PORT));

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
        int port = parts.length > 1 ? Integer.parseInt(parts[1]) : TurboSnake.GAME_PORT;
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
