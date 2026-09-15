/** Small dependency-free rules shared by simulation and validation. */
public final class ArenaRules {
    private ArenaRules() {}
    public static final float STEP = 1f/120f;
    public static final int MAX_LENGTH = 128;
    public static float clamp(float v,float min,float max) { return Math.max(min,Math.min(max,v)); }
    public static float finite(float v,float fallback) { return Float.isFinite(v)?v:fallback; }
    public static float angleDelta(float from,float to) {
        return (float)Math.atan2(Math.sin(to-from),Math.cos(to-from));
    }
    public static float damping(float rate,float dt) { return 1f-(float)Math.exp(-rate*dt); }
    public static float segmentDistanceSquared(float px,float pz,float ax,float az,float bx,float bz) {
        float dx=bx-ax,dz=bz-az,den=dx*dx+dz*dz;
        float t=den<0.000001f?0f:clamp(((px-ax)*dx+(pz-az)*dz)/den,0f,1f);
        dx=px-(ax+t*dx); dz=pz-(az+t*dz); return dx*dx+dz*dz;
    }
    public static int waveBudget(int wave,int players) { return Math.min(30,4+wave*2+Math.max(0,players-1)*2); }
    public static boolean bossWave(int wave) { return wave>0 && wave%3==0; }
    public static int bossKind(int wave) { return 4+Math.max(0,wave/3-1)%3; }
    public static int levelCost(int level) { return 4+Math.max(0,level-1)*3; }
    public static boolean canMove(int playersAlive,int totalPlayers,boolean solo) {
        return solo || totalPlayers<=1 ? playersAlive>0 : playersAlive>1;
    }
}
