import com.jme3.app.SimpleApplication;
import com.jme3.input.InputManager;
import com.jme3.input.dummy.*;
import com.jme3.math.Vector3f;
import com.jme3.system.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Two real game processes connected through a lossy loopback UDP relay. */
public final class NetworkSmoke extends SimpleApplication {
    private final boolean host;
    private final int localPort,relayPort;
    private SnakeApp.GameState game;
    private ArenaCombat combat;
    private int frames;
    private boolean moved,sawIce,checked;
    private volatile boolean finished;
    private volatile Throwable failure;
    private Vector3f initial;
    NetworkSmoke(boolean host,int localPort,int relayPort){this.host=host;this.localPort=localPort;this.relayPort=relayPort;}
    @Override public void simpleInitApp(){
        try{
            DummyMouseInput mouse=new DummyMouseInput();mouse.initialize();DummyKeyInput keys=new DummyKeyInput();keys.initialize();
            inputManager=new InputManager(mouse,keys,null,null);SnakeApp.LemurUi.init(this);
            setDisplayFps(false);setDisplayStatView(false);
            assetManager.registerLocator(".",com.jme3.asset.plugins.FileLocator.class);
            InetSocketAddress relay=new InetSocketAddress("127.0.0.1",relayPort);
            DatagramSocket socket=new DatagramSocket(new InetSocketAddress("127.0.0.1",localPort));
            Map<InetSocketAddress,String> names=new HashMap<>();if(host)names.put(relay,"Client");
            game=new SnakeApp.GameState(host?"Host":"Client",Arrays.asList("Host","Client"),host?0:1,false,host,socket,"127.0.0.1",relayPort,host?Arrays.asList(relay):new ArrayList<>(),0,false,new HashMap<>(),names);
            stateManager.attach(game);
        }catch(Throwable t){fail(t);}
    }
    @Override public void simpleUpdate(float dt){
        try{
            frames++;
            if(frames==3){
                combat=(ArenaCombat)SimulationChecks.get(game,"combat");
                initial=game.combatSnake(1).getHeadPos().clone();
                if(host){
                    for(Object pilot:(Object[])SimulationChecks.get(combat,"pilots"))SimulationChecks.set(pilot,"invulnerable",999f);
                    SimulationChecks.set(combat,"intermission",999f);
                    SimulationChecks.call(game,"startFrozenArenaEvent",new Class<?>[0]);
                }
            }
            if(frames<4)return;
            SnakeApp.SnakePlayer remote=game.combatSnake(1);
            moved|=remote.getHeadPos().distance(initial)>1f;
            sawIce|=!((List<?>)SimulationChecks.get(game,"iceSpikes")).isEmpty();
            if(!host && frames>30 && frames<140){remote.setMoving(true);remote.setTurnLeft(true);}
            if(!host && frames==100){
                combat.action(1,3,1);
                game.combatSend("CMD2|0|999999|0|0|1|0|0"); // must not control the host
                game.combatSend("STATE|1|0|0|0|0|99999|100|0"); // obsolete client authority
            }
            if(!host && frames==140){remote.setMoving(false);remote.setTurnLeft(false);remote.setBraking(true);}
            if(host && frames==30){
                remote.addScore(200);
                for(int n=0;n<4;n++)combat.food(1,false);
            }
            if(host && frames==260){
                Object pilot=((Object[])SimulationChecks.get(combat,"pilots"))[1];
                SimulationChecks.check(moved,"host received held movement despite packet loss");
                SimulationChecks.check((int)SimulationChecks.get(pilot,"power")==1,"reliable client upgrade reached host");
                SimulationChecks.check(remote.getScore()<99999,"client score forgery rejected");
                SimulationChecks.check(!game.combatSnake(0).isMoving(),"sender cannot control another player");
                game.combatKill(1,"network test disconnect");
            }
            if(host && frames==300){
                SimulationChecks.check(!(boolean)SimulationChecks.get(game,"gameOver"),"cooperative match continues after partner death");checked=true;
            }
            if(!host && frames>300 && remote.isDead()){
                Object pilot=((Object[])SimulationChecks.get(combat,"pilots"))[1];
                SimulationChecks.check(moved && sawIce,"client received motion and visible ice hazards");
                SimulationChecks.check((int)SimulationChecks.get(pilot,"power")==1,"client received upgrade snapshot");
                SimulationChecks.check(remote.getScore()>=200 && remote.getScore()<99999,"host score synchronized");
                SimulationChecks.check(!((List<?>)SimulationChecks.get(game,"foodItems")).isEmpty(),"food snapshot recovered");
                SimulationChecks.check(!(boolean)SimulationChecks.get(game,"gameOver"),"client remains in cooperative match");
                checked=true;stop();
            }
            if(host && frames==400)stop();
            if(frames>650)throw new AssertionError("Network convergence timeout");
        }catch(Throwable t){fail(t);}
    }
    private void fail(Throwable t){failure=t;t.printStackTrace();stop();}
    @Override public void handleError(String message,Throwable t){fail(t==null?new RuntimeException(message):t);}
    @Override public void destroy(){try{super.destroy();}finally{finished=true;}}
    private static int freePort()throws Exception{try(DatagramSocket s=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){return s.getLocalPort();}}
    private static Process worker(String role,int port,int relay,String session)throws Exception{
        return new ProcessBuilder(System.getProperty("java.home")+"/bin/java","-cp",System.getProperty("java.class.path"),"NetworkSmoke",role,""+port,""+relay,session)
                .redirectErrorStream(true).redirectOutput(new java.io.File("build/network-"+role+".log")).start();
    }
    public static void main(String[] args)throws Exception{
        Thread.setDefaultUncaughtExceptionHandler((thread,error)->{error.printStackTrace();System.exit(1);});
        if(args.length>0){
            SnakeApp.matchSession=args[3];
            NetworkSmoke app=new NetworkSmoke("host".equals(args[0]),Integer.parseInt(args[1]),Integer.parseInt(args[2]));
            AppSettings settings=new AppSettings(true);settings.setResolution(960,640);settings.setFrameRate(50);settings.setAudioRenderer(null);
            app.setSettings(settings);app.setShowSettings(false);app.start(JmeContext.Type.Headless);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(40);
            while(!app.finished && System.nanoTime()<deadline)Thread.sleep(50);
            if(!app.finished){app.stop();throw new AssertionError("Worker timeout");}
            if(app.failure!=null || !app.checked)throw new AssertionError("Network worker failed",app.failure);
            System.out.println("PASS: "+args[0]+" game replication");return;
        }
        Files.createDirectories(Path.of("build"));int hostPort=freePort(),clientPort=freePort();String session="test-"+UUID.randomUUID();
        Process host=null,client=null;int packets=0,dropped=0,duplicated=0,reordered=0;
        try(DatagramSocket relay=new DatagramSocket(new InetSocketAddress("127.0.0.1",0))){
            relay.setSoTimeout(10);host=worker("host",hostPort,relay.getLocalPort(),session);client=worker("client",clientPort,relay.getLocalPort(),session);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(45);List<Delayed> delayed=new ArrayList<>();
            while((host.isAlive() || client.isAlive()) && System.nanoTime()<deadline){
                long now=System.nanoTime();
                for(Iterator<Delayed> it=delayed.iterator();it.hasNext();){Delayed d=it.next();if(d.at<=now){relay.send(d.packet);it.remove();}}
                DatagramPacket p=new DatagramPacket(new byte[8192],8192);
                try{relay.receive(p);}catch(SocketTimeoutException ignored){continue;}
                if(p.getPort()!=hostPort && p.getPort()!=clientPort)continue;
                packets++;if(packets%5==0){dropped++;continue;}
                DatagramPacket out=new DatagramPacket(Arrays.copyOf(p.getData(),p.getLength()),p.getLength(),InetAddress.getByName("127.0.0.1"),p.getPort()==hostPort?clientPort:hostPort);
                if(packets%17==0){delayed.add(new Delayed(out,now+TimeUnit.MILLISECONDS.toNanos(60)));reordered++;continue;}
                relay.send(out);if(packets%7==0){relay.send(out);duplicated++;}
            }
            if(host.isAlive() || client.isAlive() || host.exitValue()!=0 || client.exitValue()!=0){
                for(String role:new String[]{"host","client"})System.err.println(Files.readString(Path.of("build/network-"+role+".log")));
                throw new AssertionError("Two-process network test failed");
            }
            System.out.println("PASS: two game processes; "+packets+" packets, "+dropped+" dropped, "+duplicated+" duplicated, "+reordered+" delayed");
        }finally{if(host!=null && host.isAlive())host.destroyForcibly();if(client!=null && client.isAlive())client.destroyForcibly();}
    }
    private static final class Delayed{final DatagramPacket packet;final long at;Delayed(DatagramPacket p,long at){packet=p;this.at=at;}}
}
