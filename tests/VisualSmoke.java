import com.jme3.app.SimpleApplication;
import com.jme3.app.state.ScreenshotAppState;
import com.jme3.math.*;
import com.jme3.system.AppSettings;
import java.util.*;
import java.lang.reflect.*;

/** Real OpenGL smoke run, also usable under Xvfb + Mesa in CI. */
public class VisualSmoke extends SimpleApplication {
    private SnakeApp.GameState game;
    private ScreenshotAppState screenshots;
    private int frames;
    private final int map;
    private static volatile Throwable failure;
    private volatile boolean finished;
    private volatile boolean validated;
    private int simulatedFrames;
    private boolean restarted;
    private boolean headless;
    VisualSmoke(int map){this.map=map;}
    @Override public void simpleInitApp() {
        try {
            assetManager.registerLocator(".",com.jme3.asset.plugins.FileLocator.class);
            setDisplayFps(false);setDisplayStatView(false);if(flyCam!=null)flyCam.setEnabled(false);
            if(inputManager==null){
                com.jme3.input.dummy.DummyMouseInput mouse=new com.jme3.input.dummy.DummyMouseInput();mouse.initialize();
                com.jme3.input.dummy.DummyKeyInput keys=new com.jme3.input.dummy.DummyKeyInput();keys.initialize();
                inputManager=new com.jme3.input.InputManager(mouse,keys,null,null);
            }
            setPauseOnLostFocus(false);
            if(inputManager.hasMapping(SimpleApplication.INPUT_MAPPING_EXIT))inputManager.deleteMapping(SimpleApplication.INPUT_MAPPING_EXIT);
            SnakeApp.LemurUi.init(this);
            if(map<0){stateManager.attach(new SnakeApp.MainMenuState());}
            else game=new SnakeApp.GameState("Smoke",Collections.singletonList("Smoke"),0,true,true,null,null,SnakeApp.GAME_PORT,new ArrayList<>(),map,true,new HashMap<>(),new HashMap<>());
            if(game!=null)stateManager.attach(game);
            if(!headless){screenshots=new ScreenshotAppState("build/screenshots/");
            screenshots.setFileName("map-"+map+"-");stateManager.attach(screenshots);}
        }catch(Throwable e){failure=e;stop();}
    }
    @Override public void simpleUpdate(float dt) {
        try {
            frames++;
            if(map<0){if(frames==25 && screenshots!=null)screenshots.takeScreenshot();if(frames==40){validated=true;System.out.println("PASS: menu rendered");stop();}return;}
            if(frames==8){
                if(map==0)SimulationChecks.run(game);
                Field f=game.getClass().getDeclaredField("combat");f.setAccessible(true);Object combat=f.get(game);
                if(combat!=null){
                    Field ps=combat.getClass().getDeclaredField("pilots");ps.setAccessible(true);Object pilot=((Object[])ps.get(combat))[0];
                    Field shield=pilot.getClass().getDeclaredField("invulnerable");shield.setAccessible(true);shield.setFloat(pilot,999f);
                    Field wave=combat.getClass().getDeclaredField("wave");wave.setAccessible(true);wave.setInt(combat,2+(map%3)*3);
                    Field pause=combat.getClass().getDeclaredField("intermission");pause.setAccessible(true);pause.setFloat(combat,.1f);
                }else{
                    Object def=field(game,"mapDef"),ctx=field(game,"mapCtx");
                    Method start=def.getClass().getDeclaredMethod("startBattle",SnakeApp.MapContext.class);start.setAccessible(true);start.invoke(def,ctx);
                    Field kills=def.getClass().getDeclaredField("kills");kills.setAccessible(true);kills.setInt(def,10);
                    Field shield=def.getClass().getDeclaredField("playerShieldTimer");shield.setAccessible(true);shield.setFloat(def,999f);
                }
            }
            if(frames>8 && frames<160){
                SnakeApp.SnakePlayer s=game.combatSnake(0);
                if(s==null)throw new AssertionError("Map did not initialize");
                s.setMoving(true);s.setTurnLeft(true);
                for(int i=0;i<3;i++)game.update(1f/60f);
                simulatedFrames++;
                Vector3f p=s.getHeadPos();if(!Float.isFinite(p.x)||!Float.isFinite(p.y)||!Float.isFinite(p.z))throw new AssertionError("Non-finite snake position");
            }
            if(frames==100 && screenshots!=null)screenshots.takeScreenshot();
            if(frames==120 && field(game,"combat")!=null && !((ArenaCombat)field(game,"combat")).bossAlive())throw new AssertionError("Boss was not simulated");
            if(frames==135 && map==0){
                Vector3f before=game.combatSnake(0).getHeadPos().clone();
                SimulationChecks.call(game,"togglePause",new Class<?>[0]);
                game.update(.1f);
                SimulationChecks.check(before.equals(game.combatSnake(0).getHeadPos()),"solo pause freezes movement");
                SimulationChecks.check(!((com.jme3.bullet.BulletAppState)field(game,"bulletAppState")).isEnabled(),"solo pause freezes physics");
                SimulationChecks.call(game,"togglePause",new Class<?>[0]);
            }
            if(frames==160){
                Method restart=game.getClass().getDeclaredMethod("restartGame");restart.setAccessible(true);restart.invoke(game);restarted=true;
            }
            if(frames==164){
                game=stateManager.getState(SnakeApp.GameState.class);
                if(!restarted || game==null || (int)field(game,"mapIndex")!=map || game.combatSnake(0)==null || game.combatSnake(0).isDead())throw new AssertionError("Restart did not preserve the map and recreate player");
            }
            if(frames==165){
                if(simulatedFrames<100)throw new AssertionError("Insufficient simulation");
                System.out.println("PASS: "+(headless?"Headless":"OpenGL")+" map "+map+" initialized, simulated and restarted");
                validated=true;stateManager.detach(game);
            }
            if(frames==170)stop();
        }catch(Throwable e){failure=e;e.printStackTrace();stop();}
    }
    static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    @Override public void destroy(){try{super.destroy();}finally{finished=true;}}
    @Override public void handleError(String message,Throwable t){failure=t!=null?t:new RuntimeException(message);failure.printStackTrace();stop();}
    public static void main(String[] args) throws Exception {
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{error.printStackTrace();System.exit(1);});
        new java.io.File("build/screenshots").mkdirs();
        VisualSmoke app=new VisualSmoke(Integer.parseInt(args[0]));
        app.headless=args.length>1 && "--headless".equals(args[1]);
        java.nio.file.Path result=java.nio.file.Path.of("build/smoke-"+app.map+".log");
        java.nio.file.Files.deleteIfExists(result);
        AppSettings settings=new AppSettings(true);settings.setResolution(960,640);settings.setFrameRate(30);
        settings.setSamples(0);settings.setAudioRenderer(null);settings.setTitle("TurboSnake smoke");
        app.setSettings(settings);app.setShowSettings(false);app.start(app.headless?com.jme3.system.JmeContext.Type.Headless:com.jme3.system.JmeContext.Type.Display);
        long deadline=System.currentTimeMillis()+45000;
        while(!app.finished){
            Thread.sleep(100);
            if(System.currentTimeMillis()>deadline){app.stop();throw new AssertionError("Smoke timeout");}
        }
        if(failure!=null)throw new AssertionError("OpenGL smoke failed",failure);
        if(!app.validated)throw new AssertionError("Smoke closed before validation at frame "+app.frames);
        java.nio.file.Files.writeString(result,"PASS: "+(app.map<0?"menu rendered":"map "+app.map+", simulation and restart validated")+", frames="+app.frames+"\n");
    }
}
