import com.jme3.math.*;
import com.jme3.scene.Node;
import java.lang.reflect.*;
import java.util.*;

/** Exercises actual gameplay classes after an OpenGL scene has initialized. */
public final class SimulationChecks {
    private static int checks;
    static void check(boolean result,String message){checks++;if(!result)throw new AssertionError(message);}
    static Object get(Object o,String name)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}
    static void set(Object o,String name,Object value)throws Exception{Field f=o.getClass().getDeclaredField(name);f.setAccessible(true);f.set(o,value);}
    static Object call(Object o,String name,Class<?>[] types,Object...args)throws Exception{Method m=o.getClass().getDeclaredMethod(name,types);m.setAccessible(true);return m.invoke(o,args);}
    public static void run(SnakeApp.GameState game)throws Exception {
        SnakeApp.MapContext ctx=(SnakeApp.MapContext)get(game,"mapCtx");
        Vector3f reference=null;
        for(int fps:new int[]{30,60,120}){
            Node root=new Node();
            SnakeApp.SnakePlayer s=new SnakeApp.SnakePlayer("test",new Vector3f(0,1,0),0,ctx.lit(ColorRGBA.Green),root,ctx.assetManager,ctx.guiNode,ctx.camera,null,game);
            s.setMoving(true);
            for(int i=0;i<fps*2;i++)s.update(1f/fps,8f,3.2f,.55f);
            Vector3f at=s.getHeadPos().clone();
            if(reference==null)reference=at;else check(reference.distance(at)<.25f,"movement differs with frame rate");
            check(((Float)get(s,"currentSpeed"))<=8.01f,"speed cap");
            s.setBraking(true);for(int i=0;i<fps;i++)s.update(1f/fps,8f,3.2f,.55f);
            check(((Float)get(s,"currentSpeed"))<.01f,"brake must override held throttle");
            s.enableIceMode(true);s.setBraking(false);
            for(int i=0;i<fps;i++)s.update(1f/fps,8f,3.2f,.55f);
            check(Float.isFinite(s.getHeadPos().y)&&((Float)get(s,"currentSpeed"))<=10.01f,"ice motion bounded");
            for(int i=0;i<160;i++)s.grow(ctx.assetManager);
            check(s.getLength()==ArenaRules.MAX_LENGTH,"growth cap");
            s.removeSegmentsAtWorldPos(s.getHeadPos(),1000f);
            check(s.getLength()==2,"tail damage must retain head and body");
            check(((List<?>)get(s,"tailCurrentY")).size()==s.getLength(),"tail height indices after damage");
            s.cleanup(ctx.guiNode);
        }
        ArenaCombat combat=(ArenaCombat)get(game,"combat");
        if(combat!=null){
            Object pilot=((Object[])get(combat,"pilots"))[0];
            for(int n=0;n<4;n++)combat.food(0,false);
            check((int)get(pilot,"choices")==1,"food grants an upgrade");
            combat.action(0,3,1);
            check((int)get(pilot,"power")==1 && (int)get(pilot,"choices")==0,"upgrade consumes one choice");
            combat.hurt(0,1);int hp=(int)get(pilot,"hp");combat.hurt(0,1);
            check((int)get(pilot,"hp")==hp,"damage invulnerability");
            call(combat,"spawnEnemy",new Class<?>[]{int.class},0);
            Object enemy=((List<?>)get(combat,"enemies")).get(0);
            set(enemy,"warm",0f);
            Field x=enemy.getClass().getSuperclass().getDeclaredField("x"),z=enemy.getClass().getSuperclass().getDeclaredField("z");x.setAccessible(true);z.setAccessible(true);
            Vector3f head=ctx.getSnakeHead(0);x.setFloat(enemy,head.x+2f);z.setFloat(enemy,head.z);
            combat.action(0,2,2);
            check(((List<?>)get(combat,"enemies")).isEmpty(),"pulse destroys enemy");
            check(ctx.game.combatSnake(0).getScore()>0,"enemy kill awards points");
            call(combat,"spawnEnemy",new Class<?>[]{int.class},4);
            Object boss=((List<?>)get(combat,"enemies")).get(0);set(boss,"warm",0f);set(boss,"hp",1);
            call(combat,"updateEnemy",new Class<?>[]{boss.getClass(),float.class},boss,.01f);
            check((int)get(boss,"phase")==2,"boss enters final phase");
            call(combat,"hit",new Class<?>[]{boss.getClass(),int.class,int.class},boss,1,0);
            check(!combat.bossAlive() && (int)get(pilot,"bosses")==1,"boss reward and removal");
            set(pilot,"pulse",0f);set(pilot,"hp",get(pilot,"maxHp"));
        }
        System.out.println("PASS: "+checks+" gameplay integration checks");
    }
}
