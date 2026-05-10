import com.jme3.math.ColorRGBA;

public class DesertArena implements SnakeApp.ExternalMapDef {
    @Override public String id() { return "DesertArena"; }
    @Override public String displayName() { return "Desert Arena"; }
    @Override public String previewImage() { return "maps/DesertArena.png"; }
    @Override public ColorRGBA accentColor() { return new ColorRGBA(1f, 0.78f, 0.29f, 1f); }
    @Override public SnakeApp.MapRuntimeSettings settings() {
        SnakeApp.MapRuntimeSettings s = new SnakeApp.MapRuntimeSettings();
        s.mapHalf = 42f;
        s.snakeSpeed = 7.4f;
        s.turnSpeed = 2.55f;
        s.maxFood = 16;
        s.allowGrowth = true;
        s.enableRegularFood = true;
        s.enableBadFood = true;
        s.enableBallRain = true;
        s.enableRain = false;
        s.enableFrozenArena = true;
        s.enableSandstorm = true;
        s.enableBlackCubesDefault = true;
        s.forceSnakeColor = false;
        s.cameraDistance = 13.5f;
        s.cameraHeight = 6.6f;
        s.cameraLookAhead = 4.2f;
        s.mode = "desert-survival";
        return s;
    }
}
