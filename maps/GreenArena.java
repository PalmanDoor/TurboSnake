import com.jme3.math.ColorRGBA;

public class GreenArena implements SnakeApp.ExternalMapDef {
    @Override public String id() { return "GreenArena"; }
    @Override public String displayName() { return "Green Arena"; }
    @Override public String previewImage() { return "maps/GreenArena.png"; }
    @Override public ColorRGBA accentColor() { return new ColorRGBA(0.12f, 0.94f, 0.48f, 1f); }
    @Override public SnakeApp.MapRuntimeSettings settings() {
        SnakeApp.MapRuntimeSettings s = new SnakeApp.MapRuntimeSettings();
        s.mapHalf = 60f;
        s.snakeSpeed = 8.2f;
        s.turnSpeed = 2.85f;
        s.maxFood = 20;
        s.allowGrowth = true;
        s.enableRegularFood = true;
        s.enableBadFood = true;
        s.enableBallRain = true;
        s.enableRain = true;
        s.enableFrozenArena = true;
        s.enableSandstorm = false;
        s.enableBlackCubesDefault = true;
        s.forceSnakeColor = false;
        s.cameraDistance = 12f;
        s.cameraHeight = 6f;
        s.cameraLookAhead = 4f;
        s.mode = "classic";
        return s;
    }
}
