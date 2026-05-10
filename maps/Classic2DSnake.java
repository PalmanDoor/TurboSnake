import com.jme3.math.*;
import com.jme3.scene.Node;

// 2D-режим как карта: змейка, еда и мультиплеер остаются из SnakeApp,
// но камера смотрит сверху, а арена строится как плоское поле.
public class Classic2DSnake implements SnakeApp.ExternalMapDef {
    private Node world;

    @Override public String id() { return "Classic2DSnake"; }
    @Override public String displayName() { return "Classic 2D Snake"; }
    @Override public String previewImage() { return "maps/Classic2DSnake.png"; }
    @Override public ColorRGBA accentColor() { return new ColorRGBA(0.25f, 1f, 0.35f, 1f); }

    @Override
    public SnakeApp.MapRuntimeSettings settings() {
        SnakeApp.MapRuntimeSettings s = new SnakeApp.MapRuntimeSettings();
        s.mapHalf = 32f;
        s.snakeSpeed = 9.0f;
        s.turnSpeed = 4.0f;
        s.maxFood = 10;
        s.allowGrowth = true;
        s.enableRegularFood = true;
        s.enableBadFood = false;
        s.enableBallRain = false;
        s.enableRain = false;
        s.enableFrozenArena = false;
        s.enableSandstorm = false;
        s.enableBlackCubesDefault = false;
        s.cameraDistance = 0f;
        s.cameraHeight = 70f;
        s.cameraLookAhead = 0f;
        s.mode = "classic-2d";
        return s;
    }

    @Override public boolean overridesArena() { return true; }
    @Override public boolean overridesCamera() { return true; }

    @Override
    public void buildWorld(SnakeApp.MapContext ctx) {
        world = new Node("Classic2DWorld");
        ctx.rootNode.attachChild(world);

        float h = ctx.mapHalf();
        ctx.addStaticBox(world, "2DFloor", new Vector3f(0f, -0.4f, 0f), new Vector3f(h, 0.15f, h), new ColorRGBA(0.04f, 0.12f, 0.06f, 1f));
        ctx.addStaticBox(world, "TopWall", new Vector3f(0f, 0.5f, h), new Vector3f(h, 0.7f, 0.35f), new ColorRGBA(0.15f, 0.65f, 0.22f, 1f));
        ctx.addStaticBox(world, "BottomWall", new Vector3f(0f, 0.5f, -h), new Vector3f(h, 0.7f, 0.35f), new ColorRGBA(0.15f, 0.65f, 0.22f, 1f));
        ctx.addStaticBox(world, "LeftWall", new Vector3f(-h, 0.5f, 0f), new Vector3f(0.35f, 0.7f, h), new ColorRGBA(0.15f, 0.65f, 0.22f, 1f));
        ctx.addStaticBox(world, "RightWall", new Vector3f(h, 0.5f, 0f), new Vector3f(0.35f, 0.7f, h), new ColorRGBA(0.15f, 0.65f, 0.22f, 1f));

        // Сетка поля без физики.
        for (int i = -32; i <= 32; i += 4) {
            ctx.addVisualBox(world, "GridX" + i, new Vector3f(i, -0.18f, 0f), new Vector3f(0.035f, 0.02f, h), new ColorRGBA(0.08f, 0.22f, 0.10f, 1f));
            ctx.addVisualBox(world, "GridZ" + i, new Vector3f(0f, -0.17f, i), new Vector3f(h, 0.02f, 0.035f), new ColorRGBA(0.08f, 0.22f, 0.10f, 1f));
        }
    }

    @Override
    public void updateCamera(SnakeApp.MapContext ctx, float tpf) {
        Vector3f head = ctx.getMySnakeHead();
        ctx.camera.setLocation(new Vector3f(head.x, 70f, head.z + 0.01f));
        ctx.camera.lookAt(new Vector3f(head.x, 0f, head.z), Vector3f.UNIT_Z);
    }

    @Override
    public void cleanup(SnakeApp.MapContext ctx) {
        if (world != null) {
            world.removeFromParent();
            world = null;
        }
    }
}
