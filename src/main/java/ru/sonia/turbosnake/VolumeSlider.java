package ru.sonia.turbosnake;

import com.jme3.asset.AssetManager;
import com.jme3.material.Material;
import com.jme3.math.*;
import com.jme3.scene.*;
import com.jme3.scene.shape.Box;

/**
 * VolumeSlider — горизонтальный ползунок для регулировки громкости.
 *
 * Состоит из трёх Geometry: дорожка (track), заливка (fill) и ползунок (thumb).
 * При клике по дорожке обновляет значение [0..1].
 *
 * Методы:
 *   onClick(mx, my)   — обрабатывает клик мышью, возвращает true если задет
 *   getValue()        — текущее значение [0..1]
 *   setValue(v)       — программная установка значения
 *   setVisible(bool)  — скрыть/показать
 */
public class VolumeSlider {
    private static final float TRACK_H  = 10f, THUMB_W = 18f, THUMB_H = 28f;
    private final Geometry track, fill, thumb;
    private final float left, cy, trackW, z;
    private float value;
    private boolean hovered = false;

    VolumeSlider(float cx, float cy, float w, float initVal, AssetManager am, Node parent, float z) {
        this.trackW = w; this.left = cx - w/2f; this.cy = cy; this.z = z;
        this.value = Math.max(0f, Math.min(1f, initVal));

        Box trackBox = new Box(w/2f, TRACK_H/2f, 0.3f);
        track = new Geometry("VSlTrack", trackBox);
        Material tm = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        tm.setColor("Color", new ColorRGBA(0.10f, 0.18f, 0.38f, 1f)); track.setMaterial(tm);
        track.setLocalTranslation(cx, cy, z); parent.attachChild(track);

        Box fillBox = new Box(0.5f, TRACK_H/2f-1f, 0.4f);
        fill = new Geometry("VSlFill", fillBox);
        Material fm = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        fm.setColor("Color", TurboSnake.ACCENT2); fill.setMaterial(fm); parent.attachChild(fill);

        Box thumbBox = new Box(THUMB_W/2f, THUMB_H/2f, 0.6f);
        thumb = new Geometry("VSlThumb", thumbBox);
        Material thm = new Material(am, "Common/MatDefs/Misc/Unshaded.j3md");
        thm.setColor("Color", TurboSnake.ACCENT2); thumb.setMaterial(thm); parent.attachChild(thumb);
        refreshVisuals();
    }

    private void refreshVisuals() {
        float fillW = Math.max(0.001f, value * trackW);
        fill.setLocalScale(fillW, 1f, 1f);
        fill.setLocalTranslation(left + fillW/2f, cy, z+0.1f);
        thumb.setLocalTranslation(left + value * trackW, cy, z+0.2f);
    }

    void updateHover(float mx, float my) {
        float thumbX = left + value * trackW;
        hovered = mx >= thumbX-THUMB_W && mx <= thumbX+THUMB_W && my >= cy-THUMB_H && my <= cy+THUMB_H;
        thumb.getMaterial().setColor("Color", hovered ? TurboSnake.ACCENT : TurboSnake.ACCENT2);
    }

    boolean onClick(float mx, float my) {
        if (mx >= left-THUMB_W && mx <= left+trackW+THUMB_W && my >= cy-THUMB_H && my <= cy+THUMB_H) {
            value = Math.max(0f, Math.min(1f, (mx-left)/trackW));
            refreshVisuals(); return true;
        }
        return false;
    }

    void setValue(float v) { value = Math.max(0f, Math.min(1f, v)); refreshVisuals(); }
    float getValue() { return value; }
    void setVisible(boolean visible) {
        Spatial.CullHint hint = visible ? Spatial.CullHint.Inherit : Spatial.CullHint.Always;
        track.setCullHint(hint);
        fill.setCullHint(hint);
        thumb.setCullHint(hint);
    }
}