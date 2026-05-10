import com.jme3.math.ColorRGBA;

public class SpikePits implements SnakeApp.ExternalMapDef {
    @Override public String id() { return "SpikePits"; }
    @Override public String displayName() { return "Spike Pits"; }
    @Override public String previewImage() { return "maps/SpikePits.png"; }
    @Override public ColorRGBA accentColor() { return new ColorRGBA(1f, 0.27f, 0.27f, 1f); }
    @Override public SnakeApp.MapRuntimeSettings settings() {
        SnakeApp.MapRuntimeSettings s = new SnakeApp.MapRuntimeSettings();
        s.mapHalf = 40f;
        s.snakeSpeed = 8.0f;
        s.turnSpeed = 2.9f;
        s.maxFood = 18;
        s.allowGrowth = true;
        s.enableRegularFood = true;
        s.enableBadFood = true;
        s.enableBallRain = true;
        s.enableRain = true;
        s.enableFrozenArena = false;
        s.enableSandstorm = false;
        s.enableBlackCubesDefault = true;
        s.forceSnakeColor = false;
        s.cameraDistance = 12.5f;
        s.cameraHeight = 6.8f;
        s.cameraLookAhead = 4.0f;
        s.mode = "hazard";
        return s;
    }
}
