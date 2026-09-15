import com.jme3.font.*;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.*;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.scene.*;
import com.jme3.scene.shape.*;
import com.jme3.util.BufferUtils;
import java.util.*;

/** Cooperative wave combat for the four snake arenas. Simulation is host-owned. */
public final class ArenaCombat {
    private final SnakeApp.MapContext ctx;
    private final Node world=new Node("ArenaCombat");
    private final Node hud=new Node("CombatHud");
    private final Random rng=new Random();
    private final List<Enemy> enemies=new ArrayList<>();
    private final List<Shot> shots=new ArrayList<>();
    private final List<Hazard> hazards=new ArrayList<>();
    private final List<Pickup> pickups=new ArrayList<>();
    private final List<Effect> effects=new ArrayList<>();
    private final Pilot[] pilots;
    private final BitmapText status, objective, abilities, bossText, upgrades;
    private final Geometry bossBar;
    private final Geometry statusBackground;
    private boolean fireHeld, saved;
    private int nextId=1, wave, toSpawn, event=-1, lastEvent=-1;
    private float time, spawnTimer, intermission=8f, netTimer, eventTimer, nextEvent=45f;
    private long tick, worldTick=-1, manifestTick=-1, commandId;
    private final Set<Integer> enemyManifest=new HashSet<>(), shotManifest=new HashSet<>(),
            hazardManifest=new HashSet<>(), pickupManifest=new HashSet<>();
    private static final String[] NAMES={"Охотник","Таран","Стрелок","Оса","Страж","Колосс","Улей"};
    private static final ColorRGBA[] COLORS={
        new ColorRGBA(1,.32f,.2f,1),new ColorRGBA(1,.7f,.16f,1),
        new ColorRGBA(.7f,.36f,1,1),new ColorRGBA(.2f,.85f,1,1),
        new ColorRGBA(.85f,.28f,1,1),new ColorRGBA(1,.35f,.13f,1),
        new ColorRGBA(.35f,1,.55f,1)};
    private final ActionListener input=this::onInput;
    private void onInput(String name, boolean pressed, float tpf) {
        if(!ctx.game.acceptsGameplayInput()) { fireHeld=false; return; }
        if("CombatFire".equals(name)) fireHeld=pressed;
        if(!pressed) return;
        if("CombatPulse".equals(name)) action(ctx.myIndex,2,++commandId);
        if("CombatUpgrade1".equals(name)) action(ctx.myIndex,3,++commandId);
        if("CombatUpgrade2".equals(name)) action(ctx.myIndex,4,++commandId);
        if("CombatUpgrade3".equals(name)) action(ctx.myIndex,5,++commandId);
    }
    private static final class Pilot {
        int hp=4,maxHp=4,xp,level=1,choices,power,rapid,kills,combo,bosses;
        float fire,pulse,dash,dashTime,invulnerable,comboTime;
        long lastAction=-1, lastSnapshot=-1;
    }
    private static class Entity {
        int id; float x,z; long last=-1; Node node;
    }
    private static final class Enemy extends Entity {
        int kind,hp,maxHp,phase; float warm=.9f,cooldown=1.5f,angle,charge,vx,vz;
        final Map<Integer,Float> dashHits=new HashMap<>();
    }
    private static final class Shot extends Entity {
        float vx,vz,life=2.2f; int owner,damage; float radius=.22f;
    }
    private static final class Hazard extends Entity { float radius=4f,wait=1.6f; }
    private static final class Pickup extends Entity { int kind; float life=25f; }
    private static final class Effect { Node node; float life,total; }
    public ArenaCombat(SnakeApp.MapContext context) {
        ctx=context; pilots=new Pilot[ctx.players.size()];
        for(int i=0;i<pilots.length;i++) pilots[i]=new Pilot();
        ctx.rootNode.attachChild(world); ctx.guiNode.attachChild(hud);
        BitmapFont f=SnakeApp.loadFont(ctx.assetManager);
        status=text(f,19,SnakeApp.TEXT); objective=text(f,18,SnakeApp.ACCENT3);
        abilities=text(f,16,SnakeApp.TEXT_DIM); bossText=text(f,18,SnakeApp.DANGER);
        upgrades=text(f,18,SnakeApp.ACCENT);
        bossBar=new Geometry("BossHealth",new Quad(1,1));
        bossBar.setMaterial(ctx.unshaded(SnakeApp.DANGER)); hud.attachChild(bossBar);
        statusBackground=new Geometry("CombatStatusPanel",new Quad(1,1));
        Material background=ctx.unshaded(new ColorRGBA(.025f,.045f,.065f,.82f));
        background.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        statusBackground.setMaterial(background);hud.attachChild(statusBackground);
        install(); decorate();
        notice("SPACE — огонь   E — импульс   SHIFT — боевой рывок",SnakeApp.ACCENT);
    }
    private BitmapText text(BitmapFont font,float size,ColorRGBA color) {
        BitmapText t=new BitmapText(font); t.setSize(size); t.setColor(color); hud.attachChild(t); return t;
    }
    private void install() {
        add("CombatFire",KeyInput.KEY_SPACE); add("CombatPulse",KeyInput.KEY_E);
        add("CombatUpgrade1",KeyInput.KEY_1); add("CombatUpgrade2",KeyInput.KEY_2); add("CombatUpgrade3",KeyInput.KEY_3);
        ctx.inputManager.addListener(input,"CombatFire","CombatPulse","CombatUpgrade1","CombatUpgrade2","CombatUpgrade3");
    }
    private void add(String n,int key) { if(!ctx.inputManager.hasMapping(n)) ctx.inputManager.addMapping(n,new KeyTrigger(key)); }
    public void clearInput() { fireHeld=false; }
    public boolean firing() { return fireHeld && ctx.game.acceptsGameplayInput(); }
    private SnakeApp.SnakePlayer snake(int i) { return ctx.game.combatSnake(i); }
    private boolean alive(int i) { return i>=0 && i<pilots.length && snake(i)!=null && !snake(i).isDead(); }
    private boolean host() { return ctx.isHostLogic(); }
    private float floor(float x,float z) { return ctx.game.getSurfaceHeight(x,z); }
    private void notice(String s,ColorRGBA c) { ctx.game.combatNotice(s,c); }
    public float speedMultiplier(int i) { return i>=0 && i<pilots.length && pilots[i].dashTime>0 ? 3.1f:1f; }
    public float dashCooldown(int i) { return pilots[i].dash; }
    public float dashTime(int i) { return pilots[i].dashTime; }
    public void dash() { action(ctx.myIndex,1,++commandId); }
    public void action(int i,int kind,long sequence) {
        if(!alive(i)) return;
        if(!host()) { ctx.game.combatSend("ACT2|"+i+"|"+kind+"|"+sequence); return; }
        Pilot p=pilots[i];
        if(sequence<=0) return;
        if(kind==1 && p.dash<=0) { p.dash=3.5f; p.dashTime=.42f; p.invulnerable=Math.max(p.invulnerable,.42f); }
        if(kind==2 && p.pulse<=0) {
            p.pulse=8f; Vector3f at=ctx.getSnakeHead(i); burst(at.x,at.z,SnakeApp.ACCENT2,7f);
            for(Enemy e:new ArrayList<>(enemies)) if(dist(e.x,e.z,at.x,at.z)<49f) hit(e,3+p.power,i);
            for(Shot s:new ArrayList<>(shots)) if(s.owner<0 && dist(s.x,s.z,at.x,at.z)<64f) removeShot(s);
            ctx.game.combatPulseCubes(at,7f,3+p.power,i);
        }
        if(kind>=3 && kind<=5 && p.choices>0) {
            if((kind==3 && p.power>=8) || (kind==4 && p.rapid>=6) || (kind==5 && p.maxHp>=10 && p.hp>=p.maxHp))return;
            p.choices--;
            if(kind==3) p.power=Math.min(8,p.power+1);
            if(kind==4) p.rapid=Math.min(6,p.rapid+1);
            if(kind==5) { p.maxHp=Math.min(10,p.maxHp+1); p.hp=p.maxHp; }
        }
    }
    public void food(int i,boolean bad) { if(host() && alive(i) && !bad) reward(i,1,0); }
    public void cubeReward(int i) { if(host() && alive(i)) reward(i,3,35); }
    private void reward(int i,int xp,int points) {
        Pilot p=pilots[i];
        int multiplier=event==0?2:1;
        p.xp+=xp*multiplier;
        if(points>0) {
            p.kills++; p.combo=Math.min(5,p.combo+1); p.comboTime=6f;
            snake(i).addScore(points*multiplier*Math.max(1,p.combo));
        }
        while(p.xp>=ArenaRules.levelCost(p.level)) {
            p.xp-=ArenaRules.levelCost(p.level); p.level++; p.choices++;
            p.hp=Math.min(p.maxHp,p.hp+1);
        }
    }
    public void hurt(int i,int damage) {
        if(!host() || !alive(i)) return;
        Pilot p=pilots[i]; if(p.invulnerable>0) return;
        p.hp=Math.max(0,p.hp-damage); p.invulnerable=1.1f; p.combo=0; p.comboTime=0;
        Vector3f at=ctx.getSnakeHead(i); burst(at.x,at.z,SnakeApp.DANGER,1.5f);
        if(p.hp<=0) ctx.game.combatKill(i,"потеряна вся броня");
        else { snake(i).shrink(); if(i==ctx.myIndex) notice("Попадание! Броня "+p.hp+"/"+p.maxHp,SnakeApp.DANGER); }
    }
    public boolean bossAlive() { for(Enemy e:enemies) if(e.kind>=4 && e.hp>0)return true; return false; }
    public void step(float dt,boolean[] remoteFire) {
        if(!host()) return;
        time+=dt;
        for(int i=0;i<pilots.length;i++) {
            Pilot p=pilots[i];
            p.fire=Math.max(0,p.fire-dt); p.pulse=Math.max(0,p.pulse-dt);
            p.dash=Math.max(0,p.dash-dt); p.dashTime=Math.max(0,p.dashTime-dt);
            p.invulnerable=Math.max(0,p.invulnerable-dt);
            p.comboTime=Math.max(0,p.comboTime-dt); if(p.comboTime==0)p.combo=0;
            boolean trigger=i==ctx.myIndex?firing():remoteFire[i];
            if(alive(i) && trigger && p.fire<=0) {
                p.fire=Math.max(.12f,.38f/(1+.15f*p.rapid))*(event==1?.6f:1f);
                Vector3f at=ctx.getSnakeHead(i), dir=ctx.getSnakeDirection(i);
                shot(at.x+dir.x*.65f,at.z+dir.z*.65f,dir.x*27f,dir.z*27f,i,1+p.power);
            }
        }
        if(intermission>0) {
            intermission-=dt;
            if(intermission<=0) {
                wave++; toSpawn=ArenaRules.waveBudget(wave,pilots.length); spawnTimer=.5f;
                if(ArenaRules.bossWave(wave)) { spawnEnemy(ArenaRules.bossKind(wave)); toSpawn=3+pilots.length; }
                notice("ВОЛНА "+wave+(ArenaRules.bossWave(wave)?" — БОСС":""),SnakeApp.ACCENT3);
            }
        } else {
            spawnTimer-=dt;
            if(toSpawn>0 && spawnTimer<=0 && enemies.size()<18) {
                int unlocked=Math.min(4,1+wave);
                spawnEnemy(rng.nextInt(unlocked)); toSpawn--; spawnTimer=.75f;
            }
            if(toSpawn==0 && enemies.isEmpty()) {
                intermission=10f;
                for(Pilot p:pilots) p.hp=Math.min(p.maxHp,p.hp+1);
                for(int i=0;i<3;i++) { Vector3f p=safeSpawn(5f); pickup(p.x,p.z,i%2); }
                notice("Волна пройдена! Подбери припасы и выбери улучшения.",SnakeApp.ACCENT);
            }
        }
        nextEvent-=dt;
        if(eventTimer>0) {
            eventTimer-=dt;
            if(event==2 && ((int)(time*2)!=(int)((time-dt)*2))) {
                int target=nearest(0,0); if(target>=0){Vector3f at=ctx.getSnakeHead(target); hazard(at.x,at.z,3.5f,1.8f);}
            }
            if(eventTimer<=0) {event=-1; nextEvent=45f+rng.nextFloat()*20f;}
        } else if(nextEvent<=0 && !bossAlive() && intermission<=0) {
            event=(lastEvent+1+rng.nextInt(2))%3; lastEvent=event; eventTimer=16f;
            notice(eventName(),SnakeApp.ACCENT2);
        }
        for(Enemy e:new ArrayList<>(enemies)) updateEnemy(e,dt);
        for(Shot s:new ArrayList<>(shots)) updateShot(s,dt);
        for(Hazard h:new ArrayList<>(hazards)) {
            h.wait-=dt;
            if(h.wait<=0) {
                for(int i=0;i<pilots.length;i++) if(alive(i)) {Vector3f a=ctx.getSnakeHead(i); if(dist(a.x,a.z,h.x,h.z)<h.radius*h.radius)hurt(i,1);}
                burst(h.x,h.z,SnakeApp.DANGER,h.radius); h.node.removeFromParent(); hazards.remove(h);
            }
        }
        for(Pickup p:new ArrayList<>(pickups)) {
            p.life-=dt;
            for(int i=0;i<pilots.length;i++) if(alive(i)) {
                Vector3f a=ctx.getSnakeHead(i);
                if(dist(a.x,a.z,p.x,p.z)<2.8f) {
                    if(p.kind==0) pilots[i].hp=Math.min(pilots[i].maxHp,pilots[i].hp+2);
                    else reward(i,3,0);
                    p.life=0; break;
                }
            }
            if(p.life<=0) {p.node.removeFromParent();pickups.remove(p);}
        }
    }
    private void spawnEnemy(int kind) {
        if(enemies.size()>=22) return;
        Vector3f p=safeSpawn(kind>=4?12f:8f);
        Enemy e=new Enemy(); e.id=nextId++; e.kind=kind; e.x=p.x;e.z=p.z;
        e.hp=e.maxHp=kind>=4?Math.round((90+wave*22)*(1f+.65f*Math.max(0,pilots.length-1))):
                new int[]{2,4,3,2}[kind]+wave/3;
        e.node=enemyModel(kind); enemies.add(e); world.attachChild(e.node); place(e,.65f);
    }
    private Vector3f safeSpawn(float clearance) {
        float half=ctx.mapHalf()-4f;
        Vector3f best=new Vector3f(half,0,half); float bestDistance=-1;
        for(int attempt=0;attempt<60;attempt++) {
            float x=(rng.nextFloat()*2-1)*half,z=(rng.nextFloat()*2-1)*half;
            float d=Float.MAX_VALUE;
            for(int i=0;i<pilots.length;i++) if(alive(i)) {Vector3f p=ctx.getSnakeHead(i);d=Math.min(d,dist(x,z,p.x,p.z));}
            if(!ctx.game.combatSafePosition(x,z,2f))continue;
            if(d>bestDistance){bestDistance=d;best.set(x,0,z);}
            if(d>=clearance*clearance) return new Vector3f(x,0,z);
        }
        return best;
    }
    private int nearest(float x,float z) {
        int target=-1;float d=Float.MAX_VALUE;
        for(int i=0;i<pilots.length;i++) if(alive(i)) {
            Vector3f p=ctx.getSnakeHead(i);float n=dist(x,z,p.x,p.z);if(n<d){d=n;target=i;}
        }
        return target;
    }
    private void updateEnemy(Enemy e,float dt) {
        if(!enemies.contains(e))return;
        e.warm=Math.max(0,e.warm-dt); if(e.warm>0)return;
        int target=nearest(e.x,e.z); if(target<0)return;
        Vector3f p=ctx.getSnakeHead(target);
        float dx=p.x-e.x,dz=p.z-e.z,d=(float)Math.sqrt(dx*dx+dz*dz);
        if(d>.001f){dx/=d;dz/=d;}else{dx=0;dz=1;}
        e.angle=(float)Math.atan2(dx,dz); e.cooldown-=dt;
        e.phase=e.kind>=4?(e.hp*3<=e.maxHp?2:e.hp*3<=e.maxHp*2?1:0):0;
        float speed=new float[]{3.8f,2.7f,2.2f,4.4f,1.5f,1.7f,1.1f}[e.kind]+Math.min(1.5f,wave*.08f);
        if(e.kind==2 || e.kind==4 || e.kind==6) {
            float sign=d>13?1:d<8?-1:0; e.x+=dx*speed*sign*dt;e.z+=dz*speed*sign*dt;
        } else if(e.kind==3 && d<12) {e.x+=(-dz+dx*.25f)*speed*dt;e.z+=(dx+dz*.25f)*speed*dt;}
        else if(e.charge<=0) {e.x+=dx*speed*dt;e.z+=dz*speed*dt;}
        if(e.charge>0) {e.charge-=dt; e.x+=e.vx*dt;e.z+=e.vz*dt;}
        if(e.cooldown<=0) {
            if(e.kind==1 || e.kind==5) { e.vx=dx*(e.kind==5?13:10);e.vz=dz*(e.kind==5?13:10); e.charge=.7f; }
            if(e.kind==2 || e.kind==3) shot(e.x,e.z,dx*9,dz*9,-1,1);
            if(e.kind==4) {
                int n=10+e.phase*4;float offset=time*.4f;
                for(int k=0;k<n;k++){float a=k*FastMath.TWO_PI/n+offset;shot(e.x,e.z,FastMath.sin(a)*8,FastMath.cos(a)*8,-1,1);}
                hazard(p.x,p.z,4f,1.6f);
            }
            if(e.kind==5) {hazard(p.x,p.z,5+e.phase,1.8f);hazard(e.x,e.z,3f,1.2f);}
            if(e.kind==6) {
                if(enemies.size()<16){spawnEnemy(0);spawnEnemy(e.phase>0?3:0);}
                for(int k=-1;k<=1;k++){float a=e.angle+k*.25f;shot(e.x,e.z,FastMath.sin(a)*10,FastMath.cos(a)*10,-1,1);}
            }
            e.cooldown=(e.kind>=4?3f-e.phase*.45f:2.1f);
            if(e.kind==1 || e.kind==5) {e.warm=.65f;burst(e.x,e.z,COLORS[e.kind],e.kind==5?3f:1.4f);}
        }
        float bound=ctx.mapHalf()-3f;
        e.x=ArenaRules.clamp(e.x,-bound,bound);e.z=ArenaRules.clamp(e.z,-bound,bound);
        for(int i=0;i<pilots.length;i++) if(alive(i)) {
            Vector3f a=ctx.getSnakeHead(i);float radius=e.kind>=4?2.3f:1f;
            if(dist(a.x,a.z,e.x,e.z)<radius*radius) {
                if(pilots[i].dashTime>0 && time>=e.dashHits.getOrDefault(i,0f)) {
                    e.dashHits.put(i,time+.6f);hit(e,5+pilots[i].power,i);
                } else hurt(i,1);
            }
        }
    }
    private void shot(float x,float z,float vx,float vz,int owner,int damage) {
        if(shots.size()>=160)return;
        Shot s=new Shot();s.id=nextId++;s.x=x;s.z=z;s.vx=vx;s.vz=vz;s.owner=owner;s.damage=damage;
        s.node=orb(owner<0?SnakeApp.DANGER:SnakeApp.ACCENT2,s.radius);
        world.attachChild(s.node);shots.add(s);place(s,.6f);
    }
    private void updateShot(Shot s,float dt) {
        if(!shots.contains(s))return;
        float ox=s.x,oz=s.z; s.x+=s.vx*dt;s.z+=s.vz*dt;s.life-=dt;
        boolean remove=s.life<=0 || Math.abs(s.x)>ctx.mapHalf()-1 || Math.abs(s.z)>ctx.mapHalf()-1;
        if(!remove && s.owner>=0) {
            for(Enemy e:new ArrayList<>(enemies)) {
                float radius=e.kind>=4?2.1f:.85f;
                if(e.warm<=0 && ArenaRules.segmentDistanceSquared(e.x,e.z,ox,oz,s.x,s.z)<radius*radius) {hit(e,s.damage,s.owner);remove=true;break;}
            }
            if(!remove)remove=ctx.game.combatHitCube(ox,oz,s.x,s.z,s.damage,s.owner);
        } else if(!remove) {
            for(int i=0;i<pilots.length;i++) if(alive(i)) {
                Vector3f p=ctx.getSnakeHead(i);
                if(ArenaRules.segmentDistanceSquared(p.x,p.z,ox,oz,s.x,s.z)<.5f) {hurt(i,s.damage);remove=true;break;}
            }
        }
        if(remove)removeShot(s);
    }
    private void removeShot(Shot s){s.node.removeFromParent();shots.remove(s);}
    private void hit(Enemy e,int damage,int owner) {
        if(!enemies.contains(e) || e.warm>0)return;
        e.hp-=damage;
        burst(e.x,e.z,COLORS[e.kind],.75f);
        if(e.hp>0)return;
        e.node.removeFromParent();enemies.remove(e);
        if(alive(owner)) {
            reward(owner,e.kind>=4?12:2,e.kind>=4?400:25);
            if(e.kind>=4) {pilots[owner].bosses++;pilots[owner].choices++;pickup(e.x,e.z,0);}
        }
        if(e.kind>=4) {
            notice(NAMES[e.kind]+" повержен! Улучшение и ремонт.",SnakeApp.ACCENT3);
            for(Shot s:new ArrayList<>(shots)) if(s.owner<0)removeShot(s);
        } else if(rng.nextFloat()<.15f)pickup(e.x,e.z,rng.nextBoolean()?0:1);
    }
    private void hazard(float x,float z,float radius,float wait) {
        if(hazards.size()>=24)return;
        Hazard h=new Hazard();h.id=nextId++;h.x=x;h.z=z;h.radius=radius;h.wait=wait;
        h.node=ring(x,z,radius,SnakeApp.DANGER);world.attachChild(h.node);hazards.add(h);
    }
    private void pickup(float x,float z,int kind) {
        if(pickups.size()>=16)return;
        Pickup p=new Pickup();p.id=nextId++;p.x=x;p.z=z;p.kind=kind;p.node=pickupModel(kind);
        world.attachChild(p.node);pickups.add(p);place(p,.7f);
    }
    private void place(Entity e,float height) {e.node.setLocalTranslation(e.x,floor(e.x,e.z)+height,e.z);}
    private static float dist(float x,float z,float bx,float bz){float dx=x-bx,dz=z-bz;return dx*dx+dz*dz;}
    private Node orb(ColorRGBA c,float radius) {Node n=new Node();Geometry g=new Geometry("Core",new Sphere(10,12,radius));g.setMaterial(ctx.unshaded(c));n.attachChild(g);return n;}
    private void box(Node n,String name,float x,float y,float z,float sx,float sy,float sz,ColorRGBA c) {
        Geometry g=new Geometry(name,new Box(sx,sy,sz));g.setMaterial(ctx.lit(c));g.setLocalTranslation(x,y,z);
        g.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);n.attachChild(g);
    }
    private Node enemyModel(int kind) {
        Node n=new Node("Enemy_"+NAMES[kind]);ColorRGBA c=COLORS[kind];
        float r=kind>=4?1.25f:.5f;
        Geometry core=new Geometry("Armored core",kind==1 || kind==5?new Box(r,r*.6f,r):new Sphere(12,16,r));
        core.setMaterial(ctx.lit(c));core.setShadowMode(RenderQueue.ShadowMode.CastAndReceive);n.attachChild(core);
        Node eye=orb(new ColorRGBA(1,.95f,.65f,1),r*.23f);eye.setLocalTranslation(0,.1f,r*.9f);n.attachChild(eye);
        for(int side=-1;side<=1;side+=2) {
            box(n,"Armor",side*r*.9f,0,0,r*.25f,r*.32f,r*.75f,c.mult(.6f));
            if(kind==0 || kind==6)for(int j=-1;j<=1;j++)box(n,"Leg",side*r*1.3f,-r*.3f,j*r*.6f,r*.65f,r*.07f,r*.08f,c);
            if(kind==2 || kind==4)box(n,"Cannon",side*r*.65f,.2f,r*.8f,r*.15f,r*.15f,r*.8f,new ColorRGBA(.14f,.16f,.22f,1));
            if(kind==3)box(n,"Wing",side*r*1.5f,.05f,0,r,r*.07f,r*.35f,c);
            if(kind==1 || kind==5)box(n,"Ram",side*r*.7f,-.1f,r*1.15f,r*.15f,r*.25f,r*.7f,SnakeApp.TEXT);
        }
        if(kind>=4)for(int k=0;k<5;k++)box(n,"Crown",((float)k-2)*.43f,r,0,.14f,.35f+((k%2)*.2f),.14f,SnakeApp.ACCENT3);
        return n;
    }
    private Node pickupModel(int kind) {
        Node n=orb(kind==0?SnakeApp.ACCENT:SnakeApp.ACCENT3,.38f);
        if(kind==0){box(n,"RepairX",0,.4f,0,.32f,.07f,.07f,SnakeApp.TEXT);box(n,"RepairY",0,.4f,0,.07f,.32f,.07f,SnakeApp.TEXT);}
        return n;
    }
    private Node ring(float x,float z,float radius,ColorRGBA color) {
        Node node=new Node("Warning");
        Vector3f[] points=new Vector3f[65];int[] idx=new int[128];
        for(int i=0;i<65;i++) {
            float a=i*FastMath.TWO_PI/64;
            float px=x+FastMath.cos(a)*radius,pz=z+FastMath.sin(a)*radius;
            points[i]=new Vector3f(px,floor(px,pz)+.12f,pz);
            if(i<64){idx[i*2]=i;idx[i*2+1]=i+1;}
        }
        Mesh mesh=new Mesh();mesh.setMode(Mesh.Mode.Lines);
        mesh.setBuffer(VertexBuffer.Type.Position,3,BufferUtils.createFloatBuffer(points));
        mesh.setBuffer(VertexBuffer.Type.Index,2,BufferUtils.createIntBuffer(idx));mesh.updateBound();
        Geometry g=new Geometry("Danger ring",mesh);g.setMaterial(ctx.unshaded(color));g.setShadowMode(RenderQueue.ShadowMode.Off);node.attachChild(g);
        return node;
    }
    private void burst(float x,float z,ColorRGBA c,float radius) {
        if(effects.size()>=48)return;
        Effect e=new Effect();e.node=ring(x,z,radius,c);e.total=e.life=.35f;world.attachChild(e.node);effects.add(e);
    }
    private void decorate() {
        if(!SnakeApp.terrainDetailsEnabled)return;
        boolean desert=ctx.mapId.equals("DesertArena"), pits=ctx.mapId.equals("SpikePits");
        ColorRGBA c=desert?SnakeApp.ACCENT3:pits?SnakeApp.DANGER:SnakeApp.ACCENT2;
        // Landmarks stay outside the collision boundary; the whole playfield stays readable.
        float edge=ctx.mapHalf()+2.5f;
        for(int i=0;i<12;i++) {
            float a=i*FastMath.TWO_PI/12,x=FastMath.cos(a)*edge,z=FastMath.sin(a)*edge;
            if(Math.abs(x)<ctx.mapHalf()+1 && Math.abs(z)<ctx.mapHalf()+1)continue;
            Node n=new Node("Arena landmark");world.attachChild(n);n.setLocalTranslation(x,0,z);
            box(n,"Plinth",0,.5f,0,1.1f,.5f,1.1f,new ColorRGBA(.1f,.15f,.2f,1));
            box(n,"Beacon",0,2,0,.3f,1.1f,.3f,c);
            Node light=orb(c,.45f);light.setLocalTranslation(0,3.3f,0);n.attachChild(light);
        }
        // Three visible supply pads, with no invisible collision geometry.
        for(int i=0;i<3;i++){
            float a=i*FastMath.TWO_PI/3,x=FastMath.cos(a)*ctx.mapHalf()*.55f,z=FastMath.sin(a)*ctx.mapHalf()*.55f;
            world.attachChild(ring(x,z,2.3f,c));
        }
    }
    private String eventName(){return event==0?"ДВОЙНЫЕ НАГРАДЫ":event==1?"ПЕРЕГРУЗКА ОРУЖИЯ":event==2?"МЕТЕОРНЫЙ ФРОНТ":"";}
    public void frame(float dt,boolean gameOver) {
        if(!host())time+=dt;
        for(Enemy e:enemies) {
            Vector3f desired=new Vector3f(e.x,floor(e.x,e.z)+(e.kind>=4?1.5f:.7f),e.z);
            if(host())e.node.setLocalTranslation(desired);
            else e.node.setLocalTranslation(e.node.getLocalTranslation().interpolateLocal(desired,ArenaRules.damping(16,dt)));
            e.node.setLocalRotation(new Quaternion().fromAngleAxis(e.angle,Vector3f.UNIT_Y));
            e.node.setLocalScale(e.warm>0?.65f+.15f*FastMath.sin(time*18):1f);
        }
        for(Shot s:shots)place(s,.65f);
        for(Pickup p:pickups){place(p,.85f+FastMath.sin(time*3+p.id)*.18f);p.node.rotate(0,dt*1.4f,0);}
        for(Effect e:new ArrayList<>(effects)){e.life-=dt;if(e.life<=0){e.node.removeFromParent();effects.remove(e);}}
        if(!ctx.game.acceptsGameplayInput())fireHeld=false;
        hud.setCullHint(ctx.game.isMenuOverlayVisible()?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
        layout();
        if(gameOver && !saved) {saved=true;Pilot p=pilots[ctx.myIndex];RunProgress.record(ctx.mapId,wave,snake(ctx.myIndex).getScore(),p.bosses);}
        if(host() && !ctx.solo) {netTimer-=dt;if(netTimer<=0){netTimer=.1f;sync();}}
    }
    private void layout() {
        float s=Math.max(.9f,SnakeApp.uiScale(ctx.camera)),w=ctx.camera.getWidth(),h=ctx.camera.getHeight();
        statusBackground.setLocalTranslation(12*s,h-116*s,3);
        statusBackground.setLocalScale(370*s,104*s,1);
        Pilot p=pilots[ctx.myIndex];
        status.setSize(18*s);status.setLocalTranslation(22*s,h-28*s,4);
        status.setText("БРОНЯ "+p.hp+"/"+p.maxHp+"   СЧЁТ "+snake(ctx.myIndex).getScore()+"\nУРОВЕНЬ "+p.level+"   ОПЫТ "+p.xp+"/"+ArenaRules.levelCost(p.level)+"   КОМБО x"+Math.max(1,p.combo));
        objective.setSize(17*s);objective.setLocalTranslation(22*s,h-83*s,4);
        objective.setText(intermission>0?"ВОЛНА "+(wave+1)+" ЧЕРЕЗ "+(int)Math.ceil(intermission)+" с":"ВОЛНА "+wave+"   ВРАГОВ "+(enemies.size()+toSpawn));
        abilities.setSize(15*s);abilities.setLocalTranslation(22*s,110*s,4);
        abilities.setText("W / ↑ — движение   A D / ← → — поворот   S — тормоз\nSPACE — огонь   E — импульс "+(p.pulse<=0?"ГОТОВ":(int)Math.ceil(p.pulse)+" с")+"\n"+eventName()+(event>=0?"  "+(int)Math.ceil(eventTimer)+" с":""));
        upgrades.setSize(16*s);upgrades.setLocalTranslation(22*s,165*s,4);
        upgrades.setText(p.choices>0?"УЛУЧШЕНИЯ: "+p.choices+"\n[1] Урон "+p.power+"/8   [2] Темп "+p.rapid+"/6   [3] Броня + ремонт":"");
        Enemy boss=null;for(Enemy e:enemies)if(e.kind>=4){boss=e;break;}
        bossText.setSize(19*s);bossText.setLocalTranslation(w*.5f-160*s,h-133*s,4);
        bossText.setText(boss==null?"":NAMES[boss.kind]+"   ФАЗА "+(boss.phase+1)+"   "+boss.hp+"/"+boss.maxHp);
        bossBar.setCullHint(boss==null?Spatial.CullHint.Always:Spatial.CullHint.Inherit);
        bossBar.setLocalTranslation(w*.5f-160*s,h-165*s,4);
        bossBar.setLocalScale(320*s*(boss==null?0:Math.max(0,(float)boss.hp/boss.maxHp)),7*s,1);
    }
    private void send(String payload){ctx.game.combatSend("CB2|"+tick+"|"+payload);}
    private String ids(List<? extends Entity> list) {StringJoiner j=new StringJoiner(",");for(Entity e:list)j.add(""+e.id);return j.toString();}
    private void sync() {
        tick++;
        send("W|"+wave+"|"+toSpawn+"|"+intermission+"|"+event+"|"+eventTimer+"|"+time);
        for(int i=0;i<pilots.length;i++){
            Pilot p=pilots[i];
            send("P|"+i+"|"+p.hp+"|"+p.maxHp+"|"+p.xp+"|"+p.level+"|"+p.choices+"|"+p.power+"|"+p.rapid+"|"+p.pulse+"|"+p.dash+"|"+p.dashTime+"|"+p.kills+"|"+p.combo+"|"+p.bosses);
        }
        for(Enemy e:enemies)send("E|"+e.id+"|"+e.kind+"|"+e.x+"|"+e.z+"|"+e.hp+"|"+e.maxHp+"|"+e.warm+"|"+e.phase+"|"+e.angle);
        for(Shot q:shots)send("S|"+q.id+"|"+q.x+"|"+q.z+"|"+q.owner);
        for(Hazard q:hazards)send("H|"+q.id+"|"+q.x+"|"+q.z+"|"+q.radius+"|"+q.wait);
        for(Pickup q:pickups)send("K|"+q.id+"|"+q.x+"|"+q.z+"|"+q.kind);
        send("M|"+ids(enemies)+"|"+ids(shots)+"|"+ids(hazards)+"|"+ids(pickups));
    }
    private static float f(String p) {float v=Float.parseFloat(p);if(!Float.isFinite(v))throw new IllegalArgumentException("Non-finite state");return v;}
    private static int i(String p){return Integer.parseInt(p);}
    private <T extends Entity> T find(List<T> list,int id){for(T e:list)if(e.id==id)return e;return null;}
    private boolean obsolete(long seq,int id,Set<Integer> manifest){return seq<=manifestTick && !manifest.contains(id);}
    private <T extends Entity> void reconcile(List<T> list,Set<Integer> set,String value,long seq) {
        set.clear();if(!value.isEmpty())for(String id:value.split(","))set.add(i(id));
        Iterator<T> it=list.iterator();while(it.hasNext()){T e=it.next();if(e.last<=seq && !set.contains(e.id)){e.node.removeFromParent();it.remove();}}
    }
    public void receive(String message) {
        if(host())return;
        String[] p=message.split("\\|",-1);long seq=Long.parseLong(p[1]);String type=p[2];
        if("W".equals(type) && seq>worldTick) {
            worldTick=seq;wave=i(p[3]);toSpawn=i(p[4]);intermission=f(p[5]);event=i(p[6]);eventTimer=f(p[7]);time=f(p[8]);return;
        }
        if("M".equals(type) && seq>manifestTick) {
            reconcile(enemies,enemyManifest,p[3],seq);reconcile(shots,shotManifest,p[4],seq);
            reconcile(hazards,hazardManifest,p[5],seq);reconcile(pickups,pickupManifest,p[6],seq);manifestTick=seq;return;
        }
        int id=i(p[3]);
        if("P".equals(type)) {
            if(id<0 || id>=pilots.length || seq<=pilots[id].lastSnapshot)return;
            Pilot a=pilots[id];a.lastSnapshot=seq;a.hp=i(p[4]);a.maxHp=i(p[5]);a.xp=i(p[6]);a.level=i(p[7]);a.choices=i(p[8]);a.power=i(p[9]);a.rapid=i(p[10]);a.pulse=f(p[11]);a.dash=f(p[12]);a.dashTime=f(p[13]);a.kills=i(p[14]);a.combo=i(p[15]);a.bosses=i(p[16]);return;
        }
        if("E".equals(type) && !obsolete(seq,id,enemyManifest)) {
            Enemy e=find(enemies,id);if(e==null){if(enemies.size()>=24)return;e=new Enemy();e.id=id;e.kind=i(p[4]);e.node=enemyModel(e.kind);world.attachChild(e.node);enemies.add(e);}
            if(seq<=e.last)return;e.last=seq;e.x=f(p[5]);e.z=f(p[6]);e.hp=i(p[7]);e.maxHp=i(p[8]);e.warm=f(p[9]);e.phase=i(p[10]);e.angle=f(p[11]);return;
        }
        if("S".equals(type) && !obsolete(seq,id,shotManifest)) {
            Shot e=find(shots,id);if(e==null){if(shots.size()>=160)return;e=new Shot();e.id=id;e.owner=i(p[6]);e.node=orb(e.owner<0?SnakeApp.DANGER:SnakeApp.ACCENT2,.22f);world.attachChild(e.node);shots.add(e);}
            if(seq<=e.last)return;e.last=seq;e.x=f(p[4]);e.z=f(p[5]);return;
        }
        if("H".equals(type) && !obsolete(seq,id,hazardManifest)) {
            Hazard e=find(hazards,id);if(e==null){if(hazards.size()>=24)return;e=new Hazard();e.id=id;e.x=f(p[4]);e.z=f(p[5]);e.radius=f(p[6]);e.node=ring(e.x,e.z,e.radius,SnakeApp.DANGER);world.attachChild(e.node);hazards.add(e);}
            if(seq>e.last){e.last=seq;e.wait=f(p[7]);}return;
        }
        if("K".equals(type) && !obsolete(seq,id,pickupManifest)) {
            Pickup e=find(pickups,id);if(e==null){if(pickups.size()>=16)return;e=new Pickup();e.id=id;e.x=f(p[4]);e.z=f(p[5]);e.kind=i(p[6]);e.node=pickupModel(e.kind);world.attachChild(e.node);pickups.add(e);}
            e.last=Math.max(e.last,seq);
        }
    }
    public void cleanup() {
        ctx.inputManager.removeListener(input);
        for(String name:new String[]{"CombatFire","CombatPulse","CombatUpgrade1","CombatUpgrade2","CombatUpgrade3"})if(ctx.inputManager.hasMapping(name))ctx.inputManager.deleteMapping(name);
        world.removeFromParent();hud.removeFromParent();enemies.clear();shots.clear();hazards.clear();pickups.clear();effects.clear();
    }
}
