package ru.sonia.turbosnake;

import com.jme3.asset.AssetManager;
import com.jme3.font.BitmapFont;
import com.jme3.font.BitmapText;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;

/**
 * MenuButton — кастомная UI-кнопка в стиле «скошенного прямоугольника».
 *
 * Состоит из трёх Geometry:
 *   borderGeo  — тонкая рамка с акцентным цветом
 *   bgGeo      — основной фон (меняется при hover/press)
 *   accentGeo  — вертикальная полоска слева (акцент)
 * + BitmapText label — текст по центру.
 *
 * Использование:
 *   new MenuButton("Старт", cx, cy, w, h, BTN_NORMAL, BTN_HOVER, BTN_PRESS,
 *                   ACCENT, assetManager, guiNode, zOrder);
 *   button.updateHover(mx, my);   // вызывать в AnalogListener мыши
 *   button.onPress(mx, my);
 *   boolean clicked = button.onRelease(mx, my);
 */
public class MenuButton {
    final Geometry bgGeo;
    final Geometry accentGeo;
    final Geometry borderGeo;
    final BitmapText label;
    private final float x, y, w, h;
    private boolean hovered = false, pressed = false;
    private ColorRGBA accentColor;
    private ColorRGBA bgNormal, bgHover, bgPressed;

    MenuButton(String text, float cx, float cy, float w, float h,
               ColorRGBA bgNormal, ColorRGBA bgHover, ColorRGBA bgPressed,
               ColorRGBA textColor, AssetManager am, Node guiNode, float z) {
        this.w = w; this.h = h;
        this.x = cx - w/2f; this.y = cy - h/2f;
        this.accentColor = textColor;
        this.bgNormal = bgNormal;
        this.bgHover = bgHover;
        this.bgPressed = bgPressed;

        float tiltAmount = 18f;  // уменьшен скос для более чистого вида

        // Граница (чуть шире фона) — создаёт эффект рамки с акцентным цветом
        Mesh borderMesh = TurboSnake.createSlantedQuad(w + 4f, h + 4f, tiltAmount);
        borderGeo = new Geometry("BtnBorder_" + text, borderMesh);
        Material borderMat = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        ColorRGBA borderCol = new ColorRGBA(textColor.r*0.5f, textColor.g*0.5f, textColor.b*0.5f, 0.6f);
        borderMat.setColor("Color", borderCol);
        borderMat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        borderMat.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
        borderGeo.setMaterial(borderMat);
        borderGeo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
        borderGeo.setLocalTranslation(cx, cy, z - 0.1f);
        guiNode.attachChild(borderGeo);

        // Основной фон
        Mesh slantedMesh = TurboSnake.createSlantedQuad(w, h, tiltAmount);
        bgGeo = new Geometry("BtnBg_" + text, slantedMesh);
        Material mat = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        mat.setColor("Color", bgNormal);
        mat.getAdditionalRenderState().setBlendMode(com.jme3.material.RenderState.BlendMode.Alpha);
        mat.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
        bgGeo.setMaterial(mat);
        bgGeo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
        bgGeo.setLocalTranslation(cx, cy, z);
        guiNode.attachChild(bgGeo);

        // Акцентная вертикальная полоса слева (толще и ярче)
        Mesh accentMesh = TurboSnake.createSlantedQuad(8f, h - 4f, tiltAmount);
        accentGeo = new Geometry("BtnAccent_" + text, accentMesh);
        Material am2 = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        am2.setColor("Color", new ColorRGBA(textColor.r, textColor.g, textColor.b, 0.9f));
        am2.getAdditionalRenderState().setFaceCullMode(com.jme3.material.RenderState.FaceCullMode.Off);
        accentGeo.setMaterial(am2);
        accentGeo.setQueueBucket(com.jme3.renderer.queue.RenderQueue.Bucket.Gui);
        accentGeo.setLocalTranslation(cx - w/2f + 12f, cy, z + 0.3f);
        guiNode.attachChild(accentGeo);

        BitmapFont font = TurboSnake.loadFont(am);
        label = new BitmapText(font);
        label.setSize(26);
        label.setText(text);
        label.setColor(textColor);
        guiNode.attachChild(label);
        centerLabel(cx, cy, z);
    }

    private void centerLabel(float cx, float cy, float z) {

        float textX = cx - label.getLineWidth() / 2f;

        float textY = cy + label.getLineHeight() / 3f;

        label.setLocalTranslation(textX, textY, z + 1f);
    }

    void setBgNormal(ColorRGBA c) {
        this.bgNormal = c;
        refreshColor();
    }

    void updateHover(float mx, float my) { hovered = isHit(mx, my); refreshColor(); }

    void onPress(float mx, float my)    { pressed = isHit(mx, my); refreshColor(); }

    boolean onRelease(float mx, float my) {
        boolean hit = pressed && isHit(mx, my);
        pressed = false;
        refreshColor();
        return hit;
    }

    public boolean isHit(float mx, float my) { return mx >= x && mx <= x + w && my >= y && my <= y + h; }

    private void refreshColor() {
        ColorRGBA c = pressed ? bgPressed : (hovered ? bgHover : bgNormal);
        bgGeo.getMaterial().setColor("Color", c);
        // Граница ярче при наведении
        float bf = hovered ? 1.0f : 0.5f;
        float ba = hovered ? 0.95f : 0.55f;
        borderGeo.getMaterial().setColor("Color",
                new ColorRGBA(accentColor.r*bf, accentColor.g*bf, accentColor.b*bf, ba));
        // Акцент
        float af = hovered ? 1f : 0.75f;
        accentGeo.getMaterial().setColor("Color",
                new ColorRGBA(accentColor.r * af, accentColor.g * af, accentColor.b * af, hovered ? 1f : 0.85f));
        // Текст светлее при наведении
        label.setColor(hovered ? new ColorRGBA(
                Math.min(1f, accentColor.r * 1.2f),
                Math.min(1f, accentColor.g * 1.2f),
                Math.min(1f, accentColor.b * 1.2f), 1f) : accentColor);
    }

    void detach(Node guiNode) {
        guiNode.detachChild(borderGeo);
        guiNode.detachChild(bgGeo);
        guiNode.detachChild(accentGeo);
        guiNode.detachChild(label);
    }

    void setText(String t) { label.setText(t); }
    void setAccentColor(ColorRGBA c) { accentColor = c; label.setColor(c); refreshColor(); }
}